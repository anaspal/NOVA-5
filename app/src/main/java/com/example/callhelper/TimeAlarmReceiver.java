package com.example.callhelper;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/** يُستدعى عند بداية كل ساعة: يعيد الجدولة ثم يطلب من الخدمة نطق الوقت. */
public class TimeAlarmReceiver extends BroadcastReceiver {
    public static final String ACTION = "com.example.callhelper.TIME_ALARM";

    @Override public void onReceive(Context ctx, Intent intent) {
        try {
            if (!TimeAnnouncer.hourlyEnabled(ctx)) return;
            TimeAnnouncer.schedule(ctx); // الساعة التالية
            if (VoiceCommandService.requestSayTime()) return;
            boolean want = ctx.getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE)
                    .getBoolean(MainActivity.PREF_WANT_RUNNING, false);
            if (!want) return;
            ctx.startForegroundService(new Intent(ctx, VoiceCommandService.class)
                    .setAction(VoiceCommandService.ACTION_SAY_TIME));
        } catch (Exception e) { Log.e("TimeAlarm", "onReceive", e); }
    }
}
