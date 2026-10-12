# Save the Cat

A kids' game for an Android TV box with a USB webcam. Keep the cat dry!

- **Rain:** characters (letters, digits, symbols) rain down the screen and
  bounce off the floor. Rain that has already bounced doesn't wet the cat.
- **The cat:** it walks along the bottom at one speed. It stops and turns
  suddenly, and gets more erratic as the game goes on.
- **Umbrellas:** every child in front of the camera gets a dark-red umbrella
  that follows them left and right. **Stretch your arms out** and it opens
  wider. Rain landing on an umbrella slides off its edges, so the spot just past
  each edge is dangerous.
- **Melting:** rain on the cat melts it gradually; it gets squashed, see-through
  and drippy. After 40 seconds of rain in total it's gone.
- **Drying:** keep it dry for 5 seconds in a row and it starts drying back,
  all the way to full size.
- **Poppies:** about every 10 seconds a red **poppy** drifts down. If it hits
  the cat, or the cat walks into the poppy's melting puddle within 2 seconds
  of it landing, the cat **bursts into characters**.
- **Timer:** a timer at the top shows how long this cat has lived. A new cat
  walks in a few seconds after one is lost.
- **Demo mode:** with nobody in view the game runs as a demo and the timer
  pauses.

The screen is grayscale apart from the umbrella and the poppy. There's no
sound.

## How the tracking works

The box is a QTV X6: Allwinner H618, 32-bit Android TV 12.

- **Silhouettes, 30 fps** (`Silhouette.java`):
  - The app learns the empty room, then treats anything that differs from it
    as a person's silhouette.
  - Each frame is brightness-matched to the background first, because this
    webcam's auto-exposure can't be locked.
  - A person's position is the middle of their body. **Arms out** means the
    silhouette's reach at shoulder height is more than 0.66 of its height.
  - Confirmed people are almost never learned into the background, so a child
    standing still stays visible.
  - A person who briefly merges with someone, or vanishes, gets the same
    umbrella back when they reappear.
- **Person detector, about 4 per second** (`Detector.java`):
  - SSD MobileNet v1 (TensorFlow Lite 2.16.1) only confirms that a silhouette
    is a person and not a pet or a moving curtain.
  - It also merges a person whose silhouette splits into pieces.
  - On this CPU one inference takes about 220 ms, and TFLite uses a single
    core whatever the thread setting.
- **Privacy:**
  - The app has **no INTERNET permission**.
  - Only positions are drawn, never video.
  - When the app leaves the screen it releases the camera and exits, so the
    camera can't stay held in the background.

## Build

There's no Gradle: `build.sh` runs aapt2, javac, d8 and apksigner. It expects
a toolchain (JDK 17 + Android SDK 34) in `~/jarvis-hud/tools/`.

```bash
./fetch-deps.sh     # once: TFLite runtime + detector model (~11 MB, not in git)
./build.sh          # -> build/jarvis-tracker.apk
adb install -r build/jarvis-tracker.apk
adb shell pm grant com.jarvis.tracker android.permission.CAMERA
```

Launch it from the **JARVIS TRACK** tile. **Step out of the camera's view for
a couple of seconds** at the start, so it learns the empty room.

## Tuning and debugging

The game's knobs are constants at the top of `GameView.java`:

| Constant | Controls |
|---|---|
| `RAIN_PER_SEC` | How heavy the rain is |
| `WET_RATE`, `DRY_AFTER`, `DRY_RATE` | How fast the cat melts and recovers |
| `UMB_HALF`, `UMB_HALF_WIDE` | Umbrella size, normal and arms out |
| `POPPY_EVERY` | How often a poppy falls |

The tracking thresholds are at the top of `Silhouette.java`.

The app logs to logcat with the tag `JarvisTrack`:
- `sil ...`: silhouettes, every 0.5 s
- `game ...`: game state, every 3 s
- `umb ...`: umbrella positions, 15 times a second

```bash
adb logcat -s JarvisTrack
```

Launch options:

| Command | What it does |
|---|---|
| `am start -n com.jarvis.tracker/.TrackerActivity --es view blocks` | Plain block view instead of the game |
| `am start -n com.jarvis.tracker/.TrackerActivity --es track detector` | Older detector-driven tracking |
| `--ei detectors N` | Number of parallel detectors |
