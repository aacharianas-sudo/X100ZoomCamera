package com.anas.x100zoom;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Size;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Locale;

/**
 * V13 UI rebuild.
 *
 * Replaces the accumulated debug/development chrome with one coherent camera UI
 * inspired by the current vivo X300 Ultra interaction hierarchy:
 *  - dark camera chrome with yellow selected state
 *  - compact top tool row
 *  - focal/zoom selector directly above the shooting modes
 *  - PHOTO / VIDEO mode rail
 *  - large camera-style shutter, gallery thumbnail on the left
 *  - vivo-style More Settings sheet for real controls only
 *
 * The existing Camera2 engine remains underneath. No X300-only hardware feature is
 * advertised here unless the X100 engine actually implements it.
 */
public class X300UltraUiActivity extends V12CameraActivity {
    private static final int ACCENT = 0xFFFFD129;
    private static final int TEXT_DIM = 0xFFB8B8B8;
    private static final int PANEL = 0xFF050505;
    private static final int TILE = 0xFF292929;
    private static final float MIN_ZOOM = 0.6f;
    private static final float MAX_ZOOM = 100f;

    private final Handler xui = new Handler(Looper.getMainLooper());

    private FrameLayout cameraRoot;
    private FrameLayout topChrome;
    private FrameLayout bottomChrome;
    private FrameLayout settingsSheet;
    private LinearLayout modeRail;
    private ImageView galleryView;
    private ShutterView shutterView;
    private ZoomStripView zoomStrip;
    private FineZoomView fineZoom;
    private TextView photoModeButton;
    private TextView videoModeButton;
    private TextView configPill;
    private TextView settingsTitle;
    private IconButton flashIcon;
    private IconButton gridIcon;
    private IconButton focusIcon;
    private IconButton settingsIcon;

    private TextView[] ratioChoices;
    private TextView[] timerChoices;
    private TextView qualityTile;
    private TextView focusTile;
    private TextView gridTile;
    private TextView flashTile;

    private boolean uiInstalled = false;
    private boolean settingsOpen = false;
    private boolean fineZoomOpen = false;
    private boolean lastPhotoMode = false;
    private boolean lastRecording = false;
    private float lastZoom = -1f;
    private Drawable lastGalleryDrawable;

    private boolean zoomHolding = false;
    private boolean zoomHoldStarted = false;
    private int zoomHoldDirection = 0;
    private float zoomHoldTarget = 1f;

    private final Runnable stateWatcher = new Runnable() {
        @Override public void run() {
            if (uiInstalled) syncUiState();
            xui.postDelayed(this, 90L);
        }
    };

