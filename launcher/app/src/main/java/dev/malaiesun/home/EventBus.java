package dev.malaiesun.home;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayDeque;

/** Recent agent events (keys, apps, popup answers...) for API long-polling. Keeps the last 200. */
final class EventBus {
    private final ArrayDeque<JSONObject> q = new ArrayDeque<>();
    private long seq;

    synchronized long emit(String type, JSONObject data) {
        JSONObject e = new JSONObject();
        try {
            e.put("id", ++seq).put("type", type).put("time", System.currentTimeMillis());
            if (data != null) e.put("data", data);
        } catch (JSONException ignored) { }
        q.addLast(e);
        while (q.size() > 200) q.removeFirst();
        notifyAll();
        return seq;
    }

    /** Events with id > since; waits up to timeoutMs for the first one. */
    synchronized JSONObject since(long since, long timeoutMs) throws InterruptedException {
        long end = System.currentTimeMillis() + Math.max(0, Math.min(timeoutMs, 55_000));
        while (seq <= since) {
            long left = end - System.currentTimeMillis();
            if (left <= 0) break;
            wait(left);
        }
        JSONArray out = new JSONArray();
        for (JSONObject e : q) if (e.optLong("id") > since) out.put(e);
        JSONObject r = new JSONObject();
        try { r.put("last", seq).put("events", out); } catch (JSONException ignored) { }
        return r;
    }
}
