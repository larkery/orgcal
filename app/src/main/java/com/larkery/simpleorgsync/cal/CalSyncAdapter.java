package com.larkery.simpleorgsync.cal;

import android.accounts.Account;
import android.accounts.AccountManager;
import android.content.AbstractThreadedSyncAdapter;
import android.content.ContentProviderClient;
import android.content.ContentProviderOperation;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.OperationApplicationException;
import android.content.SharedPreferences;
import android.content.SyncResult;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.RemoteException;
import android.preference.PreferenceManager;
import android.provider.CalendarContract;
import android.provider.CalendarContract.Calendars;
import android.support.annotation.RequiresApi;
import android.support.v4.provider.DocumentFile;
import android.text.TextUtils;

import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.ListMultimap;
import com.google.common.collect.Sets;
import com.google.gson.JsonObject;
import com.larkery.simpleorgsync.cal.parse.Edit;
import com.larkery.simpleorgsync.cal.parse.Heading;
import com.larkery.simpleorgsync.cal.parse.OrgParser;
import com.larkery.simpleorgsync.cal.parse.Timestamp;
import com.larkery.simpleorgsync.lib.JSONPrefs;
import com.larkery.simpleorgsync.lib.Log;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;

public class CalSyncAdapter extends AbstractThreadedSyncAdapter {
    private static final String TAG = "CalSyncAdapter";
    private Timestamp.Type ttype = Timestamp.Type.ACTIVE;
    private TimeZone calTimezone = TimeZone.getDefault();

    public CalSyncAdapter(Context context, boolean autoInitialize) {
        super(context, autoInitialize);
        Log.i(TAG, "Constructed");
    }

    public static DocumentFile docFile(final Context con, final String uri) {
        final Uri _uri = Uri.parse(uri);
        if (_uri.getPath().startsWith("/tree")) {
            return DocumentFile.fromTreeUri(con, _uri);
        } else {
            return DocumentFile.fromSingleUri(con, _uri);
        }
    }

    public static void collectOrgFiles(DocumentFile rootFile,
                                       final Set<DocumentFile> output,
                                       boolean ignoreConflicts) {
        if (rootFile.isDirectory()) {
            for (DocumentFile child : rootFile.listFiles()) {
                collectOrgFiles(child, output, ignoreConflicts);
            }
        } else if (rootFile.isFile() && rootFile.getName().endsWith(".org")
                   && (ignoreConflicts || !rootFile.getName().contains(".sync-conflict"))
        ) {
            output.add(rootFile);
        }
    }

