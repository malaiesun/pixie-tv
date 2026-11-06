package dev.malaiesun.home.rules;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Matches triggers against rules. Pure Java: no Android, no clock (callers pass minute-of-day). */
public final class RuleEngine {
    public static final String HOME = "home";

    private final List<Rule> rules = new ArrayList<>();
    private final String homePackage;

    public RuleEngine(String homePackage) { this.homePackage = homePackage; }

    public void set(List<Rule> rs) { rules.clear(); if (rs != null) rules.addAll(rs); }

    public List<Rule> all() { return Collections.unmodifiableList(rules); }

    /** Which presses (single/double/long) have an enabled rule for this key in this context. */
    public Set<String> presses(String keyName, int code, String fg, int minute) {
        Set<String> s = new HashSet<>();
        for (Rule r : rules)
            if (r.enabled && keyMatches(r.key, keyName, code) && conditions(r, fg, minute)) s.add(norm(r.press));
        return s;
    }

    public List<Rule> matchKey(String keyName, int code, String press, String fg, int minute) {
        List<Rule> out = new ArrayList<>();
        for (Rule r : rules)
            if (r.enabled && keyMatches(r.key, keyName, code) && norm(r.press).equals(norm(press)) && conditions(r, fg, minute)) out.add(r);
        return out;
    }

    public List<Rule> matchApp(boolean opened, String pkg, String fg, int minute) {
        List<Rule> out = new ArrayList<>();
        for (Rule r : rules) {
            if (!r.enabled) continue;
            String want = opened ? r.appOpened : r.appClosed;
            if (want != null && resolveApp(want).equals(pkg) && conditions(r, fg, minute)) out.add(r);
        }
        return out;
    }

    public List<Rule> matchTime(int minute, String fg) {
        List<Rule> out = new ArrayList<>();
        for (Rule r : rules)
            if (r.enabled && r.time != null && parse(r.time) == minute && conditions(r, fg, minute)) out.add(r);
        return out;
    }

    public List<Rule> matchEvent(String name, String fg, int minute) {
        List<Rule> out = new ArrayList<>();
        for (Rule r : rules)
            if (r.enabled && r.event != null && r.event.equals(name) && conditions(r, fg, minute)) out.add(r);
        return out;
    }

    public Rule byName(String name) {
        for (Rule r : rules) if (r.name.equals(name)) return r;
        return null;
    }

    // ---- helpers ----

    boolean conditions(Rule r, String fg, int minute) {
        if (r.inApp != null && !resolveApp(r.inApp).equals(fg == null ? "" : fg)) return false;
        return inWindow(minute, r.after, r.before);
    }

    private String resolveApp(String a) { return HOME.equals(a) ? homePackage : a; }

    static boolean keyMatches(String ruleKey, String keyName, int code) {
        if (ruleKey == null) return false;
        String k = ruleKey.trim();
        if (k.equals(String.valueOf(code))) return true;
        return normKey(k).equals(normKey(keyName));
    }

    static String normKey(String k) {
        if (k == null) return "";
        String u = k.trim().toUpperCase(Locale.ROOT);
        return u.startsWith("KEYCODE_") ? u.substring(8) : u;
    }

    private static String norm(String p) { return p == null ? "single" : p.trim().toLowerCase(Locale.ROOT); }

    /** True when minute (0..1439) is inside [after, before). Either bound may be missing; wraps midnight. */
    public static boolean inWindow(int m, String after, String before) {
        int a = after == null ? -1 : parse(after), b = before == null ? -1 : parse(before);
        if (a < 0 && b < 0) return true;
        if (b < 0) return m >= a;
        if (a < 0) return m < b;
        return a <= b ? (m >= a && m < b) : (m >= a || m < b);
    }

    /** "HH:mm" to minute of day, or -1 if invalid. */
    public static int parse(String hhmm) {
        if (hhmm == null) return -1;
        try {
            String[] p = hhmm.trim().split(":");
            int h = Integer.parseInt(p[0]), mm = p.length > 1 ? Integer.parseInt(p[1]) : 0;
            if (h < 0 || h > 23 || mm < 0 || mm > 59) return -1;
            return h * 60 + mm;
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
