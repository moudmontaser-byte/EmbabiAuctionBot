package com.embabi.auctionbot;

import android.Manifest;
import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity {
    private static final int REQ_CAPTURE = 7001;
    private static final int REQ_NOTIFICATIONS = 7002;

    private final TextView[] ratingValues = new TextView[5];
    private TextView systemStatus;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildUi();
        requestNotificationPermissionIfNeeded();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshStatus();
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(20), dp(18), dp(24));
        scroll.addView(root);

        TextView title = text("EMBABI AUCTION GENIUS", 24, true);
        root.addView(title);

        TextView sub = text(
                "5-player auction | 100M brain | screen-driven | no fixed round timer",
                14, false);
        sub.setPadding(0, dp(6), 0, dp(16));
        root.addView(sub);

        TextView note = text(
                "Choose the minimum acceptable rating for each of the five auction slots.",
                14, false);
        note.setPadding(0, 0, 0, dp(12));
        root.addView(note);

        String[] names = {
                "1. GK - economic",
                "2. DEF",
                "3. CM1",
                "4. CM2",
                "5. ST - finish strong"
        };

        for (int i = 0; i < 5; i++) {
            final int slot = i;

            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);

            TextView label = text(names[i], 16, true);
            row.addView(label, new LinearLayout.LayoutParams(0, dp(48), 1f));

            Button minus = new Button(this);
            minus.setText("-");
            row.addView(minus, new LinearLayout.LayoutParams(dp(52), dp(48)));

            TextView value = text(String.valueOf(Prefs.getMinRating(this, i)), 20, true);
            value.setGravity(Gravity.CENTER);
            ratingValues[i] = value;
            row.addView(value, new LinearLayout.LayoutParams(dp(60), dp(48)));

            Button plus = new Button(this);
            plus.setText("+");
            row.addView(plus, new LinearLayout.LayoutParams(dp(52), dp(48)));

            minus.setOnClickListener(v -> changeThreshold(slot, -1));
            plus.setOnClickListener(v -> changeThreshold(slot, 1));

            root.addView(row);
        }

        systemStatus = text("", 14, true);
        systemStatus.setPadding(0, dp(14), 0, dp(10));
        root.addView(systemStatus);

        Button overlay = bigButton("1) GRANT OVERLAY PERMISSION");
        overlay.setOnClickListener(v -> {
            if (!Settings.canDrawOverlays(this)) {
                startActivity(new Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + getPackageName())
                ));
            } else {
                toast("Overlay permission is already enabled");
            }
        });
        root.addView(overlay);

        Button accessibility = bigButton("2) ENABLE ACCESSIBILITY TAPS");
        accessibility.setOnClickListener(v ->
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        root.addView(accessibility);

        Button start = bigButton("3) START SCREEN READER + OVERLAY");
        start.setOnClickListener(v -> startCapture());
        root.addView(start);

        Button clearCal = bigButton("RESET GUI CALIBRATION");
        clearCal.setOnClickListener(v -> {
            Prefs.clearCalibration(this);
            refreshStatus();
            toast("Calibration cleared");
        });
        root.addView(clearCal);

        Button stop = bigButton("STOP SERVICE");
        stop.setOnClickListener(v -> {
            stopService(new Intent(this, AuctionService.class));
            toast("Auction service stopped");
        });
        root.addView(stop);

        TextView instructions = text(
                "Open a live auction, then tap CAL on the floating overlay. " +
                "Tap six places in order: +, Confirm, player rating, current price, " +
                "your budget, opponent budget.\n\n" +
                "The app reads the screen continuously. If the screen is unclear or a bid " +
                "cannot be verified, AUTO pauses instead of guessing.",
                14, false);
        instructions.setPadding(0, dp(16), 0, 0);
        root.addView(instructions);

        setContentView(scroll);
    }

    private void changeThreshold(int slot, int delta) {
        Prefs.setMinRating(this, slot, Prefs.getMinRating(this, slot) + delta);
        ratingValues[slot].setText(String.valueOf(Prefs.getMinRating(this, slot)));
    }

    private void startCapture() {
        if (!Settings.canDrawOverlays(this)) {
            toast("Grant overlay permission first");
            return;
        }

        MediaProjectionManager manager =
                (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        startActivityForResult(manager.createScreenCaptureIntent(), REQ_CAPTURE);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == REQ_CAPTURE && resultCode == RESULT_OK && data != null) {
            Intent service = new Intent(this, AuctionService.class);
            service.putExtra("resultCode", resultCode);
            service.putExtra("data", data);

            if (Build.VERSION.SDK_INT >= 26) startForegroundService(service);
            else startService(service);

            toast("Reader started. Open the auction and use CAL.");
        }
    }

    private void refreshStatus() {
        if (systemStatus == null) return;

        systemStatus.setText(
                "Overlay: " + (Settings.canDrawOverlays(this) ? "ON" : "OFF") +
                "   Accessibility: " + (isAccessibilityEnabled() ? "ON" : "OFF") +
                "   Calibration: " + (Prefs.isCalibrated(this) ? "READY" : "NOT SET")
        );
    }

    private boolean isAccessibilityEnabled() {
        String enabled = Settings.Secure.getString(
                getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        );

        if (TextUtils.isEmpty(enabled)) return false;

        String target = new ComponentName(
                this,
                AuctionAccessibilityService.class
        ).flattenToString();

        for (String part : enabled.split(":")) {
            if (part.equalsIgnoreCase(target)) return true;
        }
        return false;
    }

    private void requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
                        android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(
                    new String[]{Manifest.permission.POST_NOTIFICATIONS},
                    REQ_NOTIFICATIONS
            );
        }
    }

    private Button bigButton(String label) {
        Button b = new Button(this);
        b.setText(label);

        LinearLayout.LayoutParams lp =
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        dp(54)
                );
        lp.setMargins(0, dp(5), 0, dp(5));
        b.setLayoutParams(lp);

        return b;
    }

    private TextView text(String value, float size, boolean bold) {
        TextView t = new TextView(this);
        t.setText(value);
        t.setTextSize(size);

        if (bold) {
            t.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        }

        return t;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }
}
