package com.anas.x100zoom;

import android.content.ContentValues;
import android.database.Cursor;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.TextView;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * V12 stabilization layer on top of V11.
 *
 * Fixes the photo-mode problems found on the real vivo X100:
 *  - photo ratio controls no longer occupy/overlap the VIDEO 4K/FPS badge
 *  - PHOTO gets a dedicated top row: Ratio + Normal/MAX
 *  - 1:1 / 4:3 / 16:9 / Full immediately change the live framing overlay
 *  - newly saved JPEGs are checked after capture; if the requested ratio was not
 *    actually written, the existing V11 real JPEG crop is re-applied
 *  - MediaStore WIDTH/HEIGHT are refreshed after JPEG post-processing
 *  - larger touch targets and press feedback for photo controls
 */
public class V12CameraActivity extends V11CameraActivity {
    private final Handler v12Ui = new Handler(Looper.getMainLooper());

    private LinearLayout photoTopRow;
    private TextView v12RatioButton;
    private TextView v12QualityButton;
    private RatioFrameView ratioFrame;

    private Uri lastObservedPhoto;
    private long lastMediaPollMs = 0L;

    private final Runnable v12Watcher = new Runnable() {
        @Override public void run() {
            syncChrome();
            long now = SystemClock.elapsedRealtime();
            if (now - lastMediaPollMs >= 500L) {
                lastMediaPollMs = now;
                watchLatestPhoto();
            }
            v12Ui.postDelayed(this, 80L);
        }
    };

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        v12Ui.postDelayed(this::installV12Ui, 1600L);
        v12Ui.postDelayed(v12Watcher, 1750L);
    }

    @Override protected void onDestroy() {
        v12Ui.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    private int dp12(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private GradientDrawable rounded12(int color, int radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp12(radiusDp));
        return d;
    }

    private void installV12Ui() {
        View texture = field("textureView", View.class);
        if (texture == null || !(texture.getParent() instanceof FrameLayout)) return;
        FrameLayout root = (FrameLayout) texture.getParent();

        ratioFrame = new RatioFrameView();
        FrameLayout.LayoutParams frameLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT);
        int insertIndex = Math.min(1, root.getChildCount());
        root.addView(ratioFrame, insertIndex, frameLp);

        photoTopRow = new LinearLayout(this);
        photoTopRow.setOrientation(LinearLayout.HORIZONTAL);
        photoTopRow.setGravity(Gravity.CENTER_VERTICAL);
        photoTopRow.setPadding(0, 0, 0, 0);
        photoTopRow.setVisibility(View.GONE);
        photoTopRow.setElevation(dp12(8));

        v12RatioButton = topButton("4:3  ▾");
        v12RatioButton.setOnClickListener(v -> showRatioMenu(v));
        photoTopRow.addView(v12RatioButton,
                new LinearLayout.LayoutParams(dp12(82), dp12(48)));

        v12QualityButton = topButton("NORMAL  ▾");
        LinearLayout.LayoutParams qlp = new LinearLayout.LayoutParams(dp12(104), dp12(48));
        qlp.leftMargin = dp12(8);
        photoTopRow.addView(v12QualityButton, qlp);
        v12QualityButton.setOnClickListener(v -> showQualityMenu(v));

        FrameLayout.LayoutParams rowLp = new FrameLayout.LayoutParams(dp12(194), dp12(50));
        rowLp.gravity = Gravity.TOP | Gravity.START;
        rowLp.leftMargin = dp12(14);
        rowLp.topMargin = dp12(14);
        root.addView(photoTopRow, rowLp);

        syncChrome();
    }

    private TextView topButton(String text) {
        TextView button = new TextView(this);
        button.setText(text);
        button.setTextColor(Color.WHITE);
        button.setTextSize(13f);
        button.setTypeface(null, android.graphics.Typeface.BOLD);
        button.setGravity(Gravity.CENTER);
        button.setPadding(dp12(8), 0, dp12(8), 0);
        button.setMinWidth(dp12(48));
        button.setMinHeight(dp12(48));
        button.setBackground(rounded12(0x99000000, 12));
        button.setClickable(true);
        button.setFocusable(true);
        addPressFeedback(button);
        return button;
    }

    private void addPressFeedback(View view) {
        view.setOnTouchListener((v, e) -> {
            if (e.getActionMasked() == MotionEvent.ACTION_DOWN) v.setAlpha(0.60f);
            else if (e.getActionMasked() == MotionEvent.ACTION_UP ||
                    e.getActionMasked() == MotionEvent.ACTION_CANCEL) v.setAlpha(1f);
            return false;
        });
    }

    private void showRatioMenu(View anchor) {
        PopupMenu menu = new PopupMenu(this, anchor);
        menu.getMenu().add("1:1");
        menu.getMenu().add("4:3");
        menu.getMenu().add("16:9");
        menu.getMenu().add("Full");
        menu.setOnMenuItemClickListener(item -> {
            String ratio = item.getTitle().toString();
            invokeAny("selectRatio", new Class[]{String.class}, ratio);
            setStringField("photoRatio", ratio);
            if (v12RatioButton != null) v12RatioButton.setText(ratio + "  ▾");
            if (ratioFrame != null) ratioFrame.setRatio(ratio);
            return true;
        });
        menu.show();
    }

    private void showQualityMenu(View anchor) {
        PopupMenu menu = new PopupMenu(this, anchor);
        menu.getMenu().add("Normal photo");
        menu.getMenu().add("Maximum megapixels");
        menu.getMenu().add("Timer Off");
        menu.getMenu().add("Timer 3s");
        menu.getMenu().add("Timer 5s");
        menu.getMenu().add("Timer 10s");
        menu.setOnMenuItemClickListener(item -> {
            String title = item.getTitle().toString();
            if (title.startsWith("Normal")) {
                invokeAny("setPhotoMax", new Class[]{boolean.class}, false);
                setBooleanField12("maxPhotoMode", false);
            } else if (title.startsWith("Maximum")) {
                invokeAny("setPhotoMax", new Class[]{boolean.class}, true);
                setBooleanField12("maxPhotoMode", true);
            } else if (title.endsWith("Off")) {
                invokeAny("selectTimer", new Class[]{int.class}, 0);
                setIntField("photoTimerSeconds", 0);
            } else if (title.endsWith("3s")) {
                invokeAny("selectTimer", new Class[]{int.class}, 3);
                setIntField("photoTimerSeconds", 3);
            } else if (title.endsWith("5s")) {
                invokeAny("selectTimer", new Class[]{int.class}, 5);
                setIntField("photoTimerSeconds", 5);
            } else if (title.endsWith("10s")) {
                invokeAny("selectTimer", new Class[]{int.class}, 10);
                setIntField("photoTimerSeconds", 10);
            }
            syncChrome();
            return true;
        });
        menu.show();
    }

    /** Keep the original V11 popup widgets hidden and drive a clean V12 top row. */
    private void syncChrome() {
        boolean photo = booleanField("photoMode");

        TextView oldRatio = field("ratioButton", TextView.class);
        if (oldRatio != null) oldRatio.setVisibility(View.GONE);
        LinearLayout oldPanel = field("photoPanel", LinearLayout.class);
        if (oldPanel != null) oldPanel.setVisibility(View.GONE);
        setBooleanField12("photoPanelOpen", false);

        TextView modeBadge = field("modeBadge", TextView.class);
        if (modeBadge != null) modeBadge.setVisibility(photo ? View.GONE : View.VISIBLE);

        if (photoTopRow != null) photoTopRow.setVisibility(photo ? View.VISIBLE : View.GONE);

        String ratio = stringField("photoRatio", "4:3");
        if (v12RatioButton != null) v12RatioButton.setText(ratio + "  ▾");
        boolean max = booleanField("maxPhotoMode");
        if (v12QualityButton != null) v12QualityButton.setText(max ? "MAX MP  ▾" : "NORMAL  ▾");

        if (ratioFrame != null) {
            ratioFrame.setRatio(ratio);
            ratioFrame.setVisibility(photo ? View.VISIBLE : View.GONE);
        }
    }

    private void watchLatestPhoto() {
        Uri newest = queryLatestOwnPhoto12();
        if (newest == null || newest.equals(lastObservedPhoto)) return;
        lastObservedPhoto = newest;
        // Let V11's own post-process run first. Then verify the actual file, not text state.
        v12Ui.postDelayed(() -> verifySavedRatio(newest), 1700L);
    }

    private Uri queryLatestOwnPhoto12() {
        try {
            Uri base = MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
            String[] projection = {MediaStore.Images.Media._ID};
            String selection = MediaStore.Images.Media.RELATIVE_PATH + "=? AND " +
                    MediaStore.Images.Media.DISPLAY_NAME + " LIKE ?";
            String[] args = {"DCIM/Camera/", "X100_%"};
            try (Cursor c = getContentResolver().query(base, projection, selection, args,
                    MediaStore.Images.Media.DATE_ADDED + " DESC")) {
                if (c != null && c.moveToFirst()) {
                    return Uri.withAppendedPath(base, String.valueOf(c.getLong(0)));
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    private void verifySavedRatio(Uri uri) {
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            try (java.io.InputStream in = getContentResolver().openInputStream(uri)) {
                if (in == null) return;
                BitmapFactory.decodeStream(in, null, bounds);
            }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return;

            String ratio = stringField("photoRatio", "4:3");
            float expected = wideRatioFor(ratio);
            float actual = Math.max(bounds.outWidth, bounds.outHeight) /
                    (float) Math.max(1, Math.min(bounds.outWidth, bounds.outHeight));

            if (Math.abs(actual - expected) > 0.035f && !"4:3".equals(ratio)) {
                float zoom = floatField("requestedUiZoom", 1f);
                Object hw = invokeAny("currentHardwareUiMax", new Class[]{});
                float hardwareMax = hw instanceof Float ? (Float) hw : 10f;
                invokeAny("postProcessPhoto",
                        new Class[]{Uri.class, float.class, float.class, String.class},
                        uri, zoom, hardwareMax, ratio);
                v12Ui.postDelayed(() -> syncMediaDimensions(uri), 1400L);
            } else {
                syncMediaDimensions(uri);
            }
        } catch (Exception ignored) {}
    }

    private void syncMediaDimensions(Uri uri) {
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            try (java.io.InputStream in = getContentResolver().openInputStream(uri)) {
                if (in == null) return;
                BitmapFactory.decodeStream(in, null, bounds);
            }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return;
            ContentValues values = new ContentValues();
            values.put(MediaStore.Images.Media.WIDTH, bounds.outWidth);
            values.put(MediaStore.Images.Media.HEIGHT, bounds.outHeight);
            getContentResolver().update(uri, values, null, null);
        } catch (Exception ignored) {}
    }

    private float wideRatioFor(String ratio) {
        if ("1:1".equals(ratio)) return 1f;
        if ("16:9".equals(ratio)) return 16f / 9f;
        if ("Full".equals(ratio)) {
            int w = getResources().getDisplayMetrics().widthPixels;
            int h = getResources().getDisplayMetrics().heightPixels;
            return Math.max(w, h) / (float) Math.max(1, Math.min(w, h));
        }
        return 4f / 3f;
    }

    private final class RatioFrameView extends View {
        private final Paint shade = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint edge = new Paint(Paint.ANTI_ALIAS_FLAG);
        private String ratio = "4:3";

        RatioFrameView() {
            super(V12CameraActivity.this);
            shade.setColor(0xA8000000);
            edge.setColor(0xBFFFFFFF);
            edge.setStyle(Paint.Style.STROKE);
            edge.setStrokeWidth(dp12(1));
            setClickable(false);
            setFocusable(false);
        }

        void setRatio(String value) {
            if (value == null) value = "4:3";
            if (!value.equals(ratio)) {
                ratio = value;
                invalidate();
            }
        }

        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            if ("Full".equals(ratio) || getWidth() <= 0 || getHeight() <= 0) return;

            float availableLeft = 0f;
            float availableTop = 0f;
            float availableRight = getWidth();
            float availableBottom = Math.max(dp12(300), getHeight() - dp12(260));
            float availableW = availableRight - availableLeft;
            float availableH = availableBottom - availableTop;

            float portraitWH;
            if ("1:1".equals(ratio)) portraitWH = 1f;
            else if ("16:9".equals(ratio)) portraitWH = 9f / 16f;
            else portraitWH = 3f / 4f;

            float frameW = availableW;
            float frameH = frameW / portraitWH;
            if (frameH > availableH) {
                frameH = availableH;
                frameW = frameH * portraitWH;
            }

            float left = (getWidth() - frameW) / 2f;
            float top = availableTop + (availableH - frameH) / 2f;
            float right = left + frameW;
            float bottom = top + frameH;

            canvas.drawRect(0, 0, getWidth(), top, shade);
            canvas.drawRect(0, bottom, getWidth(), getHeight(), shade);
            canvas.drawRect(0, top, left, bottom, shade);
            canvas.drawRect(right, top, getWidth(), bottom, shade);
            canvas.drawRect(left, top, right, bottom, edge);
        }

        @Override public boolean onTouchEvent(MotionEvent event) {
            return false;
        }
    }

    @SuppressWarnings("unchecked")
    private <T> T field(String name, Class<T> type) {
        Class<?> c = getClass();
        while (c != null) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                Object value = f.get(this);
                return value == null ? null : (T) value;
            } catch (NoSuchFieldException e) {
                c = c.getSuperclass();
            } catch (Exception e) {
                return null;
            }
        }
        return null;
    }

    private boolean booleanField(String name) {
        Class<?> c = getClass();
        while (c != null) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f.getBoolean(this);
            } catch (NoSuchFieldException e) {
                c = c.getSuperclass();
            } catch (Exception e) {
                return false;
            }
        }
        return false;
    }

    private float floatField(String name, float fallback) {
        Class<?> c = getClass();
        while (c != null) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f.getFloat(this);
            } catch (NoSuchFieldException e) {
                c = c.getSuperclass();
            } catch (Exception e) {
                return fallback;
            }
        }
        return fallback;
    }

    private String stringField(String name, String fallback) {
        Class<?> c = getClass();
        while (c != null) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                Object o = f.get(this);
                return o instanceof String ? (String) o : fallback;
            } catch (NoSuchFieldException e) {
                c = c.getSuperclass();
            } catch (Exception e) {
                return fallback;
            }
        }
        return fallback;
    }

    private void setBooleanField12(String name, boolean value) {
        Class<?> c = getClass();
        while (c != null) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                f.setBoolean(this, value);
                return;
            } catch (NoSuchFieldException e) {
                c = c.getSuperclass();
            } catch (Exception e) {
                return;
            }
        }
    }

    private void setIntField(String name, int value) {
        Class<?> c = getClass();
        while (c != null) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                f.setInt(this, value);
                return;
            } catch (NoSuchFieldException e) {
                c = c.getSuperclass();
            } catch (Exception e) {
                return;
            }
        }
    }

    private void setStringField(String name, String value) {
        Class<?> c = getClass();
        while (c != null) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                f.set(this, value);
                return;
            } catch (NoSuchFieldException e) {
                c = c.getSuperclass();
            } catch (Exception e) {
                return;
            }
        }
    }

    private Object invokeAny(String name, Class<?>[] types, Object... args) {
        Class<?> c = getClass();
        while (c != null) {
            try {
                Method m = c.getDeclaredMethod(name, types);
                m.setAccessible(true);
                return m.invoke(this, args);
            } catch (NoSuchMethodException e) {
                c = c.getSuperclass();
            } catch (Exception e) {
                return null;
            }
        }
        return null;
    }
}
