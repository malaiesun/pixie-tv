package dev.malaiesun.home;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Environment;
import android.provider.MediaStore;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLConnection;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Small LAN HTTP API (no dependencies). Pair once: POST /api/pair shows a 6-digit code on the TV,
 * POST /api/pair/confirm {"code"} returns a token. Every other call needs "Authorization: Bearer <token>".
 *
 *   GET  /api/info                      no auth
 *   POST /api/action  {"open_url":"..."} any action Actions knows; POST /api/<action> {params} works too
 *   GET  /api/state | /api/apps | /api/screenshot (PNG) | /api/rules | /api/learned
 *   PUT  /api/rules   rules.json body
 *   POST /api/file?name=x.mp4           raw body saved to Download/PIXIE
 *   GET  /api/events?since=N&timeout=25 long-poll
 */
final class Server {
    private static final int MAX_BODY = 2 * 1024 * 1024;

    private final PixieService s;
    private final SharedPreferences prefs;
    private final ExecutorService pool = Executors.newFixedThreadPool(4);
    private final SecureRandom rnd = new SecureRandom();
    private ServerSocket ss;
    private volatile boolean running;
    private volatile String pairCode;
    private volatile long pairUntil;
    private volatile int pairFails;
    int port;

    Server(PixieService s) {
        this.s = s;
        prefs = s.getSharedPreferences("agent", Context.MODE_PRIVATE);
    }

    void start(int port) {
        this.port = port;
        running = true;
        Thread t = new Thread(() -> {
            try {
                ss = new ServerSocket();
                ss.setReuseAddress(true);
                ss.bind(new InetSocketAddress(port));
                while (running) {
                    Socket c = ss.accept();
                    pool.execute(() -> handle(c));
                }
            } catch (IOException ignored) {
                // socket closed on stop(), or port busy
            }
        }, "pixie-http");
        t.setDaemon(true);
        t.start();
    }

    void stop() {
        running = false;
        try { if (ss != null) ss.close(); } catch (IOException ignored) { }
        pool.shutdownNow();
    }

    int pairedCount() { return prefs.getStringSet("tokens", new HashSet<>()).size(); }

    /** Shows a 6-digit code on the TV for 2 minutes. Returns seconds until it expires. */
    int startPairing(String who) {
        pairCode = String.format(Locale.ROOT, "%06d", rnd.nextInt(1_000_000));
        pairUntil = System.currentTimeMillis() + 120_000;
        pairFails = 0;
        String shown = pairCode.substring(0, 3) + " " + pairCode.substring(3);
        s.ui.post(() -> s.overlay.card("Pair with PIXIE", "Code:  " + shown + "\nEnter it in tvctl / your app (2 min).", false, 120_000));
        return 120;
    }

    // ---------------------------------------------------------------- HTTP

    private void handle(Socket sock) {
        try (Socket c = sock) {
            c.setSoTimeout(60_000);
            InputStream in = new BufferedInputStream(c.getInputStream());
            OutputStream out = c.getOutputStream();
            String line = readLine(in);
            if (line == null || line.isEmpty()) return;
            String[] rl = line.split(" ");
            if (rl.length < 2) return;
            String method = rl[0].toUpperCase(Locale.ROOT), target = rl[1];
            Map<String, String> h = new HashMap<>();
            for (String hl; (hl = readLine(in)) != null && !hl.isEmpty(); ) {
                int i = hl.indexOf(':');
                if (i > 0) h.put(hl.substring(0, i).trim().toLowerCase(Locale.ROOT), hl.substring(i + 1).trim());
            }
            String path = target, query = "";
            int qi = target.indexOf('?');
            if (qi >= 0) { path = target.substring(0, qi); query = target.substring(qi + 1); }
            Map<String, String> q = parseQuery(query);
            long len = 0;
            try { len = Long.parseLong(h.getOrDefault("content-length", "0")); } catch (NumberFormatException ignored) { }

            if (method.equals("OPTIONS")) { send(out, 204, "text/plain", new byte[0]); return; }
            if (path.equals("/api/info")) { json(out, 200, info()); return; }
            if (path.equals("/api/pair") && method.equals("POST")) {
                JSONObject r = new JSONObject().put("ok", true).put("expires_in", startPairing("api"));
                json(out, 200, r);
                return;
            }
            if (path.equals("/api/pair/confirm") && method.equals("POST")) {
                json(out, 200, confirm(new JSONObject(body(in, len))));
                return;
            }
            if (!authorized(h, q)) { json(out, 401, err("unauthorized: pair first (POST /api/pair)")); return; }

            if (path.equals("/api/file") && method.equals("POST")) { json(out, 200, saveFile(in, len, q.getOrDefault("name", "pixie.bin"))); return; }
            String body = len > 0 ? body(in, len) : "";
            switch (path) {
                case "/api/state": json(out, 200, onUi(s::state)); return;
                case "/api/apps": json(out, 200, onUi(this::apps)); return;
                case "/api/screenshot": screenshot(out); return;
                case "/api/events":
                    json(out, 200, s.events.since(parseLong(q.get("since"), 0), parseLong(q.get("timeout"), 25) * 1000));
                    return;
                case "/api/learned": file(out, new File(s.getExternalFilesDir(null), "learned.json")); return;
                case "/api/rules":
                    if (method.equals("GET")) { file(out, RulesIO.file(s)); return; }
                    RulesIO.parse(body);                                  // validate before saving
                    Config.write(RulesIO.file(s), body);
                    s.ui.post(s::reload);
                    json(out, 200, new JSONObject().put("ok", true));
                    return;
                case "/api/action": {
                    JSONObject a = new JSONObject(body.isEmpty() ? "{}" : body);
                    String name = Actions.nameOf(a);
                    json(out, 200, onUi(() -> s.actions.exec(name, Actions.paramsOf(a, name))));
                    return;
                }
                default:
                    if (path.startsWith("/api/") && method.equals("POST")) {
                        String name = path.substring(5);
                        JSONObject p = new JSONObject(body.isEmpty() ? "{}" : body);
                        json(out, 200, onUi(() -> s.actions.exec(name, p)));
                        return;
                    }
                    json(out, 404, err("not found"));
            }
        } catch (Exception e) {
            // client went away or sent garbage; nothing to do
        }
    }

