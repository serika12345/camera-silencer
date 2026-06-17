package dev.masato.pixelcamerasilencer;

import android.Manifest;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

public final class MainActivity extends Activity {
    private TextView statusView;
    private TextView detailView;
    private boolean running;
    private boolean silencing;

    private final BroadcastReceiver stateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            running = intent.getBooleanExtra(CameraGuardService.EXTRA_RUNNING, false);
            silencing = intent.getBooleanExtra(CameraGuardService.EXTRA_SILENCING, false);
            renderState();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(createContentView());
        running = CameraGuardService.savedRunning(this);
        silencing = CameraGuardService.savedSilencing(this);
        ensureNotificationPermission();
        handleIntent(getIntent());
        renderState();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent);
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

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setPadding(padding, padding, padding, padding);

        TextView title = new TextView(this);
        title.setText("Camera Silencer");
        title.setTextSize(26);
        title.setGravity(Gravity.CENTER);
        root.addView(title, matchWrap());

        statusView = new TextView(this);
        statusView.setTextSize(18);
        statusView.setGravity(Gravity.CENTER);
        root.addView(statusView, topMargin(matchWrap(), 28));

        detailView = new TextView(this);
        detailView.setTextSize(14);
        detailView.setGravity(Gravity.CENTER);
        detailView.setLineSpacing(0, 1.15f);
        root.addView(detailView, topMargin(matchWrap(), 12));

        Button start = new Button(this);
        start.setText("Start guard");
        start.setOnClickListener(v -> CameraGuardService.start(this));
        root.addView(start, topMargin(matchWrap(), 28));

        Button stop = new Button(this);
        stop.setText("Stop guard");
        stop.setOnClickListener(v -> CameraGuardService.stop(this));
        root.addView(stop, topMargin(matchWrap(), 8));

        Button restore = new Button(this);
        restore.setText("Restore audio now");
        restore.setOnClickListener(v -> {
            CameraGuardService.stop(this);
            AudioSilencer.restorePersistedSnapshot(this);
        });
        root.addView(restore, topMargin(matchWrap(), 8));

        Button notificationSettings = new Button(this);
        notificationSettings.setText("Notification settings");
        notificationSettings.setOnClickListener(v -> openNotificationSettings());
        root.addView(notificationSettings, topMargin(matchWrap(), 8));

        return root;
    }

    private void renderState() {
        if (statusView == null || detailView == null) {
            return;
        }

        if (silencing) {
            statusView.setText("Silencing while camera is active");
        } else if (running) {
            statusView.setText("Watching camera use");
        } else {
            statusView.setText("Stopped");
        }

        detailView.setText(
                "No camera, usage access, overlay, accessibility, or internet permission.\n"
                        + "When any camera becomes unavailable, system/music audio is temporarily reduced and then restored."
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

    private void handleIntent(Intent intent) {
        if (intent != null && intent.getBooleanExtra("start_guard", false)) {
            CameraGuardService.start(this);
        }
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
