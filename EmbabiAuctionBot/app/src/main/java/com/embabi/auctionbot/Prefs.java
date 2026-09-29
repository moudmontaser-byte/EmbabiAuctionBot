package com.embabi.auctionbot;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.PointF;

public final class Prefs {
    private static final String NAME = "auction_genius";
    private static final int[] DEFAULT_MIN = {84, 85, 85, 85, 86};

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
        sp(c).edit().putFloat("x_" + key, nx).putFloat("y_" + key, ny).apply();
    }

    public static PointF getPoint(Context c, String key) {
        SharedPreferences p = sp(c);
        String xk = "x_" + key;
        String yk = "y_" + key;
        if (!p.contains(xk) || !p.contains(yk)) return null;
        return new PointF(p.getFloat(xk, -1f), p.getFloat(yk, -1f));
    }

    public static boolean isCalibrated(Context c) {
        String[] keys = {"plus", "confirm", "rating", "price", "mine", "opponent"};
        for (String k : keys) {
            if (getPoint(c, k) == null) return false;
        }
        return true;
    }

    public static void clearCalibration(Context c) {
        SharedPreferences.Editor e = sp(c).edit();
        String[] keys = {"plus", "confirm", "rating", "price", "mine", "opponent"};
        for (String k : keys) {
            e.remove("x_" + k);
            e.remove("y_" + k);
        }
        e.apply();
    }
}
