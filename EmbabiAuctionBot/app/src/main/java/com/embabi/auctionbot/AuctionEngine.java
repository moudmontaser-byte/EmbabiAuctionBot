package com.embabi.auctionbot;

import android.content.Context;
import android.os.SystemClock;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

public class AuctionEngine {
    public static final String[] SLOT_NAMES = {"GK", "DEF", "CM1", "CM2", "ST"};

    public enum Action { BID, WAIT, PASS, SAFETY_PAUSE, COMPLETE }

    public static class Snapshot {
        public final int rating;
        public final int price;
        public final int myBudget;
        public final int oppBudget;
        public final boolean valid;

        public Snapshot(int rating, int price, int myBudget, int oppBudget, boolean valid) {
            this.rating = rating;
            this.price = price;
            this.myBudget = myBudget;
            this.oppBudget = oppBudget;
            this.valid = valid;
        }

        public boolean sameValues(Snapshot other) {
            return other != null &&
                    rating == other.rating &&
                    price == other.price &&
                    myBudget == other.myBudget &&
                    oppBudget == other.oppBudget &&
                    valid == other.valid;
        }
    }

    public static class Decision {
        public final Action action;
        public final int round;
        public final int softCap;
        public final int hardCap;
        public final int minRating;
        public final String reason;
        public final String opponentStyle;
        public final int opponentConfidence;
        public final int estimatedOpponentCeiling;

        Decision(Action action, int round, int softCap, int hardCap, int minRating,
                 String reason, String opponentStyle, int opponentConfidence,
                 int estimatedOpponentCeiling) {
            this.action = action;
            this.round = round;
            this.softCap = softCap;
            this.hardCap = hardCap;
            this.minRating = minRating;
            this.reason = reason;
            this.opponentStyle = opponentStyle;
            this.opponentConfidence = opponentConfidence;
            this.estimatedOpponentCeiling = estimatedOpponentCeiling;
        }
    }

    private final Context context;
    private final OpponentProfiler profiler = new OpponentProfiler();

    private int round = 1;
    private int lastPrice = -1;
    private int lastRating = -1;
    private int lastMyBudget = -1;
    private int lastOppBudget = -1;
    private int invalidFrames = 0;

    private boolean weLead = false;
    private boolean pendingOwnBid = false;
    private boolean transitionCandidate = false;
    private boolean complete = false;

    private int pendingBasePrice = -1;
    private int pendingVerifyFrames = 0;
    private long ownConfirmAt = 0L;

    public AuctionEngine(Context context) {
        this.context = context.getApplicationContext();
    }

    public int getRound() { return round; }

    // Screen-driven transition used by the v3 Accessibility bot.
    // Called only after a result screen disappears and a new live auction screen is positively recognized.
    public void advanceRoundFromScreen() {
        if (complete) return;
        if (round < 5) round++;
        else {
            complete = true;
            return;
        }
        lastPrice = -1;
        lastRating = -1;
        lastMyBudget = -1;
        lastOppBudget = -1;
        invalidFrames = 0;
        weLead = false;
        pendingOwnBid = false;
        transitionCandidate = false;
        pendingBasePrice = -1;
        pendingVerifyFrames = 0;
        ownConfirmAt = 0L;
    }
    public boolean isPendingOwnBid() { return pendingOwnBid; }
    public boolean isComplete() { return complete; }
    public OpponentProfiler getProfiler() { return profiler; }

    public void resetSession() {
        round = 1;
        lastPrice = -1;
        lastRating = -1;
        lastMyBudget = -1;
        lastOppBudget = -1;
        invalidFrames = 0;
        weLead = false;
        pendingOwnBid = false;
        transitionCandidate = false;
        complete = false;
        pendingBasePrice = -1;
        pendingVerifyFrames = 0;
        ownConfirmAt = 0L;
        profiler.reset();
    }

    public void onUnverifiedFrame() {
        invalidFrames++;
        if (lastPrice >= 0 && invalidFrames >= 3) {
            transitionCandidate = true;
        }
    }

