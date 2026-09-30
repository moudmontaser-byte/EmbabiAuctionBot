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

    private static final long ACTION_DEBOUNCE_MS = 1050L;
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
    private int[] confirmBaseline = null;

    private long actionCooldownUntil = 0L;
    private long startedAt = 0L;
    private int matchesCompleted = 0;
    private boolean stopAfterReturn = false;

    private AuctionEngine.Snapshot stableCandidate = null;
    private int stableCandidateCount = 0;

    private String lastStatus = "جاهز — افتح Embabi Games";
    private String lastDecision = "—";
    private Integer lastRating = null, lastPrice = null, lastMine = null, lastOpp = null;
    private String targetPackage = null;

    // Post-game state machine. No 5–6 second assumption: every transition is screen-driven.
    // 0 normal game, 1 find View lineups/results, 2 find Return/Main,
    // 3 wait until Games hub is positively recognized, 4 wait for actual home.
    private int postMatchStage = 0;
    private int postMatchSwipeAttempts = 0;
    private int grayArrowAttempts = 0;

    // Floating control overlay
    private WindowManager wm;
    private View floatingView;
    private WindowManager.LayoutParams floatingLp;
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
            if (running) h.postDelayed(this, 650);
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
        if (!Prefs.isCalibrated(this)) {
            status("المعايرة ناقصة — اضغط CAL وحدد 4 مربعات ثم + و Confirm");
            showFloatingOverlay(true);
            return;
        }

        AccessibilityServiceInfo info = getServiceInfo();
        if (info == null || (info.getCapabilities() & AccessibilityServiceInfo.CAPABILITY_CAN_PERFORM_GESTURES) == 0) {
            status("Gesture غير مفعّل — اقفل Accessibility للخدمة وافتحه تاني");
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
        status(paused ? "البوت محفوظ على PAUSE" : "البوت شغال — أراقب حالة الشاشة");
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
            status("قرأت الجولة من الشاشة مباشرة: " + guiRound + "/5 ✓");
        }

        // ---------- End-of-game navigation: identical safety philosophy to Hidden Player ----------
        if (postMatchStage == 0 && isPostMatchScreen(all)) {
            postMatchStage = 1;
            postMatchSwipeAttempts = 0;
            resultWaiting = false;
            cancelBidFlow();
            status("انتهت الجيم ✓ — سأدور على عرض التشكيلات/النتائج وأنزل لو مش ظاهر");
        }

        if (postMatchStage > 0) {
            handlePostMatch(root, all);
            return;
        }

        // ---------- Per-player auction result ----------
        if (isAuctionResultScreen(all)) {
            if (!resultWaiting) {
                resultWaiting = true;
                stableCandidate = null;
                stableCandidateCount = 0;
                if (awaitingConfirm || bidFlowInProgress) {
                    cancelBidFlow();
                    paused = true;
                    Prefs.setBotPaused(this, true);
                    status("ظهرت نتيجة المزاد أثناء خطوة غير مؤكدة — SAFETY PAUSE");
                    return;
                }
            }
            int round = engine == null ? Prefs.currentRound(this) : engine.getRound();
            status(round >= 5
                    ? "نتيجة المزاد الخامس ظاهرة — لا ألمس شيء، أراقب شاشة نهاية الجيم"
                    : "نتيجة المزاد ظاهرة — أراقب الشاشة حتى يظهر اللاعب التالي فعليًا");
            return;
        }

        boolean activeAuction = isAuctionActiveScreen(all);

        // We advance only when the result screen disappeared AND a new live auction screen is visible.
        if (resultWaiting && activeAuction) {
            if (guiRound != null) {
                engine.syncRoundFromScreen(guiRound);
                Prefs.setCurrentRound(this, guiRound);
                resultWaiting = false;
                stableCandidate = null;
                stableCandidateCount = 0;
                lastDecision = "جولة جديدة";
                status("ظهر مزاد اللاعب التالي ✓ — الجولة " + guiRound + "/5");
            } else if (engine != null && engine.getRound() < 5) {
                engine.advanceRoundFromScreen();
                Prefs.setCurrentRound(this, engine.getRound());
                resultWaiting = false;
                stableCandidate = null;
                stableCandidateCount = 0;
                lastDecision = "جولة جديدة";
                status("ظهر مزاد اللاعب التالي ✓ — الجولة " + engine.getRound() + "/5");
            } else {
                status("خلصت 5 مزادات — أنتظر شاشة نهاية الجيم");
                return;
            }
        }

        if (now < actionCooldownUntil) {
            status("تم تنفيذ خطوة ✓ — أتحقق من الشاشة الجديدة قبل أي لمسة أخرى");
            return;
        }

        // ---------- Safe navigation before the auction ----------
        if (!activeAuction) {
            if (containsAny(all, "هاتلي منافس", "هات لي منافس", "find opponent")) {
                status("وجدت هاتلي منافس — أضغطه");
                clickTextAny(root, "هاتلي منافس", "هات لي منافس", "find opponent");
                return;
            }

            if (containsAny(all, "اختر الكارت", "اختار الكارت", "choose card") &&
                    containsAny(all, "المزاد", "auction")) {
                status("مود المزاد جاهز — أضغط العب");
                clickBestPlayButton(root);
                return;
            }

            if (isGamesHub(all)) {
                status("صفحة الألعاب ✓ — أفتح المزاد فقط");
                clickTextAny(root, "المزاد", "auction");
                return;
            }

            if (containsAny(all, "العب الان", "العب الآن", "play now") &&
                    !containsAny(all, "تاكيد", "تأكيد", "confirm")) {
                status("الصفحة الرئيسية — أضغط العب الآن");
                clickTextAny(root, "العب الان", "العب الآن", "play now");
                return;
            }
        }

        // ---------- Turn detection ----------
        // New GUI is explicit:
        // "تأكيد المزايدة" = OUR TURN.
        // "انتظار المزايدة" = NOT OUR TURN.
        // Button color/state is only a backup signal if text is hidden from Accessibility.
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
                "خصمك يزايد", "خصمك يختار",
                "opponent turn", "opponent's turn");

        boolean myTurnText = containsAny(all,
                "دورك", "دورك الان", "دورك الآن",
                "الدور انت", "الدور: انت",
                "زايد الان", "زايد الآن", "your turn");

        // The waiting label wins over every fallback signal.
        if (waitingBidText) {
            if (awaitingConfirm) {
                cancelBidFlow();
                paused = true;
                Prefs.setBotPaused(this, true);
                status("ظهر «انتظار المزايدة» قبل Confirm — SAFETY PAUSE");
            } else {
                status("«انتظار المزايدة» = مش دورنا — أراقب فقط");
            }
            return;
        }

        // Exact label is the main source of truth.
        if (confirmBidText) {
            if (awaitingConfirm) {
                status("«تأكيد المزايدة» ظاهر ✓ — دورنا، أتحقق من Confirm");
                verifyConfirmReady(root);
            } else if (activeAuction) {
                status("«تأكيد المزايدة» = دورنا ✓ — أقرأ اللاعب والسعر");
                readAuctionSnapshot();
            }
            return;
        }

        if (opponentTurnText && !myTurnText) {
            status("دور الخصم — لا ألمس أي شيء");
            return;
        }

        if (myTurnText && activeAuction) {
            status("الدور دوري ✓ — أقرأ اللاعب والسعر");
            if (awaitingConfirm) verifyConfirmReady(root);
            else readAuctionSnapshot();
            return;
        }

        if (activeAuction) {
            // Last fallback for WebViews that expose neither Arabic label nor enabled state.
            verifyTurnByConfirmVisual(awaitingConfirm);
            return;
        }

        status("أراقب الشاشة… في انتظار حالة معروفة");
    }

    private void verifyTurnByConfirmVisual(boolean forPendingConfirm) {
        if (turnVisualBusy || screenReadBusy || screenshotBusy) return;
        if (Prefs.getPoint(this, "confirm") == null) {
            status("زر تأكيد المزايدة غير معروف — اعمل CAL مرة واحدة");
            return;
        }

        turnVisualBusy = true;
        captureBitmap(bmp -> {
            turnVisualBusy = false;

            if (bmp == null) {
                status("المزاد ظاهر لكن Screenshot غير متاح — بدون أي كليك");
                return;
            }

            boolean green = isConfirmVisuallyActive(bmp);
            bmp.recycle();

            if (green) {
                if (forPendingConfirm) {
                    status("زر تأكيد المزايدة أخضر/مفعّل ✓ — أكمل Confirm");
                    AccessibilityNodeInfo nowRoot = getRootInActiveWindow();
                    verifyConfirmReady(nowRoot);
                } else {
                    status("زر تأكيد المزايدة أخضر ✓ — ده دورنا");
                    readAuctionSnapshot();
                }
            } else {
                status("المزاد ظاهر لكن زر تأكيد المزايدة غير مفعّل — أراقب فقط");
            }
        });
    }

    private boolean isConfirmVisuallyActive(Bitmap bmp) {
        PointF p = Prefs.getPoint(this, "confirm");
        if (bmp == null || p == null) return false;

        int cx = Math.round(p.x * bmp.getWidth());
        int cy = Math.round(p.y * bmp.getHeight());

        int rx = Math.max(24, Math.round(bmp.getWidth() * .13f));
        int ry = Math.max(14, Math.round(bmp.getHeight() * .025f));

        int l = Math.max(0, cx - rx);
        int r = Math.min(bmp.getWidth() - 1, cx + rx);
        int t = Math.max(0, cy - ry);
        int b = Math.min(bmp.getHeight() - 1, cy + ry);

        int sx = Math.max(1, (r - l) / 42);
        int sy = Math.max(1, (b - t) / 16);

        int greenPixels = 0;
        int useful = 0;
        long sumR = 0, sumG = 0, sumB = 0;

        for (int y = t; y <= b; y += sy) {
            for (int x = l; x <= r; x += sx) {
                int c = bmp.getPixel(x, y);
                int rr = Color.red(c);
                int gg = Color.green(c);
                int bb = Color.blue(c);

                // Skip near-white text and very dark shadow pixels.
                if (rr > 225 && gg > 225 && bb > 225) continue;
                if (rr < 25 && gg < 25 && bb < 25) continue;

                useful++;
                sumR += rr;
                sumG += gg;
                sumB += bb;

                if (gg >= 95 && gg > rr + 28 && gg > bb + 16) greenPixels++;
            }
        }

        if (useful < 20) return false;

        double greenRatio = (double) greenPixels / useful;
        double avgR = (double) sumR / useful;
        double avgG = (double) sumG / useful;
        double avgB = (double) sumB / useful;

        // The active Confirm button in the new GUI is strongly green.
        return greenRatio >= .24 ||
                (avgG >= 95 && avgG > avgR + 24 && avgG > avgB + 12);
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
                "نهاية المباراه", "نهاية المباراة", "تقدم المباراه", "تقدم المباراة",
                "لحظات مباشره", "لحظات مباشرة", "ملخص المباراه", "ملخص المباراة",
                "رجل المباراه", "رجل المباراة", "الاهداف والكروت", "الأهداف والكروت",
                "عرض التشكيلات", "عرض النتائج", "view lineups", "view results");
    }

    private boolean isGamesHub(String all) {
        boolean title = containsAny(all, "الالعاب", "الألعاب", "games");
        boolean cards = containsAny(all, "المزاد", "auction") &&
                containsAny(all, "اللاعب الخفي", "hidden player", "العاب", "ألعاب");
        return title || cards;
    }

    private void handlePostMatch(AccessibilityNodeInfo root, String all) {
        // Highest priority at any post-match stage: if "return home" is visible, use it.
        AccessibilityNodeInfo home = findVisibleTextAny(root,
                "العوده للرئيسيه", "العودة للرئيسية",
                "العوده للصفحه الرئيسيه", "العودة للصفحة الرئيسية",
                "العوده للقائمه الرئيسيه", "العودة للقائمة الرئيسية",
                "القائمه الرئيسيه", "القائمة الرئيسية",
                "return to main", "return home", "main menu");

        if ((postMatchStage == 1 || postMatchStage == 2) && home != null) {
            status("لقيت «العودة للرئيسية» ✓ — أضغطها");
            if (clickNode(home)) {
                onMatchCompleted();
                postMatchStage = 3;
                postMatchSwipeAttempts = 0;
            }
            return;
        }

        if (postMatchStage == 1) {
            AccessibilityNodeInfo lineups = findVisibleTextAny(root,
                    "عرض التشكيلات", "عرض تشكيلات", "التشكيلات",
                    "عرض النتائج", "عرض النتيجه", "عرض النتيجة",
                    "view lineups", "view teams", "view results");

            if (lineups != null) {
                status("لقيت «عرض التشكيلات/النتائج» ✓ — أضغطه");
                if (clickNode(lineups)) {
                    postMatchStage = 2;
                    postMatchSwipeAttempts = 0;
                }
                return;
            }

            // Some versions go directly to simulation/continue without a lineups button.
            AccessibilityNodeInfo next = findVisibleTextAny(root,
                    "محاكاه المباراه", "محاكاة المباراة",
                    "ابدأ المحاكاه", "ابدأ المحاكاة",
                    "بدء المحاكاه", "بدء المحاكاة",
                    "لعب المباراه", "لعب المباراة",
                    "استمرار", "متابعه", "متابعة",
                    "مشاهده النتيجه", "مشاهدة النتيجة",
                    "simulate", "continue", "show result");

            if (next != null) {
                status("لقيت خطوة المباراة/النتيجة ✓ — أضغطها");
                if (clickNode(next)) {
                    postMatchStage = 2;
                    postMatchSwipeAttempts = 0;
                }
                return;
            }

            scrollPostMatchFor(
                    "عرض التشكيلات / النتائج / المحاكاة",
                    36
            );
            return;
        }

        if (postMatchStage == 2) {
            // After lineups, keep following only known post-game words.
            AccessibilityNodeInfo next = findVisibleTextAny(root,
                    "محاكاه المباراه", "محاكاة المباراة",
                    "ابدأ المحاكاه", "ابدأ المحاكاة",
                    "بدء المحاكاه", "بدء المحاكاة",
                    "ابدأ المباراه", "ابدأ المباراة",
                    "لعب المباراه", "لعب المباراة",
                    "استمرار", "متابعه", "متابعة",
                    "عرض النتيجه", "عرض النتيجة",
                    "مشاهده النتيجه", "مشاهدة النتيجة",
                    "simulate match", "simulate", "continue", "show result");

            if (next != null) {
                status("لقيت زر الخطوة التالية ✓ — أضغطه ثم أعيد قراءة الشاشة");
                if (clickNode(next)) {
                    postMatchSwipeAttempts = 0;
                }
                return;
            }

            // Exact Hidden-Player behaviour: if target words are not visible,
            // scroll DOWN, reread the screen, and repeat. No blind coordinate click.
            scrollPostMatchFor(
                    "العودة للرئيسية / استمرار / عرض النتيجة",
                    42
            );
            return;
        }

        if (postMatchStage == 3) {
            if (containsAny(all, "العب الان", "العب الآن", "play now")) {
                finishReturnCycle(root);
                return;
            }

            if (isGamesHub(all)) {
                status("وصلت صفحة الألعاب ✓ — الآن فقط أضغط السهم الرمادي");
                grayArrowAttempts++;
                tapGrayGamesArrow();
            } else {
                status("ضغطت الرئيسية ✓ — أراقب لحد ما أشوف صفحة الألعاب فعليًا");
            }
            return;
        }

        if (postMatchStage == 4) {
            if (containsAny(all, "العب الان", "العب الآن", "play now")) {
                finishReturnCycle(root);
                return;
            }

            if (isGamesHub(all) && grayArrowAttempts < 4) {
                status("صفحة الألعاب ما زالت ظاهرة — أعيد السهم بعد تحقق الشاشة");
                grayArrowAttempts++;
                tapGrayGamesArrow();
                return;
            }

            status("السهم اتضغط — أراقب لحد ما الصفحة الرئيسية تظهر");
        }
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
        status("الرئيسية جاهزة ✓ — أبدأ جيم مزاد جديدة");
        clickTextAny(root, "العب الان", "العب الآن", "play now");
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

    private void readAuctionSnapshot() {
        if (screenReadBusy || bidFlowInProgress) return;
        if (!Prefs.isCalibrated(this)) {
            paused = true;
            Prefs.setBotPaused(this, true);
            status("المعايرة ناقصة — SAFETY PAUSE");
            return;
        }

        screenReadBusy = true;
        captureBitmap(bmp -> {
            if (bmp == null) {
                screenReadBusy = false;
                stableCandidate = null;
                stableCandidateCount = 0;
                status("تعذر أخذ Screenshot — لا قرار ولا كليك");
                return;
            }

            final Integer[] vals = new Integer[4];
            final int[] done = {0};

            IntResult[] sinks = new IntResult[4];
            for (int i = 0; i < 4; i++) {
                final int index = i;
                sinks[i] = value -> {
                    vals[index] = value;
                    done[0]++;
                    if (done[0] == 4) {
                        bmp.recycle();
                        screenReadBusy = false;
                        handleSnapshot(vals[0], vals[1], vals[2], vals[3]);
                    }
                };
            }

            ocrRect(bmp, Prefs.getRegion(this, "rating"), true, sinks[0]);
            ocrRect(bmp, Prefs.getRegion(this, "price"), false, sinks[1]);
            ocrRect(bmp, Prefs.getRegion(this, "mine"), false, sinks[2]);
            ocrRect(bmp, Prefs.getRegion(this, "opponent"), false, sinks[3]);
        });
    }

    private void ocrRect(Bitmap base, RectF n, boolean ratingMode, IntResult cb) {
        if (base == null || n == null) {
            cb.accept(null);
            return;
        }

        // A tiny padding protects against a calibration box cutting off one digit.
        float px = n.width() * .04f;
        float py = n.height() * .08f;
        float nl = Math.max(0f, n.left - px);
        float nt = Math.max(0f, n.top - py);
        float nr = Math.min(1f, n.right + px);
        float nb = Math.min(1f, n.bottom + py);

        int l = Math.max(0, Math.min(base.getWidth() - 1, Math.round(nl * base.getWidth())));
        int t = Math.max(0, Math.min(base.getHeight() - 1, Math.round(nt * base.getHeight())));
        int r = Math.max(l + 1, Math.min(base.getWidth(), Math.round(nr * base.getWidth())));
        int b = Math.max(t + 1, Math.min(base.getHeight(), Math.round(nb * base.getHeight())));

        Bitmap crop;
        try {
            crop = Bitmap.createBitmap(base, l, t, r - l, b - t);
        } catch (Exception e) {
            cb.accept(null);
            return;
        }

        int scale = ratingMode ? 4 : 3;
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

        recognizePrepared(enlarged, ratingMode, cb, true);
    }

    private void recognizePrepared(Bitmap image, boolean ratingMode, IntResult cb, boolean allowContrastFallback) {
        recognizer.process(InputImage.fromBitmap(image, 0))
                .addOnSuccessListener(tx -> {
                    Integer value = ratingMode ? extractRating(tx.getText()) : extractMoney(tx.getText());

                    if (value != null || !allowContrastFallback) {
                        image.recycle();
                        cb.accept(value);
                        return;
                    }

                    Bitmap high = makeHighContrast(image);
                    image.recycle();

                    if (high == null) {
                        cb.accept(null);
                        return;
                    }

                    recognizer.process(InputImage.fromBitmap(high, 0))
                            .addOnSuccessListener(tx2 -> {
                                Integer v2 = ratingMode ? extractRating(tx2.getText()) : extractMoney(tx2.getText());
                                high.recycle();
                                cb.accept(v2);
                            })
                            .addOnFailureListener(e2 -> {
                                high.recycle();
                                cb.accept(null);
                            });
                })
                .addOnFailureListener(e -> {
                    image.recycle();
                    cb.accept(null);
                });
    }

    private Bitmap makeHighContrast(Bitmap src) {
        try {
            int w = src.getWidth();
            int hgt = src.getHeight();
            int[] pixels = new int[w * hgt];
            src.getPixels(pixels, 0, w, 0, 0, w, hgt);

            long sum = 0;
            for (int c : pixels) {
                int y = (Color.red(c) * 299 + Color.green(c) * 587 + Color.blue(c) * 114) / 1000;
                sum += y;
            }

            int avg = pixels.length == 0 ? 140 : (int) (sum / pixels.length);
            int threshold = Math.max(105, Math.min(190, avg + 18));

            for (int i = 0; i < pixels.length; i++) {
                int c = pixels[i];
                int y = (Color.red(c) * 299 + Color.green(c) * 587 + Color.blue(c) * 114) / 1000;
                pixels[i] = y >= threshold ? Color.WHITE : Color.BLACK;
            }

            Bitmap out = Bitmap.createBitmap(w, hgt, Bitmap.Config.ARGB_8888);
            out.setPixels(pixels, 0, w, 0, 0, w, hgt);
            return out;
        } catch (Exception ignored) {
            return null;
        }
    }

    private void handleSnapshot(Integer rating, Integer price, Integer mine, Integer opp) {
        boolean valid = rating != null && rating >= 50 && rating <= 99 &&
                price != null && price >= 0 && price <= 100 &&
                mine != null && mine >= 0 && mine <= 100 &&
                opp != null && opp >= 0 && opp <= 100;

        if (!valid) {
            stableCandidate = null;
            stableCandidateCount = 0;
            status("OCR غير مؤكد: OVR " + show(rating) + " | Price " + show(price) +
                    " | You " + show(mine) + " | Opp " + show(opp) + " — لا ألمس الشاشة");
            return;
        }

        AuctionEngine.Snapshot s = new AuctionEngine.Snapshot(rating, price, mine, opp, true);

        if (stableCandidate != null && s.sameValues(stableCandidate)) {
            stableCandidateCount++;
        } else {
            stableCandidate = s;
            stableCandidateCount = 1;
        }

        lastRating = rating;
        lastPrice = price;
        lastMine = mine;
        lastOpp = opp;
        refreshOverlay();

        if (stableCandidateCount < 2) {
            status("قراءة 1/2: OVR " + rating + " | " + price + "M | You " + mine + " | Opp " + opp + " — أتحقق مرة ثانية");
            return;
        }

        stableCandidateCount = 0;
        stableCandidate = null;

        AuctionEngine.Decision d = engine.onStableSnapshot(s);
        lastDecision = d.action.name();
        Prefs.setCurrentRound(this, engine.getRound());
        Prefs.saveLastSnapshot(this, rating, price, mine, opp, lastDecision);

        String slot = AuctionEngine.SLOT_NAMES[Math.max(0, Math.min(4, d.round - 1))];
        String base = "R" + d.round + "/5 " + slot +
                " • OVR " + rating +
                " • " + price + "M" +
                " • You " + mine + "M" +
                " • Opp " + opp + "M" +
                "\nOpp type: " + d.opponentStyle +
                " " + d.opponentConfidence + "%";

        if (d.action == AuctionEngine.Action.BID) {
            status(base + "\nBID +1 • cap " + d.hardCap + "M • " + d.reason);
            executeBid(s);
        } else if (d.action == AuctionEngine.Action.PASS) {
            status(base + "\nPASS — سيبه للخصم/Free Player • " + d.reason);
        } else if (d.action == AuctionEngine.Action.SAFETY_PAUSE) {
            paused = true;
            Prefs.setBotPaused(this, true);
            status(base + "\nSAFETY PAUSE — " + d.reason);
        } else if (d.action == AuctionEngine.Action.COMPLETE) {
            status("خلصت 5 مزادات — أراقب نهاية الجيم");
        } else {
            status(base + "\nWAIT — " + d.reason);
        }
        refreshOverlay();
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

    private void executeBid(AuctionEngine.Snapshot s) {
        if (bidFlowInProgress || awaitingConfirm) return;

        bidFlowInProgress = true;
        engine.markOwnBidStarted(s.price);
        status("قرار BID +1 ✓ — آخذ بصمة زر Confirm قبل الضغط على +");

        captureBitmap(bmp -> {
            if (bmp == null) {
                engine.cancelPending();
                bidFlowInProgress = false;
                paused = true;
                Prefs.setBotPaused(this, true);
                status("لم أقدر أتحقق بصريًا قبل + — SAFETY PAUSE");
                return;
            }

            confirmBaseline = fingerprintAtSavedPoint(bmp, "confirm");
            bmp.recycle();

            clickPlusSmart(ok -> {
                if (!ok) {
                    engine.cancelPending();
                    bidFlowInProgress = false;
                    paused = true;
                    Prefs.setBotPaused(this, true);
                    status("زر + لم يتأكد أنه اتضغط — SAFETY PAUSE");
                    return;
                }

                bidFlowInProgress = false;
                awaitingConfirm = true;
                confirmVerifyCount = 0;
                actionCooldownUntil = System.currentTimeMillis() + ACTION_DEBOUNCE_MS;
                status("زر + اتضغط ✓ — لن أضغط Confirm إلا بعد ما الشاشة تثبت أنه اتفعل");
                queueScan(ACTION_DEBOUNCE_MS + 60);
            });
        });
    }

    private void verifyConfirmReady(AccessibilityNodeInfo root) {
        confirmVerifyCount++;

        AccessibilityNodeInfo c = findVisibleTextAny(root,
                "confirm", "confirm bid", "place bid", "تأكيد", "تاكيد", "تأكيد المزايدة", "تاكيد المزايده");

        if (c != null && isEnabledClickable(c)) {
            status("Confirm أصبح Enabled ✓ — أضغطه مرة واحدة");
            clickConfirmAndFinalize();
            return;
        }

        captureBitmap(bmp -> {
            if (bmp == null) {
                if (confirmVerifyCount >= 12) pauseConfirmUnverified();
                else status("أتحقق من Confirm… Screenshot غير متاح الآن، بدون كليك");
                return;
            }

            int[] now = fingerprintAtSavedPoint(bmp, "confirm");
            bmp.recycle();
            int diff = colorDifference(confirmBaseline, now);

            if (confirmBaseline != null && now != null && diff >= 18) {
                status("شكل/لون Confirm اتغير بعد + ✓ — أضغط Confirm");
                clickConfirmAndFinalize();
                return;
            }

            if (confirmVerifyCount >= 12) {
                pauseConfirmUnverified();
            } else {
                status("Plus اتضغط، لكن Confirm لسه مش متأكد (" + confirmVerifyCount + "/12) — لا أضغط");
            }
        });
    }

    private void pauseConfirmUnverified() {
        engine.cancelPending();
        awaitingConfirm = false;
        bidFlowInProgress = false;
        paused = true;
        Prefs.setBotPaused(this, true);
        status("Plus اتضغط لكن Confirm لم يتم التحقق منه — SAFETY PAUSE بدل أي كليك غلط");
    }

    private void clickConfirmAndFinalize() {
        if (!awaitingConfirm) return;
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
            confirmVerifyCount = 0;
            confirmBaseline = null;
            actionCooldownUntil = System.currentTimeMillis() + ACTION_DEBOUNCE_MS;
            status("Confirm اتضغط ✓ — أراقب السعر/الميزانية على الشاشة للتأكد من المزايدة");
            queueScan(ACTION_DEBOUNCE_MS + 60);
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
        calibrationSave = calibrationButton(calibrationStep < 4 ? "حفظ / التالي ✓" : "المس الزر على الشاشة", Color.rgb(31,149,255));

        cancel.setOnClickListener(v -> {
            removeCalibration();
            showFloatingOverlay(true);
            status("تم إلغاء المعايرة");
        });

        calibrationSave.setOnClickListener(v -> {
            if (calibrationStep >= 4) return;
            RectF n = calibrationView.getNormalizedSelection();
            if (n == null || n.width() < .015f || n.height() < .012f) {
                Toast.makeText(this, "ارسم مربعًا واضحًا حول الرقم فقط", Toast.LENGTH_SHORT).show();
                return;
            }
            String key = new String[]{"rating","price","mine","opponent"}[calibrationStep];
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
            case 0: return "1/6 — ارسم مربعًا حول Rating اللاعب فقط";
            case 1: return "2/6 — ارسم مربعًا حول Current Auction Price فقط";
            case 2: return "3/6 — ارسم مربعًا حول ميزانيتك فقط";
            case 3: return "4/6 — ارسم مربعًا حول ميزانية الخصم فقط";
            case 4: return "5/6 — المس منتصف زر + مرة واحدة";
            default: return "6/6 — المس منتصف زر Confirm مرة واحدة";
        }
    }

    private void onCalibrationPoint(float x, float y) {
        if (calibrationStep < 4 || calibrationStep > 5) return;
        int w = getResources().getDisplayMetrics().widthPixels;
        int hh = getResources().getDisplayMetrics().heightPixels;
        String key = calibrationStep == 4 ? "plus" : "confirm";
        Prefs.savePoint(this, key, x / Math.max(1f, w), y / Math.max(1f, hh));
        calibrationStep++;

        if (calibrationStep > 5) {
            removeCalibration();
            showFloatingOverlay(true);
            status(Prefs.isCalibrated(this)
                    ? "المعايرة READY ✓ — شغّل TEST OCR ثم START"
                    : "المعايرة غير مكتملة — أعد CAL");
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

            if (calibrationStep < 4) {
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
                c.drawText(calibrationStep == 4 ? "TAP +" : "TAP CONFIRM",
                        dp(18), dp(110), label);
            }
        }

        @Override public boolean onTouchEvent(MotionEvent e) {
            float x = Math.max(0, Math.min(getWidth(), e.getX()));
            float y = Math.max(dp(82), Math.min(getHeight()-dp(76), e.getY()));

            if (calibrationStep >= 4) {
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

    private void testOcr() {
        if (!Prefs.isCalibrated(this)) {
            status("TEST OCR: المعايرة ناقصة");
            return;
        }

        status("TEST OCR… أقرأ الأربع مربعات");
        screenReadBusy = true;
        captureBitmap(bmp -> {
            if (bmp == null) {
                screenReadBusy = false;
                status("TEST OCR: تعذر أخذ Screenshot");
                return;
            }

            final Integer[] vals = new Integer[4];
            final int[] done = {0};
            IntResult[] sinks = new IntResult[4];
            for (int i = 0; i < 4; i++) {
                final int index = i;
                sinks[i] = value -> {
                    vals[index] = value;
                    done[0]++;
                    if (done[0] == 4) {
                        bmp.recycle();
                        screenReadBusy = false;
                        status("TEST OCR ✓  OVR " + show(vals[0]) +
                                " | Price " + show(vals[1]) +
                                " | You " + show(vals[2]) +
                                " | Opp " + show(vals[3]));
                    }
                };
            }

            ocrRect(bmp, Prefs.getRegion(this, "rating"), true, sinks[0]);
            ocrRect(bmp, Prefs.getRegion(this, "price"), false, sinks[1]);
            ocrRect(bmp, Prefs.getRegion(this, "mine"), false, sinks[2]);
            ocrRect(bmp, Prefs.getRegion(this, "opponent"), false, sinks[3]);
        });
    }

    // ---------- Floating overlay ----------

    private void showFloatingOverlay(boolean expanded) {
        overlayExpanded = expanded;
        if (wm == null) wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        removeFloatingOverlay();

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(4), dp(3), dp(4), dp(4));
        box.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        box.setBackground(round(Color.argb(216,7,18,32), Color.rgb(91,82,225), 11));

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);

        TextView title = ovText("◈ AUCTION", 9.7f, Color.WHITE, true);
        title.setGravity(Gravity.LEFT | Gravity.CENTER_VERTICAL);

        ovRunState = ovText("", 8.2f, Color.rgb(0,230,150), true);
        ovRunState.setGravity(Gravity.CENTER);

        TextView collapse = ovText(expanded ? "−" : "☰", 15, Color.rgb(160,186,255), true);
        collapse.setGravity(Gravity.CENTER);
        TextView close = ovText("×", 16, Color.rgb(255,105,125), true);
        close.setGravity(Gravity.CENTER);

        head.addView(title, new LinearLayout.LayoutParams(0, dp(23), 1.0f));
        head.addView(ovRunState, new LinearLayout.LayoutParams(0, dp(23), 1.25f));
        head.addView(collapse, new LinearLayout.LayoutParams(dp(25), dp(23)));
        head.addView(close, new LinearLayout.LayoutParams(dp(25), dp(23)));
        box.addView(head);

        if (expanded) {
            ovStatus = ovText(lastStatus, 7.8f, Color.rgb(207,219,239), false);
            ovStatus.setSingleLine(true);
            ovStatus.setEllipsize(android.text.TextUtils.TruncateAt.END);
            box.addView(ovStatus, new LinearLayout.LayoutParams(-1, dp(18)));

            LinearLayout metrics = new LinearLayout(this);
            metrics.setOrientation(LinearLayout.HORIZONTAL);
            metrics.setPadding(0, dp(2), 0, 0);

            ovRating = chip("O" + show(lastRating), Color.rgb(15,45,67));
            ovPrice = chip("€" + show(lastPrice), Color.rgb(15,45,67));
            ovBudgets = chip("Y" + show(lastMine) + "/O" + show(lastOpp), Color.rgb(15,45,67));
            ovDecision = chip(lastDecision, Color.rgb(15,45,67));

            metrics.addView(ovRating, new LinearLayout.LayoutParams(0, dp(24), 1));
            addGap(metrics, 2);
            metrics.addView(ovPrice, new LinearLayout.LayoutParams(0, dp(24), 1));
            addGap(metrics, 2);
            metrics.addView(ovBudgets, new LinearLayout.LayoutParams(0, dp(24), 1.55f));
            addGap(metrics, 2);
            metrics.addView(ovDecision, new LinearLayout.LayoutParams(0, dp(24), 1.15f));
            box.addView(metrics);

            LinearLayout players = new LinearLayout(this);
            players.setOrientation(LinearLayout.HORIZONTAL);
            players.setPadding(0, dp(3), 0, 0);

            String[] pNames = {"GK", "DF", "C1", "C2", "ST"};
            int activeSlot = Math.max(0, Math.min(4, currentRound() - 1));

            for (int i = 0; i < 5; i++) {
                String text = pNames[i] + "\n" + Prefs.getMinRating(this, i);
                int fill = i == activeSlot ? Color.rgb(78,58,180) : Color.rgb(15,45,67);
                TextView p = chip(text, fill);
                p.setTextSize(7.8f);
                p.setGravity(Gravity.CENTER);
                ovPlayerMins[i] = p;
                players.addView(p, new LinearLayout.LayoutParams(0, dp(29), 1));
                if (i < 4) addGap(players, 2);
            }
            box.addView(players);

            LinearLayout controls = new LinearLayout(this);
            controls.setOrientation(LinearLayout.HORIZONTAL);
            controls.setPadding(0, dp(3), 0, 0);

            Button start = ovButton("▶", Color.rgb(0,191,120));
            Button pause = ovButton("Ⅱ", Color.rgb(26,55,82));
            Button stop = ovButton("■", Color.rgb(105,24,42));
            Button cal = ovButton("CAL", Color.rgb(25,133,220));
            Button test = ovButton("OCR", Color.rgb(34,58,86));

            start.setOnClickListener(v -> startBot(true));
            pause.setOnClickListener(v -> togglePause());
            stop.setOnClickListener(v -> stopBot("تم إيقاف البوت يدويًا"));
            cal.setOnClickListener(v -> startCalibration());
            test.setOnClickListener(v -> testOcr());

            controls.addView(start, new LinearLayout.LayoutParams(0, dp(27), 1));
            addGap(controls, 2);
            controls.addView(pause, new LinearLayout.LayoutParams(0, dp(27), 1));
            addGap(controls, 2);
            controls.addView(stop, new LinearLayout.LayoutParams(0, dp(27), 1));
            addGap(controls, 2);
            controls.addView(cal, new LinearLayout.LayoutParams(0, dp(27), 1));
            addGap(controls, 2);
            controls.addView(test, new LinearLayout.LayoutParams(0, dp(27), 1));
            box.addView(controls);
        } else {
            ovStatus = ovText(shortStatus(), 8.8f, Color.rgb(205,219,241), true);
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
        int compactWidth = Math.max(dp(150), Math.round(screenWidth * .50f));

        floatingLp = new WindowManager.LayoutParams(
                compactWidth, -2,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);

        floatingLp.gravity = Gravity.TOP | Gravity.START;
        floatingLp.x = dp(4);
        floatingLp.y = dp(58);

        final float[] touch = new float[4];
        title.setOnTouchListener((v, e) -> {
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

                try {
                    wm.updateViewLayout(floatingView, floatingLp);
                } catch (Exception ignored) {}
                return true;
            }

            return true;
        });

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
        if (clickPlusNode()) {
            actionCooldownUntil = System.currentTimeMillis() + ACTION_DEBOUNCE_MS;
            cb.onDone(true);
            return;
        }
        tapSavedPoint("plus", cb);
    }

    private void clickConfirmSmart(Callback cb) {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        AccessibilityNodeInfo n = findVisibleTextAny(root,
                "confirm", "confirm bid", "place bid", "تأكيد", "تاكيد", "تأكيد المزايدة", "تاكيد المزايده");
        if (n != null && clickNode(n)) {
            actionCooldownUntil = System.currentTimeMillis() + ACTION_DEBOUNCE_MS;
            cb.onDone(true);
            return;
        }
        tapSavedPoint("confirm", cb);
    }

    private boolean clickPlusNode() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return false;
        AccessibilityNodeInfo n = findPlusRec(root, 0);
        return n != null && clickNode(n);
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

    private void tapSavedPoint(String key, Callback cb) {
        PointF p = Prefs.getPoint(this, key);
        if (p == null) {
            cb.onDone(false);
            return;
        }
        int w = getResources().getDisplayMetrics().widthPixels;
        int hh = getResources().getDisplayMetrics().heightPixels;
        dispatchTapPx(p.x * w, p.y * hh,
                () -> {
                    actionCooldownUntil = System.currentTimeMillis() + ACTION_DEBOUNCE_MS;
                    cb.onDone(true);
                },
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
        AccessibilityNodeInfo cur = n;
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

        Rect r = new Rect();
        n.getBoundsInScreen(r);
        if (!r.isEmpty() && nodeOnScreen(n)) {
            dispatchTapPx(r.centerX(), r.centerY(), null, null);
            return true;
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
        dispatchTapNormalized(.5f, .88f, null, null);
    }

    private void tapGrayGamesArrow() {
        dispatchTapNormalized(.890f, .078f,
                () -> {
                    postMatchStage = 4;
                    status("السهم الرمادي اتضغط ✓ — أتحقق من الصفحة التالية");
                },
                () -> {
                    postMatchStage = 3;
                    status("Gesture السهم اتلغى — لن أكمل إلا بعد إعادة التحقق");
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
        if (System.currentTimeMillis() < actionCooldownUntil) {
            if (cancelled != null) h.post(cancelled);
            return;
        }
        actionCooldownUntil = System.currentTimeMillis() + ACTION_DEBOUNCE_MS;
        Path p = new Path();
        p.moveTo(x, y);
        p.lineTo(x + .5f, y + .5f);
        GestureDescription.StrokeDescription stroke = new GestureDescription.StrokeDescription(p, 0, 90);
        GestureDescription g = new GestureDescription.Builder().addStroke(stroke).build();

        boolean accepted;
        try {
            accepted = dispatchGesture(g, new GestureResultCallback() {
                @Override public void onCompleted(GestureDescription gestureDescription) {
                    super.onCompleted(gestureDescription);
                    actionCooldownUntil = System.currentTimeMillis() + ACTION_DEBOUNCE_MS;
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

        if (!accepted && cancelled != null) h.post(cancelled);
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
