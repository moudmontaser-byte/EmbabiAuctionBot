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

    private int lastRating=-1,lastPrice=-1,lastMax=-1;
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

    private final Runnable monitor=new Runnable() {
        @Override public void run() {
            Prefs.setHeartbeat(AuctionAccessibilityService.this,System.currentTimeMillis());
            if(running && !paused) analyze();
            h.postDelayed(this,650);
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
            status("اعمل CAL 6/6 أولاً");
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
        pending=Pending.NONE; pendingRetries=0; actionBusy=false;
        startedAt=System.currentTimeMillis();
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

    private void analyze() {
        if(screenshotBusy||actionBusy||System.currentTimeMillis()<actionCooldownUntil) return;
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
        Integer guiRound=extractRound(all);
        if(guiRound!=null && guiRound>=1 && guiRound<=5) {
            if(guiRound!=round) {
                round=guiRound; Prefs.setCurrentRound(this,round);
                if(pending!=Pending.NONE && guiRound!=pendingRound) clearPending();
            }
        }

        if(handlePostFlow(root,all)) return;

        if(isAuctionScreen(all)) {
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

        if(isGamesHub(all)) {
            status("الألعاب ✓ — أفتح «المزاد»");
            clickText(root,"المزاد","auction");
            return;
        }

        if(containsAny(all,"العب الان","العب الآن","play now")) {
            if(stopAfterReturn) {
                stopBot("اكتملت المدة/العدد ✓ — رجعنا للرئيسية");
                return;
            }
            round=1; Prefs.setCurrentRound(this,1); pending=Pending.NONE;
            status("الرئيسية ✓ — أضغط «العب الآن»");
            clickText(root,"العب الان","العب الآن","play now");
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
        if(pending!=Pending.NONE) {
            if(guiRound!=null && guiRound!=pendingRound) { clearPending(); }
            else if(pending==Pending.CONFIRM && containsAny(all,"انتظار المزايدة","انتظار المزايده","waiting bid")) {
                clearPending();
                status("المزايدة اتأكدت ✓ — دور الخصم");
                return;
            } else if(pending==Pending.SKIP) {
                long age=System.currentTimeMillis()-pendingAt;
                if(age>1500 && pendingRetries<3) {
                    pendingRetries++; pendingAt=System.currentTimeMillis();
                    status("Skip لم يغيّر اللاعب بعد — إعادة المحاولة "+pendingRetries+"/3");
                    tapSaved("skip",ok->{ actionBusy=false; });
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

    private void readCardAndAct(AccessibilityNodeInfo root,String all,Integer guiRound) {
        if(screenshotBusy) return;
        screenshotBusy=true;
        takeScreenshot(android.view.Display.DEFAULT_DISPLAY,getMainExecutor(),new TakeScreenshotCallback(){
            @Override public void onSuccess(ScreenshotResult result) {
                Bitmap b=Bitmap.wrapHardwareBuffer(result.getHardwareBuffer(),result.getColorSpace());
                result.getHardwareBuffer().close();
                if(b==null){ screenshotBusy=false; status("Screenshot فشل — بدون ضغط"); return; }
                Bitmap copy=b.copy(Bitmap.Config.ARGB_8888,false);
                recognizer.process(InputImage.fromBitmap(copy,0))
                        .addOnSuccessListener(tx->{
                            String rt=textInRegion(tx,Prefs.getRegion(AuctionAccessibilityService.this,"rating"),copy.getWidth(),copy.getHeight());
                            String pt=textInRegion(tx,Prefs.getRegion(AuctionAccessibilityService.this,"position"),copy.getWidth(),copy.getHeight());
                            String mt=textInRegion(tx,Prefs.getRegion(AuctionAccessibilityService.this,"price"),copy.getWidth(),copy.getHeight());
                            copy.recycle(); screenshotBusy=false;
                            Integer rating=extractRating(rt);
                            Integer price=extractMoney(mt);
                            String pos=extractPosition(pt);
                            if(rating==null || price==null || pos==null) {
                                status("قراءة غير مؤكدة: OVR "+show(rating)+" | "+(pos==null?"?":pos)+" | "+show(price)+"M — لا أضغط");
                                return;
                            }
                            verifyPositionAndDecide(rating,pos,price,guiRound);
                        })
                        .addOnFailureListener(e->{ copy.recycle(); screenshotBusy=false; status("OCR فشل — بدون ضغط"); });
            }
            @Override public void onFailure(int errorCode) {
                screenshotBusy=false; status("تعذر Screenshot ("+errorCode+")");
            }
        });
    }

    private void verifyPositionAndDecide(int rating,String pos,int price,Integer guiRound) {
        if(guiRound!=null && guiRound>=1 && guiRound<=5 && guiRound!=round) {
            round=guiRound; Prefs.setCurrentRound(this,round);
        }

        String expected=Prefs.EXPECTED_POSITIONS[round-1];
        if(!expected.equals(pos)) {
            int unique=uniqueSlotForPosition(pos);
            if(guiRound==null && unique>round) {
                round=unique; Prefs.setCurrentRound(this,round);
                expected=Prefs.EXPECTED_POSITIONS[round-1];
            }
        }

        if(!expected.equals(pos)) {
            status("حماية الترتيب: متوقع "+expected+" في R"+round+" لكن قرأت "+pos+" — أنتظر ولا أضغط");
            return;
        }

        lastRating=rating; lastPosition=pos; lastPrice=price;
        int slot=round-1;
        int maxBid=Prefs.maxBidForRating(this,slot,rating);
        lastMax=maxBid;
        refreshOverlay();

        if(maxBid<=0) {
            status("R"+round+" "+pos+" OVR "+rating+" خارج الـ3 Ranges → SKIP");
            doSkip();
            return;
        }

        if(price>maxBid) {
            status("السعر "+price+"M أعلى من Max "+maxBid+"M → SKIP");
            doSkip();
            return;
        }

        if(price==maxBid) {
            status("وصل Max "+maxBid+"M بالضبط — Confirm بدون Skip");
            submitConfirm();
            return;
        }

        int step=Prefs.bidStep(maxBid);
        int target=Math.min(maxBid,price+step);
        int clicks=Math.max(0,target-price);
        status("R"+round+" "+pos+" OVR "+rating+" • "+price+"→"+target+"M • +"+clicks+" ثم Confirm");
        doBidClicks(clicks,target);
    }

    private int uniqueSlotForPosition(String p) {
        if("GK".equals(p)) return 1;
        if("CB".equals(p)) return 2;
        if("ST".equals(p)) return 5;
        return -1;
    }

    private void doSkip() {
        if(actionBusy) return;
        actionBusy=true;
        tapSaved("skip",ok->{
            actionBusy=false;
            if(ok) {
                pending=Pending.SKIP; pendingRound=round; pendingRetries=0; pendingAt=System.currentTimeMillis();
                status("Skip اتضغط ✓ — أتحقق إن اللاعب اتغيّر");
            } else status("فشل ضغط Skip — سأحاول في القراءة التالية");
        });
    }

    private void doBidClicks(int clicks,int target) {
        if(actionBusy) return;
        actionBusy=true;
        tapPlusSequence(clicks,()->{
            h.postDelayed(()->tapSaved("confirm",ok->{
                actionBusy=false;
                if(ok) {
                    pending=Pending.CONFIRM; pendingRound=round; pendingRetries=0; pendingAt=System.currentTimeMillis();
                    status("Confirm ✓ — Target "+target+"M، أنتظر الخصم");
                } else status("فشل Confirm — لن أضيف + جديد تلقائياً");
            }),260);
        });
    }

    private void submitConfirm() {
        if(actionBusy) return;
        actionBusy=true;
        tapSaved("confirm",ok->{
            actionBusy=false;
            if(ok) {
                pending=Pending.CONFIRM; pendingRound=round; pendingRetries=0; pendingAt=System.currentTimeMillis();
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

    private void clearPending() { pending=Pending.NONE; pendingRetries=0; pendingAt=0; pendingRound=0; }

    // ---------- Post auction / simulation / result loop ----------

    private boolean handlePostFlow(AccessibilityNodeInfo root,String all) {
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
        actionCooldownUntil=System.currentTimeMillis()+800;
        dispatchGesture(g,new GestureResultCallback(){
            @Override public void onCompleted(GestureDescription d){h.postDelayed(scanOnce,720);}
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

    private void cooldown(){ actionCooldownUntil=System.currentTimeMillis()+700; h.postDelayed(scanOnce,760); }

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

    private Integer extractRating(String raw) {
        String s=cleanDigits(raw);
        Matcher m=Pattern.compile("(?<!\\d)([5-9]\\d)(?!\\d)").matcher(s);
        while(m.find())try{int v=Integer.parseInt(m.group(1));if(v>=50&&v<=99)return v;}catch(Exception ignored){}
        return null;
    }

    private Integer extractMoney(String raw) {
        String s=cleanDigits(raw);
        Matcher m=Pattern.compile("(?<!\\d)(100|[0-9]{1,2})(?!\\d)").matcher(s);
        while(m.find())try{int v=Integer.parseInt(m.group(1));if(v>=0&&v<=100)return v;}catch(Exception ignored){}
        return null;
    }

    private String extractPosition(String raw) {
        if(raw==null)return null;
        String s=raw.toUpperCase(Locale.US).replace(" ","").replace("\n","");
        if(s.contains("GK")||s.contains("6K"))return "GK";
        if(s.contains("CB"))return "CB";
        if(s.contains("CM"))return "CM";
        if(s.contains("ST"))return "ST";
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
        TextView drag=text("AUCTION FLEX",12,Color.WHITE,true);
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

    // ---------- Calibration 6/6 ----------

    private void startCalibration() {
        running=false;paused=false;clearPending();actionBusy=false;
        removeOverlay();removeCalibration();Prefs.clearCalibration(this);
        calibrationStep=0;showCalibrationStep();
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

        LinearLayout bottom=new LinearLayout(this);bottom.setPadding(dp(8),dp(8),dp(8),dp(8));
        bottom.setBackgroundColor(Color.argb(230,5,14,27));
        Button cancel=ovButton("إلغاء",Color.rgb(88,35,48));
        Button save=ovButton(calibrationStep<3?"حفظ / التالي ✓":"المس المكان على الشاشة",Color.rgb(31,149,255));
        bottom.addView(cancel,new LinearLayout.LayoutParams(0,dp(50),1));gap(bottom);
        bottom.addView(save,new LinearLayout.LayoutParams(0,dp(50),2));
        frame.addView(bottom,new FrameLayout.LayoutParams(-1,dp(68),Gravity.BOTTOM));

        cancel.setOnClickListener(v->{removeCalibration();showOverlay();status("تم إلغاء CAL");});
        save.setOnClickListener(v->{
            if(calibrationStep>=3)return;
            RectF r=calibrationView.selection();
            if(r==null||r.width()<.012f||r.height()<.010f){Toast.makeText(this,"ارسم مربع واضح حول المطلوب",Toast.LENGTH_SHORT).show();return;}
            String key=new String[]{"rating","position","price"}[calibrationStep];
            Prefs.saveRegion(this,key,r);calibrationStep++;showCalibrationStep();
        });

        calibrationRoot=frame;
        WindowManager.LayoutParams lp=new WindowManager.LayoutParams(-1,-1,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN|WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        lp.gravity=Gravity.TOP|Gravity.START;
        try{wm.addView(frame,lp);}catch(Exception e){calibrationRoot=null;showOverlay();status("تعذر فتح CAL");}
    }

    private String calibrationLabel() {
        switch(calibrationStep) {
            case 0:return "1/6 — ارسم مربع صغير حول رقم تقييم اللاعب فقط (مثلاً 87)";
            case 1:return "2/6 — ارسم مربع حول المركز فقط (GK / CB / CM / ST)";
            case 2:return "3/6 — ارسم مربع حول قيمة المزايدة الحالية فقط";
            case 3:return "4/6 — المس منتصف زر +";
            case 4:return "5/6 — المس منتصف زر «تأكيد المزايدة»";
            default:return "6/6 — المس منتصف زر «تخطي اللاعب»";
        }
    }

    private void onCalibrationTap(float x,float y) {
        if(calibrationStep<3||calibrationStep>5)return;
        int w=getResources().getDisplayMetrics().widthPixels,hg=getResources().getDisplayMetrics().heightPixels;
        String key=calibrationStep==3?"plus":calibrationStep==4?"confirm":"skip";
        Prefs.saveTapPointPx(this,key,x,y,w,hg);
        calibrationStep++;
        if(calibrationStep>5) {
            Prefs.markCalibrated(this);removeCalibration();showOverlay();
            status(Prefs.isCalibrated(this)?"CAL 6/6 READY ✓":"CAL غير مكتمل");
        } else showCalibrationStep();
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
            super(c);border.setStyle(Paint.Style.STROKE);border.setStrokeWidth(dp(3));border.setColor(Color.rgb(40,195,255));
            shade.setColor(Color.argb(70,0,0,0));label.setColor(Color.WHITE);label.setTextSize(dp(14));label.setTypeface(Typeface.DEFAULT_BOLD);
        }
        @Override protected void onDraw(Canvas c){
            super.onDraw(c);
            if(calibrationStep<3 && !sel.isEmpty()){
                c.drawRect(0,0,getWidth(),sel.top,shade);c.drawRect(0,sel.bottom,getWidth(),getHeight(),shade);
                c.drawRect(0,sel.top,sel.left,sel.bottom,shade);c.drawRect(sel.right,sel.top,getWidth(),sel.bottom,shade);
                c.drawRoundRect(sel,dp(8),dp(8),border);c.drawText("OCR",sel.left+dp(5),Math.max(dp(92),sel.top-dp(5)),label);
            } else if(calibrationStep>=3) {
                c.drawText(calibrationStep==3?"TAP +":calibrationStep==4?"TAP CONFIRM":"TAP SKIP",dp(18),dp(105),label);
            }
        }
        @Override public boolean onTouchEvent(MotionEvent e){
            float x=Math.max(0,Math.min(getWidth(),e.getX()));
            float y=Math.max(dp(78),Math.min(getHeight()-dp(72),e.getY()));
            if(calibrationStep>=3){
                if(e.getAction()==MotionEvent.ACTION_DOWN){float rx=e.getRawX(),ry=e.getRawY();h.post(()->onCalibrationTap(rx,ry));}
                return true;
            }
            if(e.getAction()==MotionEvent.ACTION_DOWN){sx=x;sy=y;sel.set(x,y,x+1,y+1);invalidate();return true;}
            if(e.getAction()==MotionEvent.ACTION_MOVE){sel.set(Math.min(sx,x),Math.min(sy,y),Math.max(sx,x),Math.max(sy,y));invalidate();return true;}
            return true;
        }
        RectF selection(){
            if(getWidth()<=0||getHeight()<=0||sel.isEmpty())return null;
            return new RectF(sel.left/getWidth(),sel.top/getHeight(),sel.right/getWidth(),sel.bottom/getHeight());
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
