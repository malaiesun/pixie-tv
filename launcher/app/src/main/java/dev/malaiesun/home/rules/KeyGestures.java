package dev.malaiesun.home.rules;

/**
 * Turns raw key down/up events into single / double / long presses. Time is passed in, and the
 * caller runs tick() at deadline(), so this is deterministic and unit-testable.
 *
 * - A key with no double rule fires "single" on release (no added latency).
 * - A key with a double rule waits DOUBLE_MS after release for a second tap.
 * - A key with a long rule fires "long" once LONG_MS after the press while still held.
 */
public final class KeyGestures {
    public interface Sink { void gesture(String key, int code, String press); }

    public static final long LONG_MS = 550, DOUBLE_MS = 320;

    private final Sink sink;
    private String key;
    private int code;
    private long downAt = -1, upAt = -1;
    private boolean down, longFired, wantsDouble, wantsLong;
    private int taps;

    public KeyGestures(Sink sink) { this.sink = sink; }

    /** First ACTION_DOWN of a press (not auto-repeats). */
    public void down(String k, int c, long t, boolean wantsDouble, boolean wantsLong) {
        if (key != null && key.equals(k) && taps == 1 && !down && t - upAt <= DOUBLE_MS) {
            taps = 2;           // second tap of a double press
            down = true;
            downAt = t;
            return;
        }
        if (key != null) flush();
        key = k;
        code = c;
        downAt = t;
        down = true;
        longFired = false;
        taps = 1;
        this.wantsDouble = wantsDouble;
        this.wantsLong = wantsLong;
    }

    public void up(String k, long t) {
        if (key == null || !key.equals(k)) return;
        down = false;
        upAt = t;
        if (longFired) { reset(); return; }
        if (taps == 2) { emit("double"); reset(); return; }
        if (!wantsDouble) { emit("single"); reset(); }
        // else: wait for a possible second tap until deadline()
    }

    public void tick(long t) {
        if (key == null) return;
        if (down && taps == 1 && wantsLong && !longFired && t - downAt >= LONG_MS) { longFired = true; emit("long"); return; }
        if (!down && taps == 1 && wantsDouble && t - upAt > DOUBLE_MS) { emit("single"); reset(); }
    }

    /** When tick() must run next, or -1 if nothing is pending. */
    public long deadline() {
        if (key == null) return -1;
        if (down && taps == 1 && wantsLong && !longFired) return downAt + LONG_MS;
        if (!down && taps == 1 && wantsDouble) return upAt + DOUBLE_MS + 1;
        return -1;
    }

    /** True while this key is being tracked (its events must be consumed). */
    public boolean tracking(String k) { return key != null && key.equals(k); }

    private void flush() {
        if (key != null && !down && taps == 1 && !longFired) emit("single");
        reset();
    }

    private void emit(String press) { sink.gesture(key, code, press); }

    private void reset() {
        key = null;
        taps = 0;
        down = false;
        longFired = false;
        downAt = upAt = -1;
    }
}
