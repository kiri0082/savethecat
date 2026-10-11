# Save the Cat

A kids' game for an Android TV box with a USB webcam. Characters rain down the
screen and a cat wanders along the bottom. Every child in front of the camera
holds an umbrella that follows them left and right. Stretching your arms out
opens it wider. Rain sliding off an umbrella's edges falls on the cat, and the
cat slowly melts; keep it dry for 5 s and it dries back. About every 10 s a red
poppy drifts down. If it hits the cat, or the cat steps in its melting puddle,
the cat bursts into characters.

## How the tracking works

- **Silhouettes, 30 fps:** the app learns the empty room, then treats anything
  that differs from it as a person's silhouette. A person's position is the
  middle of their body, and "arms out" is how far the silhouette reaches at
  shoulder height. Frames are brightness-matched first, because the webcam's
  exposure can't be locked.
- **Person detector, about 4 per second:** SSD MobileNet v1 (TensorFlow Lite)
  only confirms that a silhouette is a person and not a pet or a moving
  curtain. It also merges a person split into pieces.
- **Privacy:** the app has no INTERNET permission, the camera turns off when
  the app leaves the screen, and only positions are ever drawn, never video.

## Build

There's no Gradle; the build runs aapt2, javac, d8 and apksigner directly. The
toolchain paths are in `build.sh`.

    ./fetch-deps.sh     # once: TFLite runtime + detector model
    ./build.sh          # -> build/jarvis-tracker.apk
    adb install -r build/jarvis-tracker.apk

Launch it from the "JARVIS TRACK" tile. Step out of the camera's view for a
couple of seconds at the start, so it can learn the empty room.

Debug views: `am start -n com.jarvis.tracker/.TrackerActivity --es view blocks`
shows the plain block view; `--es track detector` uses the older
detector-driven tracking.
