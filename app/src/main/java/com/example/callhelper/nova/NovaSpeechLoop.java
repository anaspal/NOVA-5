package com.example.callhelper.nova;

import android.content.Context;
import android.content.Intent;
import android.media.AudioManager;
import android.os.Build;
import android.os.Bundle;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.util.Log;

import java.util.ArrayList;

/**
 * حلقة تعرف صوتي مستمرة نسبياً: تستمع محلياً متى توفر محرك on-device،
 * وتعيد فتح جلسة قصيرة بعد كل نتيجة. لا تنفذ الأوامر في هذه المرحلة.
 */
public final class NovaSpeechLoop implements RecognitionListener {
    public interface Listener {
        void onWakeWord();
        void onCommand(NovaCommand command);
        void onStatus(String status);
    }

    private static final String TAG = "NovaSpeechLoop";
    private final Context appContext;
    private final Listener listener;
    private SpeechRecognizer recognizer;
    private boolean running;
    private boolean waitingForCommand;
    private boolean restarting;
    private boolean paused;
    private long conversationUntilMs;
    private boolean usingOnDevice;
    private int consecutiveErrors;
    private boolean beepMuted;
    private boolean systemWasMuted;
    private boolean notifWasMuted;
    private static final long CONVERSATION_WINDOW_MS = 12000L;
    // رموز لغة عربية نجرّبها بالترتيب إذا رفض المحرك العادي الرمز السابق (خطأ 12/13).
    private static final String[] LANGS = {"ar-PS", "ar-SA", "ar-JO", "ar-EG", "ar"};
    private int langIndex = 0;
    private boolean languageExhausted = false;

    // ---- التعرف المضمّن (Moonshine العربي داخل التطبيق، يعمل بدون إنترنت) ----
    private static final long MODE_CHECK_MS = 8000L;
    private final android.os.Handler mainHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private MoonshineAsrEngine embedded;
    private boolean usingEmbedded;
    private boolean embeddedFallback;  // جوجل لا يدعم العربية/الشبكة تتعطل: نبقى على المضمّن حتى الإيقاف
    private boolean embeddedBroken;    // المضمّن فشل (ذاكرة/ملفات): لا نعيد المحاولة في هذه الجلسة
    private final Runnable modeWatcher = new Runnable() {
        @Override public void run() {
            if (!running) return;
            checkMode();
            mainHandler.postDelayed(this, MODE_CHECK_MS);
        }
    };

    public NovaSpeechLoop(Context context, Listener listener) {
        this.appContext = context.getApplicationContext();
        this.listener = listener;
    }

    public static boolean isAvailable(Context context) {
        return SpeechRecognizer.isRecognitionAvailable(context) || MoonshineAsrEngine.isPackaged(context);
    }

    public void start() {
        if (running) return;
        boolean packaged = MoonshineAsrEngine.isPackaged(appContext);
        boolean googleOk = SpeechRecognizer.isRecognitionAvailable(appContext);
        if (!googleOk && !packaged) {
            listener.onStatus("لا يوجد محرك تعرف على الكلام مثبت على الجهاز");
            return;
        }
        try {
            if (wantEmbedded(packaged, googleOk)) {
                running = true;
                startEmbedded();
            } else {
                createRecognizer(true);
                running = true;
                muteBeep();
                startSession();
            }
            mainHandler.postDelayed(modeWatcher, MODE_CHECK_MS);
        } catch (Exception e) {
            Log.e(TAG, "Unable to start speech recognizer", e);
            listener.onStatus("خطأ: تعذر تشغيل التعرف الصوتي: " + e.getMessage());
            stop();
        }
    }

    /** ينشئ المتعرف؛ المحلي أولاً إن أمكن، وإلا محرك الجهاز العادي. */
    private void createRecognizer(boolean allowOnDevice) {
        if (recognizer != null) {
            try { recognizer.cancel(); } catch (Exception ignored) { }
            try { recognizer.destroy(); } catch (Exception ignored) { }
            recognizer = null;
        }
        if (allowOnDevice && Build.VERSION.SDK_INT >= 31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(appContext)) {
            recognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(appContext);
            usingOnDevice = true;
            listener.onStatus("التعرف الصوتي المحلي متاح");
        } else {
            recognizer = SpeechRecognizer.createSpeechRecognizer(appContext);
            usingOnDevice = false;
            listener.onStatus("يعمل محرك التعرف العادي للجهاز");
        }
        recognizer.setRecognitionListener(this);
    }

