package com.example.callhelper;

import android.app.Notification;
import android.app.PendingIntent;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.util.Log;
import java.util.Locale;

/**
 * طريقة رد إضافية: إشعار المكالمة الواردة فيه زر "رد". نقرأ الإشعار ونفعّل زر الرد مباشرة
 * بدون لمس الشاشة. تحتاج تفعيل "الوصول إلى الإشعارات" للتطبيق (من الإعدادات أو من سكربت ADB).
 */
public class CallNotificationListener extends NotificationListenerService {
    private static final String TAG = "NotifL";
    private static volatile CallNotificationListener sInstance;
    private static volatile String sLastInfo = "";

    public static boolean isConnected() { return sInstance != null; }
    public static String lastInfo() { return sLastInfo; }

    /** Returns the newest readable notification only when the user has granted notification access. */
    public static String readLatestNotification() {
        CallNotificationListener service = sInstance;
        if (service == null) return "فعّل إذن الوصول إلى الإشعارات لنوفا من إعدادات الهاتف أولاً.";
        try {
            StatusBarNotification[] active = service.getActiveNotifications();
            if (active == null || active.length == 0) return "لا توجد إشعارات حالياً.";
            java.util.Arrays.sort(active, (a, b) -> Long.compare(b.getPostTime(), a.getPostTime()));
            for (StatusBarNotification sbn : active) {
                if (sbn == null || sbn.getNotification() == null) continue;
                Notification n = sbn.getNotification();
                if (service.getPackageName().equals(sbn.getPackageName())) continue;
                android.os.Bundle extras = n.extras;
                if (extras == null) continue;
                CharSequence title = extras.getCharSequence(Notification.EXTRA_TITLE);
                CharSequence body = extras.getCharSequence(Notification.EXTRA_BIG_TEXT);
                if (body == null || body.length() == 0) body = extras.getCharSequence(Notification.EXTRA_TEXT);
                if ((body == null || body.length() == 0) && extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES) != null) {
                    CharSequence[] lines = extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES);
                    if (lines.length > 0) body = lines[lines.length - 1];
                }
                String t = title == null ? "" : title.toString().trim();
                String b = body == null ? "" : body.toString().trim();
                if (!t.isEmpty() || !b.isEmpty()) {
                    String result = t.isEmpty() ? b : (b.isEmpty() ? t : t + ". " + b);
                    return result.length() > 420 ? result.substring(0, 420) : result;
                }
            }
            return "لا يوجد إشعار يحتوي على نص يمكن قراءته.";
        } catch (SecurityException e) {
            return "إذن قراءة الإشعارات غير متاح. فعّله من إعدادات الهاتف.";
        } catch (Exception e) {
            Log.w(TAG, "read latest notification failed", e);
            return "تعذرت قراءة الإشعارات حالياً.";
        }
    }

    @Override public void onListenerConnected() { sInstance = this; Log.d(TAG, "listener connected"); AutoStarter.tryStart(this, "notif-listener"); }
    @Override public void onListenerDisconnected() { sInstance = null; Log.d(TAG, "listener disconnected"); }
    @Override public void onDestroy() { sInstance = null; super.onDestroy(); }

    private static final String[] PKG_HINTS = {"call", "dialer", "phone", "telecom", "incallui"};

    private static boolean isAnswerTitle(String t) {
        if (t.isEmpty()) return false;
        if (t.contains("decline") || t.contains("reject") || t.contains("end") || t.contains("hang")
                || t.contains("رفض") || t.contains("إنهاء") || t.contains("انهاء")) return false;
        return t.equals("answer") || t.equals("accept") || t.contains("answer") || t.contains("accept")
                || t.equals("رد") || t.equals("الرد") || t.equals("قبول") || t.equals("الرد على المكالمة")
                || t.equals("رد على المكالمة") || t.equals("قبول المكالمة");
    }

    /** يضغط زر "رد" داخل إشعار المكالمة. يرجع true إذا أرسل الضغطة. */
    public static boolean answerViaNotification() {
        CallNotificationListener s = sInstance;
        if (s == null) { sLastInfo = "الوصول للإشعارات غير مفعّل"; return false; }
        StatusBarNotification[] list;
        try { list = s.getActiveNotifications(); }
        catch (Exception e) { sLastInfo = "قراءة الإشعارات فشلت"; return false; }
        if (list == null) { sLastInfo = "لا إشعارات"; return false; }
        StringBuilder seen = new StringBuilder();
        for (StatusBarNotification sbn : list) {
            Notification n = sbn.getNotification();
            if (n == null || n.actions == null) continue;
            String pkg = sbn.getPackageName() == null ? "" : sbn.getPackageName().toLowerCase(Locale.ROOT);
            boolean call = Notification.CATEGORY_CALL.equals(n.category);
            for (String h : PKG_HINTS) if (pkg.contains(h)) call = true;
            if (!call) continue;
            for (Notification.Action a : n.actions) {
                String t = a.title == null ? "" : a.title.toString().trim().toLowerCase(Locale.ROOT);
                seen.append('[').append(t).append(']');
                if (isAnswerTitle(t) && a.actionIntent != null) {
                    try {
                        a.actionIntent.send();
                        sLastInfo = "ضغط زر الإشعار: " + t;
                        Log.d(TAG, "answered via notification action: " + t);
                        return true;
                    } catch (PendingIntent.CanceledException e) { Log.w(TAG, "action canceled", e); }
                }
            }
        }
        sLastInfo = seen.length() == 0 ? "ما لقى إشعار مكالمة فيه أزرار" : "ما لقى زر رد بين: " + seen;
        return false;
    }
}
