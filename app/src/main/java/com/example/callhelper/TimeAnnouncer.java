package com.example.callhelper;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;
import java.util.Calendar;

/** نطق الوقت بالعربي: كل ساعة تماماً (عبر AlarmManager) وعند ضغط رفع الصوت. */
public final class TimeAnnouncer {
    private static final String TAG = "TimeAnnouncer";
    private TimeAnnouncer() {}

    private static final String[] H = {"", "الواحدة", "الثانية", "الثالثة", "الرابعة", "الخامسة", "السادسة",
            "السابعة", "الثامنة", "التاسعة", "العاشرة", "الحادية عشرة", "الثانية عشرة"};

    public static boolean hourlyEnabled(Context c) {
        return c.getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE).getBoolean(MainActivity.PREF_TIME_HOURLY, false);
    }

    public static boolean volumeEnabled(Context c) {
        return c.getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE).getBoolean(MainActivity.PREF_TIME_ON_VOLUME, false);
    }

    private static String period(int h24) {
        if (h24 >= 5 && h24 < 12) return "صباحاً";
        if (h24 >= 12 && h24 < 15) return "ظهراً";
        if (h24 >= 15 && h24 < 18) return "عصراً";
        if (h24 >= 18 && h24 < 21) return "مساءً";
        return "ليلاً";
    }

    private static String hourWord(int h24) {
        int h12 = h24 % 12;
        return H[h12 == 0 ? 12 : h12];
    }

    /** مثال: "الساعة الآن الواحدة تماماً ظهراً" أو "الساعة الآن الثالثة و 10 دقائق عصراً" */
    public static String phrase(Calendar cal) {
        int h = cal.get(Calendar.HOUR_OF_DAY);
        int m = cal.get(Calendar.MINUTE);
        String body;
        int hForPeriod = h;
        if (m == 0) {
            body = hourWord(h) + " تماماً";
        } else if (m == 15) {
            body = hourWord(h) + " والربع";
        } else if (m == 30) {
            body = hourWord(h) + " والنصف";
        } else if (m == 45) {
            hForPeriod = (h + 1) % 24;
            body = hourWord(hForPeriod) + " إلا ربعاً";
        } else {
            body = hourWord(h) + " و " + m + (m == 2 ? " دقيقتان" : (m >= 3 && m <= 10 ? " دقائق" : " دقيقة"));
        }
        return "الساعة الآن " + body + " " + period(hForPeriod);
    }

    private static PendingIntent pi(Context c) {
        Intent i = new Intent(c, TimeAlarmReceiver.class).setAction(TimeAlarmReceiver.ACTION);
        return PendingIntent.getBroadcast(c, 7, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    /** يجدول التنبيه القادم عند بداية الساعة التالية (إذا كان الخيار مفعّلاً). */
    public static void schedule(Context c) {
        try {
            if (!hourlyEnabled(c)) return;
            AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
            if (am == null) return;
            Calendar next = Calendar.getInstance();
            next.set(Calendar.MINUTE, 0);
            next.set(Calendar.SECOND, 0);
            next.set(Calendar.MILLISECOND, 0);
            next.add(Calendar.HOUR_OF_DAY, 1);
            long t = next.getTimeInMillis();
            boolean exact = Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms();
            try {
                if (exact) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, t, pi(c));
                else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, t, pi(c));
            } catch (SecurityException se) {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, t, pi(c));
            }
            Log.d(TAG, "next alarm at " + next.getTime() + " exact=" + exact);
        } catch (Exception e) { Log.e(TAG, "schedule", e); }
    }

    public static void cancel(Context c) {
        try {
            AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
            if (am != null) am.cancel(pi(c));
        } catch (Exception e) { Log.e(TAG, "cancel", e); }
    }
}
