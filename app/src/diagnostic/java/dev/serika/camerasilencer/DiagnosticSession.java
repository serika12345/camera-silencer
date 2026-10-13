package dev.serika.camerasilencer;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.media.audiofx.AudioEffect;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import org.json.JSONObject;

import java.util.HashMap;
import java.util.Map;

final class DiagnosticSession implements GuardSession, GuardObserver {
    static final long LIMIT_MS = 180_000;
    private final Context context;
    private final SharedPreferences preferences;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Map<String, AudioEffect> effects = new HashMap<>();
    private DiagnosticRecord record;
    private boolean recording;
    private boolean stopping;
    private long generation;
    private long startTime;
    private boolean firstWrite;
    private boolean cameraInUse;
    private boolean attempting;
    private Runnable updateListener = () -> {};
    private final Runnable timeout = () -> stop("time_limit");
    private final Runnable probe = new Runnable() {
        @Override public void run() {
            synchronized (DiagnosticSession.this) {
                if (!recording) return;
                for (Map.Entry<String, AudioEffect> entry : effects.entrySet()) {
                    observeEffect(entry.getKey(), entry.getValue());
                }
            }
            handler.postDelayed(this, 1000);
        }
    };

    DiagnosticSession(Context context) {
        this.context = context;
        preferences = context.getSharedPreferences("diagnostic_report", Context.MODE_PRIVATE);
        String saved = preferences.getString("last", null);
        if (saved != null) {
            try {
                record = new DiagnosticRecord(saved);
                if ("recording".equals(record.data.optString("phase"))
                        || "stopping".equals(record.data.optString("phase"))) {
                    DiagnosticRecord.put(record.data, "phase", "interrupted");
                    persist();
                }
            } catch (Exception e) { record = null; }
        }
    }

    synchronized void begin(String mode, String cameraPackage) {
        if (recording || stopping) throw new IllegalStateException("Session already running");
        generation++;
        startTime = SystemClock.elapsedRealtime();
        firstWrite = false;
        cameraInUse = false;
        attempting = false;
        JSONObject meta = new JSONObject();
        DiagnosticRecord.put(meta, "application_id", context.getPackageName());
        DiagnosticRecord.put(meta, "version", BuildConfig.VERSION_NAME);
        DiagnosticRecord.put(meta, "version_code", BuildConfig.VERSION_CODE);
        DiagnosticRecord.put(meta, "revision", BuildConfig.REVISION);
        DiagnosticRecord.put(meta, "manufacturer", Build.MANUFACTURER);
        DiagnosticRecord.put(meta, "model", Build.MODEL);
        DiagnosticRecord.put(meta, "android", Build.VERSION.RELEASE);
        DiagnosticRecord.put(meta, "sdk", Build.VERSION.SDK_INT);
        DiagnosticRecord.put(meta, "os_build", Build.DISPLAY);
        if (Build.VERSION.SDK_INT >= 31) {
            DiagnosticRecord.put(meta, "soc_manufacturer", Build.SOC_MANUFACTURER);
            DiagnosticRecord.put(meta, "soc_model", Build.SOC_MODEL);
        }
        DiagnosticRecord.put(meta, "mode", mode);
        DiagnosticRecord.put(meta, "usage_access", CameraGuardService.hasUsageAccess(context));
        DiagnosticRecord.put(meta, "selected_camera", packageDescription(cameraPackage));
        DiagnosticRecord.put(meta, "requested_audio", "session=0; priority=2147483647; EQ=min; Dynamics=input/threshold/postGain=-100dB,ratio=20,time-resolution; AudioTrack=48000Hz,stereo,PCM16,media,low-latency; no focus/mode change");
        DiagnosticRecord.put(meta, "actual_shutter_route", "not_observable");
        record = new DiagnosticRecord(meta);
        String[] components = {"equalizer", "dynamics", "track", "stream_1_after_mute", "stream_7_after_mute"};
        for (String component : components) record.add(0, component, "not_attempted", "");
        if (Build.VERSION.SDK_INT < 28) record.add(0, "dynamics", "unsupported", "requires_API_28");
        recording = true;
        state("session", "observed", "started; API state does not establish shutter silence");
        audioEnvironment("audio_before");
        handler.postDelayed(timeout, LIMIT_MS);
        handler.postDelayed(probe, 1000);
    }

