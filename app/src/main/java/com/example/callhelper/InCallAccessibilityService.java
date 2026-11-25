package com.example.callhelper;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.graphics.Rect;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.util.Log;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class InCallAccessibilityService extends AccessibilityService {
    private static final String TAG = "A11y";

    private static volatile boolean sConnected = false;
    private static volatile InCallAccessibilityService sInstance = null;
    private static volatile long sLastKeyTime = 0;
    private static volatile int sLastKeyCode = 0;
    private static volatile int sKeyCount = 0;
    private static volatile String sLastKeyInfo = "";
    private String pendingSendPackage = null;
    private String pendingSendDraft = null;
    private long pendingSendUntil = 0L;

    public static boolean isConnected() { return sConnected; }

    /** Executes Android global navigation actions only after the user enables this accessibility service. */
    public static boolean performNovaGlobalAction(int action) {
        InCallAccessibilityService service = sInstance;
        if (service == null || !sConnected) return false;
        try { return service.performGlobalAction(action); }
        catch (Exception e) { Log.w(TAG, "NOVA global action failed", e); return false; }
    }

    /** يبحث عن عنصر ظاهر بالاسم ويضغطه فقط عند وجود تطابق وحيد واضح. */
    public static String clickVisibleText(String label) {
        InCallAccessibilityService service=sInstance;
        if(service==null||!sConnected)return "فعّل خدمة إمكانية الوصول الخاصة بنوفا أولاً.";
        return service.clickUniqueLabel(label);
    }

    /** يكتب في حقل التحرير النشط فقط، ويرفض حقول كلمات المرور. */
    public static String typeIntoFocusedField(String value) {
        InCallAccessibilityService service=sInstance;
        if(service==null||!sConnected)return "خدمة إمكانية الوصول غير مفعّلة.";
        return service.typeFocused(value);
    }

    public static String scrollScreen(boolean down) {
        InCallAccessibilityService service=sInstance;
        if(service==null||!sConnected)return "فعّل خدمة إمكانية الوصول لتنفيذ التمرير.";
        return service.scrollActive(down);
    }

    /** يحفظ سياق المسودة وزر الإرسال دون الضغط عليه؛ التأكيد اللاحق يجب أن يطابق السياق نفسه. */
    public static String prepareSendConfirmation() {
        InCallAccessibilityService service=sInstance;
        if(service==null||!sConnected)return "خدمة إمكانية الوصول غير مفعّلة.";
        return service.captureSendContext();
    }

    /** لا ينفذ الإرسال إلا إذا بقي التطبيق والمسودة كما كانا عند طلب التأكيد. */
    public static String confirmSendMessage() {
        InCallAccessibilityService service=sInstance;
        if(service==null||!sConnected)return "خدمة إمكانية الوصول غير مفعّلة.";
        return service.confirmSendWithDraft();
    }
    public static long lastKeyTime() { return sLastKeyTime; }
    public static int lastKeyCode() { return sLastKeyCode; }
    public static int keyCount() { return sKeyCount; }
    public static String lastKeyInfo() { return sLastKeyInfo; }


    private String clickUniqueLabel(String requested) {
        String wanted=com.example.callhelper.nova.NovaCommandParser.normalize(requested);
        if(wanted.isEmpty())return "اذكر اسم العنصر الذي تريد الضغط عليه.";
        AccessibilityNodeInfo root=null;
        try { root=getRootInActiveWindow(); } catch(Exception ignored) {}
        if(root==null)return "لا أستطيع قراءة عناصر الشاشة الحالية.";
        List<AccessibilityNodeInfo> matches=new ArrayList<>();
        collectLabelMatches(root,wanted,matches,0);
        matches=dedupeByClickTarget(matches);
        if(matches.size()==0)return "لم أجد عنصرًا ظاهرًا باسم "+requested+". قد لا يتيحه التطبيق لخدمة إمكانية الوصول.";
        if(matches.size()>1)return "وجدت أكثر من عنصر باسم "+requested+"؛ حدّد اسمه بشكل أدق.";
        AccessibilityNodeInfo node=matches.get(0);
        AccessibilityNodeInfo click=node;
        int depth=0;
        while(click!=null&&!click.isClickable()&&depth++<5)click=click.getParent();
        boolean ok=click!=null&&click.isClickable()&&click.performAction(AccessibilityNodeInfo.ACTION_CLICK);
        if(ok)return "تم الضغط على "+requested+".";
        Rect bounds=new Rect(); node.getBoundsInScreen(bounds);
        if(!bounds.isEmpty()){
            Path path=new Path();path.moveTo(bounds.exactCenterX(),bounds.exactCenterY());
            GestureDescription gesture=new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(path,0,90)).build();
            if(dispatchGesture(gesture,null,null))return "أرسلت ضغطة إلى "+requested+".";
        }
        return "وجدت "+requested+" لكن التطبيق لم يسمح بالضغط عليه.";
    }

    /** يدمج العقد المتطابقة التي تعود لنفس العنصر القابل للضغط (زر + نص داخله) حتى لا تُعدّ غموضاً كاذباً. */
    private List<AccessibilityNodeInfo> dedupeByClickTarget(List<AccessibilityNodeInfo> in) {
        List<AccessibilityNodeInfo> out = new ArrayList<>();
        List<AccessibilityNodeInfo> keys = new ArrayList<>();
        for (AccessibilityNodeInfo n : in) {
            AccessibilityNodeInfo t = n;
            int d = 0;
            while (t != null && !t.isClickable() && d++ < 5) t = t.getParent();
            AccessibilityNodeInfo key = t != null ? t : n;
            boolean dup = false;
            for (AccessibilityNodeInfo k : keys) { if (k.equals(key)) { dup = true; break; } }
            if (!dup) { keys.add(key); out.add(n); }
        }
        return out;
    }

    private void collectLabelMatches(AccessibilityNodeInfo node,String wanted,List<AccessibilityNodeInfo> out,int depth) {
        if(node==null||depth>35||out.size()>3)return;
        String text=com.example.callhelper.nova.NovaCommandParser.normalize(node.getText()==null?"":node.getText().toString());
        String desc=com.example.callhelper.nova.NovaCommandParser.normalize(node.getContentDescription()==null?"":node.getContentDescription().toString());
        boolean exact=wanted.equals(text)||wanted.equals(desc);
        boolean partial=(text.length()>0&&text.equals(wanted))||(desc.length()>0&&desc.equals(wanted));
        if((exact||partial) && node.isVisibleToUser() && node.isEnabled())out.add(node);
        for(int i=0;i<node.getChildCount();i++)collectLabelMatches(node.getChild(i),wanted,out,depth+1);
    }

    private String captureSendContext() {
        AccessibilityNodeInfo root;
        try { root=getRootInActiveWindow(); } catch(Exception e) { root=null; }
        if(root==null)return "لا أستطيع التحقق من شاشة الرسالة الحالية؛ لم أجهز طلب إرسال.";
        AccessibilityNodeInfo focused=root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
        if(focused==null||!focused.isEditable()||focused.isPassword())
            return "لم أجد حقل رسالة نشطًا وآمنًا؛ لم أجهز طلب إرسال.";
        CharSequence draft=focused.getText();
        String draftText=draft==null?"":draft.toString();
        if(draftText.trim().isEmpty())return "حقل الرسالة فارغ؛ لم أجهز طلب إرسال.";
        String packageName=root.getPackageName()==null?"":root.getPackageName().toString();
        if(packageName.isEmpty())return "تعذر تحديد التطبيق الحالي؛ لم أجهز طلب إرسال.";
        String sendLabel=hasUniqueSendControl(root,"إرسال")?"إرسال":(hasUniqueSendControl(root,"Send")?"Send":null);
        if(sendLabel==null)return "لم أجد زر إرسال واحدًا واضحًا؛ لم أجهز طلب إرسال.";
        pendingSendPackage=packageName;
        pendingSendDraft=draftText;
        pendingSendUntil=android.os.SystemClock.elapsedRealtime()+30000L;
        return "جهزت طلب التأكيد لمدة 30 ثانية. راجع المستلم والنص، ثم قل: أؤكد إرسال الرسالة.";
    }

    private boolean hasUniqueSendControl(AccessibilityNodeInfo root,String label) {
        List<AccessibilityNodeInfo> matches=new ArrayList<>();
        collectLabelMatches(root,com.example.callhelper.nova.NovaCommandParser.normalize(label),matches,0);
        return dedupeByClickTarget(matches).size()==1;
    }

    private String confirmSendWithDraft() {
        if(pendingSendUntil==0L||android.os.SystemClock.elapsedRealtime()>pendingSendUntil) {
            clearPendingSend();
            return "انتهت صلاحية طلب الإرسال أو لا يوجد طلب معلّق؛ لم يتم الإرسال.";
        }
        AccessibilityNodeInfo root;
        try { root=getRootInActiveWindow(); } catch(Exception e) { root=null; }
        if(root==null)return "لا أستطيع التحقق من شاشة الرسالة الحالية؛ لم يتم الإرسال.";
        String currentPackage=root.getPackageName()==null?"":root.getPackageName().toString();
        AccessibilityNodeInfo focused=root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
        if(!pendingSendPackage.equals(currentPackage)||focused==null||!focused.isEditable()||focused.isPassword()) {
            clearPendingSend();
            return "تغير التطبيق أو حقل الرسالة؛ ألغيت التأكيد ولم يتم الإرسال.";
        }
        CharSequence current=focused.getText();
        String currentDraft=current==null?"":current.toString();
        if(!pendingSendDraft.equals(currentDraft)) {
            clearPendingSend();
            return "تغير نص المسودة منذ طلب التأكيد؛ ألغيت العملية ولم يتم الإرسال.";
        }
        clearPendingSend();
        String result=clickUniqueLabel("إرسال");
        if(result.startsWith("لم أجد"))result=clickUniqueLabel("Send");
        return result.startsWith("تم الضغط")||result.startsWith("أرسلت ضغطة")
                ? "نفذت أمر الضغط على زر الإرسال. تحقق من نتيجة التطبيق."
                : "لم أتمكن من الضغط على زر إرسال واحد بوضوح؛ لم يتم الإرسال. "+result;
    }

    private void clearPendingSend() {
        pendingSendPackage=null;
        pendingSendDraft=null;
        pendingSendUntil=0L;
    }

    private String typeFocused(String value) {
        if(value==null||value.trim().isEmpty())return "لم يصلني نص للكتابة.";
        AccessibilityNodeInfo root;
        try{root=getRootInActiveWindow();}catch(Exception e){root=null;}
        if(root==null)return "لا يوجد حقل نشط يمكن الوصول إليه.";
        AccessibilityNodeInfo focused=root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
        if(focused==null||!focused.isEditable())return "لم أجد حقل كتابة نشطًا. اضغط داخل الحقل المطلوب أولاً.";
        if(focused.isPassword())return "لن أكتب في حقل كلمة مرور أو حقل محمي.";
        if(!focused.isEnabled()||!focused.isFocusable())return "حقل الكتابة غير جاهز لاستقبال النص.";
        CharSequence existing=focused.getText();
        if(existing!=null&&existing.length()>0)
            return "حقل الكتابة يحتوي نصًا بالفعل. لن أستبدله حتى لا أفقد محتواك؛ امسحه أو اختر حقلًا فارغًا ثم أعد الأمر.";
        android.os.Bundle args=new android.os.Bundle();
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,value);
        boolean ok=focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,args);
        return ok?"كتبت النص في حقل الإدخال الفارغ النشط.":"تعذر إدخال النص؛ قد لا يدعم التطبيق الكتابة عبر إمكانية الوصول.";
    }

    private String scrollActive(boolean down) {
        AccessibilityNodeInfo root;
        try{root=getRootInActiveWindow();}catch(Exception e){root=null;}
        if(root==null)return "لا أستطيع الوصول إلى محتوى الشاشة للتمرير.";
        AccessibilityNodeInfo target=findScrollable(root,0);
        if(target!=null){
            int action=down?AccessibilityNodeInfo.ACTION_SCROLL_FORWARD:AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD;
            if(target.performAction(action))return down?"تم التمرير للأسفل.":"تم التمرير للأعلى.";
        }
        android.util.DisplayMetrics dm=new android.util.DisplayMetrics();
        try{android.view.WindowManager wm=(android.view.WindowManager)getSystemService(WINDOW_SERVICE);if(wm==null)return "تعذر تحديد مساحة الشاشة للتمرير.";wm.getDefaultDisplay().getRealMetrics(dm);}catch(Exception e){return "تعذر تحديد مساحة الشاشة للتمرير.";}
        float x=dm.widthPixels*0.5f;
        float from=down?dm.heightPixels*0.78f:dm.heightPixels*0.30f;
        float to=down?dm.heightPixels*0.30f:dm.heightPixels*0.78f;
        Path path=new Path();path.moveTo(x,from);path.lineTo(x,to);
        GestureDescription g=new GestureDescription.Builder().addStroke(new GestureDescription.StrokeDescription(path,0,320)).build();
        return dispatchGesture(g,null,null)?(down?"تم التمرير للأسفل.":"تم التمرير للأعلى."):"تعذر تنفيذ التمرير.";
    }

    private AccessibilityNodeInfo findScrollable(AccessibilityNodeInfo node,int depth) {
        if(node==null||depth>30)return null;
        AccessibilityNodeInfo best=null;
        long bestArea=-1L;
        if(node.isScrollable()&&node.isVisibleToUser()&&node.isEnabled()) {
            Rect bounds=new Rect();
            node.getBoundsInScreen(bounds);
            long area=(long)Math.max(0,bounds.width())*Math.max(0,bounds.height());
            if(!bounds.isEmpty()&&area>bestArea) { best=node; bestArea=area; }
        }
        for(int i=0;i<node.getChildCount();i++) {
            AccessibilityNodeInfo candidate=findScrollable(node.getChild(i),depth+1);
            if(candidate!=null) {
                Rect bounds=new Rect();
                candidate.getBoundsInScreen(bounds);
                long area=(long)Math.max(0,bounds.width())*Math.max(0,bounds.height());
                if(area>bestArea) { best=candidate; bestArea=area; }
            }
        }
        return best;
    }

    /** يضغط زر "رد" في شاشة/إشعار المكالمة. يرجع true إذا وجد زراً وضغطه. */
    public static boolean clickAnswer() {
        InCallAccessibilityService s = sInstance;
        return s != null && s.clickButton(true);
    }

    /** احتياط لشاشات المكالمة التي تتطلب سحب زر الرد (شاشة القفل في بعض الأجهزة). */
    public static boolean swipeAnswer() {
        InCallAccessibilityService s = sInstance;
        return s != null && s.swipeOnButton();
    }

    /** يضغط زر "إنهاء/رفض" في شاشة/إشعار المكالمة. */
    public static boolean clickDecline() {
        InCallAccessibilityService s = sInstance;
        return s != null && s.clickButton(false);
    }

    private final android.os.Handler UI = new android.os.Handler(android.os.Looper.getMainLooper());
    private boolean consumedUp = false;
    private boolean consumedDown = false;

    @Override protected void onServiceConnected() {
        super.onServiceConnected();
        try {
            AccessibilityServiceInfo info = getServiceInfo();
            if (info != null) {
                // نضمن تفعيل اعتراض الأزرار وقراءة النوافذ من الكود حتى لو تجاهلها النظام من ملف XML
                info.flags |= AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS
                        | AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
                        | AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS;
                setServiceInfo(info);
            }
        } catch (Exception e) { Log.w(TAG, "setServiceInfo failed", e); }
        sInstance = this;
        sConnected = true;
        Log.d(TAG, "service connected");
        AutoStarter.tryStart(this, "a11y");
    }

    @Override public boolean onUnbind(Intent intent) {
        sConnected = false;
        sInstance = null;
        Log.d(TAG, "service unbound");
        return super.onUnbind(intent);
    }

    @Override public void onDestroy() {
        sConnected = false;
        sInstance = null;
        super.onDestroy();
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {}
    @Override public void onInterrupt() {}

    private static long sLastTimeSpeak = 0;

    @Override public boolean onKeyEvent(KeyEvent event) {
        int code = event.getKeyCode();
        if (code != KeyEvent.KEYCODE_VOLUME_UP && code != KeyEvent.KEYCODE_VOLUME_DOWN) return super.onKeyEvent(event);
        boolean up = (code == KeyEvent.KEYCODE_VOLUME_UP);

        // ACTION_UP والتكرار: نبتلعها فقط إذا ابتلعنا الـ DOWN الأصلي
        if (event.getAction() == KeyEvent.ACTION_UP) {
            boolean was = up ? consumedUp : consumedDown;
            if (up) consumedUp = false; else consumedDown = false;
            return was;
        }
        if (event.getRepeatCount() > 0) return up ? consumedUp : consumedDown;

        // مهم: النظام ينتظر ردنا 500ms فقط، وإن تأخرنا يمرّر الزر لنفسه فيكتم الرنين.
        // لذلك نقرّر بسرعة ونرجع فوراً، ونؤجّل العمل الثقيل (الرد/الإنهاء) إلى Handler.
        final boolean ringing = CallController.ringing(this);
        final boolean inCall = CallController.inCall(this);
        sLastKeyTime = System.currentTimeMillis();
        sLastKeyCode = code;
        sKeyCount = sKeyCount + 1;
        sLastKeyInfo = (up ? "رفع الصوت" : "خفض الصوت") + " | رنين=" + (ringing ? "نعم" : "لا")
                + " | مكالمة=" + (inCall ? "نعم" : "لا");
        Log.d(TAG, "key " + sLastKeyInfo);

        // نطق الوقت عند رفع الصوت (بدون رنين أو مكالمة). لا نبتلع الزر، فيرتفع الصوت كالعادة.
        if (up && !ringing && !inCall && TimeAnnouncer.volumeEnabled(this)) {
            long now = System.currentTimeMillis();
            if (now - sLastTimeSpeak > 4000 && VoiceCommandService.requestSayTime()) sLastTimeSpeak = now;
            return super.onKeyEvent(event);
        }

        if (!ringing && !inCall) return super.onKeyEvent(event);

        // خيار "ترك الأزرار للنظام أثناء المكالمة": بعد الرد (OFFHOOK) لا نتدخل إطلاقاً
        if (inCall && !ringing && getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE)
                .getBoolean(MainActivity.PREF_KEYS_PASS_IN_CALL, false)) {
            return super.onKeyEvent(event);
        }

        if (up) {
            if (ringing) {
                UI.post(() -> { CallController.answerCall(InCallAccessibilityService.this); vibrate(80); });
            } else {
                UI.post(() -> { CallController.toggleSpeaker(InCallAccessibilityService.this); vibrate(40); });
            }
            consumedUp = true;
            return true;
        }
        if (inCall) {
            UI.post(() -> { CallController.endCall(InCallAccessibilityService.this); vibrate(80); });
            consumedDown = true;
            return true;
        }
        // خفض الصوت أثناء الرنين فقط: نترك النظام يكتم الرنين كالعادة
        return super.onKeyEvent(event);
    }

    // ---------------- الضغط على أزرار شاشة المكالمة (خطة احتياطية) ----------------

    private static final String[] ANSWER_LABELS = {
            "answer", "accept", "answer call", "accept call",
            "رد", "الرد", "الرد على المكالمة", "رد على المكالمة", "قبول", "قبول المكالمة"};
    private static final String[] DECLINE_LABELS = {
            "decline", "reject", "end call", "end", "hang up", "hangup",
            "إنهاء", "إنهاء المكالمة", "إنهاء الاتصال", "رفض", "رفض المكالمة"};
    private static final String[] ANSWER_IDS = {"answer", "accept"};
    private static final String[] DECLINE_IDS = {"decline", "reject", "end_call", "endcall", "hangup", "disconnect", "end_button", "endbutton"};
    private static final String[] ID_EXCLUDE = {"message", "sms", "hold", "swap", "merge", "speaker", "mute"};
    private static final String[] PKG_HINTS = {"call", "dialer", "phone", "telecom", "systemui", "contacts"};

    private List<AccessibilityNodeInfo> collectRoots() {
        List<AccessibilityNodeInfo> roots = new ArrayList<>();
        try {
            List<AccessibilityWindowInfo> wins = getWindows();
            if (wins != null) {
                for (AccessibilityWindowInfo w : wins) {
                    AccessibilityNodeInfo r = w.getRoot();
                    if (r != null) roots.add(r);
                }
            }
        } catch (Exception e) { Log.w(TAG, "getWindows failed", e); }
        try {
            AccessibilityNodeInfo active = getRootInActiveWindow();
            if (active != null) roots.add(active);
        } catch (Exception e) { Log.w(TAG, "getRootInActiveWindow failed", e); }
        return roots;
    }

    private AccessibilityNodeInfo findNode(boolean answer) {
        for (AccessibilityNodeInfo r : collectRoots()) {
            if (!isCallRelatedPackage(r)) continue;
            AccessibilityNodeInfo n = findMatch(r, answer, 0);
            if (n != null) return n;
        }
        return null;
    }

    private AccessibilityNodeInfo findMatch(AccessibilityNodeInfo n, boolean answer, int depth) {
        if (n == null || depth > 40) return null;
        if (matches(n, answer)) return n;
        int count = n.getChildCount();
        for (int i = 0; i < count; i++) {
            AccessibilityNodeInfo f = findMatch(n.getChild(i), answer, depth + 1);
            if (f != null) return f;
        }
        return null;
    }

    private boolean clickButton(boolean answer) {
        AccessibilityNodeInfo n = findNode(answer);
        if (n == null) { Log.w(TAG, "no " + (answer ? "answer" : "decline") + " button found"); return false; }
        AccessibilityNodeInfo t = n;
        int up = 0;
        while (t != null && !t.isClickable() && up < 4) { t = t.getParent(); up++; }
        if (t != null && t.isClickable() && t.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            Log.d(TAG, "clicked " + (answer ? "answer" : "decline"));
            return true;
        }
        // فشل الضغط البرمجي: نلمس مكان الزر على الشاشة بإيماءة
        Rect r = new Rect();
        n.getBoundsInScreen(r);
        if (r.isEmpty()) return false;
        Path p = new Path();
        p.moveTo(r.exactCenterX(), r.exactCenterY());
        GestureDescription g = new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(p, 0, 80)).build();
        boolean ok = dispatchGesture(g, null, null);
        Log.d(TAG, "tap gesture at " + r.centerX() + "," + r.centerY() + " -> " + ok);
        return ok;
    }

    private boolean swipeOnButton() {
        AccessibilityNodeInfo n = findNode(true);
        if (n == null) return false;
        Rect r = new Rect();
        n.getBoundsInScreen(r);
        if (r.isEmpty()) return false;
        float x = r.exactCenterX(), y = r.exactCenterY();
        Path p = new Path();
        p.moveTo(x, y);
        p.lineTo(x, Math.max(10f, y - 600f));
        GestureDescription g = new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(p, 0, 350)).build();
        return dispatchGesture(g, null, null);
    }

    private boolean isCallRelatedPackage(AccessibilityNodeInfo root) {
        CharSequence p = root.getPackageName();
        if (p == null) return false;
        String s = p.toString().toLowerCase(Locale.ROOT);
        for (String h : PKG_HINTS) if (s.contains(h)) return true;
        return false;
    }

    private boolean matches(AccessibilityNodeInfo n, boolean answer) {
        String[] labels = answer ? ANSWER_LABELS : DECLINE_LABELS;
        String t = norm(n.getText());
        String d = norm(n.getContentDescription());
        for (String l : labels) {
            if (l.equals(t) || l.equals(d)) return true;
            if (l.length() >= 6 && d != null && d.contains(l)) return true;
        }
        String id = null;
        try { id = n.getViewIdResourceName(); } catch (Exception ignored) {}
        if (id != null) {
            id = id.toLowerCase(Locale.ROOT);
            for (String ex : ID_EXCLUDE) if (id.contains(ex)) return false;
            for (String k : (answer ? ANSWER_IDS : DECLINE_IDS)) if (id.contains(k)) return true;
        }
        return false;
    }

    private static String norm(CharSequence cs) {
        return cs == null ? null : cs.toString().trim().toLowerCase(Locale.ROOT);
    }

    private void vibrate(int ms) {
        try {
            Vibrator v = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
            if (v == null) return;
            if (Build.VERSION.SDK_INT >= 26) v.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE));
            else v.vibrate(ms);
        } catch (Exception ignored) {}
    }
}
