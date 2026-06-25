package dev.serika.camerasilencer;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.AudioManager;
import android.os.Build;
import android.util.Log;

final class AudioSilencer {
    private static final String TAG = "AudioSilencer";
    private static final String PREFS = "audio_snapshot";
    private static final String KEY_HAS_SNAPSHOT = "has_snapshot";
    private static final String KEY_MODE = "mode";
    private static final int STREAM_SYSTEM_ENFORCED = 7;

    private final AudioManager audioManager;
    private final SharedPreferences prefs;
    private final OutputMixGuard outputMixGuard;
    private Snapshot snapshot;

    AudioSilencer(Context context) {
        Context appContext = context.getApplicationContext();
        audioManager = (AudioManager) appContext.getSystemService(Context.AUDIO_SERVICE);
        prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        outputMixGuard = new OutputMixGuard();
    }

    static void restorePersistedSnapshot(Context context) {
        Context appContext = context.getApplicationContext();
        AudioManager manager = (AudioManager) appContext.getSystemService(Context.AUDIO_SERVICE);
        SharedPreferences preferences = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (manager == null || !preferences.getBoolean(KEY_HAS_SNAPSHOT, false)) {
            return;
        }

        Snapshot.load(preferences).restore(manager);
        preferences.edit().clear().apply();
    }

    synchronized boolean isActive() {
        return snapshot != null;
    }

    synchronized void enable() {
        if (snapshot != null || audioManager == null) {
            return;
        }

        Snapshot next = Snapshot.capture(audioManager);
        try {
            next.persist(prefs);
            Log.w(TAG, "Enabling guard: system=" + next.system.volume + "/" + next.system.muted
                    + " enforced=" + next.enforced.volume + "/" + next.enforced.muted);
            applyMutedStream(AudioManager.STREAM_SYSTEM);
            applyMutedStream(STREAM_SYSTEM_ENFORCED);
            outputMixGuard.enable();
            snapshot = next;
            Log.w(TAG, "Audio guard enabled");
        } catch (RuntimeException e) {
            Log.w(TAG, "Failed to enable audio guard", e);
            outputMixGuard.disable();
            next.restore(audioManager);
            prefs.edit().clear().apply();
        }
    }

    synchronized void disable() {
        if (snapshot == null || audioManager == null) {
            return;
        }

        Snapshot old = snapshot;
        snapshot = null;
        outputMixGuard.disable();
        old.restore(audioManager);
        prefs.edit().clear().apply();
        Log.w(TAG, "Audio guard disabled");
    }

    synchronized void shutdown() {
        disable();
        outputMixGuard.release();
    }

    private void applyMutedStream(int stream) {
        try {
            audioManager.adjustStreamVolume(stream, AudioManager.ADJUST_MUTE, 0);
        } catch (RuntimeException e) {
            Log.d(TAG, "adjustStreamVolume mute failed for stream " + stream, e);
        }

        try {
            audioManager.setStreamVolume(stream, 0, 0);
        } catch (RuntimeException e) {
            Log.d(TAG, "setStreamVolume failed for stream " + stream, e);
        }
    }

    private static final class Snapshot {
        private final Integer mode;
        private final StreamState system;
        private final StreamState legacyMusic;
        private final StreamState enforced;

        private Snapshot(
                Integer mode,
                StreamState system,
                StreamState legacyMusic,
                StreamState enforced
        ) {
            this.mode = mode;
            this.system = system;
            this.legacyMusic = legacyMusic;
            this.enforced = enforced;
        }

        static Snapshot capture(AudioManager audioManager) {
            return new Snapshot(
                    null,
                    StreamState.capture(audioManager, AudioManager.STREAM_SYSTEM),
                    null,
                    StreamState.capture(audioManager, STREAM_SYSTEM_ENFORCED)
            );
        }

        static Snapshot load(SharedPreferences prefs) {
            StreamState legacyMusic = prefs.contains("music.volume") || prefs.contains("music.muted")
                    ? StreamState.load(prefs, "music", AudioManager.STREAM_MUSIC)
                    : null;
            return new Snapshot(
                    prefs.contains(KEY_MODE)
                            ? prefs.getInt(KEY_MODE, AudioManager.MODE_NORMAL)
                            : null,
                    StreamState.load(prefs, "system", AudioManager.STREAM_SYSTEM),
                    legacyMusic,
                    StreamState.load(prefs, "enforced", STREAM_SYSTEM_ENFORCED)
            );
        }

        void persist(SharedPreferences prefs) {
            prefs.edit().clear()
                    .putBoolean(KEY_HAS_SNAPSHOT, true)
                    .putInt("system.volume", system.volume)
                    .putBoolean("system.muted", system.muted)
                    .putInt("enforced.volume", enforced.volume)
                    .putBoolean("enforced.muted", enforced.muted)
                    .apply();
        }

        void restore(AudioManager audioManager) {
            system.restore(audioManager);
            if (legacyMusic != null) {
                legacyMusic.restore(audioManager);
            }
            enforced.restore(audioManager);

            if (mode != null) {
                try {
                    audioManager.setMode(mode);
                } catch (RuntimeException e) {
                    Log.w(TAG, "Failed to restore audio mode", e);
                }
            }
        }
    }

    private static final class StreamState {
        private final int stream;
        private final int volume;
        private final boolean muted;

        private StreamState(int stream, int volume, boolean muted) {
            this.stream = stream;
            this.volume = volume;
            this.muted = muted;
        }

        static StreamState capture(AudioManager audioManager, int stream) {
            boolean muted = false;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                try {
                    muted = audioManager.isStreamMute(stream);
                } catch (RuntimeException e) {
                    Log.d(TAG, "isStreamMute failed for stream " + stream, e);
                }
            }

            return new StreamState(stream, audioManager.getStreamVolume(stream), muted);
        }

        static StreamState load(SharedPreferences prefs, String prefix, int stream) {
            return new StreamState(
                    stream,
                    prefs.getInt(prefix + ".volume", 0),
                    prefs.getBoolean(prefix + ".muted", false)
            );
        }

        void restore(AudioManager audioManager) {
            try {
                audioManager.setStreamVolume(stream, volume, 0);
            } catch (RuntimeException e) {
                Log.w(TAG, "Failed to restore stream volume " + stream, e);
            }

            try {
                audioManager.adjustStreamVolume(
                        stream,
                        muted ? AudioManager.ADJUST_MUTE : AudioManager.ADJUST_UNMUTE,
                        0
                );
            } catch (RuntimeException e) {
                Log.d(TAG, "Failed to restore mute state " + stream, e);
            }
        }
    }
}