    @Override
    public void onPerformSync(Account account, Bundle extras, String authority,
                              ContentProviderClient provider, SyncResult syncResult) {
        final JSONPrefs prefs = JSONPrefs.fromContext(getContext(), account);
        Log.i(TAG, "Prefs: " + prefs);
        int updatedInOrg = 0;

        final String agendaRoot = prefs.getString("agenda_files", null);

        if (agendaRoot == null) return;

        Log.i(TAG, "Starting sync of "  + agendaRoot);

        final Uri calendarsURI =
                syncAdapterURI(Calendars.CONTENT_URI,
                        account);

        final Uri eventsURI =
                syncAdapterURI(CalendarContract.Events.CONTENT_URI,
                        account);

        // identify / create calendars for all the names
        final Set<DocumentFile> distinctAgendaFiles = new HashSet<>();

        final DocumentFile agendaRootFile = docFile(getContext(), agendaRoot);
        collectOrgFiles(agendaRootFile, distinctAgendaFiles,
                prefs.getBoolean("ignore_syncthing_conflicts", true));
        final Map<DocumentFile, StringBuffer> fileContents = new HashMap<>();
        final ListMultimap<DocumentFile, Heading> headingsByFile = ArrayListMultimap.create();
        final ListMultimap<String, Heading> headingsByCategory = ArrayListMultimap.create();


        // load it all
        for (final DocumentFile df : distinctAgendaFiles) {
            try {
                final long now = System.currentTimeMillis();
                Log.i(TAG, "Parsing " + df.getName());
                final StringBuffer sb = readFile(df.getUri());
                fileContents.put(df, sb);
                String category = df.getName();
                if (category.endsWith(".org")) {
                    category = category.substring(0, category.length() - 4);
                }
                final List<Heading> headings = OrgParser.parse(sb, TimeZone.getTimeZone("Europe/London"), category);
                headingsByFile.putAll(df, headings);
                final long delta = System.currentTimeMillis() - now;
                Log.i(TAG, df.getName() + " contains " + headings.size() + " headings" +
                        ", parsed in " + delta +"ms");
            } catch (IOException ex) {
                Log.e(TAG, "Reading " + df.getName(), ex);
            }
        }

        // identify all the categories that exist and contain relevant stuff
        final Set<String> categories = new HashSet<>();
        for (final Heading h : headingsByFile.values()) {
            if (h.hasTag("ARCHIVE")) continue; // we don't care about these esp.
            for (Timestamp ts : h.getTimestamps()) {
                if (ts.getType() == Timestamp.Type.ACTIVE) {
                    categories.add(h.getCategory());
                    // store relevant headings for sync
                    headingsByCategory.put(h.getCategory(), h);
                    break;
                }
            }
        }

        boolean readOnly = prefs.getBoolean("read_only", false);
        Log.i(TAG, readOnly ? "Read-only mode" : "Read-write mode");
        final Map<String, Long> calendarIDs;

        try {
            calendarIDs = createCalendars(account, calendarsURI, categories, provider, readOnly);
        } catch (RemoteException e) {
            Log.e(TAG, "Unable to create calendars", e);
            return;
        }

        switch (prefs.getString("date_type", "active")) {
            case "deadline":
                ttype = Timestamp.Type.DEADLINE;
                break;
            case "scheduled":
                ttype = Timestamp.Type.SCHEDULED;
                break;
            default:
            case"active":
                ttype = Timestamp.Type.ACTIVE;
        }

        final ArrayList<ContentProviderOperation> operations = new ArrayList<>();
        for (final String category : categories) {
            final long calendarID = calendarIDs.get(category);
            final List<Heading> newHeadings = new ArrayList<>();

            syncCalendar(eventsURI, calendarID,
                    headingsByCategory.get(category), provider,
                    operations, newHeadings, readOnly);

            // I think at this point we need to put the new headings into the files

            boolean foundFile = false;
            for (final DocumentFile df : distinctAgendaFiles) {
                if (df.getName().endsWith(".org")) {
                    final String c = df.getName().substring(0, df.getName().length() - 4);
                    if (c.equals(category)) {
                        headingsByFile.putAll(df, newHeadings);
                        foundFile = true;
                        break;
                    }
                }
            }

            if (!foundFile) {
                // need to put headings in a new file or so

                if (agendaRootFile.isDirectory()) {
                    final DocumentFile categoryFile =
                            agendaRootFile.createFile("text/plain",
                                    category + ".org");
                    headingsByFile.putAll(categoryFile, newHeadings);
                    distinctAgendaFiles.add(categoryFile);
                }
            }
        }

        // now write each file, one at a time
        if (!readOnly) {
            for (final DocumentFile file : distinctAgendaFiles) {
                final List<Heading> fileHeadings = headingsByFile.get(file);
                final List<Edit> edits = new ArrayList<>();
                final StringBuffer appends = new StringBuffer();
                for (final Heading h : fileHeadings) {
                    try {
                        if (h.exists()) {
                            int nedits = edits.size();
                            h.edit(edits);
                            nedits = edits.size() - nedits;
                            if (nedits > 0) Log.i(TAG, h.getHeading() + ": " + nedits + " edits");
                            if (nedits > 0) updatedInOrg++;
                        } else if (!h.exists()) {
                            Log.i(TAG, "Appending " + h);
                            h.append(appends);
                            updatedInOrg++;
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "Error with heading" + h, e);
                    }
                }
                Log.i(TAG, edits.size() + " edits to " + file.getName() + ", and " + appends.length() + " to append");
                if (appends.length() == 0 && edits.isEmpty()) continue;

                // now perform the edits and modify/replace the file

                final StringBuffer newContents = Edit.apply(edits, fileContents.get(file));
                if (appends.length() > 0) {
                    newContents.append("\n");
                    newContents.append(appends);
                }
                try {
                    final OutputStreamWriter w = new OutputStreamWriter(
                            getContext().getContentResolver().openOutputStream(
                                    file.getUri()
                            ),
                            StandardCharsets.UTF_8);
                    try {
                        w.write(newContents.toString());
                    } finally {
                        w.close();
                    }
                } catch (IOException e) {
                    Log.e(TAG, "Error updating " + file.getName(), e);
                }
            }
        } else {
            Log.i(TAG, "Skip any writes, because we are in read-only mode");
        }

        try {
            Log.i(TAG, "Applying " + operations.size() + " operations");
            provider.applyBatch(operations);
            Log.i(TAG, "Done");
        } catch (RemoteException e) {
            Log.e(TAG, "Error applying operations", e);
        } catch (OperationApplicationException e) {
            Log.e(TAG, "Error applying operations", e);
        }
    }

