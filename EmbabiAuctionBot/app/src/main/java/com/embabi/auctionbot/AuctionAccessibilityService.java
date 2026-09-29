package com.embabi.auctionbot;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.os.Bundle;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.ArrayDeque;
import java.util.Locale;

public class AuctionAccessibilityService extends AccessibilityService {
    private static volatile AuctionAccessibilityService instance;

    public interface Callback {
        void onDone(boolean ok);
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
    }

    public static boolean isReady() {
        return instance != null;
    }

    public static void clickPlus(float fallbackX, float fallbackY, Callback callback) {
        AuctionAccessibilityService s = instance;
        if (s == null) {
            if (callback != null) callback.onDone(false);
            return;
        }
        if (s.clickNode("+", "increase", "add bid", "bid +")) {
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
        if (s.clickNode("confirm", "confirm bid", "place bid")) {
            if (callback != null) callback.onDone(true);
            return;
        }
        tap(fallbackX, fallbackY, callback);
    }

    private boolean clickNode(String... labels) {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return false;

        ArrayDeque<AccessibilityNodeInfo> q = new ArrayDeque<>();
        q.add(root);

        while (!q.isEmpty()) {
            AccessibilityNodeInfo node = q.removeFirst();
            CharSequence t = node.getText();
            CharSequence d = node.getContentDescription();
            String a = t == null ? "" : t.toString().trim().toLowerCase(Locale.US);
            String b = d == null ? "" : d.toString().trim().toLowerCase(Locale.US);

            for (String label : labels) {
                String wanted = label.toLowerCase(Locale.US);
                if (a.equals(wanted) || b.equals(wanted)) {
                    AccessibilityNodeInfo clickable = node;
                    while (clickable != null && !clickable.isClickable()) {
                        clickable = clickable.getParent();
                    }
                    if (clickable != null && clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                        return true;
                    }
                }
            }

            for (int i = 0; i < node.getChildCount(); i++) {
                AccessibilityNodeInfo child = node.getChild(i);
                if (child != null) q.addLast(child);
            }
        }
        return false;
    }

    public static void tap(float x, float y, Callback callback) {
        AuctionAccessibilityService s = instance;
        if (s == null) {
            if (callback != null) callback.onDone(false);
            return;
        }

        Path p = new Path();
        p.moveTo(x, y);
        GestureDescription.StrokeDescription stroke =
                new GestureDescription.StrokeDescription(p, 0, 70);
        GestureDescription gesture =
                new GestureDescription.Builder().addStroke(stroke).build();

        boolean accepted = s.dispatchGesture(gesture, new GestureResultCallback() {
            @Override
            public void onCompleted(GestureDescription gestureDescription) {
                if (callback != null) callback.onDone(true);
            }

            @Override
            public void onCancelled(GestureDescription gestureDescription) {
                if (callback != null) callback.onDone(false);
            }
        }, null);

        if (!accepted && callback != null) callback.onDone(false);
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {}
    @Override public void onInterrupt() {}

    @Override
    public void onDestroy() {
        if (instance == this) instance = null;
        super.onDestroy();
    }
}
