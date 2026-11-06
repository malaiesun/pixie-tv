package dev.malaiesun.home;

import android.os.SystemClock;
import android.view.Choreographer;

import java.util.ArrayList;
import java.util.List;

/**
 * One shared ~30 fps animation clock for Home (background, logo, mascot). Views that each ran their own
 * timer drew in different vsyncs, so the screen re-rendered ~48 times a second; on one clock every
 * animated view updates in the same frame. Main thread only.
 */
final class Pulse {
    interface Listener { void onPulse(long nowMs); }

    private static final long PERIOD_NS = 30_000_000L;   // every 2nd vsync at 60 Hz
    private static final List<Listener> listeners = new ArrayList<>();
    private static boolean posted;
    private static long lastNs;

    private static final Choreographer.FrameCallback tick = new Choreographer.FrameCallback() {
        @Override public void doFrame(long ns) {
            posted = false;
            if (listeners.isEmpty()) return;
            if (ns - lastNs >= PERIOD_NS) {
                lastNs = ns;
                long now = SystemClock.uptimeMillis();
                for (Listener l : new ArrayList<>(listeners)) l.onPulse(now);
            }
            post();
        }
    };

    static void add(Listener l) {
        if (!listeners.contains(l)) listeners.add(l);
        post();
    }

    static void remove(Listener l) { listeners.remove(l); }

    private static void post() {
        if (posted || listeners.isEmpty()) return;
        posted = true;
        Choreographer.getInstance().postFrameCallback(tick);
    }

    private Pulse() { }
}