    private SharedPreferences getMPSharedPreferences(Context context) {
        return context.getSharedPreferences(
                context.getPackageName() + "_preferences",
                Context.MODE_PRIVATE | Context.MODE_MULTI_PROCESS);
    }

    private Map<String,Long> createCalendars(Account account,
                                             Uri calendarsURI,
                                             Set<String> categories,
                                             ContentProviderClient provider,
                                             boolean readOnly) throws RemoteException {
        final Map<String, Long> result = new HashMap<>();

        try (final Cursor knownCalendars = provider.query(
                calendarsURI,
                new String[]{
                        Calendars._ID,
                        Calendars.NAME
                },
                null, null, null)) {


            final Set<String> missingCategories = new HashSet<>(categories);
            final Set<Long> extraCalendars = new HashSet<>();
            while (knownCalendars.moveToNext()) {
                final long id = knownCalendars.getLong(0);
                final String name = knownCalendars.getString(1);
                missingCategories.remove(name);
                if (!categories.contains(name)) {
                    extraCalendars.add(id);
                } else {
                    result.put(name, id);
                }
            }

            provider.delete(
                    calendarsURI,
                    Calendars._ID + " IN " + "(" +
                            TextUtils.join(", ", extraCalendars)
                            + ")", null
            );
            {
                final ContentValues readOnlyValues = new ContentValues();
                readOnlyValues.put(Calendars.CALENDAR_ACCESS_LEVEL, readOnly
                        ? Calendars.CAL_ACCESS_READ : Calendars.CAL_ACCESS_OWNER);
                provider.update(calendarsURI,
                        readOnlyValues,
                        Calendars.ACCOUNT_NAME + "= ? AND "+ Calendars.ACCOUNT_TYPE + "= ?",
                        new String[] {account.type, account.name}
                );
            }

            for (final String s : missingCategories) {
                final ContentValues values = new ContentValues();

                values.put(Calendars.ACCOUNT_TYPE, account.type);
                values.put(Calendars.ACCOUNT_NAME, account.name);
                values.put(Calendars.VISIBLE, 1);
                values.put(Calendars.SYNC_EVENTS, 1);
                values.put(Calendars.NAME, s);
                values.put(Calendars.CALENDAR_COLOR, s.hashCode());
                values.put(Calendars.CALENDAR_DISPLAY_NAME, properCase(s));
                values.put(Calendars.OWNER_ACCOUNT, account.name);
                values.put(Calendars.CALENDAR_ACCESS_LEVEL, readOnly
                        ? Calendars.CAL_ACCESS_READ : Calendars.CAL_ACCESS_OWNER);
                values.put(Calendars.CALENDAR_TIME_ZONE, TimeZone.getDefault().getID());

                result.put(s,
                        ContentUris.parseId(provider.insert(calendarsURI, values)));
            }
        }

        return result;
    }

