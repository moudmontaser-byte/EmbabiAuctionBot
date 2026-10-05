package com.embabi.auctionflex;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.PointF;
import android.graphics.RectF;

public final class Prefs {
    private static final String NAME = "embabi_flex_v20";
    private static final int CAL_SCHEMA = 32;
    public static final String[] SLOT_NAMES = {"GK", "CB", "CM1", "CM2", "ST"};
    public static final String[] EXPECTED_POSITIONS = {"GK", "CB", "CM", "CM", "ST"};
    public static final int RANGE_COUNT = 6;

    // Default auction strategy requested by the user.
    // Indexed as [slot][range] for GK, CB, CM1, CM2, ST.
    private static final int[][] DEFAULT_MIN = {
            {92,90,88,86,71,70}, // GK
            {92,90,88,84,71,70}, // CB
            {92,90,88,85,71,70}, // CM1
            {90,88,86,50,71,70}, // CM2
            {92,89,86,84,71,70}  // ST
    };

    private static final int[][] DEFAULT_MAX = {
            {99,91,89,87,71,70}, // GK
            {99,91,89,87,71,70}, // CB
            {99,91,89,87,71,70}, // CM1
            {99,89,87,50,71,70}, // CM2
            {99,91,88,85,71,70}  // ST
    };

    private static final int[][] DEFAULT_BID = {
            {45,35,25,20,0,0}, // GK
            {50,42,30,20,0,0}, // CB
            {55,45,35,25,0,0}, // CM1
            {55,45,35,25,0,0}, // CM2
            {65,45,30,10,0,0}  // ST
    };

    private Prefs() {}

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences(NAME, Context.MODE_PRIVATE);
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    public static int getRangeMin(Context c, int slot, int range) {
        slot = clamp(slot,0,4); range = clamp(range,0,RANGE_COUNT-1);
        return sp(c).getInt("rmin_"+slot+"_"+range, DEFAULT_MIN[slot][range]);
    }

    public static int getRangeMax(Context c, int slot, int range) {
        slot = clamp(slot,0,4); range = clamp(range,0,RANGE_COUNT-1);
        return sp(c).getInt("rmax_"+slot+"_"+range, DEFAULT_MAX[slot][range]);
    }

    public static int getMaxBid(Context c, int slot, int range) {
        slot = clamp(slot,0,4); range = clamp(range,0,RANGE_COUNT-1);
        return sp(c).getInt("bid_"+slot+"_"+range, DEFAULT_BID[slot][range]);
    }

    public static void setRange(Context c, int slot, int range, int min, int max, int bid) {
        slot = clamp(slot,0,4); range = clamp(range,0,RANGE_COUNT-1);
        min = clamp(min,50,99); max = clamp(max,50,99);
        if (min > max) { int t=min; min=max; max=t; }
        bid = clamp(bid,0,100);
        sp(c).edit()
                .putInt("rmin_"+slot+"_"+range,min)
                .putInt("rmax_"+slot+"_"+range,max)
                .putInt("bid_"+slot+"_"+range,bid)
                .apply();
    }

    public static int maxBidForRating(Context c, int slot, int rating) {
        for (int r=0;r<RANGE_COUNT;r++) {
            if (rating >= getRangeMin(c,slot,r) && rating <= getRangeMax(c,slot,r)) {
                return getMaxBid(c,slot,r);
            }
        }
        return 0;
    }

    public static int rangeIndexForRating(Context c, int slot, int rating) {
        for (int r=0;r<RANGE_COUNT;r++) {
            if (rating >= getRangeMin(c,slot,r) && rating <= getRangeMax(c,slot,r)) return r;
        }
        return -1;
    }

    public static int bidStep(int maxBid) {
        if (maxBid <= 0) return 0;

        // Default v36 bidding cadence:
        // - Max <= 50M: spread the bid across ~5 raises.
        // - Max > 50M: spread the bid across ~9 raises so each jump is smaller.
        // ceil() keeps whole-million clicks and the caller already caps at Max.
        double divisor = (maxBid > 50) ? 9.0 : 5.0;
        return Math.max(1, (int)Math.ceil(maxBid / divisor));
    }

    public static void saveRegion(Context c, String key, RectF n) {
        if (n == null) return;
        float l=Math.max(0f,Math.min(1f,Math.min(n.left,n.right)));
        float t=Math.max(0f,Math.min(1f,Math.min(n.top,n.bottom)));
        float r=Math.max(0f,Math.min(1f,Math.max(n.left,n.right)));
        float b=Math.max(0f,Math.min(1f,Math.max(n.top,n.bottom)));
        sp(c).edit()
                .putBoolean("roi_"+key,true)
                .putFloat("l_"+key,l).putFloat("t_"+key,t)
                .putFloat("r_"+key,r).putFloat("b_"+key,b).apply();
    }

