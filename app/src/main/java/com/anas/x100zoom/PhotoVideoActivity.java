package com.anas.x100zoom;

import android.Manifest;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.ImageFormat;
import android.graphics.SurfaceTexture;
import android.graphics.drawable.GradientDrawable;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.OutputConfiguration;
import android.hardware.camera2.params.SessionConfiguration;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.provider.MediaStore;
import android.util.Size;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.PopupMenu;
import android.widget.TextView;
import android.widget.Toast;

import java.io.OutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * V10: VIDEO + PHOTO, real device MediaStore integration, NORMAL/MAX JPEG capture.
 * MAX uses Camera2 maximum-resolution sensor mode only when the HAL advertises it.
 */
public class PhotoVideoActivity extends CameraChromeActivity {
    private static final int REQ_MEDIA = 901;

    private boolean photoMode = false;
    private boolean maxPhotoMode = false;
    private boolean takingPhoto = false;

    private TextView modeStrip;
    private TextView modeBadge;
    private Button shutter;
    private ImageReader photoReader;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestMediaAccessIfNeeded();
        new Handler(getMainLooper()).postDelayed(this::installPhotoVideoUi, 650L);
    }

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    private void requestMediaAccessIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33) {
            boolean images = checkSelfPermission(Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED;
            boolean videos = checkSelfPermission(Manifest.permission.READ_MEDIA_VIDEO) == PackageManager.PERMISSION_GRANTED;
            if (!images || !videos) {
                requestPermissions(new String[]{Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO}, REQ_MEDIA);
            }
        } else if (checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE}, REQ_MEDIA);
        }
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        if (requestCode == REQ_MEDIA) {
            new Handler(getMainLooper()).postDelayed(this::refreshLatestMedia, 250L);
            return;
        }
        super.onRequestPermissionsResult(requestCode, permissions, results);
    }

    private void installPhotoVideoUi() {
        TextureView preview = privateField("textureView", TextureView.class);
        if (preview == null || !(preview.getParent() instanceof FrameLayout)) return;
        FrameLayout root = (FrameLayout) preview.getParent();

        modeBadge = privateField("modeBadge", TextView.class);
        shutter = privateField("recordButton", Button.class);

        TextView oldVideoLabel = findText(root, "VIDEO");
        if (oldVideoLabel != null) oldVideoLabel.setVisibility(View.GONE);

        modeStrip = new TextView(this);
        modeStrip.setTextSize(15f);
        modeStrip.setTypeface(null, android.graphics.Typeface.BOLD);
        modeStrip.setGravity(Gravity.CENTER);
        modeStrip.setShadowLayer(5f, 0f, 1f, Color.BLACK);
        modeStrip.setPadding(dp(20), dp(8), dp(20), dp(8));
        modeStrip.setOnTouchListener((v, e) -> {
            if (e.getActionMasked() == MotionEvent.ACTION_UP) {
                setPhotoMode(e.getX() < v.getWidth() / 2f);
                return true;
            }
            return true;
        });
        FrameLayout.LayoutParams stripLp = new FrameLayout.LayoutParams(dp(220), dp(44));
        stripLp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        stripLp.bottomMargin = dp(18);
        root.addView(modeStrip, stripLp);

        if (shutter != null) {
            shutter.setOnClickListener(v -> {
                if (photoMode) capturePhoto();
                else toggleVideoRecording();
            });
        }

        if (modeBadge != null) {
            modeBadge.setOnClickListener(v -> {
                if (photoMode) showPhotoQualityMenu(v);
                else invokePrivate("showVideoModeMenu", new Class[]{View.class}, v);
            });
        }

        setPhotoMode(false);
    }

    private void setPhotoMode(boolean enabled) {
        if (takingPhoto) return;
        photoMode = enabled;
        updateModeStrip();
        updateBadgeForCurrentMode();
        updateShutterAppearance();
    }

    private void updateModeStrip() {
        if (modeStrip == null) return;
        if (photoMode) {
            modeStrip.setText("●  PHOTO          VIDEO");
            modeStrip.setTextColor(0xFFFFD54F);
        } else {
            modeStrip.setText("PHOTO          ●  VIDEO");
            modeStrip.setTextColor(0xFFFFD54F);
        }
    }

    private void updateBadgeForCurrentMode() {
        if (modeBadge == null) return;
        if (photoMode) {
            modeBadge.setText(maxPhotoMode ? "PHOTO\nMAX" : "PHOTO\nNORMAL");
        } else {
            invokePrivate("updateModeBadge", new Class[]{});
        }
    }

    private GradientDrawable circle(int color) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setColor(color);
        return d;
    }

    private void updateShutterAppearance() {
        if (shutter == null) return;
        shutter.setBackground(circle(photoMode ? Color.WHITE : 0xFFFF3B30));
    }

    private void showPhotoQualityMenu(View anchor) {
        PopupMenu menu = new PopupMenu(this, anchor);
        menu.getMenu().add("Normal photo");
        menu.getMenu().add("Maximum megapixels");
        menu.setOnMenuItemClickListener(item -> {
            maxPhotoMode = item.getTitle().toString().startsWith("Maximum");
            updateBadgeForCurrentMode();
            return true;
        });
        menu.show();
    }

    private void toggleVideoRecording() {
        Boolean recording = privateBoolean("recording");
        Boolean starting = privateBoolean("recordingStarting");
        if (Boolean.TRUE.equals(recording) || Boolean.TRUE.equals(starting)) {
            invokePrivate("stopRecording", new Class[]{});
        } else {
            invokePrivate("startRecording", new Class[]{});
        }
    }

    private void capturePhoto() {
        if (!photoMode || takingPhoto) return;
        CameraDevice camera = privateField("cameraDevice", CameraDevice.class);
        CameraCharacteristics chars = privateField("currentChars", CameraCharacteristics.class);
        Handler cameraHandler = privateField("cameraHandler", Handler.class);
        if (camera == null || chars == null || cameraHandler == null) {
            toast("Camera is not ready yet.");
            return;
        }

        PhotoConfig config = choosePhotoConfig(chars, maxPhotoMode);
        if (config == null) {
            toast("This lens did not report a JPEG photo size.");
            return;
        }

        takingPhoto = true;
        if (shutter != null) shutter.setAlpha(0.55f);
        closeExistingSession();
        closePhotoReader();

        try {
            photoReader = ImageReader.newInstance(config.size.getWidth(), config.size.getHeight(), ImageFormat.JPEG, 2);
            final ImageReader reader = photoReader;
            reader.setOnImageAvailableListener(r -> {
                Image image = null;
                try {
                    image = r.acquireLatestImage();
                    if (image == null) return;
                    ByteBuffer buffer = image.getPlanes()[0].getBuffer();
                    byte[] jpeg = new byte[buffer.remaining()];
                    buffer.get(jpeg);
                    Uri saved = saveJpeg(jpeg, config);
                    if (saved != null) {
                        runOnUiThread(() -> {
                            toast("Photo saved • " + config.size.getWidth() + "×" + config.size.getHeight());
                            refreshLatestMedia();
                        });
                    }
                } catch (Exception e) {
                    runOnUiThread(() -> toast("Photo save failed: " + e.getMessage()));
                } finally {
                    if (image != null) image.close();
                    finishPhotoCapture();
                }
            }, cameraHandler);

            OutputConfiguration output = new OutputConfiguration(reader.getSurface());
            if (Build.VERSION.SDK_INT >= 31 && config.maximumPixelMode) {
                output.addSensorPixelModeUsed(CameraMetadata.SENSOR_PIXEL_MODE_MAXIMUM_RESOLUTION);
            }
            List<OutputConfiguration> outputs = new ArrayList<>();
            outputs.add(output);

            SessionConfiguration sessionConfig = new SessionConfiguration(
                    SessionConfiguration.SESSION_REGULAR,
                    outputs,
                    command -> cameraHandler.post(command),
                    new CameraCaptureSession.StateCallback() {
                        @Override public void onConfigured(CameraCaptureSession session) {
                            try {
                                CaptureRequest.Builder still = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
                                still.addTarget(reader.getSurface());
                                still.set(CaptureRequest.CONTROL_CAPTURE_INTENT, CaptureRequest.CONTROL_CAPTURE_INTENT_STILL_CAPTURE);
                                still.set(CaptureRequest.JPEG_QUALITY, (byte) 97);
                                Object orientation = invokePrivate("computeOrientationHint", new Class[]{});
                                if (orientation instanceof Integer) still.set(CaptureRequest.JPEG_ORIENTATION, (Integer) orientation);

                                invokePrivate("configureCommonRequest", new Class[]{CaptureRequest.Builder.class}, still);
                                invokePrivate("setZoomOnBuilder", new Class[]{CaptureRequest.Builder.class}, still);
                                if (config.maximumPixelMode && Build.VERSION.SDK_INT >= 31) {
                                    still.set(CaptureRequest.SENSOR_PIXEL_MODE, CameraMetadata.SENSOR_PIXEL_MODE_MAXIMUM_RESOLUTION);
                                }

                                int[] af = chars.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES);
                                if (contains(af, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)) {
                                    still.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
                                }
                                session.capture(still.build(), new CameraCaptureSession.CaptureCallback() {}, cameraHandler);
                            } catch (Exception e) {
                                runOnUiThread(() -> toast("Photo capture failed: " + e.getMessage()));
                                finishPhotoCapture();
                            }
                        }

                        @Override public void onConfigureFailed(CameraCaptureSession session) {
                            runOnUiThread(() -> toast("Vivo HAL rejected this photo resolution."));
                            finishPhotoCapture();
                        }
                    });
            camera.createCaptureSession(sessionConfig);
        } catch (Exception e) {
            toast("Photo setup failed: " + e.getMessage());
            finishPhotoCapture();
        }
    }

    private PhotoConfig choosePhotoConfig(CameraCharacteristics chars, boolean wantMax) {
        StreamConfigurationMap normal = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        Size[] normalJpeg = normal != null ? normal.getOutputSizes(ImageFormat.JPEG) : null;
        Size normalSize = chooseNormalSize(normalJpeg);
        if (!wantMax) return normalSize != null ? new PhotoConfig(normalSize, false, "NORMAL") : null;

        if (Build.VERSION.SDK_INT >= 31) {
            StreamConfigurationMap maxMap = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP_MAXIMUM_RESOLUTION);
            Size[] maxJpeg = maxMap != null ? maxMap.getOutputSizes(ImageFormat.JPEG) : null;
            Size maxSize = largest(maxJpeg);
            if (maxSize != null) return new PhotoConfig(maxSize, true, "MAX");
        }
        Size largestNormal = largest(normalJpeg);
        return largestNormal != null ? new PhotoConfig(largestNormal, false, "MAX-HAL") : null;
    }

    private Size chooseNormalSize(Size[] sizes) {
        if (sizes == null || sizes.length == 0) return null;
        List<Size> list = new ArrayList<>(Arrays.asList(sizes));
        list.sort(Comparator.comparingLong(this::pixels).reversed());
        for (Size s : list) {
            long p = pixels(s);
            double ar = s.getWidth() / (double) s.getHeight();
            if (p <= 16_000_000L && ar > 1.25 && ar < 1.40) return s;
        }
        for (Size s : list) if (pixels(s) <= 16_000_000L) return s;
        return list.get(list.size() - 1);
    }

    private Size largest(Size[] sizes) {
        if (sizes == null || sizes.length == 0) return null;
        return Arrays.stream(sizes).max(Comparator.comparingLong(this::pixels)).orElse(null);
    }

    private long pixels(Size s) { return (long) s.getWidth() * (long) s.getHeight(); }

    private Uri saveJpeg(byte[] data, PhotoConfig config) throws Exception {
        ContentValues values = new ContentValues();
        values.put(MediaStore.Images.Media.DISPLAY_NAME,
                "X100_" + config.label + "_" + System.currentTimeMillis() + ".jpg");
        values.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
        values.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_DCIM + "/Camera");
        values.put(MediaStore.Images.Media.IS_PENDING, 1);

        ContentResolver resolver = getContentResolver();
        Uri uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
        if (uri == null) return null;
        try (OutputStream os = resolver.openOutputStream(uri, "w")) {
            if (os == null) throw new IllegalStateException("Cannot open camera album output");
            os.write(data);
        } catch (Exception e) {
            resolver.delete(uri, null, null);
            throw e;
        }
        ContentValues done = new ContentValues();
        done.put(MediaStore.Images.Media.IS_PENDING, 0);
        resolver.update(uri, done, null, null);
        return uri;
    }

    private void finishPhotoCapture() {
        if (!takingPhoto) return;
        takingPhoto = false;
        runOnUiThread(() -> { if (shutter != null) shutter.setAlpha(1f); });
        Handler cameraHandler = privateField("cameraHandler", Handler.class);
        if (cameraHandler != null) {
            cameraHandler.postDelayed(() -> {
                closePhotoReader();
                invokePrivate("startPreviewSession", new Class[]{});
            }, 250L);
        } else {
            closePhotoReader();
        }
    }

    private void closeExistingSession() {
        invokePrivate("closeSessionOnly", new Class[]{});
    }

    private void closePhotoReader() {
        if (photoReader != null) {
            try { photoReader.close(); } catch (Exception ignored) {}
            photoReader = null;
        }
    }

    private boolean contains(int[] values, int wanted) {
        if (values == null) return false;
        for (int v : values) if (v == wanted) return true;
        return false;
    }

    private Boolean privateBoolean(String name) {
        try {
            Class<?> c = getClass();
            while (c != null) {
                try {
                    Field f = c.getDeclaredField(name);
                    f.setAccessible(true);
                    return f.getBoolean(this);
                } catch (NoSuchFieldException e) { c = c.getSuperclass(); }
            }
        } catch (Exception ignored) {}
        return false;
    }

    private Object invokePrivate(String name, Class<?>[] types, Object... args) {
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

    private TextView findText(View root, String exact) {
        if (root instanceof TextView) {
            CharSequence t = ((TextView) root).getText();
            if (t != null && exact.contentEquals(t)) return (TextView) root;
        }
        if (root instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) root;
            for (int i = 0; i < g.getChildCount(); i++) {
                TextView r = findText(g.getChildAt(i), exact);
                if (r != null) return r;
            }
        }
        return null;
    }

    private void toast(String text) {
        runOnUiThread(() -> Toast.makeText(this, text, Toast.LENGTH_SHORT).show());
    }

    private static final class PhotoConfig {
        final Size size;
        final boolean maximumPixelMode;
        final String label;
        PhotoConfig(Size size, boolean maximumPixelMode, String label) {
            this.size = size;
            this.maximumPixelMode = maximumPixelMode;
            this.label = label;
        }
    }
}
