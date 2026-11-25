package com.example.callhelper;

import android.app.Activity;
import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

public final class Ui {
    public static int BG = Color.parseColor("#F3F5F9");
    public static int CARD = Color.WHITE;
    public static int TEXT = Color.parseColor("#111827");
    public static int MUTED = Color.parseColor("#6B7280");
    public static int GRAY = Color.parseColor("#D1D5DB");
    public static int LINE = Color.parseColor("#E5E7EB");
    public static boolean night = false;

    public static final int PRIMARY = Color.parseColor("#2563EB");
    public static final int GREEN = Color.parseColor("#16A34A");
    public static final int RED = Color.parseColor("#DC2626");
    public static final int MATCH = LinearLayout.LayoutParams.MATCH_PARENT;
    public static final int WRAP = LinearLayout.LayoutParams.WRAP_CONTENT;

    private Ui() {}

    /** يحدد الألوان حسب الوضع الليلي/النهاري للنظام. استدعِها في بداية onCreate. */
    public static void init(Context c) {
        int mode = c.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        night = (mode == Configuration.UI_MODE_NIGHT_YES);
        if (night) {
            BG = Color.parseColor("#0F172A");
            CARD = Color.parseColor("#1E293B");
            TEXT = Color.parseColor("#F1F5F9");
            MUTED = Color.parseColor("#94A3B8");
            GRAY = Color.parseColor("#475569");
            LINE = Color.parseColor("#334155");
        } else {
            BG = Color.parseColor("#F3F5F9");
            CARD = Color.WHITE;
            TEXT = Color.parseColor("#111827");
            MUTED = Color.parseColor("#6B7280");
            GRAY = Color.parseColor("#D1D5DB");
            LINE = Color.parseColor("#E5E7EB");
        }
    }

    public static void systemBars(Activity a) {
        a.getWindow().setStatusBarColor(BG);
        a.getWindow().setNavigationBarColor(BG);
        int f = night ? 0 : (View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        a.getWindow().getDecorView().setSystemUiVisibility(f);
    }

    public static int dp(Context c, float v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }

    public static int alpha(int color, int a) {
        return Color.argb(a, Color.red(color), Color.green(color), Color.blue(color));
    }

    public static GradientDrawable rounded(Context c, int color, float radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(c, radiusDp));
        return d;
    }

    public static GradientDrawable outline(Context c, int fill, int stroke, float radiusDp) {
        GradientDrawable d = rounded(c, fill, radiusDp);
        d.setStroke(dp(c, 1.5f), stroke);
        return d;
    }

    public static GradientDrawable circle(Context c, int fill, int stroke, float strokeDp) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setColor(fill);
        d.setStroke(dp(c, strokeDp), stroke);
        return d;
    }

    public static LinearLayout.LayoutParams lp(Context c, int w, int h, float topDp, float bottomDp) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, h);
        p.topMargin = dp(c, topDp);
        p.bottomMargin = dp(c, bottomDp);
        return p;
    }

    public static LinearLayout card(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = rounded(c, CARD, 22);
        if (night) bg.setStroke(dp(c, 1), LINE); // بالوضع الليلي لا يوجد ظل، فنفصل البطاقة بحدّ رفيع
        l.setBackground(bg);
        int p = dp(c, 16);
        l.setPadding(p, p, p, p);
        l.setElevation(dp(c, night ? 0 : 2));
        l.setLayoutParams(lp(c, MATCH, WRAP, 0, 14));
        return l;
    }

    public static TextView text(Context c, String s, float sp, int color, boolean bold) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    public static TextView chip(Context c, String emoji, int color) {
        TextView t = new TextView(c);
        t.setText(emoji);
        t.setTextSize(20);
        t.setGravity(Gravity.CENTER);
        t.setBackground(rounded(c, alpha(color, 40), 12));
        t.setLayoutParams(new LinearLayout.LayoutParams(dp(c, 44), dp(c, 44)));
        return t;
    }

    private static boolean isLight(int color) {
        return (0.299 * Color.red(color) + 0.587 * Color.green(color) + 0.114 * Color.blue(color)) / 255.0 > 0.7;
    }

    /**
     * زر بتصميم حديث: زوايا 16dp، ارتفاع 52dp، تأثير ضغط (ripple)، وظل ملوّن خفيف للأزرار الممتلئة.
     * الأزرار الشفافة (ألوان بنسبة alpha) تظهر كأزرار «tonal» بحدّ رفيع بدل أن تبدو باهتة.
     */
    public static Button button(Context c, String s, int fill, int textColor) {
        Button b = new Button(c);
        b.setText(s);
        b.setAllCaps(false);
        b.setTextColor(textColor);
        b.setTextSize(15.5f);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setLetterSpacing(0.01f);
        b.setGravity(Gravity.CENTER);

        final float radius = 16;
        boolean solid = Color.alpha(fill) == 255;
        GradientDrawable content = rounded(c, fill, radius);
        if (!solid) content.setStroke(dp(c, 1), alpha(textColor, 70));
        GradientDrawable mask = rounded(c, Color.WHITE, radius);
        // لون الموجة: أبيض على الخلفيات الغامقة، ولون النص على الفاتحة/الشفافة
        int ripple = (solid && !isLight(fill)) ? alpha(Color.WHITE, 80) : alpha(textColor, 55);
        b.setBackground(new RippleDrawable(ColorStateList.valueOf(ripple), content, mask));

        b.setMinHeight(dp(c, 52));
        b.setMinimumHeight(dp(c, 52));
        int ph = dp(c, 20);
        b.setPadding(ph, 0, ph, 0);

        b.setStateListAnimator(null);
        if (solid && !night) {
            b.setElevation(dp(c, 3));
            if (Build.VERSION.SDK_INT >= 28) {
                b.setOutlineSpotShadowColor(alpha(fill, 200));
                b.setOutlineAmbientShadowColor(alpha(fill, 90));
            }
        }
        return b;
    }

    /** زر بحدّ رفيع وخلفية شفافة (للإجراءات الثانوية أو الخطرة مثل الحذف). */
    public static Button outlineButton(Context c, String s, int color) {
        return button(c, s, alpha(color, 0), color);
    }

    public static View divider(Context c) {
        View v = new View(c);
        v.setBackgroundColor(LINE);
        v.setLayoutParams(new LinearLayout.LayoutParams(MATCH, dp(c, 1)));
        return v;
    }
}
