package com.embabi.auctionflex;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Space;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity {
    private final EditText[][][] rangeInputs = new EditText[5][Prefs.RANGE_COUNT][3];
    private EditText countInput, timeInput;
    private TextView statusText, calText;
    private Button infiniteBtn, countBtn, timeBtn;

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (BotActions.STATUS.equals(intent.getAction())) {
                String s=intent.getStringExtra(BotActions.EXTRA_STATUS);
                if(s!=null && statusText!=null) statusText.setText(s);
            }
        }
    };

    @Override public void onCreate(Bundle b) {
        super.onCreate(b);
        buildUi();
        IntentFilter f=new IntentFilter(BotActions.STATUS);
        if(Build.VERSION.SDK_INT>=33) registerReceiver(receiver,f,Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(receiver,f);
    }

    @Override protected void onDestroy() {
        try { unregisterReceiver(receiver); } catch(Exception ignored) {}
        super.onDestroy();
    }

    @Override protected void onResume() {
        super.onResume();
        if(statusText!=null) statusText.setText(Prefs.status(this));
        if(calText!=null) calText.setText(Prefs.isCalibrated(this) ? "Calibration: READY ✓" : "Calibration: مطلوب قبل START");
    }

    private void buildUi() {
        ScrollView sv=new ScrollView(this);
        sv.setFillViewport(true);
        sv.setBackgroundColor(Color.rgb(5,14,27));

        LinearLayout root=new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(14),dp(18),dp(14),dp(28));
        root.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        sv.addView(root,new ScrollView.LayoutParams(-1,-2));

        TextView title=text("mahmoud montaser",25,Color.WHITE,true);
        title.setGravity(Gravity.CENTER);
        root.addView(title);
        TextView sub=text("5 لاعبين • 6 Rating Ranges لكل لاعب • Max Bid مستقل",13,Color.rgb(155,178,205),false);
        sub.setGravity(Gravity.CENTER);
        sub.setPadding(0,dp(4),0,dp(14));
        root.addView(sub);

        LinearLayout service=card();
        statusText=text(Prefs.status(this),13,Color.rgb(218,229,244),false);
        calText=text(Prefs.isCalibrated(this)?"Calibration: READY ✓":"Calibration: مطلوب قبل START",13,Color.rgb(0,220,145),true);
        service.addView(statusText);
        service.addView(calText);

        Button access=button("فتح Accessibility",Color.rgb(32,128,230));
        access.setOnClickListener(v->startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        service.addView(access,marginTop(8));

        Button cal=button("CAL — (Rating + Position) / Price / + / Confirm / Skip",Color.rgb(65,82,180));
        cal.setOnClickListener(v->send(BotActions.CALIBRATE));
        service.addView(cal,marginTop(7));

        Button overlay=button("إظهار الـ Overlay فوق اللعبة",Color.rgb(28,61,91));
        overlay.setOnClickListener(v->send(BotActions.SHOW_OVERLAY));
        service.addView(overlay,marginTop(7));
        root.addView(service,marginTop(4));

        TextView h=text("إعدادات المزاد",20,Color.WHITE,true);
        h.setPadding(0,dp(18),0,dp(7));
        root.addView(h);

        String[] ar={"حارس مرمى","مدافع","وسط 1","وسط 2","مهاجم"};
        for(int s=0;s<5;s++) {
            LinearLayout box=card();
            TextView sh=text((s+1)+"/5  "+Prefs.SLOT_NAMES[s]+" — "+ar[s],17,Color.rgb(255,214,50),true);
            box.addView(sh);

            LinearLayout headers=new LinearLayout(this);
            headers.setOrientation(LinearLayout.HORIZONTAL);
            headers.addView(cellLabel("من"),new LinearLayout.LayoutParams(0,dp(30),1));
            headers.addView(cellLabel("إلى"),new LinearLayout.LayoutParams(0,dp(30),1));
            headers.addView(cellLabel("Max M"),new LinearLayout.LayoutParams(0,dp(30),1.2f));
            box.addView(headers);

            for(int r=0;r<Prefs.RANGE_COUNT;r++) {
                LinearLayout row=new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                EditText min=num(String.valueOf(Prefs.getRangeMin(this,s,r)));
                EditText max=num(String.valueOf(Prefs.getRangeMax(this,s,r)));
                EditText bid=num(String.valueOf(Prefs.getMaxBid(this,s,r)));
                rangeInputs[s][r][0]=min; rangeInputs[s][r][1]=max; rangeInputs[s][r][2]=bid;
                row.addView(min,new LinearLayout.LayoutParams(0,dp(50),1));
                addGap(row,5);
                row.addView(max,new LinearLayout.LayoutParams(0,dp(50),1));
                addGap(row,5);
                row.addView(bid,new LinearLayout.LayoutParams(0,dp(50),1.2f));
                box.addView(row,marginTop(5));
            }

            TextView note=text("خارج الـ6 Ranges = SKIP • لو أنت تبدأ اللاعب: 1M فقط أولاً ثم Skip",11.5f,Color.rgb(140,163,190),false);
            note.setPadding(0,dp(6),0,0);
            box.addView(note);
            root.addView(box,marginTop(8));
        }

        Button save=button("حفظ إعدادات اللاعبين",Color.rgb(0,181,116));
        save.setOnClickListener(v->saveRanges());
        root.addView(save,marginTop(12));

        LinearLayout repeat=card();
        repeat.addView(text("التكرار",18,Color.WHITE,true));
        LinearLayout seg=new LinearLayout(this);
        seg.setOrientation(LinearLayout.HORIZONTAL);
        infiniteBtn=segment("∞ بدون نهاية");
        countBtn=segment("عدد Games");
        timeBtn=segment("وقت");
        seg.addView(infiniteBtn,new LinearLayout.LayoutParams(0,dp(48),1.25f));
        addGap(seg,5);
        seg.addView(countBtn,new LinearLayout.LayoutParams(0,dp(48),1));
        addGap(seg,5);
        seg.addView(timeBtn,new LinearLayout.LayoutParams(0,dp(48),1));
        repeat.addView(seg,marginTop(7));

        countInput=num(String.valueOf(Prefs.repeatCount(this)));
        countInput.setHint("عدد Games");
        timeInput=num(String.valueOf(Prefs.repeatMinutes(this)));
        timeInput.setHint("المدة بالدقائق");
        repeat.addView(countInput,marginTop(7));
        repeat.addView(timeInput,marginTop(7));

        infiniteBtn.setOnClickListener(v->selectMode("infinite"));
        countBtn.setOnClickListener(v->selectMode("count"));
        timeBtn.setOnClickListener(v->selectMode("time"));
        selectMode(Prefs.repeatMode(this));
        root.addView(repeat,marginTop(12));

        Button show=button("افتح الـ Overlay وابدأ من اللعبة",Color.rgb(0,205,130));
        show.setOnClickListener(v->{ saveAll(); send(BotActions.SHOW_OVERLAY); Toast.makeText(this,"افتح Embabi Games واضغط START من الـOverlay",Toast.LENGTH_LONG).show(); });
        root.addView(show,marginTop(12));

        setContentView(sv);
    }

    private void saveRanges() {
        for(int s=0;s<5;s++) for(int r=0;r<Prefs.RANGE_COUNT;r++) {
            int mn=parse(rangeInputs[s][r][0],Prefs.getRangeMin(this,s,r));
            int mx=parse(rangeInputs[s][r][1],Prefs.getRangeMax(this,s,r));
            int bid=parse(rangeInputs[s][r][2],Prefs.getMaxBid(this,s,r));
            Prefs.setRange(this,s,r,mn,mx,bid);
            rangeInputs[s][r][0].setText(String.valueOf(Prefs.getRangeMin(this,s,r)));
            rangeInputs[s][r][1].setText(String.valueOf(Prefs.getRangeMax(this,s,r)));
            rangeInputs[s][r][2].setText(String.valueOf(Prefs.getMaxBid(this,s,r)));
        }
        Toast.makeText(this,"تم حفظ 30 Range ✓",Toast.LENGTH_SHORT).show();
    }

    private void saveAll() {
        saveRanges();
        Prefs.setRepeatCount(this,parse(countInput,Prefs.repeatCount(this)));
        Prefs.setRepeatMinutes(this,parse(timeInput,Prefs.repeatMinutes(this)));
    }

    private void selectMode(String mode) {
        Prefs.setRepeatMode(this,mode);
        setSelected(infiniteBtn,"infinite".equals(mode));
        setSelected(countBtn,"count".equals(mode));
        setSelected(timeBtn,"time".equals(mode));
        countInput.setVisibility("count".equals(mode)?View.VISIBLE:View.GONE);
        timeInput.setVisibility("time".equals(mode)?View.VISIBLE:View.GONE);
    }

    private void setSelected(Button b,boolean selected) {
        b.setBackground(round(selected?Color.rgb(64,78,196):Color.rgb(18,42,67),selected?Color.rgb(255,210,0):Color.rgb(50,78,108),11));
        b.setTextColor(Color.WHITE);
    }

    private void send(String action) {
        Intent i=new Intent(action);
        i.setPackage(getPackageName());
        sendBroadcast(i);
    }

    private int parse(EditText e,int fallback) {
        try { return Integer.parseInt(e.getText().toString().trim()); } catch(Exception x) { return fallback; }
    }

    private LinearLayout card() {
        LinearLayout l=new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setPadding(dp(12),dp(12),dp(12),dp(12));
        l.setBackground(round(Color.rgb(10,27,47),Color.rgb(33,63,91),14));
        return l;
    }

    private TextView cellLabel(String s) {
        TextView t=text(s,12,Color.rgb(151,176,203),true);
        t.setGravity(Gravity.CENTER);
        return t;
    }

    private EditText num(String value) {
        EditText e=new EditText(this);
        e.setText(value);
        e.setTextColor(Color.WHITE);
        e.setHintTextColor(Color.rgb(110,134,160));
        e.setTextSize(16);
        e.setGravity(Gravity.CENTER);
        e.setSingleLine(true);
        e.setSelectAllOnFocus(true);
        e.setInputType(InputType.TYPE_CLASS_NUMBER);
        e.setPadding(dp(6),0,dp(6),0);
        e.setBackground(round(Color.rgb(15,42,67),Color.rgb(48,81,112),10));
        return e;
    }

    private Button button(String s,int fill) {
        Button b=new Button(this);
        b.setAllCaps(false);
        b.setText(s);
        b.setTextColor(Color.WHITE);
        b.setTextSize(15);
        b.setTypeface(Typeface.DEFAULT,Typeface.BOLD);
        b.setBackground(round(fill,Color.rgb(70,100,130),12));
        return b;
    }

    private Button segment(String s) {
        Button b=button(s,Color.rgb(18,42,67));
        b.setTextSize(12);
        return b;
    }

    private TextView text(String s,float size,int color,boolean bold) {
        TextView t=new TextView(this);
        t.setText(s);
        t.setTextSize(size);
        t.setTextColor(color);
        t.setTypeface(Typeface.DEFAULT,bold?Typeface.BOLD:Typeface.NORMAL);
        return t;
    }

    private GradientDrawable round(int fill,int stroke,int radius) {
        GradientDrawable g=new GradientDrawable();
        g.setColor(fill); g.setCornerRadius(dp(radius));
        g.setStroke(dp(1),stroke);
        return g;
    }

    private LinearLayout.LayoutParams marginTop(int top) {
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);
        p.topMargin=dp(top);
        return p;
    }

    private void addGap(LinearLayout l,int d) {
        Space s=new Space(this);
        l.addView(s,new LinearLayout.LayoutParams(dp(d),1));
    }

    private int dp(int v) { return Math.round(v*getResources().getDisplayMetrics().density); }
}
