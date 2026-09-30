package com.embabi.auctionbot;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.PointF;
import android.graphics.RectF;

public final class Prefs {
    private static final String NAME = "auction_genius";
    private static final int[] DEFAULT_MIN = {84, 85, 85, 85, 86};

    private static final String[] ROI_KEYS = {"rating", "price", "mine", "opponent"};

    private Prefs() {}

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences(NAME, Context.MODE_PRIVATE);
    }

    public static int getMinRating(Context c, int slot) {
        slot = Math.max(0, Math.min(4, slot));
        return sp(c).getInt("min_" + slot, DEFAULT_MIN[slot]);
    }

    public static void setMinRating(Context c, int slot, int value) {
        slot = Math.max(0, Math.min(4, slot));
        value = Math.max(70, Math.min(99, value));
        sp(c).edit().putInt("min_" + slot, value).apply();
    }

    public static void savePoint(Context c, String key, float nx, float ny) {
        sp(c).edit()
                .putFloat("x_" + key, clamp01(nx))
                .putFloat("y_" + key, clamp01(ny))
                .apply();
    }

    public static void saveTapPointPx(Context c, String key,
                                      float rawX, float rawY,
                                      int screenW, int screenH) {
        sp(c).edit()
                .putFloat("px_x_" + key, rawX)
                .putFloat("px_y_" + key, rawY)
                .putInt("px_w_" + key, Math.max(1, screenW))
                .putInt("px_h_" + key, Math.max(1, screenH))
                .apply();
    }

    public static PointF getTapPointPx(Context c, String key, int currentW, int currentH) {
        SharedPreferences p = sp(c);
        if (!p.contains("px_x_" + key) || !p.contains("px_y_" + key)) return null;

        float x = p.getFloat("px_x_" + key, -1f);
        float y = p.getFloat("px_y_" + key, -1f);
        int savedW = Math.max(1, p.getInt("px_w_" + key, currentW));
        int savedH = Math.max(1, p.getInt("px_h_" + key, currentH));

        if (x < 0 || y < 0) return null;

        // Same phone/orientation: this is exactly the pixel the user touched.
        if (savedW == currentW && savedH == currentH) {
            return new PointF(x, y);
        }

        // Fallback for a small display-size change.
        return new PointF(
                x * currentW / (float) savedW,
                y * currentH / (float) savedH
        );
    }

    public static PointF getPoint(Context c, String key) {
        SharedPreferences p = sp(c);
        String xk = "x_" + key;
        String yk = "y_" + key;
        if (!p.contains(xk) || !p.contains(yk)) return null;
        return new PointF(p.getFloat(xk, -1f), p.getFloat(yk, -1f));
    }

    public static void saveRegion(Context c, String key, RectF n) {
        if (n == null) return;
        RectF r = new RectF(
                clamp01(Math.min(n.left, n.right)),
                clamp01(Math.min(n.top, n.bottom)),
                clamp01(Math.max(n.left, n.right)),
                clamp01(Math.max(n.top, n.bottom))
        );
        sp(c).edit()
                .putBoolean("roi_set_" + key, true)
                .putFloat("roi_l_" + key, r.left)
                .putFloat("roi_t_" + key, r.top)
                .putFloat("roi_r_" + key, r.right)
                .putFloat("roi_b_" + key, r.bottom)
                .putFloat("x_" + key, r.centerX())
                .putFloat("y_" + key, r.centerY())
                .apply();
    }

    public static RectF getRegion(Context c, String key) {
        SharedPreferences p = sp(c);
        if (!p.getBoolean("roi_set_" + key, false)) return null;
        return new RectF(
                p.getFloat("roi_l_" + key, 0f),
                p.getFloat("roi_t_" + key, 0f),
                p.getFloat("roi_r_" + key, 0f),
                p.getFloat("roi_b_" + key, 0f)
        );
    }

    public static boolean hasRegion(Context c, String key) {
        return getRegion(c, key) != null;
    }

    public static boolean isCalibrated(Context c) {
        for (String key : ROI_KEYS) {
            RectF r = getRegion(c, key);
            if (r == null || r.width() < .015f || r.height() < .012f) return false;
        }
        return getPoint(c, "plus") != null && getPoint(c, "confirm") != null;
    }

    public static void clearCalibration(Context c) {
        SharedPreferences.Editor e = sp(c).edit();
        String[] points = {"plus", "confirm", "rating", "price", "mine", "opponent"};
        for (String k : points) {
            e.remove("x_" + k);
            e.remove("y_" + k);
            e.remove("px_x_" + k);
            e.remove("px_y_" + k);
            e.remove("px_w_" + k);
            e.remove("px_h_" + k);
        }
        for (String k : ROI_KEYS) {
            e.remove("roi_set_" + k);
            e.remove("roi_l_" + k);
            e.remove("roi_t_" + k);
            e.remove("roi_r_" + k);
            e.remove("roi_b_" + k);
        }
        e.apply();
    }

    public static String repeatMode(Context c) {
        return sp(c).getString("repeat_mode", "infinite");
    }

    public static void setRepeatMode(Context c, String mode) {
        if (!"count".equals(mode) && !"time".equals(mode)) mode = "infinite";
        sp(c).edit().putString("repeat_mode", mode).apply();
    }

    public static int repeatCount(Context c) {
        return Math.max(1, sp(c).getInt("repeat_count", 10));
    }

    public static void setRepeatCount(Context c, int value) {
        sp(c).edit().putInt("repeat_count", Math.max(1, Math.min(999, value))).apply();
    }

    public static int repeatMinutes(Context c) {
        return Math.max(1, sp(c).getInt("repeat_minutes", 60));
    }

    public static void setRepeatMinutes(Context c, int value) {
        sp(c).edit().putInt("repeat_minutes", Math.max(1, Math.min(1440, value))).apply();
    }

    public static boolean botWanted(Context c) {
        return sp(c).getBoolean("bot_wanted", false);
    }

    public static void setBotWanted(Context c, boolean value) {
        sp(c).edit().putBoolean("bot_wanted", value).apply();
    }

    public static boolean botPaused(Context c) {
        return sp(c).getBoolean("bot_paused", false);
    }

    public static void setBotPaused(Context c, boolean value) {
        sp(c).edit().putBoolean("bot_paused", value).apply();
    }

    public static boolean overlayWanted(Context c) {
        return sp(c).getBoolean("overlay_wanted", true);
    }

    public static void setOverlayWanted(Context c, boolean value) {
        sp(c).edit().putBoolean("overlay_wanted", value).apply();
    }

    public static int currentRound(Context c) {
        return Math.max(1, Math.min(5, sp(c).getInt("current_round", 1)));
    }

    public static void setCurrentRound(Context c, int round) {
        sp(c).edit().putInt("current_round", Math.max(1, Math.min(5, round))).apply();
    }

    public static int matchesCompleted(Context c) {
        return Math.max(0, sp(c).getInt("matches_completed", 0));
    }

    public static void setMatchesCompleted(Context c, int value) {
        sp(c).edit().putInt("matches_completed", Math.max(0, value)).apply();
    }

    public static long botStartedAt(Context c) {
        return sp(c).getLong("bot_started_at", 0L);
    }

    public static void setBotStartedAt(Context c, long value) {
        sp(c).edit().putLong("bot_started_at", value).apply();
    }

    public static long accessHeartbeat(Context c) {
        return sp(c).getLong("access_heartbeat", 0L);
    }

    public static void setAccessHeartbeat(Context c, long value) {
        sp(c).edit().putLong("access_heartbeat", value).apply();
    }

    public static String lastStatus(Context c) {
        return sp(c).getString("last_status", "جاهز — افتح Embabi Games");
    }

    public static void setLastStatus(Context c, String value) {
        sp(c).edit().putString("last_status", value == null ? "" : value).apply();
    }

    public static void saveLastSnapshot(Context c, Integer rating, Integer price, Integer mine, Integer opp, String decision) {
        SharedPreferences.Editor e = sp(c).edit();
        e.putInt("last_rating", rating == null ? -1 : rating);
        e.putInt("last_price", price == null ? -1 : price);
        e.putInt("last_mine", mine == null ? -1 : mine);
        e.putInt("last_opp", opp == null ? -1 : opp);
        e.putString("last_decision", decision == null ? "—" : decision);
        e.apply();
    }

    public static int lastRating(Context c) { return sp(c).getInt("last_rating", -1); }
    public static int lastPrice(Context c) { return sp(c).getInt("last_price", -1); }
    public static int lastMine(Context c) { return sp(c).getInt("last_mine", -1); }
    public static int lastOpp(Context c) { return sp(c).getInt("last_opp", -1); }
    public static String lastDecision(Context c) { return sp(c).getString("last_decision", "—"); }

    private static float clamp01(float v) {
        return Math.max(0f, Math.min(1f, v));
    }
}