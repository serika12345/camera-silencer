package dev.serika.camerasilencer;

import android.Manifest;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ResolveInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;

public final class MainActivity extends Activity {
    static final String EXTRA_REARM_AFTER_BOOT = "rearm_after_boot";

    private TextView statusView;
    private TextView detailView;
    private Button manualGuardToggleButton;
    private RadioGroup modeGroup;
    private int followRingerModeId;
    private int alwaysModeId;
    private int manualModeId;
    private boolean running;
    private boolean silencing;
    private boolean cameraInUse;
    private boolean ringerSilent;
    private boolean rendering;
    private String mode = GuardSettings.MODE_MANUAL;

    private final BroadcastReceiver stateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            running = intent.getBooleanExtra(CameraGuardService.EXTRA_RUNNING, false);
            silencing = intent.getBooleanExtra(CameraGuardService.EXTRA_SILENCING, false);
            cameraInUse = intent.getBooleanExtra(CameraGuardService.EXTRA_CAMERA_IN_USE, false);
            ringerSilent = intent.getBooleanExtra(CameraGuardService.EXTRA_RINGER_SILENT, false);
            String nextMode = intent.getStringExtra(CameraGuardService.EXTRA_MODE);
            mode = nextMode == null ? GuardSettings.getMode(MainActivity.this) : nextMode;
            renderState();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(createContentView());
        running = CameraGuardService.savedRunning(this);
        silencing = CameraGuardService.savedSilencing(this);
        mode = GuardSettings.getMode(this);
        ensureNotificationPermission();
        handleIntent(getIntent());
        rearmAutoModeFromUserOpen();
        renderState();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent);
        rearmAutoModeFromUserOpen();
        renderState();
    }

    @Override
    protected void onStart() {
        super.onStart();
        IntentFilter filter = new IntentFilter(CameraGuardService.ACTION_STATE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(stateReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(stateReceiver, filter);
        }
    }

    @Override
    protected void onStop() {
        unregisterReceiver(stateReceiver);
        super.onStop();
    }

    private View createContentView() {
        int padding = dp(24);
        int compactGap = 8;

        ScrollView scroller = new ScrollView(this);
        scroller.setClipToPadding(false);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setPadding(padding, padding, padding, padding);
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            view.setPadding(
                    padding,
                    padding + insets.getSystemWindowInsetTop(),
                    padding,
                    padding + insets.getSystemWindowInsetBottom()
            );
            return insets;
        });

        TextView title = new TextView(this);
        title.setText("Camera Silencer");
        title.setTextSize(24);
        title.setGravity(Gravity.CENTER);
        root.addView(title, matchWrap());

        statusView = new TextView(this);
        statusView.setTextSize(18);
        statusView.setGravity(Gravity.CENTER);
        root.addView(statusView, topMargin(matchWrap(), 24));

        detailView = new TextView(this);
        detailView.setTextSize(14);
        detailView.setGravity(Gravity.START);
        detailView.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
        detailView.setLineSpacing(0, 1.15f);
        root.addView(detailView, topMargin(matchWrap(), 12));

        TextView modeTitle = new TextView(this);
        modeTitle.setText("Guard mode");
        modeTitle.setTextSize(16);
        root.addView(modeTitle, topMargin(matchWrap(), 24));

        modeGroup = new RadioGroup(this);
        modeGroup.setOrientation(RadioGroup.VERTICAL);

        RadioButton followRinger = createModeButton(
                "Follow silent/vibrate mode",
                "Tap the boot notification or open this app after reboot"
        );
        followRingerModeId = followRinger.getId();
        modeGroup.addView(followRinger);

        RadioButton always = createModeButton(
                "Always silence camera",
                "Tap the boot notification or open this app after reboot"
        );
        alwaysModeId = always.getId();
        modeGroup.addView(always);

        RadioButton manual = createModeButton(
                "Manual control",
                "Use the guard toggle below; no boot reminder"
        );
        manualModeId = manual.getId();
        modeGroup.addView(manual);

        modeGroup.setOnCheckedChangeListener((group, checkedId) -> handleModeSelection(checkedId));
        root.addView(modeGroup, topMargin(matchWrap(), compactGap));

        manualGuardToggleButton = new Button(this);
        manualGuardToggleButton.setOnClickListener(v -> toggleManualGuard());
        root.addView(manualGuardToggleButton, topMargin(matchWrap(), 24));

        Button restore = new Button(this);
        restore.setText("Restore audio now");
        restore.setOnClickListener(v -> {
            CameraGuardService.stop(this);
            AudioSilencer.restorePersistedSnapshot(this);
            mode = GuardSettings.MODE_MANUAL;
            running = false;
            silencing = false;
            renderState();
        });
        root.addView(restore, topMargin(matchWrap(), compactGap));

        TextView recovery = new TextView(this);
        recovery.setText("If the camera still makes sound, force stop the camera app once. Then start this guard before opening the camera again.");
        recovery.setTextSize(13);
        recovery.setGravity(Gravity.CENTER);
        recovery.setLineSpacing(0, 1.15f);
        root.addView(recovery, topMargin(matchWrap(), 18));

        Button cameraAppInfo = new Button(this);
        cameraAppInfo.setText("Open camera app info");
        cameraAppInfo.setOnClickListener(v -> openCameraAppInfo());
        root.addView(cameraAppInfo, topMargin(matchWrap(), compactGap));

        Button notificationSettings = new Button(this);
        notificationSettings.setText("Notification settings");
        notificationSettings.setOnClickListener(v -> openNotificationSettings());
        root.addView(notificationSettings, topMargin(matchWrap(), compactGap));

        scroller.addView(root);
        return scroller;
    }

    private void renderState() {
        if (statusView == null || detailView == null) {
            return;
        }

        renderModeSelection();
        renderManualGuardToggle();

        if (silencing) {
            statusView.setText("Silencing while camera is active");
        } else if (running) {
            statusView.setText("Watching camera use");
        } else {
            statusView.setText("Stopped");
        }

        String modeText = "Mode: " + GuardSettings.labelFor(mode);
        String bootText = GuardSettings.MODE_MANUAL.equals(mode)
                ? "Boot re-arm prompt: off"
                : "Boot re-arm prompt: on";
        String modeDetail;
        if (GuardSettings.MODE_FOLLOW_RINGER.equals(mode)) {
            modeDetail = "Camera audio is reduced only while the phone ringer is silent or vibrate.";
        } else if (GuardSettings.MODE_ALWAYS.equals(mode)) {
            modeDetail = "Camera audio is reduced whenever a camera is active.";
        } else {
            modeDetail = "Manual mode uses the guard toggle and does not start after reboot.";
        }

        detailView.setText(
                modeText + "\n"
                        + bootText + "\n"
                        + modeDetail + "\n"
                        + "Camera: " + (cameraInUse ? "in use" : "idle")
                        + " / Ringer: " + (ringerSilent ? "silent or vibrate" : "audible") + "\n"
                        + "No camera, usage access, overlay, accessibility, or internet permission."
        );
    }

    private void ensureNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return;
        }
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 10);
        }
    }

    private void openNotificationSettings() {
        Intent intent = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName());
        startActivity(intent);
    }

    private void toggleManualGuard() {
        if (running || silencing) {
            CameraGuardService.stop(this);
            mode = GuardSettings.MODE_MANUAL;
            running = false;
            silencing = false;
        } else {
            CameraGuardService.start(this);
            mode = GuardSettings.MODE_MANUAL;
            running = true;
        }
        renderState();
    }

    private void openCameraAppInfo() {
        String packageName = resolveCameraPackage();
        if (packageName == null) {
            startActivity(new Intent(Settings.ACTION_MANAGE_APPLICATIONS_SETTINGS));
            return;
        }

        Intent intent = new Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:" + packageName)
        );
        try {
            startActivity(intent);
        } catch (RuntimeException e) {
            startActivity(new Intent(Settings.ACTION_MANAGE_APPLICATIONS_SETTINGS));
        }
    }

    private String resolveCameraPackage() {
        String imageCapturePackage = resolveCameraPackage(
                new Intent(MediaStore.ACTION_IMAGE_CAPTURE)
        );
        if (imageCapturePackage != null) {
            return imageCapturePackage;
        }

        String stillCameraPackage = resolveCameraPackage(
                new Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)
        );
        if (stillCameraPackage != null) {
            return stillCameraPackage;
        }

        String googleCamera = "com.google.android.GoogleCamera";
        try {
            getPackageManager().getPackageInfo(googleCamera, 0);
            return googleCamera;
        } catch (PackageManager.NameNotFoundException e) {
            return null;
        }
    }

    private String resolveCameraPackage(Intent intent) {
        ResolveInfo info = getPackageManager().resolveActivity(
                intent,
                PackageManager.MATCH_DEFAULT_ONLY
        );
        if (info != null
                && info.activityInfo != null
                && info.activityInfo.packageName != null
                && !"android".equals(info.activityInfo.packageName)
                && !getPackageName().equals(info.activityInfo.packageName)) {
            return info.activityInfo.packageName;
        }
        return null;
    }

    private void handleIntent(Intent intent) {
        if (intent == null) {
            return;
        }

        if (intent.getBooleanExtra(EXTRA_REARM_AFTER_BOOT, false)) {
            rearmAutoModeFromUserOpen();
            return;
        }

        if (intent.getBooleanExtra("start_guard", false)) {
            CameraGuardService.start(this);
            mode = GuardSettings.MODE_MANUAL;
            running = true;
        }
    }

    private void rearmAutoModeFromUserOpen() {
        String currentMode = GuardSettings.getMode(this);
        if (GuardSettings.MODE_MANUAL.equals(currentMode)) {
            return;
        }

        mode = currentMode;
        GuardSettings.setManualRunning(this, false);
        CameraGuardService.startForCurrentMode(this);
        running = true;
    }

    private void handleModeSelection(int checkedId) {
        if (rendering) {
            return;
        }

        String selectedMode = modeForId(checkedId);
        mode = selectedMode;
        GuardSettings.setMode(this, selectedMode);

        if (GuardSettings.MODE_MANUAL.equals(selectedMode)) {
            GuardSettings.setManualRunning(this, false);
            if (running || silencing) {
                CameraGuardService.stop(this);
            }
            running = false;
            silencing = false;
        } else {
            GuardSettings.setManualRunning(this, false);
            CameraGuardService.startForCurrentMode(this);
            running = true;
        }
        renderState();
    }

    private RadioButton createModeButton(String title, String description) {
        RadioButton button = new RadioButton(this);
        button.setId(View.generateViewId());
        button.setText(title + "\n" + description);
        button.setTextSize(14);
        return button;
    }

    private void renderModeSelection() {
        if (modeGroup == null) {
            return;
        }

        rendering = true;
        modeGroup.check(idForMode(mode));
        rendering = false;
    }

    private void renderManualGuardToggle() {
        if (manualGuardToggleButton == null) {
            return;
        }

        if (!GuardSettings.MODE_MANUAL.equals(mode)) {
            manualGuardToggleButton.setVisibility(View.GONE);
            return;
        }

        manualGuardToggleButton.setVisibility(View.VISIBLE);
        manualGuardToggleButton.setText((running || silencing)
                ? "Stop manual guard"
                : "Start manual guard");
    }

    private int idForMode(String mode) {
        if (GuardSettings.MODE_FOLLOW_RINGER.equals(mode)) {
            return followRingerModeId;
        }
        if (GuardSettings.MODE_ALWAYS.equals(mode)) {
            return alwaysModeId;
        }
        return manualModeId;
    }

    private String modeForId(int checkedId) {
        if (checkedId == followRingerModeId) {
            return GuardSettings.MODE_FOLLOW_RINGER;
        }
        if (checkedId == alwaysModeId) {
            return GuardSettings.MODE_ALWAYS;
        }
        return GuardSettings.MODE_MANUAL;
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
    }

    private LinearLayout.LayoutParams topMargin(LinearLayout.LayoutParams params, int dp) {
        params.topMargin = dp(dp);
        return params;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