    private String properCase(String category) {
        final StringBuffer out = new StringBuffer();
        for (String s : category.split("[\\s-_]+")) {
            if (s.isEmpty()) continue;
            if (out.length() > 0) out.append(' ');
            out.append(Character.toUpperCase(s.charAt(0)));
            out.append(s.substring(1));
        }
        return out.toString();
    }

    private Uri syncAdapterURI(Uri contentUri, Account account) {
        return contentUri.buildUpon()
                .appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
                .appendQueryParameter(Calendars.ACCOUNT_TYPE, account.type)
                .appendQueryParameter(Calendars.ACCOUNT_NAME, account.name)
                .build();
    }

    private static final long MAX_FILE = 1024 * 1024 * 1024;

    private StringBuffer readFile(Uri file) throws IOException {
        try (final BufferedReader isr = new BufferedReader(new InputStreamReader(
                getContext().getContentResolver().openInputStream(file),
                StandardCharsets.UTF_8
        ))) {
            final StringBuffer sb = new StringBuffer();

            String line;

            while (null != (line = isr.readLine())) {
                sb.append(line);
                sb.append("\n");
            }

            return sb;
        }
    }

    private enum EventsProjection {
        ID(CalendarContract.Events._ID),
        SYNC_ID(CalendarContract.Events._SYNC_ID),
        ORG_HASH(CalendarContract.Events.SYNC_DATA1),
        DIRTY(CalendarContract.Events.DIRTY),
        DELETED(CalendarContract.Events.DELETED),
        TITLE(CalendarContract.Events.TITLE),
        START_TIME(CalendarContract.Events.DTSTART),
        END_TIME(CalendarContract.Events.DTEND),
        DURATION(CalendarContract.Events.DURATION),
        IS_ALL_DAY(CalendarContract.Events.ALL_DAY),
        LOCATION(CalendarContract.Events.EVENT_LOCATION),
        RRULE(CalendarContract.Events.RRULE);

        private final String field;

        EventsProjection(String field) {
            this.field = field;
        }

        public static final String[] PROJECTION;

        static {
            PROJECTION = new String[values().length];
            for (final EventsProjection p : values()) {
                PROJECTION[p.ordinal()] = p.field;
            }
        }
    }

