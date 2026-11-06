package dev.malaiesun.home.rules;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Set;

import org.junit.Test;

public class RuleEngineTest {
    private static final String HOME = "dev.malaiesun.home";

    private static Rule key(String name, String key, String press) {
        Rule r = new Rule(); r.name = name; r.key = key; r.press = press; return r;
    }

    @Test public void keyNamesMatchWithOrWithoutPrefixAndByNumber() {
        assertTrue(RuleEngine.keyMatches("BACK", "BACK", 4));
        assertTrue(RuleEngine.keyMatches("KEYCODE_BACK", "BACK", 4));
        assertTrue(RuleEngine.keyMatches("back", "KEYCODE_BACK", 4));
        assertTrue(RuleEngine.keyMatches("83", "NOTIFICATION", 83));
        assertFalse(RuleEngine.keyMatches("BACK", "HOME", 3));
        assertFalse(RuleEngine.keyMatches(null, "BACK", 4));
    }

    @Test public void pressesOnlyCountRulesWhoseConditionsHold() {
        RuleEngine e = new RuleEngine(HOME);
        Rule dbl = key("dbl", "BACK", "double"); dbl.inApp = "home";
        Rule lng = key("lng", "SETTINGS", "long");
        e.set(Arrays.asList(dbl, lng));
        Set<String> onHome = e.presses("BACK", 4, HOME, 600);
        assertEquals(1, onHome.size());
        assertTrue(onHome.contains("double"));
        assertTrue("Back in YouTube must pass through untouched", e.presses("BACK", 4, "com.google.android.youtube.tv", 600).isEmpty());
        assertTrue(e.presses("SETTINGS", 176, "anything", 600).contains("long"));
    }

    @Test public void matchKeyFiltersByPress() {
        RuleEngine e = new RuleEngine(HOME);
        e.set(Arrays.asList(key("tap", "PROG_BLUE", "single"), key("hold", "PROG_BLUE", "long")));
        assertEquals("tap", e.matchKey("PROG_BLUE", 186, "single", "x", 0).get(0).name);
        assertEquals("hold", e.matchKey("PROG_BLUE", 186, "long", "x", 0).get(0).name);
        assertTrue(e.matchKey("PROG_BLUE", 186, "double", "x", 0).isEmpty());
    }

    @Test public void disabledRulesNeverMatch() {
        RuleEngine e = new RuleEngine(HOME);
        Rule r = key("off", "BACK", "single"); r.enabled = false;
        e.set(Arrays.asList(r));
        assertTrue(e.matchKey("BACK", 4, "single", HOME, 0).isEmpty());
        assertTrue(e.presses("BACK", 4, HOME, 0).isEmpty());
    }

    @Test public void timeWindowsWrapMidnight() {
        int t2330 = 23 * 60 + 30, t0100 = 60, t1200 = 720;
        assertTrue(RuleEngine.inWindow(t2330, "23:00", "05:00"));
        assertTrue(RuleEngine.inWindow(t0100, "23:00", "05:00"));
        assertFalse(RuleEngine.inWindow(t1200, "23:00", "05:00"));
        assertTrue(RuleEngine.inWindow(t1200, "09:00", "17:00"));
        assertFalse(RuleEngine.inWindow(t2330, "09:00", "17:00"));
        assertTrue(RuleEngine.inWindow(t1200, null, null));
        assertTrue(RuleEngine.inWindow(t2330, "23:00", null));
        assertFalse(RuleEngine.inWindow(t1200, "23:00", null));
        assertTrue(RuleEngine.inWindow(t0100, null, "05:00"));
    }

    @Test public void appOpenedRuleRespectsWindow() {
        RuleEngine e = new RuleEngine(HOME);
        Rule r = new Rule(); r.name = "late yt"; r.appOpened = "com.google.android.youtube.tv"; r.after = "23:00"; r.before = "05:00";
        e.set(Arrays.asList(r));
        assertEquals(1, e.matchApp(true, "com.google.android.youtube.tv", "com.google.android.youtube.tv", 23 * 60 + 15).size());
        assertTrue(e.matchApp(true, "com.google.android.youtube.tv", "com.google.android.youtube.tv", 20 * 60).isEmpty());
        assertTrue("closing is a different trigger", e.matchApp(false, "com.google.android.youtube.tv", HOME, 23 * 60 + 15).isEmpty());
    }

    @Test public void timeAndEventTriggers() {
        RuleEngine e = new RuleEngine(HOME);
        Rule t = new Rule(); t.name = "night"; t.time = "22:30";
        Rule ev = new Rule(); ev.name = "pop"; ev.event = "popup:sleep:yes";
        e.set(Arrays.asList(t, ev));
        assertEquals(1, e.matchTime(22 * 60 + 30, "x").size());
        assertTrue(e.matchTime(22 * 60 + 31, "x").isEmpty());
        assertEquals("pop", e.matchEvent("popup:sleep:yes", "x", 0).get(0).name);
        assertEquals("night", e.byName("night").name);
    }

    @Test public void parseRejectsGarbage() {
        assertEquals(-1, RuleEngine.parse("25:00"));
        assertEquals(-1, RuleEngine.parse("ab"));
        assertEquals(-1, RuleEngine.parse(null));
        assertEquals(7 * 60 + 5, RuleEngine.parse("07:05"));
        assertEquals(8 * 60, RuleEngine.parse("8"));
    }
}