    private String packageDescription(String packageName) {
        if (packageName == null || packageName.isEmpty()) return "unknown";
        try {
            PackageInfo info = context.getPackageManager().getPackageInfo(packageName, 0);
            return packageName + " version=" + info.versionName + " code="
                    + (Build.VERSION.SDK_INT >= 28 ? info.getLongVersionCode() : info.versionCode);
        } catch (Exception e) { return packageName + " version=unknown"; }
    }

    void checkDeadline() {
        boolean expired;
        synchronized (this) { expired = recording && SystemClock.elapsedRealtime() - startTime >= LIMIT_MS; }
        if (expired) stop("time_limit");
    }

    synchronized boolean recording() { return recording; }
    synchronized boolean stopping() { return stopping; }
    synchronized boolean hasReport() { return record != null; }
    synchronized String report() { return record == null ? "" : record.text(); }
    synchronized String result() { return record == null ? "not_reported" : record.data.optString("audible_result"); }
    synchronized boolean hasIssues() { return record != null && record.data.optBoolean("had_issues"); }
    synchronized String phase() { return record == null ? "ready" : record.data.optString("phase"); }

    synchronized void result(String result) {
        if (record == null) return;
        record.outcome(result);
        persist();
    }

    void stop(String reason) {
        synchronized (this) {
            if (!recording) return;
            event("session", "stop_requested: " + reason);
            recording = false;
            stopping = true;
            DiagnosticRecord.put(record.data, "phase", "stopping");
            persist();
        }
        handler.removeCallbacks(timeout);
        handler.removeCallbacks(probe);
        GuardSettings.setManualRunning(context, false);
        if (!context.stopService(new Intent(context, CameraGuardService.class))) serviceStopped(context);
    }

    synchronized void clear() {
        if (recording || stopping) return;
        record = null;
        preferences.edit().remove("last").apply();
    }

    private void persist() {
        if (record != null) preferences.edit().putString("last", record.data.toString()).apply();
    }

    synchronized void state(String component, String state, String detail) {
        if (record == null || (!recording && !stopping)) return;
        record.add(SystemClock.elapsedRealtime() - startTime, component, state, detail);
        persist();
        if ("error".equals(state) || "unavailable".equals(state)) {
            handler.removeCallbacks(updateListener);
            handler.post(updateListener);
        }
    }

    @Override public void event(String component, String detail) { state(component, "observed", detail); }
    @Override public void failure(String component, Throwable error) {
        // Exception messages can include device addresses or unrelated application details.
        state(component, "error", error.getClass().getSimpleName());
    }
    @Override public synchronized void setUpdateListener(Runnable listener) {
        handler.removeCallbacks(updateListener);
        updateListener = listener;
    }
    @Override public GuardObserver observer() { return this; }
    @Override public Class<?> activityClass() { return DiagnosticActivity.class; }
    @Override public synchronized boolean startService(Service service) { return recording; }
    @Override public int restartMode() { return Service.START_NOT_STICKY; }
    @Override public synchronized long token() { return generation; }

    @Override public synchronized void serviceStopped(Context ignored) {
        handler.removeCallbacks(timeout);
        handler.removeCallbacks(probe);
        effects.clear();
        if (recording || stopping) {
            audioEnvironment("audio_after");
            event("session", "service_stopped; restore operations completed, check observations");
            DiagnosticRecord.put(record.data, "phase", "finished");
            persist();
        }
        recording = false;
        stopping = false;
        attempting = false;
        GuardSettings.setManualRunning(context, false);
    }

    @Override public synchronized void effect(String name, AudioEffect effect, int result) {
        event(name + "_enable", "return=" + result + "; audioSession=0");
        if (result != AudioEffect.SUCCESS) state(name + "_enable", "error", "return=" + result);
        effects.put(name, effect);
        observeEffect(name, effect);
        long token = generation;
        try {
            effect.setControlStatusListener((changed, control) -> {
                synchronized (DiagnosticSession.this) {
                    if (token == generation && effects.get(name) == changed) observeEffect(name, changed);
                }
            });
            effect.setEnableStatusListener((changed, enabled) -> {
                synchronized (DiagnosticSession.this) {
                    if (token == generation && effects.get(name) == changed) observeEffect(name, changed);
                }
            });
        } catch (RuntimeException e) { failure(name + "_observation", e); }
    }

