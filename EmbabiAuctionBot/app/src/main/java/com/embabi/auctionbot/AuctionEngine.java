package com.embabi.auctionbot;

import android.content.Context;
import android.os.SystemClock;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Five-auction decision engine.
 *
 * Design goals:
 * 1) Build the strongest five-player team possible with a hard 100M budget.
 * 2) Treat money as a dynamic resource across sequential auctions, not as five isolated bids.
 * 3) Learn the opponent's observable bidding behaviour inside the current match.
 * 4) Use cheap "probe" bids on only safe, medium-value cards to learn willingness-to-pay.
 * 5) Never diagnose a person. The profiler classifies bidding behaviour only.
 */
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

    // A single cheap probe per safe round. The point is information, not gambling.
    private final boolean[] probeUsed = new boolean[5];
    private boolean pendingBidWasProbe = false;
    private boolean lastOpponentReplyWasToProbe = false;

    public AuctionEngine(Context context) {
        this.context = context.getApplicationContext();
    }

    public int getRound() { return round; }

    // The game UI itself shows 1/5 ... 5/5. When that signal is visible it is
    // more reliable than inferring the round from elapsed time or transitions.
    public void syncRoundFromScreen(int guiRound) {
        int target = Math.max(1, Math.min(5, guiRound));
        if (target == round) return;

        round = target;
        complete = false;
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
        pendingBidWasProbe = false;
        lastOpponentReplyWasToProbe = false;
        profiler.startRound(round);
    }


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
        pendingBidWasProbe = false;
        lastOpponentReplyWasToProbe = false;
        profiler.startRound(round);
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
        pendingBidWasProbe = false;
        lastOpponentReplyWasToProbe = false;

        for (int i = 0; i < probeUsed.length; i++) probeUsed[i] = false;

        profiler.reset();
        profiler.startRound(1);
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
        pendingBidWasProbe = false;
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

        // Back-up transition detection. The Accessibility layer is the primary source of round changes.
        boolean newRound = false;
        if (transitionCandidate && lastPrice >= 0) {
            boolean resetPrice = s.price <= 5 && s.price < lastPrice;
            boolean changedCard = s.rating != lastRating;
            boolean changedBudget = s.myBudget != lastMyBudget || s.oppBudget != lastOppBudget;

            if (resetPrice && (changedCard || changedBudget)) {
                if (round < 5) {
                    round++;
                    newRound = true;
                    profiler.startRound(round);
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
            pendingBidWasProbe = false;
            lastOpponentReplyWasToProbe = false;
            lastPrice = -1;
        }

        // Learn from price movement.
        if (lastPrice >= 0 && s.price > lastPrice) {
            int delta = s.price - lastPrice;

            if (pendingOwnBid && s.price >= pendingBasePrice + 1) {
                // Our +1 was accepted by the game.
                pendingOwnBid = false;
                pendingVerifyFrames = 0;
                weLead = true;
            } else {
                long responseMs =
                        ownConfirmAt > 0 ? SystemClock.elapsedRealtime() - ownConfirmAt : -1L;

                lastOpponentReplyWasToProbe = pendingBidWasProbe;
                profiler.recordOpponentBid(
                        delta,
                        s.price,
                        responseMs,
                        s.oppBudget,
                        lastOpponentReplyWasToProbe
                );

                pendingOwnBid = false;
                pendingVerifyFrames = 0;
                pendingBidWasProbe = false;
                weLead = false;
            }
        } else if (pendingOwnBid && s.price == pendingBasePrice) {
            pendingVerifyFrames++;
            if (pendingVerifyFrames >= 8) {
                pendingOwnBid = false;
                pendingVerifyFrames = 0;
                pendingBidWasProbe = false;
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

        // User's hard rule: below minimum = normally let the opponent take it and use free fallback.
        if (s.rating < min) {
            return make(Action.PASS, 0, 0,
                    "OVR " + s.rating + " < min " + min +
                    " - keep money and prefer the free fallback");
        }

        int[] caps = computeCaps(s, slot);
        int soft = caps[0];
        int hard = caps[1];

        // You never need more than opponent's entire remaining budget + 1M to beat them.
        int strategicCeiling = Math.min(hard, Math.max(0, s.oppBudget + 1));
        hard = Math.min(hard, strategicCeiling);

        if (s.price + 1 > hard) {
            return make(Action.PASS, soft, hard,
                    "Hard cap reached - protect the 100M five-player plan");
        }

        boolean star = s.rating >= 89;
        boolean elite = s.rating >= 91;
        boolean finalStriker = slot == 4 && s.rating >= 88;

        // Last player: unused money has no future value.
        if (finalStriker) {
            return make(Action.BID, soft, hard,
                    "FINAL ST 88+ - spend remaining budget intelligently");
        }

        // Elite/star player: intentionally much more stubborn, but still not irrational.
        if (elite) {
            return make(Action.BID, soft, hard,
                    "ELITE 91+ - fight hard; future reserve is already protected");
        }

        if (star) {
            return make(Action.BID, soft, hard,
                    "STAR 89+ - keep +1 pressure inside the protected team budget");
        }

        // Cheap information probe on a medium card.
        if (shouldProbe(s, slot, min, soft, hard)) {
            probeUsed[slot] = true;
            pendingBidWasProbe = true;
            return make(Action.BID, soft, hard,
                    "PROBE +1 - cheap test of opponent willingness, safe if we win cheaply");
        }

        // If a probe made the opponent instantly jump, do not chase a merely average card.
        if (lastOpponentReplyWasToProbe &&
                profiler.lastResponseWasStrong() &&
                s.rating <= min + 2 &&
                s.price >= Math.max(4, soft - 2)) {
            lastOpponentReplyWasToProbe = false;
            return make(Action.PASS, soft, hard,
                    "Probe exposed strong interest - let opponent burn budget on a medium card");
        }

        if (s.price + 1 <= soft) {
            return make(Action.BID, soft, hard,
                    "Good value - +1 keeps information and avoids overpaying");
        }

        String coarse = profiler.style();
        String type = profiler.archetype();

        // Slowdown after several fast counters is useful evidence that we are near the opponent's comfort edge.
        if (profiler.isHesitating() && s.price + 1 <= hard) {
            return make(Action.BID, soft, hard,
                    type + " is slowing down - controlled +1 pressure");
        }

        if ("CAUTIOUS".equals(coarse) && s.price + 1 <= hard) {
            return make(Action.BID, soft, hard,
                    type + " - keep +1 pressure, never gift them a large jump");
        }

        // Repeated chasers/jumpers are useful to us on medium cards: make them spend, then stop.
        if ("AGGRESSIVE".equals(coarse)) {
            int margin = s.rating >= 88 ? 4 : 1;
            int pressureLimit = Math.min(hard, soft + margin);

            if (profiler.isChaser() && s.rating <= min + 2) {
                pressureLimit = Math.min(pressureLimit, soft);
            }

            if (s.price + 1 <= pressureLimit) {
                return make(Action.BID, soft, hard,
                        type + " - controlled +1 pressure; make them pay");
            }

            return make(Action.PASS, soft, hard,
                    type + " is overpaying - let them deplete their budget");
        }

        // If opponent has already spent much more than us, preserve the advantage unless the card is strong.
        int budgetEdge = s.myBudget - s.oppBudget;
        if (budgetEdge >= 18 && s.rating <= min + 2 && s.price >= soft) {
            return make(Action.PASS, soft, hard,
                    "We already own a strong budget edge - no need to chase a marginal card");
        }

        int balancedLimit = Math.min(hard, soft + (s.rating >= 88 ? 3 : 2));
        if (s.price + 1 <= balancedLimit) {
            return make(Action.BID, soft, hard,
                    type + " - balanced +1 pressure");
        }

        return make(Action.PASS, soft, hard,
                "Value exhausted - save money for a stronger remaining player");
    }

    private boolean shouldProbe(Snapshot s, int slot, int min, int soft, int hard) {
        if (slot <= 0 || slot >= 4) return false;      // not GK, not final ST
        if (probeUsed[slot]) return false;
        if (s.rating < min || s.rating > min + 2) return false;
        if (s.price > 4) return false;
        if (s.price + 1 > Math.min(hard, Math.max(4, soft / 2))) return false;
        if (s.myBudget < 45) return false;

        // Probe is most valuable while we are still learning this opponent.
        return profiler.confidence() < 72;
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
                profiler.archetype(),
                profiler.confidence(),
                profiler.estimatedCeiling()
        );
    }

    private int[] computeCaps(Snapshot s, int slot) {
        int min = Prefs.getMinRating(context, slot);

        double base = fairValue(s.rating) * positionMultiplier(slot);
        double qualityAboveMinimum = Math.max(0, s.rating - min);

        // Marginal cards get less budget. True upgrades get progressively more.
        double qualityFactor;
        if (qualityAboveMinimum == 0) qualityFactor = .78;
        else if (qualityAboveMinimum == 1) qualityFactor = .88;
        else if (qualityAboveMinimum == 2) qualityFactor = .98;
        else if (qualityAboveMinimum == 3) qualityFactor = 1.08;
        else qualityFactor = 1.16;

        double urgency = new double[]{0.70, 0.92, 1.00, 1.06, 1.22}[slot];
        double soft = base * urgency * qualityFactor;

        int reserve = reserveForFuture(slot, s.myBudget);
        int maxAffordable = Math.max(0, s.myBudget - reserve);

        double starBoost = 1.0;
        if (s.rating == 89) starBoost = 1.22;
        else if (s.rating == 90) starBoost = 1.34;
        else if (s.rating == 91) starBoost = 1.48;
        else if (s.rating == 92) starBoost = 1.58;
        else if (s.rating >= 93) starBoost = 1.68;

        double desiredHard = soft * starBoost + 2.0;

        // GK usually has acceptable quality; preserve ammunition for DEF/CM/ST.
        if (slot == 0) {
            double gkShare;
            if (s.rating >= 91) gkShare = .25;
            else if (s.rating >= 89) gkShare = .21;
            else gkShare = .16;

            desiredHard = Math.min(desiredHard, Math.max(8.0, s.myBudget * gkShare));
        }

        // Final striker: no future reserve. If 88+, remaining money is useful now, not later.
        if (slot == 4) {
            if (s.rating >= 88) {
                desiredHard = s.myBudget;
                soft = Math.max(soft, Math.min(s.myBudget, base * 1.18));
            } else {
                desiredHard = Math.min(s.myBudget, Math.max(desiredHard, base * 1.05));
            }
        }

        String style = profiler.style();

        if ("CAUTIOUS".equals(style)) desiredHard += 2.0;

        if ("AGGRESSIVE".equals(style) && s.rating < 89 && slot != 4) {
            desiredHard -= profiler.isChaser() ? 3.0 : 1.5;
        }

        if (profiler.isHesitating()) desiredHard += 1.5;

        // A budget-depleted opponent can often be beaten cheaply.
        if (s.oppBudget <= 18 && s.myBudget > s.oppBudget) {
            desiredHard = Math.min(desiredHard + 2.0, s.oppBudget + 1.0);
        }

        // Never set a rational hard ceiling above what is necessary to beat the other full budget.
        desiredHard = Math.min(desiredHard, s.oppBudget + 1.0);

        int hard = slot == 4 && s.rating >= 88
                ? Math.min(s.myBudget, s.oppBudget + 1)
                : Math.max(0, Math.min(maxAffordable, (int) Math.floor(desiredHard)));

        int softInt = Math.max(0, Math.min(hard, (int) Math.floor(soft)));
        return new int[]{softInt, hard};
    }

    private int reserveForFuture(int currentSlot, int currentBudget) {
        if (currentSlot >= 4) return 0;

        double reserve = 0.0;

        for (int i = currentSlot + 1; i < 5; i++) {
            int threshold = Prefs.getMinRating(context, i);
            double target = fairValue(threshold) * positionMultiplier(i);

            // We do not reserve the whole theoretical value because losing can still give a free player.
            double reserveFactor = .70;

            // But reserve serious money for the final striker.
            if (i == 4) reserveFactor = .82;

            // CM2 and ST deserve slightly more late-game flexibility.
            if (i == 3) reserveFactor = .74;

            reserve += target * reserveFactor;
        }

        // Never reserve so much that a true star in the current slot becomes impossible.
        int ceiling = Math.max(0, currentBudget - 5);
        return Math.min(ceiling, (int) Math.ceil(reserve));
    }

    private double positionMultiplier(int slot) {
        return new double[]{0.70, 0.95, 1.00, 1.04, 1.18}
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

    /**
     * Observable bidding-behaviour model.
     *
     * These labels are strategy archetypes, not psychological diagnoses.
     * It learns from increment sizes, counter frequency, response speed,
     * slowdown near higher prices, and reaction to cheap probe bids.
     */
    public static class OpponentProfiler {
        private final List<Integer> deltas = new ArrayList<>();
        private final List<Integer> prices = new ArrayList<>();
        private final List<Long> responses = new ArrayList<>();
        private final List<Integer> budgets = new ArrayList<>();

        private int currentRound = 1;
        private int countersThisRound = 0;
        private int maxCountersInRound = 0;
        private int quickCounters = 0;
        private int probeReplies = 0;
        private int strongProbeReplies = 0;
        private boolean lastStrongResponse = false;

        void reset() {
            deltas.clear();
            prices.clear();
            responses.clear();
            budgets.clear();

            currentRound = 1;
            countersThisRound = 0;
            maxCountersInRound = 0;
            quickCounters = 0;
            probeReplies = 0;
            strongProbeReplies = 0;
            lastStrongResponse = false;
        }

        void startRound(int round) {
            maxCountersInRound = Math.max(maxCountersInRound, countersThisRound);
            countersThisRound = 0;
            currentRound = Math.max(1, Math.min(5, round));
            lastStrongResponse = false;
        }

        void recordOpponentBid(int delta, int price, long responseMs,
                               int opponentBudget, boolean replyToProbe) {
            if (delta <= 0 || delta > 30) return;

            deltas.add(delta);
            prices.add(price);
            budgets.add(opponentBudget);

            countersThisRound++;
            maxCountersInRound = Math.max(maxCountersInRound, countersThisRound);

            if (responseMs >= 0 && responseMs < 30000) {
                responses.add(responseMs);
                if (responseMs <= 1200) quickCounters++;
            }

            boolean strong = delta >= 3 ||
                    (responseMs >= 0 && responseMs <= 850);

            lastStrongResponse = strong;

            if (replyToProbe) {
                probeReplies++;
                if (strong) strongProbeReplies++;
            }

            trim(deltas, 50);
            trim(prices, 50);
            trim(responses, 50);
            trim(budgets, 50);
        }

        private <T> void trim(List<T> list, int max) {
            while (list.size() > max) list.remove(0);
        }

        public boolean lastResponseWasStrong() {
            return lastStrongResponse;
        }

        public int confidence() {
            int evidence = deltas.size() * 9 +
                    Math.min(12, responses.size() * 2) +
                    Math.min(8, probeReplies * 4);
            return Math.min(98, 12 + evidence);
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

        public double smallIncrementRatio() {
            if (deltas.isEmpty()) return 0.0;
            int n = 0;
            for (int d : deltas) if (d <= 2) n++;
            return (double) n / deltas.size();
        }

        public double jumpRatio() {
            if (deltas.isEmpty()) return 0.0;
            int n = 0;
            for (int d : deltas) if (d >= 4) n++;
            return (double) n / deltas.size();
        }

        public double quickRatio() {
            if (responses.isEmpty()) return 0.0;
            return (double) quickCounters / responses.size();
        }

        public boolean isChaser() {
            return maxCountersInRound >= 4 ||
                    (countersThisRound >= 3 && quickRatio() >= .55);
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

            return oldMedian > 0 &&
                    newMedian > oldMedian * 1.55 &&
                    newMedian - oldMedian > 300;
        }

        public int estimatedCeiling() {
            if (prices.isEmpty()) return 0;

            int maxPrice = 0;
            for (int p : prices) maxPrice = Math.max(maxPrice, p);

            int extra;
            String c = style();

            if ("CAUTIOUS".equals(c)) extra = 2;
            else if ("AGGRESSIVE".equals(c)) extra =
                    Math.max(4, (int) Math.ceil(averageIncrement() * 2.2));
            else extra = 3;

            if (isChaser()) extra += 2;
            if (isHesitating()) extra = Math.min(extra, 2);

            if (!budgets.isEmpty()) {
                int remaining = budgets.get(budgets.size() - 1);
                extra = Math.min(extra, Math.max(0, remaining));
            }

            return maxPrice + extra;
        }

        public String style() {
            if (deltas.size() < 2) return "LEARNING";

            double avg = averageIncrement();
            double small = smallIncrementRatio();
            double jumps = jumpRatio();

            if (avg <= 1.8 && small >= .65) return "CAUTIOUS";
            if (avg >= 3.0 || jumps >= .30 || isChaser()) return "AGGRESSIVE";
            return "BALANCED";
        }

        public String archetype() {
            if (deltas.size() < 2) return "LEARNING";

            if (isHesitating()) return "HESITATOR";
            if (isChaser() && quickRatio() >= .50) return "FAST CHASER";
            if (jumpRatio() >= .35 || averageIncrement() >= 3.2) return "JUMPER";
            if (smallIncrementRatio() >= .75 && averageIncrement() <= 1.7) return "NIBBLER";
            if (quickRatio() >= .70) return "FAST PRESSER";
            if (probeReplies >= 2 && strongProbeReplies == 0) return "BUDGET GUARD";
            return "BALANCED";
        }

        public String summary() {
            return String.format(
                    Locale.US,
                    "%s %d%% | avg +%.1f | small %.0f%% | quick %.0f%% | est %dM%s",
                    archetype(),
                    confidence(),
                    averageIncrement(),
                    smallIncrementRatio() * 100.0,
                    quickRatio() * 100.0,
                    estimatedCeiling(),
                    isHesitating() ? " | slowing" : ""
            );
        }
    }
}
