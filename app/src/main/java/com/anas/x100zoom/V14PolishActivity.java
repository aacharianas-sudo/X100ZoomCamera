package com.anas.x100zoom;

import android.Manifest;
import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.content.ContentUris;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.util.Range;
import android.util.Size;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.animation.OvershootInterpolator;
import android.view.animation.PathInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * V14 polish layer for the X300-style UI.
 *
 * Real behavior changes in this layer:
 *  - immersive camera UI (status/navigation bars hidden like stock camera)
 *  - Full photo ratio lets preview continue under top/bottom controls; 4:3/16:9/1:1
 *    retain black camera chrome and framing
 *  - tapping the preview below an open settings sheet closes settings immediately
 *  - smoother settings/mode/ratio transitions
 *  - stable MediaStore album thumbnail that only changes when the newest media ID changes
 *  - 60fps uses an exact Camera2 60..60 AE target range + MediaRecorder's existing 60fps
 *    setting; unsupported 60fps is never silently represented as supported
 */
public class V14PolishActivity extends X300UltraUiActivity {
    private static final int TOP_DARK = 0xCC050505;
    private static final int BOTTOM_DARK = 0xFF050505;
    private static final int ACCENT = 0xFFFFD129;

    private final Handler v14 = new Handler(Looper.getMainLooper());
    private final ExecutorService mediaWorker = Executors.newSingleThreadExecutor();

    private FrameLayout cameraRoot14;
    private FrameLayout topChrome14;
    private FrameLayout bottomChrome14;
    private FrameLayout settingsSheet14;
    private View settingsDismissLayer;
    private View shutter14;
    private View zoomStrip14;
    private LinearLayout modeRail14;
    private TextView configPill14;
    private TextView qualityTile14;
    private View ratioFrame14;

    private ImageView stableGallery;
    private Uri stableGalleryUri;
    private String stableGalleryMime;
    private long stableMediaId = -1L;
    private long stableMediaDate = -1L;
    private boolean galleryLoadInFlight = false;

    private boolean installed14 = false;
    private boolean lastSettingsOpen14 = false;
    private boolean lastPhotoMode14 = false;
    private String lastRatio14 = "";
    private int currentTopColor = TOP_DARK;
    private int currentBottomColor = BOTTOM_DARK;
    private long lastGalleryPollMs = 0L;
    private long lastFpsApplyMs = 0L;
    private boolean warnedNoFixed60 = false;

    private long fpsWindowStartNs = 0L;
    private int fpsFrameCount = 0;
    private volatile float measuredCameraFps = 0f;

    private final CameraCaptureSession.CaptureCallback fpsCaptureCallback =
            new CameraCaptureSession.CaptureCallback() {
        @Override public void onCaptureCompleted(CameraCaptureSession session,
                                                  CaptureRequest request,
                                                  TotalCaptureResult result) {
            Long ts = result.get(CaptureResult.SENSOR_TIMESTAMP);
            if (ts == null || ts <= 0L) return;
            if (fpsWindowStartNs == 0L) {
                fpsWindowStartNs = ts;
                fpsFrameCount = 1;
                return;
            }
            fpsFrameCount++;
            long span = ts - fpsWindowStartNs;
            if (span >= 900_000_000L && fpsFrameCount > 2) {
                measuredCameraFps = (fpsFrameCount - 1) * 1_000_000_000f / span;
                fpsWindowStartNs = ts;
                fpsFrameCount = 1;
            }
        }
    };

