# PIXIE: Infinix 43GU3Q softmod

No ads, no Google TV home, no OEM telemetry: a custom animated home screen (**PIXIE Home**) with a pixel
mascot, an on-TV agent with remote-button shortcuts, a Home Assistant panel, a themed volume slider, a LAN
API and a PC tool (`tvctl`). No root, everything reversible.

Coded with the help of **Claude Opus 5.5** (Anthropic).

<p align="center">
  <img src="docs/media/open-retro.gif" width="720" alt="Pixie runs under a tile, jumps, bumps it and the app opens with an 8-bit wipe">
</p>

## Gallery

### Six themes
| Retro 8-bit | Comic Pop | Neon Grid |
|---|---|---|
| <img src="docs/media/home-retro.png" width="300"> | <img src="docs/media/home-comic.png" width="300"> | <img src="docs/media/home-neon.png" width="300"> |
| **Hero** | **Aurora** | **Minimal** |
| <img src="docs/media/home-hero.png" width="300"> | <img src="docs/media/home-aurora.png" width="300"> | <img src="docs/media/home-minimal.png" width="300"> |

### Opening an app: a different move and effect per theme
| Retro: jump and bump, 8-bit wipe | Comic: portal punch, POW wipe | Neon: jetpack laser, neon frame |
|---|---|---|
| <img src="docs/media/open-retro.gif" width="300"> | <img src="docs/media/open-comic.gif" width="300"> | <img src="docs/media/open-neon.gif" width="300"> |
| **Hero: superman dive, light rays** | **Aurora: magic star, soft glow** | **Minimal: paper plane, zoom** |
| <img src="docs/media/open-hero.gif" width="300"> | <img src="docs/media/open-aurora.gif" width="300"> | <img src="docs/media/open-minimal.gif" width="300"> |

### Getting around
| Comic: portals | Neon: jetpack | Hero: flying | Aurora: floating |
|---|---|---|---|
| <img src="docs/media/roam-comic.gif" width="220"> | <img src="docs/media/roam-neon.gif" width="220"> | <img src="docs/media/roam-hero.gif" width="220"> | <img src="docs/media/roam-aurora.gif" width="220"> |

### Changing themes, saying hello
| Theme change: Pixie spins into the new outfit | After every boot |
|---|---|
| <img src="docs/media/theme-switch.gif" width="440"> | <img src="docs/media/hello.gif" width="440"> |

### Panels and overlays
| Gear: quick panel | Gear held: Home Assistant | Themes |
|---|---|---|
| <img src="docs/media/panel-quick.png" width="300"> | <img src="docs/media/panel-ha.png" width="300"> | <img src="docs/media/panel-themes.png" width="300"> |
| **Talk to (Gemini / HA Assist)** | **Popup from Home Assistant** | **Notification** |
| <img src="docs/media/panel-voice.png" width="300"> | <img src="docs/media/popup.png" width="300"> | <img src="docs/media/notify.png" width="300"> |
| **Volume (Retro)** | **Volume (Neon)** | **Quick panel (Neon)** |
| <img src="docs/media/volume-retro.png" width="300"> | <img src="docs/media/volume-neon.png" width="300"> | <img src="docs/media/panel-quick-neon.png" width="300"> |

### Pixie's outfits
<img src="docs/media/pixie-outfits.png" width="520">

## Features

- **PIXIE Home**: animated themes (Retro 8-bit, Comic Pop, Hero, Neon Grid, Aurora, Minimal), favorites,
  inputs, recent apps, live Home Assistant chips. Rotate themes daily, day/night schedule, or mix them.
- **Pixie**: a pixel robot mascot with a different outfit and moves per theme. It walks, flies, floats or
  teleports around Home, opens apps with its own move, greets you after boot, and speaks for the TV in
  panels, popups and notifications.
- **Opening animations**: each theme has its own full-screen effect, played from the tile you picked and
  reversed when you come back.
- **Remote shortcuts**: tap / double / long-press rules for any button, a quick panel, a Home Assistant panel,
  HA Assist by voice, a themed volume slider, sleep timer.
- **LAN API + `tvctl`**: send links, text, files, keys, notifications and popups from a PC; switch inputs,
  themes and volume; follow TV events.
- **Home Assistant**: scripts for inputs, apps and keys, plus a package that lets HA push notifications,
  popups and themes to the TV.
- **Debloat**: ad, telemetry and OEM packages disabled (never uninstalled), with a one-command restore.

## Tested on

| | |
|---|---|
| TV | Infinix 43GU3Q, 43" 4K (India), built by Skyworth |
| Board | SW6H, MediaTek m7332, 1.9 GB RAM |
| OS | Android TV 14 (API 34), Google TV, bootloader locked |

The launcher and agent should work on other Android TV 10+ devices; the debloat list is for this board only.

## Setup

1. On the TV: Settings > System > About > tap *Android TV OS build* 7 times, then Developer options >
   USB debugging + Wireless debugging. Pair once (`adb pair <ip>:<port>`), then `adb connect <ip>:5555`.
