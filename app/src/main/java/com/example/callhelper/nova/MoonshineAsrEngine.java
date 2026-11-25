package com.example.callhelper.nova;

import android.content.Context;
import android.content.res.AssetManager;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Process;
import android.util.Log;

import com.k2fsa.sherpa.onnx.OfflineModelConfig;
import com.k2fsa.sherpa.onnx.OfflineMoonshineModelConfig;
import com.k2fsa.sherpa.onnx.OfflineRecognizer;
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig;
import com.k2fsa.sherpa.onnx.OfflineRecognizerResult;
import com.k2fsa.sherpa.onnx.OfflineStream;
import com.k2fsa.sherpa.onnx.SileroVadModelConfig;
import com.k2fsa.sherpa.onnx.SpeechSegment;
import com.k2fsa.sherpa.onnx.Vad;
import com.k2fsa.sherpa.onnx.VadModelConfig;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * تعرف صوتي عربي مضمّن داخل التطبيق (دون إنترنت ودون أي اعتماد على جوجل).
 *
 * النموذج: Moonshine Base Arabic (مكمّم) عبر sherpa-onnx. بما أنه نموذج غير متدفق (offline)،
 * نستخدم Silero VAD لتقطيع الكلام إلى جمل، ثم نفك ترميز كل جملة في خيط مستقل
 * حتى لا يضيع الكلام الذي يُقال أثناء المعالجة.
 *
 * الملفات المطلوبة في assets/moonshine-ar/ (يضعها الـ workflow):
 *   encoder_model.ort, decoder_model_merged.ort, tokens.txt, silero_vad.onnx
 */
public final class MoonshineAsrEngine {
    public interface Callback {
        /** يُستدعى من خيط فك الترميز (وليس من الخيط الرئيسي). */
        void onText(String text);
        void onStatus(String status);
        /** تعذر تشغيل المحرك (ملفات ناقصة، ذاكرة، ميكروفون...). المحرك متوقف بعدها. */
        void onFatal(String message);
    }

    private static final String TAG = "MoonshineAsr";
    private static final String ROOT = "moonshine-ar";
    private static final String ENCODER = ROOT + "/encoder_model.ort";
    private static final String DECODER = ROOT + "/decoder_model_merged.ort";
    private static final String TOKENS = ROOT + "/tokens.txt";
    private static final String VAD_MODEL = ROOT + "/silero_vad.onnx";

    private static final int SAMPLE_RATE = 16000;
    private static final int WINDOW = 512;               // نافذة Silero عند 16kHz
    private static final int MIN_SEGMENT_SAMPLES = 4800; // أقل من 0.3 ثانية = ضجيج، نتجاهله
    private static final int MAX_QUEUE = 4;

    private final Context appContext;
    private final Callback callback;
    private final ArrayBlockingQueue<float[]> queue = new ArrayBlockingQueue<>(MAX_QUEUE);
    private volatile boolean running;
    private Thread captureThread;
    private Thread decodeThread;

    public MoonshineAsrEngine(Context context, Callback callback) {
        this.appContext = context.getApplicationContext();
        this.callback = callback;
    }

    /** هل ملفات النموذج مضمّنة فعلاً داخل الـ APK؟ */
    public static boolean isPackaged(Context context) {
        try {
            String[] files = context.getAssets().list(ROOT);
            if (files == null) return false;
            Set<String> have = new HashSet<>(Arrays.asList(files));
            return have.contains("encoder_model.ort") && have.contains("decoder_model_merged.ort")
                    && have.contains("tokens.txt") && have.contains("silero_vad.onnx");
        } catch (Exception e) {
            return false;
        }
    }

    public synchronized void start() {
        if (running) return;
        running = true;
        queue.clear();
        decodeThread = new Thread(this::decodeLoop, "nova-moonshine-decode");
        decodeThread.setDaemon(true);
        captureThread = new Thread(this::captureLoop, "nova-moonshine-capture");
        captureThread.setDaemon(true);
        decodeThread.start();
        captureThread.start();
    }

    public synchronized void stop() {
        running = false;
        if (captureThread != null) captureThread.interrupt();
        if (decodeThread != null) decodeThread.interrupt();
        captureThread = null;
        decodeThread = null;
        queue.clear();
    }

    public boolean isRunning() { return running; }

    private void fatal(String message) {
        boolean wasRunning = running;
        running = false;
        if (wasRunning) callback.onFatal(message);
    }

    // ---------------- الالتقاط + كشف الكلام ----------------

