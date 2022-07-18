package com.larkery.simpleorgsync.lib;

import android.accounts.Account;
import android.accounts.AccountManager;
import android.content.Context;

import org.json.JSONException;
import org.json.JSONObject;

public class JSONPrefs {
    JSONObject value;
    private JSONPrefs(String s) {
        try {
            value = new JSONObject(s);
        } catch (Exception e) {
            Log.e("JSONPrefs", "Invalid prefs", e);
            value = new JSONObject();
        }
    }
    public static JSONPrefs fromString(String s) {
        return new JSONPrefs(s);
    }

    public static JSONPrefs fromContext(Context context, Account account) {
        return fromString(
                context.getSystemService(AccountManager.class).getUserData(account, "prefs")
        );
    }

    public String toString() {
        return value.toString();
    }

    public String getString(final String key, final String def) {
        try {
            return value.getString(key);
        } catch (JSONException e) {
            return def;
        }
    }

    public boolean getBoolean(final String key, final boolean def) {
        try {
            return value.getBoolean(key);
        } catch (JSONException e) {
            return def;
        }
    }

    public int getInt(final String key, final int def) {
        try {
            return value.getInt(key);
        } catch (JSONException e) {
            return def;
        }
    }
}