    private void observeEffect(String name, AudioEffect effect) {
        try {
            boolean enabled = effect.getEnabled();
            boolean control = effect.hasControl();
            state(name, enabled && control ? "enabled_and_controlled" : "unavailable",
                    "enabled=" + enabled + "; hasControl=" + control + "; shutter_effect=unverified");
        } catch (RuntimeException e) { failure(name + "_observation", e); }
    }

    @Override public synchronized void effectReleased(String name) {
        if (effects.remove(name) != null) state(name, "release_requested", "release_requested");
    }

    @Override public void stream(AudioManager audio, int stream, String phase) {
        try {
            boolean muted = audio.isStreamMute(stream);
            state("stream_" + stream + "_" + phase, "observed",
                    "muted=" + muted + "; volume=" + audio.getStreamVolume(stream)
                    + "; ringer=" + audio.getRingerMode());
            if ("after_mute".equals(phase) && !muted) {
                state("stream_" + stream + "_mute", "unavailable", "not_muted_after_request");
            }
        } catch (RuntimeException e) { failure("stream_" + stream + "_" + phase, e); }
    }

    @Override public synchronized void track(long token, AudioTrack track, int volumeResult) {
        if (token != generation) return;
        firstWrite = false;
        try {
            state("track", track.getState() == AudioTrack.STATE_INITIALIZED
                    && track.getPlayState() == AudioTrack.PLAYSTATE_PLAYING && volumeResult == 0
                    ? "playing" : "unavailable", "state=" + track.getState()
                    + "; playState=" + track.getPlayState() + "; volumeReturn=" + volumeResult
                    + "; sampleRate=" + track.getSampleRate() + "; session=" + track.getAudioSessionId()
                    + "; performanceMode=" + track.getPerformanceMode());
        } catch (RuntimeException e) { failure("track_observation", e); }
    }

    @Override public synchronized boolean write(long token, int result) {
        if (token != generation) return false;
        if (result <= 0) {
            state("track_write", "error", "return=" + result);
            return false;
        }
        if (!firstWrite) {
            firstWrite = true;
            state("track_write", "accepted", "first_write_samples=" + result);
        }
        return true;
    }
    @Override public synchronized void trackStopped(long token, boolean requested) {
        if (token == generation) state("track", requested ? "stopped" : "error",
                requested ? "stop_requested" : "unexpected_playback_end");
    }
    @Override public synchronized void trackFailure(long token, Throwable error) {
        if (token == generation) failure("track", error);
    }

    @Override public synchronized void decision(Context ignored, boolean camera, boolean foregroundCamera,
            boolean access, String foreground, boolean attempt, boolean previous) {
        if (!recording) return;
        cameraInUse = camera;
        attempting = attempt;
        event("decision", "cameraInUse=" + camera + "; usageAccess=" + access
                + "; foregroundIsCamera=" + foregroundCamera + "; previouslyAttempting=" + previous
                + "; shouldAttempt=" + attempt + "; mode=" + GuardSettings.getMode(context));
        // Only camera packages are recorded; general foreground history is not collected.
        if (foregroundCamera && foreground != null) event("detected_camera", packageDescription(foreground));
        if (attempt) audioEnvironment("audio_during");
    }

    private void audioEnvironment(String phase) {
        try {
            AudioManager audio = context.getSystemService(AudioManager.class);
            event(phase, "ringer=" + audio.getRingerMode() + "; audioMode=" + audio.getMode()
                    + "; musicActive=" + audio.isMusicActive() + "; volumeFixed=" + audio.isVolumeFixed()
                    + "; outputSampleRate=" + audio.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE));
        } catch (RuntimeException e) { failure(phase, e); }
    }

    synchronized String status() {
        if (stopping) return context.getString(R.string.diag_stopping);
        if (!recording) return context.getString("interrupted".equals(phase())
                ? R.string.diag_interrupted : hasReport() ? R.string.diag_finished : R.string.diag_ready);
        if (hasIssues()) return context.getString(R.string.diag_issue);
        return context.getString(attempting ? R.string.diag_attempting
                : cameraInUse ? R.string.diag_detected : R.string.diag_watching);
    }
    @Override public String notificationTitle(Context context, boolean camera, boolean attempt, String usual) {
        return status();
    }
    @Override public String notificationText(Context context, String usual) {
        return context.getString(R.string.diag_unverified);
    }
}