    /** يكتم صوت "بيب" بدء الاستماع الذي يتكرر مع كل جلسة (يُعاد الصوت عند الإيقاف). */
    private void muteBeep() {
        if (beepMuted) return;
        try {
            AudioManager am = (AudioManager) appContext.getSystemService(Context.AUDIO_SERVICE);
            if (am == null) return;
            systemWasMuted = Build.VERSION.SDK_INT >= 23 && am.isStreamMute(AudioManager.STREAM_SYSTEM);
            notifWasMuted = Build.VERSION.SDK_INT >= 23 && am.isStreamMute(AudioManager.STREAM_NOTIFICATION);
            if (!systemWasMuted) am.adjustStreamVolume(AudioManager.STREAM_SYSTEM, AudioManager.ADJUST_MUTE, 0);
            if (!notifWasMuted) am.adjustStreamVolume(AudioManager.STREAM_NOTIFICATION, AudioManager.ADJUST_MUTE, 0);
            beepMuted = true;
        } catch (Exception e) {
            Log.w(TAG, "mute beep failed", e);
        }
    }

    private void unmuteBeep() {
        if (!beepMuted) return;
        beepMuted = false;
        try {
            AudioManager am = (AudioManager) appContext.getSystemService(Context.AUDIO_SERVICE);
            if (am == null) return;
            if (!systemWasMuted) am.adjustStreamVolume(AudioManager.STREAM_SYSTEM, AudioManager.ADJUST_UNMUTE, 0);
            if (!notifWasMuted) am.adjustStreamVolume(AudioManager.STREAM_NOTIFICATION, AudioManager.ADJUST_UNMUTE, 0);
        } catch (Exception e) {
            Log.w(TAG, "unmute beep failed", e);
        }
    }

