package com.embabi.auctionflex;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.accessibilityservice.GestureDescription;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.PointF;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.Space;
import android.widget.TextView;
import android.widget.Toast;

import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class AuctionAccessibilityService extends AccessibilityService {
    private final Handler h=new Handler(Looper.getMainLooper());
    private final TextRecognizer recognizer=TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);

    private boolean running=false, paused=false, screenshotBusy=false, actionBusy=false;
    private long actionCooldownUntil=0L;
    private String targetPackage=null;
    private int round=1, matches=0;
    private long startedAt=0L;
    private boolean stopAfterReturn=false;

    private enum Pending { NONE, CONFIRM, SKIP }
    private Pending pending=Pending.NONE;
    private int pendingRound=0, pendingRetries=0;
    private long pendingAt=0L;

    // A new player starts with a forced opening round. In that phase the game
    // disables "Skip Player" and will auto-submit 1M when the timer expires.
    // If our rating rules say SKIP, remember that decision and wait until the
    // button is genuinely enabled instead of tapping a disabled control.
    private boolean skipWhenAllowed=false;
    private int skipWhenAllowedRound=0;

    private int lastRating=-1,lastPrice=-1,lastMax=-1;
    private int verifyLastObservedPrice=-1, verifyStallCount=0;
    private String lastPosition="—", lastStatus="جاهز";
    private boolean postFlow=false;
    private int postStage=0, scrollAttempts=0;

    private WindowManager wm;
    private View floatingView;
    private WindowManager.LayoutParams floatingLp;
    private TextView ovStatus,ovInfo,ovState;
    private int savedX=Integer.MIN_VALUE,savedY=Integer.MIN_VALUE;

    private View calibrationRoot;
    private CalibrationView calibrationView;
    private TextView calibrationInstruction;
    private int calibrationStep=0;

    // Quality / stability state. Decisions are made only after repeated agreement.
    private boolean auctionActive=false;
    private long lastProgressAt=System.currentTimeMillis();
    private long lastActionAt=System.currentTimeMillis();
    private long lastRecoveryCheckAt=0L;
    private final int[] sampleRating={-1,-1,-1};
    private final int[] samplePrice={-1,-1,-1};
    private final int[] sampleRound={-1,-1,-1};
    private final String[] samplePosition={"","",""};
    private int sampleIndex=0, sampleCount=0;


    private final Runnable monitor=new Runnable() {
        @Override public void run() {
            Prefs.setHeartbeat(AuctionAccessibilityService.this,System.currentTimeMillis());
            if(running && !paused) analyze();
            h.postDelayed(this,currentPollDelay());
        }
    };

    private final BroadcastReceiver receiver=new BroadcastReceiver() {
        @Override public void onReceive(Context c,Intent i) {
            String a=i.getAction();
            if(BotActions.SHOW_OVERLAY.equals(a)) showOverlay();
            else if(BotActions.CALIBRATE.equals(a)) startCalibration();
            else if(BotActions.START.equals(a)) startBot();
            else if(BotActions.PAUSE.equals(a)) togglePause();
            else if(BotActions.STOP.equals(a)) stopBot("تم الإيقاف");
        }
    };

    @Override protected void onServiceConnected() {
        super.onServiceConnected();
        wm=(WindowManager)getSystemService(WINDOW_SERVICE);
        IntentFilter f=new IntentFilter();
        f.addAction(BotActions.SHOW_OVERLAY); f.addAction(BotActions.CALIBRATE);
        f.addAction(BotActions.START); f.addAction(BotActions.PAUSE); f.addAction(BotActions.STOP);
        if(Build.VERSION.SDK_INT>=33) registerReceiver(receiver,f,Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(receiver,f);
        round=Prefs.currentRound(this);
        matches=Prefs.matches(this);
        startedAt=Prefs.startedAt(this);
        lastStatus=Prefs.status(this);
        showOverlay();
        h.removeCallbacks(monitor);
        h.post(monitor);
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        if(running && !paused && System.currentTimeMillis()>=actionCooldownUntil) {
            h.removeCallbacks(scanOnce);
            h.postDelayed(scanOnce,120);
        }
    }

    private final Runnable scanOnce=()->{ if(running&&!paused) analyze(); };

    @Override public void onInterrupt() {}

    @Override public void onDestroy() {
        try{unregisterReceiver(receiver);}catch(Exception ignored){}
        h.removeCallbacksAndMessages(null);
        removeOverlay();
        removeCalibration();
        recognizer.close();
        super.onDestroy();
    }

    private void startBot() {
        if(!Prefs.isCalibrated(this)) {
            running=false; paused=false;
            status("اعمل CAL 5/5 أولاً");
            showOverlay();
            return;
        }
        AccessibilityServiceInfo info=getServiceInfo();
        if(info==null || (info.getCapabilities()&AccessibilityServiceInfo.CAPABILITY_CAN_PERFORM_GESTURES)==0) {
            status("Accessibility لا يسمح بالضغط — أعد تفعيل الخدمة");
            return;
        }
        running=true; paused=false; targetPackage=null;
        round=1; matches=0; stopAfterReturn=false; postFlow=false; postStage=0;
        skipWhenAllowed=false; skipWhenAllowedRound=0;
        pending=Pending.NONE; pendingRetries=0; actionBusy=false;
        skipWhenAllowed=false; skipWhenAllowedRound=0;
        startedAt=System.currentTimeMillis();
        lastProgressAt=startedAt;
        lastActionAt=startedAt;
        lastRecoveryCheckAt=0L;
        auctionActive=false;
        resetCardConsensus();
        Prefs.setCurrentRound(this,1); Prefs.setMatches(this,0); Prefs.setStartedAt(this,startedAt);
        status("RUN — افتح Embabi Games، سأتعرف على الشاشة تلقائياً");
        showOverlay();
        analyze();
    }

    private void togglePause() {
        if(!running) { startBot(); return; }
        paused=!paused;
        status(paused?"PAUSE — لن ألمس الشاشة":"RUN — رجعت أراقب");
        if(!paused) analyze();
    }

    private void stopBot(String s) {
        running=false; paused=false; actionBusy=false; pending=Pending.NONE;
        status(s);
    }


    private long currentPollDelay() {
        if(!running || paused) return 900;
        if(actionBusy || screenshotBusy) return 180;
        if(auctionActive) return 260;
        if(postFlow && postStage==4) return 1050; // simulation: no need to burn CPU
        if(postFlow) return 600;
        return 700;
    }

    private void markProgress() {
        lastProgressAt=System.currentTimeMillis();
    }

    private void markAction() {
        lastActionAt=System.currentTimeMillis();
    }

    private void resetCardConsensus() {
        sampleIndex=0;
        sampleCount=0;
        for(int i=0;i<3;i++) {
            sampleRating[i]=-1;
            samplePrice[i]=-1;
            sampleRound[i]=-1;
            samplePosition[i]="";
        }
    }

    private void resetVisiblePlayerRead() {
        lastRating=-1;
        lastPrice=-1;
        lastMax=-1;
        lastPosition="—";
        resetCardConsensus();
        refreshOverlay();
    }

    private CardRead addConsensusSample(int rating,String pos,int price,int sampleR) {
        sampleRating[sampleIndex]=rating;
        samplePrice[sampleIndex]=price;
        sampleRound[sampleIndex]=sampleR;
        samplePosition[sampleIndex]=pos==null?"":pos;
        sampleIndex=(sampleIndex+1)%3;
        if(sampleCount<3) sampleCount++;

        for(int i=0;i<sampleCount;i++) {
            int votes=0;
            for(int j=0;j<sampleCount;j++) {
                if(sampleRating[i]==sampleRating[j] &&
                        samplePrice[i]==samplePrice[j] &&
                        sampleRound[i]==sampleRound[j] &&
                        samplePosition[i].equals(samplePosition[j])) {
                    votes++;
                }
            }
            if(votes>=2) {
                CardRead out=new CardRead();
                out.rating=sampleRating[i];
                out.position=samplePosition[i];
                out.price=samplePrice[i];
                return out;
            }
        }
        return null;
    }

    private void analyze() {
        long now=System.currentTimeMillis();
        if(actionBusy && now-lastActionAt>=50_000L) {
            actionBusy=false;
            clearPending();
            resetCardConsensus();
        }
        if(screenshotBusy||actionBusy||now<actionCooldownUntil) return;
        AccessibilityNodeInfo root=getRootInActiveWindow();
        if(root==null) { status("أنتظر شاشة اللعبة…"); return; }

        CharSequence pkgCs=root.getPackageName();
        String pkg=pkgCs==null?"":pkgCs.toString();
        if(pkg.equals(getPackageName()) || pkg.contains("systemui") || pkg.contains("launcher")) {
            status("البوت شغال — افتح Embabi Games");
            return;
        }
        if(targetPackage==null || targetPackage.isEmpty()) targetPackage=pkg;
        if(!pkg.equals(targetPackage)) { status("البوت شغال — ارجع للعبة"); return; }

        String all=normalize(collectText(root));
        auctionActive=isAuctionScreen(all);

        // Conservative watchdog: resync state without blind taps if a state is stale.
        if(auctionActive && System.currentTimeMillis()-lastProgressAt>25_000L) {
            clearPending();
            resetCardConsensus();
            lastProgressAt=System.currentTimeMillis();
            status("إعادة مزامنة آمنة للمزاد — أقرأ الحالة من جديد بدون ضغط عشوائي");
        }
        Integer guiRound=extractRound(all);
        if(guiRound!=null && guiRound>=1 && guiRound<=5) {
            if(guiRound!=round) {
                round=guiRound;
                Prefs.setCurrentRound(this,round);
                if(pending!=Pending.NONE && guiRound!=pendingRound) clearPending();
                resetVisiblePlayerRead();
                markProgress();
            }
        }

        // 50-second inactivity watchdog. Re-read the CURRENT screen instead of
        // trusting a stale post-match state. The most important recovery is the
        // home screen: if "العب الآن" is visible, clear stale end-game state and
        // enter the next game immediately.
        if(now-lastActionAt>=50_000L && now-lastRecoveryCheckAt>=5_000L) {
            lastRecoveryCheckAt=now;
            clearPending();
            resetCardConsensus();

            if(containsAny(all,"العب الان","العب الآن","play now")) {
                postFlow=false;
                postStage=0;
                scrollAttempts=0;
                auctionActive=false;
                skipWhenAllowed=false;
                skipWhenAllowedRound=0;
                round=1;
                Prefs.setCurrentRound(this,1);

                if(stopAfterReturn) {
                    stopBot("اكتملت المدة/العدد ✓ — رجعنا للرئيسية");
                    return;
                }

                status("50 ثانية بدون أكشن — لقيت «العب الآن» وضغطتها");
                if(clickText(root,"العب الان","العب الآن","play now")) {
                    waitTwoSecondsAfterPlayNow();
                    return;
                }
            }
        }

        if(handlePostFlow(root,all)) { auctionActive=false; return; }

        if(auctionActive) {
            handleAuction(root,all,guiRound);
            return;
        }

        // Main navigation, always text/state based.
        if(containsAny(all,"هاتلي منافس","هات لي منافس","find opponent")) {
            status("هاتلي منافس ✓");
            clickText(root,"هاتلي منافس","هات لي منافس","find opponent");
            return;
        }

        if(isAuctionSetup(all)) {
            if(clickBottomPlay(root)) {
                status("إعداد المزاد ✓ — ضغطت «العب»");
            } else {
                status("إعداد المزاد — أنزل لحد زر «العب»");
                swipeDown();
            }
            return;
        }

        // Safety: if a wrong game (مثل «أنت هتحور؟») was opened by mistake,
        // immediately go back to the games hub and retry the auction card.
        if(!isGamesHub(all) && containsAny(all,
                "أنت هتحور","انت هتحور",
                "بحور ولا منجورش","بحور ولا منجورش؟",
                "you are bluffing","bluff")) {
            status("دخل لعبة غلط — أرجع وأختار «المزاد» أعلى اليمين");
            if(performGlobalAction(GLOBAL_ACTION_BACK)) {
                cooldown();
            }
            return;
        }

        if(isGamesHub(all)) {
            status("الألعاب ✓ — أفتح «المزاد» أعلى اليمين فقط");
            if(!clickAuctionCard(root)) {
                status("صفحة الألعاب — لم أجد «المزاد» أعلى اليمين بشكل مؤكد");
            }
            return;
        }

        if(containsAny(all,"العب الان","العب الآن","play now")) {
            if(stopAfterReturn) {
                stopBot("اكتملت المدة/العدد ✓ — رجعنا للرئيسية");
                return;
            }
            round=1; Prefs.setCurrentRound(this,1); pending=Pending.NONE;
            status("الرئيسية ✓ — أضغط «العب الآن»");
            if(clickText(root,"العب الان","العب الآن","play now")) {
                waitTwoSecondsAfterPlayNow();
            }
            return;
        }

        if(containsAny(all,"لقينا اللي هينافسك","استعد يا نجم","جاري البحث","searching")) {
            status("جاري اختيار المنافس — أنتظر");
            return;
        }

        status("أراقب الشاشة…");
    }

    private boolean isGamesHub(String all) {
        return containsAny(all,"الالعاب","الألعاب","games") &&
                containsAny(all,"المزاد","auction") &&
                containsAny(all,"اللاعب الخفي","hidden player","يا دوبك","بكات");
    }

    private boolean isAuctionSetup(String all) {
        return containsAny(all,"اختر الكارت","اختار الكارت","اختر لاعبي المزاد","اختار لاعبي المزاد","choose card") &&
                containsAny(all,"المزاد","auction");
    }

    private boolean isAuctionScreen(String all) {
        return containsAny(all,"المزايدة جارية","المزايده جاريه","تأكيد المزايدة","تاكيد المزايده",
                "انتظار المزايدة","انتظار المزايده","تخطي اللاعب","confirm bid");
    }

    private void handleAuction(AccessibilityNodeInfo root,String all,Integer guiRound) {
        // A SKIP decision survives the opening round. The starter cannot skip.
        if(skipWhenAllowed) {
            if(guiRound!=null && guiRound!=skipWhenAllowedRound) {
                skipWhenAllowed=false;
                skipWhenAllowedRound=0;
                clearPending();
            } else {
                if(pending==Pending.CONFIRM &&
                        containsAny(all,"انتظار المزايدة","انتظار المزايده","waiting bid","waiting for bid")) {
                    clearPending();
                }

                boolean skipEnabled=isSkipEnabled(root);
                boolean ourTurn=isOurTurn(root,all);

                // As soon as Skip is really enabled on our turn, execute the saved decision.
                // Do not rely only on the blue opening-message text because it may linger.
                if(skipEnabled && ourTurn) {
                    status("Skip أصبح متاحًا ✓ — أنفذ قرار التخطي المحفوظ");
                    skipWhenAllowed=false;
                    skipWhenAllowedRound=0;
                    doSkip();
                } else if(pending==Pending.CONFIRM) {
                    status("دفعت 1M الافتتاحية فقط ✓ — أنتظر الخصم ثم Skip");
                } else if(!ourTurn) {
                    status("قرار SKIP محفوظ — دور الخصم، أنتظر دورنا");
                } else {
                    status("قرار SKIP محفوظ — زر Skip ما زال مقفولًا، لن أضغطه");
                }
                return;
            }
        }

        if(pending!=Pending.NONE) {
            if(guiRound!=null && guiRound!=pendingRound) { clearPending(); }
            else if(pending==Pending.CONFIRM && containsAny(all,"انتظار المزايدة","انتظار المزايده","waiting bid")) {
                clearPending();
                markProgress();
                status("المزايدة اتأكدت ✓ — دور الخصم");
                return;
            } else if(pending==Pending.SKIP) {
                long age=System.currentTimeMillis()-pendingAt;
                if(age>1300 && pendingRetries<3) {
                    AccessibilityNodeInfo live=getRootInActiveWindow();
                    String liveText=live==null?"":normalize(collectText(live));
                    if(live!=null && !isOpeningRound(liveText) &&
                            isSkipEnabled(live) && isOurTurn(live,liveText)) {
                        pendingRetries++;
                        pendingAt=System.currentTimeMillis();
                        status("Skip لم يغيّر اللاعب — إعادة آمنة "+pendingRetries+"/3");
                        tapSaved("skip",ok->{ if(ok) markProgress(); });
                    } else {
                        status("أتحقق من انتقال اللاعب — لن أعيد Skip وهو غير متاح");
                    }
                } else {
                    status("أنتظر انتقال اللاعب بعد Skip…");
                }
                return;
            } else if(pending==Pending.CONFIRM) {
                long age=System.currentTimeMillis()-pendingAt;
                if(age>1700 && pendingRetries<1 && isOurTurn(root,all)) {
                    pendingRetries++; pendingAt=System.currentTimeMillis();
                    status("Confirm ما زال ظاهر — أعيد Confirm فقط بدون +");
                    tapSaved("confirm",ok->{ actionBusy=false; });
                } else status("أنتظر رد الخصم…");
                return;
            }
        }

        if(!isOurTurn(root,all)) {
            status("دور الخصم — أراقب فقط");
            return;
        }

        readCardAndAct(root,all,guiRound);
    }

    private boolean isOurTurn(AccessibilityNodeInfo root,String all) {
        AccessibilityNodeInfo confirm=findTextNode(root,"تأكيد المزايدة","تاكيد المزايده","تأكيد مزايدة","confirm bid");
        boolean confirmEnabled=confirm!=null && confirm.isVisibleToUser() && effectiveEnabled(confirm);
        if(confirmEnabled) return true;
        if(containsAny(all,"انتظار المزايدة","انتظار المزايده","waiting bid","waiting for bid")) return false;
        return false;
    }

    private boolean effectiveEnabled(AccessibilityNodeInfo n) {
        AccessibilityNodeInfo x=n;
        for(int i=0;i<4 && x!=null;i++) {
            if(x.isClickable()) return x.isEnabled();
            x=x.getParent();
        }
        return n.isEnabled();
    }

    private boolean isOpeningRound(String all) {
        return containsAny(all,
                "الدور الافتتاحي",
                "مزايدة تلقائية",
                "مزايده تلقائيه",
                "opening round",
                "automatic bid");
    }

    private Boolean controlEnabledAtSavedPoint(AccessibilityNodeInfo root,String key) {
        if(root==null) return null;
        int w=getResources().getDisplayMetrics().widthPixels;
        int h=getResources().getDisplayMetrics().heightPixels;
        PointF p=Prefs.getTapPointPx(this,key,w,h);
        if(p==null) return null;

        AccessibilityNodeInfo n=findSmallestNodeAtPoint(root,(int)p.x,(int)p.y,null);
        if(n==null) return null;
        return effectiveEnabled(n);
    }

    private AccessibilityNodeInfo findSmallestNodeAtPoint(AccessibilityNodeInfo n,int x,int y,
                                                           AccessibilityNodeInfo best) {
        if(n==null || !n.isVisibleToUser()) return best;
        Rect r=new Rect();
        n.getBoundsInScreen(r);
        if(r.isEmpty() || !r.contains(x,y)) return best;

        if(best==null) {
            best=n;
        } else {
            Rect br=new Rect();
            best.getBoundsInScreen(br);
            long a=(long)r.width()*r.height();
            long ba=(long)br.width()*br.height();
            if(a>0 && (ba<=0 || a<ba)) best=n;
        }

        for(int i=0;i<n.getChildCount();i++) {
            AccessibilityNodeInfo child=n.getChild(i);
            AccessibilityNodeInfo cand=findSmallestNodeAtPoint(child,x,y,best);
            if(cand!=null) {
                Rect cr=new Rect(), rr=new Rect();
                cand.getBoundsInScreen(cr);
                best.getBoundsInScreen(rr);
                long ca=(long)cr.width()*cr.height();
                long ra=(long)rr.width()*rr.height();
                if(ca>0 && (ra<=0 || ca<ra)) best=cand;
            }
        }
        return best;
    }

    private Boolean confirmEnabledOnScreen(AccessibilityNodeInfo root) {
        if(root==null) return null;
        AccessibilityNodeInfo n=findTextNode(root,
                "تأكيد المزايدة","تاكيد المزايده","تأكيد","confirm bid","confirm");
        if(n!=null && n.isVisibleToUser()) return effectiveEnabled(n);
        return controlEnabledAtSavedPoint(root,"confirm");
    }

    private void confirmAtAvailableBudget(int now,int target,String reason) {
        status(reason+" — Confirm تلقائي عند "+now+"M بدل "+target+"M");
        tapSaved("confirm",ok->{
            actionBusy=false;
            if(ok) {
                pending=Pending.CONFIRM;
                pendingRound=round;
                pendingRetries=0;
                pendingAt=System.currentTimeMillis();
                markProgress();
                status("الـ+ توقف عند "+now+"M وConfirm متاح ✓ — تم التأكيد تلقائيًا");
            } else {
                status("الـ+ متوقف لكن Confirm فشل — سأعيد قراءة الحالة");
            }
        });
    }

    private boolean isSkipEnabled(AccessibilityNodeInfo root) {
        AccessibilityNodeInfo skip=findTextNode(root,
                "تخطي اللاعب","تخطى اللاعب","تخطي","skip player","skip");
        return skip!=null && skip.isVisibleToUser() && effectiveEnabled(skip);
    }

    private void readCardAndAct(AccessibilityNodeInfo root,String all,Integer guiRound) {
        if(screenshotBusy) return;
        final boolean openingRound=isOpeningRound(all);
        final boolean skipEnabled=isSkipEnabled(root);

        // v33: read the rating first with the SAME Accessibility tree technique
        // already used to understand the rest of the game screen.
        final RectF exact=Prefs.getRegion(AuctionAccessibilityService.this,"card");
        final Integer accessibilityRating=extractRatingFromAccessibility(root,exact);

        screenshotBusy=true;
        takeScreenshot(android.view.Display.DEFAULT_DISPLAY,getMainExecutor(),new TakeScreenshotCallback(){
            @Override public void onSuccess(ScreenshotResult result) {
                Bitmap b=Bitmap.wrapHardwareBuffer(result.getHardwareBuffer(),result.getColorSpace());
                result.getHardwareBuffer().close();
                if(b==null){
                    screenshotBusy=false;
                    h.postDelayed(scanOnce,220);
                    return;
                }

                Bitmap copy=b.copy(Bitmap.Config.ARGB_8888,false);

                recognizer.process(InputImage.fromBitmap(copy,0))
                        .addOnSuccessListener(tx->{
                            // Same whole-screen OCR used by the screen reader, then filter by
                            // the tiny calibrated rating rectangle. This is only fallback when
                            // Accessibility does not expose the number.
                            String seed=textInRegion(tx,exact,copy.getWidth(),copy.getHeight());
                            String mt=textInRegion(tx,Prefs.getRegion(AuctionAccessibilityService.this,"price"),
                                    copy.getWidth(),copy.getHeight());
                            Integer price=extractMoney(mt);

                            Bitmap ratingCrop=cropRegion(copy,exact);
                            copy.recycle();

                            readCardRobust(ratingCrop,seed,card->{
                                screenshotBusy=false;

                                Integer rating=accessibilityRating!=null
                                        ? accessibilityRating
                                        : (card==null?null:card.rating);

                                int posRound=(guiRound!=null && guiRound>=1 && guiRound<=5)?guiRound:round;
                                String pos=(posRound>=1 && posRound<=5)
                                        ? Prefs.EXPECTED_POSITIONS[posRound-1] : null;

                                lastRating=rating==null?-1:rating;
                                lastPosition=pos==null?"—":pos;
                                lastPrice=price==null?-1:price;
                                lastMax=(rating!=null && round>=1 && round<=5)
                                        ? Prefs.maxBidForRating(AuctionAccessibilityService.this,round-1,rating)
                                        : -1;
                                refreshOverlay();

                                if(rating==null || price==null || pos==null) {
                                    resetCardConsensus();
                                    status("قراءة غير مؤكدة: OVR "+show(rating)+
                                            " | "+(pos==null?"?":pos)+" | "+show(price)+
                                            "M — لا أضغط");
                                    return;
                                }

                                // v34: the yellow line is already the accepted live read.
                                // Do not wait for a second identical screenshot.
                                verifyPositionAndDecide(rating,pos,price,
                                        guiRound,openingRound,skipEnabled);
                            });
                        })
                        .addOnFailureListener(e->{
                            copy.recycle();
                            screenshotBusy=false;
                            h.postDelayed(scanOnce,220);
                        });
            }

            @Override public void onFailure(int errorCode) {
                screenshotBusy=false;
                // Error 3 can happen transiently while Android is updating the frame.
                // Keep the last good yellow read and retry automatically.
                h.postDelayed(scanOnce,220);
            }
        });
    }

    private void verifyPositionAndDecide(int rating,String pos,int price,Integer guiRound,
                                         boolean openingRound, boolean skipEnabled) {
        resetCardConsensus();
        if(guiRound!=null && guiRound>=1 && guiRound<=5 && guiRound!=round) {
            round=guiRound;
            Prefs.setCurrentRound(this,round);
        }

        String expected=Prefs.EXPECTED_POSITIONS[round-1];
        if(!expected.equals(pos)) {
            int unique=uniqueSlotForPosition(pos);
            if(guiRound==null && unique>round) {
                round=unique;
                Prefs.setCurrentRound(this,round);
                expected=Prefs.EXPECTED_POSITIONS[round-1];
            }
        }

        // Position is a safety confirmation, not an assumption.
        if(!expected.equals(pos)) {
            status("حماية الترتيب: متوقع "+expected+" في R"+round+
                    " لكن قرأت "+pos+" — لا أضغط وأعيد القراءة");
            return;
        }

        lastRating=rating;
        lastPosition=pos;
        lastPrice=price;

        int slot=round-1;
        int maxBid=Prefs.maxBidForRating(this,slot,rating);
        lastMax=maxBid;
        refreshOverlay();

        if(maxBid<=0) {
            requestSkipRespectingOpening(price,openingRound,skipEnabled,
                    "R"+round+" "+pos+" OVR "+rating+" خارج الـ4 Ranges");
            return;
        }

        if(price>maxBid) {
            requestSkipRespectingOpening(price,openingRound,skipEnabled,
                    "السعر "+price+"M أعلى من Max "+maxBid+"M");
            return;
        }

        if(price==maxBid) {
            status("وصل Max "+maxBid+"M بالضبط — Confirm بدون Skip");
            submitConfirm();
            return;
        }

        AccessibilityNodeInfo liveRoot=getRootInActiveWindow();
        Boolean plusEnabled=controlEnabledAtSavedPoint(liveRoot,"plus");
        Boolean confirmEnabled=confirmEnabledOnScreen(liveRoot);
        if(Boolean.FALSE.equals(plusEnabled) && Boolean.TRUE.equals(confirmEnabled)) {
            status("الـ+ غير متاح وConfirm متاح — وصلت للمتاح من الميزانية");
            submitConfirm();
            return;
        }

        int step=Prefs.bidStep(maxBid);
        int target=Math.min(maxBid,price+step);
        int clicks=Math.max(0,target-price);

        status("R"+round+" "+pos+" OVR "+rating+
                " • "+price+"→"+target+"M • +"+clicks+" ثم Confirm");
        doBidClicks(clicks,target);
    }

    private void requestSkipRespectingOpening(int price, boolean openingRound,
                                                  boolean skipEnabled, String why) {
        // If the opponent started this player, Skip becomes available when our turn arrives.
        // If WE started, Skip is disabled: submit the minimum 1M only, then skip next turn.
        if(skipEnabled) {
            status(why+" → SKIP");
            doSkip();
            return;
        }

        skipWhenAllowed=true;
        skipWhenAllowedRound=round;

        if(openingRound && price<=1) {
            status(why+" • أنت تبدأ اللاعب: أدفع 1M فقط ثم Skip في دورنا التالي");
            submitOpeningMinimumForSkip();
        } else {
            status(why+" • Skip مقفول الآن — أحفظ القرار وأنتظر فتحه");
        }
    }

    private void submitOpeningMinimumForSkip() {
        if(actionBusy) return;
        actionBusy=true;

        // Important: no PLUS here. Current opening bid is already 1M.
        tapSaved("confirm",ok->{
            actionBusy=false;
            if(ok) {
                pending=Pending.CONFIRM;
                pendingRound=round;
                pendingRetries=0;
                pendingAt=System.currentTimeMillis();
                status("تم دفع 1M الافتتاحية فقط ✓ — لا مزايدة إضافية، Skip محفوظ");
            } else {
                status("فشل Confirm للـ1M — لن أضغط +، سأعيد التحقق من الشاشة");
            }
        });
    }

    private int uniqueSlotForPosition(String p) {
        if("GK".equals(p)) return 1;
        if("CB".equals(p)) return 2;
        if("ST".equals(p)) return 5;
        return -1;
    }

    private void doSkip() {
        if(actionBusy) return;

        AccessibilityNodeInfo root=getRootInActiveWindow();
        String all=root==null?"":normalize(collectText(root));
        if(root==null || isOpeningRound(all) || !isSkipEnabled(root)) {
            skipWhenAllowed=true;
            skipWhenAllowedRound=round;
            status(isOpeningRound(all)
                    ? "الدور الافتتاحي — زر Skip مقفول، أنتظر 1M التلقائية"
                    : "زر Skip غير متاح الآن — قرار التخطي محفوظ");
            return;
        }

        actionBusy=true;
        resetCardConsensus();
        tapSaved("skip",ok->{
            actionBusy=false;
            if(ok) {
                pending=Pending.SKIP; pendingRound=round; pendingRetries=0; pendingAt=System.currentTimeMillis();
                markProgress();
                status("Skip اتضغط ✓ — أتحقق إن اللاعب اتغيّر");
            } else status("فشل ضغط Skip — سأحاول في القراءة التالية");
        });
    }

    private void doBidClicks(int clicks,int target) {
        if(actionBusy) return;
        actionBusy=true;
        resetCardConsensus();
        verifyLastObservedPrice=-1;
        verifyStallCount=0;

        tapPlusSequence(clicks,()->{
            h.postDelayed(()->verifyBidPriceThenConfirm(target,0),260);
        });
    }

    private void verifyBidPriceThenConfirm(int target,int attempt) {
        if(attempt>3) {
            actionBusy=false;
            status("لم أقدر أتأكد من قيمة المزايدة بعد + — لم أضغط Confirm حفاظًا على القرار");
            return;
        }

        screenshotBusy=true;
        takeScreenshot(android.view.Display.DEFAULT_DISPLAY,getMainExecutor(),new TakeScreenshotCallback(){
            @Override public void onSuccess(ScreenshotResult result) {
                Bitmap b=Bitmap.wrapHardwareBuffer(result.getHardwareBuffer(),result.getColorSpace());
                result.getHardwareBuffer().close();
                if(b==null) {
                    screenshotBusy=false;
                    h.postDelayed(()->verifyBidPriceThenConfirm(target,attempt+1),180);
                    return;
                }

                Bitmap copy=b.copy(Bitmap.Config.ARGB_8888,false);
                recognizer.process(InputImage.fromBitmap(copy,0))
                        .addOnSuccessListener(tx->{
                            Integer now=extractMoney(textInRegion(tx,
                                    Prefs.getRegion(AuctionAccessibilityService.this,"price"),
                                    copy.getWidth(),copy.getHeight()));
                            copy.recycle();
                            screenshotBusy=false;

                            if(now==null) {
                                h.postDelayed(()->verifyBidPriceThenConfirm(target,attempt+1),180);
                                return;
                            }

                            lastPrice=now;
                            refreshOverlay();

                            if(now==target) {
                                tapSaved("confirm",ok->{
                                    actionBusy=false;
                                    if(ok) {
                                        pending=Pending.CONFIRM;
                                        pendingRound=round;
                                        pendingRetries=0;
                                        pendingAt=System.currentTimeMillis();
                                        markProgress();
                                        status("السعر اتأكد "+now+"M ✓ — Confirm، أنتظر الخصم");
                                    } else {
                                        status("السعر صحيح لكن Confirm فشل — سأعيد التحقق بدون +");
                                    }
                                });
                            } else if(now<target) {
                                if(now==verifyLastObservedPrice) verifyStallCount++;
                                else {
                                    verifyLastObservedPrice=now;
                                    verifyStallCount=0;
                                }

                                AccessibilityNodeInfo liveRoot=getRootInActiveWindow();
                                Boolean plusEnabled=controlEnabledAtSavedPoint(liveRoot,"plus");
                                Boolean confirmEnabled=confirmEnabledOnScreen(liveRoot);

                                // Exact requested case: PLUS disabled while Confirm is enabled.
                                if(Boolean.FALSE.equals(plusEnabled) &&
                                        Boolean.TRUE.equals(confirmEnabled)) {
                                    confirmAtAvailableBudget(now,target,
                                            "وصلت لأقصى مبلغ متاح: زر + غير متاح وConfirm متاح");
                                    return;
                                }

                                // Some game builds do not expose the PLUS disabled state to
                                // Accessibility. If repeated + taps leave the bid unchanged
                                // twice and Confirm is enabled, treat it as the same budget-cap state.
                                if(verifyStallCount>=2 && Boolean.TRUE.equals(confirmEnabled)) {
                                    confirmAtAvailableBudget(now,target,
                                            "المزايدة ثابتة رغم محاولات + المتكررة وConfirm متاح");
                                    return;
                                }

                                int missing=target-now;
                                status("تحقق المزايدة: وصل "+now+"M بدل "+target+"M — أكمل +"+missing);
                                tapPlusSequence(missing,()->
                                        h.postDelayed(()->verifyBidPriceThenConfirm(target,attempt+1),220));
                            } else {
                                actionBusy=false;
                                status("حماية: السعر أصبح "+now+"M أعلى من الهدف "+target+
                                        "M — لم أضغط Confirm");
                            }
                        })
                        .addOnFailureListener(e->{
                            copy.recycle();
                            screenshotBusy=false;
                            h.postDelayed(()->verifyBidPriceThenConfirm(target,attempt+1),180);
                        });
            }

            @Override public void onFailure(int errorCode) {
                screenshotBusy=false;
                h.postDelayed(()->verifyBidPriceThenConfirm(target,attempt+1),180);
            }
        });
    }

    private void submitConfirm() {
        if(actionBusy) return;
        actionBusy=true;
        resetCardConsensus();
        tapSaved("confirm",ok->{
            actionBusy=false;
            if(ok) {
                pending=Pending.CONFIRM; pendingRound=round; pendingRetries=0; pendingAt=System.currentTimeMillis();
                markProgress();
                status("Confirm عند Max ✓ — أنتظر الخصم");
            } else status("فشل Confirm");
        });
    }

    private void tapPlusSequence(int left,Runnable done) {
        if(left<=0){ done.run(); return; }
        tapSaved("plus",ok->{
            if(!ok){ actionBusy=false; status("فشل ضغط + — توقفت عن هذه المزايدة مؤقتاً"); return; }
            h.postDelayed(()->tapPlusSequence(left-1,done),155);
        });
    }

    private void clearPending() {
        pending=Pending.NONE;
        pendingRetries=0;
        pendingAt=0;
        pendingRound=0;
        resetCardConsensus();
    }

    // ---------- Post auction / simulation / result loop ----------

    private boolean handlePostFlow(AccessibilityNodeInfo root,String all) {
        // Rematch popup can appear on the result screen before we scroll to
        // "العودة للرئيسية". Always reject the rematch first, then continue
        // the normal post-match flow on the next scan.
        if(containsAny(all,
                "جاهز لجولة تانية",
                "جاهز لجوله تانيه",
                "جولة تانية",
                "جوله تانيه",
                "ready for another round",
                "play again") &&
                containsAny(all,
                        "مش دلوقتي",
                        "مش دلوقتى",
                        "not now")) {
            postFlow=true;
            postStage=5;
            clearPending();

            if(clickText(root,
                    "مش دلوقتي","مش دلوقتى",
                    "not now")) {
                status("طلب جولة تانية ظهر ✓ — ضغطت «مش دلوقتي»");
            } else {
                status("طلب جولة تانية ظاهر — أحاول ضغط «مش دلوقتي»");
            }
            return true;
        }

        // Special fast-win path: opponent disconnects/leaves the match.
        // This screen already contains "العودة للرئيسية", so skip the normal
        // simulation/results sequence and return home immediately.
        if(containsAny(all,
                "خصمك خرج من المباراة",
                "خصمك خرج من المباراه",
                "فزت بالانسحاب",
                "فزت بالإنسحاب",
                "opponent left the match",
                "opponent disconnected")) {
            postFlow=true;
            postStage=5;
            clearPending();
            skipWhenAllowed=false;
            skipWhenAllowedRound=0;

            if(clickText(root,
                    "العودة للرئيسية","العوده للرئيسيه",
                    "return home","return to main")) {
                onMatchDone();
                status("الخصم خرج من المباراة ✓ — العودة للرئيسية");
            } else {
                status("الخصم خرج من المباراة — أبحث عن «العودة للرئيسية»");
                safePostScroll();
            }
            return true;
        }

        if(containsAny(all,"جاري تعيين المدربين","جار تعيين المدربين","assigning coaches")) {
            postFlow=true; postStage=1; clearPending();
            status("جاري تعيين المدربين… أنتظر");
            return true;
        }

        if(postFlow || containsAny(all,"التشكيلتان النهائيتان","أجواء الملعب مضاءة","اجواء الملعب مضاءه",
                "تقدم المباراة","رجل المباراة","عرض النتائج","المدربين")) {
            postFlow=true;

            if(containsAny(all,"المدربين","coaches") && containsAny(all,"عرض التشكيلات","view lineups")) {
                postStage=2; scrollAttempts=0;
                status("المدربين ✓ — أضغط عرض التشكيلات");
                clickText(root,"عرض التشكيلات","view lineups");
                return true;
            }

            if(containsAny(all,"التشكيلتان النهائيتان","التشكيلتان النهائيتين","final lineups")) {
                postStage=3;
                if(clickText(root,"بدء المحاكاة","بدء المحاكاه","ابدأ المحاكاة","start simulation")) {
                    status("بدء المحاكاة ✓");
                } else {
                    status("التشكيلات النهائية — Scroll لآخر الصفحة");
                    safePostScroll();
                }
                return true;
            }

            if(containsAny(all,"أجواء الملعب مضاءة","اجواء الملعب مضاءه","تقدم المباراة","match progress","محاكاة المباراة")) {
                postStage=4;
                if(clickText(root,"عرض النتائج","عرض النتايج","view results")) {
                    status("عرض النتائج ✓");
                } else if(containsAny(all,"100%","نهاية المباراة","نهايه المباراه","match ended")) {
                    status("المباراة انتهت — Scroll حتى «عرض النتائج»");
                    safePostScroll();
                } else {
                    status("المحاكاة جارية — أنتظر");
                }
                return true;
            }

            if(containsAny(all,"رجل المباراة","رجل المباراه","خسارة","خساره","فوز","match summary")) {
                postStage=5;
                if(clickText(root,"العودة للرئيسية","العوده للرئيسيه","return home","return to main")) {
                    onMatchDone();
                    status("العودة للرئيسية ✓");
                } else {
                    status("صفحة النتيجة — Scroll لآخر الصفحة حتى العودة للرئيسية");
                    safePostScroll();
                }
                return true;
            }

            if(containsAny(all,"عرض النتائج","view results")) {
                postStage=4;
                clickText(root,"عرض النتائج","view results");
                return true;
            }

            status("مسار نهاية الجيم — أراقب");
            return true;
        }
        return false;
    }

    private void onMatchDone() {
        matches++;
        markProgress();
        Prefs.setMatches(this,matches);
        String mode=Prefs.repeatMode(this);
        if("count".equals(mode) && matches>=Prefs.repeatCount(this)) stopAfterReturn=true;
        if("time".equals(mode) && System.currentTimeMillis()-startedAt>=Prefs.repeatMinutes(this)*60_000L) stopAfterReturn=true;
        postFlow=false; postStage=0; scrollAttempts=0; clearPending(); round=1; Prefs.setCurrentRound(this,1);
    }

    private void safePostScroll() {
        if(System.currentTimeMillis()<actionCooldownUntil) return;
        scrollAttempts++;
        if(scrollAttempts>50) {
            paused=true;
            status("لم أجد الزر بعد 50 Scroll — PAUSE للمراجعة");
            return;
        }
        swipeDown();
    }

    // ---------- Navigation helpers ----------

    private boolean clickText(AccessibilityNodeInfo root,String... targets) {
        AccessibilityNodeInfo n=findTextNode(root,targets);
        if(n==null || !n.isVisibleToUser()) return false;
        AccessibilityNodeInfo x=n;
        for(int i=0;i<5 && x!=null;i++) {
            if(x.isClickable() && x.isEnabled()) {
                boolean ok=x.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                if(ok){ cooldown(); return true; }
            }
            x=x.getParent();
        }
        Rect r=new Rect(); n.getBoundsInScreen(r);
        if(r.isEmpty()) return false;
        dispatchTap(r.centerX(),r.centerY(),null);
        return true;
    }

    private boolean clickAuctionCard(AccessibilityNodeInfo root) {
        if(root==null) return false;
        List<AccessibilityNodeInfo> hits=new ArrayList<>();
        collectMatching(root,hits,"المزاد","auction");

        int w=getResources().getDisplayMetrics().widthPixels;
        int hgt=getResources().getDisplayMetrics().heightPixels;
        AccessibilityNodeInfo best=null;
        long bestScore=Long.MIN_VALUE;

        for(AccessibilityNodeInfo n:hits) {
            if(n==null || !n.isVisibleToUser()) continue;
            String v=normalize(nodeText(n));
            if(!(v.equals(normalize("المزاد")) || v.equals("auction"))) continue;

            Rect r=new Rect();
            n.getBoundsInScreen(r);
            if(r.isEmpty()) continue;

            // The auction tile is the TOP-RIGHT card on the games screen.
            // Reject lower/right cards such as «أنت هتحور؟».
            if(r.centerX() < w*.50f) continue;
            if(r.centerY() > hgt*.48f) continue;

            long score=(long)r.centerX()*4L - (long)r.centerY()*2L;
            if(best==null || score>bestScore) {
                best=n;
                bestScore=score;
            }
        }

        if(best!=null) {
            boolean ok=clickNode(best);
            if(ok) {
                status("المزاد أعلى اليمين ✓");
                return true;
            }
        }

        // No blind click on any other card. A conservative fallback taps only
        // inside the known top-right auction tile region.
        float x=w*.75f;
        float y=hgt*.30f;
        status("أضغط منطقة «المزاد» أعلى اليمين فقط");
        dispatchTap(x,y,null);
        return true;
    }

    private boolean clickBottomPlay(AccessibilityNodeInfo root) {
        List<AccessibilityNodeInfo> hits=new ArrayList<>();
        collectMatching(root,hits,"العب","ابدأ","play","start");
        AccessibilityNodeInfo best=null; int bestY=-1;
        int hgt=getResources().getDisplayMetrics().heightPixels;
        for(AccessibilityNodeInfo n:hits) {
            if(!n.isVisibleToUser()) continue;
            String v=normalize(nodeText(n));
            if(!(v.equals("العب")||v.equals("ابدأ")||v.equals("play")||v.equals("start"))) continue;
            Rect r=new Rect(); n.getBoundsInScreen(r);
            if(r.centerY()>hgt*.45f && r.centerY()>bestY){best=n;bestY=r.centerY();}
        }
        if(best==null) return false;
        return clickNode(best);
    }

    private boolean clickNode(AccessibilityNodeInfo n) {
        AccessibilityNodeInfo x=n;
        for(int i=0;i<5 && x!=null;i++) {
            if(x.isClickable()&&x.isEnabled()) {
                boolean ok=x.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                if(ok){cooldown();return true;}
            }
            x=x.getParent();
        }
        Rect r=new Rect();n.getBoundsInScreen(r);
        if(r.isEmpty())return false;
        dispatchTap(r.centerX(),r.centerY(),null);
        return true;
    }

    private AccessibilityNodeInfo findTextNode(AccessibilityNodeInfo root,String... targets) {
        if(root==null)return null;
        String v=normalize(nodeText(root));
        for(String t:targets) if(v.contains(normalize(t)) && root.isVisibleToUser()) return root;
        for(int i=0;i<root.getChildCount();i++) {
            AccessibilityNodeInfo hit=findTextNode(root.getChild(i),targets);
            if(hit!=null)return hit;
        }
        return null;
    }

    private void collectMatching(AccessibilityNodeInfo n,List<AccessibilityNodeInfo> out,String... targets) {
        if(n==null)return;
        String v=normalize(nodeText(n));
        for(String t:targets) if(v.contains(normalize(t))) { out.add(n); break; }
        for(int i=0;i<n.getChildCount();i++) collectMatching(n.getChild(i),out,targets);
    }

    private Integer extractRatingFromAccessibility(AccessibilityNodeInfo root,RectF region) {
        if(root==null || region==null) return null;
        int sw=getResources().getDisplayMetrics().widthPixels;
        int sh=getResources().getDisplayMetrics().heightPixels;
        Rect roi=new Rect(
                Math.round(region.left*sw),
                Math.round(region.top*sh),
                Math.round(region.right*sw),
                Math.round(region.bottom*sh));
        RatingNodeCandidate best=new RatingNodeCandidate();
        findRatingNode(root,roi,best,0);
        return best.rating;
    }

    private static final class RatingNodeCandidate {
        Integer rating=null;
        float score=-999f;
    }

    private void findRatingNode(AccessibilityNodeInfo n,Rect roi,RatingNodeCandidate best,int depth) {
        if(n==null || depth>45) return;

        Rect nr=new Rect();
        n.getBoundsInScreen(nr);

        if(!nr.isEmpty() && Rect.intersects(nr,roi)) {
            StringBuilder raw=new StringBuilder();
            CharSequence t=n.getText(),d=n.getContentDescription();
            if(t!=null) raw.append(t).append(' ');
            if(d!=null) raw.append(d);

            Integer rating=extractRatingStrict(raw.toString());
            if(rating!=null) {
                float cx=nr.exactCenterX(), cy=nr.exactCenterY();
                float rx=roi.exactCenterX(), ry=roi.exactCenterY();

                float dx=(cx-rx)/Math.max(1f,roi.width());
                float dy=(cy-ry)/Math.max(1f,roi.height());
                float score=10f-(float)Math.sqrt(dx*dx+dy*dy);

                if(roi.contains(Math.round(cx),Math.round(cy))) score+=5f;
                if(nr.contains(Math.round(rx),Math.round(ry))) score+=2f;

                float area=(float)Math.max(1,nr.width()*nr.height());
                float roiArea=(float)Math.max(1,roi.width()*roi.height());
                if(area>roiArea*8f) score-=6f;

                String s=raw.toString().trim();
                if(s.length()<=8) score+=3f;

                if(score>best.score) {
                    best.score=score;
                    best.rating=rating;
                }
            }
        }

        for(int i=0;i<n.getChildCount();i++) {
            AccessibilityNodeInfo child=n.getChild(i);
            if(child!=null) findRatingNode(child,roi,best,depth+1);
        }
    }

    private String collectText(AccessibilityNodeInfo n) {
        StringBuilder b=new StringBuilder();
        collectTextRec(n,b,0);
        return b.toString();
    }

    private void collectTextRec(AccessibilityNodeInfo n,StringBuilder b,int depth) {
        if(n==null||depth>45)return;
        CharSequence t=n.getText(),d=n.getContentDescription();
        if(t!=null)b.append(' ').append(t);
        if(d!=null)b.append(' ').append(d);
        for(int i=0;i<n.getChildCount();i++) collectTextRec(n.getChild(i),b,depth+1);
    }

    private String nodeText(AccessibilityNodeInfo n) {
        if(n==null)return "";
        StringBuilder b=new StringBuilder();
        if(n.getText()!=null)b.append(n.getText());
        if(n.getContentDescription()!=null)b.append(' ').append(n.getContentDescription());
        return b.toString();
    }

    private void swipeDown() {
        if(System.currentTimeMillis()<actionCooldownUntil)return;
        int w=getResources().getDisplayMetrics().widthPixels,hg=getResources().getDisplayMetrics().heightPixels;
        Path p=new Path(); p.moveTo(w*.86f,hg*.82f); p.lineTo(w*.86f,hg*.26f);
        GestureDescription g=new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(p,0,560)).build();
        markAction();
        final long delay=postFlow ? 2000L : 800L;
        actionCooldownUntil=System.currentTimeMillis()+delay;
        dispatchGesture(g,new GestureResultCallback(){
            @Override public void onCompleted(GestureDescription d){h.postDelayed(scanOnce,delay+60L);}
        },null);
    }

    private void tapSaved(String key,TapResult cb) {
        int w=getResources().getDisplayMetrics().widthPixels,hg=getResources().getDisplayMetrics().heightPixels;
        PointF p=Prefs.getTapPointPx(this,key,w,hg);
        if(p==null){ if(cb!=null)cb.done(false); return; }
        dispatchTap(p.x,p.y,cb);
    }

    private interface TapResult { void done(boolean ok); }

    private void dispatchTap(float x,float y,TapResult cb) {
        Path p=new Path();p.moveTo(x,y);p.lineTo(x+.5f,y+.5f);
        GestureDescription g=new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(p,0,85)).build();
        boolean accepted;
        try{
            accepted=dispatchGesture(g,new GestureResultCallback(){
                @Override public void onCompleted(GestureDescription d){
                    cooldown();
                    if(cb!=null)h.post(()->cb.done(true));
                }
                @Override public void onCancelled(GestureDescription d){
                    if(cb!=null)h.post(()->cb.done(false));
                }
            },null);
        }catch(Exception e){accepted=false;}
        if(!accepted && cb!=null) cb.done(false);
    }

    private void waitTwoSecondsAfterPlayNow() {
        markAction();
        actionCooldownUntil=System.currentTimeMillis()+2000L;
        h.postDelayed(scanOnce,2060L);
        status("ضغطت «العب الآن» ✓ — أنتظر ثانيتين لظهور الشاشة التالية");
    }

    private void cooldown(){
        markAction();
        long delay=postFlow ? 2000L : 700L;
        actionCooldownUntil=System.currentTimeMillis()+delay;
        h.postDelayed(scanOnce,delay+60L);
    }

    // ---------- OCR helpers ----------

    private String textInRegion(Text tx,RectF n,int w,int hg) {
        if(tx==null||n==null)return "";
        RectF px=new RectF(n.left*w,n.top*hg,n.right*w,n.bottom*hg);
        StringBuilder b=new StringBuilder();
        for(Text.TextBlock block:tx.getTextBlocks()) {
            for(Text.Line line:block.getLines()) {
                Rect r=line.getBoundingBox();
                if(r!=null && px.contains(r.centerX(),r.centerY())) b.append(' ').append(line.getText());
                for(Text.Element e:line.getElements()) {
                    Rect er=e.getBoundingBox();
                    if(er!=null && px.contains(er.centerX(),er.centerY())) b.append(' ').append(e.getText());
                }
            }
        }
        return b.toString();
    }

    private Bitmap thresholdOcrVariant(Bitmap src,int threshold,boolean inverse) {
        if(src==null) return null;
        try {
            int w=src.getWidth(), h=src.getHeight();
            int[] px=new int[w*h];
            src.getPixels(px,0,w,0,0,w,h);

            for(int i=0;i<px.length;i++) {
                int color=px[i];
                int y=(Color.red(color)*299+Color.green(color)*587+Color.blue(color)*114)/1000;
                boolean light=y>=threshold;
                if(inverse) light=!light;
                px[i]=light?Color.WHITE:Color.BLACK;
            }

            Bitmap out=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888);
            out.setPixels(px,0,w,0,0,w,h);
            return out;
        } catch(Exception e) {
            return null;
        }
    }

    private Bitmap saturationOcrVariant(Bitmap src) {
        if(src==null) return null;
        try {
            int w=src.getWidth(), h=src.getHeight();
            int[] px=new int[w*h];
            src.getPixels(px,0,w,0,0,w,h);

            for(int i=0;i<px.length;i++) {
                int color=px[i];
                int r=Color.red(color), g=Color.green(color), b=Color.blue(color);
                int max=Math.max(r,Math.max(g,b));
                int min=Math.min(r,Math.min(g,b));
                int sat=max-min;
                int lum=(r*299+g*587+b*114)/1000;

                // Gold card letters have strong chroma plus a dark outline.
                boolean ink=(sat>45 && lum<225) || lum<92;
                px[i]=ink?Color.BLACK:Color.WHITE;
            }

            Bitmap out=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888);
            out.setPixels(px,0,w,0,0,w,h);
            return out;
        } catch(Exception e) {
            return null;
        }
    }

    private static class RatingVotes {
        final int[] counts=new int[100];
        final ArrayList<String> traces=new ArrayList<>();

        void add(Integer v,String raw) {
            if(v==null || v<50 || v>99) return;
            counts[v]++;
            traces.add(v+":"+sanitizeTrace(raw));
        }

        Integer winner() {
            int best=-1,bestVotes=0,second=0;
            for(int v=50;v<=99;v++) {
                int n=counts[v];
                if(n>bestVotes) {
                    second=bestVotes;
                    bestVotes=n;
                    best=v;
                } else if(n>second) {
                    second=n;
                }
            }
            if(bestVotes==0) return null;
            if(bestVotes>=2 && bestVotes>second) return best;
            if(bestVotes==1 && second==0) return best; // temporal consensus still required later
            return null; // conflicting OCR variants: do not guess
        }

        int winnerVotes() {
            int m=0;
            for(int v=50;v<=99;v++) m=Math.max(m,counts[v]);
            return m;
        }
    }

    private static class PositionVotes {
        int gk=0,cb=0,cm=0,st=0;

        void add(String p) {
            if("GK".equals(p)) gk++;
            else if("CB".equals(p)) cb++;
            else if("CM".equals(p)) cm++;
            else if("ST".equals(p)) st++;
        }

        String winner() {
            int[] n={gk,cb,cm,st};
            String[] p={"GK","CB","CM","ST"};
            int best=-1,bv=0,second=0;
            for(int i=0;i<n.length;i++) {
                if(n[i]>bv) { second=bv; bv=n[i]; best=i; }
                else if(n[i]>second) second=n[i];
            }
            if(best<0 || bv==0) return null;
            if(bv>=2 && bv>second) return p[best];
            if(bv==1 && second==0) return p[best];
            return null;
        }
    }

    private static String sanitizeTrace(String s) {
        if(s==null) return "";
        s=s.replace('\n',' ').replace('\r',' ').trim();
        return s.length()>18?s.substring(0,18):s;
    }

    private static class CardRead {
        Integer rating;
        String position;
        Integer price;
        String raw;
    }

    private interface CardReadResult {
        void done(CardRead result);
    }

    private RectF expandRegion(RectF n,float padX,float padY) {
        if(n==null) return null;
        float w=n.width(), h=n.height();
        return new RectF(
                Math.max(0f,n.left-w*padX),
                Math.max(0f,n.top-h*padY),
                Math.min(1f,n.right+w*padX),
                Math.min(1f,n.bottom+h*padY)
        );
    }

    private Bitmap cropRegion(Bitmap base,RectF n) {
        if(base==null || n==null) return null;
        try {
            int l=Math.max(0,Math.min(base.getWidth()-1,Math.round(n.left*base.getWidth())));
            int t=Math.max(0,Math.min(base.getHeight()-1,Math.round(n.top*base.getHeight())));
            int r=Math.max(l+1,Math.min(base.getWidth(),Math.round(n.right*base.getWidth())));
            int b=Math.max(t+1,Math.min(base.getHeight(),Math.round(n.bottom*base.getHeight())));
            return Bitmap.createBitmap(base,l,t,r-l,b-t);
        } catch(Exception e) {
            return null;
        }
    }

    private void readCardRobust(Bitmap crop,String seed,CardReadResult cb) {
        // v33 fallback is intentionally simple: use the same ML Kit reader on the
        // original rating image, without gold masks, B/W thresholds or shape guesses.
        Integer seedRating=extractRatingStrict(seed);
        if(seedRating!=null) {
            if(crop!=null) crop.recycle();
            CardRead out=new CardRead();
            out.rating=seedRating;
            out.position=null;
            out.raw="screen-ocr";
            cb.done(out);
            return;
        }

        if(crop==null) {
            CardRead out=new CardRead();
            out.rating=null;
            out.position=null;
            out.raw="";
            cb.done(out);
            return;
        }

        Bitmap scaled;
        try {
            scaled=Bitmap.createScaledBitmap(crop,
                    Math.max(1,crop.getWidth()*4),
                    Math.max(1,crop.getHeight()*4),true);
        } catch(Exception e) {
            crop.recycle();
            CardRead out=new CardRead();
            out.rating=null;
            out.position=null;
            out.raw="";
            cb.done(out);
            return;
        }
        crop.recycle();

        recognizer.process(InputImage.fromBitmap(scaled,0))
                .addOnSuccessListener(tx->{
                    Integer rating=extractRatingStrict(tx.getText());
                    scaled.recycle();
                    CardRead out=new CardRead();
                    out.rating=rating;
                    out.position=null;
                    out.raw="crop-ocr";
                    cb.done(out);
                })
                .addOnFailureListener(e->{
                    scaled.recycle();
                    CardRead out=new CardRead();
                    out.rating=null;
                    out.position=null;
                    out.raw="";
                    cb.done(out);
                });
    }

    private Bitmap cropRelative(Bitmap src,float l,float t,float r,float b) {
        if(src==null) return null;
        try {
            int x1=Math.max(0,Math.min(src.getWidth()-1,Math.round(l*src.getWidth())));
            int y1=Math.max(0,Math.min(src.getHeight()-1,Math.round(t*src.getHeight())));
            int x2=Math.max(x1+1,Math.min(src.getWidth(),Math.round(r*src.getWidth())));
            int y2=Math.max(y1+1,Math.min(src.getHeight(),Math.round(b*src.getHeight())));
            return Bitmap.createBitmap(src,x1,y1,x2-x1,y2-y1);
        } catch(Exception e) {
            return null;
        }
    }

    private ArrayList<Bitmap> buildRatingVariants(Bitmap src) {
        ArrayList<Bitmap> v=new ArrayList<>();
        if(src==null) return v;
        v.add(src);
        Bitmap a=thresholdOcrVariant(src,105,false);
        Bitmap b=thresholdOcrVariant(src,135,false);
        Bitmap d=thresholdOcrVariant(src,170,false);
        Bitmap e=binaryOcrVariant(src,false);
        Bitmap f=binaryOcrVariant(src,true);
        Bitmap g=saturationOcrVariant(src);
        if(a!=null)v.add(a);
        if(b!=null)v.add(b);
        if(d!=null)v.add(d);
        if(e!=null)v.add(e);
        if(f!=null)v.add(f);
        if(g!=null)v.add(g);
        return v;
    }

    private ArrayList<Bitmap> buildPositionVariants(Bitmap src) {
        ArrayList<Bitmap> v=new ArrayList<>();
        if(src==null) return v;
        v.add(src);
        Bitmap a=thresholdOcrVariant(src,120,false);
        Bitmap b=thresholdOcrVariant(src,160,false);
        Bitmap d=binaryOcrVariant(src,false);
        Bitmap e=saturationOcrVariant(src);
        if(a!=null)v.add(a);
        if(b!=null)v.add(b);
        if(d!=null)v.add(d);
        if(e!=null)v.add(e);
        return v;
    }

    private void readRatingSimple(Bitmap ratingCrop,String seed,SimpleIntResult cb) {
        if(ratingCrop==null) {
            cb.done(extractRatingStrict(seed));
            return;
        }

        Bitmap gold=goldRatingMaskStrict(ratingCrop);
        if(gold!=null) {
            recognizer.process(InputImage.fromBitmap(gold,0))
                    .addOnSuccessListener(tx->{
                        Integer rating=extractRatingStrict(tx.getText());
                        gold.recycle();

                        if(rating!=null) {
                            ratingCrop.recycle();
                            cb.done(rating);
                        } else {
                            readRatingOriginalStrict(ratingCrop,seed,cb);
                        }
                    })
                    .addOnFailureListener(e->{
                        gold.recycle();
                        readRatingOriginalStrict(ratingCrop,seed,cb);
                    });
        } else {
            readRatingOriginalStrict(ratingCrop,seed,cb);
        }
    }

    private void readRatingOriginalStrict(Bitmap ratingCrop,String seed,SimpleIntResult cb) {
        recognizer.process(InputImage.fromBitmap(ratingCrop,0))
                .addOnSuccessListener(tx->{
                    Integer rating=extractRatingStrict(tx.getText());
                    if(rating==null) rating=extractRatingStrict(seed);
                    ratingCrop.recycle();
                    cb.done(rating);
                })
                .addOnFailureListener(e->{
                    Integer rating=extractRatingStrict(seed);
                    ratingCrop.recycle();
                    cb.done(rating);
                });
    }

    private Bitmap goldRatingMaskStrict(Bitmap src) {
        if(src==null) return null;

        try {
            int w=src.getWidth(), h=src.getHeight();
            int[] px=new int[w*h];
            src.getPixels(px,0,w,0,0,w,h);

            boolean[] on=new boolean[px.length];
            int count=0;

            for(int i=0;i<px.length;i++) {
                int color=px[i];
                int r=Color.red(color), g=Color.green(color), b=Color.blue(color);

                int max=Math.max(r,Math.max(g,b));
                int min=Math.min(r,Math.min(g,b));
                int delta=max-min;
                if(max<80 || delta<24) continue;

                float sat=delta/(float)Math.max(1,max);
                float hue;

                if(max==r) {
                    hue=60f*((g-b)/(float)Math.max(1,delta));
                    if(hue<0) hue+=360f;
                } else if(max==g) {
                    hue=60f*(2f+(b-r)/(float)Math.max(1,delta));
                } else {
                    hue=60f*(4f+(r-g)/(float)Math.max(1,delta));
                }

                // Empirically tuned on the user's failed white/gold cards:
                // 86,87,88,90,91. It accepts bronze->yellow gold, not white marble.
                boolean gold =
                        hue>=14f && hue<=72f &&
                        sat>=0.48f &&
                        max>=90 &&
                        (r-b)>=38 &&
                        (g-b)>=14;

                on[i]=gold;
                if(gold) count++;
            }

            float ratio=count/(float)Math.max(1,px.length);
            if(ratio<0.018f || ratio>0.46f) return null;

            // One small density cleanup only. No threshold voting, no fuzzy guesses.
            int[] out=new int[px.length];
            int kept=0;

            for(int y=0;y<h;y++) {
                for(int x=0;x<w;x++) {
                    int idx=y*w+x;

                    if(!on[idx]) {
                        out[idx]=Color.WHITE;
                        continue;
                    }

                    int n=0;
                    for(int yy=Math.max(0,y-1);yy<=Math.min(h-1,y+1);yy++) {
                        for(int xx=Math.max(0,x-1);xx<=Math.min(w-1,x+1);xx++) {
                            if(on[yy*w+xx]) n++;
                        }
                    }

                    if(n>=4) {
                        out[idx]=Color.BLACK;
                        kept++;
                    } else {
                        out[idx]=Color.WHITE;
                    }
                }
            }

            if(kept<Math.max(30,px.length/180)) return null;

            // Add a clean white margin around the digits before OCR.
            Bitmap mask=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888);
            mask.setPixels(out,0,w,0,0,w,h);

            int pad=Math.max(12,Math.min(w,h)/10);
            Bitmap padded=Bitmap.createBitmap(w+pad*2,h+pad*2,Bitmap.Config.ARGB_8888);
            Canvas canvas=new Canvas(padded);
            canvas.drawColor(Color.WHITE);
            canvas.drawBitmap(mask,pad,pad,null);
            mask.recycle();

            return padded;
        } catch(Exception e) {
            return null;
        }
    }

    private interface SimpleIntResult { void done(Integer value); }

    private void recognizePositionVariants(ArrayList<Bitmap> variants,int index,
                                           PositionVotes votes,Runnable done) {
        if(index>=variants.size()) {
            done.run();
            return;
        }

        Bitmap img=variants.get(index);
        recognizer.process(InputImage.fromBitmap(img,0))
                .addOnSuccessListener(tx->{
                    votes.add(extractPosition(tx.getText()));
                    img.recycle();
                    recognizePositionVariants(variants,index+1,votes,done);
                })
                .addOnFailureListener(e->{
                    img.recycle();
                    recognizePositionVariants(variants,index+1,votes,done);
                });
    }

    private void recycleVariants(ArrayList<Bitmap> variants,int from) {
        for(int i=from;i<variants.size();i++) {
            try {
                Bitmap b=variants.get(i);
                if(b!=null && !b.isRecycled()) b.recycle();
            } catch(Exception ignored) {}
        }
    }

    private Bitmap binaryOcrVariant(Bitmap src,boolean inverse) {
        if(src==null) return null;
        try {
            int w=src.getWidth(), h=src.getHeight();
            int[] px=new int[w*h];
            src.getPixels(px,0,w,0,0,w,h);

            long sum=0;
            for(int color:px) {
                int y=(Color.red(color)*299+Color.green(color)*587+Color.blue(color)*114)/1000;
                sum+=y;
            }

            int avg=px.length==0?135:(int)(sum/px.length);
            int threshold=Math.max(72,Math.min(205,avg));

            for(int i=0;i<px.length;i++) {
                int color=px[i];
                int y=(Color.red(color)*299+Color.green(color)*587+Color.blue(color)*114)/1000;
                boolean light=y>=threshold;
                if(inverse) light=!light;
                px[i]=light?Color.WHITE:Color.BLACK;
            }

            Bitmap out=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888);
            out.setPixels(px,0,w,0,0,w,h);
            return out;
        } catch(Exception e) {
            return null;
        }
    }

    private CardRead parseCard(String raw) {
        CardRead out=new CardRead();
        out.raw=raw==null?"":raw;
        out.rating=extractRatingStrict(out.raw);
        out.position=extractPosition(out.raw);
        return out;
    }

    private Integer extractRating(String raw) {
        return extractRatingStrict(raw);
    }

    private Integer extractRatingStrict(String raw) {
        if(raw==null) return null;

        String s=toWestern(raw.toUpperCase(Locale.US));
        Matcher m=Pattern.compile("(?<!\\d)([7-9]\\d)(?!\\d)").matcher(s);

        while(m.find()) {
            try {
                int v=Integer.parseInt(m.group(1));
                if(v>=70 && v<=99) return v;
            } catch(Exception ignored) {}
        }

        // Allow "9 0" / "9\n0", but only if there are exactly two real digits.
        String digits=s.replaceAll("[^0-9]","");
        if(digits.length()==2) {
            try {
                int v=Integer.parseInt(digits);
                if(v>=70 && v<=99) return v;
            } catch(Exception ignored) {}
        }

        return null;
    }

    

    private Integer extractRatingFuzzy(String raw) {
        if(raw==null) return null;

        // Only used on the RATING-ONLY crop. Never transform arbitrary full-card text.
        String s=toWestern(raw.toUpperCase(Locale.US))
                .replace('O','0')
                .replace('Q','0')
                .replace('D','0')
                .replace('I','1')
                .replace('L','1')
                .replace('Z','2')
                .replace('S','5')
                .replace('G','6')
                .replace('B','8');

        String compact=s.replaceAll("[^0-9]","");
        if(compact.length()<2) return null;

        // Prefer exactly two digit-like glyphs. Longer strings often contain noise.
        if(compact.length()==2) {
            try {
                int v=Integer.parseInt(compact);
                if(v>=50 && v<=99) return v;
            } catch(Exception ignored) {}
        }

        for(int i=0;i+1<compact.length();i++) {
            try {
                int v=Integer.parseInt(compact.substring(i,i+2));
                if(v>=50 && v<=99) return v;
            } catch(Exception ignored) {}
        }
        return null;
    }

    private Integer extractMoney(String raw) {
        String s=cleanDigits(raw);
        Matcher m=Pattern.compile("(?<!\\d)(100|[0-9]{1,2})(?!\\d)").matcher(s);
        while(m.find())try{int v=Integer.parseInt(m.group(1));if(v>=0&&v<=100)return v;}catch(Exception ignored){}
        return null;
    }

    private String extractPosition(String raw) {
        if(raw==null) return null;
        String s=raw.toUpperCase(Locale.US)
                .replaceAll("[^A-Z0-9]","");

        if(s.contains("GK") || s.contains("6K")) return "GK";
        if(s.contains("CB") || s.contains("C8")) return "CB";
        if(s.contains("CM")) return "CM";
        if(s.contains("ST") || s.contains("5T")) return "ST";
        return null;
    }

    private String cleanDigits(String raw) {
        if(raw==null)return "";
        String s=toWestern(raw.toUpperCase(Locale.US));
        return s.replace('O','0').replace('S','5').replace('B','8').replace('I','1').replace('L','1');
    }

    private Integer extractRound(String all) {
        String s=toWestern(all);
        Matcher m=Pattern.compile("(?<!\\d)([1-5])\\s*/\\s*5(?!\\d)").matcher(s);
        if(m.find())try{return Integer.parseInt(m.group(1));}catch(Exception ignored){}
        return null;
    }

    private String toWestern(String s) {
        if(s==null)return "";
        StringBuilder b=new StringBuilder(s.length());
        for(char c:s.toCharArray()) {
            if(c>='٠'&&c<='٩')b.append((char)('0'+c-'٠'));
            else if(c>='۰'&&c<='۹')b.append((char)('0'+c-'۰'));
            else b.append(c);
        }
        return b.toString();
    }

    private String normalize(String s) {
        if(s==null)return "";
        return toWestern(s).toLowerCase(Locale.ROOT)
                .replace("أ","ا").replace("إ","ا").replace("آ","ا")
                .replace("ة","ه").replace("ى","ي")
                .replace("َ","").replace("ً","").replace("ُ","").replace("ٌ","")
                .replace("ِ","").replace("ٍ","").replace("ْ","").replace("ّ","");
    }

    private boolean containsAny(String s,String... q) {
        for(String x:q) if(s.contains(normalize(x))) return true;
        return false;
    }

    private String show(Integer x){return x==null?"—":String.valueOf(x);}

    // ---------- Overlay ----------

    private void showOverlay() {
        if(wm==null)wm=(WindowManager)getSystemService(WINDOW_SERVICE);
        removeOverlay();

        LinearLayout box=new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(8),dp(6),dp(8),dp(7));
        box.setBackground(roundBg(Color.argb(235,5,18,32),Color.rgb(40,112,190),13));

        LinearLayout head=new LinearLayout(this);head.setGravity(Gravity.CENTER_VERTICAL);
        TextView drag=text("mahmoud montaser",12,Color.WHITE,true);
        ovState=text("",10.5f,Color.rgb(0,225,145),true);ovState.setGravity(Gravity.CENTER);
        TextView close=text("×",22,Color.rgb(255,105,125),true);close.setGravity(Gravity.CENTER);
        head.addView(drag,new LinearLayout.LayoutParams(0,dp(32),1.2f));
        head.addView(ovState,new LinearLayout.LayoutParams(0,dp(32),1));
        head.addView(close,new LinearLayout.LayoutParams(dp(36),dp(32)));
        box.addView(head);

        ovStatus=text(lastStatus,10.5f,Color.rgb(215,228,242),false);
        ovStatus.setMaxLines(2);box.addView(ovStatus,new LinearLayout.LayoutParams(-1,dp(38)));
        ovInfo=text("R"+round+" • "+lastPosition+" • OVR "+show(lastRating)+" • "+show(lastPrice)+"M / Max "+show(lastMax),10.5f,Color.rgb(255,214,50),true);
        box.addView(ovInfo,new LinearLayout.LayoutParams(-1,dp(27)));

        LinearLayout buttons=new LinearLayout(this);
        Button start=ovButton("START",Color.rgb(0,183,115));
        Button pause=ovButton("PAUSE",Color.rgb(35,66,95));
        Button stop=ovButton("STOP",Color.rgb(112,30,46));
        Button cal=ovButton("CAL",Color.rgb(38,123,213));
        buttons.addView(start,new LinearLayout.LayoutParams(0,dp(40),1));
        gap(buttons);buttons.addView(pause,new LinearLayout.LayoutParams(0,dp(40),1));
        gap(buttons);buttons.addView(stop,new LinearLayout.LayoutParams(0,dp(40),1));
        gap(buttons);buttons.addView(cal,new LinearLayout.LayoutParams(0,dp(40),.8f));
        box.addView(buttons);

        start.setOnClickListener(v->startBot());
        pause.setOnClickListener(v->togglePause());
        stop.setOnClickListener(v->stopBot("STOP"));
        cal.setOnClickListener(v->startCalibration());
        close.setOnClickListener(v->{stopBot("تم إغلاق الـOverlay");removeOverlay();});

        floatingView=box;
        int sw=getResources().getDisplayMetrics().widthPixels;
        floatingLp=new WindowManager.LayoutParams(Math.max(dp(270),(int)(sw*.72f)),-2,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE|WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        floatingLp.gravity=Gravity.TOP|Gravity.START;
        floatingLp.x=savedX==Integer.MIN_VALUE?dp(5):savedX;
        floatingLp.y=savedY==Integer.MIN_VALUE?dp(55):savedY;

        final float[] t=new float[4];
        drag.setOnTouchListener((v,e)->{
            if(e.getAction()==MotionEvent.ACTION_DOWN){t[0]=e.getRawX();t[1]=e.getRawY();t[2]=floatingLp.x;t[3]=floatingLp.y;return true;}
            if(e.getAction()==MotionEvent.ACTION_MOVE){
                floatingLp.x=Math.round(t[2]+e.getRawX()-t[0]);
                floatingLp.y=Math.round(t[3]+e.getRawY()-t[1]);
                savedX=floatingLp.x;savedY=floatingLp.y;
                try{wm.updateViewLayout(floatingView,floatingLp);}catch(Exception ignored){}
                return true;
            }
            return true;
        });

        try{wm.addView(box,floatingLp);}catch(Exception e){floatingView=null;}
        refreshOverlay();
    }

    private void removeOverlay() {
        if(floatingView!=null&&wm!=null)try{wm.removeView(floatingView);}catch(Exception ignored){}
        floatingView=null;ovStatus=null;ovInfo=null;ovState=null;
    }

    private void refreshOverlay() {
        if(ovStatus!=null)ovStatus.setText(lastStatus);
        if(ovInfo!=null)ovInfo.setText("R"+round+" • "+lastPosition+" • OVR "+show(lastRating)+" • "+show(lastPrice)+"M / Max "+show(lastMax));
        if(ovState!=null) {
            ovState.setText(!running?"● STOP":paused?"● PAUSE":"● RUN");
            ovState.setTextColor(!running?Color.rgb(255,105,125):paused?Color.rgb(255,190,80):Color.rgb(0,225,145));
        }
    }

    private void status(String s) {
        lastStatus=s;Prefs.setStatus(this,s);refreshOverlay();
        Intent i=new Intent(BotActions.STATUS);i.setPackage(getPackageName());i.putExtra(BotActions.EXTRA_STATUS,s);sendBroadcast(i);
    }

    // ---------- Calibration 5/5 ----------

    private void startCalibration() {
        running=false;
        paused=false;
        clearPending();
        actionBusy=false;
        skipWhenAllowed=false;
        skipWhenAllowedRound=0;
        removeOverlay();
        removeCalibration();
        Prefs.clearCalibration(this);
        calibrationStep=0;
        showCalibrationStep();
    }

    private void showCalibrationStep() {
        removeCalibration();
        if(wm==null)wm=(WindowManager)getSystemService(WINDOW_SERVICE);

        FrameLayout frame=new FrameLayout(this);
        calibrationView=new CalibrationView(this);
        frame.addView(calibrationView,new FrameLayout.LayoutParams(-1,-1));

        calibrationInstruction=text(calibrationLabel(),16,Color.WHITE,true);
        calibrationInstruction.setGravity(Gravity.CENTER);
        calibrationInstruction.setPadding(dp(8),dp(8),dp(8),dp(8));
        calibrationInstruction.setBackgroundColor(Color.argb(230,5,14,27));
        frame.addView(calibrationInstruction,new FrameLayout.LayoutParams(-1,dp(74),Gravity.TOP));

        LinearLayout bottom=new LinearLayout(this);
        bottom.setPadding(dp(8),dp(8),dp(8),dp(8));
        bottom.setBackgroundColor(Color.argb(230,5,14,27));

        Button cancel=ovButton("إلغاء",Color.rgb(88,35,48));
        Button save=ovButton(calibrationStep<2?"حفظ / التالي ✓":"المس المكان على الشاشة",Color.rgb(31,149,255));

        bottom.addView(cancel,new LinearLayout.LayoutParams(0,dp(50),1));
        gap(bottom);
        bottom.addView(save,new LinearLayout.LayoutParams(0,dp(50),2));
        frame.addView(bottom,new FrameLayout.LayoutParams(-1,dp(68),Gravity.BOTTOM));

        cancel.setOnClickListener(v->{
            removeCalibration();
            showOverlay();
            status("تم إلغاء CAL");
        });

        save.setOnClickListener(v->{
            if(calibrationStep>=2) return;
            RectF r=calibrationView.selection();
            if(r==null || r.width()<.015f || r.height()<.018f) {
                Toast.makeText(this,"ارسم مربع واضح حول المطلوب",Toast.LENGTH_SHORT).show();
                return;
            }
            String key=new String[]{"card","price"}[calibrationStep];
            Prefs.saveRegion(this,key,r);
            calibrationStep++;
            showCalibrationStep();
        });

        calibrationRoot=frame;
        WindowManager.LayoutParams lp=new WindowManager.LayoutParams(-1,-1,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN|WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        lp.gravity=Gravity.TOP|Gravity.START;

        try {
            wm.addView(frame,lp);
        } catch(Exception e) {
            calibrationRoot=null;
            showOverlay();
            status("تعذر فتح CAL");
        }
    }

    private String calibrationLabel() {
        switch(calibrationStep) {
            case 0:return "1/5 — ارسم مربع ضيق حول رقم التقييم فقط (مثلاً 89 فقط، بدون CM وبدون وجه اللاعب)";
            case 1:return "2/5 — ارسم مربع حول قيمة المزايدة الحالية فقط";
            case 2:return "3/5 — المس منتصف زر +";
            case 3:return "4/5 — المس منتصف زر «تأكيد المزايدة»";
            default:return "5/5 — المس منتصف زر «تخطي اللاعب»";
        }
    }

    private void onCalibrationTap(float x,float y) {
        if(calibrationStep<2 || calibrationStep>4) return;

        int w=getResources().getDisplayMetrics().widthPixels;
        int hg=getResources().getDisplayMetrics().heightPixels;
        String key=calibrationStep==2?"plus":calibrationStep==3?"confirm":"skip";

        Prefs.saveTapPointPx(this,key,x,y,w,hg);
        calibrationStep++;

        if(calibrationStep>4) {
            Prefs.markCalibrated(this);
            removeCalibration();
            showOverlay();
            status(Prefs.isCalibrated(this)?"CAL 5/5 READY ✓":"CAL غير مكتمل");
        } else {
            showCalibrationStep();
        }
    }

    private void removeCalibration() {
        if(calibrationRoot!=null&&wm!=null)try{wm.removeView(calibrationRoot);}catch(Exception ignored){}
        calibrationRoot=null;calibrationView=null;calibrationInstruction=null;
    }

    private class CalibrationView extends View {
        private final Paint border=new Paint(3),shade=new Paint(3),label=new Paint(3);
        private final RectF sel=new RectF();
        private float sx,sy;

        CalibrationView(Context c){
            super(c);
            border.setStyle(Paint.Style.STROKE);
            border.setStrokeWidth(dp(3));
            border.setColor(Color.rgb(40,195,255));
            shade.setColor(Color.argb(70,0,0,0));
            label.setColor(Color.WHITE);
            label.setTextSize(dp(14));
            label.setTypeface(Typeface.DEFAULT_BOLD);
        }

        @Override protected void onDraw(Canvas c){
            super.onDraw(c);

            if(calibrationStep<2 && !sel.isEmpty()){
                c.drawRect(0,0,getWidth(),sel.top,shade);
                c.drawRect(0,sel.bottom,getWidth(),getHeight(),shade);
                c.drawRect(0,sel.top,sel.left,sel.bottom,shade);
                c.drawRect(sel.right,sel.top,getWidth(),sel.bottom,shade);
                c.drawRoundRect(sel,dp(8),dp(8),border);
                c.drawText(calibrationStep==0?"RATING ONLY":"PRICE",
                        sel.left+dp(5),Math.max(dp(92),sel.top-dp(5)),label);
            } else if(calibrationStep>=2) {
                String tap=calibrationStep==2?"TAP +":
                        calibrationStep==3?"TAP CONFIRM":"TAP SKIP";
                c.drawText(tap,dp(18),dp(105),label);
            }
        }

        @Override public boolean onTouchEvent(MotionEvent e){
            float x=Math.max(0,Math.min(getWidth(),e.getX()));
            float y=Math.max(dp(78),Math.min(getHeight()-dp(72),e.getY()));

            if(calibrationStep>=2){
                if(e.getAction()==MotionEvent.ACTION_DOWN){
                    float rx=e.getRawX(), ry=e.getRawY();
                    h.post(()->onCalibrationTap(rx,ry));
                }
                return true;
            }

            if(e.getAction()==MotionEvent.ACTION_DOWN){
                sx=x; sy=y;
                sel.set(x,y,x+1,y+1);
                invalidate();
                return true;
            }

            if(e.getAction()==MotionEvent.ACTION_MOVE){
                sel.set(Math.min(sx,x),Math.min(sy,y),Math.max(sx,x),Math.max(sy,y));
                invalidate();
                return true;
            }
            return true;
        }

        RectF selection(){
            if(getWidth()<=0 || getHeight()<=0 || sel.isEmpty()) return null;
            return new RectF(sel.left/getWidth(),sel.top/getHeight(),
                    sel.right/getWidth(),sel.bottom/getHeight());
        }
    }

    // ---------- tiny UI helpers ----------

    private Button ovButton(String s,int fill) {
        Button b=new Button(this);b.setAllCaps(false);b.setText(s);b.setTextColor(Color.WHITE);b.setTextSize(10.5f);
        b.setTypeface(Typeface.DEFAULT,Typeface.BOLD);b.setPadding(dp(2),0,dp(2),0);
        b.setBackground(roundBg(fill,Color.rgb(60,88,118),9));return b;
    }
    private TextView text(String s,float size,int color,boolean bold) {
        TextView t=new TextView(this);t.setText(s);t.setTextSize(size);t.setTextColor(color);
        t.setTypeface(Typeface.DEFAULT,bold?Typeface.BOLD:Typeface.NORMAL);return t;
    }
    private GradientDrawable roundBg(int fill,int stroke,int radius) {
        GradientDrawable g=new GradientDrawable();g.setColor(fill);g.setCornerRadius(dp(radius));g.setStroke(dp(1),stroke);return g;
    }
    private void gap(LinearLayout l){Space s=new Space(this);l.addView(s,new LinearLayout.LayoutParams(dp(4),1));}
    private int dp(int v){return Math.round(v*getResources().getDisplayMetrics().density);}
}
