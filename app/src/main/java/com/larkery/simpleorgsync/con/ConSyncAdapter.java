package com.larkery.simpleorgsync.con;

import android.accounts.Account;
import android.accounts.AccountManager;
import android.content.AbstractThreadedSyncAdapter;
import android.content.ContentProviderClient;
import android.content.ContentProviderOperation;
import android.content.ContentUris;
import android.content.Context;
import android.content.OperationApplicationException;
import android.content.SharedPreferences;
import android.content.SyncResult;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.RemoteException;
import android.preference.PreferenceManager;
import android.provider.ContactsContract;
import android.provider.ContactsContract.CommonDataKinds.Email;
import android.provider.ContactsContract.CommonDataKinds.Event;
import android.provider.ContactsContract.CommonDataKinds.Note;
import android.provider.ContactsContract.CommonDataKinds.Organization;
import android.provider.ContactsContract.CommonDataKinds.Phone;
import android.provider.ContactsContract.CommonDataKinds.StructuredName;
import android.provider.ContactsContract.CommonDataKinds.StructuredPostal;
import android.provider.ContactsContract.RawContacts;

import com.google.common.base.Objects;
import com.google.common.collect.Sets;
import com.larkery.simpleorgsync.lib.JSONPrefs;
import com.larkery.simpleorgsync.lib.Log;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public class ConSyncAdapter extends AbstractThreadedSyncAdapter {
    static final String TAG = "ConSyncAdapter";
    private Uri rawContactsURI;
    private Uri dataURI;
    private Account account;

    public ConSyncAdapter(Context applicationContext, boolean b) {
        super(applicationContext, b);
    }

    private enum ContactsProjection {
        ID(RawContacts._ID),
        DIRTY(RawContacts.DIRTY),
        DELETED(RawContacts.DELETED),
        UUID(RawContacts.SOURCE_ID),
        CHKSUM(RawContacts.SYNC1)
        ;

        private final String field;

        ContactsProjection(String field) {
            this.field = field;
        }

        public static final String[] PROJECTION;

        static {
            PROJECTION = new String[values().length];
            for (final ContactsProjection p : values()) {
                PROJECTION[p.ordinal()] = p.field;
            }
        }
    }

    private Uri syncAdapterURI(Uri contentUri, Account account) {
        return contentUri.buildUpon()
                .appendQueryParameter(ContactsContract.CALLER_IS_SYNCADAPTER, "true")
                .build();
    }

    @Override
    public void onPerformSync(Account account, Bundle extras, String authority, ContentProviderClient provider, SyncResult syncResult) {
        JSONPrefs prefs = JSONPrefs.fromContext(getContext(), account);

        Log.i(TAG, "Prefs: " + prefs);
        final String contactsFile = prefs.getString("contacts_file", null);
        Log.i(TAG, "Sync Contacts File " + contactsFile);
        if (contactsFile == null) return;

        this.rawContactsURI = syncAdapterURI(RawContacts.CONTENT_URI, account);
        this.dataURI = syncAdapterURI(ContactsContract.Data.CONTENT_URI, account);
        this.account = account;
        Map<String, ContactsJson.Contact> dbContents = Collections.emptyMap();

        try (final BufferedReader reader = new BufferedReader(
                new InputStreamReader(
                        getContext().getContentResolver().openInputStream(Uri.parse(contactsFile))
                )
        )) {
            dbContents = ContactsJson.load(reader);
        } catch (FileNotFoundException e) {
            e.printStackTrace();
        } catch (IOException e) {
            e.printStackTrace();
        }

        final ArrayList<ContentProviderOperation> ops = new ArrayList<>();
        ops.add(ContentProviderOperation.newInsert(
                ContactsContract.Settings.CONTENT_URI)
                .withValue(ContactsContract.Settings.ACCOUNT_NAME, account.name)
                .withValue(ContactsContract.Settings.ACCOUNT_TYPE, account.type)
                .withValue(ContactsContract.Settings.UNGROUPED_VISIBLE, true)
                .build());


            Log.i(TAG, "Read " + dbContents.size() + " entries");
            final Set<String> processedContacts = new HashSet<>();

            final Map<Long, String> groupNames = new HashMap<>();

            boolean changedDatabaseContents = false;
            // iterate on the database contents
            try (Cursor rawContacts = provider.query(
                    rawContactsURI.buildUpon()
                            .appendQueryParameter(ContactsContract.RawContacts.ACCOUNT_TYPE, account.type)
                            .appendQueryParameter(ContactsContract.RawContacts.ACCOUNT_NAME, account.name)
                    .build(),
                    ContactsProjection.PROJECTION,
                    null, null, null)) {

                // process contacts on device
                while (rawContacts.moveToNext()) {
                    final String uuid = rawContacts.getString(ContactsProjection.UUID.ordinal());
                    final long id = rawContacts.getLong(ContactsProjection.ID.ordinal());

                    if (uuid != null) processedContacts.add(uuid);

                    final boolean newOnPhone = uuid == null || uuid.isEmpty();
                    final boolean deletedElsewhere = !newOnPhone && !dbContents.containsKey(uuid);

                    final boolean deletedOnPhone = rawContacts.getInt(ContactsProjection.DELETED.ordinal()) == 1;
                    final boolean modifiedOnPhone = rawContacts.getInt(ContactsProjection.DIRTY.ordinal()) == 1;

                    final String lastChecksum = rawContacts.getString(ContactsProjection.CHKSUM.ordinal());
                    final String curChecksum = dbContents.containsKey(uuid) ? dbContents.get(uuid).checksum() : null;
                    final boolean modifiedElsewhere = !Objects.equal(lastChecksum, curChecksum);

                    if (deletedOnPhone || deletedElsewhere) {
                        Log.i(TAG, "Delete globally " + id + " " + uuid);
                        dbContents.remove(uuid);
                        changedDatabaseContents = true;
                        deleteOnPhone(id, ops);
                    } else if (newOnPhone) {
                        final String u = UUID.randomUUID().toString();
                        Log.i(TAG, "Created on phone " + id + " " + u);
                        final ContactsJson.Contact c = loadFromPhone(provider, id, groupNames);
                        dbContents.put(u, c);
                        changedDatabaseContents = true;
                        setUUIDOnPhone(id, u, c.checksum(), ops);
                        processedContacts.add(u);
                    } else if (modifiedElsewhere && modifiedOnPhone) {
                        Log.i(TAG, "Merge changes in " + id + " " + uuid);
                        final ContactsJson.Contact c = dbContents.get(uuid);
                        c.mergeWith(loadFromPhone(provider, id, groupNames));
                        deleteDataOnPhone(id, ops);
                        updateOnPhone(id, c, ops);
                        setUUIDOnPhone(id, uuid, curChecksum, ops);
                        dbContents.put(uuid, c);
                        changedDatabaseContents = true;
                    } else if (modifiedOnPhone) {
                        Log.i(TAG, "Update from phone " + id + " " + uuid);
                        final ContactsJson.Contact c = loadFromPhone(provider, id, groupNames);
                        dbContents.put(uuid, c);
                        changedDatabaseContents = true;
                        setUUIDOnPhone(id, uuid, c.checksum(), ops);
                    } else if (modifiedElsewhere) {
                        Log.i(TAG, "Update to phone " + id + " " + uuid);
                        deleteDataOnPhone(id, ops);
                        updateOnPhone(id, dbContents.get(uuid), ops);
                    }
                }
            } catch (RemoteException e) {
                e.printStackTrace();
            }

        for (final String missing : Sets.difference(dbContents.keySet(), processedContacts)) {
                Log.i(TAG, "Insert " + missing);
                insertOnPhone(missing, dbContents.get(missing), ops);
            }

        try {
            provider.applyBatch(ops);
        } catch (OperationApplicationException e) {
            e.printStackTrace();
        } catch (RemoteException e) {
            e.printStackTrace();
        }
        if (changedDatabaseContents) {
                try (
                        final OutputStreamWriter w = new OutputStreamWriter(
                                getContext().getContentResolver().openOutputStream(
                                        Uri.parse(contactsFile),"wt"
                                )
                        ,
                        StandardCharsets.UTF_8)) {
                    ContactsJson.save(w, dbContents);
                } catch (FileNotFoundException e) {
                    e.printStackTrace();
                } catch (IOException e) {
                    e.printStackTrace();
                }
        }
    }

    private void updateOnPhone(long id, ContactsJson.Contact m,
                               ArrayList<ContentProviderOperation> ops) {
        insDataRows(m, ops, id, true);
    }

    private void deleteDataOnPhone(long id, ArrayList<ContentProviderOperation> ops) {
        ops.add(ContentProviderOperation.newDelete(ContactsContract.Data.CONTENT_URI)
                .withSelection(ContactsContract.Data.CONTACT_ID + "= ?",
                        new String[] {""+id}
                ).withYieldAllowed(true).build()
        );
    }

    private static <T> T leastNull(T x, T y) {
        if (x != null)  return x;
        return y;
    }

    private void deleteOnPhone(long id, ArrayList<ContentProviderOperation> ops) {
        ops.add(ContentProviderOperation.newDelete(rawContactsURI(id)
                .buildUpon().appendQueryParameter(ContactsContract.CALLER_IS_SYNCADAPTER, "true")
                .build()
        ).build());
    }

    private ContactsJson.Contact loadFromPhone(ContentProviderClient provider, long id, final Map<Long, String> groupNames) throws RemoteException, RemoteException {
        final ContactsJson.Contact out = new ContactsJson.Contact();

        Uri dataURI = rawContactsURI(id);
        dataURI = Uri.withAppendedPath(
                dataURI,
                RawContacts.Entity.CONTENT_DIRECTORY
        );

        try (Cursor dataRows = provider.query(
                dataURI,
                new String[] {
                        RawContacts.Entity.MIMETYPE,
                        RawContacts.Entity.DATA1,
                        RawContacts.Entity.DATA2,
                        RawContacts.Entity.DATA3,
                        RawContacts.Entity.DATA4,
                        RawContacts.Entity.DATA5,
                        RawContacts.Entity.DATA6,
                        RawContacts.Entity.DATA7,
                        RawContacts.Entity.DATA8,
                        RawContacts.Entity.DATA9
                },
                null, null, null)) {

            while (dataRows.moveToNext()) {
                final String key = dataRows.getString(0);
                switch (key) {
                    case Organization.CONTENT_ITEM_TYPE: {
                        final String organisationName = dataRows.getString(1);
                        if (out.organisation == null) {
                            out.organisation = organisationName;
                        }
                        break;
                    }
                    case Note.CONTENT_ITEM_TYPE: {
                        final String note = dataRows.getString(1);
                        if (note != null) out.notes = note;
                        break;
                    }
                    case Event.CONTENT_ITEM_TYPE: {
                        final String date = dataRows.getString(1);
                        final String type;

                        switch (dataRows.getInt(2)) {
                            case Event.TYPE_BIRTHDAY:
                                type = "birthday";
                                out.birthday = date;
                                break;
                            case Event.TYPE_ANNIVERSARY:
                                type = "anniversary";
                                out.anniversary = date;
                                break;
                        }

                        break;
                    }
                    case StructuredName.CONTENT_ITEM_TYPE: {
                        final String displayName = dataRows.getString(1);
                        final String givenName = dataRows.getString(2);
                        final String surname = dataRows.getString(3);
                        final String prefix = dataRows.getString(4);
                        final String middleName = dataRows.getString(5);
                        final String suffix = dataRows.getString(6);

                        out.firstName = givenName;
                        out.middleName = middleName;
                        out.surname = surname;
                        break;
                    }
                    case Email.CONTENT_ITEM_TYPE: {
                        final String address = dataRows.getString(1);
                        final int type = dataRows.getInt(2);
                        String label = dataRows.getString(3);
                        switch(type) {
                            case Email.TYPE_HOME:
                                label = "home";
                                break;
                            case Email.TYPE_WORK:
                                label = "work";
                                break;
                            case Email.TYPE_OTHER:
                                label = "other";
                                break;
                            case Email.TYPE_MOBILE:
                                label = "mobile";
                                break;
                            case Email.TYPE_CUSTOM:
                                if (label == null) label = "custom";
                                break;
                        }

                        out.addEmail(label, address);
                        break;
                    }
                    case Phone.CONTENT_ITEM_TYPE: {
                        final String number = dataRows.getString(1);
                        final int type = dataRows.getInt(2);
                        final String label = dataRows.getString(3);

                        String types = "mobile";
                        switch (type) {
                            case Phone.TYPE_HOME:
                                types = "home";
                                break;
                            case Phone.TYPE_WORK:
                                types = "work";
                                break;
                            case Phone.TYPE_MOBILE:
                                types = "mobile";
                                break;
                            case Phone.TYPE_WORK_MOBILE:
                                types = "work mobile";
                                break;
                            case Phone.TYPE_CUSTOM:
                                types = label;
                                break;
                        }

                        out.addPhone(types, number);
                        break;
                    }
                    case StructuredPostal.CONTENT_ITEM_TYPE: {
                        final String rawText = dataRows.getString(1);
                        final int type = dataRows.getInt(2);
                        String label = dataRows.getString(3);
                        // who cares about structured address anyway

                        switch (type) {
                            case StructuredPostal.TYPE_HOME:
                                label = "home";
                                break;
                            case StructuredPostal.TYPE_WORK:
                                label = "work";
                                break;
                            case StructuredPostal.TYPE_OTHER:
                                if (label == null) label = "other";
                        }

                        out.addAddress(label, rawText);

                        break;
                    }
                    case ContactsContract.CommonDataKinds.GroupMembership.CONTENT_ITEM_TYPE: {
                        final long groupID = dataRows.getLong(1);
                        // find the group name for this
                        String group = groupNames.get(groupID);
                        if (group != null) {
                            out.addGroup(group);
                        }
                        break;
                    }
                    default:
                        Log.i(TAG,
                                "Unhandled content item type: " + key
                        );
                }
            }
        }

        return out;
    }

    private void setUUIDOnPhone(long id, String newUUID, String checksum, ArrayList<ContentProviderOperation> ops) {
        ops.add(ContentProviderOperation.newUpdate(rawContactsURI(id))
                .withValue(RawContacts.SOURCE_ID, newUUID)
                .withValue(RawContacts.DIRTY, 0)
                .withValue(RawContacts.SYNC1, checksum)
                .withYieldAllowed(true)
                .build());
    }

    private Uri rawContactsURI(long id) {
        return ContentUris.withAppendedId(RawContacts.CONTENT_URI, id);
    }

    private void insertOnPhone(String uuid, ContactsJson.Contact e, ArrayList<ContentProviderOperation> ops) {
        int backReference = ops.size();
        ops.add(ContentProviderOperation.newInsert(rawContactsURI)
                .withValue(RawContacts.SOURCE_ID, uuid)
                .withValue(RawContacts.ACCOUNT_NAME, account.name)
                .withValue(RawContacts.ACCOUNT_TYPE, account.type)
                .withValue(RawContacts.RAW_CONTACT_IS_READ_ONLY, false)
                .withValue(RawContacts.SYNC1, e.checksum())
                .withYieldAllowed(true)
                .build());

        insDataRows(e, ops, backReference, false);
    }

    private ContentProviderOperation.Builder insRow(long idOrBackRef, boolean isId, String mimeType) {
        if (isId) {
            return ContentProviderOperation.newInsert(dataURI)
                    .withValue(ContactsContract.Data.RAW_CONTACT_ID, idOrBackRef)
                    .withValue(ContactsContract.Data.MIMETYPE, mimeType);
        } else {
            return ContentProviderOperation.newInsert(dataURI)
                    .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, (int)idOrBackRef)
                    .withValue(ContactsContract.Data.MIMETYPE, mimeType);
        }
    }

    private void insDataRows(ContactsJson.Contact e,
                             ArrayList<ContentProviderOperation> ops,
                             long idOrBackRef, boolean isID
                             ) {
        {
            ContentProviderOperation.Builder operation =
                    insRow(idOrBackRef, isID, StructuredName.CONTENT_ITEM_TYPE);

            if (e.firstName != null) {
                operation = operation.withValue(
                        StructuredName.GIVEN_NAME,
                        e.firstName);
            }
            if (e.middleName != null) {
                operation = operation.withValue(
                        StructuredName.MIDDLE_NAME,
                        e.middleName
                );
            }
            if (e.surname != null) {
                operation = operation.withValue(
                        StructuredName.FAMILY_NAME,
                        e.surname
                );
            }
            ops.add(operation.build());
        }

        if (e.organisation != null) {
            ops.add(insRow(idOrBackRef, isID, Organization.CONTENT_ITEM_TYPE)
                    .withValue(Organization.COMPANY, e.organisation).build()
            );
        }

        if (e.birthday != null) {
            ops.add(insRow(idOrBackRef, isID, Event.CONTENT_ITEM_TYPE)
                    .withValue(Event.TYPE, Event.TYPE_BIRTHDAY)
                    .withValue(Event.START_DATE, e.birthday)
                    .build()
            );
        }
        if (e.anniversary != null) {
            ops.add(insRow(idOrBackRef, isID, Event.CONTENT_ITEM_TYPE)
                    .withValue(Event.TYPE, Event.TYPE_ANNIVERSARY)
                    .withValue(Event.START_DATE, e.anniversary)
                    .build()
            );
        }
        if (e.notes != null) {
            ops.add(insRow(idOrBackRef, isID, Note.CONTENT_ITEM_TYPE)
                    .withValue(Note.NOTE, e.notes)
                    .build()
            );
        }

        if (e.address != null)
        for (ContactsJson.Tuple a : e.address) {
            int typeCode;
            switch (a.lbl) {
                case "home":
                    typeCode = StructuredPostal.TYPE_HOME;
                    break;
                case "work":
                    typeCode = StructuredPostal.TYPE_WORK;
                    break;
                default:
                    typeCode = StructuredPostal.TYPE_OTHER;
                    break;
            }
            ops.add(insRow(idOrBackRef, isID, StructuredPostal.CONTENT_ITEM_TYPE)
                    .withValue(StructuredPostal.TYPE, typeCode)
                    .withValue(StructuredPostal.FORMATTED_ADDRESS, a.val)
                    .build()
            );
        }

        if (e.phone != null)
        for (ContactsJson.Tuple p : e.phone) {
            int typeCode;
            switch (p.lbl) {
                case "mobile":
                    typeCode = Phone.TYPE_MOBILE;
                    break;
                case "home":
                    typeCode = Phone.TYPE_HOME;
                    break;
                case "work mobile":
                    typeCode = Phone.TYPE_WORK_MOBILE;
                    break;
                case "work":
                    typeCode = Phone.TYPE_WORK;
                    break;
                default:
                    typeCode = Phone.TYPE_CUSTOM;
                    break;
            }
            ops.add(insRow(idOrBackRef, isID, Phone.CONTENT_ITEM_TYPE)
                    .withValue(Phone.NUMBER, p.val)
                    .withValue(Phone.TYPE, typeCode)
                    .withValue(Phone.LABEL, p.lbl)
                    .build());
        }

        if (e.email != null)
            for (final ContactsJson.Tuple email : e.email) {
            int type = Email.TYPE_CUSTOM;
                switch(email.lbl) {
                    case "home":
                        type = Email.TYPE_HOME;
                        break;
                    case "work":
                        type = Email.TYPE_WORK;
                        break;
                    case "other":
                        type = Email.TYPE_OTHER;
                        break;
                    case "mobile":
                        type = Email.TYPE_MOBILE;
                        break;
                    default:
                        type = Email.TYPE_CUSTOM;
                }
                ops.add(insRow(idOrBackRef, isID, Email.CONTENT_ITEM_TYPE)
                    .withValue(Email.ADDRESS, email.val)
                    .withValue(Email.TYPE, type)
                    .withValue(Email.LABEL, email.lbl == null ? "custom" : email.lbl)
                    .build()
            );
        }
    }
}