    private void startSession() {
        if (!running || usingEmbedded || recognizer == null || restarting) return;
        try {
            Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
            String lang = LANGS[Math.min(langIndex, LANGS.length - 1)];
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang);
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, lang);
            // «دون إنترنت» يُطلب فقط مع المتعرف المحلي؛ إرساله لمحرك جوجل العادي بلا حزمة عربية محمّلة يسبب خطأ 12.
            // دون إنترنت: نطلب التعرف المحلي (يستعمل حزمة العربية المحمّلة في تطبيق Google). مع وجود إنترنت لا نفرضه.
            if (usingOnDevice || !hasInternet()) intent.putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true);
            intent.putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, appContext.getPackageName());
            intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3);
            intent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false);
            intent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1200);
            recognizer.startListening(intent);
        } catch (Exception e) {
            Log.w(TAG, "startListening failed", e);
            scheduleRestart();
        }
    }

    private boolean hasInternet() {
        try {
            android.net.ConnectivityManager cm = (android.net.ConnectivityManager)
                    appContext.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return true;
            android.net.Network n = cm.getActiveNetwork();
            if (n == null) return false;
            android.net.NetworkCapabilities caps = cm.getNetworkCapabilities(n);
            return caps != null
                    && caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    && caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED);
        } catch (Exception e) {
            return true;
        }
    }

    /** المضمّن عند: عدم وجود إنترنت فعلي، أو جوجل غير متاح/فاشل بالعربية. وجود إنترنت = جوجل (أدق للهجات). */
    private boolean wantEmbedded(boolean packaged, boolean googleOk) {
        if (!packaged || embeddedBroken) return false;
        return !googleOk || embeddedFallback || !hasInternet();
    }

    /** يُستدعى كل بضع ثوانٍ وعند أخطاء جوجل: يبدّل بين المحركين حسب حالة الشبكة. */
    private void checkMode() {
        if (!running) return;
        boolean packaged = MoonshineAsrEngine.isPackaged(appContext);
        boolean googleOk = SpeechRecognizer.isRecognitionAvailable(appContext);
        boolean want = wantEmbedded(packaged, googleOk);
        if (want && !usingEmbedded) {
            listener.onStatus("لا يوجد إنترنت: انتقلت للتعرف المضمّن");
            startEmbedded();
        } else if (!want && usingEmbedded && googleOk) {
            listener.onStatus("عاد الإنترنت: رجعت لمحرك جوجل");
            stopEmbedded();
            try {
                createRecognizer(true);
                muteBeep();
                startSession();
            } catch (Exception e) {
                Log.w(TAG, "switch to google failed", e);
                listener.onStatus("خطأ: تعذر الرجوع لمحرك جوجل: " + e.getMessage());
            }
        }
    }

    private void startEmbedded() {
        if (recognizer != null) {
            try { recognizer.cancel(); } catch (Exception ignored) { }
            try { recognizer.destroy(); } catch (Exception ignored) { }
            recognizer = null;
        }
        unmuteBeep();
        usingOnDevice = false;
        usingEmbedded = true;
        embedded = new MoonshineAsrEngine(appContext, new MoonshineAsrEngine.Callback() {
            @Override public void onText(String text) {
                mainHandler.post(() -> {
                    if (!running || !usingEmbedded) return;
                    consecutiveErrors = 0;
                    handleText(text);
                });
            }
            @Override public void onStatus(String status) {
                mainHandler.post(() -> { if (running && usingEmbedded) listener.onStatus(status); });
            }
            @Override public void onFatal(String message) {
                mainHandler.post(() -> onEmbeddedFatal(message));
            }
        });
        embedded.start();
    }

    private void stopEmbedded() {
        usingEmbedded = false;
        if (embedded != null) {
            embedded.stop();
            embedded = null;
        }
    }

    private void onEmbeddedFatal(String message) {
        if (!running || !usingEmbedded) return;
        embeddedBroken = true;
        stopEmbedded();
        if (SpeechRecognizer.isRecognitionAvailable(appContext)) {
            listener.onStatus("خطأ: " + message + " — رجعت لمحرك جوجل");
            try {
                createRecognizer(true);
                muteBeep();
                startSession();
            } catch (Exception e) {
                listener.onStatus("خطأ: تعذر تشغيل أي محرك تعرف: " + e.getMessage());
                stop();
            }
        } else {
            listener.onStatus("خطأ: " + message);
            stop();
        }
    }

    private void scheduleRestart() {
        if (!running || restarting) return;
        restarting = true;
        // SpeechRecognizer callbacks arrive on the main thread; defer without a tight retry loop.
        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
            restarting = false;
            startSession();
        }, Math.min(3000L, 700L + consecutiveErrors * 400L));
    }

    private void handleText(String text) {
        if (text == null || text.trim().isEmpty()) return;
        listener.onStatus("سمعت: " + text);
        boolean wake = NovaCommandParser.containsWakeWord(text);
        long now = android.os.SystemClock.elapsedRealtime();
        if (wake) {
            String afterWake = NovaCommandParser.removeWakeWord(text);
            conversationUntilMs = now + CONVERSATION_WINDOW_MS;
            if (paused) {
                if (afterWake.isEmpty()) {
                    waitingForCommand = true;
                    conversationUntilMs = now + CONVERSATION_WINDOW_MS;
                    listener.onWakeWord();
                    return;
                }
                NovaCommand resume = NovaCommandParser.parse(text);
                if (resume.type == NovaCommand.Type.RESUME_LISTENING) {
                    paused = false;
                    waitingForCommand = false;
                    conversationUntilMs = 0L;
                    listener.onCommand(resume);
                } else {
                    waitingForCommand = false;
                    conversationUntilMs = 0L;
                    listener.onStatus("الاستماع متوقف للأوامر؛ قل: نوفا استأنفي الاستماع");
                }
                return;
            }
            if (afterWake.isEmpty()) {
                waitingForCommand = true;
                listener.onWakeWord();
                return;
            }
            waitingForCommand = true;
            listener.onCommand(NovaCommandParser.parse(text));
            return;
        }
        // أثناء وضع التوقف نقبل كلمة الاستئناف فقط، ولا ننفذ أي أمر آخر.
        if (paused && waitingForCommand && now <= conversationUntilMs) {
            NovaCommand resume = NovaCommandParser.parse(text);
            waitingForCommand = false;
            conversationUntilMs = 0L;
            if (resume.type == NovaCommand.Type.RESUME_LISTENING) {
                paused = false;
                listener.onCommand(resume);
            } else {
                listener.onStatus("لم أستأنف الاستماع؛ قل: استأنفي الاستماع");
            }
            return;
        }
        // بعد نداء NOVA، تقبل الأوامر التالية لفترة قصيرة دون تكرار كلمة التنبيه.
        if (waitingForCommand && now <= conversationUntilMs) {
            conversationUntilMs = now + CONVERSATION_WINDOW_MS;
            NovaCommand command = NovaCommandParser.parse(text);
            if (!paused) listener.onCommand(command);
            return;
        }
        waitingForCommand = false;
        conversationUntilMs = 0L;
    }

    /** يوقف تنفيذ الأوامر مع إبقاء جلسة الاستماع لكلمة الاستئناف فقط. */
    public void pauseCommands() {
        paused = true;
        waitingForCommand = false;
        conversationUntilMs = 0L;
    }

    public void resumeCommands() {
        paused = false;
        waitingForCommand = false;
        conversationUntilMs = 0L;
    }

    public boolean isPaused() { return paused; }

    public void stop() {
        running = false;
        mainHandler.removeCallbacks(modeWatcher);
        stopEmbedded();
        unmuteBeep();
        paused = false;
        waitingForCommand = false;
        conversationUntilMs = 0L;
        restarting = false;
        if (recognizer != null) {
            try { recognizer.cancel(); } catch (Exception ignored) { }
            try { recognizer.destroy(); } catch (Exception ignored) { }
            recognizer = null;
        }
    }

    @Override public void onReadyForSpeech(Bundle params) { }
    @Override public void onBeginningOfSpeech() { }
    @Override public void onRmsChanged(float rmsdB) { }
    @Override public void onBufferReceived(byte[] buffer) { }
    @Override public void onEndOfSpeech() { }
    @Override public void onError(int error) {
        if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
            listener.onStatus("خطأ: إذن الميكروفون غير متاح");
            stop();
            return;
        }
        if (error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
            consecutiveErrors = 0; // صمت طبيعي، ليس عطلاً
            scheduleRestart();
            return;
        }
        consecutiveErrors++;
        Log.w(TAG, "recognizer error=" + error + " onDevice=" + usingOnDevice);
        if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY && recognizer != null) {
            try { recognizer.cancel(); } catch (Exception ignored) { }
        }
        // اللغة العربية غير متوفرة محلياً (12/13) أو تعطل المتعرف (5): انتقل لمحرك الجهاز العادي
        boolean languageProblem = error == 12 || error == 13;
        if (languageProblem && !usingOnDevice && langIndex < LANGS.length - 1) {
            langIndex++;
            listener.onStatus("جرّبت رمز لغة آخر: " + LANGS[langIndex]);
            scheduleRestart();
            return;
        }
        if ((languageProblem || error == SpeechRecognizer.ERROR_CLIENT || consecutiveErrors >= 4) && usingOnDevice) {
            try {
                createRecognizer(false);
                listener.onStatus("خطأ " + error + ": انتقلت لمحرك التعرف العادي (قد يحتاج اتصالاً بالإنترنت)");
            } catch (Exception e) {
                listener.onStatus("خطأ: تعذر تبديل المتعرف: " + e.getMessage());
            }
        } else if ((error == SpeechRecognizer.ERROR_NETWORK || error == SpeechRecognizer.ERROR_NETWORK_TIMEOUT
                || error == SpeechRecognizer.ERROR_SERVER) && consecutiveErrors >= 3
                && MoonshineAsrEngine.isPackaged(appContext) && !embeddedBroken) {
            embeddedFallback = true;
            checkMode();
            return;
        } else if (error == SpeechRecognizer.ERROR_NETWORK || error == SpeechRecognizer.ERROR_NETWORK_TIMEOUT
                || error == SpeechRecognizer.ERROR_SERVER) {
            listener.onStatus("خطأ " + error + ": الحزمة العربية للتعرف دون اتصال غير محمّلة؛ حمّلها مرة واحدة بإنترنت مؤقت");
        } else if ((error == 12 || error == 13) && MoonshineAsrEngine.isPackaged(appContext) && !embeddedBroken) {
            embeddedFallback = true;
            listener.onStatus("محرك جوجل لا يجد العربية؛ انتقلت للتعرف المضمّن");
            checkMode();
            return;
        } else if (error == 12 || error == 13) {
            if (!languageExhausted) {
                languageExhausted = true;
                listener.onStatus(hasInternet()
                        ? "خطأ " + error + ": محرك جوجل لا يجد العربية. حدّث تطبيق Google وحمّل العربية للتعرف دون إنترنت"
                        : "خطأ " + error + ": لا يوجد إنترنت ولا حزمة عربية محمّلة. اتصل بإنترنت مرة واحدة وحمّل العربية دون اتصال في تطبيق Google");
            } else {
                listener.onStatus("خطأ " + error + ": العربية غير متاحة للتعرف حالياً");
            }
        } else {
            listener.onStatus("خطأ في التعرف الصوتي رقم " + error);
        }
        scheduleRestart();
    }
    @Override public void onResults(Bundle results) {
        ArrayList<String> matches = results == null ? null : results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        consecutiveErrors = 0;
        if (matches != null) for (String m : matches) { if (NovaCommandParser.containsWakeWord(m) || waitingForCommand || paused) { handleText(m); matches = null; break; } }
        if (matches != null && !matches.isEmpty()) handleText(matches.get(0));
        scheduleRestart();
    }
    @Override public void onPartialResults(Bundle partialResults) { }
    @Override public void onEvent(int eventType, Bundle params) { }
}
