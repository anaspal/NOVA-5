package com.example.callhelper;

import android.Manifest;
import android.annotation.TargetApi;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.BroadcastReceiver;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.database.Cursor;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.net.Uri;
import android.os.SystemClock;
import android.os.PowerManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.provider.ContactsContract;
import android.speech.tts.TextToSpeech;
import com.example.callhelper.nova.NovaCommand;
import com.example.callhelper.nova.NovaSpeechLoop;
import com.example.callhelper.nova.NovaCommandExecutor;
import android.speech.tts.UtteranceProgressListener;
import android.telephony.PhoneStateListener;
import android.telephony.TelephonyCallback;
import android.telephony.TelephonyManager;
import android.util.Log;
import java.util.Calendar;
import java.util.Locale;

public class VoiceCommandService extends Service {
    public static final String ACTION_ANNOUNCE = "com.example.callhelper.ANNOUNCE";
    public static final String EXTRA_NUMBER = "number";
    public static final String ACTION_SAY_TIME = "com.example.callhelper.SAY_TIME";
    public static final String ACTION_NOVA_CONFIG_CHANGED = "com.example.callhelper.NOVA_CONFIG_CHANGED";
    private static volatile VoiceCommandService sInstance;
    private boolean pendingIsTime = false;

    /** يطلب من الخدمة (إن كانت شغّالة) نطق الوقت الآن. */
    public static boolean requestSayTime() {
        VoiceCommandService s = sInstance;
        if (s == null) return false;
        s.handler.post(s::sayTime);
        return true;
    }

    private void sayTime() {
        if (ringingNow) return;
        String s = TimeAnnouncer.phrase(Calendar.getInstance());
        if (isDiiVoiceSelected()) { speakNow(s); return; }
        ensureTts();
        if (!ttsReady) { pendingSpeech = s; pendingIsTime = true; return; }
        speakNow(s);
    }

    private static final String TAG = "VoiceSvc";
    private static final String CH_ID = "callhelper";
    private static final int NOTIF_ID = 1;
    private static final int MIN_TEMPLATES = 2;

    private final Object lock = new Object();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private volatile float[][][] templates = new float[0][][];
    private volatile float threshold = 0f;
    private volatile AudioRecord audioRecord;
    private volatile boolean capturing = false;
    private boolean foregroundOk = false;
    private NovaSpeechLoop novaSpeechLoop;
    private NovaCommandExecutor novaCommandExecutor;
    private volatile boolean micMutedWarned = false;
    private static volatile boolean sRunning = false;
    public static boolean isRunning() { return sRunning; }

    // قراءة اسم المتصل
    private TextToSpeech tts;
    private volatile boolean ttsReady = false;
    private volatile boolean speaking = false;
    private volatile boolean ringingNow = false;
    private volatile boolean announced = false;
    private String pendingSpeech = null;
    private final Runnable unknownFallback = () -> {
        if (ringingNow && !announced) announce(null);
    };

    // ---- الرد بالإيماءات (تلويح فوق حساس القرب / هزّ الجوال) ----
    private SensorManager sensorManager;
    private SensorEventListener gestureListener;
    private long nearSince = 0, lastShake = 0, ringStartMs = 0;
    private int shakeCount = 0;

    // AirWake uses the proximity sensor's wake-up variant only; no polling or partial wake lock.
    private static final long AIRWAKE_ARM_DELAY_MS = 1400L;
    private static final long AIRWAKE_PULSE_MIN_MS = 120L;
    private static final long AIRWAKE_PULSE_MAX_MS = 650L;
    private static final long AIRWAKE_MIN_GAP_MS = 180L;
    private static final long AIRWAKE_MAX_GAP_MS = 1000L;
    private static final long AIRWAKE_SEQUENCE_MS = 2200L;
    private static final long AIRWAKE_TOGGLE_COOLDOWN_MS = 5000L;
    private SensorManager airWakeSensorManager;
    private Sensor airWakeProximitySensor;
    private SensorEventListener airWakeListener;
    private BroadcastReceiver airWakeScreenReceiver;
    private boolean airWakeReceiverRegistered = false;
    private boolean airWakeSensorRegistered = false;
    private boolean airWakeSensorStateKnown = false;
    private boolean airWakeNear = false;
    private boolean airWakeReady = false;
    private long airWakeNearSince = 0L;
    private long airWakeFarSince = 0L;
    private long airWakeLastPass = 0L;
    private long airWakeLastToggle = 0L;
    private int airWakePassCount = 0;
    private final Runnable airWakeArmRunnable = () -> {
        long now = SystemClock.elapsedRealtime();
        if (airWakeSensorRegistered && airWakeSensorStateKnown && !airWakeNear
                && airWakeFarSince > 0L && now - airWakeFarSince >= AIRWAKE_ARM_DELAY_MS) {
            airWakeReady = true;
            Log.d(TAG, "AirWake armed after clear-sensor baseline");
        }
    };

