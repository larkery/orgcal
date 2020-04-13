package com.larkery.simpleorgsync.con;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import android.support.annotation.Nullable;

/**
 * Created by hinton on 12/04/20.
 */

public class ConSyncService extends Service {
    private static ConSyncAdapter adapter = null;

    @Override
    public void onCreate() {
        super.onCreate();

        synchronized (this.getClass()) {
            if (adapter == null) {
                adapter = new ConSyncAdapter(getApplicationContext(), true);
            }
        }
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return adapter.getSyncAdapterBinder();
    }
}
