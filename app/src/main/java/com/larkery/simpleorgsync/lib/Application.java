package com.larkery.simpleorgsync.lib;

import android.accounts.Account;
import android.accounts.AccountManager;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.provider.CalendarContract;
import android.provider.ContactsContract;
import android.util.Log;

import com.larkery.simpleorgsync.R;

import java.util.Calendar;

public class Application extends android.app.Application implements SharedPreferences.OnSharedPreferenceChangeListener {
    public static final String TAG = "ORGSYNC";
    private AccountManager accountManager;
    private Account account;
    @Override
    public void onCreate() {
        super.onCreate();
        this.accountManager = (AccountManager) getApplicationContext().getSystemService(Context.ACCOUNT_SERVICE);
        account =
                new Account("Org Agenda",
                        getString(R.string.account_type));

        final SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(getApplicationContext());
        preferences.registerOnSharedPreferenceChangeListener(this);

        boolean inotify = preferences.getBoolean("inotify", false);
        Intent i = new Intent(getApplicationContext(), FileChangeSyncTrigger.class);
        if (inotify) {
            getApplicationContext().startService(i);
        }
    }

    public Account getAccount() {
        accountManager.addAccountExplicitly(account, null, null);
        return account;
    }

    public void deleteAccount() {
        accountManager.removeAccountExplicitly(account);
    }

    private void informSystemSyncable() {
        boolean contacts = isContactsSyncable();
        boolean calendar = isCalendarSyncable();

        if (!(contacts || calendar)) {
            deleteAccount();
        } else {
            final Account a = getAccount();
            ContentResolver.setIsSyncable(a, ContactsContract.AUTHORITY, contacts ? 1 : 0);
            ContentResolver.setSyncAutomatically(a, ContactsContract.AUTHORITY, contacts);
            ContentResolver.setIsSyncable(a, CalendarContract.AUTHORITY, calendar ? 1 : 0);
            ContentResolver.setSyncAutomatically(a, CalendarContract.AUTHORITY, calendar);
        }
    }

    public void requestSync() {
        informSystemSyncable();
        boolean contacts = isContactsSyncable();
        boolean calendar = isCalendarSyncable();

        if (contacts) {
            Log.i(TAG, "Request contacts sync");
            Bundle settingsBundle = new Bundle();
            settingsBundle.putBoolean(ContentResolver.SYNC_EXTRAS_MANUAL, true);
            settingsBundle.putBoolean(ContentResolver.SYNC_EXTRAS_EXPEDITED, true);
            ContentResolver.requestSync(getAccount(), ContactsContract.AUTHORITY, settingsBundle);
        }

        if (calendar) {
            Log.i(TAG, "Request calendar sync");
            Bundle settingsBundle = new Bundle();
            settingsBundle.putBoolean(ContentResolver.SYNC_EXTRAS_MANUAL, true);
            settingsBundle.putBoolean(ContentResolver.SYNC_EXTRAS_EXPEDITED, true);
            ContentResolver.requestSync(getAccount(), CalendarContract.AUTHORITY, settingsBundle);
        }
    }


    private boolean isContactsSyncable() {
        return
                PreferenceManager.getDefaultSharedPreferences(getApplicationContext())
                .getString("contacts_file", null) != null;
    }

    private boolean isCalendarSyncable() {
        return
                PreferenceManager.getDefaultSharedPreferences(getApplicationContext())
                        .getString("agenda_files", null) != null;
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String s) {
        final String cf = sharedPreferences.getString("contacts_file", null);
        final String af = sharedPreferences.getString("agenda_files", null);
        Log.i(TAG, "Pref change " + cf + " " + af);
        switch (s) {
            case "contacts_file":
            case "agenda_files":
            case "date_type":
            case "ignore_syncthing_conflicts":
            {
               requestSync();
            }
                break;
            case "inotify":
                boolean inotify = sharedPreferences.getBoolean("inotify", false);
                Intent i = new Intent(getApplicationContext(), FileChangeSyncTrigger.class);
                if (inotify) {
                    getApplicationContext().startService(i);
                } else {
                    getApplicationContext().stopService(i);
                }
                break;
            case "sync_frequency":
            {
                int syncFrequency = Integer.parseInt(sharedPreferences.getString("sync_frequency", "-1"));
                if (af != null || cf != null) {
                    if (af != null) {
                        if (syncFrequency > 0)
                            ContentResolver.addPeriodicSync(
                                    getAccount(),
                                    CalendarContract.AUTHORITY,
                                    new Bundle(),
                                    syncFrequency
                            );
                        else {
                            ContentResolver.removePeriodicSync(getAccount(),
                                    CalendarContract.AUTHORITY,
                                    new Bundle());
                        }
                    }

                    if (cf != null) {
                        if (syncFrequency > 0) {
                            ContentResolver.addPeriodicSync(
                                    getAccount(),
                                    ContactsContract.AUTHORITY,
                                    new Bundle(),
                                    syncFrequency
                            );
                        } else {
                            ContentResolver.removePeriodicSync(getAccount(),
                                    ContactsContract.AUTHORITY,
                                    new Bundle());
                        }
                    }
                }
            }
                break;
        }
    }
}
