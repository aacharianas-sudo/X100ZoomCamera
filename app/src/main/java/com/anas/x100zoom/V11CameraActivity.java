package com.anas.x100zoom;

import android.content.ContentResolver;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapRegionDecoder;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Rect;
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
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.OptIn;
import androidx.media3.common.Effect;
import androidx.media3.common.MediaItem;
import androidx.media3.common.audio.AudioProcessor;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.effect.MatrixTransformation;
import androidx.media3.transformer.Composition;
import androidx.media3.transformer.EditedMediaItem;
import androidx.media3.transformer.Effects;
import androidx.media3.transformer.ExportException;
import androidx.media3.transformer.ExportResult;
import androidx.media3.transformer.Transformer;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * V11 integration layer.
 *
 * Real features added on top of V10:
 *  - dedicated PHOTO chrome/settings (ratio, timer, NORMAL/MAX, grid shortcut)
 *  - real 1:1 / 4:3 / 16:9 / Full crop applied to saved JPEG
 *  - 0.6x..100x UI; > HAL zoom is center-cropped into the actual saved JPEG
 *  - video > HAL zoom is baked into the saved MP4 with Media3 GPU transformation
 *  - camera-specific launcher icon is provided by the manifest/resources
 */
@OptIn(markerClass = UnstableApi.class)
public class V11CameraActivity extends PhotoVideoActivity {
    private static final float MIN_ZOOM = 0.6f;
    private static final float MAX_ZOOM = 100f;
    private static final long HOLD_START_MS = 180L;
    private static final long HOLD_FRAME_MS = 16L;

    private final Handler ui = new Handler(Looper.getMainLooper());

    private TextView ratioButton;
    private LinearLayout photoPanel;
    private TextView countdownView;
    private TextView exportStatus;
    private ExtendedZoomView extendedSlider;

    private String photoRatio = "4:3";
    private int photoTimerSeconds = 0;
    private boolean photoPanelOpen = false;
    private boolean photoCountdownRunning = false;

    private boolean zoomHolding = false;
    private boolean holdStarted = false;
    private int holdDirection = 0;
    private float holdTarget = 1f;

    private Uri beforePhotoUri;
    private float pendingPhotoZoom = 1f;
    private float pendingPhotoHardwareMax = 10f;
    private String pendingPhotoRatio = "4:3";

    private boolean lastRecording = false;
    private long recordingStartElapsed = 0L;
    private final ArrayList<ZoomSample> recordingZoom = new ArrayList<>();
    private float lastRecordedExtra = -1f;
    private Transformer activeTransformer;
    private boolean processingVideo = false;

    private final Runnable modeWatcher = new Runnable() {
        @Override public void run() {
            updatePhotoVideoChrome();
            watchRecordingState();
            ui.postDelayed(this, 60L);
        }
    };

