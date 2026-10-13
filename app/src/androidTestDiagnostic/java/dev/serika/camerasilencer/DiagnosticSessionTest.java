package dev.serika.camerasilencer;

import static org.junit.Assert.*;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.SharedPreferences;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public final class DiagnosticSessionTest {
    private Context context;
    private DiagnosticSession session;

    @Before public void setup() {
        context = new ContextWrapper(InstrumentationRegistry.getInstrumentation().getTargetContext()) {
            @Override public SharedPreferences getSharedPreferences(String name, int mode) {
                return super.getSharedPreferences("test_" + name, mode);
            }
            @Override public boolean stopService(Intent intent) { return false; }
        };
        context.getSharedPreferences("diagnostic_report", 0).edit().clear().commit();
        session = new DiagnosticSession(context);
    }
    @After public void cleanup() {
        session.stop("test_cleanup");
        session.clear();
    }
    @Test public void eventHistoryIsBoundedAndReportsLoss() throws Exception {
        DiagnosticRecord record = new DiagnosticRecord(new JSONObject());
        for (int i = 0; i < 240; i++) record.add(i, "camera", "observed", "event=" + i);
        JSONArray events = record.data.getJSONArray("events");
        assertEquals(200, events.length());
        assertEquals(40, record.data.getInt("dropped_events"));
        assertEquals(40, events.getJSONObject(0).getLong("ms"));
    }
    @Test public void apiSuccessNeverClaimsAudibleSuccessAndKeepsFailureHistory() throws Exception {
        DiagnosticRecord record = new DiagnosticRecord(new JSONObject());
        record.add(0, "equalizer", "enabled_and_controlled", "enabled=true");
        assertEquals("not_reported", record.data.getString("audible_result"));
        record.add(1, "equalizer", "unavailable", "hasControl=false");
        record.add(2, "equalizer", "enabled_and_controlled", "hasControl=true");
        assertTrue(record.data.getBoolean("had_issues"));
        assertEquals("not_reported", record.data.getString("audible_result"));
    }
    @Test public void newRunResetsResultAndOldWorkerCannotContaminateIt() throws Exception {
        session.begin(GuardSettings.MODE_MANUAL, "");
        long old = session.token();
        session.result("heard");
        session.stop("completed");
        session.begin(GuardSettings.MODE_MANUAL, "");
        session.trackFailure(old, new IllegalStateException());
        assertEquals("not_reported", session.result());
        assertFalse(session.hasIssues());
    }
    @Test public void unrelatedForegroundPackagesAndExceptionMessagesAreExcluded() {
        session.begin(GuardSettings.MODE_MANUAL, "");
        session.decision(context, true, false, true, "com.private.unrelated", false, false);
        session.failure("track", new IllegalStateException("private-address-secret"));
        assertFalse(session.report().contains("com.private.unrelated"));
        assertFalse(session.report().contains("private-address-secret"));
        assertTrue(session.report().contains("IllegalStateException"));
    }
    @Test public void interruptedReportSurvivesProcessReplacementWithoutRestarting() {
        session.begin(GuardSettings.MODE_MANUAL, "");
        session.event("camera", "unavailable id=0");
        DiagnosticSession replacement = new DiagnosticSession(context);
        assertEquals("interrupted", replacement.phase());
        assertFalse(replacement.recording());
        assertFalse(replacement.startService(null));
        assertTrue(replacement.report().contains("unavailable id=0"));
    }
    @Test public void stoppingKeepsUserResultAndRejectsAutomaticRestart() {
        session.begin(GuardSettings.MODE_MANUAL, "");
        session.result("not_heard");
        session.stop("result_entered");
        assertEquals("finished", session.phase());
        assertEquals("not_heard", session.result());
        assertFalse(session.startService(null));
        session.clear();
        assertFalse(session.hasReport());
    }
}
