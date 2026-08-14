package com.anas.x100zoom;

import android.content.ContentUris;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.drawable.GradientDrawable;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CaptureRequest;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.util.Size;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import java.lang.reflect.Field;

/**
 * V9 camera chrome on top of V8.
 *
 * Adds:
 *  - real Camera2 torch toggle at top-right (replaces settings gear)
 *  - 3x3 rule-of-thirds grid toggle beside flash
 *  - latest X100Zoom media thumbnail at bottom-left
 */
public class CameraChromeActivity extends SmoothZoomActivity {
    private final Handler ui = new Handler(Looper.getMainLooper());

    private TextView flashButton;
    private TextView gridButton;
    private GridOverlay gridOverlay;
    private ImageView albumThumb;

    private boolean flashEnabled = false;
    private boolean gridEnabled = false;
    private Boolean lastFlashAvailable = null;

    private CaptureRequest.Builder lastBuilder;
    private CameraCaptureSession lastSession;
    private Uri latestMediaUri;
    private String latestMediaMime;

    private final Runnable cameraStateWatcher = new Runnable() {
        @Override public void run() {
            try {
                CaptureRequest.Builder b = privateField("repeatingBuilder", CaptureRequest.Builder.class);
                CameraCaptureSession s = privateField("captureSession", CameraCaptureSession.class);
                CameraCharacteristics c = privateField("currentChars", CameraCharacteristics.class);

                boolean flashAvailable = c != null && Boolean.TRUE.equals(
                        c.get(CameraCharacteristics.FLASH_INFO_AVAILABLE));

                if (lastFlashAvailable == null || lastFlashAvailable != flashAvailable) {
                    lastFlashAvailable = flashAvailable;
                    updateFlashButton(flashAvailable);
                }

                if (b != null && s != null && (b != lastBuilder || s != lastSession)) {
                    lastBuilder = b;
                    lastSession = s;
                    if (flashEnabled && flashAvailable) applyTorch(true);
                }
            } catch (Exception ignored) {}

            ui.postDelayed(this, 250L);
        }
    };

