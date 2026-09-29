package com.embabi.auctionbot;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.graphics.PointF;
import android.graphics.Rect;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.google.android.gms.tasks.Task;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;

import java.nio.ByteBuffer;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class AuctionService extends Service {
    private static final String CHANNEL = "auction_genius";
    private static final int NOTIFY_ID = 41;

    private WindowManager wm;
    private LinearLayout panel;
    private LinearLayout minPanel;
    private TextView status;
    private Button autoButton;

    private boolean autoEnabled = false;

    private MediaProjection projection;
    private VirtualDisplay virtualDisplay;
    private ImageReader imageReader;
    private HandlerThread captureThread;
    private Handler captureHandler;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private TextRecognizer recognizer;

    private int screenW;
    private int screenH;
    private int densityDpi;

    private volatile boolean ocrBusy = false;
    private long lastOcrAt = 0L;

    private AuctionEngine engine;
    private AuctionEngine.Snapshot lastRaw;
    private int stableFrames = 0;

    private float dragStartX;
    private float dragStartY;
    private int panelStartX;
    private int panelStartY;

    @Override
    public void onCreate() {
        super.onCreate();

        engine = new AuctionEngine(this);
        recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);

        createNotification();
        createCaptureThread();
        createOverlay();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && projection == null) {
            int resultCode = intent.getIntExtra("resultCode", 0);
            Intent data = intent.getParcelableExtra("data");

            if (resultCode != 0 && data != null) {
                startProjection(resultCode, data);
            }
        }

        return START_STICKY;
    }

    private void createNotification() {
        NotificationManager manager =
                (NotificationManager) getSystemService(NOTIFICATION_SERVICE);

        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(
                    new NotificationChannel(
                            CHANNEL,
                            "Embabi Auction Genius",
                            NotificationManager.IMPORTANCE_LOW
                    )
            );
        }

        PendingIntent openApp = PendingIntent.getActivity(
                this,
                0,
                new Intent(this, MainActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL)
                : new Notification.Builder(this);

        builder.setContentTitle("Embabi Auction Genius")
                .setContentText("Watching the auction screen")
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentIntent(openApp)
                .setOngoing(true);

        startForeground(NOTIFY_ID, builder.build());
    }

    private void createCaptureThread() {
        captureThread = new HandlerThread("AuctionCapture");
        captureThread.start();
        captureHandler = new Handler(captureThread.getLooper());
    }

    private void startProjection(int resultCode, Intent data) {
        DisplayMetrics metrics = new DisplayMetrics();
        wm.getDefaultDisplay().getRealMetrics(metrics);

        screenW = metrics.widthPixels;
        screenH = metrics.heightPixels;
        densityDpi = metrics.densityDpi;

        MediaProjectionManager projectionManager =
                (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);

        projection = projectionManager.getMediaProjection(resultCode, data);

        if (projection == null) {
            updateStatus("Screen projection failed");
            return;
        }

        projection.registerCallback(new MediaProjection.Callback() {
            @Override
            public void onStop() {
                autoEnabled = false;
                mainHandler.post(() -> {
                    syncAutoButton();
                    updateStatus("Screen capture stopped");
                });
            }
        }, captureHandler);

        imageReader = ImageReader.newInstance(
                screenW,
                screenH,
                PixelFormat.RGBA_8888,
                2
        );

        virtualDisplay = projection.createVirtualDisplay(
                "EmbabiAuctionScreen",
                screenW,
                screenH,
                densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader.getSurface(),
                null,
                captureHandler
        );

        imageReader.setOnImageAvailableListener(this::onImage, captureHandler);

        updateStatus(
                Prefs.isCalibrated(this)
                        ? "Reader ON | AUTO OFF"
                        : "Reader ON | CAL REQUIRED"
        );
    }

    private void onImage(ImageReader reader) {
        Image image = null;

        try {
            image = reader.acquireLatestImage();

            if (image == null) return;

            long now = SystemClock.elapsedRealtime();

            // This only limits OCR load. It is NOT a gameplay timer.
            if (ocrBusy || now - lastOcrAt < 220) return;

            lastOcrAt = now;
            ocrBusy = true;

            Image.Plane plane = image.getPlanes()[0];
            ByteBuffer buffer = plane.getBuffer();

            int pixelStride = plane.getPixelStride();
            int rowStride = plane.getRowStride();
            int rowPadding = rowStride - pixelStride * screenW;
            int paddedW = screenW + rowPadding / pixelStride;

            Bitmap padded =
                    Bitmap.createBitmap(paddedW, screenH, Bitmap.Config.ARGB_8888);

            padded.copyPixelsFromBuffer(buffer);

            Bitmap frame =
                    Bitmap.createBitmap(padded, 0, 0, screenW, screenH);

            if (padded != frame) padded.recycle();

            Task<Text> task =
                    recognizer.process(InputImage.fromBitmap(frame, 0));

            task.addOnSuccessListener(this::handleOcr);

            task.addOnFailureListener(error -> {
                engine.onUnverifiedFrame();
                mainHandler.post(() ->
                        updateStatus("OCR uncertain | NO ACTION"));
            });

            task.addOnCompleteListener(done -> {
                frame.recycle();
                ocrBusy = false;
            });

        } catch (Throwable t) {
            ocrBusy = false;

        } finally {
            if (image != null) image.close();
        }
    }

    private void handleOcr(Text text) {
        AuctionEngine.Snapshot snapshot = parseSnapshot(text);

        if (!snapshot.valid) {
            stableFrames = 0;
            lastRaw = null;
            engine.onUnverifiedFrame();

            mainHandler.post(() ->
                    showDecision(null, snapshot));

            return;
        }

        if (snapshot.sameValues(lastRaw)) stableFrames++;
        else stableFrames = 1;

        lastRaw = snapshot;

        if (stableFrames < 2) {
            mainHandler.post(() ->
                    showDecision(null, snapshot));
            return;
        }

        AuctionEngine.Decision decision =
                engine.onStableSnapshot(snapshot);

        mainHandler.post(() -> {
            showDecision(decision, snapshot);

            if (decision.action == AuctionEngine.Action.SAFETY_PAUSE) {
                pauseAuto("Safety verification failed");
                return;
            }

            if (decision.action == AuctionEngine.Action.COMPLETE) {
                autoEnabled = false;
                syncAutoButton();
                return;
            }

            if (autoEnabled &&
                    decision.action == AuctionEngine.Action.BID) {
                performBid(snapshot);
            }
        });
    }

    private AuctionEngine.Snapshot parseSnapshot(Text text) {
        if (!Prefs.isCalibrated(this) ||
                screenW <= 0 ||
                screenH <= 0) {
            return new AuctionEngine.Snapshot(0, 0, 0, 0, false);
        }

        Integer rating =
                findRating(text, Prefs.getPoint(this, "rating"));

        Integer price =
                findMoney(text, Prefs.getPoint(this, "price"));

        Integer mine =
                findMoney(text, Prefs.getPoint(this, "mine"));

        Integer opponent =
                findMoney(text, Prefs.getPoint(this, "opponent"));

        boolean valid =
                rating != null &&
                price != null &&
                mine != null &&
                opponent != null &&
                rating >= 70 && rating <= 99 &&
                price >= 0 && price <= 100 &&
                mine >= 0 && mine <= 100 &&
                opponent >= 0 && opponent <= 100;

        return new AuctionEngine.Snapshot(
                rating == null ? 0 : rating,
                price == null ? 0 : price,
                mine == null ? 0 : mine,
                opponent == null ? 0 : opponent,
                valid
        );
    }

    private Integer findRating(Text text, PointF point) {
        if (point == null) return null;

        double best = Double.MAX_VALUE;
        Integer value = null;

        for (Text.TextBlock block : text.getTextBlocks()) {
            for (Text.Line line : block.getLines()) {
                for (Text.Element element : line.getElements()) {
                    Integer rating = parseRating(element.getText());
                    Rect box = element.getBoundingBox();

                    if (rating != null && box != null) {
                        double distance =
                                distanceTo(box.centerX(), box.centerY(), point);

                        if (distance < best) {
                            best = distance;
                            value = rating;
                        }
                    }
                }
            }
        }

        return best <= maxDistance() ? value : null;
    }

    private Integer findMoney(Text text, PointF point) {
        if (point == null) return null;

        double best = Double.MAX_VALUE;
        Integer value = null;

        for (Text.TextBlock block : text.getTextBlocks()) {
            for (Text.Line line : block.getLines()) {
                Integer lineValue = parseMoney(line.getText());
                Rect lineBox = line.getBoundingBox();

                if (lineValue != null && lineBox != null) {
                    double distance =
                            distanceTo(lineBox.centerX(), lineBox.centerY(), point);

                    if (distance < best) {
                        best = distance;
                        value = lineValue;
                    }
                }

                for (Text.Element element : line.getElements()) {
                    Integer elementValue = parseMoney(element.getText());
                    Rect box = element.getBoundingBox();

                    if (elementValue != null && box != null) {
                        double distance =
                                distanceTo(box.centerX(), box.centerY(), point);

                        if (distance < best) {
                            best = distance;
                            value = elementValue;
                        }
                    }
                }
            }
        }

        return best <= maxDistance() ? value : null;
    }

    private double distanceTo(float x, float y, PointF point) {
        float targetX = point.x * screenW;
        float targetY = point.y * screenH;

        double dx = x - targetX;
        double dy = y - targetY;

        return Math.sqrt(dx * dx + dy * dy);
    }

    private double maxDistance() {
        return Math.sqrt(
                (double) screenW * screenW +
                (double) screenH * screenH
        ) * 0.20;
    }

    private Integer parseRating(String raw) {
        String value = normalizeOcr(raw);

        Matcher matcher =
                Pattern.compile("(?<!\\d)(\\d{2})(?!\\d)")
                        .matcher(value);

        while (matcher.find()) {
            int rating = Integer.parseInt(matcher.group(1));

            if (rating >= 50 && rating <= 99) {
                return rating;
            }
        }

        return null;
    }

    private Integer parseMoney(String raw) {
        if (raw == null) return null;

        String value =
                normalizeOcr(raw).toUpperCase(Locale.US);

        Matcher millions =
                Pattern.compile(
                        "(\\d{1,3}(?:[.,]\\d{1,2})?)\\s*M"
                ).matcher(value);

        if (millions.find()) {
            try {
                double parsed =
                        Double.parseDouble(
                                millions.group(1).replace(',', '.')
                        );

                if (parsed >= 0 && parsed <= 100) {
                    return (int) Math.round(parsed);
                }
            } catch (Exception ignored) {}
        }

        String digits =
                value.replaceAll("[^0-9]", "");

        if (digits.isEmpty()) return null;

        try {
            long n = Long.parseLong(digits);

            if (n >= 1_000_000L) {
                long millionsValue =
                        Math.round(n / 1_000_000.0);

                if (millionsValue >= 0 &&
                        millionsValue <= 100) {
                    return (int) millionsValue;
                }
            }

            if (n <= 100) return (int) n;

        } catch (Exception ignored) {}

        return null;
    }

    private String normalizeOcr(String raw) {
        if (raw == null) return "";

        return raw
                .toUpperCase(Locale.US)
                .replace('O', '0')
                .replace('I', '1')
                .replace('L', '1');
    }

    private void performBid(AuctionEngine.Snapshot snapshot) {
        if (!autoEnabled ||
                engine.isPendingOwnBid()) {
            return;
        }

        if (!Prefs.isCalibrated(this)) {
            pauseAuto("Calibration missing");
            return;
        }

        if (!AuctionAccessibilityService.isReady()) {
            pauseAuto("Accessibility is OFF");
            return;
        }

        PointF plus =
                Prefs.getPoint(this, "plus");

        PointF confirm =
                Prefs.getPoint(this, "confirm");

        if (plus == null || confirm == null) {
            pauseAuto("Button positions missing");
            return;
        }

        engine.markOwnBidStarted(snapshot.price);

        float plusX = plus.x * screenW;
        float plusY = plus.y * screenH;
        float confirmX = confirm.x * screenW;
        float confirmY = confirm.y * screenH;

        AuctionAccessibilityService.clickPlus(
                plusX,
                plusY,
                plusOk -> {
                    if (!plusOk) {
                        engine.cancelPending();

                        mainHandler.post(() ->
                                pauseAuto("PLUS could not be verified"));

                        return;
                    }

                    AuctionAccessibilityService.clickConfirm(
                            confirmX,
                            confirmY,
                            confirmOk -> {
                                if (!confirmOk) {
                                    engine.cancelPending();

                                    mainHandler.post(() ->
                                            pauseAuto("CONFIRM could not be verified"));

                                } else {
                                    engine.markConfirmDispatched();

                                    mainHandler.post(() ->
                                            updateStatus(
                                                    "Bid sent | waiting for SCREEN verification"
                                            ));
                                }
                            }
                    );
                }
        );
    }

    private void pauseAuto(String reason) {
        autoEnabled = false;
        syncAutoButton();
        updateStatus(reason + " | AUTO PAUSED");
    }

    private void createOverlay() {
        panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(10), dp(8), dp(10), dp(8));
        panel.setBackgroundColor(0xE0121212);

        TextView header = new TextView(this);
        header.setText("AUCTION GENIUS  =");
        header.setTextColor(0xFFFFFFFF);
        header.setTextSize(14);
        header.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        panel.addView(header);

        status = new TextView(this);
        status.setTextColor(0xFFFFFFFF);
        status.setTextSize(12);
        status.setText("Reader not started");
        panel.addView(status);

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);

        autoButton = new Button(this);
        autoButton.setText("AUTO OFF");
        autoButton.setOnClickListener(v -> {
            if (!Prefs.isCalibrated(this)) {
                startCalibration();
                return;
            }

            if (!AuctionAccessibilityService.isReady()) {
                updateStatus("Enable Accessibility first");
                return;
            }

            autoEnabled = !autoEnabled;
            syncAutoButton();
        });

        buttons.addView(
                autoButton,
                new LinearLayout.LayoutParams(0, dp(44), 1f)
        );

        Button calibrate = new Button(this);
        calibrate.setText("CAL");
        calibrate.setOnClickListener(v -> startCalibration());

        buttons.addView(
                calibrate,
                new LinearLayout.LayoutParams(0, dp(44), 1f)
        );

        Button min = new Button(this);
        min.setText("MIN");
        min.setOnClickListener(v -> {
            if (minPanel != null) {
                minPanel.setVisibility(
                        minPanel.getVisibility() == View.VISIBLE
                                ? View.GONE
                                : View.VISIBLE
                );
                refreshMinPanel();
            }
        });

        buttons.addView(
                min,
                new LinearLayout.LayoutParams(0, dp(44), 1f)
        );

        Button reset = new Button(this);
        reset.setText("R");
        reset.setOnClickListener(v -> {
            engine.resetSession();
            autoEnabled = false;
            syncAutoButton();
            updateStatus("Session reset | Round 1/5");
        });

        buttons.addView(
                reset,
                new LinearLayout.LayoutParams(dp(48), dp(44))
        );

        panel.addView(buttons);

        minPanel = new LinearLayout(this);
        minPanel.setOrientation(LinearLayout.VERTICAL);
        minPanel.setVisibility(View.GONE);
        panel.addView(minPanel);

        WindowManager.LayoutParams params = overlayParams();
        wm.addView(panel, params);

        header.setOnTouchListener((v, event) -> {
            WindowManager.LayoutParams p =
                    (WindowManager.LayoutParams) panel.getLayoutParams();

            if (event.getAction() == MotionEvent.ACTION_DOWN) {
                dragStartX = event.getRawX();
                dragStartY = event.getRawY();
                panelStartX = p.x;
                panelStartY = p.y;
                return true;
            }

            if (event.getAction() == MotionEvent.ACTION_MOVE) {
                p.x =
                        panelStartX +
                        (int) (event.getRawX() - dragStartX);

                p.y =
                        panelStartY +
                        (int) (event.getRawY() - dragStartY);

                wm.updateViewLayout(panel, p);
                return true;
            }

            return false;
        });
    }

    private void refreshMinPanel() {
        if (minPanel == null ||
                minPanel.getVisibility() != View.VISIBLE) {
            return;
        }

        minPanel.removeAllViews();

        for (int i = 0; i < 5; i++) {
            final int slot = i;

            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);

            TextView label = new TextView(this);
            label.setText(
                    AuctionEngine.SLOT_NAMES[i] +
                    "  min " +
                    Prefs.getMinRating(this, i)
            );
            label.setTextColor(0xFFFFFFFF);
            label.setTextSize(12);

            row.addView(
                    label,
                    new LinearLayout.LayoutParams(0, dp(38), 1f)
            );

            Button minus = new Button(this);
            minus.setText("-");
            row.addView(
                    minus,
                    new LinearLayout.LayoutParams(dp(48), dp(38))
            );

            Button plus = new Button(this);
            plus.setText("+");
            row.addView(
                    plus,
                    new LinearLayout.LayoutParams(dp(48), dp(38))
            );

            minus.setOnClickListener(v -> {
                Prefs.setMinRating(
                        this,
                        slot,
                        Prefs.getMinRating(this, slot) - 1
                );
                refreshMinPanel();
            });

            plus.setOnClickListener(v -> {
                Prefs.setMinRating(
                        this,
                        slot,
                        Prefs.getMinRating(this, slot) + 1
                );
                refreshMinPanel();
            });

            minPanel.addView(row);
        }
    }

    private WindowManager.LayoutParams overlayParams() {
        WindowManager.LayoutParams params =
                new WindowManager.LayoutParams(
                        dp(330),
                        WindowManager.LayoutParams.WRAP_CONTENT,
                        Build.VERSION.SDK_INT >= 26
                                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                                : WindowManager.LayoutParams.TYPE_PHONE,
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
                                WindowManager.LayoutParams.FLAG_SECURE,
                        PixelFormat.TRANSLUCENT
                );

        params.gravity = Gravity.TOP | Gravity.START;
        params.x = dp(8);
        params.y = dp(70);

        return params;
    }

    private void startCalibration() {
        autoEnabled = false;
        syncAutoButton();

        if (panel != null) {
            panel.setVisibility(View.GONE);
        }

        String[] keys = {
                "plus",
                "confirm",
                "rating",
                "price",
                "mine",
                "opponent"
        };

        String[] labels = {
                "1/6 TAP THE + BID BUTTON",
                "2/6 TAP THE CONFIRM BUTTON",
                "3/6 TAP PLAYER RATING",
                "4/6 TAP CURRENT AUCTION PRICE",
                "5/6 TAP YOUR BUDGET",
                "6/6 TAP OPPONENT BUDGET"
        };

        LinearLayout layer = new LinearLayout(this);
        layer.setOrientation(LinearLayout.VERTICAL);
        layer.setGravity(
                Gravity.TOP |
                Gravity.CENTER_HORIZONTAL
        );
        layer.setBackgroundColor(0x11000000);

        TextView instruction = new TextView(this);
        instruction.setText(labels[0]);
        instruction.setTextColor(0xFFFFFFFF);
        instruction.setTextSize(18);
        instruction.setGravity(Gravity.CENTER);
        instruction.setTypeface(
                android.graphics.Typeface.DEFAULT_BOLD
        );
        instruction.setPadding(
                dp(12),
                dp(18),
                dp(12),
                dp(18)
        );
        instruction.setBackgroundColor(0xE0000000);

        layer.addView(
                instruction,
                new LinearLayout.LayoutParams(
                        WindowManager.LayoutParams.MATCH_PARENT,
                        WindowManager.LayoutParams.WRAP_CONTENT
                )
        );

        final int[] step = {0};

        layer.setOnTouchListener((v, event) -> {
            if (event.getAction() != MotionEvent.ACTION_DOWN) {
                return true;
            }

            int width =
                    Math.max(
                            1,
                            screenW > 0
                                    ? screenW
                                    : getResources()
                                        .getDisplayMetrics()
                                        .widthPixels
                    );

            int height =
                    Math.max(
                            1,
                            screenH > 0
                                    ? screenH
                                    : getResources()
                                        .getDisplayMetrics()
                                        .heightPixels
                    );

            Prefs.savePoint(
                    this,
                    keys[step[0]],
                    event.getRawX() / width,
                    event.getRawY() / height
            );

            step[0]++;

            if (step[0] >= keys.length) {
                try {
                    wm.removeView(layer);
                } catch (Exception ignored) {}

                panel.setVisibility(View.VISIBLE);
                updateStatus("Calibration READY | AUTO OFF");

            } else {
                instruction.setText(labels[step[0]]);
            }

            return true;
        });

        WindowManager.LayoutParams params =
                new WindowManager.LayoutParams(
                        WindowManager.LayoutParams.MATCH_PARENT,
                        WindowManager.LayoutParams.MATCH_PARENT,
                        Build.VERSION.SDK_INT >= 26
                                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                                : WindowManager.LayoutParams.TYPE_PHONE,
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
                                WindowManager.LayoutParams.FLAG_SECURE,
                        PixelFormat.TRANSLUCENT
                );

        params.gravity = Gravity.TOP | Gravity.START;

        wm.addView(layer, params);
    }

    private void showDecision(
            AuctionEngine.Decision decision,
            AuctionEngine.Snapshot snapshot) {

        if (status == null) return;

        if (!Prefs.isCalibrated(this)) {
            updateStatus("CAL REQUIRED | use CAL on live auction");
            return;
        }

        if (snapshot == null || !snapshot.valid) {
            updateStatus("Watching screen... no verified auction state");
            return;
        }

        if (decision == null) {
            updateStatus(
                    "Reading... R" +
                    engine.getRound() +
                    "/5 | OVR " +
                    snapshot.rating +
                    " | " +
                    snapshot.price +
                    "M"
            );
            return;
        }

        String slot =
                AuctionEngine.SLOT_NAMES[
                        Math.max(
                                0,
                                Math.min(4, decision.round - 1)
                        )
                ];

        String line1 =
                "R" + decision.round + "/5 " +
                slot +
                " | OVR " + snapshot.rating +
                " | " + snapshot.price + "M" +
                " | You " + snapshot.myBudget + "M" +
                " | Opp " + snapshot.oppBudget + "M";

        String line2 =
                decision.action.name() +
                " | caps " +
                decision.softCap +
                "/" +
                decision.hardCap +
                "M | min " +
                decision.minRating;

        String line3 =
                decision.opponentStyle +
                " " +
                decision.opponentConfidence +
                "% | est " +
                decision.estimatedOpponentCeiling +
                "M";

        String line4 = decision.reason;

        updateStatus(
                line1 +
                "\n" +
                line2 +
                "\n" +
                line3 +
                "\n" +
                line4
        );
    }

    private void updateStatus(String value) {
        if (status != null) status.setText(value);
    }

    private void syncAutoButton() {
        if (autoButton != null) {
            autoButton.setText(
                    autoEnabled
                            ? "AUTO ON"
                            : "AUTO OFF"
            );
        }
    }

    private int dp(int value) {
        return Math.round(
                value *
                getResources()
                        .getDisplayMetrics()
                        .density
        );
    }

    @Override
    public void onDestroy() {
        autoEnabled = false;

        try {
            if (panel != null) wm.removeView(panel);
        } catch (Exception ignored) {}

        try {
            if (virtualDisplay != null) virtualDisplay.release();
        } catch (Exception ignored) {}

        try {
            if (imageReader != null) imageReader.close();
        } catch (Exception ignored) {}

        try {
            if (projection != null) projection.stop();
        } catch (Exception ignored) {}

        try {
            if (recognizer != null) recognizer.close();
        } catch (Exception ignored) {}

        try {
            if (captureThread != null) captureThread.quitSafely();
        } catch (Exception ignored) {}

        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
