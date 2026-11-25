package com.example.callhelper;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.speech.tts.TextToSpeech;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.os.Build;
import android.speech.tts.Voice;
import android.view.Gravity;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** إعدادات إخراج صوت NOVA: Android TTS أو نموذج Dii المحلي. */
public class NovaVoiceSettingsActivity extends Activity {
    public static final String PREF_VOICE_MODE = "nova_voice_mode";
    public static final String PREF_SYSTEM_VOICE = "nova_system_voice";
    public static final String PREF_SPEECH_RATE = "nova_speech_rate";
    private static final String MODE_SYSTEM = "system";
    private static final String MODE_EMBEDDED = "embedded_dii";

    private TextToSpeech previewTts;
    private SpeechRecognizer offlineTestRecognizer;
    private boolean ttsReady;
    private boolean loadingVoices;
    private Spinner voiceSpinner;
    private SeekBar rateSeek;
    private TextView rateValue;
    private final List<Voice> arabicVoices = new ArrayList<>();
    private final List<String> voiceNames = new ArrayList<>();
    private String selectedVoiceName = "";

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        Ui.init(this);
        Ui.systemBars(this);
        setContentView(buildUi());
        loadSettings();
        initPreviewTts();
    }

    private View buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Ui.BG);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        int pad = Ui.dp(this, 18);
        root.setPadding(pad, pad, pad, pad);

        TextView title = Ui.text(this, "إعدادات صوت نوفا", 23, Ui.TEXT, true);
        root.addView(title, Ui.lp(this, Ui.MATCH, Ui.WRAP, 0, 6));
        TextView intro = Ui.text(this, "اختر طريقة النطق واضبط سرعة الصوت ثم جرّب النتيجة.", 14, Ui.MUTED, false);
        root.addView(intro, Ui.lp(this, Ui.MATCH, Ui.WRAP, 0, 16));

        LinearLayout modeCard = Ui.card(this);
        modeCard.addView(Ui.text(this, "طريقة إخراج الصوت", 18, Ui.TEXT, true));
        RadioGroup modes = new RadioGroup(this);
        modes.setOrientation(RadioGroup.VERTICAL);
        RadioButton system = new RadioButton(this);
        system.setId(View.generateViewId());
        system.setText("صوت النظام (Android TTS)");
        system.setTextColor(Ui.TEXT);
        modes.addView(system);
        RadioButton embedded = new RadioButton(this);
        embedded.setId(View.generateViewId());
        embedded.setText("الصوت المدمج Dii – نسائي");
        boolean diiPackaged = DiiTtsEngine.isModelPackaged(this);
        embedded.setTextColor(diiPackaged ? Ui.TEXT : Ui.MUTED);
        embedded.setEnabled(diiPackaged);
        modes.addView(embedded);
        String mode = getPrefs().getString(PREF_VOICE_MODE, MODE_SYSTEM);
        if (MODE_EMBEDDED.equals(mode) && !diiPackaged) mode = MODE_SYSTEM;
        modes.check(MODE_SYSTEM.equals(mode) ? system.getId() : embedded.getId());
        getPrefs().edit().putString(PREF_VOICE_MODE, mode).apply();
        modes.setOnCheckedChangeListener((group, checkedId) -> {
            String selectedMode = checkedId == embedded.getId() ? MODE_EMBEDDED : MODE_SYSTEM;
            getPrefs().edit().putString(PREF_VOICE_MODE, selectedMode).apply();
            applySettingsToService();
            Toast.makeText(this, MODE_EMBEDDED.equals(selectedMode)
                    ? "تم اختيار صوت Dii المدمج – يعمل دون إنترنت"
                    : "تم اختيار صوت نظام Android", Toast.LENGTH_SHORT).show();
        });
        modeCard.addView(modes, Ui.lp(this, Ui.MATCH, Ui.WRAP, 8, 4));
        TextView embeddedNote = Ui.text(this,
                diiPackaged
                        ? "Dii صوت عربي نسائي يعمل محلياً دون إنترنت. يحتاج أول تشغيل إلى تجهيز ملفاته داخل مساحة التطبيق."
                        : "ملفات Dii غير موجودة في حزمة البناء الحالية. أعد البناء عبر GitHub Actions لتنزيل النموذج وإدخاله في APK.",
                12, Ui.MUTED, false);
        modeCard.addView(embeddedNote);
        root.addView(modeCard);

        LinearLayout systemCard = Ui.card(this);
        systemCard.addView(Ui.text(this, "صوت النظام", 18, Ui.TEXT, true));
        systemCard.addView(Ui.text(this, "الأصوات العربية المثبتة على هاتفك", 13, Ui.MUTED, false));
        voiceSpinner = new Spinner(this);
        systemCard.addView(voiceSpinner, Ui.lp(this, Ui.MATCH, Ui.WRAP, 8, 8));
        root.addView(systemCard);

        LinearLayout rateCard = Ui.card(this);
        rateCard.addView(Ui.text(this, "سرعة النطق", 18, Ui.TEXT, true));
        rateValue = Ui.text(this, "1.00×", 15, Ui.PRIMARY, true);
        rateCard.addView(rateValue, Ui.lp(this, Ui.MATCH, Ui.WRAP, 8, 0));
        rateSeek = new SeekBar(this);
        rateSeek.setMax(80); // 0.60x .. 1.40x
        rateCard.addView(rateSeek, Ui.lp(this, Ui.MATCH, Ui.WRAP, 4, 0));
        rateSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                float rate = 0.60f + (progress / 100f);
                rateValue.setText(String.format(Locale.US, "%.2f×", rate));
                if (fromUser) {
                    getPrefs().edit().putFloat(PREF_SPEECH_RATE, rate).apply();
                    if (ttsReady && previewTts != null) previewTts.setSpeechRate(rate);
                    applySettingsToService();
                }
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) { }
            @Override public void onStopTrackingTouch(SeekBar seekBar) { }
        });
        root.addView(rateCard);

        LinearLayout testCard = Ui.card(this);
        testCard.addView(Ui.text(this, "اختبار الصوت", 18, Ui.TEXT, true));
        testCard.addView(Ui.text(this, "ستسمع جملة قصيرة للتأكد من وضوح النطق والسرعة.", 13, Ui.MUTED, false));
        android.widget.Button test = Ui.button(this, "استمع إلى نوفا", Ui.PRIMARY, android.graphics.Color.WHITE);
        test.setOnClickListener(v -> speakPreview());
        testCard.addView(test, Ui.lp(this, Ui.MATCH, Ui.WRAP, 12, 0));
        root.addView(testCard);

        LinearLayout offlineCard = Ui.card(this);
        offlineCard.addView(Ui.text(this, "فحص التعرف دون إنترنت", 18, Ui.TEXT, true));
        String offlineStatus;
        if (android.os.Build.VERSION.SDK_INT >= 31
                && android.speech.SpeechRecognizer.isOnDeviceRecognitionAvailable(this)) {
            offlineStatus = "الجهاز يعلن توفر محرك التعرف المحلي. هذا لا يضمن توفر حزمة اللغة العربية أو نجاحها؛ يجب اختبارها عملياً.";
        } else {
            offlineStatus = "لم يعلن Android توفر محرك تعرف محلي على هذا الجهاز. سيبقى سلوك Stage 5 الحالي دون تغيير.";
        }
        offlineCard.addView(Ui.text(this, offlineStatus, 13, Ui.MUTED, false));
        android.widget.Button offlineTest = Ui.button(this, "اختبار التعرف المحلي الآن", Ui.PRIMARY, android.graphics.Color.WHITE);
        offlineTest.setOnClickListener(v -> runOfflineRecognitionTest());
        offlineCard.addView(offlineTest, Ui.lp(this, Ui.MATCH, Ui.WRAP, 12, 0));
        root.addView(offlineCard);

        android.widget.Button back = Ui.button(this, "رجوع", Ui.alpha(Ui.PRIMARY, 30), Ui.TEXT);
        back.setOnClickListener(v -> finish());
        root.addView(back, Ui.lp(this, Ui.MATCH, Ui.WRAP, 0, 12));
        scroll.addView(root);
        return scroll;
    }

    private void loadSettings() {
        float rate = getPrefs().getFloat(PREF_SPEECH_RATE, 1.0f);
        int progress = Math.max(0, Math.min(80, Math.round((rate - 0.60f) * 100f)));
        rateSeek.setProgress(progress);
        rateValue.setText(String.format(Locale.US, "%.2f×", 0.60f + progress / 100f));
        selectedVoiceName = getPrefs().getString(PREF_SYSTEM_VOICE, "");
    }

    private void initPreviewTts() {
        previewTts = new TextToSpeech(this, status -> {
            if (status != TextToSpeech.SUCCESS || previewTts == null) {
                Toast.makeText(this, "تعذر تشغيل محرك تحويل النص إلى كلام", Toast.LENGTH_LONG).show();
                return;
            }
            ttsReady = true;
            previewTts.setLanguage(new Locale("ar"));
            previewTts.setSpeechRate(getPrefs().getFloat(PREF_SPEECH_RATE, 1.0f));
            populateArabicVoices();
        });
    }

    private void populateArabicVoices() {
        if (previewTts == null || !ttsReady || loadingVoices) return;
        loadingVoices = true;
        arabicVoices.clear();
        try {
            for (Voice voice : previewTts.getVoices()) {
                if (voice != null && voice.getLocale() != null
                        && "ar".equalsIgnoreCase(voice.getLocale().getLanguage())) {
                    arabicVoices.add(voice);
                }
            }
        } catch (Exception ignored) { }
        Collections.sort(arabicVoices, Comparator.comparing(v -> v.getName().toLowerCase(Locale.ROOT)));
        voiceNames.clear();
        for (Voice voice : arabicVoices) voiceNames.add(voice.getName() + " • " + voice.getLocale().toLanguageTag());
        if (voiceNames.isEmpty()) voiceNames.add("لا توجد أصوات عربية مثبتة في محرك النظام");
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, voiceNames);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        voiceSpinner.setAdapter(adapter);
        int selected = -1;
        for (int i = 0; i < arabicVoices.size(); i++) {
            if (arabicVoices.get(i).getName().equals(selectedVoiceName)) selected = i;
        }
        if (selected >= 0) voiceSpinner.setSelection(selected);
        voiceSpinner.setEnabled(!arabicVoices.isEmpty());
        voiceSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (position < 0 || position >= arabicVoices.size()) return;
                Voice voice = arabicVoices.get(position);
                selectedVoiceName = voice.getName();
                getPrefs().edit().putString(PREF_SYSTEM_VOICE, selectedVoiceName).apply();
                if (ttsReady && previewTts != null) previewTts.setVoice(voice);
                applySettingsToService();
            }
            @Override public void onNothingSelected(AdapterView<?> parent) { }
        });
        if (selected >= 0 && previewTts != null) previewTts.setVoice(arabicVoices.get(selected));
        loadingVoices = false;
    }

    private void speakPreview() {
        String mode = getPrefs().getString(PREF_VOICE_MODE, MODE_SYSTEM);
        float speed = getPrefs().getFloat(PREF_SPEECH_RATE, 1.0f);
        String sample = "مرحباً، أنا نوفا. تم ضبط إعدادات الصوت بنجاح.";
        if (MODE_EMBEDDED.equals(mode)) {
            DiiTtsEngine.speak(this, sample, speed, new DiiTtsEngine.Callback() {
                @Override public void onComplete() { }
                @Override public void onError(String message) {
                    runOnUiThread(() -> Toast.makeText(NovaVoiceSettingsActivity.this,
                            "تعذر تشغيل Dii: " + message, Toast.LENGTH_LONG).show());
                }
            });
            return;
        }
        if (!ttsReady || previewTts == null) {
            Toast.makeText(this, "محرك الصوت لم يجهز بعد", Toast.LENGTH_SHORT).show();
            return;
        }
        previewTts.setSpeechRate(speed);
        previewTts.speak(sample, TextToSpeech.QUEUE_FLUSH, null, "nova-preview");
    }

    private void runOfflineRecognitionTest() {
        if (Build.VERSION.SDK_INT < 31 || !SpeechRecognizer.isOnDeviceRecognitionAvailable(this)) {
            Toast.makeText(this, "محرك التعرف المحلي غير متاح على هذا الجهاز", Toast.LENGTH_LONG).show();
            return;
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "امنح نوفا إذن الميكروفون أولاً", Toast.LENGTH_LONG).show();
            return;
        }
        releaseOfflineTestRecognizer();
        try {
            offlineTestRecognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(this);
            offlineTestRecognizer.setRecognitionListener(new RecognitionListener() {
                @Override public void onReadyForSpeech(Bundle params) {
                    Toast.makeText(NovaVoiceSettingsActivity.this, "تحدث الآن بالعربية لمدة قصيرة", Toast.LENGTH_SHORT).show();
                }
                @Override public void onBeginningOfSpeech() { }
                @Override public void onRmsChanged(float rmsdB) { }
                @Override public void onBufferReceived(byte[] buffer) { }
                @Override public void onEndOfSpeech() { }
                @Override public void onError(int error) {
                    releaseOfflineTestRecognizer();
                    new android.app.AlertDialog.Builder(NovaVoiceSettingsActivity.this)
                            .setTitle("نتيجة اختبار التعرف المحلي")
                            .setMessage("لم ينجح التعرف. رمز الخطأ: " + error
                                    + "\nقد تكون حزمة العربية غير مثبتة أو غير مدعومة في محرك الجهاز.")
                            .setPositiveButton("حسناً", null).show();
                }
                @Override public void onResults(Bundle results) {
                    ArrayList<String> matches = results == null ? null
                            : results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                    String text = matches == null || matches.isEmpty() ? "لم يتم التعرف على كلام واضح" : matches.get(0);
                    releaseOfflineTestRecognizer();
                    new android.app.AlertDialog.Builder(NovaVoiceSettingsActivity.this)
                            .setTitle("نتيجة اختبار التعرف المحلي")
                            .setMessage("النص المتعرف عليه:\n" + text
                                    + "\n\nهذا الاختبار استخدم On-device SpeechRecognizer مباشرة.")
                            .setPositiveButton("حسناً", null).show();
                }
                @Override public void onPartialResults(Bundle partialResults) { }
                @Override public void onEvent(int eventType, Bundle params) { }
            });
            Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ar");
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "ar");
            intent.putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true);
            intent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false);
            offlineTestRecognizer.startListening(intent);
        } catch (Exception e) {
            releaseOfflineTestRecognizer();
            Toast.makeText(this, "تعذر بدء الاختبار: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void releaseOfflineTestRecognizer() {
        if (offlineTestRecognizer != null) {
            try { offlineTestRecognizer.cancel(); } catch (Exception ignored) { }
            try { offlineTestRecognizer.destroy(); } catch (Exception ignored) { }
            offlineTestRecognizer = null;
        }
    }

    private android.content.SharedPreferences getPrefs() {
        return getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE);
    }

    private void applySettingsToService() {
        if (VoiceCommandService.isRunning()) {
            startService(new Intent(this, VoiceCommandService.class).setAction(VoiceCommandService.ACTION_NOVA_CONFIG_CHANGED));
        }
    }

    @Override protected void onDestroy() {
        releaseOfflineTestRecognizer();
        if (previewTts != null) {
            try { previewTts.stop(); previewTts.shutdown(); } catch (Exception ignored) { }
            previewTts = null;
        }
        super.onDestroy();
    }
}