    private final Runnable zoomHoldRunnable = new Runnable() {
        @Override public void run() {
            if (!zoomHolding || zoomHoldDirection == 0) return;
            zoomHoldStarted = true;
            float z = zoomHoldTarget;
            float step;
            if (z < 3f) step = 0.025f;
            else if (z < 10f) step = 0.055f;
            else if (z < 30f) step = 0.14f;
            else if (z < 60f) step = 0.30f;
            else step = 0.48f;
            zoomHoldTarget = clamp(z + zoomHoldDirection * step, MIN_ZOOM, MAX_ZOOM);
            setUiZoom(zoomHoldTarget);
            if ((zoomHoldDirection < 0 && zoomHoldTarget <= MIN_ZOOM) ||
                    (zoomHoldDirection > 0 && zoomHoldTarget >= MAX_ZOOM)) {
                stopZoomHold();
            } else {
                xui.postDelayed(this, 16L);
            }
        }
    };

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setNavigationBarColor(Color.BLACK);
        xui.postDelayed(this::installX300Ui, 2100L);
        xui.postDelayed(stateWatcher, 2250L);
    }

    @Override protected void onDestroy() {
        xui.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private GradientDrawable rounded(int color, int radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(radiusDp));
        return d;
    }

    private GradientDrawable outlined(int fill, int stroke, int strokeDp, int radiusDp) {
        GradientDrawable d = rounded(fill, radiusDp);
        d.setStroke(dp(strokeDp), stroke);
        return d;
    }

    private void installX300Ui() {
        View preview = field("textureView", View.class);
        if (preview == null || !(preview.getParent() instanceof FrameLayout)) return;
        cameraRoot = (FrameLayout) preview.getParent();

        hideLegacyChrome();
        buildTopChrome();
        buildBottomChrome();
        buildZoomStrip();
        buildFineZoom();
        buildSettingsSheet();

        uiInstalled = true;
        syncUiState();
    }

    /** Hide the old development chrome. The V13 chrome is added above everything. */
    private void hideLegacyChrome() {
        hideFieldView("zoomLiveView");
        hideFieldView("routeView");
        hideFieldView("timerView");
        hideFieldView("modeStrip");
        hideFieldView("focusModeButton");
        hideFieldView("flashButton");
        hideFieldView("gridButton");
        hideFieldView("albumThumb");

        Button oldShutter = field("recordButton", Button.class);
        if (oldShutter != null && oldShutter.getParent() instanceof View) {
            View recordWrap = (View) oldShutter.getParent();
            if (recordWrap.getParent() instanceof View) {
                ((View) recordWrap.getParent()).setVisibility(View.GONE);
            }
        }
    }

    private void hideFieldView(String name) {
        View v = field(name, View.class);
        if (v != null) v.setVisibility(View.GONE);
    }

    private void buildTopChrome() {
        topChrome = new FrameLayout(this);
        topChrome.setBackgroundColor(0xCC050505);
        topChrome.setElevation(dp(20));
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, dp(76));
        lp.gravity = Gravity.TOP;
        cameraRoot.addView(topChrome, lp);

        LinearLayout leftTools = new LinearLayout(this);
        leftTools.setOrientation(LinearLayout.HORIZONTAL);
        leftTools.setGravity(Gravity.CENTER_VERTICAL);
        FrameLayout.LayoutParams toolsLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, dp(56));
        toolsLp.gravity = Gravity.BOTTOM | Gravity.START;
        toolsLp.leftMargin = dp(8);
        toolsLp.bottomMargin = dp(4);
        topChrome.addView(leftTools, toolsLp);

        flashIcon = new IconButton(IconButton.FLASH);
        flashIcon.setContentDescription("Flash");
        flashIcon.setOnClickListener(v -> invokeAny("toggleFlash", new Class[]{}));
        leftTools.addView(flashIcon, iconLp());

        gridIcon = new IconButton(IconButton.GRID);
        gridIcon.setContentDescription("Grid lines");
        gridIcon.setOnClickListener(v -> invokeAny("toggleGrid", new Class[]{}));
        leftTools.addView(gridIcon, iconLp());

        focusIcon = new IconButton(IconButton.FOCUS);
        focusIcon.setContentDescription("Auto or manual focus");
        focusIcon.setOnClickListener(v -> toggleFocusMode());
        leftTools.addView(focusIcon, iconLp());

        configPill = new TextView(this);
        configPill.setText("4:3");
        configPill.setTextColor(Color.WHITE);
        configPill.setTextSize(12f);
        configPill.setTypeface(null, android.graphics.Typeface.BOLD);
        configPill.setGravity(Gravity.CENTER);
        configPill.setMinWidth(dp(58));
        configPill.setBackground(rounded(0x33000000, 16));
        configPill.setOnClickListener(v -> showSettings(true));
        addPressFeedback(configPill);
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(dp(62), dp(46));
        cp.leftMargin = dp(2);
        leftTools.addView(configPill, cp);

        settingsIcon = new IconButton(IconButton.SETTINGS);
        settingsIcon.setContentDescription("More settings");
        settingsIcon.setOnClickListener(v -> showSettings(!settingsOpen));
        FrameLayout.LayoutParams settingsLp = new FrameLayout.LayoutParams(dp(52), dp(52));
        settingsLp.gravity = Gravity.BOTTOM | Gravity.END;
        settingsLp.rightMargin = dp(8);
        settingsLp.bottomMargin = dp(6);
        topChrome.addView(settingsIcon, settingsLp);
    }

    private LinearLayout.LayoutParams iconLp() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(dp(48), dp(48));
        p.rightMargin = dp(1);
        return p;
    }

    private void buildBottomChrome() {
        bottomChrome = new FrameLayout(this);
        bottomChrome.setBackgroundColor(PANEL);
        bottomChrome.setElevation(dp(18));
        FrameLayout.LayoutParams bottomLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, dp(222));
        bottomLp.gravity = Gravity.BOTTOM;
        cameraRoot.addView(bottomChrome, bottomLp);

        modeRail = new LinearLayout(this);
        modeRail.setOrientation(LinearLayout.HORIZONTAL);
        modeRail.setGravity(Gravity.CENTER);
        FrameLayout.LayoutParams railLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, dp(48));
        railLp.gravity = Gravity.TOP;
        railLp.topMargin = dp(2);
        bottomChrome.addView(modeRail, railLp);

        photoModeButton = modeLabel("Photo");
        videoModeButton = modeLabel("Video");
        TextView more = modeLabel("More");
        more.setTextColor(TEXT_DIM);

        photoModeButton.setOnClickListener(v -> setPhotoModeReal(true));
        videoModeButton.setOnClickListener(v -> setPhotoModeReal(false));
        more.setOnClickListener(v -> showSettings(true));

        modeRail.addView(photoModeButton, new LinearLayout.LayoutParams(dp(90), dp(46)));
        modeRail.addView(videoModeButton, new LinearLayout.LayoutParams(dp(90), dp(46)));
        modeRail.addView(more, new LinearLayout.LayoutParams(dp(90), dp(46)));

        FrameLayout shutterWrap = new FrameLayout(this);
        FrameLayout.LayoutParams shutterLp = new FrameLayout.LayoutParams(dp(104), dp(104));
        shutterLp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        shutterLp.topMargin = dp(62);
        bottomChrome.addView(shutterWrap, shutterLp);

        shutterView = new ShutterView();
        shutterView.setContentDescription("Shutter");
        shutterView.setOnClickListener(v -> triggerExistingShutter());
        shutterView.setOnTouchListener((v, e) -> {
            if (e.getActionMasked() == MotionEvent.ACTION_DOWN) {
                v.animate().scaleX(0.92f).scaleY(0.92f).setDuration(80L).start();
            } else if (e.getActionMasked() == MotionEvent.ACTION_UP ||
                    e.getActionMasked() == MotionEvent.ACTION_CANCEL) {
                v.animate().scaleX(1f).scaleY(1f).setDuration(110L).start();
            }
            return false;
        });
        shutterWrap.addView(shutterView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        galleryView = new ImageView(this);
        galleryView.setScaleType(ImageView.ScaleType.CENTER_CROP);
        galleryView.setBackground(outlined(0xFF1A1A1A, 0x55FFFFFF, 1, 9));
        galleryView.setClipToOutline(true);
        galleryView.setContentDescription("Open latest photo or video");
        galleryView.setOnClickListener(v -> openLegacyGallery());
        addPressFeedback(galleryView);
        FrameLayout.LayoutParams galleryLp = new FrameLayout.LayoutParams(dp(58), dp(58));
        galleryLp.gravity = Gravity.TOP | Gravity.START;
        galleryLp.leftMargin = dp(24);
        galleryLp.topMargin = dp(86);
        bottomChrome.addView(galleryView, galleryLp);

        IconButton lowerSettings = new IconButton(IconButton.MORE);
        lowerSettings.setContentDescription("Camera controls");
        lowerSettings.setOnClickListener(v -> showSettings(true));
        FrameLayout.LayoutParams lowerLp = new FrameLayout.LayoutParams(dp(58), dp(58));
        lowerLp.gravity = Gravity.TOP | Gravity.END;
        lowerLp.rightMargin = dp(24);
        lowerLp.topMargin = dp(86);
        bottomChrome.addView(lowerSettings, lowerLp);
    }

    private TextView modeLabel(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(TEXT_DIM);
        t.setTextSize(15f);
        t.setTypeface(null, android.graphics.Typeface.BOLD);
        t.setGravity(Gravity.CENTER);
        t.setClickable(true);
        t.setFocusable(true);
        addPressFeedback(t);
        return t;
    }

    private void buildZoomStrip() {
        zoomStrip = new ZoomStripView();
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(dp(344), dp(62));
        lp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        lp.bottomMargin = dp(216);
        cameraRoot.addView(zoomStrip, lp);
    }

    private void buildFineZoom() {
        fineZoom = new FineZoomView();
        fineZoom.setVisibility(View.GONE);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(dp(352), dp(72));
        lp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        lp.bottomMargin = dp(278);
        cameraRoot.addView(fineZoom, lp);
    }

    private void buildSettingsSheet() {
        settingsSheet = new FrameLayout(this);
        settingsSheet.setBackgroundColor(0xF7060606);
        settingsSheet.setVisibility(View.GONE);
        settingsSheet.setAlpha(0f);
        settingsSheet.setTranslationY(-dp(24));
        settingsSheet.setElevation(dp(60));
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, dp(430));
        lp.gravity = Gravity.TOP;
        cameraRoot.addView(settingsSheet, lp);

        IconButton close = new IconButton(IconButton.CLOSE);
        close.setContentDescription("Close settings");
        close.setOnClickListener(v -> showSettings(false));
        FrameLayout.LayoutParams closeLp = new FrameLayout.LayoutParams(dp(56), dp(56));
        closeLp.gravity = Gravity.TOP | Gravity.START;
        closeLp.leftMargin = dp(10);
        closeLp.topMargin = dp(12);
        settingsSheet.addView(close, closeLp);

        settingsTitle = new TextView(this);
        settingsTitle.setText("More settings  ›");
        settingsTitle.setTextColor(Color.WHITE);
        settingsTitle.setTextSize(18f);
        settingsTitle.setTypeface(null, android.graphics.Typeface.BOLD);
        settingsTitle.setGravity(Gravity.CENTER_VERTICAL | Gravity.END);
        FrameLayout.LayoutParams titleLp = new FrameLayout.LayoutParams(dp(190), dp(56));
        titleLp.gravity = Gravity.TOP | Gravity.END;
        titleLp.rightMargin = dp(18);
        titleLp.topMargin = dp(12);
        settingsSheet.addView(settingsTitle, titleLp);

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(18), dp(8), dp(18), dp(12));
        FrameLayout.LayoutParams contentLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, dp(342));
        contentLp.gravity = Gravity.BOTTOM;
        contentLp.bottomMargin = dp(12);
        settingsSheet.addView(content, contentLp);

        LinearLayout ratioRow = segmentedRow();
        ratioChoices = new TextView[]{
                segment("1:1"), segment("4:3"), segment("16:9"), segment("Full")
        };
        for (TextView choice : ratioChoices) {
            ratioRow.addView(choice, new LinearLayout.LayoutParams(0, dp(54), 1f));
            choice.setOnClickListener(v -> selectPhotoRatio(((TextView) v).getText().toString()));
        }
        content.addView(ratioRow, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(54)));

        LinearLayout timerRow = segmentedRow();
        timerChoices = new TextView[]{
                segment("Off"), segment("3s"), segment("5s"), segment("10s")
        };
        for (TextView choice : timerChoices) {
            timerRow.addView(choice, new LinearLayout.LayoutParams(0, dp(54), 1f));
            choice.setOnClickListener(v -> {
                String s = ((TextView) v).getText().toString();
                int seconds = "3s".equals(s) ? 3 : ("5s".equals(s) ? 5 : ("10s".equals(s) ? 10 : 0));
                selectPhotoTimer(seconds);
            });
        }
        LinearLayout.LayoutParams timerLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(54));
        timerLp.topMargin = dp(12);
        content.addView(timerRow, timerLp);

        LinearLayout tiles = new LinearLayout(this);
        tiles.setOrientation(LinearLayout.HORIZONTAL);
        tiles.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams tilesLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(88));
        tilesLp.topMargin = dp(18);
        content.addView(tiles, tilesLp);

        gridTile = settingsTile("Grid lines", () -> invokeAny("toggleGrid", new Class[]{}));
        focusTile = settingsTile("Focus", this::toggleFocusMode);
        qualityTile = settingsTile("Quality", this::toggleQualityOrVideoMode);
        flashTile = settingsTile("Flash", () -> invokeAny("toggleFlash", new Class[]{}));
        tiles.addView(gridTile, weightedTileLp());
        tiles.addView(focusTile, weightedTileLp());
        tiles.addView(qualityTile, weightedTileLp());
        tiles.addView(flashTile, weightedTileLp());

        TextView note = new TextView(this);
        note.setText("Only controls that change the real X100 camera/output are shown here.");
        note.setTextColor(0xFF8D8D8D);
        note.setTextSize(10f);
        note.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams noteLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(42));
        noteLp.topMargin = dp(10);
        content.addView(note, noteLp);
    }

    private LinearLayout segmentedRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER);
        row.setBackground(rounded(0xFF262626, 9));
        row.setPadding(dp(2), dp(2), dp(2), dp(2));
        return row;
    }

    private TextView segment(String label) {
        TextView t = new TextView(this);
        t.setText(label);
        t.setTextColor(Color.WHITE);
        t.setTextSize(14f);
        t.setTypeface(null, android.graphics.Typeface.BOLD);
        t.setGravity(Gravity.CENTER);
        t.setClickable(true);
        t.setFocusable(true);
        addPressFeedback(t);
        return t;
    }

    private TextView settingsTile(String label, Runnable action) {
        TextView t = new TextView(this);
        t.setText(label);
        t.setTextColor(Color.WHITE);
        t.setTextSize(12f);
        t.setTypeface(null, android.graphics.Typeface.BOLD);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(5), dp(8), dp(5), dp(8));
        t.setBackground(rounded(TILE, 9));
        t.setOnClickListener(v -> action.run());
        addPressFeedback(t);
        return t;
    }

    private LinearLayout.LayoutParams weightedTileLp() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, dp(82), 1f);
        p.setMargins(dp(4), dp(3), dp(4), dp(3));
        return p;
    }

    private void showSettings(boolean show) {
        if (settingsSheet == null || settingsOpen == show) return;
        settingsOpen = show;
        if (show) {
            fineZoomOpen = false;
            if (fineZoom != null) fineZoom.setVisibility(View.GONE);
            settingsSheet.setVisibility(View.VISIBLE);
            settingsSheet.animate().alpha(1f).translationY(0f).setDuration(170L).start();
        } else {
            settingsSheet.animate().alpha(0f).translationY(-dp(24)).setDuration(150L)
                    .withEndAction(() -> settingsSheet.setVisibility(View.GONE)).start();
        }
    }

    private void toggleFineZoom() {
        fineZoomOpen = !fineZoomOpen;
        if (fineZoom == null) return;
        if (fineZoomOpen) {
            showSettings(false);
            fineZoom.setAlpha(0f);
            fineZoom.setVisibility(View.VISIBLE);
            fineZoom.animate().alpha(1f).setDuration(120L).start();
        } else {
            fineZoom.animate().alpha(0f).setDuration(100L)
                    .withEndAction(() -> fineZoom.setVisibility(View.GONE)).start();
        }
    }

    private void addPressFeedback(View view) {
        view.setOnTouchListener((v, e) -> {
            if (e.getActionMasked() == MotionEvent.ACTION_DOWN) v.setAlpha(0.58f);
            else if (e.getActionMasked() == MotionEvent.ACTION_UP ||
                    e.getActionMasked() == MotionEvent.ACTION_CANCEL) v.setAlpha(1f);
            return false;
        });
    }

    private void syncUiState() {
        boolean photo = booleanField("photoMode");
        boolean recording = booleanField("recording");
        float zoom = floatField("requestedUiZoom", 1f);

        // Parent V12 periodically re-shows these two controls. Keep them behind V13.
        View oldPhotoTop = field("photoTopRow", View.class);
        if (oldPhotoTop != null) oldPhotoTop.setAlpha(0f);
        View oldModeBadge = field("modeBadge", View.class);
        if (oldModeBadge != null) oldModeBadge.setAlpha(0f);

        if (photo != lastPhotoMode || recording != lastRecording) {
            lastPhotoMode = photo;
            lastRecording = recording;
            updateModeUi(photo, recording);
        }

        if (Math.abs(zoom - lastZoom) > 0.005f) {
            lastZoom = zoom;
            if (zoomStrip != null) zoomStrip.setZoom(zoom);
            if (fineZoom != null) fineZoom.setZoom(zoom);
        }

        if (configPill != null) {
            if (photo) {
                configPill.setText(stringField("photoRatio", "4:3"));
            } else {
                int fps = intField("selectedFps", 60);
                Object size = objectField("selectedSize");
                boolean uhd = size instanceof Size && ((Size) size).getWidth() >= 3800;
                configPill.setText((uhd ? "4K" : "1080P") + "  " + fps);
            }
        }

        boolean flash = booleanField("flashEnabled");
        boolean grid = booleanField("gridEnabled");
        boolean manual = booleanField("manualMode");
        if (flashIcon != null) flashIcon.setActive(flash);
        if (gridIcon != null) gridIcon.setActive(grid);
        if (focusIcon != null) focusIcon.setActive(manual);
        if (settingsIcon != null) settingsIcon.setActive(settingsOpen);

        if (gridTile != null) styleTile(gridTile, grid);
        if (focusTile != null) {
            focusTile.setText(manual ? "Focus\nManual" : "Focus\nAuto");
            styleTile(focusTile, manual);
        }
        if (flashTile != null) styleTile(flashTile, flash);
        if (qualityTile != null) {
            if (photo) {
                boolean max = booleanField("maxPhotoMode");
                qualityTile.setText(max ? "MAX MP" : "Normal");
                styleTile(qualityTile, max);
            } else {
                int fps = intField("selectedFps", 60);
                Object size = objectField("selectedSize");
                boolean uhd = size instanceof Size && ((Size) size).getWidth() >= 3800;
                qualityTile.setText((uhd ? "4K" : "1080P") + "\n" + fps + " fps");
                styleTile(qualityTile, false);
            }
        }

        updateSegmentStates(photo);
        syncGalleryThumbnail();
    }

    private void updateModeUi(boolean photo, boolean recording) {
        if (photoModeButton != null) {
            photoModeButton.setTextColor(photo ? ACCENT : Color.WHITE);
            photoModeButton.setScaleX(photo ? 1.05f : 1f);
            photoModeButton.setScaleY(photo ? 1.05f : 1f);
        }
        if (videoModeButton != null) {
            videoModeButton.setTextColor(!photo ? ACCENT : Color.WHITE);
            videoModeButton.setScaleX(!photo ? 1.05f : 1f);
            videoModeButton.setScaleY(!photo ? 1.05f : 1f);
        }
        if (shutterView != null) {
            shutterView.setPhotoMode(photo);
            shutterView.setRecording(recording);
        }
        if (settingsTitle != null) settingsTitle.setText(photo ? "More settings  ›" : "Video settings  ›");
    }

    private void updateSegmentStates(boolean photo) {
        if (ratioChoices != null) {
            String ratio = stringField("photoRatio", "4:3");
            for (TextView t : ratioChoices) {
                boolean selected = photo && t.getText().toString().equals(ratio);
                t.setTextColor(selected ? Color.BLACK : (photo ? Color.WHITE : 0xFF737373));
                t.setBackground(selected ? rounded(ACCENT, 8) : null);
                t.setEnabled(photo);
            }
        }
        if (timerChoices != null) {
            int seconds = intField("photoTimerSeconds", 0);
            String selectedLabel = seconds == 0 ? "Off" : seconds + "s";
            for (TextView t : timerChoices) {
                boolean selected = photo && t.getText().toString().equals(selectedLabel);
                t.setTextColor(selected ? Color.BLACK : (photo ? Color.WHITE : 0xFF737373));
                t.setBackground(selected ? rounded(ACCENT, 8) : null);
                t.setEnabled(photo);
            }
        }
    }

    private void styleTile(TextView tile, boolean active) {
        tile.setTextColor(active ? Color.BLACK : Color.WHITE);
        tile.setBackground(rounded(active ? ACCENT : TILE, 9));
    }

    private void syncGalleryThumbnail() {
        ImageView old = field("albumThumb", ImageView.class);
        if (old == null || galleryView == null) return;
        Drawable d = old.getDrawable();
        if (d != null && d != lastGalleryDrawable) {
            lastGalleryDrawable = d;
            galleryView.setImageDrawable(d);
        }
    }

    private void setPhotoModeReal(boolean photo) {
        if (booleanField("recording") || booleanField("recordingStarting")) return;
        invokeAny("setPhotoMode", new Class[]{boolean.class}, photo);
        xui.postDelayed(this::syncUiState, 40L);
    }

    private void triggerExistingShutter() {
        Button old = field("recordButton", Button.class);
        if (old != null) old.performClick();
    }

    private void openLegacyGallery() {
        ImageView old = field("albumThumb", ImageView.class);
        if (old != null) old.performClick();
    }

    private void toggleFocusMode() {
        TextView old = field("focusModeButton", TextView.class);
        if (old != null) old.performClick();
    }

    private void selectPhotoRatio(String ratio) {
        if (!booleanField("photoMode")) return;
        invokeAny("selectRatio", new Class[]{String.class}, ratio);
        setObjectField("photoRatio", ratio);
        syncUiState();
    }

    private void selectPhotoTimer(int seconds) {
        if (!booleanField("photoMode")) return;
        invokeAny("selectTimer", new Class[]{int.class}, seconds);
        setIntField("photoTimerSeconds", seconds);
        syncUiState();
    }

    private void toggleQualityOrVideoMode() {
        if (booleanField("photoMode")) {
            boolean next = !booleanField("maxPhotoMode");
            invokeAny("setPhotoMax", new Class[]{boolean.class}, next);
            setBooleanField("maxPhotoMode", next);
            syncUiState();
            return;
        }
        cycleVideoMode();
    }

    private void cycleVideoMode() {
        if (booleanField("recording") || booleanField("recordingStarting")) return;
        int fps = intField("selectedFps", 60);
        Object size = objectField("selectedSize");
        boolean uhd = size instanceof Size && ((Size) size).getWidth() >= 3800;

        // 1080/30 -> 1080/60 -> 4K/30 -> 4K/60 -> repeat.
        boolean nextUhd;
        int nextFps;
        if (!uhd && fps == 30) { nextUhd = false; nextFps = 60; }
        else if (!uhd) { nextUhd = true; nextFps = 30; }
        else if (fps == 30) { nextUhd = true; nextFps = 60; }
        else { nextUhd = false; nextFps = 30; }

        setObjectField("selectedSize", nextUhd ? new Size(3840, 2160) : new Size(1920, 1080));
        setIntField("selectedFps", nextFps);
        invokeAny("updateModeBadge", new Class[]{});
        Handler cameraHandler = field("cameraHandler", Handler.class);
        if (cameraHandler != null) cameraHandler.post(() -> invokeAny("startPreviewSession", new Class[]{}));
        syncUiState();
    }

    private void setUiZoom(float value) {
        float z = clamp(value, MIN_ZOOM, MAX_ZOOM);
        invokeAny("setV11Zoom", new Class[]{float.class}, z);
        // Fallback if V11 reflection ever changes.
        setFloatField("requestedUiZoom", z);
        xui.postDelayed(this::syncUiState, 20L);
    }

    private void startZoomHold(int direction) {
        zoomHolding = true;
        zoomHoldStarted = false;
        zoomHoldDirection = direction;
        zoomHoldTarget = floatField("requestedUiZoom", 1f);
        xui.removeCallbacks(zoomHoldRunnable);
        xui.postDelayed(zoomHoldRunnable, 180L);
    }

    private void finishZoomHold(int direction) {
        boolean held = zoomHoldStarted;
        float z = floatField("requestedUiZoom", 1f);
        stopZoomHold();
        if (!held) {
            float step = z < 3f ? 0.1f : (z < 10f ? 0.2f : (z < 30f ? 0.5f : 1f));
            setUiZoom(z + direction * step);
        }
    }

    private void stopZoomHold() {
        zoomHolding = false;
        zoomHoldStarted = false;
        zoomHoldDirection = 0;
        xui.removeCallbacks(zoomHoldRunnable);
    }

    private float clamp(float v, float min, float max) {
        return Math.max(min, Math.min(max, v));
    }

    private final class ShutterView extends View {
        private final Paint outer = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint inner = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
        private boolean photo = false;
        private boolean recording = false;

        ShutterView() {
            super(X300UltraUiActivity.this);
            setClickable(true);
            setFocusable(true);
            outer.setStyle(Paint.Style.STROKE);
            outer.setStrokeWidth(dp(4));
            outer.setColor(Color.WHITE);
            ring.setStyle(Paint.Style.STROKE);
            ring.setStrokeWidth(dp(3));
            ring.setColor(ACCENT);
        }

        void setPhotoMode(boolean value) { photo = value; invalidate(); }
        void setRecording(boolean value) { recording = value; invalidate(); }

        @Override protected void onDraw(Canvas c) {
            super.onDraw(c);
            float cx = getWidth() / 2f;
            float cy = getHeight() / 2f;
            float r = Math.min(getWidth(), getHeight()) * 0.40f;
            c.drawCircle(cx, cy, r + dp(7), outer);
            if (photo) {
                inner.setColor(0xFF050505);
                inner.setStyle(Paint.Style.FILL);
                c.drawCircle(cx, cy, r, inner);
                c.drawCircle(cx, cy, r - dp(4), ring);
            } else if (recording) {
                inner.setColor(0xFFFF3B30);
                inner.setStyle(Paint.Style.FILL);
                c.drawRoundRect(cx - dp(22), cy - dp(22), cx + dp(22), cy + dp(22),
                        dp(8), dp(8), inner);
            } else {
                inner.setColor(0xFFFF3B30);
                inner.setStyle(Paint.Style.FILL);
                c.drawCircle(cx, cy, r - dp(3), inner);
            }
        }
    }

    private final class ZoomStripView extends View {
        private final Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint selected = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final float[] presets = {0.6f, 1f, 2f, 3f, 10f};
        private float zoom = 1f;
        private float downX;
        private boolean dragging = false;

        ZoomStripView() {
            super(X300UltraUiActivity.this);
            setClickable(true);
            setFocusable(true);
            bg.setColor(0xB3171717);
            selected.setColor(0xDD2A2110);
            text.setTextAlign(Paint.Align.CENTER);
            text.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            text.setTextSize(dp(12));
        }

        void setZoom(float z) { zoom = clamp(z, MIN_ZOOM, MAX_ZOOM); invalidate(); }

        @Override protected void onDraw(Canvas c) {
            super.onDraw(c);
            float h = getHeight();
            c.drawRoundRect(0, dp(5), getWidth(), h - dp(5), dp(24), dp(24), bg);
            float usable = getWidth() - dp(62);
            float step = usable / presets.length;
            for (int i = 0; i < presets.length; i++) {
                float x = step * (i + 0.5f);
                boolean near = Math.abs(zoom - presets[i]) < (presets[i] < 3f ? 0.08f : 0.25f);
                if (near) c.drawCircle(x, h / 2f, dp(20), selected);
                text.setColor(near ? ACCENT : Color.WHITE);
                c.drawText(formatZoomShort(presets[i]), x, h / 2f + dp(5), text);
            }
            float dotsX = getWidth() - dp(31);
            Paint dots = new Paint(Paint.ANTI_ALIAS_FLAG);
            dots.setColor(fineZoomOpen ? ACCENT : Color.WHITE);
            for (int row = -1; row <= 1; row++) {
                for (int col = -1; col <= 1; col++) {
                    c.drawCircle(dotsX + col * dp(5), h / 2f + row * dp(5), dp(1.25f), dots);
                }
            }
        }

        @Override public boolean onTouchEvent(MotionEvent e) {
            float dotsStart = getWidth() - dp(62);
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX = e.getX();
                    dragging = false;
                    return true;
                case MotionEvent.ACTION_MOVE:
                    if (Math.abs(e.getX() - downX) > dp(7)) dragging = true;
                    if (dragging && e.getX() < dotsStart) {
                        float p = clamp(e.getX() / Math.max(1f, dotsStart), 0f, 1f);
                        float a = (float) Math.log(MIN_ZOOM);
                        float b = (float) Math.log(MAX_ZOOM);
                        setUiZoom((float) Math.exp(a + p * (b - a)));
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                    if (!dragging) {
                        if (e.getX() >= dotsStart) {
                            toggleFineZoom();
                        } else {
                            float step = dotsStart / presets.length;
                            int index = Math.max(0, Math.min(presets.length - 1, (int) (e.getX() / step)));
                            setUiZoom(presets[index]);
                        }
                    }
                    performClick();
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    return true;
            }
            return true;
        }

        @Override public boolean performClick() { super.performClick(); return true; }
    }

    private final class FineZoomView extends View {
        private final Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint accent = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint knob = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
        private float zoom = 1f;
        private int activeHold = 0;

        FineZoomView() {
            super(X300UltraUiActivity.this);
            setClickable(true);
            bg.setColor(0xE6121212);
            line.setColor(0xFF6C6C6C);
            line.setStrokeWidth(dp(2));
            accent.setColor(ACCENT);
            accent.setStrokeWidth(dp(3));
            knob.setColor(Color.WHITE);
            text.setColor(Color.WHITE);
            text.setTextAlign(Paint.Align.CENTER);
            text.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            text.setTextSize(dp(12));
        }

        void setZoom(float z) { zoom = clamp(z, MIN_ZOOM, MAX_ZOOM); invalidate(); }

        @Override protected void onDraw(Canvas c) {
            super.onDraw(c);
            c.drawRoundRect(0, 0, getWidth(), getHeight(), dp(18), dp(18), bg);
            text.setTextSize(dp(22));
            c.drawText("−", dp(28), getHeight() / 2f + dp(7), text);
            c.drawText("+", getWidth() - dp(28), getHeight() / 2f + dp(7), text);
            text.setTextSize(dp(11));

            float left = dp(62);
            float right = getWidth() - dp(62);
            float y = getHeight() / 2f;
            float p = logPosition(zoom);
            float x = left + p * (right - left);
            c.drawLine(left, y, right, y, line);
            c.drawLine(left, y, x, y, accent);
            c.drawCircle(x, y, dp(12), knob);
            text.setColor(Color.BLACK);
            c.drawText(formatZoomShort(zoom), x, y + dp(4), text);
            text.setColor(Color.WHITE);
        }

        @Override public boolean onTouchEvent(MotionEvent e) {
            float leftButtonEnd = dp(54);
            float rightButtonStart = getWidth() - dp(54);
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    if (e.getX() <= leftButtonEnd) {
                        activeHold = -1;
                        startZoomHold(-1);
                    } else if (e.getX() >= rightButtonStart) {
                        activeHold = 1;
                        startZoomHold(1);
                    } else {
                        activeHold = 0;
                        updateFromX(e.getX());
                    }
                    return true;
                case MotionEvent.ACTION_MOVE:
                    if (activeHold == 0) updateFromX(e.getX());
                    return true;
                case MotionEvent.ACTION_UP:
                    if (activeHold != 0) finishZoomHold(activeHold);
                    activeHold = 0;
                    performClick();
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    stopZoomHold();
                    activeHold = 0;
                    return true;
            }
            return true;
        }

        private void updateFromX(float x) {
            float left = dp(62);
            float right = getWidth() - dp(62);
            float p = clamp((x - left) / Math.max(1f, right - left), 0f, 1f);
            float a = (float) Math.log(MIN_ZOOM);
            float b = (float) Math.log(MAX_ZOOM);
            setUiZoom((float) Math.exp(a + p * (b - a)));
        }

        private float logPosition(float z) {
            float a = (float) Math.log(MIN_ZOOM);
            float b = (float) Math.log(MAX_ZOOM);
            return ((float) Math.log(clamp(z, MIN_ZOOM, MAX_ZOOM)) - a) / (b - a);
        }

        @Override public boolean performClick() { super.performClick(); return true; }
    }

    private String formatZoomShort(float z) {
        if (Math.abs(z - Math.round(z)) < 0.01f) return String.format(Locale.US, "%.0f", z);
        return String.format(Locale.US, "%.1f", z);
    }

    /** Small original line-icon renderer so no vendor icon assets are copied. */
    private final class IconButton extends View {
        static final int FLASH = 1;
        static final int GRID = 2;
        static final int FOCUS = 3;
        static final int SETTINGS = 4;
        static final int MORE = 5;
        static final int CLOSE = 6;

        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private final int type;
        private boolean active = false;

        IconButton(int type) {
            super(X300UltraUiActivity.this);
            this.type = type;
            setClickable(true);
            setFocusable(true);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(dp(2));
            p.setStrokeCap(Paint.Cap.ROUND);
            addPressFeedback(this);
        }

        void setActive(boolean value) {
            if (active != value) { active = value; invalidate(); }
        }

        @Override protected void onDraw(Canvas c) {
            super.onDraw(c);
            p.setColor(active ? ACCENT : Color.WHITE);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(dp(2));
            float cx = getWidth() / 2f;
            float cy = getHeight() / 2f;

            if (type == FLASH) {
                path.reset();
                path.moveTo(cx + dp(3), cy - dp(15));
                path.lineTo(cx - dp(7), cy + dp(1));
                path.lineTo(cx + dp(1), cy + dp(1));
                path.lineTo(cx - dp(3), cy + dp(15));
                path.lineTo(cx + dp(9), cy - dp(3));
                path.lineTo(cx + dp(2), cy - dp(3));
                path.close();
                c.drawPath(path, p);
            } else if (type == GRID) {
                for (int i = -1; i <= 1; i += 2) {
                    c.drawLine(cx + i * dp(6), cy - dp(14), cx + i * dp(6), cy + dp(14), p);
                    c.drawLine(cx - dp(14), cy + i * dp(6), cx + dp(14), cy + i * dp(6), p);
                }
            } else if (type == FOCUS) {
                float r = dp(11);
                c.drawLine(cx - r, cy - r, cx - dp(3), cy - r, p);
                c.drawLine(cx - r, cy - r, cx - r, cy - dp(3), p);
                c.drawLine(cx + r, cy - r, cx + dp(3), cy - r, p);
                c.drawLine(cx + r, cy - r, cx + r, cy - dp(3), p);
                c.drawLine(cx - r, cy + r, cx - dp(3), cy + r, p);
                c.drawLine(cx - r, cy + r, cx - r, cy + dp(3), p);
                c.drawLine(cx + r, cy + r, cx + dp(3), cy + r, p);
                c.drawLine(cx + r, cy + r, cx + r, cy + dp(3), p);
                c.drawCircle(cx, cy, dp(3), p);
            } else if (type == SETTINGS) {
                c.drawCircle(cx, cy, dp(10), p);
                c.drawCircle(cx, cy, dp(3), p);
                for (int i = 0; i < 8; i++) {
                    double a = Math.PI * 2 * i / 8.0;
                    float x1 = cx + (float) Math.cos(a) * dp(12);
                    float y1 = cy + (float) Math.sin(a) * dp(12);
                    float x2 = cx + (float) Math.cos(a) * dp(15);
                    float y2 = cy + (float) Math.sin(a) * dp(15);
                    c.drawLine(x1, y1, x2, y2, p);
                }
            } else if (type == MORE) {
                p.setStyle(Paint.Style.FILL);
                for (int row = -1; row <= 1; row++) {
                    for (int col = -1; col <= 1; col++) {
                        c.drawCircle(cx + col * dp(6), cy + row * dp(6), dp(1.7f), p);
                    }
                }
            } else if (type == CLOSE) {
                c.drawLine(cx - dp(10), cy - dp(10), cx + dp(10), cy + dp(10), p);
                c.drawLine(cx + dp(10), cy - dp(10), cx - dp(10), cy + dp(10), p);
            }
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
        Object o = objectField(name);
        return o instanceof Boolean && (Boolean) o;
    }

    private int intField(String name, int fallback) {
        Object o = objectField(name);
        return o instanceof Integer ? (Integer) o : fallback;
    }

    private float floatField(String name, float fallback) {
        Object o = objectField(name);
        return o instanceof Float ? (Float) o : fallback;
    }

    private String stringField(String name, String fallback) {
        Object o = objectField(name);
        return o instanceof String ? (String) o : fallback;
    }

    private Object objectField(String name) {
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

    private void setBooleanField(String name, boolean value) { setPrimitiveOrObject(name, value); }
    private void setIntField(String name, int value) { setPrimitiveOrObject(name, value); }
    private void setFloatField(String name, float value) { setPrimitiveOrObject(name, value); }
    private void setObjectField(String name, Object value) { setPrimitiveOrObject(name, value); }

    private void setPrimitiveOrObject(String name, Object value) {
        Class<?> c = getClass();
        while (c != null) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                if (value instanceof Boolean) f.setBoolean(this, (Boolean) value);
                else if (value instanceof Integer) f.setInt(this, (Integer) value);
                else if (value instanceof Float) f.setFloat(this, (Float) value);
                else f.set(this, value);
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