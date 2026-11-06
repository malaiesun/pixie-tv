#!/usr/bin/env python3
"""tvctl: control PIXIE (the Infinix 43GU3Q softmod agent) from a PC. Python 3.8+, stdlib only.

  tvctl pair [HOST]              pair with the TV (it shows a 6-digit code)
  tvctl url URL                  open a link on the TV (YouTube links open YouTube)
  tvctl text "hello"             type into the focused text field on the TV
  tvctl clip                     send this PC's clipboard to the TV clipboard
  tvctl clip-sync                keep sending clipboard changes (Ctrl+C to stop)
  tvctl push FILE...             send files to the TV (Download/PIXIE)
  tvctl key NAME                 BACK HOME UP DOWN LEFT RIGHT OK PLAY_PAUSE VOLUME_UP MUTE ...
  tvctl notify "text" [-t TITLE]
  tvctl popup "question" -b Yes -b No [--wait]   (--wait prints the button pressed)
  tvctl open PACKAGE|youtube|netflix|hotstar|smarttube
  tvctl input hdmi1|hdmi2|hdmi3|av|livetv
  tvctl volume +5|-5|30|mute
  tvctl panel ha|quick|apps|voice
  tvctl theme comic|hero|retro|neon|aurora|minimal|next
  tvctl assist "turn off the fan"   (Home Assistant conversation)
  tvctl sleep MINUTES             sleep timer (0 cancels)
  tvctl screenshot [FILE.png]
  tvctl rules pull|push [FILE]
  tvctl state | apps | learn
  tvctl events [--notify]        live TV events; --notify shows desktop notifications
  tvctl action '{"toast":"hi"}'  any raw agent action
  tvctl install-menu             add "PIXIE TV" to Explorer's Send to (Windows) or Dolphin (KDE)
"""
import argparse
import json
import os
import platform
import subprocess
import sys
import time
import urllib.error
import urllib.request

APPS = {"youtube": "com.google.android.youtube.tv", "netflix": "com.netflix.ninja",
        "hotstar": "in.startv.hotstar", "jiohotstar": "in.startv.hotstar", "smarttube": "org.smarttube.stable"}
HERE = os.path.dirname(os.path.abspath(__file__))


def config_paths():
    if os.environ.get("PIXIE_CONFIG"):
        yield os.environ["PIXIE_CONFIG"]
    yield os.path.join(HERE, ".pixie.json")
    base = os.environ.get("APPDATA") or os.path.join(os.path.expanduser("~"), ".config")
    yield os.path.join(base, "pixie", "tvctl.json")


def load_cfg():
    for p in config_paths():
        if os.path.isfile(p):
            with open(p, encoding="utf-8") as f:
                c = json.load(f)
            c["_path"] = p
            return c
    sys.exit("Not paired yet. Run: tvctl pair <tv-ip>")


def save_cfg(c, path=None):
    path = path or next(config_paths())
    os.makedirs(os.path.dirname(path) or ".", exist_ok=True)
    with open(path, "w", encoding="utf-8") as f:
        json.dump({k: v for k, v in c.items() if not k.startswith("_")}, f, indent=2)
    return path


def http(method, url, body=None, token=None, raw=None, ctype="application/json", timeout=30):
    data = raw if raw is not None else (json.dumps(body).encode() if body is not None else None)
    req = urllib.request.Request(url, data=data, method=method)
    if data is not None:
        req.add_header("Content-Type", ctype)
    if token:
        req.add_header("Authorization", "Bearer " + token)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            return r.read(), r.headers.get("Content-Type", "")
    except urllib.error.HTTPError as e:
        return e.read(), e.headers.get("Content-Type", "")
    except urllib.error.URLError as e:
        sys.exit(f"Can't reach the TV ({e.reason}). Is it on and on the network?")


