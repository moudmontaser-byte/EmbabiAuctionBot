package com.embabi.auctionbot;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.accessibilityservice.GestureDescription;
import android.content.*;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.hardware.HardwareBuffer;
import android.os.*;
import android.view.*;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.*;

import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;

import java.text.Normalizer;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class AuctionAccessibilityService extends AccessibilityService {
    private static volatile AuctionAccessibilityService instance;

    public interface Callback { void onDone(boolean ok); }
    private interface BitmapConsumer { void accept(Bitmap bitmap); }
    private interface IntResult { void accept(Integer value); }

    private static class NumericHints {
        Integer rating;
        Integer price;
        Integer mine;
        Integer opp;
    }


    private static final long ACTION_DEBOUNCE_MS = 1150L;
    private final Handler h = new Handler(Looper.getMainLooper());
    private final TextRecognizer recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);

    private BroadcastReceiver receiver;
    private AuctionEngine engine;

    private boolean running = false;
    private boolean paused = false;
    private boolean scanQueued = false;
    private boolean screenshotBusy = false;
    private boolean screenReadBusy = false;
    private boolean turnVisualBusy = false;

    private boolean resultWaiting = false;
    private boolean bidFlowInProgress = false;
    private boolean awaitingConfirm = false;
    private int confirmVerifyCount = 0;
    private int confirmRetryCount = 0;
    private int[] confirmBaseline = null;

    private long actionCooldownUntil = 0L;
    private long postActionNotBefore = 0L;
    private long returnToMainClickedAt = 0L;
    private long startedAt = 0L;
    private int matchesCompleted = 0;
    private boolean stopAfterReturn = false;
    private boolean mustSeeWaitingBeforeNextBid = false;

    private AuctionEngine.Snapshot stableCandidate = null;
    private int stableCandidateCount = 0;

    private String lastStatus = "جاهز — افتح Embabi Games";
    private String lastDecision = "—";
    private Integer lastRating = null, lastPrice = null, lastMine = null, lastOpp = null;
    private String targetPackage = null;

    // Post-game state machine copied from the actual recorded flow:
    // 0 auction, 1 Coaches -> View Lineups, 2 Final Lineups -> Start Simulation,
    // 3 Simulation -> View Results, 4 Summary -> scroll/find Return Home,
    // 5 wait for Games hub, 6 wait for actual Home.
    private int postMatchStage = 0;
    private int postMatchSwipeAttempts = 0;
    private int grayArrowAttempts = 0;

    // Floating control overlay
    private WindowManager wm;
    private View floatingView;
    private WindowManager.LayoutParams floatingLp;
    private int overlaySavedX = Integer.MIN_VALUE;
    private int overlaySavedY = Integer.MIN_VALUE;
    private boolean overlayExpanded = true;
    private TextView ovStatus, ovRunState, ovRound, ovRating, ovPrice, ovBudgets, ovDecision, ovRepeat;
    private final TextView[] ovPlayerMins = new TextView[5];

    // Calibration overlay
    private View calibrationRoot;
    private CalibrationView calibrationView;
    private TextView calibrationInstruction;
    private Button calibrationSave;
    private int calibrationStep = 0;

    private final Runnable monitor = new Runnable() {
        @Override public void run() {
            if (running && !paused) analyzeCurrentScreen();
            if (running) h.postDelayed(this, 450);
        }
    };

    private final Runnable heartbeat = new Runnable() {
        @Override public void run() {
            Prefs.setAccessHeartbeat(AuctionAccessibilityService.this, System.currentTimeMillis());
            h.postDelayed(this, 1200);
        }
    };

    @Override protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        engine = new AuctionEngine(this);
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);

        receiver = new BroadcastReceiver() {
            @Override public void onReceive(Context c, Intent i) {
                String a = i.getAction();
                if (BotActions.START.equals(a)) startBot(true);
                else if (BotActions.STOP.equals(a)) stopBot("تم إيقاف البوت يدويًا");
                else if (BotActions.PAUSE.equals(a)) togglePause();
                else if (BotActions.SHOW_OVERLAY.equals(a)) {
                    Prefs.setOverlayWanted(AuctionAccessibilityService.this, true);
                    showFloatingOverlay(true);
                }
                else if (BotActions.HIDE_OVERLAY.equals(a)) {
                    Prefs.setOverlayWanted(AuctionAccessibilityService.this, false);
                    removeFloatingOverlay();
                }
                else if (BotActions.CALIBRATE.equals(a)) startCalibration();
                else if (BotActions.TEST_OCR.equals(a)) testOcr();
            }
        };

        IntentFilter f = new IntentFilter();
        f.addAction(BotActions.START);
        f.addAction(BotActions.STOP);
        f.addAction(BotActions.PAUSE);
        f.addAction(BotActions.SHOW_OVERLAY);
        f.addAction(BotActions.HIDE_OVERLAY);
        f.addAction(BotActions.CALIBRATE);
        f.addAction(BotActions.TEST_OCR);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(receiver, f, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(receiver, f);

        lastStatus = Prefs.lastStatus(this);
        int v;
        v = Prefs.lastRating(this); lastRating = v >= 0 ? v : null;
        v = Prefs.lastPrice(this); lastPrice = v >= 0 ? v : null;
        v = Prefs.lastMine(this); lastMine = v >= 0 ? v : null;
        v = Prefs.lastOpp(this); lastOpp = v >= 0 ? v : null;
        lastDecision = Prefs.lastDecision(this);
        matchesCompleted = Prefs.matchesCompleted(this);

        Prefs.setAccessHeartbeat(this, System.currentTimeMillis());
        h.removeCallbacks(heartbeat);
        h.post(heartbeat);

        if (Prefs.overlayWanted(this)) showFloatingOverlay(true);
        if (Prefs.botWanted(this)) h.postDelayed(() -> startBot(false), 600);
        status(lastStatus);
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        if (running && !paused) queueScan(120);
    }

    @Override public void onInterrupt() {}

    @Override public void onDestroy() {
        Prefs.setAccessHeartbeat(this, 0L);
        if (instance == this) instance = null;
        h.removeCallbacksAndMessages(null);
        removeFloatingOverlay();
        removeCalibration();
        if (receiver != null) try { unregisterReceiver(receiver); } catch (Exception ignored) {}
        recognizer.close();
        super.onDestroy();
    }

    private void startBot(boolean fresh) {
        // v16 can run without CAL: it has automatic screen zones and tap fallbacks.
        // A completed CAL only improves precision and overrides the defaults.
        AccessibilityServiceInfo info = getServiceInfo();
        if (info == null ||
                (info.getCapabilities() & AccessibilityServiceInfo.CAPABILITY_CAN_PERFORM_GESTURES) == 0) {
            status("صلاحية الضغط غير مفعّلة — فعّل Accessibility للخدمة من جديد");
            return;
        }
        if (Build.VERSION.SDK_INT >= 30 &&
                (info.getCapabilities() & AccessibilityServiceInfo.CAPABILITY_CAN_TAKE_SCREENSHOT) == 0) {
            status("صلاحية قراءة الشاشة غير مفعّلة — فعّل Accessibility للخدمة من جديد");
            return;
        }

        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root != null && root.getPackageName() != null &&
                !getPackageName().contentEquals(root.getPackageName())) {
            targetPackage = root.getPackageName().toString();
        }

        running = true;
        paused = fresh ? false : Prefs.botPaused(this);
        Prefs.setBotWanted(this, true);
        Prefs.setBotPaused(this, paused);

        if (fresh) {
            engine.resetSession();
            matchesCompleted = 0;
            Prefs.setMatchesCompleted(this, 0);
            startedAt = System.currentTimeMillis();
            Prefs.setBotStartedAt(this, startedAt);
            clearSessionScreenState();
            Prefs.setCurrentRound(this, 1);
        } else {
            startedAt = Prefs.botStartedAt(this);
            if (startedAt <= 0) startedAt = System.currentTimeMillis();
        }

        showFloatingOverlay(true);
        status(paused ? "البوت محفوظ على PAUSE" :
                (Prefs.isCalibrated(this)
                        ? "البوت شغال — CAL + AUTO"
                        : "البوت شغال — AUTO بدون CAL"));
        h.removeCallbacks(monitor);
        h.post(monitor);
        queueScan(80);
    }

    private void stopBot(String message) {
        running = false;
        paused = false;
        Prefs.setBotWanted(this, false);
        Prefs.setBotPaused(this, false);
        h.removeCallbacks(monitor);
        cancelBidFlow();
        status(message);
        refreshOverlay();
    }

    private void togglePause() {
        if (!running) {
            startBot(true);
            return;
        }
        paused = !paused;
        Prefs.setBotPaused(this, paused);
        status(paused ? "PAUSE — لن ألمس الشاشة" : "RUNNING — رجعت أراقب الشاشة");
        if (!paused) queueScan(60);
        refreshOverlay();
    }

    private void clearSessionScreenState() {
        resultWaiting = false;
        postMatchStage = 0;
        postMatchSwipeAttempts = 0;
        grayArrowAttempts = 0;
        stopAfterReturn = false;
        postActionNotBefore = 0L;
        returnToMainClickedAt = 0L;
        mustSeeWaitingBeforeNextBid = false;
        confirmRetryCount = 0;
        stableCandidate = null;
        stableCandidateCount = 0;
        cancelBidFlow();
        lastDecision = "—";
    }

    private void cancelBidFlow() {
        bidFlowInProgress = false;
        awaitingConfirm = false;
        confirmVerifyCount = 0;
        confirmBaseline = null;
        if (engine != null) engine.cancelPending();
    }

    private void queueScan(long delay) {
        if (scanQueued) return;
        scanQueued = true;
        h.postDelayed(() -> {
            scanQueued = false;
            if (running && !paused) analyzeCurrentScreen();
        }, Math.max(0, delay));
    }

    private void analyzeCurrentScreen() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) {
            status("أراقب الشاشة… في انتظار Embabi Games");
            return;
        }

        if (!isTargetPackage(root)) {
            status("البوت شغال — ارجع إلى Embabi Games");
            return;
        }

        String raw = collectVisibleText(root);
        if (raw.trim().length() < 6) raw = collectText(root);
        String all = normalize(raw);
        long now = System.currentTimeMillis();

        Integer guiRound = extractGuiRound(all);
        if (guiRound != null && postMatchStage == 0 && engine != null &&
                guiRound != engine.getRound()) {
            engine.syncRoundFromScreen(guiRound);
            Prefs.setCurrentRound(this, guiRound);
            stableCandidate = null;
            stableCandidateCount = 0;
        }

        // Same successful rule used by Hidden Player:
        // after EVERY physical action, give the WebView a full second to render.
        if (now < actionCooldownUntil) {
            status("أنتظر ثانية لظهور الشاشة الجديدة…");
            return;
        }

        // Withdrawal / defeat result: if Return Home is visible, click it immediately.
        if (containsAny(all,
                "العودة للرئيسية", "العوده للرئيسيه",
                "العودة للصفحة الرئيسية", "العوده للصفحه الرئيسيه",
                "return home", "return to main")) {
            status("لقيت «العودة للرئيسية» ✓ — أحدد مكانها من الصورة وأضغطها");
            clickRenderedTextOnce(
                    () -> {
                        onMatchCompleted();
                        postMatchStage = 5;
                        returnToMainClickedAt = System.currentTimeMillis();
                    },
                    "العودة للرئيسية", "العوده للرئيسيه",
                    "العودة للصفحة الرئيسية", "العوده للصفحه الرئيسيه",
                    "return home", "return to main");
            return;
        }

        // ---------- Strict post-match loop ----------
        if (postMatchStage == 0 && isPostMatchScreen(all)) {
            postMatchStage = 1;
            postMatchSwipeAttempts = 0;
            resultWaiting = false;
            cancelBidFlow();
            mustSeeWaitingBeforeNextBid = false;
            status("انتهت الجيم ✓ — أنزل لحد «عرض التشكيلات/النتائج»");
        }

        if (postMatchStage > 0) {
            if (now < postActionNotBefore) {
                status("أنتظر ثانية لانتقال الشاشة…");
                return;
            }
            scanPostMatchByOcr();
            return;
        }

        // ---------- Auction result between the 5 players ----------
        if (isAuctionResultScreen(all)) {
            resultWaiting = true;
            stableCandidate = null;
            stableCandidateCount = 0;
            mustSeeWaitingBeforeNextBid = false;
            cancelBidFlow();

            int r = engine == null ? Prefs.currentRound(this) : engine.getRound();
            status(r >= 5
                    ? "نتيجة اللاعب الخامس ✓ — أنتظر نهاية الجيم"
                    : "نتيجة المزاد ظاهرة ✓ — لا ألمس شيء لحد اللاعب التالي");
            return;
        }

        boolean activeAuction = isAuctionActiveScreen(all);

        if (resultWaiting && activeAuction) {
            if (guiRound != null) {
                engine.syncRoundFromScreen(guiRound);
                Prefs.setCurrentRound(this, guiRound);
            } else if (engine != null && engine.getRound() < 5) {
                engine.advanceRoundFromScreen();
                Prefs.setCurrentRound(this, engine.getRound());
            }

            resultWaiting = false;
            stableCandidate = null;
            stableCandidateCount = 0;
            lastDecision = "NEW";
            status("ظهر اللاعب التالي ✓ — R" + currentRound() + "/5");
            return;
        }

        // In a live auction, DO NOT trust WebView turn labels from Accessibility.
        // The recorded videos showed stale/misleading "انتظار المزايدة" text while
        // the real rendered button was "تأكيد المزايدة". Read the actual button.
        if (activeAuction) {
            detectAuctionTurnVisually();
            return;
        }

        // ---------- Normal navigation ----------
        if (!activeAuction) {
            if (containsAny(all, "هاتلي منافس", "هات لي منافس", "find opponent")) {
                engine.resetSession();
                Prefs.setCurrentRound(this, 1);
                status("لقيت «هاتلي منافس» ✓");
                clickTextAny(root, "هاتلي منافس", "هات لي منافس", "find opponent");
                return;
            }

            if (containsAny(all, "اختر الكارت", "اختار الكارت", "choose card") &&
                    containsAny(all, "المزاد", "auction")) {
                status("مود المزاد جاهز ✓ — أضغط «العب»");
                clickBestPlayButton(root);
                return;
            }

            if (isGamesHub(all)) {
                status("صفحة الألعاب ✓ — أفتح «المزاد» فقط");
                clickTextAny(root, "المزاد", "auction");
                return;
            }

            if (containsAny(all, "العب الان", "العب الآن", "العب ماتش مع امبابي", "العب ماتش مع إمبابي", "play now") &&
                    !containsAny(all, "تأكيد المزايدة", "تاكيد المزايده", "انتظار المزايدة")) {
                status("الصفحة الرئيسية ✓ — أضغط «العب الآن»");
                clickTextAny(root, "العب الان", "العب الآن", "العب ماتش مع امبابي", "العب ماتش مع إمبابي", "play now");
                return;
            }
        }

        // ---------- Auction turn ----------
        // In the new GUI these labels are the primary truth:
        // تأكيد المزايدة = OUR TURN
        // انتظار المزايدة = OPPONENT TURN
        boolean waitingBidText = containsAny(all,
                "انتظار المزايدة", "انتظار المزايده",
                "بانتظار المزايدة", "بانتظار المزايده",
                "waiting bid", "waiting for bid");

        boolean confirmBidText = containsAny(all,
                "تأكيد المزايدة", "تاكيد المزايده",
                "تأكيد مزايدة", "تاكيد مزايدة",
                "confirm bid");

        boolean opponentTurnText = containsAny(all,
                "دور الخصم", "الدور خصمك", "الدور: خصمك",
                "خصمك يزايد", "opponent turn", "opponent's turn");

        boolean myTurnText = containsAny(all,
                "دورك", "دورك الان", "دورك الآن",
                "الدور انت", "الدور: انت", "your turn");

        if (false && ((waitingBidText && !confirmBidText && !myTurnText) ||
                (opponentTurnText && !confirmBidText && !myTurnText))) {
            if (awaitingConfirm) {
                cancelBidFlow();
                paused = true;
                Prefs.setBotPaused(this, true);
                status("ظهر «انتظار المزايدة» قبل تأكيدنا — SAFETY PAUSE");
                return;
            }

            mustSeeWaitingBeforeNextBid = false;
            status("«انتظار المزايدة» = دور الخصم — أراقب السعر فقط");

            // IMPORTANT: still read the numbers while waiting.
            // This lets the engine verify OUR previous bid and then learn the opponent's next bid.
            if (activeAuction) readAuctionSnapshot(false);
            return;
        }

        if (false && (confirmBidText || myTurnText)) {
            if (awaitingConfirm) {
                status("بعد + ما زال «تأكيد المزايدة» ظاهر ✓ — أضغط Confirm");
                verifyConfirmReady(root);
                return;
            }

            if (mustSeeWaitingBeforeNextBid) {
                status("أكدت المزايدة ✓ — أنتظر «انتظار المزايدة» قبل أي مزايدة جديدة");
                return;
            }

            if (activeAuction) {
                status("«تأكيد المزايدة» = دورنا ✓ — أقرأ اللاعب والسعر");
                readAuctionSnapshot(true);
                return;
            }
        }

        if (activeAuction) {
            // Accessibility tree may occasionally hide the button text in the WebView.
            // OCR only the calibrated Confirm-button area; never guess from a random pixel.
            verifyTurnLabelByOcr();
            return;
        }

        detectAuctionTurnVisually();
    }

    private Integer extractGuiRound(String all) {
        if (all == null || all.isEmpty()) return null;
        String western = toWesternDigits(all);
        Matcher m = Pattern.compile("(?<!\\d)([1-5])\\s*/\\s*5(?!\\d)").matcher(western);
        if (!m.find()) return null;
        try {
            return Integer.parseInt(m.group(1));
        } catch (Exception ignored) {
            return null;
        }
    }

    private NumericHints parseNumericHints(String raw) {
        NumericHints h = new NumericHints();
        if (raw == null) return h;

        String text = normalize(toWesternDigits(raw));

        h.rating = findNumberNearAny(text, 50, 99,
                "التقييم", "تقييم اللاعب", "rating", "ovr");

        h.price = findNumberNearAny(text, 0, 100,
                "المزايده الحاليه", "المزايدة الحالية",
                "السعر الحالي", "سعر المزايده", "سعر المزايدة",
                "مزايدتك", "current bid", "current price", "bid price");

        h.mine = findNumberNearAny(text, 0, 100,
                "ميزانيتك", "ميزانيه اللاعب", "ميزانية اللاعب",
                "رصيدك", "فلوسك", "your budget", "my budget");

        h.opp = findNumberNearAny(text, 0, 100,
                "ميزانيه الخصم", "ميزانية الخصم",
                "ميزانيه خصمك", "ميزانية خصمك",
                "رصيد الخصم", "opponent budget", "opp budget");

        return h;
    }

    private Integer findNumberNearAny(String text, int min, int max, String... keys) {
        if (text == null || text.isEmpty()) return null;

        for (String key : keys) {
            String k = normalize(key);
            Pattern after = Pattern.compile(Pattern.quote(k) +
                    "[^0-9]{0,28}(100|[0-9]{1,2})(?![0-9])");
            Matcher ma = after.matcher(text);
            while (ma.find()) {
                try {
                    int v = Integer.parseInt(ma.group(1));
                    if (v >= min && v <= max) return v;
                } catch (Exception ignored) {}
            }

            Pattern before = Pattern.compile("(?<![0-9])(100|[0-9]{1,2})" +
                    "[^0-9]{0,18}" + Pattern.quote(k));
            Matcher mb = before.matcher(text);
            while (mb.find()) {
                try {
                    int v = Integer.parseInt(mb.group(1));
                    if (v >= min && v <= max) return v;
                } catch (Exception ignored) {}
            }
        }

        return null;
    }

    private boolean isTargetPackage(AccessibilityNodeInfo root) {
        CharSequence p = root.getPackageName();
        if (p == null) return false;
        String pkg = p.toString();
        if (getPackageName().equals(pkg)) return false;
        if (pkg.contains("systemui") || pkg.contains("launcher")) return false;
        if (targetPackage == null) targetPackage = pkg;
        return pkg.equals(targetPackage);
    }

    private boolean isAuctionActiveScreen(String all) {
        boolean turn = containsAny(all, "دورك", "دور الخصم", "خصمك", "your turn", "opponent turn");
        boolean controls = containsAny(all,
                "confirm", "تأكيد", "تاكيد",
                "زايد", "مزايده", "مزايدة",
                "انتظار المزايدة", "انتظار المزايده",
                "waiting bid", "waiting for bid");
        boolean price = containsAny(all, "السعر", "price", "bid", "مزايدتك");
        return turn || controls || price;
    }

    private boolean isAuctionResultScreen(String all) {
        if (containsAny(all,
                "نتيجة المزاد", "نتيجه المزاد", "فاز بالمزاد", "كسب المزاد",
                "اللاعب المجاني", "لاعب مجاني", "free player", "won the auction")) return true;
        return containsAny(all, "الفائز", "winner") && containsAny(all, "مجاني", "free");
    }

    private boolean isPostMatchScreen(String all) {
        return containsAny(all,
                "بدء المحاكاه", "بدء المحاكاة",
                "ابدأ المحاكاه", "ابدأ المحاكاة",
                "محاكاه المباراه", "محاكاة المباراة",
                "simulate match", "start simulation",
                "نهاية المباراه", "نهاية المباراة",
                "انتهت المباراه", "انتهت المباراة",
                "النتيجه النهائيه", "النتيجة النهائية",
                "تقدم المباراه", "تقدم المباراة",
                "لحظات مباشره", "لحظات مباشرة",
                "ملخص المباراه", "ملخص المباراة",
                "رجل المباراه", "رجل المباراة",
                "الاهداف والكروت", "الأهداف والكروت",
                "عرض التشكيلات", "عرض النتائج",
                "مشاهده التشكيلات", "مشاهدة التشكيلات",
                "view lineups", "view results", "match summary",
                "خسارة", "فوز", "خرجت من المباراة", "فاز خصمك بالانسحاب",
                "العودة للرئيسية", "العوده للرئيسيه");
    }

    private boolean isGamesHub(String all) {
        boolean title = containsAny(all, "الالعاب", "الألعاب", "games");
        boolean cards = containsAny(all, "المزاد", "auction") &&
                containsAny(all, "اللاعب الخفي", "hidden player", "العاب", "ألعاب");
        return title || cards;
    }

    private void handlePostMatch(AccessibilityNodeInfo root, String all) {
        // The recorded game is a WebView. ACTION_CLICK can report success on a
        // text node while the game does nothing. Use rendered OCR coordinates only.
        scanPostMatchByOcr();
    }

    private void clickRenderedTextOnce(Runnable onSuccess, String... targets) {
        if (screenshotBusy || screenReadBusy) return;

        captureBitmap(bmp -> {
            if (bmp == null) {
                status("تعذر Screenshot — بدون كليك");
                return;
            }

            recognizer.process(InputImage.fromBitmap(bmp, 0))
                    .addOnSuccessListener(tx -> {
                        Rect hit = findOcrTextRect(tx, targets);
                        bmp.recycle();

                        if (hit == null) {
                            status("النص ظاهر لكن OCR لم يحدد مكانه — سأعيد المحاولة");
                            queueScan(ACTION_DEBOUNCE_MS);
                            return;
                        }

                        dispatchTapPx(hit.centerX(), hit.centerY(),
                                () -> {
                                    if (onSuccess != null) onSuccess.run();
                                    queueScan(ACTION_DEBOUNCE_MS + 120);
                                },
                                () -> {
                                    status("Gesture اتلغى — سأعيد المحاولة");
                                    queueScan(ACTION_DEBOUNCE_MS);
                                });
                    })
                    .addOnFailureListener(e -> {
                        bmp.recycle();
                        status("OCR فشل — سأعيد المحاولة");
                        queueScan(ACTION_DEBOUNCE_MS);
                    });
        });
    }

    private void scanPostMatchByOcr() {
        if (screenshotBusy || screenReadBusy) return;

        captureBitmap(bmp -> {
            if (bmp == null) {
                status("تعذر Screenshot بعد الجيم — بدون كليك");
                return;
            }

            recognizer.process(InputImage.fromBitmap(bmp, 0))
                    .addOnSuccessListener(tx -> {
                        Rect hit = null;

                        // 1) Coaches -> View Lineups.
                        if (postMatchStage == 1) {
                            hit = findOcrTextRect(tx,
                                    "عرض التشكيلات", "مشاهدة التشكيلات",
                                    "view lineups", "lineups");

                            if (hit != null) {
                                postMatchStage = 2;
                                postMatchSwipeAttempts = 0;
                                Rect target = hit;
                                bmp.recycle();
                                status("OCR: لقيت «عرض التشكيلات» ✓ — أضغطه");
                                dispatchTapPx(target.centerX(), target.centerY(),
                                        () -> queueScan(ACTION_DEBOUNCE_MS + 120),
                                        () -> {
                                            postMatchStage = 1;
                                            status("فشل ضغط عرض التشكيلات — سأعيد البحث");
                                        });
                                return;
                            }

                            hit = findOcrTextRect(tx,
                                    "بدء المحاكاه", "بدء المحاكاة",
                                    "ابدأ المحاكاه", "ابدأ المحاكاة",
                                    "start simulation", "simulate match");

                            if (hit != null) {
                                postMatchStage = 3;
                                Rect target = hit;
                                bmp.recycle();
                                status("OCR: التشكيلات النهائية ظاهرة ✓ — أضغط بدء المحاكاة");
                                dispatchTapPx(target.centerX(), target.centerY(),
                                        () -> queueScan(ACTION_DEBOUNCE_MS + 120),
                                        () -> {
                                            postMatchStage = 2;
                                            status("فشل ضغط بدء المحاكاة — سأعيد البحث");
                                        });
                                return;
                            }

                            bmp.recycle();
                            status("أنتظر ظهور «عرض التشكيلات» — بدون Scroll عشوائي");
                            return;
                        }

                        // 2) Final lineups -> Start Simulation.
                        if (postMatchStage == 2) {
                            hit = findOcrTextRect(tx,
                                    "بدء المحاكاه", "بدء المحاكاة",
                                    "ابدأ المحاكاه", "ابدأ المحاكاة",
                                    "start simulation", "simulate match");

                            if (hit != null) {
                                postMatchStage = 3;
                                postMatchSwipeAttempts = 0;
                                Rect target = hit;
                                bmp.recycle();
                                status("OCR: لقيت «بدء المحاكاة» ✓ — أضغطه");
                                dispatchTapPx(target.centerX(), target.centerY(),
                                        () -> queueScan(ACTION_DEBOUNCE_MS + 120),
                                        () -> {
                                            postMatchStage = 2;
                                            status("فشل ضغط بدء المحاكاة — سأعيد المحاولة");
                                        });
                                return;
                            }

                            // If the old View Lineups screen is still there, retry it.
                            hit = findOcrTextRect(tx,
                                    "عرض التشكيلات", "مشاهدة التشكيلات",
                                    "view lineups", "lineups");
                            if (hit != null) {
                                Rect target = hit;
                                bmp.recycle();
                                status("«عرض التشكيلات» ما زال ظاهر — أعيد الضغط");
                                dispatchTapPx(target.centerX(), target.centerY(),
                                        () -> queueScan(ACTION_DEBOUNCE_MS + 120),
                                        () -> status("فشل إعادة ضغط عرض التشكيلات"));
                                return;
                            }

                            bmp.recycle();
                            status("أنتظر ثانية لظهور «بدء المحاكاة»");
                            return;
                        }

                        // 3) Simulation: absolutely no scrolling. Wait for View Results.
                        if (postMatchStage == 3) {
                            hit = findOcrTextRect(tx,
                                    "عرض النتائج", "عرض النتيجه", "عرض النتيجة",
                                    "view results", "results");

                            if (hit != null) {
                                postMatchStage = 4;
                                postMatchSwipeAttempts = 0;
                                Rect target = hit;
                                bmp.recycle();
                                status("OCR: المحاكاة انتهت ✓ — أضغط «عرض النتائج»");
                                dispatchTapPx(target.centerX(), target.centerY(),
                                        () -> queueScan(ACTION_DEBOUNCE_MS + 120),
                                        () -> {
                                            postMatchStage = 3;
                                            status("فشل ضغط عرض النتائج — سأعيد المحاولة");
                                        });
                                return;
                            }

                            bmp.recycle();
                            status("المحاكاة جارية — أنتظر «عرض النتائج»");
                            return;
                        }

                        // 4) Summary: now scrolling is correct.
                        if (postMatchStage == 4) {
                            hit = findOcrTextRect(tx,
                                    "العودة للرئيسية", "العوده للرئيسيه",
                                    "العودة للصفحة الرئيسية", "العوده للصفحه الرئيسيه",
                                    "القائمة الرئيسية", "القائمه الرئيسيه",
                                    "return to main", "return home", "main menu");

                            if (hit != null) {
                                postMatchStage = 5;
                                postMatchSwipeAttempts = 0;
                                returnToMainClickedAt = System.currentTimeMillis();
                                onMatchCompleted();
                                Rect target = hit;
                                bmp.recycle();
                                status("OCR: لقيت «العودة للرئيسية» ✓ — أضغطها");
                                dispatchTapPx(target.centerX(), target.centerY(),
                                        () -> queueScan(ACTION_DEBOUNCE_MS + 120),
                                        () -> {
                                            postMatchStage = 4;
                                            status("فشل ضغط العودة للرئيسية — سأعيد البحث");
                                        });
                                return;
                            }

                            bmp.recycle();
                            postMatchSwipeAttempts++;

                            if (postMatchSwipeAttempts > 45) {
                                paused = true;
                                Prefs.setBotPaused(AuctionAccessibilityService.this, true);
                                status("لم أجد العودة للرئيسية بعد 45 Scroll — PAUSE");
                            } else {
                                status("ملخص المباراة — Scroll لتحت #" + postMatchSwipeAttempts);
                                swipePageDown();
                            }
                            return;
                        }

                        // 5/6) Return path back to actual Home.
                        if (postMatchStage == 5 || postMatchStage == 6) {
                            hit = findOcrTextRect(tx,
                                    "العب الآن", "العب الان", "play now");

                            if (hit != null) {
                                Rect target = hit;
                                bmp.recycle();

                                if (stopAfterReturn) {
                                    postMatchStage = 0;
                                    stopBot("اكتملت دورة التكرار ✓ — رجعنا للرئيسية");
                                } else {
                                    engine.resetSession();
                                    clearSessionScreenState();
                                    Prefs.setCurrentRound(AuctionAccessibilityService.this, 1);
                                    postMatchStage = 0;
                                    status("الرئيسية جاهزة ✓ — أبدأ لوب مزاد جديدة");
                                    dispatchTapPx(target.centerX(), target.centerY(),
                                            () -> queueScan(ACTION_DEBOUNCE_MS + 120),
                                            () -> status("فشل الضغط على العب الآن"));
                                }
                                return;
                            }

                            Rect games = findOcrTextRect(tx,
                                    "المزاد", "auction", "الألعاب", "الالعاب", "games");

                            if (games != null && postMatchStage == 5) {
                                bmp.recycle();
                                status("OCR: صفحة الألعاب ✓ — أضغط السهم الرمادي");
                                tapGrayGamesArrow();
                                return;
                            }

                            bmp.recycle();
                            status("أنتظر الصفحة التالية…");
                            return;
                        }

                        bmp.recycle();
                    })
                    .addOnFailureListener(e -> {
                        bmp.recycle();
                        status("OCR شاشة نهاية الجيم فشل — بدون كليك");
                    });
        });
    }

    private boolean ocrRectHitsOverlay(Rect r) {
        if (r == null || floatingView == null || floatingLp == null ||
                floatingView.getWidth() <= 0 || floatingView.getHeight() <= 0) {
            return false;
        }
        Rect ov = new Rect(
                floatingLp.x,
                floatingLp.y,
                floatingLp.x + floatingView.getWidth(),
                floatingLp.y + floatingView.getHeight()
        );
        return Rect.intersects(ov, r);
    }

    private String ocrTextWithoutOverlay(Text tx) {
        if (tx == null) return "";
        StringBuilder sb = new StringBuilder();
        for (Text.TextBlock block : tx.getTextBlocks()) {
            for (Text.Line line : block.getLines()) {
                Rect r = line.getBoundingBox();
                if (r != null && ocrRectHitsOverlay(r)) continue;
                sb.append(' ').append(line.getText());
            }
        }
        return normalize(sb.toString());
    }

    private Rect findOcrTextRect(Text tx, String... targets) {
        if (tx == null) return null;

        for (Text.TextBlock block : tx.getTextBlocks()) {
            for (Text.Line line : block.getLines()) {
                String lineText = normalize(line.getText());

                for (String target : targets) {
                    if (lineText.contains(normalize(target))) {
                        Rect r = line.getBoundingBox();
                        if (r != null && !r.isEmpty() && !ocrRectHitsOverlay(r)) return new Rect(r);
                    }
                }

                for (Text.Element el : line.getElements()) {
                    String elementText = normalize(el.getText());
                    for (String target : targets) {
                        if (elementText.contains(normalize(target))) {
                            Rect r = el.getBoundingBox();
                            if (r != null && !r.isEmpty() && !ocrRectHitsOverlay(r)) return new Rect(r);
                        }
                    }
                }
            }
        }
        return null;
    }

    private void scrollPostMatchFor(String target, int maxAttempts) {
        postMatchSwipeAttempts++;

        if (postMatchSwipeAttempts > maxAttempts) {
            paused = true;
            Prefs.setBotPaused(this, true);
            status("نزلت وبحثت " + maxAttempts + " مرة ومش لاقي «" + target + "» — SAFETY PAUSE");
            return;
        }

        status("الكلمة المطلوبة مش ظاهرة — أنزل لتحت وأدور على «" +
                target + "» #" + postMatchSwipeAttempts);
        swipePageDown();
    }

    private void finishReturnCycle(AccessibilityNodeInfo root) {
        if (stopAfterReturn) {
            postMatchStage = 0;
            stopBot("اكتملت دورة التكرار ✓ — رجعنا للرئيسية");
            return;
        }

        engine.resetSession();
        clearSessionScreenState();
        Prefs.setCurrentRound(this, 1);
        postMatchStage = 0;
        status("الرئيسية جاهزة ✓ — أبدأ لوب مزاد جديدة");
        if (clickTextAny(root, "العب الان", "العب الآن", "العب ماتش مع امبابي", "العب ماتش مع إمبابي", "play now")) {
            actionCooldownUntil = System.currentTimeMillis() + ACTION_DEBOUNCE_MS;
            queueScan(ACTION_DEBOUNCE_MS + 120);
        }
    }

    private void onMatchCompleted() {
        matchesCompleted++;
        Prefs.setMatchesCompleted(this, matchesCompleted);

        String mode = Prefs.repeatMode(this);
        if ("count".equals(mode) && matchesCompleted >= Prefs.repeatCount(this)) stopAfterReturn = true;
        if ("time".equals(mode) && System.currentTimeMillis() - startedAt >= Prefs.repeatMinutes(this) * 60_000L) stopAfterReturn = true;

        refreshOverlay();
        status("انتهت الجيم #" + matchesCompleted +
                (stopAfterReturn ? " — سأرجع للرئيسية ثم أتوقف" : " — سأرجع وأبدأ جيم جديدة"));
    }

    // ---------- OCR / state reading ----------

    private static class SpatialNumbers {
        Integer rating;
        Integer price;
        Integer mine;
        Integer opp;
    }

    private Integer numberFromText(String raw, int min, int max) {
        if (raw == null) return null;
        String cleaned = cleanOcrDigits(raw);
        Matcher m = Pattern.compile("(?<!\\d)(100|[0-9]{1,2})(?!\\d)").matcher(cleaned);
        while (m.find()) {
            try {
                int v = Integer.parseInt(m.group(1));
                if (v >= min && v <= max) return v;
            } catch (Exception ignored) {}
        }
        return null;
    }

    private SpatialNumbers extractSpatialAuctionNumbers(Text tx, int w, int h) {
        SpatialNumbers out = new SpatialNumbers();
        if (tx == null || w <= 0 || h <= 0) return out;

        float bestRating = Float.MAX_VALUE;
        float bestPrice = Float.MAX_VALUE;
        float bestMine = Float.MAX_VALUE;
        float bestOpp = Float.MAX_VALUE;

        for (Text.TextBlock block : tx.getTextBlocks()) {
            for (Text.Line line : block.getLines()) {
                ArrayList<Rect> rects = new ArrayList<>();
                ArrayList<String> texts = new ArrayList<>();

                Rect lr = line.getBoundingBox();
                if (lr != null && !lr.isEmpty()) {
                    rects.add(lr);
                    texts.add(line.getText());
                }

                for (Text.Element el : line.getElements()) {
                    Rect er = el.getBoundingBox();
                    if (er != null && !er.isEmpty()) {
                        rects.add(er);
                        texts.add(el.getText());
                    }
                }

                for (int k = 0; k < rects.size(); k++) {
                    Rect r = rects.get(k);
                    if (ocrRectHitsOverlay(r)) continue;

                    String txt = texts.get(k);
                    Integer num = numberFromText(txt, 0, 100);
                    if (num == null) continue;

                    float nx = r.centerX() / (float) w;
                    float ny = r.centerY() / (float) h;
                    float nh = r.height() / (float) h;

                    if (num >= 80 && num <= 99 &&
                            nx >= .10f && nx <= .43f &&
                            ny >= .28f && ny <= .66f) {
                        float distance = Math.abs(nx - .285f) + Math.abs(ny - .425f);
                        float score = distance - nh * 3.2f;
                        if (score < bestRating) {
                            bestRating = score;
                            out.rating = num;
                        }
                    }

                    if (num >= 1 && num <= 100 &&
                            nx >= .28f && nx <= .72f &&
                            ny >= .70f && ny <= .90f) {
                        float distance = Math.abs(nx - .50f) + Math.abs(ny - .815f);
                        String nt = normalize(txt);
                        float moneyBonus = containsAny(nt, "m", "م", "€") ? .05f : 0f;
                        float score = distance - nh * 2.2f - moneyBonus;
                        if (score < bestPrice) {
                            bestPrice = score;
                            out.price = num;
                        }
                    }

                    if (num >= 1 && num <= 100 &&
                            ny >= .135f && ny <= .285f) {
                        if (nx >= .06f && nx <= .46f) {
                            float distance = Math.abs(nx - .275f) + Math.abs(ny - .205f);
                            float score = distance - nh * 1.4f;
                            if (score < bestMine) {
                                bestMine = score;
                                out.mine = num;
                            }
                        } else if (nx >= .54f && nx <= .94f) {
                            float distance = Math.abs(nx - .725f) + Math.abs(ny - .205f);
                            float score = distance - nh * 1.4f;
                            if (score < bestOpp) {
                                bestOpp = score;
                                out.opp = num;
                            }
                        }
                    }
                }
            }
        }

        return out;
    }

    private int detectBottomAuctionTurn(Text tx, int w, int h) {
        if (tx == null || w <= 0 || h <= 0) return 0;

        boolean sawConfirm = false;
        boolean sawWaiting = false;

        for (Text.TextBlock block : tx.getTextBlocks()) {
            for (Text.Line line : block.getLines()) {
                Rect r = line.getBoundingBox();
                if (r == null || r.isEmpty() || ocrRectHitsOverlay(r)) continue;

                float ny = r.centerY() / (float) h;
                if (ny < .76f) continue;

                String t = normalize(line.getText());

                if (containsAny(t,
                        "تأكيد المزايدة", "تاكيد المزايده",
                        "تأكيد مزايدة", "تاكيد مزايدة",
                        "confirm bid", "confirm")) {
                    sawConfirm = true;
                }

                if (containsAny(t,
                        "انتظار المزايدة", "انتظار المزايده",
                        "بانتظار المزايدة", "بانتظار المزايده",
                        "waiting bid", "waiting for bid")) {
                    sawWaiting = true;
                }
            }
        }

        if (sawConfirm) return 1;
        if (sawWaiting) return 2;
        return 0;
    }


    private void readAuctionSnapshot(boolean allowAction) {
        if (screenReadBusy || bidFlowInProgress) return;

        if (!Prefs.isCalibrated(this)) {
            paused = true;
            Prefs.setBotPaused(this, true);
            status("اعمل CAL للـ + و Confirm مرة واحدة");
            return;
        }

        AccessibilityNodeInfo root = getRootInActiveWindow();
        String rawTree = root == null ? "" : collectText(root);
        NumericHints hints = parseNumericHints(rawTree);

        screenReadBusy = true;

        captureBitmap(bmp -> {
            if (bmp == null) {
                screenReadBusy = false;
                Integer rating = hints.rating;
                Integer price = hints.price;
                handleSnapshot(rating, price, hints.mine, hints.opp, allowAction);
                return;
            }

            final Integer[] vals = new Integer[4];
            final int[] done = {0};

            IntResult[] sinks = new IntResult[4];
            for (int i = 0; i < 4; i++) {
                final int idx = i;
                sinks[i] = value -> {
                    vals[idx] = value;
                    done[0]++;

                    if (done[0] == 4) {
                        bmp.recycle();
                        screenReadBusy = false;

                        Integer rating = vals[0] != null ? vals[0] : hints.rating;

                        Integer price = vals[1] != null && vals[1] > 0
                                ? vals[1]
                                : (hints.price != null && hints.price > 0 ? hints.price : null);

                        Integer mine = vals[2] != null && vals[2] > 0
                                ? vals[2]
                                : (hints.mine != null && hints.mine > 0 ? hints.mine : null);

                        Integer opp = vals[3] != null && vals[3] > 0
                                ? vals[3]
                                : (hints.opp != null && hints.opp > 0 ? hints.opp : null);

                        status("OCR: OVR " + show(rating) +
                                " | Price " + show(price) +
                                " | You " + show(mine) +
                                " | Opp " + show(opp));

                        handleSnapshot(rating, price, mine, opp, allowAction);
                    }
                };
            }

            ocrRect(bmp, Prefs.getRegion(this, "rating"), true, sinks[0]);
            ocrRect(bmp, Prefs.getRegion(this, "price"), false, sinks[1]);
            ocrRect(bmp, Prefs.getRegion(this, "mine"), false, sinks[2]);
            ocrRect(bmp, Prefs.getRegion(this, "opponent"), false, sinks[3]);
        });
    }


    private void handleSnapshot(Integer rating, Integer price, Integer mine, Integer opp,
                                boolean allowAction) {
        // Rating + current price are mandatory. Budgets can safely fall back
        // to the last valid reading (or 100/100 at the beginning of a fresh match).
        if (mine == null || mine < 0 || mine > 100) {
            mine = (lastMine != null && lastMine >= 0 && lastMine <= 100)
                    ? lastMine
                    : 100;
        }

        if (opp == null || opp < 0 || opp > 100) {
            opp = (lastOpp != null && lastOpp >= 0 && lastOpp <= 100)
                    ? lastOpp
                    : 100;
        }

        boolean valid = rating != null && rating >= 80 && rating <= 99 &&
                price != null && price >= 1 && price <= 100 &&
                mine != null && mine >= 0 && mine <= 100 &&
                opp != null && opp >= 0 && opp <= 100;

        if (!valid) {
            stableCandidate = null;
            stableCandidateCount = 0;
            status("OCR غير مؤكد/مشكوك فيه: OVR " + show(rating) +
                    " | Price " + show(price) +
                    " | You " + show(mine) +
                    " | Opp " + show(opp) +
                    " — لا ألمس الشاشة");
            return;
        }

        AuctionEngine.Snapshot snap =
                new AuctionEngine.Snapshot(rating, price, mine, opp, true);

        if (stableCandidate != null && snap.sameValues(stableCandidate)) {
            stableCandidateCount++;
        } else {
            stableCandidate = snap;
            stableCandidateCount = 1;
        }

        lastRating = rating;
        lastPrice = price;
        lastMine = mine;
        lastOpp = opp;
        refreshOverlay();

        // On OUR turn, the explicit "تأكيد المزايدة" label already confirms the state,
        // so one valid numeric read is enough. Requiring two identical OCR frames was
        // causing the bot to sit still while the live timer kept moving.
        int requiredReads = allowAction ? 1 : 2;

        if (stableCandidateCount < requiredReads) {
            status((allowAction ? "دوري" : "مراقبة") +
                    " • قراءة " + stableCandidateCount + "/" + requiredReads +
                    ": OVR " + rating +
                    " | " + price + "M | Y" + mine + " | O" + opp);
            return;
        }

        stableCandidate = null;
        stableCandidateCount = 0;

        AuctionEngine.Decision d = engine.onStableSnapshot(snap);
        if (d.action == AuctionEngine.Action.PASS && rating < d.minRating) {
            lastDecision = "PASS " + rating + "<" + d.minRating;
        } else if (d.action == AuctionEngine.Action.BID) {
            lastDecision = "BID +1";
        } else {
            lastDecision = d.action.name();
        }
        Prefs.setCurrentRound(this, engine.getRound());
        Prefs.saveLastSnapshot(this, rating, price, mine, opp, lastDecision);

        String slot = AuctionEngine.SLOT_NAMES[Math.max(0, Math.min(4, d.round - 1))];
        String base = "R" + d.round + "/5 " + slot +
                " • OVR " + rating +
                " • " + price + "M" +
                " • Y" + mine +
                " • O" + opp +
                " • " + d.opponentStyle;

        if (!allowAction) {
            status(base + " • OBSERVE");
            refreshOverlay();
            return;
        }

        if (d.action == AuctionEngine.Action.BID) {
            status(base + " • BID +1 • cap " + d.hardCap + "M");
            executeBid(snap);
        } else if (d.action == AuctionEngine.Action.PASS) {
            status(base + " • PASS • " + d.reason + " • سأضغط تخطي اللاعب");
            lastDecision = rating < d.minRating
                    ? "SKIP " + rating + "<" + d.minRating
                    : "SKIP";
            refreshOverlay();
            scheduleSkipPlayer();
        } else if (d.action == AuctionEngine.Action.SAFETY_PAUSE) {
            paused = true;
            Prefs.setBotPaused(this, true);
            status(base + " • SAFETY PAUSE");
        } else if (d.action == AuctionEngine.Action.COMPLETE) {
            status("خلصت 5 مزادات ✓ — أراقب نهاية الجيم");
        } else {
            status(base + " • WAIT");
        }

        refreshOverlay();
    }

    private void ocrRect(Bitmap base, RectF n, boolean ratingMode, IntResult cb) {
        if (base == null || n == null) {
            cb.accept(null);
            return;
        }

        int l = Math.max(0, Math.min(base.getWidth() - 1, Math.round(n.left * base.getWidth())));
        int t = Math.max(0, Math.min(base.getHeight() - 1, Math.round(n.top * base.getHeight())));
        int r = Math.max(l + 1, Math.min(base.getWidth(), Math.round(n.right * base.getWidth())));
        int b = Math.max(t + 1, Math.min(base.getHeight(), Math.round(n.bottom * base.getHeight())));

        Bitmap crop;
        try {
            crop = Bitmap.createBitmap(base, l, t, r - l, b - t);
        } catch (Exception e) {
            cb.accept(null);
            return;
        }

        int scale = ratingMode ? 5 : 4;
        Bitmap enlarged;
        try {
            enlarged = Bitmap.createScaledBitmap(
                    crop,
                    Math.max(1, crop.getWidth() * scale),
                    Math.max(1, crop.getHeight() * scale),
                    true
            );
        } catch (Exception e) {
            crop.recycle();
            cb.accept(null);
            return;
        }
        crop.recycle();

        recognizer.process(InputImage.fromBitmap(enlarged, 0))
                .addOnSuccessListener(tx -> {
                    Integer first = ratingMode ? extractRating(tx.getText()) : extractMoney(tx.getText());

                    if (first != null && (ratingMode || first > 0)) {
                        enlarged.recycle();
                        cb.accept(first);
                        return;
                    }

                    Bitmap hi = thresholdForOcr(enlarged, false);
                    Bitmap inv = thresholdForOcr(enlarged, true);
                    enlarged.recycle();

                    if (hi == null) {
                        if (inv != null) {
                            recognizeOne(inv, ratingMode, cb);
                        } else {
                            cb.accept(first);
                        }
                        return;
                    }

                    recognizer.process(InputImage.fromBitmap(hi, 0))
                            .addOnSuccessListener(tx2 -> {
                                Integer second = ratingMode ? extractRating(tx2.getText()) : extractMoney(tx2.getText());
                                hi.recycle();

                                if (second != null && (ratingMode || second > 0)) {
                                    if (inv != null) inv.recycle();
                                    cb.accept(second);
                                    return;
                                }

                                if (inv == null) {
                                    cb.accept(second != null ? second : first);
                                    return;
                                }

                                recognizeOne(inv, ratingMode, value -> {
                                    if (value != null && (ratingMode || value > 0)) cb.accept(value);
                                    else if (second != null) cb.accept(second);
                                    else cb.accept(first);
                                });
                            })
                            .addOnFailureListener(e2 -> {
                                hi.recycle();
                                if (inv != null) recognizeOne(inv, ratingMode, cb);
                                else cb.accept(first);
                            });
                })
                .addOnFailureListener(e -> {
                    Bitmap hi = thresholdForOcr(enlarged, false);
                    enlarged.recycle();
                    if (hi != null) recognizeOne(hi, ratingMode, cb);
                    else cb.accept(null);
                });
    }

    private void recognizeOne(Bitmap img, boolean ratingMode, IntResult cb) {
        recognizer.process(InputImage.fromBitmap(img, 0))
                .addOnSuccessListener(tx -> {
                    Integer v = ratingMode ? extractRating(tx.getText()) : extractMoney(tx.getText());
                    img.recycle();
                    cb.accept(v);
                })
                .addOnFailureListener(e -> {
                    img.recycle();
                    cb.accept(null);
                });
    }

    private Bitmap thresholdForOcr(Bitmap src, boolean inverse) {
        try {
            int w = src.getWidth(), h = src.getHeight();
            int[] px = new int[w * h];
            src.getPixels(px, 0, w, 0, 0, w, h);

            long sum = 0;
            for (int c : px) {
                int y = (Color.red(c) * 299 + Color.green(c) * 587 + Color.blue(c) * 114) / 1000;
                sum += y;
            }

            int avg = px.length == 0 ? 140 : (int)(sum / px.length);
            int threshold = Math.max(90, Math.min(190, avg + 8));

            for (int i = 0; i < px.length; i++) {
                int c = px[i];
                int y = (Color.red(c) * 299 + Color.green(c) * 587 + Color.blue(c) * 114) / 1000;
                boolean bright = y >= threshold;
                if (inverse) bright = !bright;
                px[i] = bright ? Color.WHITE : Color.BLACK;
            }

            Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            out.setPixels(px, 0, w, 0, 0, w, h);
            return out;
        } catch (Exception ignored) {
            return null;
        }
    }


    private String show(Integer v) { return v == null ? "—" : String.valueOf(v); }

    private Integer extractRating(String raw) {
        if (raw == null) return null;
        String s = cleanOcrDigits(raw);
        Matcher m = Pattern.compile("(?<!\\d)([5-9]\\d)(?!\\d)").matcher(s);
        while (m.find()) {
            try {
                int v = Integer.parseInt(m.group(1));
                if (v >= 50 && v <= 99) return v;
            } catch (Exception ignored) {}
        }
        return null;
    }

    private Integer extractMoney(String raw) {
        if (raw == null) return null;
        String s = cleanOcrDigits(raw);
        Matcher m = Pattern.compile("(?<!\\d)(100|[0-9]{1,2})(?!\\d)").matcher(s);
        while (m.find()) {
            try {
                int v = Integer.parseInt(m.group(1));
                if (v >= 0 && v <= 100) return v;
            } catch (Exception ignored) {}
        }
        return null;
    }

    private String cleanOcrDigits(String raw) {
        String s = toWesternDigits(raw.toUpperCase(Locale.US));
        return s.replace('O','0').replace('S','5').replace('B','8').replace('I','1').replace('L','1');
    }

    private String toWesternDigits(String s) {
        if (s == null) return "";
        StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= '٠' && c <= '٩') b.append((char) ('0' + (c - '٠')));
            else if (c >= '۰' && c <= '۹') b.append((char) ('0' + (c - '۰')));
            else b.append(c);
        }
        return b.toString();
    }

    // ---------- + then Confirm: verified two-step flow ----------

    private void scheduleSkipPlayer() {
        if (bidFlowInProgress || awaitingConfirm) return;

        bidFlowInProgress = true;
        lastDecision = "SKIP…";
        refreshOverlay();

        long wait = Math.max(0L, actionCooldownUntil - System.currentTimeMillis());
        wait = Math.max(wait, 1000L);

        h.postDelayed(() -> {
            if (!running || paused) {
                bidFlowInProgress = false;
                return;
            }
            findAndClickSkipPlayer();
        }, wait);
    }

    private void findAndClickSkipPlayer() {
        captureBitmap(bmp -> {
            if (bmp == null) {
                bidFlowInProgress = false;
                status("PASS لكن Screenshot فشل — لم أضغط شيئًا");
                return;
            }

            recognizer.process(InputImage.fromBitmap(bmp, 0))
                    .addOnSuccessListener(tx -> {
                        Rect hit = findOcrTextRect(tx,
                                "تخطي اللاعب", "تخطى اللاعب",
                                "تخطي", "skip player", "skip");

                        if (hit == null) {
                            bmp.recycle();
                            lastDecision = "SKIP→";
                            refreshOverlay();
                            status("PASS ✓ — OCR لم يقرأ زر التخطي، أستخدم مكانه الثابت");
                            tapSavedPoint("skip", ok -> {
                                bidFlowInProgress = false;
                                if (ok) {
                                    lastDecision = "SKIP✓";
                                    refreshOverlay();
                                    status("«تخطي اللاعب» اتضغط ✓");
                                    queueScan(ACTION_DEBOUNCE_MS + 120);
                                } else {
                                    lastDecision = "SKIP✕";
                                    refreshOverlay();
                                    status("فشل ضغط تخطي اللاعب");
                                }
                            });
                            return;
                        }

                        bmp.recycle();
                        lastDecision = "SKIP→";
                        refreshOverlay();
                        status("PASS ✓ — لقيت «تخطي اللاعب» وأضغطه الآن");

                        dispatchTapPx(
                                hit.centerX(),
                                hit.centerY(),
                                () -> {
                                    bidFlowInProgress = false;
                                    lastDecision = "SKIP✓";
                                    refreshOverlay();
                                    status("«تخطي اللاعب» اتضغط ✓ — أنتظر ثانية للشاشة الجديدة");
                                    queueScan(ACTION_DEBOUNCE_MS + 120);
                                },
                                () -> {
                                    bidFlowInProgress = false;
                                    lastDecision = "SKIP✕";
                                    refreshOverlay();
                                    status("فشل ضغط «تخطي اللاعب» — بدون ضغط عشوائي");
                                }
                        );
                    })
                    .addOnFailureListener(e -> {
                        bmp.recycle();
                        bidFlowInProgress = false;
                        status("PASS لكن OCR زر التخطي فشل — بدون ضغط");
                    });
        });
    }

    private void executeBid(AuctionEngine.Snapshot snap) {
        if (bidFlowInProgress || awaitingConfirm || mustSeeWaitingBeforeNextBid) return;

        long remaining = actionCooldownUntil - System.currentTimeMillis();
        if (remaining > 0) {
            h.postDelayed(() -> executeBid(snap), remaining + 80);
            return;
        }

        confirmRetryCount = 0;
        bidFlowInProgress = true;
        awaitingConfirm = false;
        engine.markOwnBidStarted(snap.price);

        lastDecision = "BID→+";
        refreshOverlay();
        status("BID ✓ — أضغط + الآن");

        clickPlusSmart(ok -> {
            if (!ok) {
                bidFlowInProgress = false;
                engine.cancelPending();
                paused = true;
                Prefs.setBotPaused(this, true);
                lastDecision = "+✕";
                refreshOverlay();
                status("فشل ضغط + — SAFETY PAUSE");
                return;
            }

            lastDecision = "+✓ → CFM";
            refreshOverlay();
            status("+ اتضغط ✓ — أنتظر ثانية ثم أضغط «تأكيد المزايدة» مباشرة");

            // No extra OCR/state gate between + and Confirm.
            // In this GUI + only changes the intended bid; Confirm submits it.
            h.postDelayed(() -> {
                if (!running || paused) {
                    bidFlowInProgress = false;
                    engine.cancelPending();
                    return;
                }

                awaitingConfirm = true;
                lastDecision = "CFM→";
                refreshOverlay();
                status("الثانية عدّت ✓ — أضغط «تأكيد المزايدة» الآن");

                clickConfirmSmart(confirmOk -> {
                    awaitingConfirm = false;
                    bidFlowInProgress = false;

                    if (!confirmOk) {
                        engine.cancelPending();
                        paused = true;
                        Prefs.setBotPaused(this, true);
                        lastDecision = "CFM✕";
                        refreshOverlay();
                        status("فشل ضغط «تأكيد المزايدة» — SAFETY PAUSE");
                        return;
                    }

                    engine.markConfirmDispatched();
                    mustSeeWaitingBeforeNextBid = true;
                    lastDecision = "CFM✓";
                    refreshOverlay();
                    status("تمت المزايدة ✓ — أنتظر ثانية ثم أقرأ الشاشة الجديدة");

                    actionCooldownUntil = System.currentTimeMillis() + ACTION_DEBOUNCE_MS;
                    queueScan(ACTION_DEBOUNCE_MS + 120);
                });
            }, 1000L);
        });
    }

    private void verifyConfirmReady(AccessibilityNodeInfo root) {
        if (!awaitingConfirm) return;
        if (System.currentTimeMillis() < actionCooldownUntil) return;

        String raw = root == null ? "" : collectVisibleText(root);
        String all = normalize(raw);

        if (containsAny(all, "انتظار المزايدة", "انتظار المزايده",
                "waiting bid", "waiting for bid")) {
            engine.cancelPending();
            awaitingConfirm = false;
            paused = true;
            Prefs.setBotPaused(this, true);
            status("بعد + ظهرت «انتظار المزايدة» قبل Confirm — SAFETY PAUSE");
            return;
        }

        AccessibilityNodeInfo c = findVisibleTextAny(root,
                "تأكيد المزايدة", "تاكيد المزايده",
                "تأكيد مزايدة", "تاكيد مزايدة",
                "confirm bid", "confirm");

        if (c != null) {
            status("«تأكيد المزايدة» ظاهر ✓ — Physical tap على Confirm");
            clickConfirmAndFinalize();
            return;
        }

        status("بعد + لم أقرأ زر Confirm بوضوح — أتحقق منه بالـOCR فقط");
        verifyTurnLabelByOcr();
    }

    private void pauseConfirmUnverified() {
        engine.cancelPending();
        awaitingConfirm = false;
        bidFlowInProgress = false;
        paused = true;
        Prefs.setBotPaused(this, true);
        status("Confirm غير مؤكد — SAFETY PAUSE");
    }

    private void clickConfirmAndFinalize() {
        if (!awaitingConfirm) return;
        if (System.currentTimeMillis() < actionCooldownUntil) return;

        clickConfirmSmart(ok -> {
            if (!ok) {
                engine.cancelPending();
                awaitingConfirm = false;
                paused = true;
                Prefs.setBotPaused(this, true);
                status("Confirm لم يتأكد أنه اتضغط — SAFETY PAUSE");
                return;
            }

            engine.markConfirmDispatched();
            awaitingConfirm = false;
            bidFlowInProgress = false;
            mustSeeWaitingBeforeNextBid = true;
            lastDecision = "CFM✓";
            refreshOverlay();

            actionCooldownUntil = System.currentTimeMillis() + ACTION_DEBOUNCE_MS;
            status("Confirm اتضغط ✓ — أنتظر ثانية ثم «انتظار المزايدة»");
            queueScan(ACTION_DEBOUNCE_MS + 120);
        });
    }

    private void scanUnknownScreenByOcr() {
        if (turnVisualBusy || screenshotBusy || screenReadBusy) return;
        turnVisualBusy = true;

        captureBitmap(bmp -> {
            if (bmp == null) {
                turnVisualBusy = false;
                status("تعذر قراءة الشاشة — بدون كليك");
                return;
            }

            recognizer.process(InputImage.fromBitmap(bmp, 0))
                    .addOnSuccessListener(tx -> {
                        turnVisualBusy = false;
                        String all = ocrTextWithoutOverlay(tx);

                        Rect immediateHome = findOcrTextRect(tx,
                                "العودة للرئيسية", "العوده للرئيسيه",
                                "العودة للصفحة الرئيسية", "العوده للصفحه الرئيسيه",
                                "return home", "return to main");
                        if (immediateHome != null) {
                            Rect target = immediateHome;
                            bmp.recycle();
                            onMatchCompleted();
                            postMatchStage = 5;
                            returnToMainClickedAt = System.currentTimeMillis();
                            status("OCR: لقيت «العودة للرئيسية» ✓ — أضغطها مباشرة");
                            dispatchTapPx(target.centerX(), target.centerY(),
                                    () -> queueScan(ACTION_DEBOUNCE_MS + 120),
                                    () -> {
                                        postMatchStage = 0;
                                        status("فشل ضغط العودة للرئيسية — سأعيد البحث");
                                    });
                            return;
                        }

                        if (isPostMatchScreen(all)) {
                            bmp.recycle();
                            postMatchStage = 1;
                            postMatchSwipeAttempts = 0;
                            resultWaiting = false;
                            cancelBidFlow();
                            mustSeeWaitingBeforeNextBid = false;
                            status("OCR اكتشف نهاية الجيم ✓ — أبدأ البحث والـScroll");
                            queueScan(ACTION_DEBOUNCE_MS);
                            return;
                        }

                        int bottomTurn = detectBottomAuctionTurn(
                                tx, bmp.getWidth(), bmp.getHeight());
                        boolean ocrConfirm = bottomTurn == 1;
                        boolean ocrWaiting = bottomTurn == 2;

                        // Only the LIVE bottom auction button decides the turn.
                        if (ocrConfirm) {
                            bmp.recycle();

                            if (awaitingConfirm) {
                                status("OCR: «تأكيد المزايدة» ✓ — أضغط Confirm");
                                clickConfirmAndFinalize();
                            } else if (mustSeeWaitingBeforeNextBid) {
                                // Our previous Confirm did not leave the screen.
                                // Retry the real Confirm button instead of freezing forever.
                                if (confirmRetryCount < 2) {
                                    confirmRetryCount++;
                                    status("Confirm ما زال ظاهر — إعادة ضغط " + confirmRetryCount + "/2");
                                    clickConfirmSmart(ok -> {
                                        if (ok) {
                                            actionCooldownUntil =
                                                    System.currentTimeMillis() + ACTION_DEBOUNCE_MS;
                                            queueScan(ACTION_DEBOUNCE_MS + 120);
                                        } else {
                                            paused = true;
                                            Prefs.setBotPaused(this, true);
                                            status("تعذر ضغط Confirm — PAUSE");
                                        }
                                    });
                                } else {
                                    paused = true;
                                    Prefs.setBotPaused(this, true);
                                    status("Confirm لم يستجب بعد محاولتين — PAUSE");
                                }
                            } else {
                                status("OCR: «تأكيد المزايدة» = دورنا");
                                readAuctionSnapshot(true);
                            }
                            return;
                        }

                        if (ocrWaiting) {
                            bmp.recycle();
                            mustSeeWaitingBeforeNextBid = false;
                            confirmRetryCount = 0;
                            status("OCR: «انتظار المزايدة» = دور الخصم");
                            readAuctionSnapshot(false);
                            return;
                        }

                        Rect hit = findOcrTextRect(tx,
                                "هاتلي منافس", "هات لي منافس", "find opponent");
                        if (hit != null) {
                            Rect target = hit;
                            bmp.recycle();
                            engine.resetSession();
                            Prefs.setCurrentRound(AuctionAccessibilityService.this, 1);
                            status("OCR: لقيت هاتلي منافس ✓");
                            dispatchTapPx(target.centerX(), target.centerY(),
                                    () -> queueScan(ACTION_DEBOUNCE_MS + 120),
                                    () -> status("فشل الضغط على هاتلي منافس"));
                            return;
                        }

                        hit = findOcrTextRect(tx,
                                "العب الآن", "العب الان", "play now");
                        if (hit != null) {
                            Rect target = hit;
                            bmp.recycle();
                            status("OCR: لقيت العب الآن ✓");
                            dispatchTapPx(target.centerX(), target.centerY(),
                                    () -> queueScan(ACTION_DEBOUNCE_MS + 120),
                                    () -> status("فشل الضغط على العب الآن"));
                            return;
                        }

                        bmp.recycle();
                        status("OCR الشاشة: لا توجد حالة معروفة — بدون كليك");
                    })
                    .addOnFailureListener(e -> {
                        turnVisualBusy = false;
                        bmp.recycle();
                        status("OCR الشاشة فشل — بدون كليك");
                    });
        });
    }

    private void detectAuctionTurnVisually() {
        if (turnVisualBusy || screenshotBusy || screenReadBusy) return;

        Rect bounds = Build.VERSION.SDK_INT >= 30
                ? wm.getMaximumWindowMetrics().getBounds()
                : new Rect(0, 0,
                    getResources().getDisplayMetrics().widthPixels,
                    getResources().getDisplayMetrics().heightPixels);

        PointF p = Prefs.getTapPointPx(this, "confirm", bounds.width(), bounds.height());
        if (p == null) {
            status("مكان Confirm مش محفوظ — اعمل CAL");
            return;
        }

        moveOverlayAwayFromPoint(p.x, p.y);
        turnVisualBusy = true;

        captureBitmap(bmp -> {
            turnVisualBusy = false;

            if (bmp == null) {
                status("تعذر Screenshot — أستخدم النص كبديل");
                verifyTurnLabelByOcr();
                return;
            }

            int cx = Math.max(0, Math.min(bmp.getWidth() - 1, Math.round(p.x)));
            int cy = Math.max(0, Math.min(bmp.getHeight() - 1, Math.round(p.y)));

            int rx = Math.max(90, Math.round(bmp.getWidth() * .18f));
            int ry = Math.max(28, Math.round(bmp.getHeight() * .028f));

            int l = Math.max(0, cx - rx);
            int r = Math.min(bmp.getWidth(), cx + rx);
            int t = Math.max(0, cy - ry);
            int b = Math.min(bmp.getHeight(), cy + ry);

            int green = 0, amber = 0, neutral = 0, total = 0;
            int sx = Math.max(2, (r-l)/90);
            int sy = Math.max(2, (b-t)/26);

            for (int y=t; y<b; y+=sy) {
                for (int x=l; x<r; x+=sx) {
                    int c = bmp.getPixel(x,y);
                    int rr = Color.red(c), gg = Color.green(c), bb = Color.blue(c);
                    total++;

                    if (gg > 90 && gg > rr * 1.12f && gg > bb * 1.10f) {
                        green++;
                    } else if (rr > 145 && gg > 95 && bb < 115) {
                        amber++;
                    } else if (Math.max(rr, Math.max(gg,bb)) - Math.min(rr, Math.min(gg,bb)) < 35) {
                        neutral++;
                    }
                }
            }

            bmp.recycle();

            float activeRatio = total == 0 ? 0f : (green + amber) / (float) total;
            float neutralRatio = total == 0 ? 0f : neutral / (float) total;

            if (activeRatio >= .035f) {
                mustSeeWaitingBeforeNextBid = false;
                status("زر Confirm ملوّن ✓ — دورنا");
                readAuctionSnapshot(true);
                return;
            }

            if (neutralRatio >= .18f) {
                confirmRetryCount = 0;
                status("زر المزايدة رمادي — دور الخصم");
                readAuctionSnapshot(false);
                return;
            }

            // During WebView transitions/loading the button is neither clearly green
            // nor clearly gray. Do not run another OCR layer and do not guess the turn.
            // Hidden Player simply waited for the next stable frame; do the same here.
            status("زر المزايدة في حالة انتقال/تحميل — أنتظر الفريم التالي");
            queueScan(500);
        });
    }

    private void verifyTurnLabelByOcr() {
        if (turnVisualBusy || screenshotBusy || screenReadBusy) return;

        Rect bounds = Build.VERSION.SDK_INT >= 30
                ? wm.getMaximumWindowMetrics().getBounds()
                : new Rect(0, 0,
                    getResources().getDisplayMetrics().widthPixels,
                    getResources().getDisplayMetrics().heightPixels);

        PointF raw = Prefs.getTapPointPx(this, "confirm", bounds.width(), bounds.height());
        PointF norm = Prefs.getPoint(this, "confirm");
        PointF fallbackConfirm = defaultTapPoint("confirm", bounds);

        turnVisualBusy = true;

        captureBitmap(bmp -> {
            if (bmp == null) {
                turnVisualBusy = false;
                status("تعذر قراءة زر المزايدة — بدون كليك");
                h.postDelayed(this::scanUnknownScreenByOcr, 150);
                return;
            }

            int cx;
            int cy;

            if (raw != null) {
                cx = Math.round(raw.x);
                cy = Math.round(raw.y);
            } else if (norm != null) {
                cx = Math.round(norm.x * bmp.getWidth());
                cy = Math.round(norm.y * bmp.getHeight());
            } else {
                cx = fallbackConfirm == null
                        ? Math.round(bmp.getWidth() * .292f)
                        : Math.round(fallbackConfirm.x);
                cy = fallbackConfirm == null
                        ? Math.round(bmp.getHeight() * .900f)
                        : Math.round(fallbackConfirm.y);
            }

            cx = Math.max(0, Math.min(bmp.getWidth() - 1, cx));
            cy = Math.max(0, Math.min(bmp.getHeight() - 1, cy));

            int rx = Math.max(110, Math.round(bmp.getWidth() * .24f));
            int ry = Math.max(42, Math.round(bmp.getHeight() * .050f));

            int l = Math.max(0, cx - rx);
            int r = Math.min(bmp.getWidth(), cx + rx);
            int t = Math.max(0, cy - ry);
            int b = Math.min(bmp.getHeight(), cy + ry);

            Bitmap crop;
            try {
                crop = Bitmap.createBitmap(bmp, l, t, Math.max(1, r-l), Math.max(1, b-t));
            } catch (Exception e) {
                bmp.recycle();
                turnVisualBusy = false;
                status("تعذر قص منطقة زر المزايدة");
                h.postDelayed(this::scanUnknownScreenByOcr, 150);
                return;
            }
            bmp.recycle();

            Bitmap big = Bitmap.createScaledBitmap(
                    crop,
                    Math.max(1, crop.getWidth() * 4),
                    Math.max(1, crop.getHeight() * 4),
                    true
            );
            crop.recycle();

            recognizer.process(InputImage.fromBitmap(big, 0))
                    .addOnSuccessListener(tx -> {
                        turnVisualBusy = false;

                        // IMPORTANT: tx bounding boxes are LOCAL to this crop.
                        // Do not compare them to the screen-space overlay rectangle.
                        String label = normalize(tx.getText());
                        big.recycle();

                        boolean cropConfirm = containsAny(label,
                                "تأكيد المزايدة", "تاكيد المزايده",
                                "تأكيد مزايدة", "تاكيد مزايدة",
                                "confirm bid", "confirm");

                        boolean cropWaiting = containsAny(label,
                                "انتظار المزايدة", "انتظار المزايده",
                                "waiting bid", "waiting for bid");

                        if (cropConfirm) {

                            if (awaitingConfirm) {
                                status("OCR زر المزايدة: Confirm ✓ — أضغطه");
                                clickConfirmAndFinalize();
                            } else if (mustSeeWaitingBeforeNextBid) {
                                if (confirmRetryCount < 2) {
                                    confirmRetryCount++;
                                    status("Confirm ما زال ظاهر — إعادة الضغط " + confirmRetryCount + "/2");
                                    clickConfirmSmart(ok -> {
                                        if (ok) {
                                            actionCooldownUntil =
                                                    System.currentTimeMillis() + ACTION_DEBOUNCE_MS;
                                            queueScan(ACTION_DEBOUNCE_MS + 120);
                                        } else {
                                            paused = true;
                                            Prefs.setBotPaused(this, true);
                                            status("تعذر ضغط Confirm — PAUSE");
                                        }
                                    });
                                } else {
                                    paused = true;
                                    Prefs.setBotPaused(this, true);
                                    status("Confirm لم يستجب بعد محاولتين — PAUSE");
                                }
                            } else {
                                status("OCR زر المزايدة: «تأكيد المزايدة» ✓ — دورنا");
                                readAuctionSnapshot(true);
                            }
                            return;
                        }

                        if (cropWaiting) {
                            mustSeeWaitingBeforeNextBid = false;
                            confirmRetryCount = 0;
                            status("OCR زر المزايدة: «انتظار المزايدة» ✓ — دور الخصم");
                            readAuctionSnapshot(false);
                            return;
                        }

                        // The small button crop did not identify the turn. Now and only
                        // now fall back to full-screen OCR for navigation/post-match words.
                        status("زر المزايدة غير واضح — أفحص الشاشة كاملة");
                        h.postDelayed(this::scanUnknownScreenByOcr, 120);
                    })
                    .addOnFailureListener(e -> {
                        turnVisualBusy = false;
                        big.recycle();
                        status("OCR زر المزايدة فشل — أفحص الشاشة كاملة");
                        h.postDelayed(this::scanUnknownScreenByOcr, 120);
                    });
        });
    }


    private int[] fingerprintAtSavedPoint(Bitmap bmp, String key) {
        PointF p = Prefs.getPoint(this, key);
        if (bmp == null || p == null) return null;

        int cx = Math.round(p.x * bmp.getWidth());
        int cy = Math.round(p.y * bmp.getHeight());
        int rx = Math.max(8, Math.round(bmp.getWidth() * .06f));
        int ry = Math.max(8, Math.round(bmp.getHeight() * .025f));

        int l = Math.max(0, cx - rx), r = Math.min(bmp.getWidth() - 1, cx + rx);
        int t = Math.max(0, cy - ry), b = Math.min(bmp.getHeight() - 1, cy + ry);

        long rr = 0, gg = 0, bb = 0, count = 0;
        int sx = Math.max(1, (r - l) / 24);
        int sy = Math.max(1, (b - t) / 12);

        for (int y = t; y <= b; y += sy) {
            for (int x = l; x <= r; x += sx) {
                int c = bmp.getPixel(x, y);
                rr += Color.red(c);
                gg += Color.green(c);
                bb += Color.blue(c);
                count++;
            }
        }

        if (count == 0) return null;
        return new int[]{(int)(rr / count), (int)(gg / count), (int)(bb / count)};
    }

    private int colorDifference(int[] a, int[] b) {
        if (a == null || b == null) return 0;
        return (Math.abs(a[0]-b[0]) + Math.abs(a[1]-b[1]) + Math.abs(a[2]-b[2])) / 3;
    }

    // ---------- Screen capture ----------

    private void captureBitmap(BitmapConsumer cb) {
        if (Build.VERSION.SDK_INT < 30) {
            cb.accept(null);
            return;
        }

        if (screenshotBusy) {
            cb.accept(null);
            return;
        }

        screenshotBusy = true;

        try {
            takeScreenshot(0, getMainExecutor(), new TakeScreenshotCallback() {
                @Override public void onSuccess(ScreenshotResult result) {
                    Bitmap copy = null;

                    try {
                        HardwareBuffer hb = result.getHardwareBuffer();
                        Bitmap hw = Bitmap.wrapHardwareBuffer(hb, result.getColorSpace());
                        if (hw != null) copy = hw.copy(Bitmap.Config.ARGB_8888, false);
                        hb.close();
                    } catch (Exception ignored) {}

                    screenshotBusy = false;
                    cb.accept(copy);
                }

                @Override public void onFailure(int errorCode) {
                    screenshotBusy = false;
                    cb.accept(null);
                }
            });
        } catch (Exception e) {
            screenshotBusy = false;
            cb.accept(null);
        }
    }

    // ---------- Calibration: 4 OCR rectangles + + point + Confirm point ----------

    private void startCalibration() {
        running = false;
        Prefs.setBotWanted(this, false);
        h.removeCallbacks(monitor);
        removeFloatingOverlay();
        removeCalibration();

        // v18: no guessed coordinates. The user defines the fixed zones once.
        Prefs.clearCalibration(this);
        calibrationStep = 0;
        showCalibrationStep();
    }

    private void showCalibrationStep() {
        removeCalibration();
        if (wm == null) wm = (WindowManager) getSystemService(WINDOW_SERVICE);

        FrameLayout frame = new FrameLayout(this);
        calibrationView = new CalibrationView(this);
        frame.addView(calibrationView, new FrameLayout.LayoutParams(-1, -1));

        calibrationInstruction = new TextView(this);
        calibrationInstruction.setTextColor(Color.WHITE);
        calibrationInstruction.setTextSize(17);
        calibrationInstruction.setTypeface(null, Typeface.BOLD);
        calibrationInstruction.setGravity(Gravity.CENTER);
        calibrationInstruction.setPadding(dp(10), dp(10), dp(10), dp(10));
        calibrationInstruction.setBackgroundColor(Color.argb(225, 5, 14, 27));
        calibrationInstruction.setText(calibrationLabel());

        FrameLayout.LayoutParams ilp = new FrameLayout.LayoutParams(-1, dp(76), Gravity.TOP);
        frame.addView(calibrationInstruction, ilp);

        LinearLayout bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.HORIZONTAL);
        bottom.setPadding(dp(10), dp(8), dp(10), dp(8));
        bottom.setBackgroundColor(Color.argb(230, 5, 14, 27));

        Button cancel = calibrationButton("إلغاء", Color.rgb(70,35,48));
        calibrationSave = calibrationButton(calibrationStep < 5 ? "حفظ / التالي ✓" : "المس الزر على الشاشة", Color.rgb(31,149,255));

        cancel.setOnClickListener(v -> {
            removeCalibration();
            showFloatingOverlay(true);
            status("تم إلغاء المعايرة");
        });

        calibrationSave.setOnClickListener(v -> {
            if (calibrationStep >= 5) return;
            RectF n = calibrationView.getNormalizedSelection();
            if (n == null || n.width() < .015f || n.height() < .012f) {
                Toast.makeText(this, "ارسم مربعًا واضحًا حول الرقم فقط", Toast.LENGTH_SHORT).show();
                return;
            }
            String key = new String[]{"rating","price","mine","opponent","turn"}[calibrationStep];
            Prefs.saveRegion(this, key, n);
            calibrationStep++;
            showCalibrationStep();
        });

        bottom.addView(cancel, new LinearLayout.LayoutParams(0, dp(52), 1));
        Space gap = new Space(this);
        bottom.addView(gap, new LinearLayout.LayoutParams(dp(8), 1));
        bottom.addView(calibrationSave, new LinearLayout.LayoutParams(0, dp(52), 2));

        FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(-1, dp(70), Gravity.BOTTOM);
        frame.addView(bottom, blp);

        calibrationRoot = frame;

        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                -1, -1,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN |
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;

        try {
            wm.addView(frame, lp);
        } catch (Exception e) {
            calibrationRoot = null;
            status("تعذر فتح شاشة المعايرة");
            showFloatingOverlay(true);
        }
    }

    private String calibrationLabel() {
        switch (calibrationStep) {
            case 0: return "1/8 — ارسم مربع صغير حول رقم تقييم اللاعب فقط";
            case 1: return "2/8 — ارسم مربع صغير حول سعر المزايدة الحالي فقط";
            case 2: return "3/8 — ارسم مربع صغير حول ميزانيتك فقط";
            case 3: return "4/8 — ارسم مربع صغير حول ميزانية الخصم فقط";
            case 4: return "5/8 — ارسم مربع حول زر «تأكيد/انتظار المزايدة» بالكامل";
            case 5: return "6/8 — المس منتصف زر + مرة واحدة";
            case 6: return "7/8 — المس منتصف زر تأكيد المزايدة مرة واحدة";
            default: return "8/8 — المس منتصف زر تخطي اللاعب مرة واحدة";
        }
    }


    private void onCalibrationPoint(float x, float y) {
        if (calibrationStep < 5 || calibrationStep > 7) return;

        Rect bounds = Build.VERSION.SDK_INT >= 30
                ? wm.getMaximumWindowMetrics().getBounds()
                : new Rect(0, 0,
                    getResources().getDisplayMetrics().widthPixels,
                    getResources().getDisplayMetrics().heightPixels);

        String key = calibrationStep == 5 ? "plus"
                : calibrationStep == 6 ? "confirm"
                : "skip";

        Prefs.savePoint(this, key,
                (x - bounds.left) / Math.max(1f, bounds.width()),
                (y - bounds.top) / Math.max(1f, bounds.height()));
        Prefs.saveTapPointPx(this, key, x, y, bounds.width(), bounds.height());

        calibrationStep++;

        if (calibrationStep > 7) {
            Prefs.markCalibrationComplete(this);
            removeCalibration();
            showFloatingOverlay(true);
            status(Prefs.isCalibrated(this)
                    ? "CAL كامل 8/8 ✓ — كل مناطق القراءة والأزرار محفوظة"
                    : "المعايرة غير مكتملة — أعد CAL");
            h.postDelayed(this::testOcr, 700);
        } else {
            showCalibrationStep();
        }
    }


    private void removeCalibration() {
        if (calibrationRoot != null && wm != null) {
            try { wm.removeView(calibrationRoot); } catch (Exception ignored) {}
        }
        calibrationRoot = null;
        calibrationView = null;
        calibrationInstruction = null;
        calibrationSave = null;
    }

    private Button calibrationButton(String s, int fill) {
        Button b = new Button(this);
        b.setAllCaps(false);
        b.setText(s);
        b.setTextColor(Color.WHITE);
        b.setTextSize(14);
        b.setBackground(round(fill, Color.rgb(80,100,130), 12));
        return b;
    }

    private class CalibrationView extends View {
        private final Paint border = new Paint(3);
        private final Paint shade = new Paint(3);
        private final Paint cross = new Paint(3);
        private final Paint label = new Paint(3);
        private RectF sel = new RectF();
        private float downX, downY;
        private boolean moving = false;
        private float offX, offY;

        CalibrationView(Context c) {
            super(c);
            setLayerType(View.LAYER_TYPE_SOFTWARE, null);
            border.setStyle(Paint.Style.STROKE);
            border.setStrokeWidth(dp(3));
            border.setColor(Color.rgb(40,195,255));
            shade.setColor(Color.argb(80,0,0,0));
            cross.setColor(Color.rgb(40,195,255));
            cross.setStrokeWidth(dp(3));
            label.setColor(Color.WHITE);
            label.setTextSize(dp(14));
            label.setTypeface(Typeface.DEFAULT_BOLD);
        }

        @Override protected void onDraw(Canvas c) {
            super.onDraw(c);

            if (calibrationStep < 5) {
                if (!sel.isEmpty()) {
                    c.drawRect(0, 0, getWidth(), sel.top, shade);
                    c.drawRect(0, sel.bottom, getWidth(), getHeight(), shade);
                    c.drawRect(0, sel.top, sel.left, sel.bottom, shade);
                    c.drawRect(sel.right, sel.top, getWidth(), sel.bottom, shade);
                    c.drawRoundRect(sel, dp(8), dp(8), border);
                    c.drawText("OCR", sel.left + dp(8), Math.max(dp(96), sel.top - dp(8)), label);
                    float cx = sel.centerX(), cy = sel.centerY();
                    c.drawLine(cx-dp(8), cy, cx+dp(8), cy, cross);
                    c.drawLine(cx, cy-dp(8), cx, cy+dp(8), cross);
                }
            } else {
                c.drawColor(Color.argb(15,0,0,0));
                String tapLabel = calibrationStep == 5 ? "TAP +"
                        : calibrationStep == 6 ? "TAP CONFIRM"
                        : "TAP SKIP";
                c.drawText(tapLabel, dp(18), dp(110), label);
            }
        }

        @Override public boolean onTouchEvent(MotionEvent e) {
            float x = Math.max(0, Math.min(getWidth(), e.getX()));
            float y = Math.max(dp(82), Math.min(getHeight()-dp(76), e.getY()));

            if (calibrationStep >= 5) {
                if (e.getAction() == MotionEvent.ACTION_DOWN) {
                    final float rawX = e.getRawX();
                    final float rawY = e.getRawY();
                    h.post(() -> onCalibrationPoint(rawX, rawY));
                }
                return true;
            }

            if (e.getAction() == MotionEvent.ACTION_DOWN) {
                downX = x; downY = y;
                if (sel.contains(x, y) && sel.width() > dp(20) && sel.height() > dp(20)) {
                    moving = true;
                    offX = x - sel.left;
                    offY = y - sel.top;
                } else {
                    moving = false;
                    sel.set(x, y, x+1, y+1);
                }
                invalidate();
                return true;
            }

            if (e.getAction() == MotionEvent.ACTION_MOVE) {
                if (moving) {
                    float ww = sel.width(), hh = sel.height();
                    float l = x - offX, t = y - offY;
                    l = Math.max(0, Math.min(getWidth() - ww, l));
                    t = Math.max(dp(82), Math.min(getHeight() - dp(76) - hh, t));
                    sel.set(l, t, l + ww, t + hh);
                } else {
                    sel.set(Math.min(downX,x), Math.min(downY,y), Math.max(downX,x), Math.max(downY,y));
                }
                invalidate();
                return true;
            }
            return true;
        }

        RectF getNormalizedSelection() {
            if (getWidth() <= 0 || getHeight() <= 0 || sel.isEmpty()) return null;
            return new RectF(
                    sel.left / getWidth(),
                    sel.top / getHeight(),
                    sel.right / getWidth(),
                    sel.bottom / getHeight()
            );
        }
    }

    private void diagnosticTap(String key, String label) {
        Rect bounds = Build.VERSION.SDK_INT >= 30
                ? wm.getMaximumWindowMetrics().getBounds()
                : new Rect(0, 0,
                    getResources().getDisplayMetrics().widthPixels,
                    getResources().getDisplayMetrics().heightPixels);

        PointF p = Prefs.getTapPointPx(this, key, bounds.width(), bounds.height());
        if (p == null) {
            status(label + ": مفيش CAL محفوظ للنقطة دي");
            return;
        }

        status(label + ": أضغط الآن عند X" + Math.round(p.x) + " Y" + Math.round(p.y));
        tapSavedPoint(key, ok -> status(ok
                ? label + " ✓ gesture completed"
                : label + " ✕ gesture failed"));
    }

    private void diagnosticForceBid() {
        status("FORCE TEST: + الآن");
        tapSavedPoint("plus", plusOk -> {
            if (!plusOk) {
                status("FORCE TEST: فشل +");
                return;
            }

            status("FORCE TEST: + ✓ — بعد ثانية Confirm");
            h.postDelayed(() -> tapSavedPoint("confirm", confirmOk -> {
                status(confirmOk
                        ? "FORCE TEST ✓: + ثم Confirm اتنفذوا"
                        : "FORCE TEST: + نجح لكن Confirm فشل");
            }), 1000L);
        });
    }

    private void testOcr() {
        if (!Prefs.isCalibrated(this)) {
            status("TEST: اعمل CAL للـ + و Confirm أولًا");
            return;
        }

        status("TEST AUTO OCR… أقرأ الشاشة كاملة");
        screenReadBusy = true;

        captureBitmap(bmp -> {
            if (bmp == null) {
                screenReadBusy = false;
                status("TEST AUTO OCR: تعذر أخذ Screenshot");
                return;
            }

            recognizer.process(InputImage.fromBitmap(bmp, 0))
                    .addOnSuccessListener(tx -> {
                        SpatialNumbers n =
                                extractSpatialAuctionNumbers(tx, bmp.getWidth(), bmp.getHeight());
                        bmp.recycle();
                        screenReadBusy = false;

                        status("TEST AUTO OCR ✓  OVR " + show(n.rating) +
                                " | Price " + show(n.price) +
                                " | You " + show(n.mine) +
                                " | Opp " + show(n.opp));
                    })
                    .addOnFailureListener(e -> {
                        bmp.recycle();
                        screenReadBusy = false;
                        status("TEST AUTO OCR: فشل OCR");
                    });
        });
    }


    // ---------- Floating overlay ----------

    private void showFloatingOverlay(boolean expanded) {
        overlayExpanded = expanded;
        if (wm == null) wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        removeFloatingOverlay();

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(expanded ? 8 : 5), dp(expanded ? 6 : 4), dp(expanded ? 8 : 5), dp(expanded ? 7 : 4));
        box.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        box.setBackground(round(
                Color.argb(expanded ? 225 : 205, 7, 18, 32),
                Color.rgb(91,82,225),
                expanded ? 14 : 11));

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout dragArea = new LinearLayout(this);
        dragArea.setOrientation(LinearLayout.HORIZONTAL);
        dragArea.setGravity(Gravity.CENTER_VERTICAL);

        TextView title = ovText(expanded ? "◈ AUCTION GENIUS" : "◈ AUCTION", expanded ? 12.5f : 10f, Color.WHITE, true);
        title.setGravity(Gravity.LEFT | Gravity.CENTER_VERTICAL);

        ovRunState = ovText("", expanded ? 10.5f : 9f, Color.rgb(0,230,150), true);
        ovRunState.setGravity(Gravity.CENTER);

        dragArea.addView(title, new LinearLayout.LayoutParams(0, dp(expanded ? 36 : 25), 1.15f));
        dragArea.addView(ovRunState, new LinearLayout.LayoutParams(0, dp(expanded ? 36 : 25), 1f));

        TextView collapse = ovText(expanded ? "−" : "☰", expanded ? 22 : 17, Color.rgb(160,186,255), true);
        collapse.setGravity(Gravity.CENTER);
        TextView close = ovText("×", expanded ? 23 : 18, Color.rgb(255,105,125), true);
        close.setGravity(Gravity.CENTER);

        head.addView(dragArea, new LinearLayout.LayoutParams(0, dp(expanded ? 36 : 25), 1));
        head.addView(collapse, new LinearLayout.LayoutParams(dp(expanded ? 42 : 28), dp(expanded ? 36 : 25)));
        head.addView(close, new LinearLayout.LayoutParams(dp(expanded ? 42 : 28), dp(expanded ? 36 : 25)));
        box.addView(head);

        if (expanded) {
            ovStatus = ovText(lastStatus, 10.2f, Color.rgb(215,226,242), false);
            ovStatus.setMaxLines(2);
            ovStatus.setEllipsize(android.text.TextUtils.TruncateAt.END);
            box.addView(ovStatus, new LinearLayout.LayoutParams(-1, dp(38)));

            LinearLayout metrics = new LinearLayout(this);
            metrics.setOrientation(LinearLayout.HORIZONTAL);
            metrics.setPadding(0, dp(3), 0, 0);

            ovRating = chip("OVR " + show(lastRating), Color.rgb(15,45,67));
            ovPrice = chip("P " + show(lastPrice) + "M", Color.rgb(15,45,67));
            ovBudgets = chip("Y " + show(lastMine) + " / O " + show(lastOpp), Color.rgb(15,45,67));
            ovDecision = chip(lastDecision, Color.rgb(15,45,67));

            metrics.addView(ovRating, new LinearLayout.LayoutParams(0, dp(34), 1));
            addGap(metrics, 4);
            metrics.addView(ovPrice, new LinearLayout.LayoutParams(0, dp(34), 1));
            addGap(metrics, 4);
            metrics.addView(ovBudgets, new LinearLayout.LayoutParams(0, dp(34), 1.55f));
            addGap(metrics, 4);
            metrics.addView(ovDecision, new LinearLayout.LayoutParams(0, dp(34), 1.35f));
            box.addView(metrics);

            LinearLayout players = new LinearLayout(this);
            players.setOrientation(LinearLayout.HORIZONTAL);
            players.setPadding(0, dp(5), 0, 0);

            String[] pNames = {"GK", "DEF", "CM1", "CM2", "ST"};
            int activeSlot = Math.max(0, Math.min(4, currentRound() - 1));

            for (int i = 0; i < 5; i++) {
                String text = pNames[i] + "\n" + Prefs.getMinRating(this, i);
                int fill = i == activeSlot ? Color.rgb(78,58,180) : Color.rgb(15,45,67);
                TextView p = chip(text, fill);
                p.setTextSize(10.5f);
                p.setGravity(Gravity.CENTER);
                ovPlayerMins[i] = p;
                players.addView(p, new LinearLayout.LayoutParams(0, dp(44), 1));
                if (i < 4) addGap(players, 3);
            }
            box.addView(players);

            LinearLayout controls = new LinearLayout(this);
            controls.setOrientation(LinearLayout.HORIZONTAL);
            controls.setPadding(0, dp(6), 0, 0);

            Button start = ovButton("START", Color.rgb(0,191,120));
            Button pause = ovButton("PAUSE", Color.rgb(26,55,82));
            Button stop = ovButton("STOP", Color.rgb(105,24,42));
            Button cal = ovButton("CAL", Color.rgb(25,133,220));
            Button test = ovButton("OCR", Color.rgb(34,58,86));

            start.setTextSize(11);
            pause.setTextSize(11);
            stop.setTextSize(11);
            cal.setTextSize(11);
            test.setTextSize(11);

            start.setOnClickListener(v -> startBot(true));
            pause.setOnClickListener(v -> togglePause());
            stop.setOnClickListener(v -> stopBot("تم إيقاف البوت يدويًا"));
            cal.setOnClickListener(v -> startCalibration());
            test.setOnClickListener(v -> testOcr());

            controls.addView(start, new LinearLayout.LayoutParams(0, dp(42), 1.2f));
            addGap(controls, 4);
            controls.addView(pause, new LinearLayout.LayoutParams(0, dp(42), 1.2f));
            addGap(controls, 4);
            controls.addView(stop, new LinearLayout.LayoutParams(0, dp(42), 1.1f));
            addGap(controls, 4);
            controls.addView(cal, new LinearLayout.LayoutParams(0, dp(42), 1f));
            addGap(controls, 4);
            controls.addView(test, new LinearLayout.LayoutParams(0, dp(42), 1f));
            box.addView(controls);

            LinearLayout diagnostics = new LinearLayout(this);
            diagnostics.setOrientation(LinearLayout.HORIZONTAL);
            diagnostics.setPadding(0, dp(5), 0, 0);

            Button testPlus = ovButton("TEST +", Color.rgb(38,92,62));
            Button testConfirm = ovButton("TEST CFM", Color.rgb(38,92,62));
            Button forceBid = ovButton("+ → CFM", Color.rgb(113,73,24));

            testPlus.setTextSize(11);
            testConfirm.setTextSize(11);
            forceBid.setTextSize(11);

            testPlus.setOnClickListener(v -> diagnosticTap("plus", "TEST +"));
            testConfirm.setOnClickListener(v -> diagnosticTap("confirm", "TEST CFM"));
            forceBid.setOnClickListener(v -> diagnosticForceBid());

            diagnostics.addView(testPlus, new LinearLayout.LayoutParams(0, dp(42), 1f));
            addGap(diagnostics, 4);
            diagnostics.addView(testConfirm, new LinearLayout.LayoutParams(0, dp(42), 1f));
            addGap(diagnostics, 4);
            diagnostics.addView(forceBid, new LinearLayout.LayoutParams(0, dp(42), 1.15f));
            box.addView(diagnostics);
        } else {
            ovStatus = ovText(shortStatus(), 9f, Color.rgb(205,219,241), true);
            ovStatus.setGravity(Gravity.CENTER);
            ovStatus.setSingleLine(true);
            box.addView(ovStatus, new LinearLayout.LayoutParams(-1, dp(22)));
        }

        collapse.setOnClickListener(v -> showFloatingOverlay(!overlayExpanded));
        close.setOnClickListener(v -> {
            stopBot("تم إغلاق لوحة التحكم — البوت متوقف");
            Prefs.setOverlayWanted(this, false);
            removeFloatingOverlay();
        });

        floatingView = box;

        int screenWidth = getResources().getDisplayMetrics().widthPixels;
        int width = expanded
                ? Math.max(dp(270), Math.round(screenWidth * .68f))
                : Math.max(dp(120), Math.round(screenWidth * .32f));

        floatingLp = new WindowManager.LayoutParams(
                width, -2,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN |
                        WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
                PixelFormat.TRANSLUCENT);

        floatingLp.gravity = Gravity.TOP | Gravity.START;
        floatingLp.x = overlaySavedX == Integer.MIN_VALUE ? dp(6) : overlaySavedX;
        floatingLp.y = overlaySavedY == Integer.MIN_VALUE ? dp(62) : overlaySavedY;

        final float[] touch = new float[4];

        View.OnTouchListener drag = (v, e) -> {
            if (floatingLp == null || floatingView == null) return false;

            if (e.getAction() == MotionEvent.ACTION_DOWN) {
                touch[0] = e.getRawX();
                touch[1] = e.getRawY();
                touch[2] = floatingLp.x;
                touch[3] = floatingLp.y;
                return true;
            }

            if (e.getAction() == MotionEvent.ACTION_MOVE) {
                floatingLp.x = Math.round(touch[2] + e.getRawX() - touch[0]);
                floatingLp.y = Math.round(touch[3] + e.getRawY() - touch[1]);
                overlaySavedX = floatingLp.x;
                overlaySavedY = floatingLp.y;

                try {
                    wm.updateViewLayout(floatingView, floatingLp);
                } catch (Exception ignored) {}
                return true;
            }

            return true;
        };

        // Large drag area: title + run state, instead of a tiny title only.
        dragArea.setOnTouchListener(drag);

        try {
            wm.addView(box, floatingLp);
            Prefs.setOverlayWanted(this, true);
            refreshOverlay();
        } catch (Exception ignored) {
            floatingView = null;
        }
    }

    private void removeFloatingOverlay() {
        if (floatingView != null && wm != null) {
            try { wm.removeView(floatingView); } catch (Exception ignored) {}
        }
        floatingView = null;
        floatingLp = null;
    }

    private void refreshOverlay() {
        int round = currentRound();

        if (ovRunState != null) {
            String state = !running ? "STOP" : paused ? "PAUSE" : "RUN";
            ovRunState.setText("● " + state + " R" + round + " • " + repeatShort());
            ovRunState.setTextColor(!running ? Color.rgb(255,105,125) :
                    paused ? Color.rgb(255,190,80) : Color.rgb(0,230,150));
        }

        if (ovStatus != null) ovStatus.setText(overlayExpanded ? lastStatus : shortStatus());
        if (ovRating != null) ovRating.setText("O" + show(lastRating));
        if (ovPrice != null) ovPrice.setText("€" + show(lastPrice));
        if (ovBudgets != null) ovBudgets.setText("Y" + show(lastMine) + "/O" + show(lastOpp));
        if (ovDecision != null) ovDecision.setText(lastDecision == null ? "—" : lastDecision);

        for (int i = 0; i < 5; i++) {
            if (ovPlayerMins[i] != null) {
                String[] names = {"GK", "DF", "C1", "C2", "ST"};
                ovPlayerMins[i].setText(names[i] + "\n" + Prefs.getMinRating(this, i));
                int fill = (i == Math.max(0, round - 1))
                        ? Color.rgb(78,58,180)
                        : Color.rgb(15,45,67);
                ovPlayerMins[i].setBackground(round(fill, Color.rgb(45,73,103), 8));
            }
        }
    }

    private String repeatShort() {
        String mode = Prefs.repeatMode(this);
        if ("count".equals(mode)) return "#" + Prefs.repeatCount(this);
        if ("time".equals(mode)) return Prefs.repeatMinutes(this) + "m";
        return "∞";
    }

    private int currentRound() {
        if (engine != null) return Math.max(1, Math.min(5, engine.getRound()));
        return Prefs.currentRound(this);
    }

    private String currentSlotName() {
        return AuctionEngine.SLOT_NAMES[Math.max(0, Math.min(4, currentRound()-1))];
    }

    private String repeatLabel() {
        String mode = Prefs.repeatMode(this);
        if ("count".equals(mode)) return "Repeat: " + Prefs.repeatCount(this) + " games";
        if ("time".equals(mode)) return "Repeat: " + Prefs.repeatMinutes(this) + " min";
        return "Repeat: ∞";
    }

    private String shortStatus() {
        if (!running) return "STOPPED";
        if (paused) return "PAUSE • R" + currentRound() + "/5";
        return "RUN • R" + currentRound() + "/5 • " + lastDecision;
    }

    // ---------- Click / accessibility helpers ----------

    public static boolean isReady() { return instance != null; }

    public static void clickPlus(float fallbackX, float fallbackY, Callback callback) {
        AuctionAccessibilityService s = instance;
        if (s == null) {
            if (callback != null) callback.onDone(false);
            return;
        }
        if (s.clickPlusNode()) {
            if (callback != null) callback.onDone(true);
            return;
        }
        tap(fallbackX, fallbackY, callback);
    }

    public static void clickConfirm(float fallbackX, float fallbackY, Callback callback) {
        AuctionAccessibilityService s = instance;
        if (s == null) {
            if (callback != null) callback.onDone(false);
            return;
        }
        AccessibilityNodeInfo root = s.getRootInActiveWindow();
        AccessibilityNodeInfo n = s.findVisibleTextAny(root, "confirm", "confirm bid", "place bid", "تأكيد", "تاكيد");
        if (n != null && s.clickNode(n)) {
            if (callback != null) callback.onDone(true);
            return;
        }
        tap(fallbackX, fallbackY, callback);
    }

    public static void tap(float x, float y, Callback callback) {
        AuctionAccessibilityService s = instance;
        if (s == null) {
            if (callback != null) callback.onDone(false);
            return;
        }
        s.dispatchTapPx(x, y, () -> {
            if (callback != null) callback.onDone(true);
        }, () -> {
            if (callback != null) callback.onDone(false);
        });
    }

    private void clickPlusSmart(Callback cb) {
        tapSavedPoint("plus", cb);
    }

    private void clickConfirmSmart(Callback cb) {
        // User explicitly calibrates the real Confirm button.
        // Keep this path intentionally simple and identical every time:
        // one physical gesture at the exact calibrated pixel.
        tapSavedPoint("confirm", cb);
    }

    private boolean tapNodePhysically(AccessibilityNodeInfo n, Callback cb) {
        if (n == null || !nodeOnScreen(n)) return false;

        Rect r = new Rect();
        n.getBoundsInScreen(r);
        if (r.isEmpty()) return false;

        dispatchTapPx(r.centerX(), r.centerY(),
                () -> cb.onDone(true),
                () -> cb.onDone(false));
        return true;
    }

    private boolean clickPlusNode() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return false;
        AccessibilityNodeInfo n = findPlusRec(root, 0);
        if (n == null) return false;

        Rect r = new Rect();
        n.getBoundsInScreen(r);
        if (r.isEmpty()) return false;

        dispatchTapPx(r.centerX(), r.centerY(), null, null);
        return true;
    }

    private AccessibilityNodeInfo findPlusRec(AccessibilityNodeInfo n, int depth) {
        if (n == null || depth > 40) return null;
        String t = n.getText() == null ? "" : n.getText().toString().trim();
        String d = n.getContentDescription() == null ? "" : normalize(n.getContentDescription().toString());
        if (nodeOnScreen(n) && ("+".equals(t) ||
                d.contains("increase") || d.contains("add bid") || d.contains("bid plus") ||
                d.contains("زياده") || d.contains("زيادة") || d.contains("زايد"))) {
            return n;
        }
        for (int i = 0; i < n.getChildCount(); i++) {
            AccessibilityNodeInfo r = findPlusRec(n.getChild(i), depth + 1);
            if (r != null) return r;
        }
        return null;
    }

    private void moveOverlayAwayFromPoint(float x, float y) {
        if (floatingView == null || floatingLp == null || wm == null ||
                floatingView.getWidth() <= 0 || floatingView.getHeight() <= 0) {
            return;
        }

        Rect ov = new Rect(
                floatingLp.x,
                floatingLp.y,
                floatingLp.x + floatingView.getWidth(),
                floatingLp.y + floatingView.getHeight()
        );

        if (!ov.contains(Math.round(x), Math.round(y))) return;

        int screenW = getResources().getDisplayMetrics().widthPixels;
        int margin = dp(6);

        // Keep the user's overlay visible, but move it away only when it would
        // physically cover the calibrated game button.
        if (x < screenW / 2f) {
            floatingLp.x = Math.max(margin, screenW - floatingView.getWidth() - margin);
        } else {
            floatingLp.x = margin;
        }
        floatingLp.y = dp(62);
        overlaySavedX = floatingLp.x;
        overlaySavedY = floatingLp.y;

        try { wm.updateViewLayout(floatingView, floatingLp); } catch (Exception ignored) {}
    }

    private PointF defaultTapPoint(String key, Rect bounds) {
        if (bounds == null) return null;
        float nx;
        float ny;

        // Defaults measured from the user's actual Embabi auction layout.
        // CAL, when present, always overrides these.
        if ("plus".equals(key)) {
            nx = .872f; ny = .818f;
        } else if ("confirm".equals(key)) {
            nx = .292f; ny = .900f;
        } else if ("skip".equals(key)) {
            nx = .760f; ny = .900f;
        } else {
            return null;
        }

        return new PointF(
                bounds.left + nx * bounds.width(),
                bounds.top + ny * bounds.height()
        );
    }

    private void tapSavedPoint(String key, Callback cb) {
        Rect bounds = Build.VERSION.SDK_INT >= 30
                ? wm.getMaximumWindowMetrics().getBounds()
                : new Rect(0, 0,
                    getResources().getDisplayMetrics().widthPixels,
                    getResources().getDisplayMetrics().heightPixels);

        PointF px = Prefs.getTapPointPx(this, key, bounds.width(), bounds.height());

        if (px != null) {
            moveOverlayAwayFromPoint(px.x, px.y);
            dispatchTapPx(px.x, px.y,
                    () -> cb.onDone(true),
                    () -> cb.onDone(false));
            return;
        }

        PointF p = Prefs.getPoint(this, key);
        PointF target;

        if (p != null) {
            target = new PointF(
                    bounds.left + p.x * bounds.width(),
                    bounds.top + p.y * bounds.height()
            );
        } else {
            target = defaultTapPoint(key, bounds);
        }

        if (target == null) {
            cb.onDone(false);
            return;
        }

        moveOverlayAwayFromPoint(target.x, target.y);
        dispatchTapPx(
                target.x,
                target.y,
                () -> cb.onDone(true),
                () -> cb.onDone(false));
    }

    private String collectVisibleText(AccessibilityNodeInfo root) {
        StringBuilder sb = new StringBuilder();
        collectVisible(root, sb, 0);
        return sb.toString();
    }

    private void collectVisible(AccessibilityNodeInfo n, StringBuilder sb, int depth) {
        if (n == null || depth > 40) return;
        if (nodeOnScreen(n)) {
            CharSequence t = n.getText(), d = n.getContentDescription();
            if (t != null) sb.append(' ').append(t);
            if (d != null) sb.append(' ').append(d);
        }
        for (int i = 0; i < n.getChildCount(); i++) collectVisible(n.getChild(i), sb, depth + 1);
    }

    private String collectText(AccessibilityNodeInfo root) {
        StringBuilder sb = new StringBuilder();
        collect(root, sb, 0);
        return sb.toString();
    }

    private void collect(AccessibilityNodeInfo n, StringBuilder sb, int depth) {
        if (n == null || depth > 40) return;
        CharSequence t = n.getText(), d = n.getContentDescription();
        if (t != null) sb.append(' ').append(t);
        if (d != null) sb.append(' ').append(d);
        for (int i = 0; i < n.getChildCount(); i++) collect(n.getChild(i), sb, depth + 1);
    }

    private String normalize(String s) {
        if (s == null) return "";
        s = Normalizer.normalize(s, Normalizer.Form.NFKD).replaceAll("[\\u064B-\\u065F\\u0670]", "");
        return s.replace('أ','ا').replace('إ','ا').replace('آ','ا')
                .replace('ة','ه').replace('ى','ي').replace('ؤ','و').replace('ئ','ي')
                .replaceAll("\\s+", " ").trim().toLowerCase(Locale.ROOT);
    }

    private boolean contains(String all, String q) { return all.contains(normalize(q)); }

    private boolean containsAny(String all, String... qs) {
        for (String q : qs) if (contains(all, q)) return true;
        return false;
    }

    private AccessibilityNodeInfo findVisibleTextAny(AccessibilityNodeInfo root, String... qs) {
        if (root == null) return null;
        for (String q : qs) {
            AccessibilityNodeInfo n = findVisibleTextRec(root, normalize(q), 0);
            if (n != null) return n;
        }
        return null;
    }

    private AccessibilityNodeInfo findVisibleTextRec(AccessibilityNodeInfo n, String q, int depth) {
        if (n == null || depth > 40) return null;
        String s = normalize((n.getText() == null ? "" : n.getText().toString()) + " " +
                (n.getContentDescription() == null ? "" : n.getContentDescription().toString()));
        if (s.contains(q) && nodeOnScreen(n)) return n;
        for (int i = 0; i < n.getChildCount(); i++) {
            AccessibilityNodeInfo r = findVisibleTextRec(n.getChild(i), q, depth + 1);
            if (r != null) return r;
        }
        return null;
    }

    private boolean nodeOnScreen(AccessibilityNodeInfo n) {
        if (n == null || !n.isVisibleToUser()) return false;
        Rect r = new Rect();
        n.getBoundsInScreen(r);
        int w = getResources().getDisplayMetrics().widthPixels;
        int hh = getResources().getDisplayMetrics().heightPixels;
        return !r.isEmpty() && r.right > 0 && r.left < w && r.bottom > 0 && r.top < hh &&
                r.centerY() > dp(35) && r.centerY() < hh - dp(25);
    }

    private boolean isEnabledClickable(AccessibilityNodeInfo n) {
        AccessibilityNodeInfo c = n;
        for (int i = 0; i < 7 && c != null; i++, c = c.getParent()) {
            if (c.isVisibleToUser() && c.isEnabled() && c.isClickable()) return true;
        }
        return false;
    }

    private boolean clickTextAny(AccessibilityNodeInfo root, String... qs) {
        AccessibilityNodeInfo n = findVisibleTextAny(root, qs);
        return n != null && clickNode(n);
    }

    private boolean clickNode(AccessibilityNodeInfo n) {
        if (n == null) return false;
        if (System.currentTimeMillis() < actionCooldownUntil) return false;

        // Real screen tap first. WebView ACTION_CLICK often returns true
        // without actually pressing the rendered button.
        AccessibilityNodeInfo cur = n;
        for (int i = 0; i < 7 && cur != null; i++, cur = cur.getParent()) {
            Rect r = new Rect();
            cur.getBoundsInScreen(r);

            if (!r.isEmpty() && nodeOnScreen(cur) && cur.isEnabled()) {
                dispatchTapPx(r.centerX(), r.centerY(), null, null);
                return true;
            }
        }

        // Last fallback only if no visible bounds are usable.
        cur = n;
        for (int i = 0; i < 7 && cur != null; i++, cur = cur.getParent()) {
            if (cur.isClickable() && cur.isEnabled()) {
                try {
                    if (cur.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                        actionCooldownUntil = System.currentTimeMillis() + ACTION_DEBOUNCE_MS;
                        return true;
                    }
                } catch (Exception ignored) {}
            }
        }
        return false;
    }

    private void clickBestPlayButton(AccessibilityNodeInfo root) {
        AccessibilityNodeInfo n = findVisibleTextAny(root, "العب", "ابدأ", "play", "start");
        if (n != null) {
            Rect r = new Rect();
            n.getBoundsInScreen(r);
            if (r.centerY() > getResources().getDisplayMetrics().heightPixels * .45f) {
                clickNode(n);
                return;
            }
        }
        status("زر «العب» مش ظاهر بوضوح — مش هضغط مكان عشوائي");
    }

    private void tapGrayGamesArrow() {
        dispatchTapNormalized(.890f, .078f,
                () -> {
                    postMatchStage = 6;
                    status("السهم الرمادي اتضغط ✓ — أتحقق من الصفحة الرئيسية");
                },
                () -> {
                    postMatchStage = 5;
                    status("Gesture السهم اتلغى — سأعيد التحقق من صفحة الألعاب");
                });
    }

    private void swipePageDown() {
        if (System.currentTimeMillis() < actionCooldownUntil) return;
        actionCooldownUntil = System.currentTimeMillis() + ACTION_DEBOUNCE_MS;
        int w = getResources().getDisplayMetrics().widthPixels;
        int hh = getResources().getDisplayMetrics().heightPixels;
        float x = .88f * w, sy = .86f * hh, ey = .24f * hh;
        Path p = new Path();
        p.moveTo(x, sy);
        p.lineTo(x, ey);

        GestureDescription.StrokeDescription st = new GestureDescription.StrokeDescription(p, 0, 650);
        GestureDescription g = new GestureDescription.Builder().addStroke(st).build();

        boolean accepted;
        try {
            accepted = dispatchGesture(g, new GestureResultCallback() {
                @Override public void onCompleted(GestureDescription gd) {
                    super.onCompleted(gd);
                    h.post(() -> {
                        actionCooldownUntil = System.currentTimeMillis() + ACTION_DEBOUNCE_MS;
                        status("تم الـSwipe ✓ — أبحث عن الكلمة المطلوبة في الشاشة الجديدة");
                        queueScan(ACTION_DEBOUNCE_MS + 60);
                    });
                }

                @Override public void onCancelled(GestureDescription gd) {
                    super.onCancelled(gd);
                    h.post(() -> status("الـSwipe اتلغى — سأعيد البحث بدون أي كليك عشوائي"));
                }
            }, null);
        } catch (Exception e) {
            accepted = false;
        }

        if (!accepted) {
            status("Android رفض الـSwipe — بدون أي كليك بديل عشوائي");
        }
    }

    private void dispatchTapNormalized(float nx, float ny, Runnable completed, Runnable cancelled) {
        int w = getResources().getDisplayMetrics().widthPixels;
        int hh = getResources().getDisplayMetrics().heightPixels;
        dispatchTapPx(nx * w, ny * hh, completed, cancelled);
    }

    private void dispatchTapPx(float x, float y, Runnable completed, Runnable cancelled) {
        Path p = new Path();
        p.moveTo(x, y);
        p.lineTo(x + 0.5f, y + 0.5f);

        GestureDescription.StrokeDescription stroke =
                new GestureDescription.StrokeDescription(p, 0, 90);
        GestureDescription g =
                new GestureDescription.Builder().addStroke(stroke).build();

        boolean accepted;
        try {
            accepted = dispatchGesture(g, new GestureResultCallback() {
                @Override public void onCompleted(GestureDescription gestureDescription) {
                    super.onCompleted(gestureDescription);
                    if (completed != null) h.post(completed);
                }

                @Override public void onCancelled(GestureDescription gestureDescription) {
                    super.onCancelled(gestureDescription);
                    if (cancelled != null) h.post(cancelled);
                }
            }, null);
        } catch (Exception e) {
            accepted = false;
        }

        if (accepted) {
            actionCooldownUntil = System.currentTimeMillis() + ACTION_DEBOUNCE_MS;
        } else if (cancelled != null) {
            h.post(cancelled);
        }
    }

    private void setOverlayPassThrough(boolean passThrough) {
        if (floatingView == null || floatingLp == null || wm == null) return;

        int flags = floatingLp.flags;

        if (passThrough) {
            flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        } else {
            flags &= ~WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        }

        if (flags == floatingLp.flags) return;

        floatingLp.flags = flags;

        try {
            wm.updateViewLayout(floatingView, floatingLp);
        } catch (Exception ignored) {}
    }

    // ---------- UI helpers / status ----------

    private TextView ovText(String s, float size, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(size);
        t.setTextColor(color);
        t.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        if (bold) t.setTypeface(null, Typeface.BOLD);
        return t;
    }

    private TextView chip(String s, int fill) {
        TextView t = ovText(s, 10.5f, Color.rgb(218,229,245), true);
        t.setGravity(Gravity.CENTER);
        t.setBackground(round(fill, Color.rgb(45,73,103), 10));
        return t;
    }

    private Button ovButton(String s, int fill) {
        Button b = new Button(this);
        b.setAllCaps(false);
        b.setText(s);
        b.setTextSize(11);
        b.setTextColor(Color.WHITE);
        b.setPadding(dp(4), 0, dp(4), 0);
        b.setBackground(round(fill, Color.rgb(
                Math.min(255, Color.red(fill)+25),
                Math.min(255, Color.green(fill)+25),
                Math.min(255, Color.blue(fill)+25)), 10));
        return b;
    }

    private void addGap(LinearLayout l, int d) {
        Space s = new Space(this);
        l.addView(s, new LinearLayout.LayoutParams(dp(d), 1));
    }

    private GradientDrawable round(int fill, int stroke, int radius) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(dp(radius));
        g.setStroke(dp(1), stroke);
        return g;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private void status(String s) {
        if (s == null) s = "";
        lastStatus = s;
        Prefs.setLastStatus(this, s);
        refreshOverlay();
        Intent i = new Intent(BotActions.STATUS).setPackage(getPackageName());
        i.putExtra(BotActions.EXTRA_STATUS, s);
        sendBroadcast(i);
    }
}