    private void captureLoop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO);
        AudioRecord ar = null;
        Vad vad = null;
        try {
            AssetManager assets = appContext.getAssets();
            SileroVadModelConfig silero = new SileroVadModelConfig();
            silero.setModel(VAD_MODEL);
            silero.setThreshold(0.5f);
            silero.setMinSilenceDuration(0.6f);   // سكتة 0.6 ثانية = نهاية الجملة
            silero.setMinSpeechDuration(0.25f);
            silero.setWindowSize(WINDOW);
            silero.setMaxSpeechDuration(8.0f);    // أوامر قصيرة: نقطع أي كلام أطول من 8 ثوانٍ
            VadModelConfig vadConfig = new VadModelConfig();
            vadConfig.setSileroVadModelConfig(silero);
            vadConfig.setSampleRate(SAMPLE_RATE);
            vadConfig.setNumThreads(1);
            vadConfig.setProvider("cpu");
            vadConfig.setDebug(false);
            vad = new Vad(assets, vadConfig);

            int min = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT);
            int bufBytes = Math.max(min, SAMPLE_RATE * 2 * 2); // ~ثانيتان
            ar = new AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufBytes);
            if (ar.getState() != AudioRecord.STATE_INITIALIZED) {
                fatal("تعذر تهيئة الميكروفون للتعرف المضمّن");
                return;
            }
            ar.startRecording();
            callback.onStatus("التعرف الصوتي المضمّن (دون إنترنت) يستمع");

            short[] pcm = new short[WINDOW];
            float[] window = new float[WINDOW];
            while (running && !Thread.currentThread().isInterrupted()) {
                int got = 0;
                while (got < WINDOW && running) {
                    int n = ar.read(pcm, got, WINDOW - got);
                    if (n <= 0) {
                        fatal("انقطع التقاط الصوت (الرمز " + n + ")");
                        return;
                    }
                    got += n;
                }
                if (got < WINDOW) break;
                for (int i = 0; i < WINDOW; i++) window[i] = pcm[i] / 32768f;
                vad.acceptWaveform(window);
                while (!vad.empty()) {
                    SpeechSegment seg = vad.front();
                    float[] samples = seg.getSamples();
                    vad.pop();
                    if (samples != null && samples.length >= MIN_SEGMENT_SAMPLES) enqueue(samples);
                }
            }
        } catch (Throwable t) {
            Log.e(TAG, "capture loop failed", t);
            fatal("تعذر تشغيل التعرف المضمّن: " + t.getMessage());
        } finally {
            if (ar != null) {
                try { ar.stop(); } catch (Exception ignored) { }
                try { ar.release(); } catch (Exception ignored) { }
            }
            if (vad != null) {
                try { vad.release(); } catch (Throwable ignored) { }
            }
        }
    }

    private void enqueue(float[] samples) {
        if (!queue.offer(samples)) {
            queue.poll();            // الجهاز متأخر في المعالجة: نتخلص من الأقدم
            queue.offer(samples);
        }
    }

    // ---------------- فك الترميز ----------------

    private OfflineRecognizer createRecognizer() {
        OfflineMoonshineModelConfig moonshine = new OfflineMoonshineModelConfig();
        moonshine.setEncoder(ENCODER);
        moonshine.setMergedDecoder(DECODER);
        OfflineModelConfig model = new OfflineModelConfig();
        model.setMoonshine(moonshine);
        model.setTokens(TOKENS);
        model.setNumThreads(2);
        model.setDebug(false);
        model.setProvider("cpu");
        OfflineRecognizerConfig config = new OfflineRecognizerConfig();
        config.setModelConfig(model);
        config.setDecodingMethod("greedy_search");
        return new OfflineRecognizer(appContext.getAssets(), config);
    }

    private void decodeLoop() {
        OfflineRecognizer recognizer = null;
        try {
            callback.onStatus("جارٍ تحميل نموذج التعرف المضمّن...");
            long t0 = System.currentTimeMillis();
            recognizer = createRecognizer();
            Log.i(TAG, "model loaded in " + (System.currentTimeMillis() - t0) + " ms");
            while (running && !Thread.currentThread().isInterrupted()) {
                float[] samples = queue.poll(500, TimeUnit.MILLISECONDS);
                if (samples == null) continue;
                long t1 = System.currentTimeMillis();
                OfflineStream stream = recognizer.createStream();
                String text = "";
                try {
                    stream.acceptWaveform(samples, SAMPLE_RATE);
                    recognizer.decode(stream);
                    OfflineRecognizerResult result = recognizer.getResult(stream);
                    text = result == null ? "" : result.getText();
                } finally {
                    stream.release();
                }
                Log.i(TAG, "decoded " + samples.length / (float) SAMPLE_RATE + "s audio in "
                        + (System.currentTimeMillis() - t1) + " ms: " + text);
                if (text != null && !text.trim().isEmpty()) callback.onText(text.trim());
            }
        } catch (InterruptedException ignored) {
            // إيقاف طبيعي
        } catch (Throwable t) {
            Log.e(TAG, "decode loop failed", t);
            fatal("تعذر تحميل نموذج التعرف المضمّن: " + t.getMessage());
        } finally {
            if (recognizer != null) {
                try { recognizer.release(); } catch (Throwable ignored) { }
            }
        }
    }
}