class Tv:
    def __init__(self, cfg):
        self.base = f"http://{cfg['host']}:{cfg.get('port', 8765)}/api"
        self.token = cfg["token"]

    def get(self, path, timeout=30):
        body, ctype = http("GET", f"{self.base}/{path}", token=self.token, timeout=timeout)
        return json.loads(body) if "json" in ctype else body

    def act(self, action, show=True):
        body, _ = http("POST", f"{self.base}/action", action, self.token)
        r = json.loads(body)
        if show and not r.get("ok"):
            print("TV says:", r.get("error"), file=sys.stderr)
        return r


# ---------------------------------------------------------------- clipboard / notifications

def read_clipboard():
    try:
        if platform.system() == "Windows":
            out = subprocess.run(["powershell", "-NoProfile", "-Command", "Get-Clipboard -Raw"],
                                 capture_output=True, text=True, encoding="utf-8", timeout=5).stdout
            return out.rstrip("\r\n")
        for cmd in (["wl-paste", "--no-newline"], ["xclip", "-o", "-selection", "clipboard"], ["pbpaste"]):
            try:
                return subprocess.run(cmd, capture_output=True, text=True, timeout=5).stdout
            except FileNotFoundError:
                continue
    except Exception:
        pass
    return ""


def notify_desktop(title, text):
    title, text = str(title)[:80], str(text)[:200]
    try:
        if platform.system() == "Windows":
            ps = (
                "[Windows.UI.Notifications.ToastNotificationManager, Windows.UI.Notifications, ContentType = WindowsRuntime] > $null;"
                "$x = [Windows.UI.Notifications.ToastNotificationManager]::GetTemplateContent([Windows.UI.Notifications.ToastTemplateType]::ToastText02);"
                "$t = $x.GetElementsByTagName('text'); $t.Item(0).AppendChild($x.CreateTextNode($env:PT)) > $null;"
                "$t.Item(1).AppendChild($x.CreateTextNode($env:PB)) > $null;"
                "[Windows.UI.Notifications.ToastNotificationManager]::CreateToastNotifier('PIXIE TV').Show([Windows.UI.Notifications.ToastNotification]::new($x))")
            subprocess.run(["powershell", "-NoProfile", "-Command", ps], env={**os.environ, "PT": title, "PB": text}, timeout=10)
        else:
            subprocess.run(["notify-send", "-a", "PIXIE TV", title, text], timeout=5)
    except Exception:
        print(f"[{title}] {text}")


# ---------------------------------------------------------------- commands

def cmd_pair(a):
    host = a.host
    if not host:
        try:
            host = load_cfg()["host"]
        except SystemExit:
            sys.exit("Usage: tvctl pair <tv-ip>")
    base = f"http://{host}:{a.port}/api"
    body, _ = http("POST", f"{base}/pair")
    if not json.loads(body).get("ok"):
        sys.exit("TV refused to start pairing")
    code = input("Code shown on the TV: ").strip().replace(" ", "")
    body, _ = http("POST", f"{base}/pair/confirm", {"code": code, "client": a.name or platform.node() + " (tvctl)"})
    r = json.loads(body)
    if not r.get("ok"):
        sys.exit("Pairing failed: " + r.get("error", "?"))
    path = save_cfg({"host": host, "port": a.port, "token": r["token"]}, a.save_to)
    print("Paired. Saved to", path)


def cmd_push(tv, a):
    for f in a.files:
        name = os.path.basename(f)
        size = os.path.getsize(f)
        print(f"sending {name} ({size / 1048576:.1f} MB)...", end=" ", flush=True)
        with open(f, "rb") as fh:
            data = fh.read()
        url = f"{tv.base}/file?name={urllib.request.quote(name)}"
        body, _ = http("POST", url, raw=data, token=tv.token, ctype="application/octet-stream", timeout=600)
        r = json.loads(body)
        print("ok ->", r.get("path") if r.get("ok") else r.get("error"))