    private final Runnable albumWatcher = new Runnable() {
        @Override public void run() {
            refreshLatestMedia();
            ui.postDelayed(this, 1300L);
        }
    };

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ui.postDelayed(this::installCameraChrome, 350L);
        ui.post(cameraStateWatcher);
        ui.postDelayed(albumWatcher, 700L);
    }

    @Override protected void onResume() {
        super.onResume();
        ui.postDelayed(this::refreshLatestMedia, 300L);
    }

    @Override protected void onDestroy() {
        ui.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void installCameraChrome() {
        View rootContent = findViewById(android.R.id.content);
        if (!(rootContent instanceof ViewGroup)) return;

        View settings = findTextView(rootContent, "⚙");
        if (settings != null) settings.setVisibility(View.GONE);

        View preview = getPreviewView();
        if (preview == null || !(preview.getParent() instanceof FrameLayout)) return;
        FrameLayout root = (FrameLayout) preview.getParent();

        gridOverlay = new GridOverlay();
        gridOverlay.setVisibility(View.GONE);
        gridOverlay.setClickable(false);
        gridOverlay.setFocusable(false);
        FrameLayout.LayoutParams gridLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT);
        root.addView(gridOverlay, Math.min(1, root.getChildCount()), gridLp);

        flashButton = makeTopButton("⚡");
        flashButton.setOnClickListener(v -> toggleFlash());
        FrameLayout.LayoutParams flashLp = new FrameLayout.LayoutParams(dp(46), dp(46));
        flashLp.gravity = Gravity.TOP | Gravity.END;
        flashLp.rightMargin = dp(14);
        flashLp.topMargin = dp(16);
        root.addView(flashButton, flashLp);

        gridButton = makeTopButton("▦");
        gridButton.setTextSize(23f);
        gridButton.setOnClickListener(v -> toggleGrid());
        FrameLayout.LayoutParams gridButtonLp = new FrameLayout.LayoutParams(dp(46), dp(46));
        gridButtonLp.gravity = Gravity.TOP | Gravity.END;
        gridButtonLp.rightMargin = dp(68);
        gridButtonLp.topMargin = dp(16);
        root.addView(gridButton, gridButtonLp);

        albumThumb = new ImageView(this);
        albumThumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
        GradientDrawable thumbBg = new GradientDrawable();
        thumbBg.setColor(0xCC202020);
        thumbBg.setCornerRadius(dp(9));
        thumbBg.setStroke(dp(2), 0xEEFFFFFF);
        albumThumb.setBackground(thumbBg);
        albumThumb.setClipToOutline(true);
        albumThumb.setOnClickListener(v -> openLatestMedia());
        albumThumb.setContentDescription("Open latest camera media");
        FrameLayout.LayoutParams albumLp = new FrameLayout.LayoutParams(dp(62), dp(62));
        albumLp.gravity = Gravity.BOTTOM | Gravity.START;
        albumLp.leftMargin = dp(20);
        albumLp.bottomMargin = dp(70);
        root.addView(albumThumb, albumLp);

        refreshLatestMedia();
        updateGridButton();
        updateFlashButton(false);
    }

    private TextView makeTopButton(String text) {
        TextView v = new TextView(this);
        v.setText(text);
        v.setTextColor(Color.WHITE);
        v.setTextSize(25f);
        v.setGravity(Gravity.CENTER);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0x44000000);
        bg.setCornerRadius(dp(20));
        v.setBackground(bg);
        v.setShadowLayer(4f, 0f, 1f, Color.BLACK);
        return v;
    }

    private void toggleGrid() {
        gridEnabled = !gridEnabled;
        if (gridOverlay != null) {
            gridOverlay.setVisibility(gridEnabled ? View.VISIBLE : View.GONE);
        }
        updateGridButton();
    }

    private void updateGridButton() {
        if (gridButton == null) return;
        gridButton.setTextColor(gridEnabled ? 0xFFFFD54F : Color.WHITE);
        gridButton.setAlpha(1f);
    }

    private void toggleFlash() {
        CameraCharacteristics c = privateField("currentChars", CameraCharacteristics.class);
        boolean available = c != null && Boolean.TRUE.equals(
                c.get(CameraCharacteristics.FLASH_INFO_AVAILABLE));

        if (!available) {
            Toast.makeText(this, "Flash is unavailable on the active lens.", Toast.LENGTH_SHORT).show();
            updateFlashButton(false);
            return;
        }

        flashEnabled = !flashEnabled;
        applyTorch(flashEnabled);
        updateFlashButton(true);
    }

    private void updateFlashButton(boolean available) {
        if (flashButton == null) return;
        flashButton.setAlpha(available ? 1f : 0.42f);
        flashButton.setTextColor(flashEnabled && available ? 0xFFFFD54F : Color.WHITE);
    }

    private void applyTorch(boolean enabled) {
        try {
            CaptureRequest.Builder b = privateField("repeatingBuilder", CaptureRequest.Builder.class);
            CameraCaptureSession s = privateField("captureSession", CameraCaptureSession.class);
            Handler cameraHandler = privateField("cameraHandler", Handler.class);
            CameraCharacteristics c = privateField("currentChars", CameraCharacteristics.class);
            if (b == null || s == null || c == null) return;

            boolean available = Boolean.TRUE.equals(c.get(CameraCharacteristics.FLASH_INFO_AVAILABLE));
            if (!available) return;

            b.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON);
            b.set(CaptureRequest.FLASH_MODE,
                    enabled ? CaptureRequest.FLASH_MODE_TORCH : CaptureRequest.FLASH_MODE_OFF);
            s.setRepeatingRequest(b.build(), null, cameraHandler);
        } catch (Exception e) {
            if (enabled) {
                flashEnabled = false;
                runOnUiThread(() -> {
                    updateFlashButton(false);
                    Toast.makeText(this, "The camera HAL rejected torch mode on this lens.",
                            Toast.LENGTH_SHORT).show();
                });
            }
        }
    }

    private void refreshLatestMedia() {
        try {
            Uri files = MediaStore.Files.getContentUri("external");
            String[] projection = {
                    MediaStore.Files.FileColumns._ID,
                    MediaStore.Files.FileColumns.MEDIA_TYPE,
                    MediaStore.Files.FileColumns.MIME_TYPE,
                    MediaStore.Files.FileColumns.DATE_ADDED
            };
            String selection = MediaStore.Files.FileColumns.RELATIVE_PATH + "=? AND (" +
                    MediaStore.Files.FileColumns.MEDIA_TYPE + "=? OR " +
                    MediaStore.Files.FileColumns.MEDIA_TYPE + "=?)";
            String[] args = {
                    "Movies/X100Zoom/",
                    String.valueOf(MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO),
                    String.valueOf(MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE)
            };
            String sort = MediaStore.Files.FileColumns.DATE_ADDED + " DESC";

            try (Cursor cursor = getContentResolver().query(
                    files, projection, selection, args, sort)) {
                if (cursor == null || !cursor.moveToFirst()) {
                    showEmptyThumbnail();
                    return;
                }

                long id = cursor.getLong(cursor.getColumnIndexOrThrow(
                        MediaStore.Files.FileColumns._ID));
                String mime = cursor.getString(cursor.getColumnIndexOrThrow(
                        MediaStore.Files.FileColumns.MIME_TYPE));
                Uri uri = ContentUris.withAppendedId(files, id);

                if (uri.equals(latestMediaUri)) return;
                latestMediaUri = uri;
                latestMediaMime = mime;

                Bitmap thumb = getContentResolver().loadThumbnail(
                        uri, new Size(dp(160), dp(160)), null);
                if (albumThumb != null) {
                    albumThumb.setImageBitmap(thumb);
                    albumThumb.setAlpha(1f);
                }
            }
        } catch (Exception ignored) {
            showEmptyThumbnail();
        }
    }

    private void showEmptyThumbnail() {
        latestMediaUri = null;
        latestMediaMime = null;
        if (albumThumb != null) {
            albumThumb.setImageDrawable(null);
            albumThumb.setAlpha(0.58f);
        }
    }

    private void openLatestMedia() {
        if (latestMediaUri == null) {
            Toast.makeText(this, "No X100 Zoom Camera videos yet.", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            Intent view = new Intent(Intent.ACTION_VIEW);
            view.setDataAndType(latestMediaUri,
                    latestMediaMime != null ? latestMediaMime : "video/*");
            view.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(view);
        } catch (Exception e) {
            Toast.makeText(this, "No gallery app could open this media.", Toast.LENGTH_SHORT).show();
        }
    }

    private View getPreviewView() {
        try {
            return privateField("textureView", View.class);
        } catch (Exception e) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private <T> T privateField(String name, Class<T> type) {
        Class<?> cls = getClass();
        while (cls != null) {
            try {
                Field f = cls.getDeclaredField(name);
                f.setAccessible(true);
                Object value = f.get(this);
                return value == null ? null : (T) value;
            } catch (NoSuchFieldException e) {
                cls = cls.getSuperclass();
            } catch (Exception e) {
                return null;
            }
        }
        return null;
    }

    private TextView findTextView(View root, String exactText) {
        if (root == null) return null;
        if (root instanceof TextView) {
            CharSequence text = ((TextView) root).getText();
            if (text != null && exactText.contentEquals(text)) return (TextView) root;
        }
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) {
                TextView found = findTextView(group.getChildAt(i), exactText);
                if (found != null) return found;
            }
        }
        return null;
    }

    private final class GridOverlay extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

        GridOverlay() {
            super(CameraChromeActivity.this);
            paint.setColor(0x88FFFFFF);
            paint.setStrokeWidth(dp(1));
        }

        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float w = getWidth();
            float h = getHeight();
            canvas.drawLine(w / 3f, 0f, w / 3f, h, paint);
            canvas.drawLine(2f * w / 3f, 0f, 2f * w / 3f, h, paint);
            canvas.drawLine(0f, h / 3f, w, h / 3f, paint);
            canvas.drawLine(0f, 2f * h / 3f, w, 2f * h / 3f, paint);
        }
    }
}