    public void markOwnBidStarted(int price) {
        pendingOwnBid = true;
        pendingBasePrice = price;
        pendingVerifyFrames = 0;
        ownConfirmAt = 0L;
    }

    public void markConfirmDispatched() {
        ownConfirmAt = SystemClock.elapsedRealtime();
    }

    public void cancelPending() {
        pendingOwnBid = false;
        pendingBasePrice = -1;
        pendingVerifyFrames = 0;
    }

    public Decision onStableSnapshot(Snapshot s) {
        if (!s.valid) {
            onUnverifiedFrame();
            return make(Action.WAIT, 0, 0, "Screen not verified");
        }

        if (complete) {
            if (looksLikeFreshGame(s)) {
                resetSession();
            } else {
                return make(Action.COMPLETE, 0, 0, "Five auctions complete");
            }
        }

        boolean newRound = false;
        if (transitionCandidate && lastPrice >= 0) {
            boolean resetPrice = s.price <= 5 && s.price < lastPrice;
            boolean changedCard = s.rating != lastRating;
            boolean changedBudget = s.myBudget != lastMyBudget || s.oppBudget != lastOppBudget;

            if (resetPrice && (changedCard || changedBudget)) {
                if (round < 5) {
                    round++;
                    newRound = true;
                } else {
                    complete = true;
                }
            }
        }

        invalidFrames = 0;
        transitionCandidate = false;

        if (complete) {
            return make(Action.COMPLETE, 0, 0, "Five auctions complete");
        }

        if (newRound) {
            weLead = false;
            pendingOwnBid = false;
            pendingVerifyFrames = 0;
            ownConfirmAt = 0L;
            lastPrice = -1;
        }

        if (lastPrice >= 0 && s.price > lastPrice) {
            int delta = s.price - lastPrice;

            if (pendingOwnBid && s.price >= pendingBasePrice + 1) {
                pendingOwnBid = false;
                pendingVerifyFrames = 0;
                weLead = true;
            } else {
                long responseMs =
                        ownConfirmAt > 0 ? SystemClock.elapsedRealtime() - ownConfirmAt : -1L;
                profiler.recordOpponentBid(delta, s.price, responseMs);
                pendingOwnBid = false;
                pendingVerifyFrames = 0;
                weLead = false;
            }
        } else if (pendingOwnBid && s.price == pendingBasePrice) {
            pendingVerifyFrames++;
            if (pendingVerifyFrames >= 8) {
                pendingOwnBid = false;
                pendingVerifyFrames = 0;
                remember(s);
                return make(Action.SAFETY_PAUSE, 0, 0,
                        "Bid was not verified on screen");
            }
        }

        remember(s);

        if (pendingOwnBid) {
            return make(Action.WAIT, 0, 0, "Waiting for screen to verify our bid");
        }

        if (weLead) {
            return make(Action.WAIT, 0, 0, "Our bid is leading - wait for opponent");
        }

        int slot = Math.max(0, Math.min(4, round - 1));
        int min = Prefs.getMinRating(context, slot);

        if (s.rating < min) {
            return make(Action.PASS, 0, 0,
                    "OVR " + s.rating + " is below your minimum " + min +
                    " - prefer the free fallback");
        }

        int[] caps = computeCaps(s, slot);
        int soft = caps[0];
        int hard = caps[1];

        if (s.price + 1 > hard) {
            return make(Action.PASS, soft, hard,
                    "Hard cap reached - protect the five-player plan");
        }

        boolean star = s.rating >= 89;
        boolean finalStriker = slot == 4 && s.rating >= 88;

        if (finalStriker) {
            return make(Action.BID, soft, hard,
                    "Final ST 88+ - use the money, do not hoard it");
        }

        if (star) {
            return make(Action.BID, soft, hard,
                    "STAR MODE 89+ - aggressive while future budget stays protected");
        }

        if (s.price + 1 <= soft) {
            return make(Action.BID, soft, hard, "Price is good value");
        }

        String style = profiler.style();

        if (profiler.isHesitating() && s.price + 1 <= hard) {
            return make(Action.BID, soft, hard,
                    "Opponent response slowed - pressure with +1");
        }

        if ("CAUTIOUS".equals(style) && s.price + 1 <= hard) {
            return make(Action.BID, soft, hard,
                    "Cautious opponent - keep +1 pressure");
        }

        if ("AGGRESSIVE".equals(style)) {
            int pressureLimit = Math.min(hard, soft + (s.rating >= 88 ? 3 : 1));
            if (s.price + 1 <= pressureLimit) {
                return make(Action.BID, soft, hard,
                        "Controlled pressure - make aggressive opponent spend");
            }
            return make(Action.PASS, soft, hard,
                    "Aggressive opponent is overpaying - let them burn budget");
        }

        int balancedLimit = Math.min(hard, soft + 2);
        if (s.price + 1 <= balancedLimit) {
            return make(Action.BID, soft, hard, "Balanced +1 pressure");
        }

        return make(Action.PASS, soft, hard,
                "Value exhausted - save money for stronger remaining players");
    }

