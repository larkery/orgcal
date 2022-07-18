package com.larkery.simpleorgsync.lib;

public class Log {
    public static final String TAG = "orgcal/";
    public static void i(String tag, String msg) {
        android.util.Log.i(TAG + tag, msg);
    }
    public static void e(String tag, String msg) {
        android.util.Log.e(TAG + tag, msg);
    }
    public static void w(String tag, String msg) {
        android.util.Log.w(TAG + tag, msg);
    }
    public static void d(String tag, String msg) {
        android.util.Log.d(TAG + tag, msg);
    }
    public static void i(String tag, String msg, Throwable th) {
        android.util.Log.i(TAG + tag, msg, th);
    }
    public static void e(String tag, String msg, Throwable th) {
        android.util.Log.e(TAG + tag, msg, th);
    }
    public static void w(String tag, String msg, Throwable th) {
        android.util.Log.w(TAG + tag, msg, th);
    }
    public static void d(String tag, String msg, Throwable th) {
        android.util.Log.d(TAG + tag, msg, th);
    }
}