def cmd_popup(tv, a):
    pid = f"tvctl{int(time.time())}"
    buttons = [{"label": b} for b in (a.button or ["OK"])]
    since = tv.get("events?since=0&timeout=0").get("last", 0) if a.wait else 0
    tv.act({"popup": {"id": pid, "title": a.title, "text": a.text, "buttons": buttons, "timeout": a.timeout}})
    if not a.wait:
        return
    end = time.time() + a.timeout
    while time.time() < end:
        ev = tv.get(f"events?since={since}&timeout=25", timeout=40)
        since = ev.get("last", since)
        for e in ev.get("events", []):
            if e.get("type", "").startswith(f"popup:{pid}:"):
                print(e["data"]["button"])
                return
    print("(no answer)")
    sys.exit(2)


def cmd_events(tv, a):
    since = tv.get("events?since=0&timeout=0").get("last", 0)
    print("Listening for TV events (Ctrl+C to stop)...")
    while True:
        ev = tv.get(f"events?since={since}&timeout=25", timeout=40)
        since = ev.get("last", since)
        for e in ev.get("events", []):
            line = f"{time.strftime('%H:%M:%S')}  {e['type']}  {json.dumps(e.get('data', {}))}"
            print(line, flush=True)
            if a.notify and (e["type"].startswith("popup:") or e["type"] in ("file_received", "assist")):
                notify_desktop("PIXIE TV", f"{e['type']} {json.dumps(e.get('data', {}))}")


def cmd_clip_sync(tv, a):
    last = read_clipboard()
    print("Syncing PC clipboard -> TV (Ctrl+C to stop)")
    while True:
        time.sleep(a.interval)
        cur = read_clipboard()
        if cur and cur != last:
            last = cur
            tv.act({"clipboard_set": {"text": cur}}, show=False)
            print(time.strftime("%H:%M:%S"), "sent", repr(cur[:60]))


def cmd_install_menu(a):
    py = sys.executable
    me = os.path.abspath(__file__)
    if platform.system() == "Windows":
        sendto = os.path.join(os.environ["APPDATA"], "Microsoft", "Windows", "SendTo", "PIXIE TV.cmd")
        with open(sendto, "w", encoding="utf-8") as f:
            f.write(f'@echo off\r\n"{py}" "{me}" push %*\r\nif errorlevel 1 pause\r\n')
        print("Added Explorer > Send to > PIXIE TV:", sendto)
    else:
        d = os.path.expanduser("~/.local/share/kio/servicemenus")
        os.makedirs(d, exist_ok=True)
        p = os.path.join(d, "pixie-send-to-tv.desktop")
        with open(p, "w") as f:
            f.write("[Desktop Entry]\nType=Service\nMimeType=all/allfiles;\nActions=pixie\nX-KDE-Priority=TopLevel\n\n"
                    f"[Desktop Action pixie]\nName=Send to TV (PIXIE)\nIcon=video-display\nExec={py} {me} push %F\n")
        os.chmod(p, 0o755)
        print("Added Dolphin service menu:", p)


