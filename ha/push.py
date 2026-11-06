"""Push ha/scripts.json to Home Assistant as UI-editable scripts (stdlib only).

Usage: python ha/push.py [--url http://homeassistant.local:8123] [--delete]
URL and the TV's ADB media_player entity come from local.env (HA_URL, HA_ADB_PLAYER).
Token is read from ha/.token (git-ignored). Keys starting with "_" in scripts.json are notes, not scripts.
--delete removes every script defined in scripts.json from HA instead.
"""
import argparse
import json
import pathlib
import urllib.request

HERE = pathlib.Path(__file__).resolve().parent


def local_env(key, default):
    import os
    if os.environ.get(key):
        return os.environ[key]
    f = HERE.parent / "local.env"
    if f.exists():
        for line in f.read_text().splitlines():
            if line.strip().startswith(key + "="):
                return line.split("=", 1)[1].strip().strip('"')
    return default


ap = argparse.ArgumentParser()
ap.add_argument("--url", default=local_env("HA_URL", "http://homeassistant.local:8123"))
ap.add_argument("--delete", action="store_true")
args = ap.parse_args()

token = (HERE / ".token").read_text().strip()
raw = (HERE / "scripts.json").read_text().replace("__ADB_PLAYER__", local_env("HA_ADB_PLAYER", "media_player.android_tv"))
scripts = {k: v for k, v in json.loads(raw).items() if not k.startswith("_")}


def call(method, path, body=None):
    req = urllib.request.Request(
        f"{args.url}{path}", method=method,
        data=None if body is None else json.dumps(body).encode(),
        headers={"Authorization": f"Bearer {token}", "Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=30) as r:
        return r.status, r.read().decode()


for sid, cfg in scripts.items():
    status, body = call("DELETE" if args.delete else "POST", f"/api/config/script/config/{sid}", None if args.delete else cfg)
    print(f"{'deleted' if args.delete else 'pushed '} script.{sid}: {status} {body[:80]}")
