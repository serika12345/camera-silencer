package dev.serika.camerasilencer;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.media.audiofx.DynamicsProcessing;
import android.media.audiofx.Equalizer;
import android.os.Build;
import android.util.Log;

final class OutputMixGuard {
    private static final String TAG = "OutputMixGuard";
    private static final int OUTPUT_MIX_SESSION = 0;
    private static final int SAMPLE_RATE = 48000;
    private static final int CHANNEL_MASK = AudioFormat.CHANNEL_OUT_STEREO;
    private static final int ENCODING = AudioFormat.ENCODING_PCM_16BIT;

    private final Context context;
    private final AudioManager audioManager;
    private final AudioManager.OnAudioFocusChangeListener focusListener = focusChange -> { };

    private Equalizer equalizer;
    private DynamicsProcessing dynamicsProcessing;
    private AudioFocusRequest focusRequest;
    private SilentTrackThread silentTrackThread;

    OutputMixGuard(Context context) {
        this.context = context.getApplicationContext();
        audioManager = (AudioManager) this.context.getSystemService(Context.AUDIO_SERVICE);
    }

    synchronized void enable() {
        requestFocus();
        enableEqualizer();
        enableDynamicsProcessing();
        startSilentTrack();
    }

    synchronized void disable() {
        stopSilentTrack();
        releaseDynamicsProcessing();
        releaseEqualizer();
        abandonFocus();
    }

    synchronized void release() {
        disable();
    }

