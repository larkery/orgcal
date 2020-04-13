package com.larkery.simpleorgsync.lib;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.FileObserver;
import android.os.Handler;
import android.os.IBinder;
import android.preference.PreferenceManager;
import android.support.annotation.Nullable;
import android.support.v4.app.NotificationCompat;
import android.util.Log;

import com.google.common.collect.Sets;
import com.larkery.simpleorgsync.PrefsActivity;
import com.larkery.simpleorgsync.R;
import com.larkery.simpleorgsync.cal.CalSyncAdapter;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public class FileChangeSyncTrigger extends Service {
    private static final String TAG = Application.TAG + "/INOTIFY";

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        ensureWatches();

        final NotificationChannel ch;
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            ch = new NotificationChannel(
                    "com.larkery.orgcalsync",
                    "General Notifications",
                    NotificationManager.IMPORTANCE_MIN
            );


            final NotificationManager mgr = (NotificationManager)
                    getSystemService(NOTIFICATION_SERVICE);
            mgr.createNotificationChannel(ch);

            startForeground(
                    1234,
                    new Notification.Builder(getApplicationContext(),
                            "com.larkery.orgcalsync")
                            .setOngoing(true)
                            .setContentTitle("Watching calendar files")
                            .setSmallIcon(R.drawable.ic_org_mode_unicorn)
                            .build()
            );
        }
        return Service.START_STICKY;
    }

    @Override
    public void onDestroy() {
        stopWatches();
        super.onDestroy();
    }

    final Runnable fire = new Runnable() {
        @Override
        public void run() {
            Log.i(TAG, "Syncing due to inotify events");
            handler.removeCallbacks(this);
            ((Application)getApplication()).requestSync();
            handler.removeCallbacks(this);
        }
    };

    final Handler handler = new Handler();

    final Map<File, FileObserver> observers = new HashMap<>();

    private static String eventString(int event) {
        switch (event) {
            case FileObserver.CREATE: return "CREATE";
            case FileObserver.ACCESS: return "ACCESS";
            case FileObserver.ATTRIB: return "ATTRIB";
            case FileObserver.CLOSE_NOWRITE: return "CLOSE_NOWRITE";
            case FileObserver.CLOSE_WRITE: return "CLOSE_WRITE";
            case FileObserver.DELETE: return "DELETE";
            case FileObserver.DELETE_SELF: return "DELETE_SELF";
            case FileObserver.MODIFY: return "MODIFY";
            case FileObserver.MOVE_SELF: return "MOVE_SELF";
            case FileObserver.MOVED_FROM: return "MOVED_FROM";
            case FileObserver.MOVED_TO: return "MOVED_TO";
            case FileObserver.OPEN: return "OPEN";
            default:return "UNKNOWN(" + event +")";
        }
    }


    private void ensureWatches() {
        final SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(getApplicationContext());
        final String contactsFile = prefs.getString("contacts_file", null);
        final String agendaFiles = prefs.getString("agenda_files", null);

        final Set<File> toWatch = new HashSet<>();
        if (contactsFile != null) {
            final File file = new File(contactsFile);
            if (file.exists()) toWatch.add(file);
        }

        if (agendaFiles != null) {
            CalSyncAdapter.collectOrgFiles(new File(agendaFiles), toWatch);
        }

        final Set<File> missingWatches = Sets.difference(toWatch, observers.keySet());
        final Set<File> extraWatches = Sets.difference(observers.keySet(), toWatch);

        for (File e : extraWatches) {
            FileObserver fo = observers.get(e);
            observers.remove(e);
            fo.stopWatching();
        }

        final int mask = FileObserver.MODIFY | FileObserver.CLOSE_WRITE | FileObserver.CREATE |
                FileObserver.MOVE_SELF | FileObserver.MOVED_FROM | FileObserver.MOVED_TO;
        for (final File m : missingWatches) {
            try {
                FileObserver fo = new FileObserver(m.getCanonicalPath(), mask) {
                    @Override
                    public void onEvent(int event, @Nullable String path) {
                        if (event == FileObserver.MODIFY ||
                                event == FileObserver.CLOSE_WRITE ||
                                event == FileObserver.CREATE ||
                                event == FileObserver.MOVE_SELF) {
                            Log.i(TAG, "Noticed file change: " + m + " "
                                    + eventString(event));

                            handler.removeCallbacks(fire);
                            handler.postDelayed(fire, 6000);
                        }
                    }
                };
                observers.put(m, fo);
                fo.startWatching();
            } catch (IOException e) {
                Log.e(TAG, "Error", e);
            }
        }

        Log.i(TAG, "Watching " +observers.keySet());
    }

    private void stopWatches() {
        Log.i(TAG, "Stopping watches");
        for (final FileObserver fo : observers.values()) {
            fo.stopWatching();
        }
        observers.clear();
    }
}
