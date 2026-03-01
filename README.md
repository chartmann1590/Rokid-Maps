# Rokid HUD Maps

Turn-by-turn navigation on your Rokid AR glasses, powered by your phone.

Your phone handles all the heavy lifting — GPS, route calculations, address search — and streams everything to your glasses over Bluetooth. You get a live map with your route drawn on it, step-by-step directions, and even your phone notifications, all floating in your field of view. No cloud services, no API keys, no subscriptions. It's all free and open-source under the hood.

## How It Works

There are two apps: one runs on your Android phone, the other on your Rokid glasses. They talk to each other over Bluetooth SPP (Serial Port Profile), sending JSON messages back and forth — one per line, nice and simple.

The phone is the brain. It grabs your GPS location once per second, calculates routes using [OSRM](http://project-osrm.org/) (a free routing engine), and searches for addresses with [Nominatim](https://nominatim.openstreetmap.org/) (OpenStreetMap's geocoder). When you start streaming, the phone opens a Bluetooth server and waits for your glasses to connect. Once they do, everything flows automatically.

The glasses are the display. They render a dark-themed map (CartoDB Dark Matter tiles with a green tint — looks great on AR glass), show your current navigation step, draw your route, and rotate the map to match the direction you're facing. If the glasses don't have Wi-Fi for downloading map tiles, the phone acts as a proxy — the glasses request tiles over Bluetooth, the phone fetches them from the internet, and sends them back.

```
┌──────────────────────────┐    Bluetooth SPP     ┌──────────────────────────┐
│       Phone App          │ ◄──────────────────►  │      Glasses App         │
│                          │   JSON messages       │                          │
│  GPS tracking @ 1Hz      │   + tile proxy        │  Live rotating map       │
│  OSRM routing            │   + APK updates       │  Turn-by-turn HUD        │
│  Nominatim search        │   + settings sync     │  Route line overlay      │
│  BT server + A2DP audio  │                       │  Phone notifications     │
│  TTS voice directions    │                       │  3 layout modes          │
└──────────────────────────┘                       └──────────────────────────┘
```

## What You Can Do

### On the Phone

- **Search for places** — Type an address or place name and pick from the results. Uses OpenStreetMap data, no API key needed.
- **Get turn-by-turn directions** — Routes are calculated with OSRM. If you go off-route, it automatically recalculates. When you arrive, it tells you.
- **See a map while navigating** — The phone shows a map and live directions while you're navigating (hides when you're not, keeps the UI clean).
- **Save places** — Found a spot you like? Save it. Long-press to delete. Simple.
- **Voice directions** — TTS reads out your turns and routes audio to your glasses via Bluetooth A2DP. Toggle it in settings.
- **Forward notifications** — Your texts, emails, whatever — they show up on your glasses. Requires notification access permission.
- **Push app updates to glasses** — Pick an APK file on your phone and send it to the glasses over Bluetooth. No need to plug in a cable or use ADB (though you still can if you want).
- **Share internet with glasses** — Send your phone's hotspot credentials to the glasses so they can download map tiles directly instead of going through the Bluetooth proxy.
- **Imperial or metric** — Your choice.
- **Mini map mode** — Toggle from the phone to switch the glasses to a compact layout: small map at the bottom, just the direction and distance, no notifications.
- **Keeps running in the background** — Uses a WakeLock so your GPS and Bluetooth keep working when the phone screen turns off. It'll ask about battery optimization so Android doesn't kill it.

### On the Glasses

- **Live map** — Rotates with your heading, renders dark-themed tiles with a green HUD overlay. If there's no Wi-Fi, tiles come through the phone via Bluetooth.
- **Navigation display** — Shows the current instruction, maneuver arrow, and distance to the next turn. Shows a checkmark and "You have arrived!" when you get there.
- **Route line** — Your full route drawn on the map with a glowing green line.
- **Compass** — Shows which way is north and your current bearing in degrees.
- **Three layouts** — Tap the screen to cycle through them:
  - **Full** — Map takes up ~72% of the screen, directions and notifications below
  - **Corner** — Small map in the bottom-right corner, text on the left
  - **Mini** — Compact strip at the bottom (toggled from phone settings)
- **Phone notifications** — Scrolling list below the directions, shows title and preview text.
- **Status indicators** — BT and Wi-Fi connection status in the top-left corner.
- **Auto-connect Wi-Fi** — When the phone sends hotspot credentials, the glasses automatically enable Wi-Fi and connect.

## Project Structure

```
rokid-maps/
├── shared/    Bluetooth protocol — message types, JSON encoding/decoding
├── phone/     Phone app — search, routing, streaming service, BT server
└── glasses/   Glasses app — HUD rendering, BT client, tile manager
```

## Building

### What You Need

- JDK 17+
- Android SDK (API 34)

### Rokid SDK (Optional)

The app can optionally use the Rokid CXR SDK for device features. If you have Rokid developer credentials, put them in `local.properties`. If you don't, everything still works — Bluetooth pairing uses standard Android APIs.

### Setup

1. Clone the repo
2. Copy `local.properties.template` to `local.properties`
3. Set your Android SDK path. Optionally add your own Rokid credentials:

```properties
sdk.dir=C\:\\Users\\YOU\\AppData\\Local\\Android\\Sdk
rokid.client.id=YOUR_CLIENT_ID
rokid.client.secret=YOUR_CLIENT_SECRET
rokid.access.key=YOUR_ACCESS_KEY
```

**Don't commit `local.properties`** — it's already in `.gitignore`.

### Build

```bash
./gradlew assembleDebug
```

APKs end up in:
- `phone/build/outputs/apk/debug/phone-debug.apk`
- `glasses/build/outputs/apk/debug/glasses-debug.apk`

### Glasses Wi-Fi Permission (Some Devices)

If Wi-Fi toggling doesn't work on your glasses, grant this via ADB:

```bash
adb shell pm grant com.rokid.hud.glasses android.permission.WRITE_SECURE_SETTINGS
```

## Installing

- **Phone** — Install the phone APK like any other Android app.
- **Glasses** — Either use `adb install -r glasses-debug.apk`, or use the phone app's "Update app" button to send it over Bluetooth.

## The Protocol

Everything goes over Bluetooth SPP as one JSON object per line. Here's what gets sent:

| Message | What It Does |
|---------|-------------|
| `state` | GPS position, bearing, speed, accuracy — sent once per second |
| `route` | Full list of waypoints, total distance and duration |
| `step` | Current instruction, maneuver type, distance to next turn |
| `settings` | TTS on/off, imperial/metric, mini map toggle |
| `wifi_creds` | Hotspot SSID and password for the glasses to connect |
| `tile_req` / `tile_resp` | Glasses ask for a map tile, phone fetches and returns it |
| `apk_start` / `apk_chunk` / `apk_end` | Glasses app update sent in chunks from the phone |
| `notification` | Forwarded phone notification with title, text, and source app |

## What It Uses

All free, all open-source:

- **[OSRM](http://project-osrm.org/)** — Routing engine (no API key)
- **[Nominatim](https://nominatim.openstreetmap.org/)** — Address search (no API key)
- **[OpenStreetMap](https://www.openstreetmap.org/)** — Map data (ODbL license)
- **[CartoDB](https://carto.com/basemaps/)** — Dark Matter tiles (CC BY-SA)
- **[osmdroid](https://github.com/osmdroid/osmdroid)** — Android map library

## License

Use it, modify it, build on it. Map data from OpenStreetMap (ODbL). Tiles from CartoDB (CC BY-SA).
