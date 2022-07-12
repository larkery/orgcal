package com.larkery.simpleorgsync.lib;

import android.app.Notification;
import android.app.Service;
import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.preference.PreferenceManager;
import android.provider.MediaStore;
import android.support.v4.app.NotificationManagerCompat;
import android.util.Log;
import android.widget.Toast;

import com.larkery.simpleorgsync.R;
import com.larkery.simpleorgsync.cal.CalSyncAdapter;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class FileJobService extends JobService {
    private static final String TAG = "ORGSYNC/FILEJOBSERVICE";

    public FileJobService() {
    }

    public static final int JOB_ID=123;

    public static void register(Context context) {
        final SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(context);
        boolean inotify = preferences.getBoolean("inotify", false);

        JobScheduler scheduler = context.getSystemService(JobScheduler.class);
        if (!inotify) {
            Log.i(TAG, "Stopping listening for file changes");
            List<JobInfo> j = scheduler.getAllPendingJobs();
            if (j != null) {
                for (JobInfo ji : j) {
                    scheduler.cancel(ji.getId());
                }
            }
        } else {
            JobInfo.Builder b = new JobInfo.Builder(JOB_ID,
                    new ComponentName(context, FileJobService.class.getName()));
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                final ContentResolver cr = context.getContentResolver();

                final SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
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

                final ArrayList<String> paths = new ArrayList<>();
                StringBuffer sb = new StringBuffer();
                for (File f : toWatch) {
                    if (sb.length() > 0) sb.append(", ");
                    sb.append("?");
                    try {
                        paths.add(f.getCanonicalPath());
                    } catch (IOException e) {}
                }

                int i = 0;
                try (Cursor files = cr.query(MediaStore.Files.getContentUri("external"),
                        new String[]{MediaStore.Files.FileColumns._ID},
                        "_DATA IN (" + sb.toString() + ")",
                        paths.toArray(new String[]{}),
                        null
                )) {
                    while (files.moveToNext()) {
                        i++;
                        b.addTriggerContentUri(
                                new JobInfo.TriggerContentUri(
                                        MediaStore.Files.getContentUri(
                                                "external",
                                                files.getLong(0)),
                                        JobInfo.TriggerContentUri.FLAG_NOTIFY_FOR_DESCENDANTS
                                )
                        );
                    }
                }

                Log.i(TAG, "Watching " + i + " contenturi for " + paths.size() + " files...");
                b.setTriggerContentUpdateDelay(100);
                b.setTriggerContentMaxDelay(3000);
                scheduler.schedule(b.build());
            }
        }
    }

    @Override
    public boolean onStartJob(JobParameters jobParameters) {
        Log.i(TAG, "Agenda sync triggered!");
        ((Application)getApplication()).requestSync();
        this.jobFinished(jobParameters, false);
        register(getApplicationContext());
        return false;
    }

    @Override
    public boolean onStopJob(JobParameters jobParameters) {
        return false;
    }
}