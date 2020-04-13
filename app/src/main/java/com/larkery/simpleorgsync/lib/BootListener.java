package com.larkery.simpleorgsync.lib;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.preference.PreferenceManager;

public class BootListener extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(context);
        boolean inotify = preferences.getBoolean("inotify", false);
        Intent i = new Intent(context, FileChangeSyncTrigger.class);
        if (inotify) {
            context.startService(i);
        }
    }
}
