package dev.malaiesun.home.rules;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

public class KeyGesturesTest {
    private final List<String> out = new ArrayList<>();
    private final KeyGestures g = new KeyGestures((k, c, p) -> out.add(k + ":" + p));

    @Test public void singleFiresOnReleaseWhenNoDoubleRule() {
        g.down("BLUE", 186, 0, false, false);
        g.up("BLUE", 80);
        assertEquals("[BLUE:single]", out.toString());
        assertEquals(-1, g.deadline());
    }

    @Test public void singleWaitsForDoubleWindow() {
        g.down("BACK", 4, 0, true, false);
        g.up("BACK", 60);
        assertTrue("nothing yet", out.isEmpty());
        long d = g.deadline();
        assertTrue(d > 60 + KeyGestures.DOUBLE_MS);
        g.tick(d);
        assertEquals("[BACK:single]", out.toString());
    }

    @Test public void doubleTap() {
        g.down("BACK", 4, 0, true, false);
        g.up("BACK", 60);
        g.down("BACK", 4, 200, true, false);
        g.up("BACK", 260);
        assertEquals("[BACK:double]", out.toString());
        g.tick(10_000);
        assertEquals("no stray single afterwards", 1, out.size());
    }

    @Test public void secondTapTooLateIsTwoSingles() {
        g.down("BACK", 4, 0, true, false);
        g.up("BACK", 60);
        g.tick(60 + KeyGestures.DOUBLE_MS + 1);
        g.down("BACK", 4, 1000, true, false);
        g.up("BACK", 1060);
        g.tick(2000);
        assertEquals("[BACK:single, BACK:single]", out.toString());
    }

    @Test public void longPressFiresWhileHeldAndOnlyOnce() {
        g.down("SETTINGS", 176, 0, false, true);
        assertEquals(KeyGestures.LONG_MS, g.deadline());
        g.tick(KeyGestures.LONG_MS);
        assertEquals("[SETTINGS:long]", out.toString());
        g.tick(KeyGestures.LONG_MS + 500);
        g.up("SETTINGS", 2000);
        assertEquals("release after long adds nothing", "[SETTINGS:long]", out.toString());
    }

    @Test public void shortPressOnLongCapableKeyIsSingle() {
        g.down("SETTINGS", 176, 0, false, true);
        g.tick(200);
        g.up("SETTINGS", 250);
        assertEquals("[SETTINGS:single]", out.toString());
    }

    @Test public void anotherKeyFlushesPendingSingle() {
        g.down("BACK", 4, 0, true, false);
        g.up("BACK", 50);
        g.down("BLUE", 186, 100, false, false);
        g.up("BLUE", 150);
        assertEquals("[BACK:single, BLUE:single]", out.toString());
    }

    @Test public void trackingReportsCurrentKey() {
        g.down("BACK", 4, 0, true, false);
        assertTrue(g.tracking("BACK"));
        g.up("BACK", 10);
        assertTrue("still waiting for a second tap", g.tracking("BACK"));
        g.tick(1000);
        assertTrue(!g.tracking("BACK"));
    }
}
