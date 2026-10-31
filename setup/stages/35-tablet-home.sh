#!/usr/bin/env bash
# Kid's home screen and parent mode:
#  1. One Android for both logins: the data moves to a shared folder that the
#     kid's and the parent's ~/.local/share/waydroid/data point at.
#  2. Builds Tablet Home (setup/android) and installs it as Android's launcher
#     and device owner, which lets it lock the kid into the carousel.
#  3. Hides the apps in android-removed-apps.txt.
set -euo pipefail
export DEBIAN_FRONTEND=noninteractive

SHARED=/var/lib/tablet-os/android-data
SDK=/opt/android-sdk
HEADLESS_RUNTIME=/run/kidsos-headless

install -d -m 755 /var/lib/tablet-os /usr/local/share/tablet-os
install -m 755 "$TABLET_DIR/files/tablet-parent-android" /usr/local/bin/tablet-parent-android
install -m 644 "$TABLET_DIR/files/tablet-parent-android.desktop" /usr/share/applications/tablet-parent-android.desktop
install -m 644 "$TABLET_DIR/files/tablet-parent-logout.desktop" /usr/share/applications/tablet-parent-logout.desktop
# Put it on the parent's desktop too (done at each parent login, see the script).
install -m 755 "$TABLET_DIR/files/tablet-parent-desktop" /usr/local/bin/tablet-parent-desktop
cat > /etc/xdg/autostart/tablet-parent-desktop.desktop <<EOF
[Desktop Entry]
Type=Application
Name=Kid Tablet desktop icon
Exec=env TABLET_PARENT_USER=$PARENT_USER /usr/local/bin/tablet-parent-desktop
OnlyShowIn=GNOME;
NoDisplay=true
X-GNOME-Autostart-Phase=Applications
EOF

android_running() { waydroid status 2>/dev/null | grep -q 'Session:.*RUNNING'; }
android_ready() { android_running && waydroid shell -- getprop sys.boot_completed 2>/dev/null | grep -q 1; }

# --- 1. shared Android data ---------------------------------------------------
needs_move=0
for user in "$KID_USER" "$PARENT_USER"; do
  data="$(getent passwd "$user" | cut -d: -f6)/.local/share/waydroid/data"
  [[ -d $data && ! -L $data ]] && needs_move=1
done
if [[ $needs_move -eq 1 ]]; then
  # Android must be stopped while its data moves.
  loginctl terminate-user "$KID_USER" 2>/dev/null || true
  for _ in $(seq 30); do android_running || break; sleep 1; done
  waydroid session stop 2>/dev/null || true
fi
for user in "$KID_USER" "$PARENT_USER"; do
  home="$(getent passwd "$user" | cut -d: -f6)"
  data="$home/.local/share/waydroid/data"
  if [[ -d $data && ! -L $data ]]; then
    if [[ ! -e $SHARED ]]; then
      echo "moving $user's Android data to $SHARED"
      mv "$data" "$SHARED"
    else
      echo "keeping $SHARED; setting $user's own Android data aside"
      mv "$data" "$data.unused-$(date +%s)"
    fi
  fi
  sudo -u "$user" mkdir -p "$home/.local/share/waydroid"
  ln -sfn "$SHARED" "$data"
  chown -h "$user:" "$data"
done
# Android's system uid (1000) owns /data; a fresh one gets populated on first boot.
[[ -d $SHARED ]] || install -d -m 771 -o 1000 -g 1000 "$SHARED"

# --- 2. build Tablet Home -----------------------------------------------------
apt-get -y install openjdk-17-jdk-headless unzip zip
if [[ ! -x $SDK/build-tools/34.0.0/aapt2 ]]; then
  tmp="$(mktemp -d)"
  curl -fsSL -o "$tmp/tools.zip" https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip
  mkdir -p "$SDK/cmdline-tools"
  unzip -q "$tmp/tools.zip" -d "$tmp"
  rm -rf "$SDK/cmdline-tools/latest"
  mv "$tmp/cmdline-tools" "$SDK/cmdline-tools/latest"
  rm -rf "$tmp"
  yes | "$SDK/cmdline-tools/latest/bin/sdkmanager" --sdk_root="$SDK" --licenses >/dev/null || true
  "$SDK/cmdline-tools/latest/bin/sdkmanager" --sdk_root="$SDK" "platforms;android-33" "build-tools;34.0.0"
fi
APPS=(tablet-home movies dance)
for app in "${APPS[@]}"; do
  ANDROID_SDK_ROOT=$SDK bash "$TABLET_DIR/android/build.sh" "$app" "/usr/local/share/tablet-os/$app.apk"
  # Each kid's own Android installs a newer build at their next login (tablet-android).
  grep -o 'android:versionCode="[0-9]*"' "$TABLET_DIR/android/$app/AndroidManifest.xml" | grep -o '[0-9]*'     > "/usr/local/share/tablet-os/$app.version"
done