    @RequiresApi(api = Build.VERSION_CODES.N)
    private void syncCalendar(
            Uri eventsUri,
            long calendarID,
            List<Heading> headings,
            ContentProviderClient provider,
            final List<ContentProviderOperation> operations,
            List<Heading> newHeadings,
            boolean readOnly) {
        final Map<String, Heading> orgIDs = new HashMap<>();

        for (final Heading h : headings) {
            orgIDs.put(h.syncID(readOnly), h);
        }

        try (final Cursor query = provider.query(eventsUri, EventsProjection.PROJECTION,
                CalendarContract.Events.CALENDAR_ID + " = ?",
                new String[]{String.valueOf(calendarID)},
                null)) {
            final HashSet<String> calIDs = new HashSet<>();
            while (query.moveToNext()) {
                final String orgID = query.getString(EventsProjection.SYNC_ID.ordinal());

                if (orgID == null || orgID.isEmpty()) {
                    Log.i(TAG, "New heading for " + query.getString(EventsProjection.TITLE.ordinal()));
                    createHeading(eventsUri, query, operations, newHeadings, readOnly);
                } else {
                    calIDs.add(orgID);
                    final Heading heading = orgIDs.get(orgID);

                    if (heading == null) {
                        Log.i(TAG, "Org heading removed: " + orgID + " ("
                                + query.getString(EventsProjection.TITLE.ordinal()) + ")");
                        createDeleteOperation(eventsUri, query, operations);
                    } else {
                        final String oldHash = query.getString(EventsProjection.ORG_HASH.ordinal());
                        final String newHash = String.valueOf(heading.checksum());
                        final boolean changedOnPhone = query.getInt(EventsProjection.DIRTY.ordinal()) == 1;
                        final boolean changedInOrg = !oldHash.equals(newHash);
                        final boolean deletedOnPhone = query.getInt(EventsProjection.DELETED.ordinal()) == 1;

                        if (deletedOnPhone) {
                            Log.i(TAG, heading.getHeading() + " deleted on phone");
                            heading.addTag("ARCHIVE");
                            createDeleteOperation(eventsUri, query, operations); // delete for real?
                        } else if (changedInOrg) {
                            Log.i(TAG, heading.getHeading() + " modified in org-mode (" + oldHash + " vs " + newHash);
                            if (changedOnPhone)
                                Log.i(TAG, "Collision for changes to " + heading.getHeading() + ", org-mode wins");
                            createUpdateOperation(query, eventsUri, heading, operations);
                        } else if (changedOnPhone) {
                            Log.i(TAG, heading.getHeading() + " modified on phone");
                            for (final Timestamp ts : heading.getTimestamps()) {
                                if (ts.getType() == ttype) {
                                    setHeadingFromQuery(query, heading, ts);
                                    break;
                                }
                            }
                        } else {
                            //Log.d(TAG, heading.getHeading() + " unchanged");
                        }
                    }
                }
            }
            query.close();
            // new-in-org: create in calendar
            for (final String id : Sets.difference(orgIDs.keySet(), calIDs)) {
                final Heading heading = orgIDs.get(id);
                Log.i(TAG, "Create: " + heading.getHeading());
                createInsertOperation(eventsUri,calendarID,heading, operations, readOnly);
            }
        } catch (RemoteException e) {
            Log.e(TAG, "Error querying events from calendar ", e);
        }
    }

    private ContentProviderOperation.Builder copy(ContentProviderOperation.Builder builder, final Heading heading) {
        for (final Timestamp ts : heading.getTimestamps()) {
            if (ts.getType() == ttype) {
                builder = builder
                        .withValue(CalendarContract.Events.SYNC_DATA1, String.valueOf(heading.checksum()))
                        .withValue(CalendarContract.Events.TITLE, heading.getTitle())
                        .withValue(CalendarContract.Events.DTSTART, ts.getStartTime())
                        .withValue(CalendarContract.Events.EVENT_TIMEZONE, ts.isAllDay() ? "UTC" : calTimezone.getID())
                        .withValue(CalendarContract.Events.ALL_DAY, ts.isAllDay() ? 1 : 0)
                        .withValue(CalendarContract.Events.DIRTY, 0)
                ;

                if (ts.isRepeating()) {
                    builder = builder
                            .withValue(CalendarContract.Events.DURATION, ts.getDuration())
                            .withValue(CalendarContract.Events.DTEND, null)
                            .withValue(CalendarContract.Events.RRULE, ts.getRRULE());
                } else {
                    builder = builder
                            .withValue(CalendarContract.Events.DTEND, ts.getEndTime())
                            .withValue(CalendarContract.Events.RRULE, null)
                            .withValue(CalendarContract.Events.DURATION, null);
                }

                if (heading.hasProperty("LOCATION")) {
                    builder = builder
                            .withValue(CalendarContract.Events.EVENT_LOCATION, heading.getProperty("LOCATION"));
                }

                return builder;
            }
        }
        return builder;
    }

    private void createUpdateOperation(Cursor query, Uri calURI, Heading heading, List<ContentProviderOperation> operations) {
        final int localID = query.getInt(EventsProjection.ID.ordinal());
        final ContentProviderOperation update =
                copy(ContentProviderOperation.newUpdate(calURI), heading)
                        .withSelection(
                                EventsProjection.ID.field + "= ?",
                                new String[]{String.valueOf(localID)}
                        ).build();

        operations.add(update);
    }

