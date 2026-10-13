package dev.serika.camerasilencer;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

final class DiagnosticRecord {
    static final int MAX_EVENTS = 200;
    final JSONObject data;

    DiagnosticRecord(JSONObject metadata) {
        data = new JSONObject();
        put(data, "schema", 1);
        put(data, "environment", metadata);
        put(data, "phase", "recording");
        put(data, "audible_result", "not_reported");
        put(data, "had_issues", false);
        put(data, "dropped_events", 0);
        put(data, "components", new JSONObject());
        put(data, "events", new JSONArray());
    }

    DiagnosticRecord(String saved) throws JSONException {
        data = new JSONObject(saved);
        data.getJSONObject("components");
        data.getJSONArray("events");
    }

    void add(long elapsed, String component, String state, String detail) {
        JSONObject components = data.optJSONObject("components");
        JSONObject old = components.optJSONObject(component);
        String bounded = detail == null ? "" : detail.substring(0, Math.min(240, detail.length()));
        if (old != null && state.equals(old.optString("state")) && bounded.equals(old.optString("detail"))) return;
        JSONObject event = new JSONObject();
        put(event, "ms", Math.max(0, elapsed));
        put(event, "component", component);
        put(event, "state", state);
        put(event, "detail", bounded);
        put(components, component, event);
        if ("error".equals(state) || "unavailable".equals(state)) put(data, "had_issues", true);
        JSONArray events = data.optJSONArray("events");
        if (events.length() == MAX_EVENTS) {
            events.remove(0);
            put(data, "dropped_events", data.optInt("dropped_events") + 1);
        }
        events.put(event);
    }

    void outcome(String value) {
        if (!"heard".equals(value) && !"not_heard".equals(value) && !"unsure".equals(value)) {
            throw new IllegalArgumentException("Invalid audible result");
        }
        put(data, "audible_result", value);
    }

    static void put(JSONObject object, String key, Object value) {
        try { object.put(key, value); }
        catch (JSONException e) { throw new IllegalArgumentException(e); }
    }

    String text() {
        try { return data.toString(2); }
        catch (JSONException e) { return data.toString(); }
    }
}