2. `cp local.env.example local.env` and fill in the TV's IP and your Home Assistant URL. Put an HA long-lived
   token in `ha/.token`. Both files are git-ignored.
3. Debloat in batches, checking after each: `bash scripts/disable.sh debloat/43gu3q.txt 1` (up to 7).
   Every change is logged with its undo; `bash debloat/restore.sh` puts everything back.
4. Build and install PIXIE Home (Android SDK, JDK 17): `bash launcher/build-install.sh`.
5. Make it Home. Re-join your Wi-Fi from Settings first (the setup wizard can own the saved network), then:
   ```
   adb shell pm disable-user --user 0 com.google.android.tungsten.setupwraith
   adb shell pm disable-user --user 0 com.google.android.apps.tv.launcherx
   adb shell cmd package set-home-activity dev.malaiesun.home/.HomeActivity
   ```
6. PC: `python pc/tvctl.py pair <tv-ip>` (enter the 6-digit code shown on the TV).
   Home Assistant: `python ha/push.py`.
7. Check everything: `bash scripts/verify.sh`.

## Using it

### Remote buttons (defaults, all changeable in `rules.json`)

| Button | Tap | Hold |
|---|---|---|
| Gear (settings) | Quick panel | Home Assistant panel |
| Blue shortcut | App switcher | HA Assist (speak to Home Assistant) |
| YouTube | YouTube | SmartTube |
| Back on Home | Top of Home (double tap: quick panel) | |
| Volume / mute | Themed volume slider | |

Panels use the D-pad; OK selects, Back closes. Long-press any app on Home for favorite, move, hide, app info
and **PIXIE Home options**.

### Themes
Tap the gear, then **Themes** (or `tvctl theme neon`, or `script.pixie_theme` from HA). The Options row there
switches Pixie (`on`, `auto` = Retro only, `off`) and the opening animation (`theme`, `system`, `off`).

### From the PC (`pc/tvctl.py`)
```
tvctl url https://youtu.be/...      tvctl text "search words"     tvctl push movie.mp4
tvctl notify "Dinner's ready"       tvctl popup "Pause?" -b Yes -b No --wait
tvctl key BACK | HOME | OK | UP     tvctl input hdmi2             tvctl volume +5 | 30 | mute
tvctl panel ha                      tvctl assist "turn off the fan"   tvctl sleep 30
tvctl theme retro                   tvctl mascot on|auto|off|demo  tvctl anim theme|system|off|demo
tvctl screenshot                    tvctl rules pull / push        tvctl events --notify
tvctl clip / clip-sync              tvctl install-menu   (Explorer "Send to" / Dolphin)
```

### From Home Assistant
`python ha/push.py` adds scripts for inputs (`pixie_hdmi_1..3`, `pixie_av`), apps (`pixie_open_app`), keys
and shell commands, using the Android TV Remote and ADB integrations. To let HA push notifications, popups
and themes to the TV, add [ha/pixie_agent.yaml](ha/pixie_agent.yaml) as a package (instructions inside).

## Customizing

| File | What |
|---|---|
| [launcher/config/pixie.json](launcher/config/pixie.json) | theme, mascot, opening animation, rows, favorites, hidden apps, HA chips and panel, agent settings |
| [launcher/config/rules.json](launcher/config/rules.json) | button shortcuts, app / time / event rules |

Push both: `bash launcher/build-install.sh --config-only` (pull changes made on the TV first with
`--pull-config`).

A rule: `{"name", "key"+"press" (single|double|long) | "app_opened" | "app_closed" | "time" | "event",
"in_app", "after", "before", "do": [actions]}`. Actions: `open_app`, `open_url`, `input_text`, `key`,
`clipboard_set`, `toast`, `notify`, `popup`, `volume`, `set_input`, `ha_service`, `ha_webhook`, `assist`,
`panel`, `voice`, `theme`, `mascot`, `launch_animation`, `sleep_timer`, `screenshot`, `learn`, `pair`,
`run_rule`, `event`, `delay`. `tvctl learn` shows the key name of any remote button.

## Repo layout

```
launcher/       PIXIE Home + agent (Java, no libraries), config/, build-install.sh
debloat/        package list for this board, restore.sh
pc/tvctl.py     PC control tool
ha/             Home Assistant scripts and package
scripts/        adb helpers, verify, debloat, tests, media recording
docs/media/     screenshots and GIFs
```

## Limits

- The mic button talks to Gemini directly and can't be remapped; HA Assist is on blue-hold instead.
- PIXIE Home uses about 70 MB of RAM; the Minimal theme is the lightest.
- Disabling `com.smartdevice.aftersale` may also stop firmware updates; re-enable it before updating.

## Credits

Built by Malaiesun, coded with the help of **Claude Opus 5.5** by Anthropic.
Debloat list format and SW6H notes from tv-vibehack.