    private void remember(Snapshot s) {
        lastPrice = s.price;
        lastRating = s.rating;
        lastMyBudget = s.myBudget;
        lastOppBudget = s.oppBudget;
    }

    private boolean looksLikeFreshGame(Snapshot s) {
        return s.myBudget >= 98 && s.oppBudget >= 98 && s.price <= 5;
    }

    private Decision make(Action action, int soft, int hard, String reason) {
        int slot = Math.max(0, Math.min(4, round - 1));
        return new Decision(
                action,
                round,
                soft,
                hard,
                Prefs.getMinRating(context, slot),
                reason,
                profiler.style(),
                profiler.confidence(),
                profiler.estimatedCeiling()
        );
    }

    private int[] computeCaps(Snapshot s, int slot) {
        double base = fairValue(s.rating) * positionMultiplier(slot);
        double urgency = new double[]{0.76, 0.93, 1.00, 1.06, 1.20}[slot];
        double soft = base * urgency;

        int reserve = reserveForFuture(slot);
        int maxAffordable = Math.max(0, s.myBudget - reserve);

        double boost = 1.0;
        if (s.rating == 89) boost = 1.20;
        else if (s.rating == 90) boost = 1.30;
        else if (s.rating >= 91) boost = 1.42;

        double desiredHard = soft * boost + 2.0;

        // GK is usually good in this game. Do not burn early budget.
        if (slot == 0) {
            double gkLimit = Math.max(9.0, s.myBudget * (s.rating >= 90 ? 0.23 : 0.18));
            desiredHard = Math.min(desiredHard, gkLimit);
        }

        // Last player is the striker. If he is 88+, remaining money is ammunition.
        if (slot == 4) {
            if (s.rating >= 88) {
                desiredHard = s.myBudget;
                soft = Math.max(soft, Math.min(s.myBudget, base * 1.15));
            } else {
                desiredHard = Math.min(s.myBudget, Math.max(desiredHard, base * 1.10));
            }
        }

        String style = profiler.style();
        if ("CAUTIOUS".equals(style)) desiredHard += 2.0;
        if ("AGGRESSIVE".equals(style) && s.rating < 89 && slot != 4) desiredHard -= 2.0;
        if (profiler.isHesitating()) desiredHard += 1.0;

        if (s.oppBudget <= 15 && s.myBudget > s.oppBudget) desiredHard += 2.0;

        int hard = slot == 4 && s.rating >= 88
                ? s.myBudget
                : Math.max(0, Math.min(maxAffordable, (int) Math.floor(desiredHard)));

        int softInt = Math.max(0, Math.min(hard, (int) Math.floor(soft)));
        return new int[]{softInt, hard};
    }

