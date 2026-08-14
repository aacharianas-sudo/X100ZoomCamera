package com.anas.x100zoom;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * V8 input layer.
 *
 * Keeps V7 AUTO/MANUAL focus behavior and adds camera-rocker style zoom controls:
 *  - quick tap +/- = one normal zoom step
 *  - press and hold +/- = continuous smooth zoom until release
 */
public class SmoothZoomActivity extends AutoManualFocusActivity {
    private static final long HOLD_START_MS = 180L;
    private static final long HOLD_FRAME_MS = 16L;

    private final Handler zoomUi = new Handler(Looper.getMainLooper());

    private Method setDesiredZoomMethod;
    private Method nudgeZoomMethod;
    private Field requestedUiZoomField;

    private boolean zoomHolding = false;
    private boolean holdStarted = false;
    private int holdDirection = 0;
    private float holdTargetZoom = 1f;

    private final Runnable continuousZoomRunnable = new Runnable() {
        @Override public void run() {
            if (!zoomHolding || holdDirection == 0) return;

            float z = holdTargetZoom;
            float perFrame;

            if (z < 3f) {
                perFrame = 0.025f;
            } else if (z < 10f) {
                perFrame = 0.050f;
            } else {
                perFrame = 0.080f;
            }

            holdTargetZoom += holdDirection * perFrame;
            if (holdTargetZoom < 0.6f) holdTargetZoom = 0.6f;
            if (holdTargetZoom > 50f) holdTargetZoom = 50f;

            setDesiredZoom(holdTargetZoom, true);

            if ((holdDirection < 0 && holdTargetZoom <= 0.6f) ||
                    (holdDirection > 0 && holdTargetZoom >= 50f)) {
                stopHolding();
                return;
            }

            zoomUi.postDelayed(this, HOLD_FRAME_MS);
        }
    };

    private final Runnable beginHoldRunnable = () -> {
        if (!zoomHolding || holdDirection == 0) return;
        holdStarted = true;
        holdTargetZoom = getRequestedZoom();
        zoomUi.post(continuousZoomRunnable);
    };

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prepareReflection();
        zoomUi.postDelayed(this::installHoldZoomControls, 250L);
    }

    @Override protected void onDestroy() {
        zoomUi.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    private void prepareReflection() {
        try {
            setDesiredZoomMethod = MainActivity.class.getDeclaredMethod(
                    "setDesiredZoom", float.class, boolean.class);
            setDesiredZoomMethod.setAccessible(true);

            nudgeZoomMethod = MainActivity.class.getDeclaredMethod("nudgeZoom", int.class);
            nudgeZoomMethod.setAccessible(true);

            requestedUiZoomField = MainActivity.class.getDeclaredField("requestedUiZoom");
            requestedUiZoomField.setAccessible(true);
        } catch (Exception ignored) {}
    }

    private void installHoldZoomControls() {
        View root = findViewById(android.R.id.content);
        TextView minus = findTextView(root, "−");
        TextView plus = findTextView(root, "+");

        if (minus != null) attachZoomRocker(minus, -1);
        if (plus != null) attachZoomRocker(plus, 1);
    }

    private void attachZoomRocker(TextView button, int direction) {
        button.setOnClickListener(null);
        button.setOnTouchListener((v, event) -> {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    holdDirection = direction;
                    zoomHolding = true;
                    holdStarted = false;
                    holdTargetZoom = getRequestedZoom();
                    v.setAlpha(0.62f);
                    zoomUi.removeCallbacks(beginHoldRunnable);
                    zoomUi.removeCallbacks(continuousZoomRunnable);
                    zoomUi.postDelayed(beginHoldRunnable, HOLD_START_MS);
                    return true;

                case MotionEvent.ACTION_UP:
                    boolean wasHold = holdStarted;
                    stopHolding();
                    v.setAlpha(1f);
                    if (!wasHold) nudgeZoom(direction);
                    v.performClick();
                    return true;

                case MotionEvent.ACTION_CANCEL:
                    stopHolding();
                    v.setAlpha(1f);
                    return true;
            }
            return true;
        });
    }

    private void stopHolding() {
        zoomHolding = false;
        holdStarted = false;
        holdDirection = 0;
        zoomUi.removeCallbacks(beginHoldRunnable);
        zoomUi.removeCallbacks(continuousZoomRunnable);
    }

    private float getRequestedZoom() {
        try {
            if (requestedUiZoomField != null) {
                return requestedUiZoomField.getFloat(this);
            }
        } catch (Exception ignored) {}
        return 1f;
    }

    private void setDesiredZoom(float zoom, boolean syncSlider) {
        try {
            if (setDesiredZoomMethod != null) {
                setDesiredZoomMethod.invoke(this, zoom, syncSlider);
            }
        } catch (Exception ignored) {}
    }

    private void nudgeZoom(int direction) {
        try {
            if (nudgeZoomMethod != null) {
                nudgeZoomMethod.invoke(this, direction);
            }
        } catch (Exception ignored) {}
    }

    private TextView findTextView(View root, String exactText) {
        if (root == null) return null;
        if (root instanceof TextView) {
            CharSequence t = ((TextView) root).getText();
            if (t != null && exactText.contentEquals(t)) return (TextView) root;
        }
        if (root instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) root;
            for (int i = 0; i < g.getChildCount(); i++) {
                TextView found = findTextView(g.getChildAt(i), exactText);
                if (found != null) return found;
            }
        }
        return null;
    }
}
