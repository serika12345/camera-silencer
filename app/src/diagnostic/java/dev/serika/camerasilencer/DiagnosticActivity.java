package dev.serika.camerasilencer;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.hardware.camera2.CameraManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.provider.Settings;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class DiagnosticActivity extends Activity {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Set<String> cameraIds = new HashSet<>();
    private final Set<String> observedIds = new HashSet<>();
    private final Set<String> busyIds = new HashSet<>();
    private final List<String> packages = new ArrayList<>();
    private DiagnosticSession session;
    private CameraManager cameras;
    private boolean callbackRegistered;
    private Exception cameraCheckFailure;
    private CheckBox confirmation;
    private TextView status;
    private TextView preview;
    private Spinner cameraChoice;
    private Spinner modeChoice;
    private Button start;
    private Button stop;
    private Button copy;
    private Button share;
    private Button issue;
    private final List<Button> results = new ArrayList<>();
    private String lastPreview = "";
    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            render();
            handler.postDelayed(this, 500);
        }
    };
    private final CameraManager.AvailabilityCallback availability = new CameraManager.AvailabilityCallback() {
        @Override public void onCameraAvailable(String id) { observedIds.add(id); busyIds.remove(id); render(); }
        @Override public void onCameraUnavailable(String id) { observedIds.add(id); busyIds.add(id); render(); }
    };

    @Override public void onCreate(Bundle savedState) {
        super.onCreate(savedState);
        session = AppDependencies.session(this);
        if (!session.recording() && !session.stopping()) AudioSilencer.restorePersistedSnapshot(this);
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setFocusableInTouchMode(true);
        int pad = dp(20);
        root.setPadding(pad, pad, pad, pad);
        scroll.setClipToPadding(true);
        scroll.setOnApplyWindowInsetsListener((v, insets) -> {
            v.setPadding(0, insets.getSystemWindowInsetTop(), 0, insets.getSystemWindowInsetBottom());
            return insets;
        });
        scroll.addView(root);
        text(root, getString(R.string.diagnostic_app_name), 24);
        text(root, BuildConfig.VERSION_NAME + " / " + BuildConfig.REVISION, 12);
        text(root, getString(R.string.diag_instructions), 15);
        text(root, getString(R.string.diag_privacy), 13);
        status = text(root, "", 18);
        confirmation = new CheckBox(this);
        confirmation.setText(R.string.diag_confirm);
        confirmation.setOnCheckedChangeListener((button, checked) -> render());
        root.addView(confirmation);
        text(root, getString(R.string.diag_camera), 16);
        cameraChoice = new Spinner(this);
        loadCameras(cameraChoice);
        root.addView(cameraChoice);
        text(root, getString(R.string.guard_mode), 16);
        modeChoice = new Spinner(this);
        modeChoice.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item,
                new String[]{getString(R.string.mode_manual), getString(R.string.mode_always), getString(R.string.mode_follow_ringer)}));
        root.addView(modeChoice);
        button(root, R.string.usage_access_settings, () -> startActivity(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)));
        start = button(root, R.string.diag_start, () -> {
            if (session.hasReport()) {
                new AlertDialog.Builder(this).setMessage(R.string.diag_replace)
                        .setNegativeButton(android.R.string.cancel, null)
                        .setPositiveButton(android.R.string.ok, (d, w) -> begin()).show();
            } else begin();
        });
        stop = button(root, R.string.diag_stop, () -> { session.stop("user_stopped"); render(); });
        text(root, getString(R.string.diag_result), 16);
        resultButton(root, R.string.diag_heard, "heard");
        resultButton(root, R.string.diag_not_heard, "not_heard");
        resultButton(root, R.string.diag_unsure, "unsure");
        copy = button(root, R.string.diag_copy, this::copyReport);
        share = button(root, R.string.diag_share, () -> {
            Intent send = new Intent(Intent.ACTION_SEND).setType("text/plain")
                    .putExtra(Intent.EXTRA_TEXT, session.report());
            startActivity(Intent.createChooser(send, getString(R.string.diag_share)));
        });
        issue = button(root, R.string.diag_issue_open, () -> {
            copyReport();
            Intent intent = new Intent(Intent.ACTION_VIEW,
                    Uri.parse("https://github.com/serika12345/camera-silencer/issues/new?template=compatibility.yml"));
            try { startActivity(intent); }
            catch (android.content.ActivityNotFoundException e) { Toast.makeText(this, R.string.diag_no_browser, Toast.LENGTH_LONG).show(); }
        });
        button(root, R.string.diag_delete, () -> {
            if (!session.recording() && !session.stopping()) {
                session.clear();
                render();
            }
        });
        preview = text(root, "", 12);
        preview.setTextIsSelectable(true);
        setContentView(scroll);
        root.requestFocus();
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 10);
    }

    private void loadCameras(Spinner spinner) {
        List<String> labels = new ArrayList<>();
        packages.add("");
        labels.add(getString(R.string.foreground_unknown));
        Set<String> seen = new HashSet<>();
        String[] actions = {MediaStore.ACTION_IMAGE_CAPTURE, MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA,
                MediaStore.ACTION_VIDEO_CAPTURE, MediaStore.INTENT_ACTION_VIDEO_CAMERA};
        for (String action : actions) {
            for (ResolveInfo info : getPackageManager().queryIntentActivities(new Intent(action), PackageManager.MATCH_ALL)) {
                if (info.activityInfo == null) continue;
                String name = info.activityInfo.packageName;
                if (seen.add(name)) {
                    packages.add(name);
                    labels.add(info.loadLabel(getPackageManager()) + " (" + name + ")");
                }
            }
        }
        spinner.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, labels));
        if (packages.size() > 1) spinner.setSelection(1);
    }

    private void begin() {
        if (!canStart()) return;
        String[] modes = {GuardSettings.MODE_MANUAL, GuardSettings.MODE_ALWAYS, GuardSettings.MODE_FOLLOW_RINGER};
        String mode = modes[modeChoice.getSelectedItemPosition()];
        session.begin(mode, packages.get(cameraChoice.getSelectedItemPosition()));
        if (cameraCheckFailure != null) session.failure("camera_preflight", cameraCheckFailure);
        GuardSettings.setMode(this, mode);
        GuardSettings.setManualRunning(this, true);
        try { CameraGuardService.startForCurrentMode(this); }
        catch (RuntimeException e) { session.failure("service_start", e); session.stop("start_failed"); }
        render();
    }

    private boolean canStart() {
        return confirmation != null && confirmation.isChecked() && !session.recording() && !session.stopping()
                && busyIds.isEmpty() && (cameraCheckFailure != null
                || (callbackRegistered && !cameraIds.isEmpty() && observedIds.containsAll(cameraIds)));
    }

    @Override public void onStart() {
        super.onStart();
        session.checkDeadline();
        cameraCheckFailure = null;
        cameras = getSystemService(CameraManager.class);
        observedIds.clear();
        busyIds.clear();
        cameraIds.clear();
        try {
            for (String id : cameras.getCameraIdList()) cameraIds.add(id);
            cameras.registerAvailabilityCallback(availability, handler);
            callbackRegistered = true;
        } catch (Exception e) { callbackRegistered = false; cameraCheckFailure = e; }
        handler.post(refresh);
    }

    @Override public void onStop() {
        handler.removeCallbacks(refresh);
        if (callbackRegistered) cameras.unregisterAvailabilityCallback(availability);
        callbackRegistered = false;
        super.onStop();
    }

    private void render() {
        if (start == null || preview == null) return;
        String value = session.status();
        if (!session.recording() && !session.stopping()) {
            if (cameraCheckFailure != null) value += "\n" + getString(R.string.diag_camera_unavailable);
            else if (!busyIds.isEmpty()) value += "\n" + getString(R.string.diag_close_camera);
            else if (!callbackRegistered || cameraIds.isEmpty() || !observedIds.containsAll(cameraIds)) {
                value += "\n" + getString(R.string.diag_camera_check);
            }
        }
        if (session.hasReport()) {
            String result = session.result();
            value += "\n" + getString(R.string.diag_result) + " " + getString(
                    "heard".equals(result) ? R.string.diag_heard : "not_heard".equals(result)
                    ? R.string.diag_not_heard : "unsure".equals(result) ? R.string.diag_unsure : R.string.diag_not_reported);
        }
        status.setText(value);
        start.setEnabled(canStart());
        stop.setEnabled(session.recording());
        modeChoice.setEnabled(!session.recording() && !session.stopping());
        cameraChoice.setEnabled(!session.recording() && !session.stopping());
        for (Button b : results) b.setEnabled(session.hasReport() && !session.stopping());
        boolean export = session.hasReport() && !session.recording() && !session.stopping();
        copy.setEnabled(export);
        share.setEnabled(export);
        issue.setEnabled(export);
        String report = session.report();
        if (!report.equals(lastPreview)) { preview.setText(report); lastPreview = report; }
    }

    private void resultButton(LinearLayout root, int label, String value) {
        results.add(button(root, label, () -> {
            session.result(value);
            session.stop("result_entered");
            render();
        }));
    }

    private void copyReport() {
        getSystemService(ClipboardManager.class).setPrimaryClip(ClipData.newPlainText(
                getString(R.string.diagnostic_app_name), session.report()));
        Toast.makeText(this, R.string.diag_copied, Toast.LENGTH_SHORT).show();
    }

    private TextView text(LinearLayout root, String value, int size) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setPadding(0, dp(8), 0, dp(8));
        root.addView(view, new LinearLayout.LayoutParams(-1, -2));
        return view;
    }
    private Button button(LinearLayout root, int label, Runnable action) {
        Button button = new Button(this);
        button.setText(label);
        button.setAllCaps(false);
        button.setOnClickListener(v -> action.run());
        root.addView(button, new LinearLayout.LayoutParams(-1, -2));
        return button;
    }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