    private int reserveForFuture(int currentSlot) {
        double reserve = 0.0;
        for (int i = currentSlot + 1; i < 5; i++) {
            int threshold = Prefs.getMinRating(context, i);
            double expected = fairValue(threshold) * positionMultiplier(i) * 0.90;
            if (i == 4) expected = Math.max(expected, 14.0);
            reserve += expected;
        }
        return (int) Math.ceil(reserve);
    }

    private double positionMultiplier(int slot) {
        return new double[]{0.72, 0.95, 1.00, 1.03, 1.15}
                [Math.max(0, Math.min(4, slot))];
    }

    private double fairValue(int rating) {
        if (rating <= 81) return 5;
        switch (rating) {
            case 82: return 6;
            case 83: return 7;
            case 84: return 9;
            case 85: return 12;
            case 86: return 15;
            case 87: return 19;
            case 88: return 24;
            case 89: return 31;
            case 90: return 39;
            case 91: return 48;
            case 92: return 58;
            case 93: return 68;
            default: return 80;
        }
    }

    public static class OpponentProfiler {
        private final List<Integer> deltas = new ArrayList<>();
        private final List<Integer> prices = new ArrayList<>();
        private final List<Long> responses = new ArrayList<>();

        void reset() {
            deltas.clear();
            prices.clear();
            responses.clear();
        }

        void recordOpponentBid(int delta, int price, long responseMs) {
            if (delta <= 0 || delta > 30) return;

            deltas.add(delta);
            prices.add(price);

            if (responseMs >= 0 && responseMs < 30000) {
                responses.add(responseMs);
            }

            if (deltas.size() > 40) deltas.remove(0);
            if (prices.size() > 40) prices.remove(0);
            if (responses.size() > 40) responses.remove(0);
        }

        public int confidence() {
            return Math.min(97, 15 + deltas.size() * 11);
        }

        public double averageIncrement() {
            if (deltas.isEmpty()) return 0.0;
            int total = 0;
            for (int d : deltas) total += d;
            return (double) total / deltas.size();
        }

        public int maxIncrement() {
            int max = 0;
            for (int d : deltas) max = Math.max(max, d);
            return max;
        }

        public boolean isHesitating() {
            if (responses.size() < 4) return false;

            int split = responses.size() / 2;
            List<Long> oldPart = new ArrayList<>(responses.subList(0, split));
            List<Long> newPart = new ArrayList<>(responses.subList(split, responses.size()));
            Collections.sort(oldPart);
            Collections.sort(newPart);

            long oldMedian = oldPart.get(oldPart.size() / 2);
            long newMedian = newPart.get(newPart.size() / 2);

            return oldMedian > 0 && newMedian > oldMedian * 1.65 && newMedian - oldMedian > 350;
        }

        public int estimatedCeiling() {
            if (prices.isEmpty()) return 0;
            int maxPrice = 0;
            for (int p : prices) maxPrice = Math.max(maxPrice, p);

            int extra;
            if ("CAUTIOUS".equals(style())) extra = 2;
            else if ("AGGRESSIVE".equals(style())) extra = Math.max(4, (int) Math.ceil(averageIncrement() * 2));
            else extra = 3;

            if (isHesitating()) extra = Math.min(extra, 2);
            return maxPrice + extra;
        }

        public String style() {
            if (deltas.size() < 2) return "LEARNING";

            int small = 0;
            int big = 0;

            for (int d : deltas) {
                if (d <= 2) small++;
                if (d >= 4) big++;
            }

            double avg = averageIncrement();
            double smallRatio = (double) small / deltas.size();
            double bigRatio = (double) big / deltas.size();

            if (avg <= 1.8 && smallRatio >= 0.65) return "CAUTIOUS";
            if (avg >= 3.0 || bigRatio >= 0.35) return "AGGRESSIVE";
            return "BALANCED";
        }

        public String summary() {
            return String.format(
                    Locale.US,
                    "%s %d%% | avg +%.1f | max +%d | est %dM%s",
                    style(),
                    confidence(),
                    averageIncrement(),
                    maxIncrement(),
                    estimatedCeiling(),
                    isHesitating() ? " | HESITATING" : ""
            );
        }
    }
}