    private JSONObject info() throws Exception {
        return new JSONObject().put("name", "PIXIE").put("app", "dev.malaiesun.home")
                .put("version", HomeActivity.BuildConfigHolder.version(s)).put("paired_clients", pairedCount());
    }

    private synchronized JSONObject confirm(JSONObject b) throws Exception {
        String code = b.optString("code", "").replace(" ", "");
        if (pairCode == null || System.currentTimeMillis() > pairUntil) return err("no pairing in progress");
        if (!code.equals(pairCode)) {
            if (++pairFails >= 5) pairCode = null;
            return err("wrong code");
        }
        pairCode = null;
        byte[] t = new byte[24];
        rnd.nextBytes(t);
        StringBuilder sb = new StringBuilder();
        for (byte x : t) sb.append(String.format(Locale.ROOT, "%02x", x));
        String token = sb.toString();
        Set<String> tokens = new HashSet<>(prefs.getStringSet("tokens", new HashSet<>()));
        tokens.add(token);
        prefs.edit().putStringSet("tokens", tokens).apply();
        String client = b.optString("client", "a device");
        s.ui.post(() -> s.overlay.card("Paired", client + " can now control PIXIE.", false, 4000));
        return new JSONObject().put("ok", true).put("token", token);
    }

    private boolean authorized(Map<String, String> h, Map<String, String> q) {
        String a = h.getOrDefault("authorization", "");
        String t = a.startsWith("Bearer ") ? a.substring(7).trim() : q.getOrDefault("token", "");
        return !t.isEmpty() && prefs.getStringSet("tokens", new HashSet<>()).contains(t);
    }

    private JSONObject apps() {
        JSONArray arr = new JSONArray();
        try {
            for (Apps.App a : Apps.query(s).values()) arr.put(new JSONObject().put("package", a.pkg).put("label", a.label));
            return new JSONObject().put("apps", arr);
        } catch (Exception e) { return err(e.getMessage()); }
    }

    private void screenshot(OutputStream out) throws Exception {
        CountDownLatch l = new CountDownLatch(1);
        AtomicReference<Bitmap> ref = new AtomicReference<>();
        s.ui.post(() -> s.screenshot(b -> { ref.set(b); l.countDown(); }));
        if (!l.await(6, TimeUnit.SECONDS) || ref.get() == null) { json(out, 503, err("screenshot failed (DRM video and some apps are blank)")); return; }
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        ref.get().compress(Bitmap.CompressFormat.PNG, 100, bo);
        send(out, 200, "image/png", bo.toByteArray());
    }

