package dev.serika.camerasilencer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.AudioManager;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public final class AudioSilencerTest {
    private Context context;
    private AudioManager audio;
    private AudioSilencer silencer;
    private int originalRingerMode;
    private int originalRingVolume;

    @Before
    public void setUp() {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        audio = context.getSystemService(AudioManager.class);
        originalRingerMode = audio.getRingerMode();
        audio.setRingerMode(AudioManager.RINGER_MODE_NORMAL);
        originalRingVolume = audio.getStreamVolume(AudioManager.STREAM_RING);
        // A non-maximum volume detects accidental restoration of the forced camera volume.
        audio.setStreamVolume(AudioManager.STREAM_RING, 3, 0);
        silencer = new AudioSilencer(context);
    }

    @After
    public void tearDown() {
        if (silencer != null) {
            silencer.shutdown();
        }
        audio.setRingerMode(AudioManager.RINGER_MODE_NORMAL);
        audio.setStreamVolume(AudioManager.STREAM_RING, originalRingVolume, 0);
        audio.setRingerMode(originalRingerMode);
    }

    @Test
    public void vibrateSurvivesGuardAndKeepsRingVolume() {
        audio.setRingerMode(AudioManager.RINGER_MODE_VIBRATE);
        silencer.enable();
        assertTrue(silencer.isActive());
        assertEquals(AudioManager.RINGER_MODE_VIBRATE, audio.getRingerMode());
        silencer.disable();
        assertEquals(AudioManager.RINGER_MODE_VIBRATE, audio.getRingerMode());
        assertRingVolumePreserved();
    }

    @Test
    public void normalSurvivesGuardAndKeepsRingVolume() {
        silencer.enable();
        assertTrue(silencer.isActive());
        assertTrue(audio.isStreamMute(AudioManager.STREAM_SYSTEM));
        assertEquals(AudioManager.RINGER_MODE_NORMAL, audio.getRingerMode());
        silencer.disable();
        assertEquals(AudioManager.RINGER_MODE_NORMAL, audio.getRingerMode());
        assertRingVolumePreserved();
    }

    @Test
    public void userCanSwitchFromVibrateToNormalDuringGuard() {
        audio.setRingerMode(AudioManager.RINGER_MODE_VIBRATE);
        silencer.enable();
        audio.setRingerMode(AudioManager.RINGER_MODE_NORMAL);
        silencer.disable();
        assertEquals(AudioManager.RINGER_MODE_NORMAL, audio.getRingerMode());
        assertRingVolumePreserved();
    }

    @Test
    public void userCanSwitchFromNormalToVibrateDuringGuard() {
        silencer.enable();
        audio.setRingerMode(AudioManager.RINGER_MODE_VIBRATE);
        silencer.disable();
        assertEquals(AudioManager.RINGER_MODE_VIBRATE, audio.getRingerMode());
        assertRingVolumePreserved();
    }

    @Test
    public void persistedRecoveryPreservesVibrate() {
        audio.setRingerMode(AudioManager.RINGER_MODE_VIBRATE);
        silencer.enable();
        AudioSilencer.restorePersistedSnapshot(context);
        assertEquals(AudioManager.RINGER_MODE_VIBRATE, audio.getRingerMode());
        assertFalse(snapshotPreferences().getBoolean("has_snapshot", false));
        assertRingVolumePreserved();
    }

    @Test
    public void persistedRecoveryUnmutesNormal() {
        silencer.enable();
        AudioSilencer.restorePersistedSnapshot(context);
        assertEquals(AudioManager.RINGER_MODE_NORMAL, audio.getRingerMode());
        assertFalse(audio.isStreamMute(AudioManager.STREAM_SYSTEM));
        assertRingVolumePreserved();
    }

    @Test
    public void legacyRecoveryPreservesCurrentRingerMode() {
        audio.setRingerMode(AudioManager.RINGER_MODE_VIBRATE);
        snapshotPreferences().edit().clear()
                .putBoolean("has_snapshot", true)
                .putInt("system.volume", 0)
                .putBoolean("system.muted", true)
                .putInt("enforced.volume", 7)
                .putBoolean("enforced.muted", false)
                .commit();

        AudioSilencer.restorePersistedSnapshot(context);

        assertEquals(AudioManager.RINGER_MODE_VIBRATE, audio.getRingerMode());
        assertFalse(snapshotPreferences().getBoolean("has_snapshot", false));
    }

    private SharedPreferences snapshotPreferences() {
        return context.getSharedPreferences("audio_snapshot", Context.MODE_PRIVATE);
    }

    private void assertRingVolumePreserved() {
        audio.setRingerMode(AudioManager.RINGER_MODE_NORMAL);
        assertEquals(3, audio.getStreamVolume(AudioManager.STREAM_RING));
    }
}
