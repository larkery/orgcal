package com.larkery.simpleorgsync.cal;

import android.accounts.Account;
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
import android.os.Bundle;
import android.os.RemoteException;
import android.preference.PreferenceManager;
import android.provider.CalendarContract;
import android.provider.CalendarContract.Calendars;
import android.text.TextUtils;
import android.util.Log;

import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.ListMultimap;
import com.google.common.collect.Sets;
import com.larkery.simpleorgsync.cal.parse.Edit;
import com.larkery.simpleorgsync.cal.parse.Heading;
import com.larkery.simpleorgsync.cal.parse.OrgParser;
import com.larkery.simpleorgsync.cal.parse.Timestamp;
import com.larkery.simpleorgsync.lib.Application;
import com.larkery.simpleorgsync.lib.FileUtils;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileFilter;
import java.io.FileInputStream;
import java.io.FileOutputStream;
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
    private static final String TAG = Application.TAG + "/CALSYNC";
    private Timestamp.Type ttype = Timestamp.Type.ACTIVE;
    private TimeZone calTimezone = TimeZone.getDefault();

    public CalSyncAdapter(Context context, boolean autoInitialize) {
        super(context, autoInitialize);
    }

    public static void collectOrgFiles(final File rootFile, final Set<File> output) {
        if (rootFile.isDirectory()) {
            final File[] sub = rootFile.listFiles(
                    new FileFilter() {
                        @Override
                        public boolean accept(File pathname) {
                            return pathname.isDirectory() ||
                                    (pathname.isFile() && pathname.getName().endsWith(".org"));
                        }
                    });
            for (final File f : sub) {
                if (f.isDirectory()) {
                    collectOrgFiles(f, output);
                } else if (f.isFile()) {
                    output.add(f);
                }
            }
        } else if (rootFile.isFile() && rootFile.getName().endsWith(".org")) {
            output.add(rootFile);
        }
    }

    @Override
    public void onPerformSync(Account account, Bundle extras, String authority,
                              ContentProviderClient provider, SyncResult syncResult) {
        final SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(getContext());
        final String agendaRoot = prefs.getString("agenda_files", null);

        Log.i(TAG,"Perform sync " + agendaRoot);

        if (agendaRoot == null) return;

        Log.i(TAG, "Starting sync of "  + agendaRoot);

        final Uri calendarsURI =
                syncAdapterURI(Calendars.CONTENT_URI,
                        account);

        final Uri eventsURI =
                syncAdapterURI(CalendarContract.Events.CONTENT_URI,
                        account);

        // identify / create calendars for all the names
        final Set<File> distinctAgendaFiles = new HashSet<>();

        final File agendaRootFile = new File(agendaRoot);

        collectOrgFiles(agendaRootFile, distinctAgendaFiles);
        final Map<File, StringBuffer> fileContents = new HashMap<>();
        final ListMultimap<File, Heading> headingsByFile = ArrayListMultimap.create();
        final ListMultimap<String, Heading> headingsByCategory = ArrayListMultimap.create();

        // load it all
        for (final File f : distinctAgendaFiles) {
            try {
                Log.i(TAG, "Parsing" + f);
                final StringBuffer sb = readFile(f);
                fileContents.put(f, sb);
                String category = f.getName();
                if (category.endsWith(".org")) {
                    category = category.substring(0, category.length() - 4);
                }
                final List<Heading> headings = OrgParser.parse(sb, TimeZone.getTimeZone("Europe/London"), category);
                headingsByFile.putAll(f, headings);
                Log.i(TAG, f + " contains " + headings.size() + " headings");
            } catch (IOException ex) {
                Log.e(TAG, "Reading " + f, ex);
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

        final Map<String, Long> calendarIDs;
        try {
            calendarIDs = createCalendars(account, calendarsURI, categories, provider);
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
                    operations, newHeadings);

            // I think at this point we need to put the new headings into the files

            boolean foundFile = false;
            for (final File f : distinctAgendaFiles) {
                if (f.getName().endsWith(".org")) {
                    final String c = f.getName().substring(0, f.getName().length() - 4);
                    if (c.equals(category)) {
                        headingsByFile.putAll(f, newHeadings);
                        foundFile = true;
                        break;
                    }
                }
            }

            if (!foundFile) {
                // need to put headings in a new file or so
                if (agendaRootFile.isDirectory()) {
                    final File categoryFile =
                            new File(agendaRootFile, category + ".org");
                    headingsByFile.putAll(categoryFile, newHeadings);
                    distinctAgendaFiles.add(categoryFile);
                }
            }
        }

        // now write each file, one at a time
        for (final File file : distinctAgendaFiles) {
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
                    } else if (!h.exists()) {
                        Log.i(TAG, "Appending " + h);
                        h.append(appends);
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Error with heading" + h, e);
                }
            }
            Log.i(TAG, edits.size() + " edits to " + file + ", and " + appends.length() + " to append");
            if (appends.length() == 0 && edits.isEmpty()) continue;

            // now perform the edits and modify/replace the file

            final StringBuffer newContents = Edit.apply(edits, fileContents.get(file));
            if (appends.length() > 0) {
                newContents.append("\n");
                newContents.append(appends);
            }
            try {
                final OutputStreamWriter w = new OutputStreamWriter(
                        new FileOutputStream(file),
                        StandardCharsets.UTF_8);
                try {
                    w.write(newContents.toString());
                } finally {
                    w.close();
                }
            } catch (IOException e) {
                Log.e(TAG, "Error updating " + file, e);
            }
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

    private Map<String,Long> createCalendars(Account account,
                                             Uri calendarsURI,
                                             Set<String> categories,
                                             ContentProviderClient provider) throws RemoteException {
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
                values.put(Calendars.CALENDAR_ACCESS_LEVEL, Calendars.CAL_ACCESS_OWNER);
                values.put(Calendars.CALENDAR_TIME_ZONE, TimeZone.getDefault().getID());
                values.put(Calendars.SYNC_EVENTS, 1);

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

    private static StringBuffer readFile(File file) throws IOException {
        final long size = file.length();
        if (size < 1 || size > MAX_FILE) {
            Log.w(TAG, file + " has unreasonable size " + size);
            return null;
        } else {
            final StringBuffer sb = new StringBuffer((int) size);

            final BufferedReader isr = new BufferedReader(new InputStreamReader(
                    new FileInputStream(file),
                    StandardCharsets.UTF_8
            ));

            try {
                String line;

                while (null != (line = isr.readLine())) {
                    sb.append(line);
                    sb.append("\n");
                }

                return sb;
            } finally {
                isr.close();
            }
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

    private void syncCalendar(
            Uri eventsUri,
            long calendarID,
            List<Heading> headings,
            ContentProviderClient provider,
            final List<ContentProviderOperation> operations,
            List<Heading> newHeadings) {
        final Map<String, Heading> orgIDs = new HashMap<>();

        for (final Heading h : headings) {
            orgIDs.put(h.ensureID(), h);
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
                    createHeading(eventsUri, query, operations, newHeadings);
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
                createInsertOperation(eventsUri,calendarID,heading, operations);
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
        ts.setEndTime(query.getLong(EventsProjection.END_TIME.ordinal()));
        ts.setAllDay(query.getInt(EventsProjection.IS_ALL_DAY.ordinal()) == 1);
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

    private void createHeading(final Uri calURI,
                               Cursor query,
                               List<ContentProviderOperation> operations,
                               List<Heading> newHeadings) {

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
                        ttype
                ));


        newHeadings.add(h);

        final int localID = query.getInt(EventsProjection.ID.ordinal());
        final ContentProviderOperation update =
                ContentProviderOperation.newUpdate(calURI)
                        .withSelection(
                                EventsProjection.ID.field + "= ?",
                                new String[]{String.valueOf(localID)}
                        )
                        .withValue(EventsProjection.SYNC_ID.field, h.ensureID())
                        .withValue(EventsProjection.ORG_HASH.field, h.checksum())
                        .withValue(EventsProjection.DIRTY.field, 0)
                        .build();

        operations.add(update);
    }

    private void createInsertOperation(Uri calURI, long calendarId, Heading heading, List<ContentProviderOperation> out) {
        final ContentProviderOperation.Builder create =
                ContentProviderOperation.newInsert(calURI)
                        .withValue(CalendarContract.Events.CALENDAR_ID, calendarId)
                        .withValue(CalendarContract.Events._SYNC_ID, heading.ensureID());

        out.add(copy(create, heading).build());

        return;
    }
}