    public static RectF getRegion(Context c, String key) {
        SharedPreferences p=sp(c);
        if (!p.getBoolean("roi_"+key,false)) return null;
        return new RectF(p.getFloat("l_"+key,0),p.getFloat("t_"+key,0),
                p.getFloat("r_"+key,0),p.getFloat("b_"+key,0));
    }

    public static void saveTapPointPx(Context c,String key,float x,float y,int w,int h) {
        sp(c).edit().putFloat("px_"+key+"_x",x).putFloat("px_"+key+"_y",y)
                .putInt("px_"+key+"_w",Math.max(1,w)).putInt("px_"+key+"_h",Math.max(1,h)).apply();
    }

    public static PointF getTapPointPx(Context c,String key,int w,int h) {
        SharedPreferences p=sp(c);
        if (!p.contains("px_"+key+"_x")) return null;
        float x=p.getFloat("px_"+key+"_x",-1), y=p.getFloat("px_"+key+"_y",-1);
        int sw=Math.max(1,p.getInt("px_"+key+"_w",w));
        int sh=Math.max(1,p.getInt("px_"+key+"_h",h));
        if (x<0 || y<0) return null;
        return new PointF(x*w/(float)sw,y*h/(float)sh);
    }

    public static void clearCalibration(Context c) {
        SharedPreferences.Editor e=sp(c).edit();
        for(String k:new String[]{"card","rating","position","price"}) {
            e.remove("roi_"+k).remove("l_"+k).remove("t_"+k).remove("r_"+k).remove("b_"+k);
        }
        for(String k:new String[]{"plus","confirm","skip"}) {
            e.remove("px_"+k+"_x").remove("px_"+k+"_y").remove("px_"+k+"_w").remove("px_"+k+"_h");
        }
        e.remove("cal_schema").apply();
    }

    public static void markCalibrated(Context c) { sp(c).edit().putInt("cal_schema",CAL_SCHEMA).apply(); }

    public static boolean isCalibrated(Context c) {
        if (sp(c).getInt("cal_schema",0)!=CAL_SCHEMA) return false;
        for(String k:new String[]{"card","price"}) if(getRegion(c,k)==null) return false;
        int w=c.getResources().getDisplayMetrics().widthPixels, h=c.getResources().getDisplayMetrics().heightPixels;
        for(String k:new String[]{"plus","confirm","skip"}) if(getTapPointPx(c,k,w,h)==null) return false;
        return true;
    }

    public static String repeatMode(Context c) { return sp(c).getString("repeat_mode","infinite"); }
    public static void setRepeatMode(Context c,String v) {
        if(!"count".equals(v)&&!"time".equals(v)) v="infinite";
        sp(c).edit().putString("repeat_mode",v).apply();
    }
    public static int repeatCount(Context c) { return Math.max(1,sp(c).getInt("repeat_count",10)); }
    public static void setRepeatCount(Context c,int v) { sp(c).edit().putInt("repeat_count",clamp(v,1,9999)).apply(); }
    public static int repeatMinutes(Context c) { return Math.max(1,sp(c).getInt("repeat_minutes",60)); }
    public static void setRepeatMinutes(Context c,int v) { sp(c).edit().putInt("repeat_minutes",clamp(v,1,14400)).apply(); }

    public static int currentRound(Context c) { return clamp(sp(c).getInt("round",1),1,5); }
    public static void setCurrentRound(Context c,int v) { sp(c).edit().putInt("round",clamp(v,1,5)).apply(); }

    public static int matches(Context c) { return Math.max(0,sp(c).getInt("matches",0)); }
    public static void setMatches(Context c,int v) { sp(c).edit().putInt("matches",Math.max(0,v)).apply(); }
    public static long startedAt(Context c) { return sp(c).getLong("started_at",0); }
    public static void setStartedAt(Context c,long v) { sp(c).edit().putLong("started_at",v).apply(); }

    public static String status(Context c) { return sp(c).getString("status","جاهز"); }
    public static void setStatus(Context c,String s) { sp(c).edit().putString("status",s==null?"":s).apply(); }
    public static long heartbeat(Context c) { return sp(c).getLong("heartbeat",0); }
    public static void setHeartbeat(Context c,long v) { sp(c).edit().putLong("heartbeat",v).apply(); }
}
