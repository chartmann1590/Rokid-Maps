# Rokid HUD Maps

Dual-app Android project: **phone** streams GPS, turn-by-turn navigation, and notifications to **Rokid AR glasses** over Bluetooth. The glasses show a live map, directions, and route; TTS can play through the glasses via Bluetooth audio.

## Architecture

```
┌─────────────────────────────────┐     Bluetooth SPP      ┌─────────────────────────────────┐
│         Phone App               │ ◄─────────────────────► │        Glasses App              │
│  • GPS @ 1Hz                    │   JSON-per-line        │  • Live map (CartoDB dark)      │
│  • OSRM routing (free, no key)  │   protocol             │  • Turn-by-turn + distance      │
│  • Nominatim search             │                        │  • Route line + compass         │
│  • BT SPP server + A2DP audio   │   Wi‑Fi creds (opt.)   │  • Wi‑Fi auto-connect (tiles)   │
│  • Wi‑Fi Direct hotspot         │ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─► │  • BT/Wi‑Fi status, 2 layouts  │
│  • TTS → Bluetooth audio        │                        │  • Notifications                │
│  • Notification relay           │                        │                                │
└─────────────────────────────────┘                        └─────────────────────────────────┘
```

## Modules

| Module   | Package                  | Description                                      |
|----------|--------------------------|--------------------------------------------------|
| `shared` | `com.rokid.hud.shared`    | Protocol (state, route, step, settings, wifi)   |
| `phone`  | `com.rokid.hud.phone`    | Search, routing, streaming, Wi‑Fi share, TTS    |
| `glasses`| `com.rokid.hud.glasses`  | HUD map, directions, Wi‑Fi connector, BT client |

## Features

### Phone app

- **Search** — Nominatim (OpenStreetMap), no API key
- **Turn-by-turn** — OSRM routing; start/stop navigation; re-route on deviation
- **Streaming** — Start streaming to start BT SPP server; glasses auto-connect when paired
- **Pair glasses** — Bluetooth device scan; bond and select device for SPP
- **Saved places** — Save current destination; view and pick from saved list
- **Voice directions (TTS)** — Reads instructions aloud; routes audio to glasses via **Bluetooth A2DP** (enable in settings and ensure BT audio is connected)
- **Wi‑Fi Direct** — Creates hotspot and sends SSID/password to glasses so they can load map tiles
- **Units** — Imperial (miles/feet) or metric (km/m)
- **Notifications** — Forward notifications to glasses (Notification Access permission)

### Glasses app

- **Live map** — CartoDB Dark Matter tiles; green tint; rotates with heading
- **Route** — Current step, distance, route line; “You have arrived!” on destination
- **Status** — BT and Wi‑Fi connection indicators
- **Layouts** — Tap to switch FULL (map 72%) / MINI (small map corner)
- **Wi‑Fi** — Receives creds over BT; enables Wi‑Fi and connects (needs `WRITE_SECURE_SETTINGS` granted via ADB on some devices)

## Building

### Prerequisites

- JDK 17+
- Android SDK (API 34 recommended)
- Android Build Tools

### Setup

1. Clone the repo (or open project on **H:\rokid-maps**).
2. Copy `local.properties.template` to `local.properties`.
3. Set `sdk.dir` to your Android SDK path. Optionally set Rokid credentials if using the SDK AAR:

```properties
sdk.dir=C\:\\Users\\YOU\\AppData\\Local\\Android\\Sdk
rokid.clientId=YOUR_CLIENT_ID
rokid.clientSecret=YOUR_CLIENT_SECRET
rokid.accessKey=YOUR_ACCESS_KEY
```

**Do not commit `local.properties`** — it is in `.gitignore`.

### Build APKs

```bash
# From project root (e.g. H:\rokid-maps)
.\gradlew.bat :phone:assembleDebug :glasses:assembleDebug
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

| Type        | Purpose                          |
|------------|-----------------------------------|
| `state`    | lat, lng, bearing, speed, accuracy |
| `route`    | waypoints, totalDistance, totalDuration |
| `step`     | instruction, maneuver, distance   |
| `settings` | ttsEnabled, useImperial           |
| `wifi_creds` | ssid, passphrase, enabled       |
| `notification` | title, text, packageName, timeMs |

## License

Use and modify as needed. Map data: OpenStreetMap (ODbL). Tiles: CartoDB (CC BY-SA). OSRM and Nominatim are open-source services.
