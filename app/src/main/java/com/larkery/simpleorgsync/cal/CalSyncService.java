package com.larkery.simpleorgsync.cal;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import android.support.annotation.Nullable;

public class CalSyncService extends Service {
    private static CalSyncAdapter adapter = null;

    @Override
    public void onCreate() {
        super.onCreate();

        synchronized (this.getClass()) {
            if (adapter == null) {
                adapter = new CalSyncAdapter(getApplicationContext(), true);
            }
        }
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return adapter.getSyncAdapterBinder();
    }
}
