package dev.malaiesun.home.rules;

import java.util.ArrayList;
import java.util.List;

/**
 * One rule: a trigger, optional conditions, and a list of actions. Actions are opaque to the
 * engine (the Android side stores JSON objects there), so this package has no Android imports
 * and is unit-tested on the JVM.
 */
public final class Rule {
    public String name = "";
    public boolean enabled = true;

    // Triggers (one per rule)
    public String key;              // "BACK", "PROG_BLUE", "KEYCODE_SETTINGS" or a keycode number ("183")
    public String press = "single"; // single | double | long
    public String appOpened, appClosed;
    public String time;             // "HH:mm"
    public String event;            // API, popup or HA event name

    // Conditions
    public String inApp;            // package name, or "home" for PIXIE Home
    public String after, before;    // "HH:mm" window, may wrap past midnight

    public final List<Object> actions = new ArrayList<>();
}