    private final Runnable holdRunnable = new Runnable() {
        @Override public void run() {
            if (!zoomHolding || holdDirection == 0) return;
            holdStarted = true;
            float z = holdTarget;
            float step;
            if (z < 3f) step = 0.025f;
            else if (z < 10f) step = 0.055f;
            else if (z < 30f) step = 0.14f;
            else if (z < 60f) step = 0.30f;
            else step = 0.48f;
            holdTarget = clamp(holdTarget + holdDirection * step, MIN_ZOOM, MAX_ZOOM);
            setV11Zoom(holdTarget);
            if ((holdDirection < 0 && holdTarget <= MIN_ZOOM) ||
                    (holdDirection > 0 && holdTarget >= MAX_ZOOM)) {
                stopZoomHold();
            } else {
                ui.postDelayed(this, HOLD_FRAME_MS);
            }
        }
    };

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ui.postDelayed(this::installV11Ui, 1250L);
        ui.postDelayed(modeWatcher, 1350L);
    }

    @Override protected void onDestroy() {
        ui.removeCallbacksAndMessages(null);
        if (activeTransformer != null) {
            try { activeTransformer.cancel(); } catch (Exception ignored) {}
        }
        super.onDestroy();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private GradientDrawable rounded(int color, int radius) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(radius));
        return d;
    }

    private void installV11Ui() {
        View texture = getField("textureView", View.class);
        if (texture == null || !(texture.getParent() instanceof FrameLayout)) return;
        FrameLayout root = (FrameLayout) texture.getParent();

        installExtendedZoom(root);
        installPhotoSettings(root);
        installCountdown(root);
        installExportStatus(root);
        replaceShutterBehavior(root);
        updatePhotoVideoChrome();
    }

    private void installExtendedZoom(FrameLayout root) {
        TextView minus = findText(root, "−");
        TextView plus = findText(root, "+");
        if (minus == null || plus == null || !(minus.getParent() instanceof LinearLayout)) return;
        LinearLayout zoomRow = (LinearLayout) minus.getParent();

        if (zoomRow.getChildCount() >= 3) {
            View original = zoomRow.getChildAt(1);
            original.setVisibility(View.GONE);
            extendedSlider = new ExtendedZoomView();
            zoomRow.addView(extendedSlider, 1,
                    new LinearLayout.LayoutParams(0, dp(68), 1f));
        }

        attachHoldButton(minus, -1);
        attachHoldButton(plus, 1);
        setV11Zoom(getRequestedZoom());
    }

    private void attachHoldButton(TextView button, int direction) {
        button.setOnClickListener(null);
        button.setOnTouchListener((v, event) -> {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    zoomHolding = true;
                    holdStarted = false;
                    holdDirection = direction;
                    holdTarget = getRequestedZoom();
                    v.setAlpha(0.62f);
                    ui.removeCallbacks(holdRunnable);
                    ui.postDelayed(holdRunnable, HOLD_START_MS);
                    return true;
                case MotionEvent.ACTION_UP:
                    boolean didHold = holdStarted;
                    stopZoomHold();
                    v.setAlpha(1f);
                    if (!didHold) nudgeV11(direction);
                    v.performClick();
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    stopZoomHold();
                    v.setAlpha(1f);
                    return true;
                default:
                    return true;
            }
        });
    }

    private void stopZoomHold() {
        zoomHolding = false;
        holdStarted = false;
        holdDirection = 0;
        ui.removeCallbacks(holdRunnable);
    }

    private void nudgeV11(int direction) {
        float z = getRequestedZoom();
        float step = z < 3f ? 0.1f : (z < 10f ? 0.2f : (z < 30f ? 0.5f : 1f));
        setV11Zoom(z + direction * step);
    }

    /**
     * Keeps MainActivity's proven lens-switch path through 50x, then extends the
     * requested value to 100x. The parent Camera2 code still clamps hardware crop
     * to the HAL range; preview + output post-processing supplies only the excess.
     */
    private void setV11Zoom(float value) {
        float z = clamp(value, MIN_ZOOM, MAX_ZOOM);
        if (z < 10f) z = Math.round(z * 10f) / 10f;
        else if (z < 30f) z = Math.round(z * 2f) / 2f;
        else z = Math.round(z);

        // Call the existing route/Camera2 logic. It is internally capped at 50x.
        invoke("setDesiredZoom", new Class[]{float.class, boolean.class}, Math.min(z, 50f), false);

        if (z > 50f) {
            setFloatField("requestedUiZoom", z);
            TextView live = getField("zoomLiveView", TextView.class);
            if (live != null) live.setText(formatZoom(z));
            invoke("applyExtraPreviewCrop", new Class[]{});
            invoke("scheduleZoomApply", new Class[]{});
        }

        if (extendedSlider != null) extendedSlider.setZoom(z);
    }

    private void installPhotoSettings(FrameLayout root) {
        ratioButton = new TextView(this);
        ratioButton.setText("4:3  ▾");
        ratioButton.setTextColor(Color.WHITE);
        ratioButton.setTextSize(14f);
        ratioButton.setTypeface(null, android.graphics.Typeface.BOLD);
        ratioButton.setGravity(Gravity.CENTER);
        ratioButton.setBackground(rounded(0x99000000, 12));
        ratioButton.setOnClickListener(v -> {
            photoPanelOpen = !photoPanelOpen;
            updatePhotoVideoChrome();
        });
        FrameLayout.LayoutParams rbLp = new FrameLayout.LayoutParams(dp(78), dp(48));
        rbLp.gravity = Gravity.TOP | Gravity.START;
        rbLp.leftMargin = dp(14);
        rbLp.topMargin = dp(14);
        root.addView(ratioButton, rbLp);

        photoPanel = new LinearLayout(this);
        photoPanel.setOrientation(LinearLayout.VERTICAL);
        photoPanel.setPadding(dp(12), dp(12), dp(12), dp(12));
        photoPanel.setBackground(rounded(0xE6151515, 18));
        photoPanel.setVisibility(View.GONE);

        TextView title = new TextView(this);
        title.setText("PHOTO SETTINGS");
        title.setTextColor(Color.WHITE);
        title.setTextSize(14f);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        title.setGravity(Gravity.CENTER_VERTICAL);
        photoPanel.addView(title, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(32)));

        photoPanel.addView(sectionLabel("Aspect ratio"));
        LinearLayout ratioRow = row();
        ratioRow.addView(settingButton("1:1", () -> selectRatio("1:1")), weightLp());
        ratioRow.addView(settingButton("4:3", () -> selectRatio("4:3")), weightLp());
        ratioRow.addView(settingButton("16:9", () -> selectRatio("16:9")), weightLp());
        ratioRow.addView(settingButton("Full", () -> selectRatio("Full")), weightLp());
        photoPanel.addView(ratioRow, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(50)));

        photoPanel.addView(sectionLabel("Timer"));
        LinearLayout timerRow = row();
        timerRow.addView(settingButton("Off", () -> selectTimer(0)), weightLp());
        timerRow.addView(settingButton("3s", () -> selectTimer(3)), weightLp());
        timerRow.addView(settingButton("5s", () -> selectTimer(5)), weightLp());
        timerRow.addView(settingButton("10s", () -> selectTimer(10)), weightLp());
        photoPanel.addView(timerRow, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(50)));

        photoPanel.addView(sectionLabel("Quality & framing"));
        LinearLayout qualityRow = row();
        qualityRow.addView(settingButton("NORMAL", () -> setPhotoMax(false)), weightLp());
        qualityRow.addView(settingButton("MAX MP", () -> setPhotoMax(true)), weightLp());
        qualityRow.addView(settingButton("GRID", this::toggleGridReal), weightLp());
        photoPanel.addView(qualityRow, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(50)));

        TextView note = new TextView(this);
        note.setText("MAX MP uses the largest JPEG / maximum-resolution sensor mode that Vivo actually exposes to Camera2.");
        note.setTextColor(0xFFBDBDBD);
        note.setTextSize(10f);
        note.setPadding(dp(4), dp(4), dp(4), 0);
        photoPanel.addView(note, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(42)));

        FrameLayout.LayoutParams panelLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, dp(292));
        panelLp.gravity = Gravity.TOP;
        panelLp.leftMargin = dp(10);
        panelLp.rightMargin = dp(10);
        panelLp.topMargin = dp(72);
        root.addView(photoPanel, panelLp);
    }

    private TextView sectionLabel(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(0xFFD0D0D0);
        t.setTextSize(11f);
        t.setGravity(Gravity.CENTER_VERTICAL);
        return t;
    }

    private LinearLayout row() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER);
        return row;
    }

    private LinearLayout.LayoutParams weightLp() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, dp(42), 1f);
        p.setMargins(dp(3), dp(3), dp(3), dp(3));
        return p;
    }

    private TextView settingButton(String text, Runnable action) {
        TextView b = new TextView(this);
        b.setText(text);
        b.setTextColor(Color.WHITE);
        b.setTextSize(12f);
        b.setTypeface(null, android.graphics.Typeface.BOLD);
        b.setGravity(Gravity.CENTER);
        b.setBackground(rounded(0xFF343434, 10));
        b.setOnClickListener(v -> action.run());
        return b;
    }

    private void selectRatio(String ratio) {
        photoRatio = ratio;
        if (ratioButton != null) ratioButton.setText(ratio + "  ▾");
        toast("Photo ratio: " + ratio);
    }

    private void selectTimer(int seconds) {
        photoTimerSeconds = seconds;
        toast(seconds == 0 ? "Photo timer off" : "Photo timer: " + seconds + "s");
    }

    private void setPhotoMax(boolean max) {
        setBooleanField("maxPhotoMode", max);
        invoke("updateBadgeForCurrentMode", new Class[]{});
        toast(max ? "MAX MP enabled" : "Normal photo quality");
    }

    private void toggleGridReal() {
        invoke("toggleGrid", new Class[]{});
    }

    private void installCountdown(FrameLayout root) {
        countdownView = new TextView(this);
        countdownView.setTextColor(Color.WHITE);
        countdownView.setTextSize(64f);
        countdownView.setTypeface(null, android.graphics.Typeface.BOLD);
        countdownView.setGravity(Gravity.CENTER);
        countdownView.setShadowLayer(10f, 0f, 2f, Color.BLACK);
        countdownView.setVisibility(View.GONE);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.CENTER;
        root.addView(countdownView, lp);
    }

    private void installExportStatus(FrameLayout root) {
        exportStatus = new TextView(this);
        exportStatus.setTextColor(Color.WHITE);
        exportStatus.setTextSize(12f);
        exportStatus.setTypeface(null, android.graphics.Typeface.BOLD);
        exportStatus.setGravity(Gravity.CENTER);
        exportStatus.setPadding(dp(14), dp(8), dp(14), dp(8));
        exportStatus.setBackground(rounded(0xCC111111, 12));
        exportStatus.setVisibility(View.GONE);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, dp(42));
        lp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        lp.topMargin = dp(92);
        root.addView(exportStatus, lp);
    }

    private void replaceShutterBehavior(FrameLayout root) {
        Button shutter = getField("recordButton", Button.class);
        if (shutter == null) return;
        shutter.setOnClickListener(v -> {
            if (processingVideo) {
                toast("Finishing the previous 100X video first.");
                return;
            }
            if (isPhotoMode()) capturePhotoWithTimer();
            else invoke("toggleVideoRecording", new Class[]{});
        });
    }

    private void capturePhotoWithTimer() {
        if (photoCountdownRunning || privateBoolean("takingPhoto")) return;
        beforePhotoUri = queryLatestOwnPhoto();
        pendingPhotoZoom = getRequestedZoom();
        pendingPhotoHardwareMax = currentHardwareUiMax();
        pendingPhotoRatio = photoRatio;

        if (photoTimerSeconds <= 0) {
            invoke("capturePhoto", new Class[]{});
            pollForCapturedPhoto(beforePhotoUri, 0);
            return;
        }
        photoCountdownRunning = true;
        runCountdown(photoTimerSeconds);
    }

    private void runCountdown(int remaining) {
        if (remaining <= 0) {
            photoCountdownRunning = false;
            if (countdownView != null) countdownView.setVisibility(View.GONE);
            invoke("capturePhoto", new Class[]{});
            pollForCapturedPhoto(beforePhotoUri, 0);
            return;
        }
        if (countdownView != null) {
            countdownView.setText(String.valueOf(remaining));
            countdownView.setVisibility(View.VISIBLE);
        }
        ui.postDelayed(() -> runCountdown(remaining - 1), 1000L);
    }

    private void pollForCapturedPhoto(Uri previous, int attempt) {
        if (attempt > 32) return;
        ui.postDelayed(() -> {
            Uri newest = queryLatestOwnPhoto();
            if (newest != null && (previous == null || !newest.equals(previous))) {
                postProcessPhoto(newest, pendingPhotoZoom, pendingPhotoHardwareMax, pendingPhotoRatio);
            } else {
                pollForCapturedPhoto(previous, attempt + 1);
            }
        }, 220L);
    }

    private Uri queryLatestOwnPhoto() {
        try {
            Uri base = MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
            String[] projection = {MediaStore.Images.Media._ID};
            String selection = MediaStore.Images.Media.RELATIVE_PATH + "=? AND " +
                    MediaStore.Images.Media.DISPLAY_NAME + " LIKE ?";
            String[] args = {"DCIM/Camera/", "X100_%"};
            try (Cursor c = getContentResolver().query(base, projection, selection, args,
                    MediaStore.Images.Media.DATE_ADDED + " DESC")) {
                if (c != null && c.moveToFirst()) {
                    long id = c.getLong(0);
                    return Uri.withAppendedPath(base, String.valueOf(id));
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    private void postProcessPhoto(Uri uri, float zoom, float hardwareUiMax, String ratio) {
        float extra = Math.max(1f, zoom / Math.max(1f, hardwareUiMax));
        boolean ratioNeedsCrop = !"4:3".equals(ratio);
        if (extra <= 1.001f && !ratioNeedsCrop) {
            toast("Photo saved • 4:3 • " + formatZoom(zoom));
            invoke("refreshLatestMedia", new Class[]{});
            return;
        }

        showExport("Processing photo " + ratio + " • " + formatZoom(zoom));
        new Thread(() -> {
            try {
                byte[] source = readAll(uri);
                byte[] processed = cropJpeg(source, ratio, extra);
                try (OutputStream out = getContentResolver().openOutputStream(uri, "wt")) {
                    if (out == null) throw new IllegalStateException("Cannot rewrite camera photo");
                    out.write(processed);
                }
                runOnUiThread(() -> {
                    hideExport();
                    toast("Photo saved • " + ratio + " • " + formatZoom(zoom));
                    invoke("refreshLatestMedia", new Class[]{});
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    hideExport();
                    toast("Photo processing failed: " + e.getMessage());
                });
            }
        }, "V11PhotoCrop").start();
    }

    private byte[] readAll(Uri uri) throws Exception {
        try (InputStream in = getContentResolver().openInputStream(uri);
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            if (in == null) throw new IllegalStateException("Cannot open photo");
            byte[] buf = new byte[128 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toByteArray();
        }
    }

    private byte[] cropJpeg(byte[] jpeg, String ratioName, float extraZoom) throws Exception {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length, bounds);
        int w = bounds.outWidth;
        int h = bounds.outHeight;
        if (w <= 0 || h <= 0) throw new IllegalStateException("Invalid JPEG dimensions");

        float targetWideRatio = ratioValue(ratioName);
        float target = w >= h ? targetWideRatio : 1f / targetWideRatio;

        int cw = Math.max(2, Math.round(w / Math.max(1f, extraZoom)));
        int ch = Math.max(2, Math.round(h / Math.max(1f, extraZoom)));
        float current = cw / (float) ch;
        if (current > target) cw = Math.max(2, Math.round(ch * target));
        else if (current < target) ch = Math.max(2, Math.round(cw / target));
        cw = Math.min(cw, w);
        ch = Math.min(ch, h);
        int left = Math.max(0, (w - cw) / 2);
        int top = Math.max(0, (h - ch) / 2);
        Rect region = new Rect(left, top, left + cw, top + ch);

        BitmapRegionDecoder decoder = BitmapRegionDecoder.newInstance(jpeg, 0, jpeg.length, false);
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inPreferredConfig = Bitmap.Config.ARGB_8888;
        Bitmap bitmap = decoder.decodeRegion(region, opts);
        decoder.recycle();
        if (bitmap == null) throw new IllegalStateException("JPEG crop decode failed");

        int orientation = privateIntFromMethod("computeOrientationHint", 0);
        if (orientation != 0) {
            Matrix rotate = new Matrix();
            rotate.postRotate(orientation);
            Bitmap rotated = Bitmap.createBitmap(bitmap, 0, 0,
                    bitmap.getWidth(), bitmap.getHeight(), rotate, true);
            if (rotated != bitmap) bitmap.recycle();
            bitmap = rotated;
        }

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 97, out)) {
            bitmap.recycle();
            throw new IllegalStateException("JPEG encode failed");
        }
        bitmap.recycle();
        return out.toByteArray();
    }

    private float ratioValue(String ratio) {
        if ("1:1".equals(ratio)) return 1f;
        if ("16:9".equals(ratio)) return 16f / 9f;
        if ("Full".equals(ratio)) {
            int w = getResources().getDisplayMetrics().widthPixels;
            int h = getResources().getDisplayMetrics().heightPixels;
            return Math.max(w, h) / (float) Math.max(1, Math.min(w, h));
        }
        return 4f / 3f;
    }

    private void updatePhotoVideoChrome() {
        boolean photo = isPhotoMode();
        TextView modeBadge = getField("modeBadge", TextView.class);
        if (modeBadge != null) modeBadge.setVisibility(photo ? View.GONE : View.VISIBLE);
        if (ratioButton != null) ratioButton.setVisibility(photo ? View.VISIBLE : View.GONE);
        if (photoPanel != null) {
            photoPanel.setVisibility(photo && photoPanelOpen ? View.VISIBLE : View.GONE);
        }
        if (!photo) photoPanelOpen = false;
    }

    private boolean isPhotoMode() {
        return privateBoolean("photoMode");
    }

    private void watchRecordingState() {
        boolean recording = privateBoolean("recording");
        if (recording && !lastRecording) {
            recordingZoom.clear();
            lastRecordedExtra = -1f;
            recordingStartElapsed = SystemClock.elapsedRealtime();
            addVideoZoomSample();
        } else if (recording) {
            addVideoZoomSample();
        } else if (!recording && lastRecording) {
            addVideoZoomSample();
            ArrayList<ZoomSample> finished = new ArrayList<>(recordingZoom);
            ui.postDelayed(() -> maybeProcessLatestVideo(finished), 450L);
        }
        lastRecording = recording;
    }

    private void addVideoZoomSample() {
        long tUs = Math.max(0L, SystemClock.elapsedRealtime() - recordingStartElapsed) * 1000L;
        float z = getRequestedZoom();
        float extra = Math.max(1f, z / Math.max(1f, currentHardwareUiMax()));
        if (lastRecordedExtra < 0f || Math.abs(extra - lastRecordedExtra) >= 0.015f || recordingZoom.isEmpty()) {
            recordingZoom.add(new ZoomSample(tUs, extra));
            lastRecordedExtra = extra;
        }
    }

    private float currentHardwareUiMax() {
        boolean activeTele = privateBoolean("activeTele");
        if (activeTele) {
            float teleMax = privateFloat("teleMaxZoom", 10f);
            return Math.max(3f, 3f * Math.max(1f, teleMax));
        }
        return Math.max(1f, privateFloat("logicalMaxZoom", 10f));
    }

    private void maybeProcessLatestVideo(ArrayList<ZoomSample> samples) {
        if (samples.isEmpty() || processingVideo) return;
        float max = 1f;
        for (ZoomSample s : samples) max = Math.max(max, s.extra);
        if (max <= 1.002f) return;
        Uri source = queryLatestOwnVideo();
        if (source == null) {
            toast("100X video crop: saved video was not found.");
            return;
        }
        processVideoZoom(source, samples);
    }

    private Uri queryLatestOwnVideo() {
        try {
            Uri base = MediaStore.Video.Media.EXTERNAL_CONTENT_URI;
            String[] projection = {MediaStore.Video.Media._ID};
            String selection = MediaStore.Video.Media.RELATIVE_PATH + "=? AND " +
                    MediaStore.Video.Media.DISPLAY_NAME + " LIKE ?";
            String[] args = {"Movies/X100Zoom/", "X100_%"};
            try (Cursor c = getContentResolver().query(base, projection, selection, args,
                    MediaStore.Video.Media.DATE_ADDED + " DESC")) {
                if (c != null && c.moveToFirst()) {
                    return Uri.withAppendedPath(base, String.valueOf(c.getLong(0)));
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    private void processVideoZoom(Uri source, ArrayList<ZoomSample> samples) {
        processingVideo = true;
        showExport("Baking extended zoom into video…");
        File temp = new File(getCacheDir(), "v11_zoom_" + System.currentTimeMillis() + ".mp4");
        if (temp.exists()) temp.delete();

        MatrixTransformation zoomEffect = presentationTimeUs -> {
            float scale = interpolateExtra(samples, presentationTimeUs);
            Matrix m = new Matrix();
            m.postScale(scale, scale);
            return m;
        };

        ArrayList<Effect> videoEffects = new ArrayList<>();
        videoEffects.add(zoomEffect);
        Effects effects = new Effects(Collections.<AudioProcessor>emptyList(), videoEffects);
        EditedMediaItem edited = new EditedMediaItem.Builder(MediaItem.fromUri(source))
                .setEffects(effects)
                .build();

        activeTransformer = new Transformer.Builder(this)
                .addListener(new Transformer.Listener() {
                    @Override public void onCompleted(Composition composition, ExportResult exportResult) {
                        new Thread(() -> replaceMediaStoreVideo(source, temp), "V11VideoCommit").start();
                    }

                    @Override public void onError(Composition composition, ExportResult exportResult,
                                                 ExportException exportException) {
                        temp.delete();
                        runOnUiThread(() -> {
                            activeTransformer = null;
                            processingVideo = false;
                            hideExport();
                            toast("Extended video zoom export failed: " + exportException.getMessage());
                        });
                    }
                })
                .build();

        try {
            activeTransformer.start(edited, temp.getAbsolutePath());
        } catch (Exception e) {
            temp.delete();
            activeTransformer = null;
            processingVideo = false;
            hideExport();
            toast("Extended video zoom could not start: " + e.getMessage());
        }
    }

    private float interpolateExtra(List<ZoomSample> samples, long timeUs) {
        if (samples.isEmpty()) return 1f;
        if (timeUs <= samples.get(0).timeUs) return samples.get(0).extra;
        for (int i = 1; i < samples.size(); i++) {
            ZoomSample b = samples.get(i);
            ZoomSample a = samples.get(i - 1);
            if (timeUs <= b.timeUs) {
                long span = Math.max(1L, b.timeUs - a.timeUs);
                float p = (timeUs - a.timeUs) / (float) span;
                return a.extra + (b.extra - a.extra) * p;
            }
        }
        return samples.get(samples.size() - 1).extra;
    }

    private void replaceMediaStoreVideo(Uri target, File temp) {
        try (InputStream in = new FileInputStream(temp);
             OutputStream out = getContentResolver().openOutputStream(target, "wt")) {
            if (out == null) throw new IllegalStateException("Cannot rewrite exported video");
            byte[] buffer = new byte[256 * 1024];
            int n;
            while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
            out.flush();
            temp.delete();
            runOnUiThread(() -> {
                activeTransformer = null;
                processingVideo = false;
                hideExport();
                toast("Saved video with real extended zoom");
                invoke("refreshLatestMedia", new Class[]{});
            });
        } catch (Exception e) {
            temp.delete();
            runOnUiThread(() -> {
                activeTransformer = null;
                processingVideo = false;
                hideExport();
                toast("Could not commit extended zoom video: " + e.getMessage());
            });
        }
    }

    private void showExport(String text) {
        runOnUiThread(() -> {
            if (exportStatus != null) {
                exportStatus.setText(text);
                exportStatus.setVisibility(View.VISIBLE);
            }
        });
    }

    private void hideExport() {
        runOnUiThread(() -> {
            if (exportStatus != null) exportStatus.setVisibility(View.GONE);
        });
    }

    private float getRequestedZoom() {
        return privateFloat("requestedUiZoom", 1f);
    }

    private String formatZoom(float z) {
        if (Math.abs(z - Math.round(z)) < 0.001f) return String.format(Locale.US, "%.0fX", z);
        return String.format(Locale.US, "%.1fX", z);
    }

    private float clamp(float v, float min, float max) {
        return Math.max(min, Math.min(max, v));
    }

    private void toast(String text) {
        runOnUiThread(() -> Toast.makeText(this, text, Toast.LENGTH_SHORT).show());
    }

    @SuppressWarnings("unchecked")
    private <T> T getField(String name, Class<T> type) {
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

    private float privateFloat(String name, float fallback) {
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

    private boolean privateBoolean(String name) {
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

    private void setFloatField(String name, float value) {
        Class<?> c = getClass();
        while (c != null) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                f.setFloat(this, value);
                return;
            } catch (NoSuchFieldException e) {
                c = c.getSuperclass();
            } catch (Exception e) {
                return;
            }
        }
    }

    private void setBooleanField(String name, boolean value) {
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

    private Object invoke(String name, Class<?>[] types, Object... args) {
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

    private int privateIntFromMethod(String name, int fallback) {
        Object o = invoke(name, new Class[]{});
        return o instanceof Integer ? (Integer) o : fallback;
    }

    private TextView findText(View root, String exact) {
        if (root == null) return null;
        if (root instanceof TextView) {
            CharSequence t = ((TextView) root).getText();
            if (t != null && exact.contentEquals(t)) return (TextView) root;
        }
        if (root instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) root;
            for (int i = 0; i < g.getChildCount(); i++) {
                TextView found = findText(g.getChildAt(i), exact);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static final class ZoomSample {
        final long timeUs;
        final float extra;
        ZoomSample(long timeUs, float extra) {
            this.timeUs = timeUs;
            this.extra = extra;
        }
    }

    private final class ExtendedZoomView extends View {
        private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint accent = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint knob = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
        private float zoom = 1f;

        ExtendedZoomView() {
            super(V11CameraActivity.this);
            line.setColor(0xFFE8E8E8);
            line.setStrokeWidth(dp(2));
            accent.setColor(0xFFFFC928);
            accent.setStrokeWidth(dp(3));
            knob.setColor(Color.WHITE);
            text.setColor(0xFF111111);
            text.setTextAlign(Paint.Align.CENTER);
            text.setTextSize(dp(9));
            text.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        }

        void setZoom(float z) {
            zoom = clamp(z, MIN_ZOOM, MAX_ZOOM);
            invalidate();
        }

        @Override protected void onDraw(Canvas c) {
            super.onDraw(c);
            float left = dp(8);
            float right = getWidth() - dp(8);
            float y = getHeight() * 0.55f;
            float neutral = xFor(1f, left, right);
            float kx = xFor(zoom, left, right);
            c.drawLine(left, y, right, y, line);
            c.drawLine(neutral, y, kx, y, accent);
            float[] marks = {0.6f, 1f, 2f, 3f, 10f, 30f, 50f, 100f};
            for (float m : marks) {
                float x = xFor(m, left, right);
                c.drawLine(x, y - dp(4), x, y + dp(4), line);
            }
            c.drawCircle(kx, y, dp(14), knob);
            c.drawText(formatZoom(zoom), kx, y + dp(3), text);
        }

        private float xFor(float z, float left, float right) {
            float a = (float) Math.log(MIN_ZOOM);
            float b = (float) Math.log(MAX_ZOOM);
            float p = ((float) Math.log(clamp(z, MIN_ZOOM, MAX_ZOOM)) - a) / (b - a);
            return left + p * (right - left);
        }

        private float zoomFor(float x, float left, float right) {
            float p = clamp((x - left) / Math.max(1f, right - left), 0f, 1f);
            float a = (float) Math.log(MIN_ZOOM);
            float b = (float) Math.log(MAX_ZOOM);
            return (float) Math.exp(a + p * (b - a));
        }

        @Override public boolean onTouchEvent(MotionEvent e) {
            if (e.getActionMasked() == MotionEvent.ACTION_DOWN ||
                    e.getActionMasked() == MotionEvent.ACTION_MOVE) {
                float raw = zoomFor(e.getX(), dp(8), getWidth() - dp(8));
                setV11Zoom(raw);
                return true;
            }
            return e.getActionMasked() == MotionEvent.ACTION_UP || super.onTouchEvent(e);
        }
    }
}
