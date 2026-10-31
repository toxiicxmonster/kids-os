#!/usr/bin/env bash
# Builds one of the Android apps in this folder with the bare SDK tools (no Gradle).
#   ./build.sh <app-dir> [output.apk]      e.g. ./build.sh tablet-home
# Needs: a JDK, and an Android SDK with platform android-33 and build-tools
# (stage 35 installs both into /opt/android-sdk).
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SDK="${ANDROID_SDK_ROOT:-/opt/android-sdk}"
BT="$SDK/build-tools/34.0.0"
PLATFORM="$SDK/platforms/android-33/android.jar"
KEYSTORE="${TABLET_HOME_KEYSTORE:-/etc/tablet-os/tablet-home.keystore}"
src="$(cd "$here/${1:?app folder, e.g. tablet-home}" && pwd)"
out="${2:-$here/$(basename "$src").apk}"
build="$(mktemp -d)"
trap 'rm -rf "$build"' EXIT

# Every app gets the sky background, rendered from the same SVG as the login screen's.
mkdir -p "$build/res"
[[ -d "$src/res" ]] && cp -r "$src/res/." "$build/res/"
mkdir -p "$build/res/drawable-nodpi"
rsvg-convert -w 1920 -h 1080 "$here/../files/sky.svg" -o "$build/res/drawable-nodpi/sky.png"
# ...and the kids' themes (files/themes/<name>.svg -> R.drawable.theme_<name>).
for theme in "$here"/../files/themes/*.svg; do
  name="$(basename "$theme" .svg)"
  rsvg-convert -w 1920 -h 1080 "$theme" -o "$build/res/drawable-nodpi/theme_$name.png"
done
python3 "$here/make-sounds.py" "$build/res/raw"
if [[ -f "$src/icon.svg" ]]; then
  mkdir -p "$build/res/mipmap-nodpi"
  rsvg-convert -w 512 -h 512 "$src/icon.svg" -o "$build/res/mipmap-nodpi/ic_launcher.png"
fi
"$BT/aapt2" compile --dir "$build/res" -o "$build/res.zip"
"$BT/aapt2" link -o "$build/unsigned.apk" -I "$PLATFORM" \
  --manifest "$src/AndroidManifest.xml" --java "$build/gen" "$build/res.zip"

mkdir -p "$build/classes" "$build/dex"
javac -source 11 -target 11 -Xlint:-options -encoding UTF-8 \
  -classpath "$PLATFORM" -d "$build/classes" \
  $(find "$src/src" "$build/gen" -name '*.java')
"$BT/d8" --release --min-api 30 --lib "$PLATFORM" --output "$build/dex" \
  $(find "$build/classes" -name '*.class')
(cd "$build/dex" && zip -q "$build/unsigned.apk" classes.dex)

# The same key must sign every build, or Android refuses the update.
if [[ ! -f "$KEYSTORE" ]]; then
  install -d -m 700 "$(dirname "$KEYSTORE")"
  keytool -genkeypair -keystore "$KEYSTORE" -alias tablethome -keyalg RSA -keysize 2048 \
    -validity 20000 -storepass tabletos -keypass tabletos -dname "CN=Tablet OS" >/dev/null
  chmod 600 "$KEYSTORE"
fi
"$BT/zipalign" -f 4 "$build/unsigned.apk" "$build/aligned.apk"
"$BT/apksigner" sign --ks "$KEYSTORE" --ks-pass pass:tabletos --key-pass pass:tabletos \
  --out "$out" "$build/aligned.apk"
echo "built $out"