    private JSONObject saveFile(InputStream in, long len, String rawName) throws Exception {
        String name = new File(rawName).getName().replaceAll("[^A-Za-z0-9._ -]", "_");
        if (name.isEmpty()) name = "pixie.bin";
        ContentResolver cr = s.getContentResolver();
        ContentValues v = new ContentValues();
        v.put(MediaStore.Downloads.DISPLAY_NAME, name);
        v.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/PIXIE");
        String mime = URLConnection.guessContentTypeFromName(name);
        if (mime != null) v.put(MediaStore.Downloads.MIME_TYPE, mime);
        v.put(MediaStore.Downloads.IS_PENDING, 1);
        Uri u = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
        if (u == null) return err("could not create file");
        long done = 0;
        try (OutputStream os = cr.openOutputStream(u)) {
            byte[] buf = new byte[64 * 1024];
            while (done < len) {
                int n = in.read(buf, 0, (int) Math.min(buf.length, len - done));
                if (n < 0) break;
                os.write(buf, 0, n);
                done += n;
            }
        }
        v.clear();
        v.put(MediaStore.Downloads.IS_PENDING, 0);
        cr.update(u, v, null, null);
        final String fn = name, size = human(done);
        s.ui.post(() -> s.overlay.card("Received", fn + "  (" + size + ")\nDownload/PIXIE", false, 5000));
        s.fireEvent("file_received", new JSONObject().put("name", name).put("bytes", done));
        return new JSONObject().put("ok", done == len).put("bytes", done).put("path", "Download/PIXIE/" + name);
    }

    // ---- helpers ----

    interface UiCall { JSONObject call(); }

    /** Runs on the main thread and waits for the result (UI and accessibility calls must run there). */
    private JSONObject onUi(UiCall c) throws InterruptedException {
        CountDownLatch l = new CountDownLatch(1);
        AtomicReference<JSONObject> r = new AtomicReference<>();
        s.ui.post(() -> { try { r.set(c.call()); } catch (Exception e) { r.set(err(e.toString())); } l.countDown(); });
        if (!l.await(10, TimeUnit.SECONDS)) return err("timeout");
        return r.get();
    }

    private void file(OutputStream out, File f) throws IOException {
        if (!f.isFile()) { send(out, 404, "application/json", "{\"ok\":false,\"error\":\"not found\"}".getBytes(StandardCharsets.UTF_8)); return; }
        send(out, 200, "application/json", Config.read(new FileInputStream(f)).getBytes(StandardCharsets.UTF_8));
    }

    private static JSONObject err(String m) {
        JSONObject o = new JSONObject();
        try { o.put("ok", false).put("error", m); } catch (Exception ignored) { }
        return o;
    }

    private static void json(OutputStream out, int code, JSONObject o) throws IOException {
        send(out, code, "application/json", o.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static void send(OutputStream out, int code, String type, byte[] body) throws IOException {
        String status = code == 200 ? "OK" : code == 204 ? "No Content" : code == 401 ? "Unauthorized" : code == 404 ? "Not Found" : "Error";
        String head = "HTTP/1.1 " + code + " " + status + "\r\nContent-Type: " + type + "\r\nContent-Length: " + body.length
                + "\r\nAccess-Control-Allow-Origin: *\r\nAccess-Control-Allow-Headers: Authorization, Content-Type"
                + "\r\nAccess-Control-Allow-Methods: GET, POST, PUT, OPTIONS\r\nConnection: close\r\n\r\n";
        out.write(head.getBytes(StandardCharsets.ISO_8859_1));
        out.write(body);
        out.flush();
    }

    private static String body(InputStream in, long len) throws IOException {
        if (len > MAX_BODY) throw new IOException("body too large");
        byte[] b = new byte[(int) len];
        int off = 0;
        while (off < len) {
            int n = in.read(b, off, (int) len - off);
            if (n < 0) break;
            off += n;
        }
        return new String(b, 0, off, StandardCharsets.UTF_8);
    }

    private static String readLine(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        for (int c; (c = in.read()) != -1; ) {
            if (c == '\n') return sb.toString();
            if (c != '\r') sb.append((char) c);
            if (sb.length() > 8192) throw new IOException("line too long");
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    private static Map<String, String> parseQuery(String q) {
        Map<String, String> m = new HashMap<>();
        for (String kv : q.split("&")) {
            if (kv.isEmpty()) continue;
            int i = kv.indexOf('=');
            try {
                String k = URLDecoder.decode(i < 0 ? kv : kv.substring(0, i), "UTF-8");
                String v = i < 0 ? "" : URLDecoder.decode(kv.substring(i + 1), "UTF-8");
                m.put(k, v);
            } catch (Exception ignored) { }
        }
        return m;
    }

    private static long parseLong(String s, long def) {
        try { return s == null ? def : Long.parseLong(s); } catch (NumberFormatException e) { return def; }
    }

    private static String human(long b) {
        if (b < 1024) return b + " B";
        if (b < 1024 * 1024) return (b / 1024) + " KB";
        return String.format(Locale.ROOT, "%.1f MB", b / 1048576.0);
    }
}
