package com.larkery.simpleorgsync.lib;

import android.accounts.Account;
import android.accounts.AccountManager;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.provider.CalendarContract;
import android.provider.ContactsContract;
import android.support.v4.app.ShareCompat;

import com.larkery.simpleorgsync.R;
import com.larkery.simpleorgsync.cal.CalSyncService;
import com.larkery.simpleorgsync.con.ConSyncService;

import org.json.JSONException;
import org.json.JSONObject;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Map;

public class Application extends android.app.Application implements SharedPreferences.OnSharedPreferenceChangeListener {
    private final static String TAG = "Application";
    private AccountManager accountManager;
    private Account account;
    @Override
    public void onCreate() {
        super.onCreate();
        this.accountManager = (AccountManager) getApplicationContext().getSystemService(Context.ACCOUNT_SERVICE);
        account = new Account("Org Agenda", getString(R.string.account_type));

        final SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(getApplicationContext());
        preferences.registerOnSharedPreferenceChangeListener(this);

        FileJobService.register(getApplicationContext());

        transmitPreferences(PreferenceManager.getDefaultSharedPreferences(getApplicationContext()));
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

    public void requestSync(final String cause) {
        informSystemSyncable();

        transmitPreferences(PreferenceManager.getDefaultSharedPreferences(getApplicationContext()));

        boolean contacts = isContactsSyncable();
        boolean calendar = isCalendarSyncable();

        if (contacts) {
            Log.i(TAG, "Request contacts sync due to " + cause);
            Bundle settingsBundle = new Bundle();
            settingsBundle.putBoolean(ContentResolver.SYNC_EXTRAS_MANUAL, true);
            settingsBundle.putBoolean(ContentResolver.SYNC_EXTRAS_EXPEDITED, true);
            ContentResolver.requestSync(getAccount(), ContactsContract.AUTHORITY, settingsBundle);
        }

        if (calendar) {
            Log.i(TAG, "Request calendar sync due to " + cause);
            Bundle settingsBundle = new Bundle();
            settingsBundle.putBoolean(ContentResolver.SYNC_EXTRAS_MANUAL, true);
            settingsBundle.putBoolean(ContentResolver.SYNC_EXTRAS_EXPEDITED, true);
            ContentResolver.requestSync(getAccount(), CalendarContract.AUTHORITY, settingsBundle);
        }
    }

    public long getLastSyncTime() {
        long result = 0;
        try {
            Method getSyncStatus = ContentResolver.class.getMethod(
                    "getSyncStatus", Account.class, String.class);
            Account mAccount = getAccount();

            if (mAccount != null) {
                Object status = getSyncStatus.invoke(null, mAccount, CalendarContract.AUTHORITY);
                Class<?> statusClass = Class
                        .forName("android.content.SyncStatusInfo");
                boolean isStatusObject = statusClass.isInstance(status);
                if (isStatusObject) {
                    Field successTime = statusClass.getField("lastSuccessTime");
                    result = successTime.getLong(status);
                }
            }
        } catch (NoSuchMethodException e) {

        } catch (IllegalAccessException e) {

        } catch (InvocationTargetException e) {

        } catch (IllegalArgumentException e) {

        } catch (ClassNotFoundException e) {

        } catch (NoSuchFieldException e) {

        } catch (NullPointerException e) {

        }
        return result;
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

    void transmitPreferences(SharedPreferences sharedPreferences) {
        final JSONObject obj = new JSONObject();
        for (final Map.Entry<String, ?> e : sharedPreferences.getAll().entrySet()) {
            try {
                obj.put(e.getKey(), (Object)e.getValue());
            } catch (JSONException jsonException) {
                jsonException.printStackTrace();
            }
        }
        getApplicationContext().getSystemService(AccountManager.class)
                .setUserData(getAccount(), "prefs", obj.toString());
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String s) {
        final String cf = sharedPreferences.getString("contacts_file", null);
        final String af = sharedPreferences.getString("agenda_files", null);
        Log.i(TAG, "Pref change " + cf + " " + af);
        transmitPreferences(sharedPreferences);
        switch (s) {
            case "contacts_file":
            case "agenda_files":
            case "date_type":
            case "ignore_syncthing_conflicts":
            case "read_only": {
                requestSync("a preference was changed: " + s);
            }
            break;
            case "inotify":
                FileJobService.register(getApplicationContext());
                break;
            case "sync_frequency": {
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
