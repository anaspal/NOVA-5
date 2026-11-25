package com.example.callhelper.nova;

import android.content.Context;
import android.content.SharedPreferences;

import com.example.callhelper.MainActivity;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** سجل بآخر أوامر NOVA (يُحفظ آخر 50 أمراً) للتشخيص والمراجعة. */
public final class NovaCommandLog {
    public static final String PREF_LOG = "nova_command_log";
    private static final int MAX = 50;
    private static final Object LOCK = new Object();

    private NovaCommandLog() { }

    public static final class Entry {
        public final long time;
        public final String heard, type, response;
        Entry(long time, String heard, String type, String response) {
            this.time = time; this.heard = heard; this.type = type; this.response = response;
        }
    }

    public static void add(Context context, String heard, String type, String response) {
        if (context == null) return;
        synchronized (LOCK) {
            try {
                SharedPreferences prefs = context.getApplicationContext()
                        .getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE);
                JSONArray old = new JSONArray(prefs.getString(PREF_LOG, "[]"));
                JSONArray out = new JSONArray();
                JSONObject o = new JSONObject();
                o.put("t", System.currentTimeMillis());
                o.put("h", heard == null ? "" : heard);
                o.put("y", type == null ? "" : type);
                o.put("r", response == null ? "" : response);
                out.put(o);
                for (int i = 0; i < old.length() && out.length() < MAX; i++) out.put(old.get(i));
                prefs.edit().putString(PREF_LOG, out.toString()).apply();
            } catch (Exception ignored) { }
        }
    }

    public static List<Entry> read(Context context) {
        List<Entry> list = new ArrayList<>();
        synchronized (LOCK) {
            try {
                JSONArray a = new JSONArray(context.getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE)
                        .getString(PREF_LOG, "[]"));
                for (int i = 0; i < a.length(); i++) {
                    JSONObject o = a.getJSONObject(i);
                    list.add(new Entry(o.optLong("t"), o.optString("h"), o.optString("y"), o.optString("r")));
                }
            } catch (Exception ignored) { }
        }
        return list;
    }

    public static void clear(Context context) {
        synchronized (LOCK) {
            context.getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE).edit().remove(PREF_LOG).apply();
        }
    }
}
