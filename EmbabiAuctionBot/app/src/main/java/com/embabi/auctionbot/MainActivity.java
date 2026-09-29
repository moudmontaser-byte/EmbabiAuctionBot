package com.embabi.auctionbot;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.Activity;
import android.content.*;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.accessibility.AccessibilityManager;
import android.widget.*;

import java.util.List;

public class MainActivity extends Activity {
    private final int bg = Color.rgb(5, 14, 27);
    private final int card = Color.rgb(11, 27, 46);
    private final int white = Color.rgb(244, 247, 255);
    private final int muted = Color.rgb(154, 174, 202);
    private final int purple = Color.rgb(117, 83, 255);
    private final int blue = Color.rgb(31, 149, 255);
    private final int green = Color.rgb(0, 220, 136);
    private final int red = Color.rgb(255, 65, 92);

    private final TextView[] values = new TextView[5];
    private TextView serviceState, calibrationState, runtimeState;
    private Button infiniteBtn, countBtn, timeBtn;
    private EditText countInput, minutesInput;
    private BroadcastReceiver receiver;

    @Override public void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().setStatusBarColor(bg);
        getWindow().setNavigationBarColor(bg);
        buildUi();

        receiver = new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) {
                String s = intent.getStringExtra(BotActions.EXTRA_STATUS);
                if (runtimeState != null && s != null) runtimeState.setText(s);
            }
        };
        registerReceiver(receiver, new IntentFilter(BotActions.STATUS), RECEIVER_NOT_EXPORTED);
    }

    @Override protected void onResume() {
        super.onResume();
        refresh();
    }

    @Override protected void onDestroy() {
        if (receiver != null) {
            try { unregisterReceiver(receiver); } catch (Exception ignored) {}
        }
        super.onDestroy();
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(bg);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(18), dp(18), dp(28));
        root.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        scroll.addView(root, new ScrollView.LayoutParams(-1, -2));

        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);

        TextView title = text("EMBABI GAMES\nAUCTION GENIUS BOT", 21, white, true);
        title.setGravity(Gravity.RIGHT);
        title.setPadding(dp(12), 0, 0, 0);
        TextView logo = text("◈", 34, purple, true);

        titleRow.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        titleRow.addView(logo, new LinearLayout.LayoutParams(dp(52), dp(52)));
        root.addView(titleRow);

        LinearLayout ready = cardBox(green, 18);
        ready.addView(text("جاهز للمزاد الذكي", 18, green, true));
        serviceState = text("", 13, muted, false);
        serviceState.setPadding(0, dp(4), 0, 0);
        ready.addView(serviceState);
        root.addView(ready, marginTop(16));

        LinearLayout service = cardBox(blue, 14);
        service.addView(text("صلاحية مراقبة الشاشة", 17, white, true));
        TextView desc = text(
                "نفس طريقة اللاعب الخفي: Accessibility + Screenshot + OCR داخل مربعات محددة. لا يعتمد على انتظار 5 أو 6 ثواني.",
                13, muted, false);
        desc.setPadding(0, dp(5), 0, dp(12));
        service.addView(desc);

        Button access = button("فتح إعدادات Accessibility", blue, Color.WHITE);
        access.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        service.addView(access);

        Button overlay = button("إظهار لوحة التحكم العائمة فوق اللعبة", Color.rgb(31,55,84), Color.WHITE);
        LinearLayout.LayoutParams op = new LinearLayout.LayoutParams(-1, dp(50));
        op.topMargin = dp(8);
        overlay.setLayoutParams(op);
        overlay.setOnClickListener(v -> {
            Prefs.setOverlayWanted(this, true);
            send(BotActions.SHOW_OVERLAY);
            Toast.makeText(this, "لوحة المزاد ستظهر فوق اللعبة بعد تفعيل Accessibility", Toast.LENGTH_SHORT).show();
        });
        service.addView(overlay);
        root.addView(service, marginTop(12));

        LinearLayout thresholds = cardBox(purple, 14);
        thresholds.addView(text("أقل تقييم مقبول لكل لاعب", 18, white, true));
        TextView thSub = text("لو اللاعب أقل من الحد، البوت يسيبه للخصم ويحافظ على الميزانية وينتظر الـFree Player.", 13, muted, false);
        thSub.setPadding(0, dp(3), 0, dp(8));
        thresholds.addView(thSub);

        String[] names = {"1. GK", "2. DEF", "3. CM1", "4. CM2", "5. ST"};
        for (int i = 0; i < 5; i++) {
            final int slot = i;
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);

            TextView label = text(names[i], 15, white, true);
            Button minus = smallButton("−");
            Button plus = smallButton("+");
            TextView value = text(String.valueOf(Prefs.getMinRating(this, slot)), 20, white, true);
            value.setGravity(Gravity.CENTER);
            values[slot] = value;

            row.addView(label, new LinearLayout.LayoutParams(0, dp(48), 1));
            row.addView(minus, new LinearLayout.LayoutParams(dp(48), dp(44)));
            row.addView(value, new LinearLayout.LayoutParams(dp(58), dp(44)));
            row.addView(plus, new LinearLayout.LayoutParams(dp(48), dp(44)));

            minus.setOnClickListener(v -> changeMin(slot, -1));
            plus.setOnClickListener(v -> changeMin(slot, 1));
            thresholds.addView(row);
        }
        root.addView(thresholds, marginTop(12));

        LinearLayout calibration = cardBox(blue, 14);
        calibration.addView(text("معايرة الـGUI الجديدة", 18, white, true));
        calibrationState = text("", 13, muted, false);
        calibrationState.setPadding(0, dp(4), 0, dp(10));
        calibration.addView(calibrationState);

        Button cal = button("▣ ابدأ المعايرة: 4 مربعات + زر + + Confirm", blue, Color.WHITE);
        cal.setOnClickListener(v -> send(BotActions.CALIBRATE));
        calibration.addView(cal);

        Button test = button("◎ TEST OCR — اختبر القراءة قبل اللعب", Color.rgb(31,55,84), Color.WHITE);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(-1, dp(50));
        tp.topMargin = dp(8);
        test.setLayoutParams(tp);
        test.setOnClickListener(v -> send(BotActions.TEST_OCR));
        calibration.addView(test);

        Button clear = button("مسح المعايرة القديمة", Color.rgb(72,31,48), Color.WHITE);
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(-1, dp(48));
        cp.topMargin = dp(8);
        clear.setLayoutParams(cp);
        clear.setOnClickListener(v -> {
            Prefs.clearCalibration(this);
            refresh();
            Toast.makeText(this, "تم مسح المعايرة", Toast.LENGTH_SHORT).show();
        });
        calibration.addView(clear);
        root.addView(calibration, marginTop(12));

        LinearLayout repeat = cardBox(purple, 14);
        repeat.addView(text("وضع التكرار", 18, white, true));
        TextView repSub = text("بعد كل جيم ينزل ويدور على كلمات النتيجة/التشكيلات/الرئيسية بنفس منطق اللاعب الخفي.", 13, muted, false);
        repSub.setPadding(0, dp(3), 0, dp(10));
        repeat.addView(repSub);

        LinearLayout tabs = new LinearLayout(this);
        tabs.setOrientation(LinearLayout.HORIZONTAL);
        infiniteBtn = segment("∞ لا نهائي");
        countBtn = segment("# عدد");
        timeBtn = segment("◷ وقت");
        tabs.addView(infiniteBtn, new LinearLayout.LayoutParams(0, dp(52), 1));
        tabs.addView(countBtn, new LinearLayout.LayoutParams(0, dp(52), 1));
        tabs.addView(timeBtn, new LinearLayout.LayoutParams(0, dp(52), 1));
        repeat.addView(tabs);

        LinearLayout inputs = new LinearLayout(this);
        inputs.setOrientation(LinearLayout.HORIZONTAL);
        inputs.setPadding(0, dp(8), 0, 0);
        countInput = numberInput(String.valueOf(Prefs.repeatCount(this)));
        minutesInput = numberInput(String.valueOf(Prefs.repeatMinutes(this)));
        inputs.addView(countInput, new LinearLayout.LayoutParams(0, dp(48), 1));
        Space gap = new Space(this);
        inputs.addView(gap, new LinearLayout.LayoutParams(dp(8), 1));
        inputs.addView(minutesInput, new LinearLayout.LayoutParams(0, dp(48), 1));
        repeat.addView(inputs);

        infiniteBtn.setOnClickListener(v -> selectRepeat("infinite"));
        countBtn.setOnClickListener(v -> selectRepeat("count"));
        timeBtn.setOnClickListener(v -> selectRepeat("time"));
        selectRepeat(Prefs.repeatMode(this));
        root.addView(repeat, marginTop(12));

        LinearLayout runtime = cardBox(green, 14);
        runtime.addView(text("الحالة الحالية", 17, white, true));
        runtimeState = text(Prefs.lastStatus(this), 13, muted, false);
        runtimeState.setPadding(0, dp(5), 0, dp(10));
        runtime.addView(runtimeState);

        Button start = button("▶ START — تشغيل البوت", Color.rgb(0,191,120), Color.WHITE);
        start.setOnClickListener(v -> {
            saveRepeatNumbers();
            Prefs.setOverlayWanted(this, true);
            send(BotActions.SHOW_OVERLAY);
            send(BotActions.START);
        });
        runtime.addView(start);

        Button stop = button("■ STOP", Color.rgb(105,24,42), Color.WHITE);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-1, dp(50));
        sp.topMargin = dp(8);
        stop.setLayoutParams(sp);
        stop.setOnClickListener(v -> send(BotActions.STOP));
        runtime.addView(stop);
        root.addView(runtime, marginTop(12));

        setContentView(scroll);
    }

    private void changeMin(int slot, int delta) {
        Prefs.setMinRating(this, slot, Prefs.getMinRating(this, slot) + delta);
        values[slot].setText(String.valueOf(Prefs.getMinRating(this, slot)));
    }

    private void selectRepeat(String mode) {
        saveRepeatNumbers();
        Prefs.setRepeatMode(this, mode);
        setSegment(infiniteBtn, "infinite".equals(mode));
        setSegment(countBtn, "count".equals(mode));
        setSegment(timeBtn, "time".equals(mode));
        countInput.setVisibility("count".equals(mode) ? View.VISIBLE : View.INVISIBLE);
        minutesInput.setVisibility("time".equals(mode) ? View.VISIBLE : View.INVISIBLE);
    }

    private void saveRepeatNumbers() {
        if (countInput != null) {
            try { Prefs.setRepeatCount(this, Integer.parseInt(countInput.getText().toString().trim())); } catch (Exception ignored) {}
        }
        if (minutesInput != null) {
            try { Prefs.setRepeatMinutes(this, Integer.parseInt(minutesInput.getText().toString().trim())); } catch (Exception ignored) {}
        }
    }

    private void refresh() {
        boolean enabled = isAccessibilityEnabled();
        long hb = Prefs.accessHeartbeat(this);
        boolean alive = enabled && hb > 0 && System.currentTimeMillis() - hb < 5000;
        if (serviceState != null) {
            serviceState.setText(alive
                    ? "Accessibility متصل ✓ — افتح Embabi Games ثم شغّل من الـOverlay."
                    : "فعّل Embabi Auction Genius من Accessibility أولاً.");
        }
        if (calibrationState != null) {
            calibrationState.setText(Prefs.isCalibrated(this)
                    ? "المعايرة READY ✓ — 4 مناطق OCR + زر + + Confirm محفوظين."
                    : "المعايرة غير مكتملة — اعملها مرة واحدة على شاشة مزاد حقيقية.");
        }
    }

    private boolean isAccessibilityEnabled() {
        AccessibilityManager am = (AccessibilityManager) getSystemService(ACCESSIBILITY_SERVICE);
        if (am == null) return false;
        List<AccessibilityServiceInfo> list = am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK);
        for (AccessibilityServiceInfo i : list) {
            if (i.getResolveInfo() != null && i.getResolveInfo().serviceInfo != null &&
                    getPackageName().equals(i.getResolveInfo().serviceInfo.packageName) &&
                    AuctionAccessibilityService.class.getName().equals(i.getResolveInfo().serviceInfo.name)) return true;
        }
        return false;
    }

    private void send(String action) {
        sendBroadcast(new Intent(action).setPackage(getPackageName()));
    }

    private LinearLayout cardBox(int stroke, int radius) {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setPadding(dp(14), dp(13), dp(14), dp(13));
        GradientDrawable g = new GradientDrawable();
        g.setColor(card);
        g.setCornerRadius(dp(radius));
        g.setStroke(dp(1), Color.argb(130, Color.red(stroke), Color.green(stroke), Color.blue(stroke)));
        l.setBackground(g);
        return l;
    }

    private Button button(String s, int fill, int tc) {
        Button b = new Button(this);
        b.setAllCaps(false);
        b.setText(s);
        b.setTextColor(tc);
        b.setTextSize(14);
        b.setBackground(round(fill, Color.rgb(66,92,120), 12));
        b.setLayoutParams(new LinearLayout.LayoutParams(-1, dp(52)));
        return b;
    }

    private Button smallButton(String s) {
        Button b = button(s, Color.rgb(24,45,68), white);
        b.setTextSize(18);
        return b;
    }

    private Button segment(String s) {
        Button b = button(s, Color.rgb(26,55,82), white);
        b.setTextSize(12);
        return b;
    }

    private void setSegment(Button b, boolean selected) {
        b.setBackground(round(selected ? purple : Color.rgb(26,55,82),
                selected ? Color.rgb(154,132,255) : Color.rgb(58,82,110), 11));
    }

    private EditText numberInput(String value) {
        EditText e = new EditText(this);
        e.setText(value);
        e.setTextColor(white);
        e.setTextSize(15);
        e.setGravity(Gravity.CENTER);
        e.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        e.setBackground(round(Color.rgb(16,38,62), Color.rgb(65,88,118), 10));
        return e;
    }

    private TextView text(String s, float size, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(size);
        t.setTextColor(color);
        if (bold) t.setTypeface(null, android.graphics.Typeface.BOLD);
        return t;
    }

    private GradientDrawable round(int fill, int stroke, int radius) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(dp(radius));
        g.setStroke(dp(1), stroke);
        return g;
    }

    private LinearLayout.LayoutParams marginTop(int top) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        p.topMargin = dp(top);
        return p;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}