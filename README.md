# Rokid HUD Maps

Dual-app Android project: **phone** streams GPS, turn-by-turn navigation, and notifications to **Rokid AR glasses** over Bluetooth. The glasses show a live map, directions, and route; TTS can play through the glasses via Bluetooth audio.

## Architecture

```
┌─────────────────────────────────┐     Bluetooth SPP      ┌─────────────────────────────────┐
│         Phone App               │ ◄─────────────────────► │        Glasses App              │
│  • GPS @ 1Hz                    │   JSON-per-line        │  • Live map (CartoDB dark)      │
│  • OSRM routing (free, no key)  │   protocol             │  • Turn-by-turn + distance      │
│  • Nominatim search             │   + tile proxy         │  • Route line + compass         │
│  • BT SPP server + A2DP audio   │   + APK update         │  • Layouts: full / corner / mini│
│  • Map + directions when nav    │   + settings           │  • Wi‑Fi (optional); tile proxy│
│  • TTS → Bluetooth audio        │                        │  • Notifications (full layout)  │
│  • WakeLock when streaming      │                        │  • Update app from phone or ADB │
└─────────────────────────────────┘                        └─────────────────────────────────┘
```

## Modules

| Module   | Package                  | Description                                                |
|----------|--------------------------|------------------------------------------------------------|
| `shared` | `com.rokid.hud.shared`   | Protocol (state, route, step, settings, wifi, tiles, APK) |
| `phone`  | `com.rokid.hud.phone`    | Search, routing, streaming, map when nav, Wi‑Fi, TTS, update glasses |
| `glasses`| `com.rokid.hud.glasses`  | HUD map, directions, Wi‑Fi connector, BT client, install APK |

## Features

### Phone app

- **Search** — Nominatim (OpenStreetMap), no API key
- **Turn-by-turn** — OSRM routing; start/stop navigation; re-route on deviation
- **Map when navigating** — OSMDroid map and live directions shown on phone only while navigating; hidden when not
- **Streaming** — Start streaming to start BT SPP server; glasses auto-connect when paired
- **Pair glasses** — Bluetooth device scan; bond and select device for SPP
- **Saved places** — Save current destination; view and pick from saved list
- **Voice directions (TTS)** — Reads instructions aloud; routes audio to glasses via **Bluetooth A2DP** (enable in settings and ensure BT audio is connected)
- **Units** — Imperial (miles/feet) or metric (km/m)
- **Mini map on glasses** — Toggle in settings; when on, glasses show 25% map at bottom with direction and distance only (no notifications area)
- **Wi‑Fi Direct** — Creates hotspot and sends SSID/password to glasses; use Mobile Hotspot section to share internet for tiles
- **Update glasses app** — Send glasses APK over Bluetooth from phone; install on glasses without ADB (manual ADB install still supported)
- **Keep running when screen off** — WakeLock and optional battery optimization exemption so maps keep updating on glasses when the phone screen times out
- **Notifications** — Forward notifications to glasses (Notification Access permission)

### Glasses app

- **Live map** — CartoDB Dark Matter tiles; green tint; rotates with heading; tiles via BT proxy from phone when no Wi‑Fi
- **Route** — Current step, distance, route line; “You have arrived!” on destination
- **Status** — BT and Wi‑Fi connection indicators
- **Layouts** — **Full**: map ~72%, directions + notifications below. **Corner**: tap to get small map corner + text. **Mini** (from phone toggle): 25% map at bottom, direction + distance at bottom, no notifications
- **Wi‑Fi** — Receives creds over BT; enables Wi‑Fi and connects (needs `WRITE_SECURE_SETTINGS` via ADB on some devices)
- **Install APK** — Can receive and install glasses APK sent from phone (FileProvider + system installer)

## Building

### Prerequisites

- JDK 17+
- Android SDK (API 34 recommended)
- Android Build Tools

### Rokid SDK credentials (your own only)

This project can use the **Rokid CXR SDK** for some optional features. You must use **your own** Rokid API credentials:

- Obtain **Client ID**, **Client Secret**, and **Access Key** from Rokid (e.g. developer portal or your account).
- Put them only in `local.properties` (see Setup below). **Never commit `local.properties`** or paste credentials into the repo.
- This repository contains **no** real Rokid credentials; `local.properties` is in `.gitignore` and is not shipped.

If you leave the Rokid fields empty, the app still runs; Bluetooth pairing uses standard Android APIs and does not require the SDK.

### Setup

1. Clone the repo (or open project on **H:\rokid-maps**).
2. Copy `local.properties.template` to `local.properties`.
3. Set `sdk.dir` to your Android SDK path. Optionally add **your own** Rokid credentials (see above):

```properties
sdk.dir=C\:\\Users\\YOU\\AppData\\Local\\Android\\Sdk
rokid.client.id=YOUR_CLIENT_ID
rokid.client.secret=YOUR_CLIENT_SECRET
rokid.access.key=YOUR_ACCESS_KEY
```

**Do not commit `local.properties`** — it is in `.gitignore` and may contain secrets.

### Build APKs

```bash
# From project root (e.g. H:\rokid-maps)
.\gradlew assembleDebug
# or explicitly:
.\gradlew :phone:assembleDebug :glasses:assembleDebug
```

Outputs:

- `phone/build/outputs/apk/debug/phone-debug.apk`
- `glasses/build/outputs/apk/debug/glasses-debug.apk`

### Glasses: Wi‑Fi and audio (optional)

On some devices the glasses app needs permission to toggle Wi‑Fi (for Wi‑Fi Direct from phone):

```bash
adb shell pm grant com.rokid.hud.glasses android.permission.WRITE_SECURE_SETTINGS
```

Re-run after reinstalling the glasses APK if needed.

## Protocol (JSON per line over Bluetooth SPP)

| Type        | Purpose                                          |
|-------------|---------------------------------------------------|
| `state`     | lat, lng, bearing, speed, accuracy                 |
| `route`     | waypoints, totalDistance, totalDuration           |
| `step`      | instruction, maneuver, distance                   |
| `settings`  | ttsEnabled, useImperial, useMiniMap               |
| `wifi_creds`| ssid, passphrase, enabled                         |
| `tile_req`  | glasses request tile (z, x, y, id)                 |
| `tile_resp` | phone sends tile data (id, base64)                 |
| `apk_start` / `apk_chunk` / `apk_end` | phone sends glasses APK in chunks |
| `notification` | title, text, packageName, timeMs               |

## Installing

- **Phone**: Install `phone/build/outputs/apk/debug/phone-debug.apk` on your Android phone.
- **Glasses**: Install `glasses/build/outputs/apk/debug/glasses-debug.apk` via ADB (e.g. `adb install -r glasses-debug.apk`) or use the phone app’s **Update app** to send the APK over Bluetooth and install on the glasses.

## License

Use and modify as needed. Map data: OpenStreetMap (ODbL). Tiles: CartoDB (CC BY-SA). OSRM and Nominatim are open-source services.
