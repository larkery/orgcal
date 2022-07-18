package com.larkery.simpleorgsync.lib;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.preference.PreferenceManager;
import android.support.v4.provider.DocumentFile;
import android.util.Log;

import com.larkery.simpleorgsync.cal.CalSyncAdapter;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class FileJobService extends JobService {
    private static final String TAG = "FileJobService";

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
                final boolean ignoreConflict = prefs.getBoolean("ignore_syncthing_conflicts", true);

                final Set<DocumentFile> toWatch = new HashSet<>();
                if (contactsFile != null) {
                    toWatch.add(DocumentFile.fromSingleUri(context, Uri.parse(contactsFile)));
                }

                if (agendaFiles != null) {
                    CalSyncAdapter.collectOrgFiles(
                            CalSyncAdapter.docFile(context, agendaFiles),
                            toWatch,
                            ignoreConflict);
                }

                final ArrayList<String> paths = new ArrayList<>();
                StringBuffer sb = new StringBuffer();
                for (DocumentFile f : toWatch) {
                    Log.i(TAG, "Watching " + f.getName());

                    b.addTriggerContentUri(
                            new JobInfo.TriggerContentUri(
                                    f.getUri(),
                                    JobInfo.TriggerContentUri.FLAG_NOTIFY_FOR_DESCENDANTS
                            )
                    );
                }

                b.setTriggerContentUpdateDelay(100);
                b.setTriggerContentMaxDelay(3000);
                scheduler.schedule(b.build());
            }
        }
    }

    @Override
    public boolean onStartJob(JobParameters jobParameters) {
        ((Application)getApplication()).requestSync("a file was changed: " +
                Arrays.toString(jobParameters.getTriggeredContentUris())
                );
        this.jobFinished(jobParameters, false);
        register(getApplicationContext());
        return false;
    }

    @Override
    public boolean onStopJob(JobParameters jobParameters) {
        return false;
    }
}