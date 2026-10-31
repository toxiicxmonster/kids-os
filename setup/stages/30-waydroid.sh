#!/usr/bin/env bash
# Waydroid with Google apps (Play Store). Picks hardware or software rendering.
set -euo pipefail
export DEBIAN_FRONTEND=noninteractive

if ! command -v waydroid &>/dev/null; then
  curl -fsSL https://repo.waydro.id | bash
  apt-get -y install waydroid
fi

if [[ ! -f /var/lib/waydroid/waydroid.cfg ]]; then
  echo "downloading Android (LineageOS + GApps) images, this takes a while"
  waydroid init -s GAPPS -f
fi

render="$WAYDROID_RENDER"
if [[ $render == auto ]]; then
  render=hardware
  if [[ "$(systemd-detect-virt || true)" != none ]]; then
    render=software
  elif ! lspci -nn | grep -Ei 'vga|3d|display' | grep -qEi 'intel|amd|ati'; then
    render=software
  fi
fi
echo "waydroid rendering: $render"

# Properties in waydroid.cfg are merged into the Android build props by
# `waydroid upgrade`, so they survive image updates.
cfg_before="$(sha256sum /var/lib/waydroid/waydroid.cfg)"
python3 - "$render" <<'EOF'
import configparser, sys
path = "/var/lib/waydroid/waydroid.cfg"
cfg = configparser.ConfigParser()
cfg.optionxform = str
cfg.read(path)
if not cfg.has_section("properties"):
    cfg.add_section("properties")
props = cfg["properties"]
# One full-screen Android, like a tablet.
props["persist.waydroid.multi_windows"] = "false"
if sys.argv[1] == "software":
    props["ro.hardware.gralloc"] = "default"
    props["ro.hardware.egl"] = "swiftshader"
else:
    props.pop("ro.hardware.gralloc", None)
    props.pop("ro.hardware.egl", None)
with open(path, "w") as f:
    cfg.write(f)
EOF
# Upgrading restarts Android, so only do it when the properties changed.
if [[ "$(sha256sum /var/lib/waydroid/waydroid.cfg)" != "$cfg_before" ]]; then
  waydroid upgrade --offline
fi

systemctl enable --now waydroid-container

# ARM translation: many kids' apps (PBS KIDS Games among them) ship only ARM
# code, which the Play Store calls "not compatible" on x86. libndk (from
# casualsnek/waydroid_script, pinned) adds it. libndk rather than Intel's
# libhoudini even on Intel CPUs: that build stops working after July 2028.
WS_COMMIT=48dbfaf34a6ddbe78688c530f9ba1c26522aafb2
WS_DIR=/opt/waydroid_script
marker=/var/lib/tablet-os/arm-translation.$WS_COMMIT
if [[ ! -f $marker ]]; then
  apt-get -y install lzip python3-venv >/dev/null
  [[ -d $WS_DIR/.git ]] || git clone -q https://github.com/casualsnek/waydroid_script "$WS_DIR"
  git -C "$WS_DIR" fetch -q origin
  git -C "$WS_DIR" checkout -q "$WS_COMMIT"
  python3 -m venv "$WS_DIR/venv"
  "$WS_DIR/venv/bin/pip" install -q -r "$WS_DIR/requirements.txt"
  "$WS_DIR/venv/bin/python3" "$WS_DIR/main.py" -a 13 install libndk
  install -d /var/lib/tablet-os
  touch "$marker"
fi

install -m 755 "$TABLET_DIR/files/tablet-android-id" /usr/local/bin/tablet-android-id
