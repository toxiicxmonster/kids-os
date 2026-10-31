#!/usr/bin/env bash
# Packs the parent's Kids OS session from a configured box (e.g. the dev VM) for
# the USB installer: Android data (apps, Google sign-in, carousel choices), the
# Movies settings (Plex sign-in, TV, movie list), the app signing key and the
# Android images.
#   sudo tools/export-session.sh /var/tmp/kidsos-session
# Android is stopped while its data is copied (the kid session ends; GDM's timed
# login brings it back afterwards). The result holds sign-ins: keep it private.
set -euo pipefail

OUT="${1:-/var/tmp/kidsos-session}"
[[ $EUID -eq 0 ]] || { echo "run with sudo" >&2; exit 1; }
install -d -m 700 "$OUT" "$OUT/images"

echo "stopping Android for a consistent copy"
systemctl stop gdm
waydroid session stop 2>/dev/null || true
systemctl stop waydroid-container
systemctl stop tablet-movies-bridge

restart() {
  systemctl start tablet-movies-bridge waydroid-container gdm
}
trap restart EXIT

tar --zstd -cpf "$OUT/android-data.tar.zst" --numeric-owner --xattrs --xattrs-include='*' \
  -C /var/lib/tablet-os android-data
install -m 600 /var/lib/tablet-movies/config.json "$OUT/tablet-movies.json"
install -m 600 /etc/tablet-os/tablet-home.keystore "$OUT/tablet-home.keystore"
cp /var/lib/waydroid/images/system.img /var/lib/waydroid/images/vendor.img "$OUT/images/"
chmod -R go-rwx "$OUT"
du -sh "$OUT"/* "$OUT"/images/*