# --- 3. install into Android ----------------------------------------------------
headless_session=0
if ! android_running; then
  # The kid's session usually comes back on its own (GDM timed login); if it
  # doesn't (e.g. first install, before any reboot), boot Android headless.
  # (Not when GDM isn't up, e.g. during the boot-time updater.)
  if systemctl is-active --quiet gdm; then
    for _ in $(seq 45); do android_running && break; sleep 1; done
  fi
  if ! android_running; then
    # Waydroid needs a Wayland compositor even with no screen: Cage's headless
    # backend. With nobody logged in (first boot) there's no login session, so
    # supply what one would: a runtime dir, a session bus (dbus-run-session),
    # software rendering (no GPU access without a session) and a placeholder
    # audio socket (Waydroid bind-mounts pulse/native and fails if it's missing).
    install -d -m 700 -o "$PARENT_USER" "$HEADLESS_RUNTIME" "$HEADLESS_RUNTIME/pulse"
    [[ -e $HEADLESS_RUNTIME/pulse/native ]] || install -m 600 -o "$PARENT_USER" /dev/null "$HEADLESS_RUNTIME/pulse/native"
    sudo -u "$PARENT_USER" XDG_RUNTIME_DIR="$HEADLESS_RUNTIME" WLR_BACKENDS=headless WLR_RENDERER=pixman \
      WLR_LIBINPUT_NO_DEVICES=1 setsid dbus-run-session -- cage -- waydroid session start \
      >/var/log/tablet-os-headless-android.log 2>&1 &
    headless_session=1
  fi
fi
for _ in $(seq 300); do android_ready && break; sleep 2; done
android_ready || {
  echo "Android did not boot; re-run: sudo ./install.sh 35 (log: /var/log/tablet-os-headless-android.log)" >&2
  exit 1
}

for app in "${APPS[@]}"; do
  install -D -m 644 "/usr/local/share/tablet-os/$app.apk" "$SHARED/local/tmp/$app.apk"
  waydroid shell -- pm install -r "/data/local/tmp/$app.apk"
  rm -f "$SHARED/local/tmp/$app.apk"
done

if waydroid shell -- dpm list-owners | grep -q org.tabletos.home; then
  echo "Tablet Home is already device owner"
elif ! waydroid shell -- dpm set-device-owner org.tabletos.home/.AdminReceiver; then
  echo "WARNING: couldn't make Tablet Home device owner (Android only allows it with no" >&2
  echo "Google account signed in). The carousel works, but without the kiosk lock." >&2
fi
waydroid shell -- cmd package set-home-activity org.tabletos.home/.HomeActivity
# The floating home button over other apps (and screen sleep) is a system overlay.
waydroid shell -- appops set org.tabletos.home SYSTEM_ALERT_WINDOW allow
# Android's "restricted networking mode" can cut our apps off from the Movies &
# TV bridge (seen in the dev VM: the home screen's bedtime checks timed out).
waydroid shell -- settings put global restricted_networking_mode 0

# Tablet behaviour: gesture navigation with no buttons and no hint bar (swipe
# up from the bottom edge goes home), and no rotation of any kind; apps that
# ask for portrait get letterboxed instead of turning the screen.
waydroid shell -- cmd overlay enable-exclusive --user 0 --category com.android.internal.systemui.navbar.gestural
waydroid shell -- cmd overlay enable --user 0 org.lineageos.overlay.customization.navbar.nohint
waydroid shell -- settings put system accelerometer_rotation 0
waydroid shell -- settings put system user_rotation 0
waydroid shell -- wm fixed-to-user-rotation enabled
waydroid shell -- wm set-ignore-orientation-request true

# "Kids OS" boot animation in place of LineageOS's, through Waydroid's overlay
# (files under overlay/ are mounted over Android's read-only system image; /product
# lives in /system/product). Android doesn't scale it, so build it at the screen
# size; re-run this stage if the screen changes (e.g. after cloning to the Dell).
# The monitor's native mode; Android's own size is Cage's default 1280x720 when
# it was started without a screen (first boot, boot-time updates).
install -m 755 "$TABLET_DIR/files/tablet-screen-size" /usr/local/bin/tablet-screen-size
size="$(tablet-screen-size)"
overlay_media=/var/lib/waydroid/overlay/system/product/media
install -d "$overlay_media"
python3 "$TABLET_DIR/files/make-bootanimation.py" "${size%x*}" "${size#*x}" "$overlay_media/bootanimation.zip"
ln -sfn bootanimation.zip "$overlay_media/bootanimation-dark.zip"

install -m 755 "$TABLET_DIR/files/tablet-android-apps" /usr/local/bin/tablet-android-apps
install -D -m 644 "$TABLET_DIR/files/android-removed-apps.txt" /etc/tablet-os/android-removed-apps.txt
tablet-android-apps apply

waydroid shell -- am start -a android.intent.action.MAIN -c android.intent.category.HOME >/dev/null
if [[ $headless_session -eq 1 ]]; then
  sudo -u "$PARENT_USER" XDG_RUNTIME_DIR="$HEADLESS_RUNTIME" waydroid session stop || true
  sleep 3
  rm -rf "$HEADLESS_RUNTIME"
fi