    private TelephonyManager telephonyManager;
    private PhoneStateListener legacyListener;
    private Object modernCallback;

    @TargetApi(31)
    private class CallCb extends TelephonyCallback implements TelephonyCallback.CallStateListener {
        @Override public void onCallStateChanged(int state) { handleCallState(state); }
    }

    @Override public void onCreate() {
        super.onCreate();
        createChannel();
        try {
            startForegroundCompat(buildNotification());
            foregroundOk = true;
            sRunning = true;
            sInstance = this;
            try { getSystemService(NotificationManager.class).cancel(2); } catch (Exception ignored) {}
        } catch (Exception e) {
            Log.e(TAG, "startForeground failed", e);
            // مثلاً: النظام أعاد الخدمة من الخلفية وأندرويد 11+ يمنع تشغيل خدمة الميكروفون هكذا
            if (getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE)
                    .getBoolean(MainActivity.PREF_WANT_RUNNING, false)) {
                BootReceiver.showRestartNotification(this);
            }
            return;
        }
        loadTemplates();
        TimeAnnouncer.schedule(this);
        setupPhoneListener();
        if (announceEnabled() && !isDiiVoiceSelected()) ensureTts();
        syncNovaListening();
        syncAirWakeMonitoring();
    }

    private void startForegroundCompat(Notification n) {
        if (Build.VERSION.SDK_INT >= 30) startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
        else startForeground(NOTIF_ID, n);
    }

    private void createChannel() {
        NotificationChannel ch = new NotificationChannel(CH_ID, "NOVA", NotificationManager.IMPORTANCE_LOW);
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) nm.createNotificationChannel(ch);
    }

    private Notification buildNotification() {
        return new Notification.Builder(this, CH_ID)
                .setContentTitle("NOVA شغّالة")
                .setContentText(getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE).getBoolean(MainActivity.PREF_NOVA_ENABLED, false)
                        ? "NOVA مفعّلة • استماع لكلمة نوفا"
                        : (getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE).getBoolean(MainActivity.PREF_AIRWAKE_ENABLED, false)
                            ? "AirWake نشط • مرّتان فوق مستشعر القرب لتبديل NOVA" : "جاهز لاستقبال المكالمات"))
                .setSmallIcon(android.R.drawable.ic_menu_call)
                .setOngoing(true)
                .build();
    }

    private void loadTemplates() {
        float[][][] arr = TemplateStore.load(this);
        templates = arr;
        float base = DtwMatcher.baseScore(arr);
        float factor = DtwMatcher.factorFromSens(
                getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE).getInt(MainActivity.PREF_SENS, 30));
        threshold = base * factor;
        Log.d(TAG, "templates=" + arr.length + " base=" + base + " factor=" + factor + " threshold=" + threshold);
    }

    private void setupPhoneListener() {
        TelephonyManager tm = (TelephonyManager) getSystemService(TELEPHONY_SERVICE);
        if (tm == null) return;
        if (checkSelfPermission(Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "READ_PHONE_STATE not granted");
            return;
        }
        telephonyManager = tm;
        try {
            if (Build.VERSION.SDK_INT >= 31) {
                CallCb cb = new CallCb();
                tm.registerTelephonyCallback(getMainExecutor(), cb);
                modernCallback = cb;
            } else {
                legacyListener = new PhoneStateListener() {
                    @Override public void onCallStateChanged(int state, String number) { handleCallState(state); }
                };
                tm.listen(legacyListener, PhoneStateListener.LISTEN_CALL_STATE);
            }
        } catch (Exception e) { Log.e(TAG, "phone listener", e); }
    }

    private void handleCallState(int state) {
        CallController.setCallState(state);
        if (state == TelephonyManager.CALL_STATE_RINGING) {
            stopAirWakeSensor();
            if (!ringingNow) { ringingNow = true; announced = false; }
            loadTemplates(); // لالتقاط أي تسجيل جديد دون إعادة تشغيل الخدمة
            stopNovaListening();
            startGestures();
            if (templates.length >= MIN_TEMPLATES) startCapture();
            if (!announced && announceEnabled()) {
                ensureTts();
                handler.removeCallbacks(unknownFallback);
                handler.postDelayed(unknownFallback, 2500);
            }
        } else {
            stopAirWakeSensor();
            ringingNow = false;
            announced = false;
            pendingSpeech = null;
            handler.removeCallbacks(unknownFallback);
            if (tts != null) { try { tts.stop(); } catch (Exception ignored) {} }
            DiiTtsEngine.stop();
            speaking = false;
            stopGestures();
            stopCapture();
            if (state == TelephonyManager.CALL_STATE_IDLE) {
                syncNovaListening();
                syncAirWakeMonitoring();
            }
        }
    }

    // ---------------- قراءة اسم المتصل ----------------

    private boolean announceEnabled() {
        boolean on = getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE)
                .getBoolean(MainActivity.PREF_ANNOUNCE, false);
        return on
                && checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED
                && checkSelfPermission(Manifest.permission.READ_CALL_LOG) == PackageManager.PERMISSION_GRANTED;
    }

    private void handleAnnounceIntent(Intent i) {
        if (!ringingNow) { ringingNow = true; announced = false; }
        announce(i.getStringExtra(EXTRA_NUMBER));
    }

    private void announce(final String number) {
        if (announced) return;
        if (!announceEnabled()) return;
        announced = true;
        handler.removeCallbacks(unknownFallback);
        new Thread(() -> {
            String text;
            if (number == null || number.trim().isEmpty()) {
                text = "اتصال من رقم مجهول";
            } else {
                String name = lookupName(number);
                text = (name != null) ? ("اتصال من " + name)
                        : ("اتصال من رقم غير محفوظ " + spaced(number));
            }
            final String say = text;
            handler.post(() -> { if (ringingNow) speak(say); });
        }, "announce").start();
    }

    private String lookupName(String number) {
        if (checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return null;
        Cursor c = null;
        try {
            Uri uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number));
            c = getContentResolver().query(uri, new String[]{ContactsContract.PhoneLookup.DISPLAY_NAME}, null, null, null);
            if (c != null && c.moveToFirst()) return c.getString(0);
        } catch (Exception e) {
            Log.w(TAG, "lookup", e);
        } finally {
            if (c != null) c.close();
        }
        return null;
    }

    private String spaced(String number) {
        String digits = number.replaceAll("[^0-9]", "");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < digits.length(); i++) {
            if (i > 0) sb.append(' ');
            sb.append(digits.charAt(i));
        }
        return sb.toString();
    }

    private void ensureTts() {
        if (tts != null) return;
        tts = new TextToSpeech(getApplicationContext(), status -> {
            if (status != TextToSpeech.SUCCESS || tts == null) {
                Log.w(TAG, "TTS init failed: " + status);
                return;
            }
            try {
                int r = tts.setLanguage(new Locale("ar"));
                if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
                    Log.w(TAG, "Arabic TTS not available, using default voice");
                }
                android.content.SharedPreferences voicePrefs = getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE);
                tts.setSpeechRate(voicePrefs.getFloat(NovaVoiceSettingsActivity.PREF_SPEECH_RATE, 1.0f));
                String preferredVoice = voicePrefs.getString(NovaVoiceSettingsActivity.PREF_SYSTEM_VOICE, "");
                if (preferredVoice != null && !preferredVoice.isEmpty() && tts.getVoices() != null) {
                    for (android.speech.tts.Voice voice : tts.getVoices()) {
                        if (voice != null && preferredVoice.equals(voice.getName())
                                && voice.getLocale() != null
                                && "ar".equalsIgnoreCase(voice.getLocale().getLanguage())) {
                            tts.setVoice(voice);
                            break;
                        }
                    }
                }
                tts.setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build());
                tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                    @Override public void onStart(String id) { speaking = true; }
                    @Override public void onDone(String id) { speaking = false; }
                    @Override public void onError(String id) { speaking = false; }
                });
                ttsReady = true;
                if (pendingSpeech != null) {
                    String p = pendingSpeech;
                    pendingSpeech = null;
                    pendingIsTime = false;
                    speakNow(p);
                }
            } catch (Exception e) { Log.e(TAG, "TTS setup", e); }
        });
    }

    private boolean isDiiVoiceSelected() {
        return "embedded_dii".equals(getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE)
                .getString(NovaVoiceSettingsActivity.PREF_VOICE_MODE, "system"));
    }

    private void speak(String text) {
        speakNow(text);
    }

    private void speakNow(String text) {
        if (isDiiVoiceSelected()) {
            float speed = getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE)
                    .getFloat(NovaVoiceSettingsActivity.PREF_SPEECH_RATE, 1.0f);
            speaking = true;
            DiiTtsEngine.speak(this, text, speed, new DiiTtsEngine.Callback() {
                @Override public void onComplete() {
                    handler.post(() -> speaking = false);
                }
                @Override public void onError(String message) {
                    Log.e(TAG, "Dii voice failed: " + message + "; falling back to Android TTS");
                    handler.post(() -> {
                        speaking = false;
                        updateNovaNotification("تعذر تشغيل Dii؛ تم الرجوع إلى صوت النظام");
                        speakWithSystemTts(text);
                    });
                }
            });
            return;
        }
        speakWithSystemTts(text);
    }

    private void speakWithSystemTts(String text) {
        ensureTts();
        if (!ttsReady || tts == null) {
            pendingSpeech = text;
            pendingIsTime = false;
            return;
        }
        try {
            speaking = true; // تجاهل الميكروفون أثناء النطق
            int r = tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "caller");
            if (r != TextToSpeech.SUCCESS) speaking = false;
        } catch (Exception e) {
            speaking = false;
            Log.e(TAG, "speak", e);
        }
    }

    // ---------------- الرد بالإيماءات ----------------
    // زر الصوت وقت الرنين يمسكه النظام ولا يمرره لأي تطبيق، فنستعمل حساس القرب والتسارع بدلاً منه.

    private void startGestures() {
        if (gestureListener != null) return;
        android.content.SharedPreferences sp = getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE);
        boolean wave = sp.getBoolean(MainActivity.PREF_WAVE, true);
        boolean shake = sp.getBoolean(MainActivity.PREF_SHAKE, false);
        if (!wave && !shake) return;
        SensorManager sm = (SensorManager) getSystemService(SENSOR_SERVICE);
        if (sm == null) return;
        sensorManager = sm;
        ringStartMs = SystemClock.uptimeMillis();
        nearSince = 0; shakeCount = 0; lastShake = 0;
        gestureListener = new SensorEventListener() {
            @Override public void onSensorChanged(SensorEvent e) {
                if (!ringingNow) return;
                long now = SystemClock.uptimeMillis();
                if (e.sensor.getType() == Sensor.TYPE_PROXIMITY) {
                    boolean near = e.values[0] < e.sensor.getMaximumRange();
                    if (near) {
                        if (nearSince == 0) nearSince = now;
                    } else if (nearSince != 0) {
                        long d = now - nearSince;
                        nearSince = 0;
                        // تغطية قصيرة ثم رفع اليد = تلويح
                        if (d >= 60 && d <= 1500 && now - ringStartMs > 600) gestureAnswer("تلويح");
                    }
                } else if (e.sensor.getType() == Sensor.TYPE_ACCELEROMETER) {
                    double g = Math.sqrt(e.values[0] * e.values[0] + e.values[1] * e.values[1] + e.values[2] * e.values[2]) / 9.81;
                    if (g > 2.6 && now - lastShake > 150) {
                        shakeCount = (now - lastShake < 900) ? shakeCount + 1 : 1;
                        lastShake = now;
                        if (shakeCount >= 3 && now - ringStartMs > 600) { shakeCount = 0; gestureAnswer("هز"); }
                    }
                }
            }
            @Override public void onAccuracyChanged(Sensor s, int a) {}
        };
        try {
            if (wave) {
                Sensor prox = sm.getDefaultSensor(Sensor.TYPE_PROXIMITY);
                if (prox != null) sm.registerListener(gestureListener, prox, SensorManager.SENSOR_DELAY_NORMAL);
                else Log.w(TAG, "no proximity sensor");
            }
            if (shake) {
                Sensor acc = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
                if (acc != null) sm.registerListener(gestureListener, acc, SensorManager.SENSOR_DELAY_GAME);
            }
        } catch (Exception e) { Log.e(TAG, "gestures", e); }
    }

    private void stopGestures() {
        if (sensorManager != null && gestureListener != null) {
            try { sensorManager.unregisterListener(gestureListener); } catch (Exception ignored) {}
        }
        gestureListener = null;
        nearSince = 0;
    }

    private void gestureAnswer(String how) {
        if (!ringingNow) return;
        stopGestures();
        vibrate(120);
        CallController.answerCall(VoiceCommandService.this);
        CallController.note("بدأ بـ " + how);
    }

    // ---------------- NOVA AirWake: screen-off proximity gesture ----------------

    private boolean airWakeEnabled() {
        return getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE)
                .getBoolean(MainActivity.PREF_AIRWAKE_ENABLED, false);
    }

    private void syncAirWakeMonitoring() {
        if (!airWakeEnabled()) {
            stopAirWakeSensor();
            if (airWakeReceiverRegistered && airWakeScreenReceiver != null) {
                try { unregisterReceiver(airWakeScreenReceiver); } catch (Exception ignored) { }
                airWakeReceiverRegistered = false;
            }
            return;
        }

        if (!airWakeReceiverRegistered) {
            airWakeScreenReceiver = new BroadcastReceiver() {
                @Override public void onReceive(Context context, Intent intent) {
                    if (intent == null) return;
                    if (Intent.ACTION_SCREEN_OFF.equals(intent.getAction())) {
                        startAirWakeSensor();
                    } else if (Intent.ACTION_SCREEN_ON.equals(intent.getAction())) {
                        stopAirWakeSensor();
                    }
                }
            };
            IntentFilter filter = new IntentFilter();
            filter.addAction(Intent.ACTION_SCREEN_OFF);
            filter.addAction(Intent.ACTION_SCREEN_ON);
            try {
                if (Build.VERSION.SDK_INT >= 33) {
                    registerReceiver(airWakeScreenReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
                } else {
                    registerReceiver(airWakeScreenReceiver, filter);
                }
                airWakeReceiverRegistered = true;
            } catch (Exception e) {
                Log.e(TAG, "AirWake screen receiver registration failed", e);
            }
        }

        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        if (pm != null && !pm.isInteractive()) startAirWakeSensor();
        else stopAirWakeSensor();
        if (!novaEnabled()) {
            updateNovaNotification(airWakeEnabled()
                    ? "AirWake نشط • مرّتان فوق مستشعر القرب لتبديل NOVA"
                    : "جاهز لاستقبال المكالمات");
        }
    }

    private void startAirWakeSensor() {
        if (!airWakeEnabled() || ringingNow || CallController.inCall(this) || airWakeSensorRegistered) return;
        SensorManager sm = (SensorManager) getSystemService(SENSOR_SERVICE);
        if (sm == null) return;
        Sensor proximity = sm.getDefaultSensor(Sensor.TYPE_PROXIMITY);
        if (proximity == null || !proximity.isWakeUpSensor()) {
            Log.w(TAG, "AirWake unavailable: no wake-up proximity sensor");
            updateNovaNotification("AirWake غير متاح: مستشعر القرب لا يدعم الإيقاظ والشاشة مطفأة");
            return;
        }
        airWakeSensorManager = sm;
        airWakeProximitySensor = proximity;
        airWakeSensorStateKnown = false;
        airWakeNear = false;
        airWakeReady = false;
        airWakeNearSince = 0L;
        airWakeFarSince = 0L;
        airWakeLastPass = 0L;
        airWakePassCount = 0;
        airWakeListener = new SensorEventListener() {
            @Override public void onSensorChanged(SensorEvent event) {
                if (!airWakeEnabled() || ringingNow || CallController.inCall(VoiceCommandService.this)) return;
                if (event.values == null || event.values.length == 0) return;
                long now = SystemClock.elapsedRealtime();
                boolean near = event.values[0] < event.sensor.getMaximumRange();

                if (!airWakeSensorStateKnown) {
                    airWakeSensorStateKnown = true;
                    airWakeNear = near;
                    if (!near) beginAirWakeFarBaseline(now);
                    else Log.d(TAG, "AirWake pocket guard: sensor initially covered; waiting for clear state");
                    return;
                }
                if (near == airWakeNear) return;
                airWakeNear = near;
                if (near) {
                    handler.removeCallbacks(airWakeArmRunnable);
                    airWakeNearSince = airWakeReady ? now : 0L;
                    airWakeFarSince = 0L;
                    return;
                }

                // A valid pass is a short cover followed by a clear sensor.
                if (airWakeReady && airWakeNearSince > 0L) {
                    long coveredFor = now - airWakeNearSince;
                    if (coveredFor >= AIRWAKE_PULSE_MIN_MS && coveredFor <= AIRWAKE_PULSE_MAX_MS) {
                        long gap = airWakeLastPass > 0L ? now - airWakeLastPass : -1L;
                        if (airWakeLastPass > 0L
                                && gap >= AIRWAKE_MIN_GAP_MS
                                && gap <= AIRWAKE_MAX_GAP_MS
                                && now - airWakeLastPass <= AIRWAKE_SEQUENCE_MS) {
                            airWakePassCount++;
                        } else {
                            airWakePassCount = 1;
                        }
                        airWakeLastPass = now;
                        Log.d(TAG, "AirWake pass=" + airWakePassCount + " coveredMs=" + coveredFor);
                        if (airWakePassCount >= 2) {
                            airWakePassCount = 0;
                            airWakeLastPass = 0L;
                            airWakeReady = false;
                            toggleNovaFromAirWake(now);
                        }
                    } else {
                        airWakePassCount = 0;
                        airWakeLastPass = 0L;
                    }
                }
                airWakeNearSince = 0L;
                beginAirWakeFarBaseline(now);
            }
            @Override public void onAccuracyChanged(Sensor sensor, int accuracy) { }
        };
        try {
            airWakeSensorRegistered = sm.registerListener(airWakeListener, proximity, SensorManager.SENSOR_DELAY_NORMAL);
            if (airWakeSensorRegistered) {
                Log.i(TAG, "AirWake proximity wake-up sensor armed for screen-off use");
            } else {
                Log.w(TAG, "AirWake proximity listener registration failed");
                updateNovaNotification("تعذر تفعيل مستشعر AirWake على هذا الهاتف");
            }
        } catch (Exception e) {
            airWakeSensorRegistered = false;
            Log.e(TAG, "AirWake sensor registration failed", e);
        }
    }

    private void beginAirWakeFarBaseline(long now) {
        airWakeFarSince = now;
        handler.removeCallbacks(airWakeArmRunnable);
        handler.postDelayed(airWakeArmRunnable, AIRWAKE_ARM_DELAY_MS);
    }

    private void stopAirWakeSensor() {
        handler.removeCallbacks(airWakeArmRunnable);
        if (airWakeSensorManager != null && airWakeListener != null) {
            try { airWakeSensorManager.unregisterListener(airWakeListener); } catch (Exception ignored) { }
        }
        airWakeListener = null;
        airWakeProximitySensor = null;
        airWakeSensorManager = null;
        airWakeSensorRegistered = false;
        airWakeSensorStateKnown = false;
        airWakeNear = false;
        airWakeReady = false;
        airWakeNearSince = 0L;
        airWakeFarSince = 0L;
        airWakePassCount = 0;
        airWakeLastPass = 0L;
    }

    private void toggleNovaFromAirWake(long now) {
        if (now - airWakeLastToggle < AIRWAKE_TOGGLE_COOLDOWN_MS) return;
        airWakeLastToggle = now;
        if (ringingNow || CallController.inCall(this)) return;
        boolean enable = !novaEnabled();
        if (enable && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            updateNovaNotification("تعذر تشغيل NOVA: إذن الميكروفون غير متاح؛ افتح التطبيق لمنحه");
            return;
        }
        getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE).edit()
                .putBoolean(MainActivity.PREF_NOVA_ENABLED, enable).apply();
        syncNovaListening();
        vibrate(90);
        updateNovaNotification(enable ? "تم تشغيل NOVA بإيماءة AirWake" : "تم إيقاف NOVA بإيماءة AirWake");
        Log.i(TAG, "AirWake toggled NOVA=" + enable);
    }

    // ---------------- NOVA: اكتشاف النداء والتعرف العربي (المرحلة الأولى) ----------------

    private boolean novaEnabled() {
        return getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE)
                .getBoolean(MainActivity.PREF_NOVA_ENABLED, false);
    }

    private void syncNovaListening() {
        if (!foregroundOk || ringingNow || !novaEnabled()
                || checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            stopNovaListening();
            return;
        }
        if (novaSpeechLoop != null) return;
        novaCommandExecutor = new NovaCommandExecutor(this);
        novaSpeechLoop = new NovaSpeechLoop(this, new NovaSpeechLoop.Listener() {
            @Override public void onWakeWord() {
                Log.i(TAG, "NOVA wake word detected");
                vibrate(70);
                speak("نعم");
                updateNovaNotification("نوفا سمعت النداء • بانتظار الأمر");
            }

            @Override public void onCommand(NovaCommand command) {
                Log.i(TAG, "NOVA parsed command=" + command.type + " text=" + command.originalText);
                if (command.type == NovaCommand.Type.STOP_LISTENING && novaSpeechLoop != null) {
                    novaSpeechLoop.pauseCommands();
                } else if (command.type == NovaCommand.Type.RESUME_LISTENING && novaSpeechLoop != null) {
                    novaSpeechLoop.resumeCommands();
                }
                if (command.type == NovaCommand.Type.MULTI_STEP) {
                    novaCommandExecutor.executeSequenceAsync(command, response -> {
                        updateNovaNotification(response);
                        speak(response);
                    });
                    return;
                }
                String response = novaCommandExecutor.execute(command);
                updateNovaNotification(response);
                speak(response);
            }

            @Override public void onStatus(String status) {
                Log.i(TAG, "NOVA: " + status);
                if (status.startsWith("خطأ") || status.startsWith("سمعت")) updateNovaNotification(status);
            }
        });
        novaSpeechLoop.start();
    }

    private void stopNovaListening() {
        if (novaSpeechLoop != null) {
            novaSpeechLoop.stop();
            novaSpeechLoop = null;
        }
    }

    private void updateNovaNotification(String message) {
        try {
            Notification n = new Notification.Builder(this, CH_ID)
                    .setContentTitle("NOVA – المساعد الذكي")
                    .setContentText(message)
                    .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                    .setOngoing(true).build();
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.notify(NOTIF_ID, n);
        } catch (Exception e) { Log.w(TAG, "NOVA notification", e); }
    }

    // ---------------- الالتقاط الصوتي ----------------

    private void startCapture() {
        synchronized (lock) {
            if (capturing) return;
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return;
            final int sr = MfccExtractor.SAMPLE_RATE;
            try {
                int min = AudioRecord.getMinBufferSize(sr, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
                int bufBytes = Math.max(min, sr * 2 * 2);
                final AudioRecord ar = new AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, sr,
                        AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufBytes);
                if (ar.getState() != AudioRecord.STATE_INITIALIZED) { ar.release(); return; }
                audioRecord = ar;
                capturing = true;
                new Thread(() -> captureLoop(ar), "voice-capture").start();
            } catch (Exception e) {
                Log.e(TAG, "start", e);
                capturing = false;
                audioRecord = null;
            }
        }
    }

    private void captureLoop(AudioRecord ar) {
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO);
        final int sr = MfccExtractor.SAMPLE_RATE;
        short[] chunk = new short[sr / 4];
        short[] window = new short[sr * 2];
        int filled = 0;
        int hits = 0;
        int zeroChunks = 0;
        try {
            ar.startRecording();
            while (capturing && audioRecord == ar) {
                int r = ar.read(chunk, 0, chunk.length);
                if (r < 0) { Log.e(TAG, "read error " + r); break; }
                if (r == 0) continue;
                // صمت رقمي تام لعدة ثوانٍ = النظام يكتم الميكروفون عنا (الخدمة بدأت من الخلفية على أندرويد 11+)
                boolean allZero = true;
                for (int k = 0; k < r; k++) if (chunk[k] != 0) { allZero = false; break; }
                zeroChunks = allZero ? zeroChunks + 1 : 0;
                if (zeroChunks == 8 && !micMutedWarned) {
                    micMutedWarned = true;
                    Log.w(TAG, "microphone appears muted by system (service started from background)");
                    BootReceiver.showRestartNotification(VoiceCommandService.this);
                }
                // أثناء نطق اسم المتصل نتجاهل الصوت حتى لا يلتقط الميكروفون صوت القارئ
                if (speaking) { filled = 0; continue; }
                System.arraycopy(window, r, window, 0, window.length - r);
                System.arraycopy(chunk, 0, window, window.length - r, r);
                filled = Math.min(window.length, filled + r);
                if (filled < window.length) continue;
                if (rms(window) < MfccExtractor.MIN_RMS) continue;

                float[][] test = MfccExtractor.extract(window);
                float thr = threshold;
                float dist = DtwMatcher.minScore(test, templates);
                Log.d(TAG, "score=" + dist + " thr=" + thr);
                hits = (dist < thr) ? hits + 1 : 0;
                if (hits >= 2) {
                    capturing = false;
                    vibrate(120);
                    CallController.answerCall(VoiceCommandService.this);
                    break;
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "loop", e);
        } finally {
            try { ar.stop(); } catch (Exception ignored) {}
            try { ar.release(); } catch (Exception ignored) {}
            synchronized (lock) {
                if (audioRecord == ar) { audioRecord = null; capturing = false; }
            }
        }
    }

    private void stopCapture() {
        AudioRecord ar;
        synchronized (lock) { capturing = false; ar = audioRecord; }
        if (ar != null) { try { ar.stop(); } catch (Exception ignored) {} }
    }

    private float rms(short[] s) {
        double sum = 0;
        for (short v : s) sum += (double) v * v;
        return (float) Math.sqrt(sum / s.length);
    }

    private void vibrate(int ms) {
        try {
            Vibrator v = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
            if (v == null) return;
            v.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE));
        } catch (Exception ignored) {}
    }

    @Override public int onStartCommand(Intent i, int f, int s) {
        if (!foregroundOk) { stopSelf(); return START_NOT_STICKY; }
        if (i != null && ACTION_SAY_TIME.equals(i.getAction())) {
            sayTime();
            return START_STICKY;
        }
        if (i != null && ACTION_NOVA_CONFIG_CHANGED.equals(i.getAction())) {
            // أعد إنشاء محرك TTS كي تُطبّق اختيارات الصوت والسرعة فوراً.
            if (tts != null) {
                try { tts.stop(); tts.shutdown(); } catch (Exception ignored) { }
                tts = null;
            }
            ttsReady = false;
            if (announceEnabled() && !isDiiVoiceSelected()) ensureTts();
            DiiTtsEngine.stop();
            syncNovaListening();
            syncAirWakeMonitoring();
            return START_STICKY;
        }
        if (i != null && ACTION_ANNOUNCE.equals(i.getAction())) {
            handleAnnounceIntent(i);
            return START_STICKY;
        }
        loadTemplates();
        return START_STICKY;
    }

    @Override public void onDestroy() {
        sRunning = false;
        sInstance = null;
        handler.removeCallbacksAndMessages(null);
        stopGestures();
        stopAirWakeSensor();
        if (airWakeReceiverRegistered && airWakeScreenReceiver != null) {
            try { unregisterReceiver(airWakeScreenReceiver); } catch (Exception ignored) { }
            airWakeReceiverRegistered = false;
        }
        stopCapture();
        stopNovaListening();
        DiiTtsEngine.stop();
        if (tts != null) {
            try { tts.stop(); tts.shutdown(); } catch (Exception ignored) {}
            tts = null;
        }
        if (telephonyManager != null) {
            try {
                if (Build.VERSION.SDK_INT >= 31 && modernCallback != null) {
                    telephonyManager.unregisterTelephonyCallback((TelephonyCallback) modernCallback);
                } else if (legacyListener != null) {
                    telephonyManager.listen(legacyListener, PhoneStateListener.LISTEN_NONE);
                }
            } catch (Exception e) { Log.w(TAG, "unregister", e); }
        }
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent i) { return null; }
}
