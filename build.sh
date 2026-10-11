#!/bin/bash
# Builds jarvis-tracker.apk without Gradle: aapt2 -> javac -> d8 -> sign.
# Toolchain lives in ~/jarvis-hud/tools (JDK 17 + Android SDK 34).
set -euo pipefail
cd "$(dirname "$0")"

TOOLS=$HOME/jarvis-hud/tools
export JAVA_HOME="$TOOLS/jdk-17.0.20.1+1/Contents/Home"
BT="$TOOLS/sdk/build-tools/34.0.0"
ANDROID_JAR="$TOOLS/sdk/platforms/android-34/android.jar"
KEYSTORE="$TOOLS/debug.keystore"

rm -rf build gen classes
mkdir -p build gen classes build/jars build/lib/armeabi-v7a assets

# on-device detector: runtime jars + 32-bit native lib (the box's userland is
# armeabi-v7a) + model as an asset. Fetch once with ./fetch-deps.sh
[ -f deps/ssd_mobilenet_v1.tflite ] || { echo "run ./fetch-deps.sh first"; exit 1; }
for a in tensorflow-lite tensorflow-lite-api; do
  unzip -qo -p deps/$a-2.16.1.aar classes.jar > build/jars/$a.jar
done
unzip -qo -j deps/tensorflow-lite-2.16.1.aar jni/armeabi-v7a/libtensorflowlite_jni.so -d build/lib/armeabi-v7a
rm -f assets/*.tflite && cp deps/ssd_mobilenet_v1.tflite assets/
JARS=build/jars/tensorflow-lite.jar:build/jars/tensorflow-lite-api.jar

# debug signing key (created once)
if [ ! -f "$KEYSTORE" ]; then
  "$JAVA_HOME/bin/keytool" -genkeypair -keystore "$KEYSTORE" -storepass jarvishud \
    -keypass jarvishud -alias jarvishud -keyalg RSA -keysize 2048 -validity 10000 \
    -dname "CN=jarvis-hud"
fi

"$BT/aapt2" compile --dir res -o build/res.zip
"$BT/aapt2" link -o build/base.apk -I "$ANDROID_JAR" \
  --manifest AndroidManifest.xml -R build/res.zip --java gen -A assets \
  --min-sdk-version 24 --target-sdk-version 34

"$JAVA_HOME/bin/javac" -source 11 -target 11 -classpath "$ANDROID_JAR:$JARS" \
  -d classes gen/com/jarvis/tracker/R.java src/com/jarvis/tracker/*.java

"$BT/d8" --release --min-api 24 --lib "$ANDROID_JAR" --output build \
  "classes/com/jarvis/tracker/"*.class build/jars/*.jar

cd build && zip -qj base.apk classes*.dex && zip -qr base.apk lib && cd ..

"$BT/zipalign" -f 4 build/base.apk build/aligned.apk
"$BT/apksigner" sign --ks "$KEYSTORE" --ks-pass pass:jarvishud \
  --out build/jarvis-tracker.apk build/aligned.apk

echo "Built: $(pwd)/build/jarvis-tracker.apk"