    private void setHeadingFromQuery(Cursor query, Heading heading, Timestamp ts) {
        // change the text
        heading.setHeading(query.getString(EventsProjection.TITLE.ordinal()));
        // change the timestamp
        ts.setStartTime(query.getLong(EventsProjection.START_TIME.ordinal()));
        ts.setAllDay(query.getInt(EventsProjection.IS_ALL_DAY.ordinal()) == 1);
        final String rrule = query.getString(EventsProjection.RRULE.ordinal());
        ts.setRRule(rrule);
        if (rrule == null || rrule.isEmpty()) {
            ts.setEndTime(query.getLong(EventsProjection.END_TIME.ordinal()));
        } else {
            // have to do this after setting start time!
            ts.setEndTimeFromDuration(query.getString(EventsProjection.DURATION.ordinal()));
        }
        final String location = query.getString(EventsProjection.LOCATION.ordinal());
        if (location != null && !location.isEmpty()) {
            heading.setProperty("LOCATION", location);
        }
    }

    private void createDeleteOperation(Uri calURI, Cursor query, List<ContentProviderOperation> operations) {
        final int idToDelete = query.getInt(EventsProjection.ID.ordinal());
        final ContentProviderOperation del =
                ContentProviderOperation.newDelete(calURI)
                        .withSelection(CalendarContract.Events._ID + " = ?",
                                new String[]{String.valueOf(idToDelete)})
                        .build();
        operations.add(del);
    }

    @RequiresApi(api = Build.VERSION_CODES.N)
    private void createHeading(final Uri calURI,
                               Cursor query,
                               List<ContentProviderOperation> operations,
                               List<Heading> newHeadings,
                               boolean readOnly) {

        final Heading h = new Heading(
                query.getString(EventsProjection.TITLE.ordinal()),
                Collections.<String>emptySet(),
                "Unknown"
        );

        final long dtStart = query.getLong(EventsProjection.START_TIME.ordinal());
        final long dtEnd = query.getLong(EventsProjection.END_TIME.ordinal());
        final boolean allDay = query.getInt(EventsProjection.IS_ALL_DAY.ordinal()) != 0;
        final long duration = query.getLong(EventsProjection.DURATION.ordinal());
        final String rrule = query.getString(EventsProjection.RRULE.ordinal());

        Log.i(TAG,
                h.getHeading() + ": " + dtStart + "->" + dtEnd + ", " + duration + " " + allDay + ", " + rrule
        );


        h.addTimestamp(
                new Timestamp(
                        calTimezone,
                        dtStart,
                        dtEnd,
                        allDay,
                        ttype,
                        rrule
                ));


        newHeadings.add(h);

        final int localID = query.getInt(EventsProjection.ID.ordinal());
        final ContentProviderOperation update =
                ContentProviderOperation.newUpdate(calURI)
                        .withSelection(
                                EventsProjection.ID.field + "= ?",
                                new String[]{String.valueOf(localID)}
                        )
                        .withValue(EventsProjection.SYNC_ID.field, h.syncID(readOnly))
                        .withValue(EventsProjection.ORG_HASH.field, h.checksum())
                        .withValue(EventsProjection.DIRTY.field, 0)
                        .build();

        operations.add(update);
    }

    private void createInsertOperation(Uri calURI,
                                       long calendarId,
                                       Heading heading,
                                       List<ContentProviderOperation> out,
                                       boolean readOnly) {
        final ContentProviderOperation.Builder create =
                ContentProviderOperation.newInsert(calURI)
                        .withValue(CalendarContract.Events.CALENDAR_ID, calendarId)
                        .withValue(CalendarContract.Events._SYNC_ID, heading.syncID(readOnly));

        out.add(copy(create, heading).build());

        return;
    }
}