    private void requestFocus() {
        if (audioManager == null) {
            return;
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                AudioAttributes attributes = new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build();
                focusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                        .setAudioAttributes(attributes)
                        .setOnAudioFocusChangeListener(focusListener)
                        .setAcceptsDelayedFocusGain(false)
                        .setWillPauseWhenDucked(false)
                        .build();
                int result = audioManager.requestAudioFocus(focusRequest);
                Log.w(TAG, "Audio focus request result=" + result);
            } else {
                int result = audioManager.requestAudioFocus(
                        focusListener,
                        AudioManager.STREAM_MUSIC,
                        AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
                );
                Log.w(TAG, "Audio focus request result=" + result);
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "Audio focus request failed", e);
        }
    }

    private void abandonFocus() {
        if (audioManager == null) {
            return;
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && focusRequest != null) {
                audioManager.abandonAudioFocusRequest(focusRequest);
            } else {
                audioManager.abandonAudioFocus(focusListener);
            }
        } catch (RuntimeException e) {
            Log.d(TAG, "Audio focus abandon failed", e);
        }
        focusRequest = null;
    }

    private void enableEqualizer() {
        if (equalizer != null) {
            return;
        }

        try {
            Equalizer next = new Equalizer(Integer.MAX_VALUE, OUTPUT_MIX_SESSION);
            short[] range = next.getBandLevelRange();
            short minLevel = range == null || range.length == 0 ? Short.MIN_VALUE : range[0];
            short bands = next.getNumberOfBands();
            for (short band = 0; band < bands; band++) {
                next.setBandLevel(band, minLevel);
            }
            next.setEnabled(true);
            equalizer = next;
            Log.w(TAG, "Equalizer enabled bands=" + bands + " minLevel=" + minLevel);
        } catch (Throwable e) {
            Log.w(TAG, "Equalizer unavailable", e);
        }
    }

    private void releaseEqualizer() {
        Equalizer old = equalizer;
        equalizer = null;
        if (old == null) {
            return;
        }

        try {
            old.setEnabled(false);
        } catch (Throwable e) {
            Log.d(TAG, "Equalizer disable failed", e);
        }
        try {
            old.release();
        } catch (Throwable e) {
            Log.d(TAG, "Equalizer release failed", e);
        }
    }

    private void enableDynamicsProcessing() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P || dynamicsProcessing != null) {
            return;
        }

        try {
            DynamicsProcessing.Limiter limiter = new DynamicsProcessing.Limiter(
                    true,
                    true,
                    0,
                    1.0f,
                    60.0f,
                    20.0f,
                    -100.0f,
                    -100.0f
            );

            DynamicsProcessing.Config config =
                    new DynamicsProcessing.Config.Builder(
                            DynamicsProcessing.VARIANT_FAVOR_TIME_RESOLUTION,
                            2,
                            false,
                            0,
                            false,
                            0,
                            false,
                            0,
                            true
                    )
                            .setInputGainAllChannelsTo(-100.0f)
                            .setLimiterAllChannelsTo(limiter)
                            .build();

            DynamicsProcessing next = new DynamicsProcessing(
                    Integer.MAX_VALUE,
                    OUTPUT_MIX_SESSION,
                    config
            );
            next.setInputGainAllChannelsTo(-100.0f);
            next.setLimiterAllChannelsTo(limiter);
            next.setEnabled(true);
            dynamicsProcessing = next;
            Log.w(TAG, "DynamicsProcessing enabled");
        } catch (Throwable e) {
            Log.w(TAG, "DynamicsProcessing unavailable", e);
        }
    }

    private void releaseDynamicsProcessing() {
        DynamicsProcessing old = dynamicsProcessing;
        dynamicsProcessing = null;
        if (old == null) {
            return;
        }

        try {
            old.setEnabled(false);
        } catch (Throwable e) {
            Log.d(TAG, "DynamicsProcessing disable failed", e);
        }
        try {
            old.release();
        } catch (Throwable e) {
            Log.d(TAG, "DynamicsProcessing release failed", e);
        }
    }

    private void startSilentTrack() {
        if (silentTrackThread != null) {
            return;
        }

        SilentTrackThread next = new SilentTrackThread();
        silentTrackThread = next;
        next.start();
    }

    private void stopSilentTrack() {
        SilentTrackThread old = silentTrackThread;
        silentTrackThread = null;
        if (old != null) {
            old.shutdown();
        }
    }

    private static final class SilentTrackThread extends Thread {
        private volatile boolean running = true;
        private AudioTrack track;

        SilentTrackThread() {
            super("silent-audio-track");
        }

        @Override
        public void run() {
            int minBuffer = AudioTrack.getMinBufferSize(SAMPLE_RATE, CHANNEL_MASK, ENCODING);
            int bufferBytes = Math.max(minBuffer, SAMPLE_RATE / 10 * 2 * 2);
            short[] silence = new short[Math.max(256, bufferBytes / 2)];

            try {
                AudioAttributes attributes = new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build();
                AudioFormat format = new AudioFormat.Builder()
                        .setSampleRate(SAMPLE_RATE)
                        .setEncoding(ENCODING)
                        .setChannelMask(CHANNEL_MASK)
                        .build();

                track = new AudioTrack.Builder()
                        .setAudioAttributes(attributes)
                        .setAudioFormat(format)
                        .setTransferMode(AudioTrack.MODE_STREAM)
                        .setBufferSizeInBytes(bufferBytes)
                        .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                        .build();
                track.setVolume(0.0f);
                track.play();
                Log.w(TAG, "Silent AudioTrack started bufferBytes=" + bufferBytes);

                while (running && track.getPlayState() == AudioTrack.PLAYSTATE_PLAYING) {
                    track.write(silence, 0, silence.length, AudioTrack.WRITE_BLOCKING);
                }
            } catch (Throwable e) {
                Log.w(TAG, "Silent AudioTrack failed", e);
            } finally {
                AudioTrack old = track;
                track = null;
                if (old != null) {
                    try {
                        old.pause();
                    } catch (Throwable ignored) {
                    }
                    try {
                        old.flush();
                    } catch (Throwable ignored) {
                    }
                    try {
                        old.release();
                    } catch (Throwable ignored) {
                    }
                }
                Log.w(TAG, "Silent AudioTrack stopped");
            }
        }

        void shutdown() {
            running = false;
            AudioTrack old = track;
            if (old != null) {
                try {
                    old.stop();
                } catch (Throwable ignored) {
                }
            }
            interrupt();
        }
    }
}