def main():
    ap = argparse.ArgumentParser(prog="tvctl", description="Control PIXIE from this PC",
                                 formatter_class=argparse.RawDescriptionHelpFormatter, epilog=__doc__)
    sp = ap.add_subparsers(dest="cmd", required=True)
    p = sp.add_parser("pair"); p.add_argument("host", nargs="?"); p.add_argument("--port", type=int, default=8765)
    p.add_argument("--name"); p.add_argument("--save-to")
    sp.add_parser("url").add_argument("url")
    sp.add_parser("text").add_argument("text")
    sp.add_parser("clip")
    p = sp.add_parser("clip-sync"); p.add_argument("--interval", type=float, default=1.0)
    sp.add_parser("push").add_argument("files", nargs="+")
    sp.add_parser("key").add_argument("name")
    p = sp.add_parser("notify"); p.add_argument("text"); p.add_argument("-t", "--title", default="PC")
    p = sp.add_parser("popup"); p.add_argument("text"); p.add_argument("-t", "--title", default="PC")
    p.add_argument("-b", "--button", action="append"); p.add_argument("--wait", action="store_true")
    p.add_argument("--timeout", type=int, default=60)
    sp.add_parser("open").add_argument("app")
    sp.add_parser("input").add_argument("name")
    sp.add_parser("volume").add_argument("value")
    sp.add_parser("panel").add_argument("name", choices=["ha", "quick", "apps", "voice", "themes"])
    sp.add_parser("theme").add_argument("preset", help="aurora|comic|hero|retro|neon|minimal|next|rotate|daynight|mix")
    sp.add_parser("mascot").add_argument("mode", help="on (every theme) | auto (Retro only) | off | next | demo (show the app-opening move)")
    sp.add_parser("anim").add_argument("mode", help="opening animation: theme | system | off | next")
    sp.add_parser("assist").add_argument("text")
    sp.add_parser("sleep").add_argument("minutes", type=int)
    sp.add_parser("screenshot").add_argument("file", nargs="?", default="pixie-screenshot.png")
    p = sp.add_parser("rules"); p.add_argument("op", choices=["pull", "push"]); p.add_argument("file", nargs="?", default="rules.json")
    for n in ("state", "apps", "learn"):
        sp.add_parser(n)
    p = sp.add_parser("events"); p.add_argument("--notify", action="store_true")
    sp.add_parser("action").add_argument("json")
    sp.add_parser("install-menu")
    a = ap.parse_args()

    if a.cmd == "pair":
        return cmd_pair(a)
    if a.cmd == "install-menu":
        return cmd_install_menu(a)
    tv = Tv(load_cfg())
    c = a.cmd
    if c == "url": tv.act({"open_url": {"url": a.url}})
    elif c == "text": tv.act({"input_text": {"text": a.text}})
    elif c == "clip":
        txt = read_clipboard()
        if not txt:
            sys.exit("Clipboard is empty (or not text)")
        tv.act({"clipboard_set": {"text": txt}})
        print("sent", repr(txt[:60]))
    elif c == "clip-sync": cmd_clip_sync(tv, a)
    elif c == "push": cmd_push(tv, a)
    elif c == "key": tv.act({"key": {"key": a.name}})
    elif c == "notify": tv.act({"notify": {"title": a.title, "text": a.text}})
    elif c == "popup": cmd_popup(tv, a)
    elif c == "open": tv.act({"open_app": {"package": APPS.get(a.app.lower(), a.app)}})
    elif c == "input": tv.act({"set_input": {"input": a.name}})
    elif c == "volume":
        v = a.value
        if v == "mute": r = tv.act({"volume": {"mute": True}})
        elif v[0] in "+-": r = tv.act({"volume": {"delta": int(v)}})
        else: r = tv.act({"volume": {"set": int(v)}})
        print("volume", r.get("volume"))
    elif c == "panel": tv.act({"panel": {"name": a.name}})
    elif c == "theme": tv.act({"theme": {"preset": a.preset}})
    elif c == "mascot": tv.act({"mascot": {"mode": a.mode}})
    elif c == "anim": tv.act({"launch_animation": {"mode": a.mode}})
    elif c == "assist": tv.act({"assist": {"text": a.text}})
    elif c == "sleep": tv.act({"sleep_timer": {"minutes": a.minutes}})
    elif c == "screenshot":
        data = tv.get("screenshot", timeout=20)
        if isinstance(data, dict):
            sys.exit("Screenshot failed: " + data.get("error", "?"))
        with open(a.file, "wb") as f:
            f.write(data)
        print("saved", a.file)
    elif c == "rules":
        if a.op == "pull":
            with open(a.file, "w", encoding="utf-8") as f:
                json.dump(tv.get("rules"), f, indent=2)
            print("saved", a.file)
        else:
            with open(a.file, "rb") as f:
                body, _ = http("PUT", f"{tv.base}/rules", raw=f.read(), token=tv.token)
            print(json.loads(body))
    elif c in ("state", "apps"): print(json.dumps(tv.get(c), indent=2))
    elif c == "learn": tv.act({"learn": {"seconds": 60}}); print("Press remote buttons; codes show on the TV for 60 s.")
    elif c == "events": cmd_events(tv, a)
    elif c == "action": print(json.dumps(tv.act(json.loads(a.json)), indent=2))


if __name__ == "__main__":
    try:
        main()
    except KeyboardInterrupt:
        pass
