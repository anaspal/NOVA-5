package com.example.callhelper;

import android.content.Context;
import android.content.res.AssetManager;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.os.SystemClock;
import android.util.Log;

import com.k2fsa.sherpa.onnx.GeneratedAudio;
import com.k2fsa.sherpa.onnx.OfflineTts;
import com.k2fsa.sherpa.onnx.OfflineTtsConfig;
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig;
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Local Arabic female Dii voice powered by sherpa-onnx/Piper VITS. */
public final class DiiTtsEngine {
    private static final String TAG = "DiiTtsEngine";
    private static final String ASSET_ROOT = "dii";
    private static final String MODEL_FILE = "ar_JO-SA_dii-high.onnx";
    private static final String TOKENS_FILE = "tokens.txt";
    private static final String ESPEAK_DIR = "espeak-ng-data";
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "nova-dii-tts");
        t.setPriority(Thread.NORM_PRIORITY - 1);
        return t;
    });
    private static final Object LOCK = new Object();
    private static final Object INIT_LOCK = new Object();
    private static volatile OfflineTts engine;
    private static volatile AudioTrack activeTrack;
    private static volatile long requestId;

    public interface Callback {
        void onComplete();
        void onError(String message);
    }

    private DiiTtsEngine() { }

    public static boolean isModelPackaged(Context context) {
        try (InputStream in = context.getAssets().open(ASSET_ROOT + "/" + MODEL_FILE)) {
            return in != null;
        } catch (Exception e) {
            return false;
        }
    }

    public static void speak(Context context, String text, float speed, Callback callback) {
        if (text == null || text.trim().isEmpty()) {
            if (callback != null) callback.onComplete();
            return;
        }
        final Context app = context.getApplicationContext();
        final long id;
        synchronized (LOCK) {
            id = ++requestId;
            stopTrackLocked();
        }
        EXECUTOR.execute(() -> {
            try {
                OfflineTts tts = getEngine(app);
                GeneratedAudio generated = tts.generate(text, 0, clampSpeed(speed));
                float[] samples = generated.getSamples();
                if (samples == null || samples.length == 0) throw new IllegalStateException("لم ينتج النموذج صوتاً");
                synchronized (LOCK) {
                    if (id != requestId) return;
                }
                play(samples, generated.getSampleRate(), id);
                if (callback != null && id == requestId) callback.onComplete();
            } catch (Throwable e) {
                Log.e(TAG, "Dii synthesis failed", e);
                if (callback != null && id == requestId) callback.onError(
                        e.getMessage() == null ? "تعذر تشغيل صوت Dii" : e.getMessage());
            }
        });
    }

    public static void stop() {
        synchronized (LOCK) {
            requestId++;
            stopTrackLocked();
        }
    }

    private static float clampSpeed(float speed) {
        return Math.max(0.60f, Math.min(1.40f, speed));
    }

    private static OfflineTts getEngine(Context context) throws Exception {
        if (engine != null) return engine;
        synchronized (INIT_LOCK) {
            if (engine != null) return engine;
            File root = new File(context.getFilesDir(), "nova_dii_voice");
            File model = new File(root, MODEL_FILE);
            File tokens = new File(root, TOKENS_FILE);
            File espeak = new File(root, ESPEAK_DIR);
            if (!model.exists() || !tokens.exists() || !new File(espeak, "voices").exists()) {
                copyAssetTree(context.getAssets(), ASSET_ROOT, root);
            }
            if (!model.isFile() || !tokens.isFile() || !new File(espeak, "voices").exists()) {
                throw new IllegalStateException("ملفات صوت Dii غير موجودة داخل هذا الإصدار. أعد بناء APK بعد تنزيل ملفات النموذج.");
            }
            // واجهة sherpa-onnx الرسمية لأندرويد مكتوبة بـ Kotlin: لا يوجد builder()، نستخدم المُنشئ الافتراضي + setters.
            OfflineTtsVitsModelConfig vits = new OfflineTtsVitsModelConfig();
            vits.setModel(model.getAbsolutePath());
            vits.setTokens(tokens.getAbsolutePath());
            vits.setDataDir(espeak.getAbsolutePath());

            OfflineTtsModelConfig modelConfig = new OfflineTtsModelConfig();
            modelConfig.setVits(vits);
            modelConfig.setNumThreads(Math.max(1, Math.min(3, Runtime.getRuntime().availableProcessors() - 1)));
            modelConfig.setDebug(false);

            OfflineTtsConfig config = new OfflineTtsConfig();
            config.setModel(modelConfig);
            config.setMaxNumSentences(1);

            // AssetManager = null: الملفات منسوخة أصلاً إلى filesDir ونمرّر مساراتها الكاملة.
            OfflineTts created = new OfflineTts(null, config);
            engine = created;
            Log.i(TAG, "Dii Arabic offline voice initialized; sampleRate=" + created.sampleRate());
            return engine;
        }
    }

    private static void copyAssetTree(AssetManager assets, String assetPath, File destination) throws Exception {
        String[] children = assets.list(assetPath);
        if (children != null && children.length > 0) {
            if (!destination.exists() && !destination.mkdirs()) throw new IllegalStateException("تعذر إنشاء مجلد ملفات الصوت");
            for (String child : children) copyAssetTree(assets, assetPath + "/" + child, new File(destination, child));
            return;
        }
        File parent = destination.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) throw new IllegalStateException("تعذر تجهيز ملفات الصوت");
        try (InputStream in = assets.open(assetPath); FileOutputStream out = new FileOutputStream(destination)) {
            byte[] buffer = new byte[32 * 1024];
            int read;
            while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
            out.getFD().sync();
        }
    }

    private static void play(float[] samples, int sampleRate, long id) throws Exception {
        int min = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_FLOAT);
        int bufferBytes = Math.max(min > 0 ? min : 4096, 4096);
        AudioTrack track = new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build())
                .setAudioFormat(new AudioFormat.Builder()
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                        .build())
                .setBufferSizeInBytes(bufferBytes)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build();
        synchronized (LOCK) {
            if (id != requestId) { track.release(); return; }
            activeTrack = track;
        }
        try {
            if (track.getState() != AudioTrack.STATE_INITIALIZED) throw new IllegalStateException("تعذر تهيئة مخرج الصوت");
            track.play();
            int offset = 0;
            while (offset < samples.length) {
                synchronized (LOCK) { if (id != requestId) return; }
                int written = track.write(samples, offset, samples.length - offset, AudioTrack.WRITE_BLOCKING);
                if (written < 0) throw new IllegalStateException("فشل إرسال الصوت إلى مكبر الهاتف: " + written);
                if (written == 0) break;
                offset += written;
            }
            long deadline = SystemClock.uptimeMillis() + Math.max(5000L, samples.length * 1000L / sampleRate + 3000L);
            while (track.getPlaybackHeadPosition() < samples.length && SystemClock.uptimeMillis() < deadline) {
                synchronized (LOCK) { if (id != requestId) return; }
                Thread.sleep(20L);
            }
        } finally {
            synchronized (LOCK) { if (activeTrack == track) activeTrack = null; }
            try { track.pause(); } catch (Exception ignored) { }
            try { track.flush(); } catch (Exception ignored) { }
            try { track.stop(); } catch (Exception ignored) { }
            track.release();
        }
    }

    private static void stopTrackLocked() {
        AudioTrack track = activeTrack;
        activeTrack = null;
        if (track != null) {
            try { track.pause(); } catch (Exception ignored) { }
            try { track.flush(); } catch (Exception ignored) { }
            try { track.stop(); } catch (Exception ignored) { }
        }
    }
}
