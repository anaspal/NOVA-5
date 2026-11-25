package com.example.callhelper;

import android.app.Activity;
import android.os.Bundle;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.example.callhelper.nova.NovaCommandLog;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** دليل أوامر NOVA + سجل آخر الأوامر المنفّذة. */
public class NovaCommandLogActivity extends Activity {
    private static final String[] GUIDE = {
            "📞 نوفا اتصلي بأحمد",
            "🔦 نوفا شغلي الكشاف / اطفي الكشاف",
            "⏰ نوفا اضبطي المنبه الساعة خمسة واثنتي عشرة",
            "📱 نوفا افتحي واتساب",
            "🔔 نوفا اقرئي الإشعارات",
            "📸 نوفا خذي لقطة شاشة",
            "🔒 نوفا اقفلي الشاشة",
            "📶 نوفا افتحي الواي فاي / البلوتوث",
            "🔊 نوفا ارفعي الصوت / اخفضي الصوت",
            "🔁 نوفا كرري  •  🔋 نوفا كم البطارية  •  🕒 نوفا كم الساعة",
            "🧩 يمكن الجمع: نوفا افتحي واتساب ثم انزلي"
    };

    private LinearLayout logBox;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        Ui.init(this);
        Ui.systemBars(this);
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Ui.BG);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        int pad = Ui.dp(this, 18);
        root.setPadding(pad, pad, pad, pad);

        root.addView(Ui.text(this, "أوامر وسجل نوفا", 23, Ui.TEXT, true), Ui.lp(this, Ui.MATCH, Ui.WRAP, 0, 12));

        LinearLayout guide = Ui.card(this);
        guide.addView(Ui.text(this, "دليل الأوامر", 18, Ui.TEXT, true));
        for (String g : GUIDE) guide.addView(Ui.text(this, g, 14, Ui.MUTED, false), Ui.lp(this, Ui.MATCH, Ui.WRAP, 6, 0));
        root.addView(guide);

        LinearLayout logCard = Ui.card(this);
        logCard.addView(Ui.text(this, "آخر الأوامر", 18, Ui.TEXT, true));
        logBox = new LinearLayout(this);
        logBox.setOrientation(LinearLayout.VERTICAL);
        logCard.addView(logBox, Ui.lp(this, Ui.MATCH, Ui.WRAP, 8, 0));
        android.widget.Button clear = Ui.button(this, "مسح السجل", Ui.alpha(Ui.RED, 40), Ui.RED);
        clear.setOnClickListener(v -> { NovaCommandLog.clear(this); renderLog(); });
        logCard.addView(clear, Ui.lp(this, Ui.MATCH, Ui.WRAP, 12, 0));
        root.addView(logCard);

        android.widget.Button back = Ui.button(this, "رجوع", Ui.alpha(Ui.PRIMARY, 30), Ui.TEXT);
        back.setOnClickListener(v -> finish());
        root.addView(back, Ui.lp(this, Ui.MATCH, Ui.WRAP, 0, 12));
        scroll.addView(root);
        setContentView(scroll);
    }

    @Override protected void onResume() {
        super.onResume();
        renderLog();
    }

    private void renderLog() {
        logBox.removeAllViews();
        List<NovaCommandLog.Entry> entries = NovaCommandLog.read(this);
        if (entries.isEmpty()) {
            logBox.addView(Ui.text(this, "لا توجد أوامر بعد. قل: نوفا ... ثم الأمر.", 13, Ui.MUTED, false));
            return;
        }
        SimpleDateFormat fmt = new SimpleDateFormat("d/M  h:mm a", new Locale("ar"));
        for (NovaCommandLog.Entry e : entries) {
            LinearLayout item = new LinearLayout(this);
            item.setOrientation(LinearLayout.VERTICAL);
            item.setPadding(0, Ui.dp(this, 8), 0, Ui.dp(this, 8));
            item.addView(Ui.text(this, fmt.format(new Date(e.time)) + "  •  " + e.type, 11, Ui.MUTED, false));
            TextView heard = Ui.text(this, "سمعت: " + (e.heard.isEmpty() ? "—" : e.heard), 14, Ui.TEXT, true);
            item.addView(heard);
            item.addView(Ui.text(this, "الرد: " + e.response, 13, Ui.MUTED, false));
            logBox.addView(item);
            logBox.addView(Ui.divider(this));
        }
    }
}
