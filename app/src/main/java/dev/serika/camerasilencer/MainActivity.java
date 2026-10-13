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
    private boolean usageAccessGranted;
    private boolean foregroundCameraApp;
    private String foregroundPackage;
    private boolean rendering;
    private String mode = GuardSettings.MODE_MANUAL;

    private final BroadcastReceiver stateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            running = intent.getBooleanExtra(CameraGuardService.EXTRA_RUNNING, false);
            silencing = intent.getBooleanExtra(CameraGuardService.EXTRA_SILENCING, false);
            cameraInUse = intent.getBooleanExtra(CameraGuardService.EXTRA_CAMERA_IN_USE, false);
            ringerSilent = intent.getBooleanExtra(CameraGuardService.EXTRA_RINGER_SILENT, false);
            usageAccessGranted = intent.getBooleanExtra(
                    CameraGuardService.EXTRA_USAGE_ACCESS_GRANTED,
                    CameraGuardService.hasUsageAccess(MainActivity.this)
            );
            foregroundCameraApp = intent.getBooleanExtra(
                    CameraGuardService.EXTRA_FOREGROUND_CAMERA_APP,
                    false
            );
            foregroundPackage = intent.getStringExtra(CameraGuardService.EXTRA_FOREGROUND_PACKAGE);
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
        usageAccessGranted = CameraGuardService.hasUsageAccess(this);
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
        usageAccessGranted = CameraGuardService.hasUsageAccess(this);
        renderState();
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
        title.setText(R.string.app_name);
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
        modeTitle.setText(R.string.guard_mode);
        modeTitle.setTextSize(16);
        root.addView(modeTitle, topMargin(matchWrap(), 24));

        modeGroup = new RadioGroup(this);
        modeGroup.setOrientation(RadioGroup.VERTICAL);

        RadioButton followRinger = createModeButton(
                R.string.mode_follow_ringer,
                R.string.mode_auto_hint
        );
        followRingerModeId = followRinger.getId();
        modeGroup.addView(followRinger);

        RadioButton always = createModeButton(
                R.string.mode_always,
                R.string.mode_auto_hint
        );
        alwaysModeId = always.getId();
        modeGroup.addView(always);

        RadioButton manual = createModeButton(
                R.string.mode_manual,
                R.string.mode_manual_hint
        );
        manualModeId = manual.getId();
        modeGroup.addView(manual);

        modeGroup.setOnCheckedChangeListener((group, checkedId) -> handleModeSelection(checkedId));
        root.addView(modeGroup, topMargin(matchWrap(), compactGap));

        manualGuardToggleButton = new Button(this);
        manualGuardToggleButton.setOnClickListener(v -> toggleManualGuard());
        root.addView(manualGuardToggleButton, topMargin(matchWrap(), 24));

        Button restore = new Button(this);
        restore.setText(R.string.restore_audio);
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
        recovery.setText(R.string.recovery_hint);
        recovery.setTextSize(13);
        recovery.setGravity(Gravity.CENTER);
        recovery.setLineSpacing(0, 1.15f);
        root.addView(recovery, topMargin(matchWrap(), 18));

        Button cameraAppInfo = new Button(this);
        cameraAppInfo.setText(R.string.open_camera_app_info);
        cameraAppInfo.setOnClickListener(v -> openCameraAppInfo());
        root.addView(cameraAppInfo, topMargin(matchWrap(), compactGap));

        Button notificationSettings = new Button(this);
        notificationSettings.setText(R.string.notification_settings);
        notificationSettings.setOnClickListener(v -> openNotificationSettings());
        root.addView(notificationSettings, topMargin(matchWrap(), compactGap));

        Button usageAccessSettings = new Button(this);
        usageAccessSettings.setText(R.string.usage_access_settings);
        usageAccessSettings.setOnClickListener(v -> openUsageAccessSettings());
        root.addView(usageAccessSettings, topMargin(matchWrap(), compactGap));

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
            statusView.setText(R.string.status_silencing);
        } else if (running) {
            statusView.setText(R.string.status_watching);
        } else {
            statusView.setText(R.string.status_stopped);
        }

        String modeText = getString(R.string.mode_status, GuardSettings.labelFor(this, mode));
        String bootText = GuardSettings.MODE_MANUAL.equals(mode)
                ? getString(R.string.boot_prompt_off)
                : getString(R.string.boot_prompt_on);
        String modeDetail;
        if (GuardSettings.MODE_FOLLOW_RINGER.equals(mode)) {
            modeDetail = getString(R.string.mode_follow_ringer_detail);
        } else if (GuardSettings.MODE_ALWAYS.equals(mode)) {
            modeDetail = getString(R.string.mode_always_detail);
        } else {
            modeDetail = getString(R.string.mode_manual_detail);
        }
        String foregroundText = foregroundPackage == null
                ? getString(R.string.foreground_unknown)
                : foregroundCameraApp
                        ? getString(R.string.foreground_camera, foregroundPackage)
                        : foregroundPackage;

        detailView.setText(getString(
                R.string.status_detail_format,
                modeText,
                bootText,
                modeDetail,
                getString(R.string.camera_ringer_status,
                        getString(cameraInUse ? R.string.camera_in_use : R.string.camera_idle),
                        getString(ringerSilent ? R.string.ringer_silent : R.string.ringer_audible)),
                getString(R.string.usage_access_status,
                        getString(usageAccessGranted
                                ? R.string.usage_access_granted : R.string.usage_access_fallback)),
                getString(R.string.foreground_status, foregroundText),
                getString(R.string.permissions_summary)
        ));
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

    private void openUsageAccessSettings() {
        startActivity(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS));
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

    private RadioButton createModeButton(int titleResId, int descriptionResId) {
        RadioButton button = new RadioButton(this);
        button.setId(View.generateViewId());
        button.setText(getString(R.string.mode_button_format,
                getString(titleResId), getString(descriptionResId)));
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
                ? R.string.stop_manual_guard
                : R.string.start_manual_guard);
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