    private final Runnable stateWatcher14 = new Runnable() {
        @Override public void run() {
            if (installed14) {
                syncV14State();
                long now = android.os.SystemClock.elapsedRealtime();
                if (now - lastGalleryPollMs >= 1200L) {
                    lastGalleryPollMs = now;
                    refreshStableGallery();
                }
                if (now - lastFpsApplyMs >= 650L) {
                    lastFpsApplyMs = now;
                    enforceRealFrameRate();
                }
            }
            v14.postDelayed(this, 65L);
        }
    };

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        enterImmersiveCameraMode();
        v14.postDelayed(this::installV14Polish, 2550L);
        v14.postDelayed(stateWatcher14, 2700L);
    }

    @Override protected void onResume() {
        super.onResume();
        enterImmersiveCameraMode();
        v14.postDelayed(this::refreshStableGallery, 350L);
    }

    @Override protected void onDestroy() {
        v14.removeCallbacksAndMessages(null);
        mediaWorker.shutdownNow();
        super.onDestroy();
    }

    private int dp14(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void enterImmersiveCameraMode() {
        Window window = getWindow();
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false);
            WindowInsetsController controller = window.getInsetsController();
            if (controller != null) {
                controller.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
                controller.setSystemBarsBehavior(
                        WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        } else {
            window.getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY |
                    View.SYSTEM_UI_FLAG_FULLSCREEN |
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION |
                    View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN |
                    View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION |
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        }
    }

    private void installV14Polish() {
        cameraRoot14 = field14("cameraRoot", FrameLayout.class);
        topChrome14 = field14("topChrome", FrameLayout.class);
        bottomChrome14 = field14("bottomChrome", FrameLayout.class);
        settingsSheet14 = field14("settingsSheet", FrameLayout.class);
        shutter14 = field14("shutterView", View.class);
        zoomStrip14 = field14("zoomStrip", View.class);
        modeRail14 = field14("modeRail", LinearLayout.class);
        configPill14 = field14("configPill", TextView.class);
        qualityTile14 = field14("qualityTile", TextView.class);
        ratioFrame14 = field14("ratioFrame", View.class);

        if (cameraRoot14 == null || topChrome14 == null || bottomChrome14 == null) return;

        addTextShadows();
        installSettingsDismissLayer();
        installStableGallery();
        installRealVideoModeSelector();

        lastPhotoMode14 = bool14("photoMode");
        lastRatio14 = string14("photoRatio", "4:3");
        applyRatioChrome(lastRatio14, false);
        installed14 = true;
        refreshStableGallery();
    }

    private void addTextShadows() {
        if (configPill14 != null) configPill14.setShadowLayer(dp14(3), 0f, dp14(1), Color.BLACK);
        if (modeRail14 != null) {
            for (int i = 0; i < modeRail14.getChildCount(); i++) {
                View child = modeRail14.getChildAt(i);
                if (child instanceof TextView) {
                    ((TextView) child).setShadowLayer(dp14(4), 0f, dp14(1), Color.BLACK);
                }
            }
        }
    }

    private void installSettingsDismissLayer() {
        settingsDismissLayer = new View(this);
        settingsDismissLayer.setBackgroundColor(Color.TRANSPARENT);
        settingsDismissLayer.setVisibility(View.GONE);
        settingsDismissLayer.setClickable(true);
        settingsDismissLayer.setOnTouchListener((v, e) -> {
            if (e.getActionMasked() == MotionEvent.ACTION_DOWN) {
                invoke14("showSettings", new Class[]{boolean.class}, false);
                return true;
            }
            return true;
        });
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT);
        lp.gravity = Gravity.TOP;
        lp.topMargin = dp14(430);
        lp.bottomMargin = dp14(300);
        cameraRoot14.addView(settingsDismissLayer, lp);
    }

    private void installStableGallery() {
        ImageView v13Gallery = field14("galleryView", ImageView.class);
        if (v13Gallery != null) v13Gallery.setVisibility(View.GONE);

        stableGallery = new ImageView(this);
        stableGallery.setScaleType(ImageView.ScaleType.CENTER_CROP);
        stableGallery.setClipToOutline(true);
        if (v13Gallery != null && v13Gallery.getBackground() != null) {
            stableGallery.setBackground(v13Gallery.getBackground().getConstantState() != null
                    ? v13Gallery.getBackground().getConstantState().newDrawable()
                    : v13Gallery.getBackground());
        }
        stableGallery.setContentDescription("Open latest photo or video");
        stableGallery.setOnClickListener(v -> openStableGallery());
        stableGallery.setOnTouchListener((v, e) -> {
            if (e.getActionMasked() == MotionEvent.ACTION_DOWN) {
                v.animate().scaleX(0.94f).scaleY(0.94f).setDuration(70L).start();
            } else if (e.getActionMasked() == MotionEvent.ACTION_UP ||
                    e.getActionMasked() == MotionEvent.ACTION_CANCEL) {
                v.animate().scaleX(1f).scaleY(1f).setDuration(110L).start();
            }
            return false;
        });

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(dp14(58), dp14(58));
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.leftMargin = dp14(24);
        lp.topMargin = dp14(86);
        bottomChrome14.addView(stableGallery, lp);
    }

    private void installRealVideoModeSelector() {
        if (qualityTile14 == null) return;
        qualityTile14.setOnClickListener(v -> {
            if (bool14("photoMode")) {
                invoke14("toggleQualityOrVideoMode", new Class[]{});
            } else {
                cycleSupportedVideoMode();
            }
        });
    }

    private void syncV14State() {
        boolean settingsOpen = bool14("settingsOpen");
        if (settingsOpen != lastSettingsOpen14) {
            lastSettingsOpen14 = settingsOpen;
            onSettingsStateChanged(settingsOpen);
        }

        boolean photo = bool14("photoMode");
        if (photo != lastPhotoMode14) {
            lastPhotoMode14 = photo;
            animateModeChange();
        }

        String ratio = string14("photoRatio", "4:3");
        if (!ratio.equals(lastRatio14)) {
            lastRatio14 = ratio;
            applyRatioChrome(ratio, true);
        }

        if (configPill14 != null && !photo) {
            int fps = int14("selectedFps", 30);
            if (fps == 60 && measuredCameraFps > 0f) {
                configPill14.setContentDescription(String.format(java.util.Locale.US,
                        "60 fps camera mode, measured preview %.1f fps", measuredCameraFps));
            }
        }
    }

    private void onSettingsStateChanged(boolean open) {
        if (settingsDismissLayer != null) {
            settingsDismissLayer.setVisibility(open ? View.VISIBLE : View.GONE);
        }
        if (settingsSheet14 == null) return;
        settingsSheet14.animate().cancel();
        settingsSheet14.setPivotY(0f);
        if (open) {
            settingsSheet14.setScaleY(0.965f);
            settingsSheet14.animate()
                    .scaleY(1f)
                    .alpha(1f)
                    .setDuration(235L)
                    .setInterpolator(new PathInterpolator(0.16f, 0.78f, 0.24f, 1f))
                    .start();
        }
    }

    private void animateModeChange() {
        OvershootInterpolator overshoot = new OvershootInterpolator(0.65f);
        if (shutter14 != null) {
            shutter14.animate().cancel();
            shutter14.setScaleX(0.84f);
            shutter14.setScaleY(0.84f);
            shutter14.setAlpha(0.72f);
            shutter14.animate().scaleX(1f).scaleY(1f).alpha(1f)
                    .setDuration(240L).setInterpolator(overshoot).start();
        }
        if (zoomStrip14 != null) {
            zoomStrip14.animate().cancel();
            zoomStrip14.setTranslationY(dp14(10));
            zoomStrip14.setAlpha(0.50f);
            zoomStrip14.animate().translationY(0f).alpha(1f)
                    .setDuration(220L)
                    .setInterpolator(new PathInterpolator(0.20f, 0f, 0f, 1f)).start();
        }
        if (configPill14 != null) {
            configPill14.animate().cancel();
            configPill14.setScaleX(0.88f);
            configPill14.setScaleY(0.88f);
            configPill14.animate().scaleX(1f).scaleY(1f)
                    .setDuration(210L).setInterpolator(overshoot).start();
        }
    }

    /**
     * Stock-vivo-like ratio treatment:
     * Full = camera preview remains visible behind both control zones.
     * Other photo ratios = black chrome provides the bounded capture presentation.
     */
    private void applyRatioChrome(String ratio, boolean animate) {
        boolean full = "Full".equalsIgnoreCase(ratio) && bool14("photoMode");
        int targetTop = full ? Color.TRANSPARENT : TOP_DARK;
        int targetBottom = full ? Color.TRANSPARENT : BOTTOM_DARK;

        if (!animate) {
            currentTopColor = targetTop;
            currentBottomColor = targetBottom;
            topChrome14.setBackgroundColor(targetTop);
            bottomChrome14.setBackgroundColor(targetBottom);
        } else {
            animateChromeColor(topChrome14, currentTopColor, targetTop, true);
            animateChromeColor(bottomChrome14, currentBottomColor, targetBottom, false);
        }

        if (ratioFrame14 != null) {
            ratioFrame14.animate().cancel();
            ratioFrame14.animate().alpha(full ? 0f : 1f).setDuration(210L).start();
        }

        if (modeRail14 != null) {
            modeRail14.animate().cancel();
            modeRail14.setTranslationY(full ? dp14(5) : -dp14(3));
            modeRail14.animate().translationY(0f).setDuration(220L)
                    .setInterpolator(new PathInterpolator(0.2f, 0f, 0f, 1f)).start();
        }
    }

    private void animateChromeColor(View view, int from, int to, boolean top) {
        ValueAnimator animator = ValueAnimator.ofObject(new ArgbEvaluator(), from, to);
        animator.setDuration(240L);
        animator.setInterpolator(new PathInterpolator(0.20f, 0f, 0f, 1f));
        animator.addUpdateListener(a -> view.setBackgroundColor((Integer) a.getAnimatedValue()));
        animator.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(android.animation.Animator animation) {
                if (top) currentTopColor = to;
                else currentBottomColor = to;
            }
        });
        animator.start();
    }

    private void refreshStableGallery() {
        if (stableGallery == null || galleryLoadInFlight || !canReadMedia14()) return;
        galleryLoadInFlight = true;
        mediaWorker.execute(() -> {
            long id = -1L;
            long date = -1L;
            String mime = null;
            Uri uri = null;
            try {
                Uri files = MediaStore.Files.getContentUri("external");
                String[] projection = {
                        MediaStore.Files.FileColumns._ID,
                        MediaStore.Files.FileColumns.MIME_TYPE,
                        MediaStore.Files.FileColumns.DATE_ADDED
                };
                String selection = "(" + MediaStore.Files.FileColumns.MEDIA_TYPE + "=? OR " +
                        MediaStore.Files.FileColumns.MEDIA_TYPE + "=?) AND " +
                        MediaStore.MediaColumns.IS_PENDING + "=0";
                String[] args = {
                        String.valueOf(MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE),
                        String.valueOf(MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO)
                };
                try (Cursor c = getContentResolver().query(files, projection, selection, args,
                        MediaStore.Files.FileColumns.DATE_ADDED + " DESC")) {
                    if (c != null && c.moveToFirst()) {
                        id = c.getLong(0);
                        mime = c.getString(1);
                        date = c.getLong(2);
                        uri = ContentUris.withAppendedId(files, id);
                    }
                }

                if (uri != null && (id != stableMediaId || date != stableMediaDate)) {
                    Bitmap bitmap = getContentResolver().loadThumbnail(
                            uri, new Size(dp14(180), dp14(180)), null);
                    final long finalId = id;
                    final long finalDate = date;
                    final String finalMime = mime;
                    final Uri finalUri = uri;
                    final Bitmap finalBitmap = bitmap;
                    runOnUiThread(() -> {
                        if (finalBitmap != null && stableGallery != null) {
                            // No recurring fade/clear: the thumbnail remains visually attached.
                            stableGallery.setImageBitmap(finalBitmap);
                            stableGallery.setAlpha(1f);
                            stableMediaId = finalId;
                            stableMediaDate = finalDate;
                            stableGalleryMime = finalMime;
                            stableGalleryUri = finalUri;
                        }
                        galleryLoadInFlight = false;
                    });
                    return;
                }
            } catch (Exception ignored) {}
            runOnUiThread(() -> galleryLoadInFlight = false);
        });
    }

    private boolean canReadMedia14() {
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            return checkSelfPermission(Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED ||
                    checkSelfPermission(Manifest.permission.READ_MEDIA_VIDEO) == PackageManager.PERMISSION_GRANTED;
        }
        return checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
    }

    private void openStableGallery() {
        if (stableGalleryUri == null) {
            refreshStableGallery();
            return;
        }
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(stableGalleryUri,
                    stableGalleryMime != null ? stableGalleryMime : "*/*");
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(intent);
        } catch (Exception e) {
            Toast.makeText(this, "No album app could open this media.", Toast.LENGTH_SHORT).show();
        }
    }

    private void cycleSupportedVideoMode() {
        if (bool14("recording") || bool14("recordingStarting")) return;
        CameraCharacteristics chars = field14("currentChars", CameraCharacteristics.class);
        if (chars == null) return;

        Size current = object14("selectedSize") instanceof Size ? (Size) object14("selectedSize")
                : new Size(3840, 2160);
        int fps = int14("selectedFps", 60);
        boolean currentUhd = current.getWidth() >= 3800;
        int start = modeIndex(currentUhd, fps);

        for (int offset = 1; offset <= 4; offset++) {
            int index = (start + offset) % 4;
            boolean uhd = index >= 2;
            int wantedFps = (index % 2 == 1) ? 60 : 30;
            Size size = uhd ? new Size(3840, 2160) : new Size(1920, 1080);
            if (!supportsRealVideoMode(chars, size, wantedFps)) continue;

            setObject14("selectedSize", size);
            setInt14("selectedFps", wantedFps);
            measuredCameraFps = 0f;
            fpsWindowStartNs = 0L;
            fpsFrameCount = 0;
            invoke14("updateModeBadge", new Class[]{});
            Handler cameraHandler = field14("cameraHandler", Handler.class);
            if (cameraHandler != null) {
                cameraHandler.post(() -> invoke14("startPreviewSession", new Class[]{}));
            }
            return;
        }
        Toast.makeText(this, "No other verified video mode is exposed by this lens.",
                Toast.LENGTH_SHORT).show();
    }

    private int modeIndex(boolean uhd, int fps) {
        if (!uhd && fps <= 30) return 0;
        if (!uhd) return 1;
        if (fps <= 30) return 2;
        return 3;
    }

    private boolean supportsRealVideoMode(CameraCharacteristics chars, Size size, int fps) {
        StreamConfigurationMap map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        if (map == null || !containsSize14(map.getOutputSizes(MediaRecorder.class), size)) return false;
        if (fps >= 60) return exactRange(chars, 60) != null;
        return containingRange(chars, 30) != null;
    }

    private boolean containsSize14(Size[] sizes, Size wanted) {
        if (sizes == null) return false;
        for (Size s : sizes) if (wanted.equals(s)) return true;
        return false;
    }

    private Range<Integer> exactRange(CameraCharacteristics chars, int fps) {
        Range<Integer>[] ranges = chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES);
        if (ranges == null) return null;
        for (Range<Integer> r : ranges) {
            if (r.getLower() == fps && r.getUpper() == fps) return r;
        }
        return null;
    }

    private Range<Integer> containingRange(CameraCharacteristics chars, int fps) {
        Range<Integer>[] ranges = chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES);
        if (ranges == null) return null;
        Range<Integer> best = null;
        for (Range<Integer> r : ranges) {
            if (r.getLower() <= fps && r.getUpper() >= fps) {
                if (best == null || r.getLower() > best.getLower()) best = r;
            }
        }
        return best;
    }

    /**
     * MediaRecorder already receives selectedFps in MainActivity. Here we make the
     * Camera2 sensor/repeating request strict as well. For 60fps we accept only 60..60.
     */
    private void enforceRealFrameRate() {
        if (bool14("photoMode") || bool14("routeSwitching")) return;
        CameraCharacteristics chars = field14("currentChars", CameraCharacteristics.class);
        CaptureRequest.Builder builder = field14("repeatingBuilder", CaptureRequest.Builder.class);
        CameraCaptureSession session = field14("captureSession", CameraCaptureSession.class);
        Handler cameraHandler = field14("cameraHandler", Handler.class);
        if (chars == null || builder == null || session == null || cameraHandler == null) return;

        int selected = int14("selectedFps", 30);
        Range<Integer> range = selected >= 60 ? exactRange(chars, 60) : containingRange(chars, 30);
        if (selected >= 60 && range == null) {
            if (!bool14("recording") && !bool14("recordingStarting")) {
                setInt14("selectedFps", 30);
                invoke14("updateModeBadge", new Class[]{});
                cameraHandler.post(() -> invoke14("startPreviewSession", new Class[]{}));
            }
            if (!warnedNoFixed60) {
                warnedNoFixed60 = true;
                runOnUiThread(() -> Toast.makeText(this,
                        "This active lens does not expose fixed 60 fps. Switched to 30 fps.",
                        Toast.LENGTH_LONG).show());
            }
            return;
        }
        if (range == null) return;
        if (selected >= 60) warnedNoFixed60 = false;

        try {
            builder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, range);
            session.setRepeatingRequest(builder.build(), fpsCaptureCallback, cameraHandler);
        } catch (Exception ignored) {}
    }

    @SuppressWarnings("unchecked")
    private <T> T field14(String name, Class<T> type) {
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

    private boolean bool14(String name) {
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

    private int int14(String name, int fallback) {
        Class<?> c = getClass();
        while (c != null) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f.getInt(this);
            } catch (NoSuchFieldException e) {
                c = c.getSuperclass();
            } catch (Exception e) {
                return fallback;
            }
        }
        return fallback;
    }

    private String string14(String name, String fallback) {
        Class<?> c = getClass();
        while (c != null) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                Object value = f.get(this);
                return value instanceof String ? (String) value : fallback;
            } catch (NoSuchFieldException e) {
                c = c.getSuperclass();
            } catch (Exception e) {
                return fallback;
            }
        }
        return fallback;
    }

    private Object object14(String name) {
        Class<?> c = getClass();
        while (c != null) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(this);
            } catch (NoSuchFieldException e) {
                c = c.getSuperclass();
            } catch (Exception e) {
                return null;
            }
        }
        return null;
    }

    private void setInt14(String name, int value) {
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

    private void setObject14(String name, Object value) {
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

    private Object invoke14(String name, Class<?>[] types, Object... args) {
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
