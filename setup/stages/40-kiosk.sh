#!/usr/bin/env bash
# Kid's login session: Cage (a single-app Wayland compositor with no desktop,
# no shortcuts and VT switching off) running Waydroid's full Android UI.
set -euo pipefail

install -m 755 "$TABLET_DIR/files/tablet-kiosk-session" /usr/local/bin/tablet-kiosk-session
install -m 755 "$TABLET_DIR/files/tablet-android" /usr/local/bin/tablet-android
install -m 755 "$TABLET_DIR/files/tablet-splash" /usr/local/bin/tablet-splash
install -m 644 "$TABLET_DIR/files/tablet-kiosk.desktop" /usr/share/wayland-sessions/tablet-kiosk.desktop

# Sound card at full volume and unmuted (saved, so it's restored at boot); the
# kid session puts its own output at 100% too, leaving Android's volume as the
# only control.
for control in Master PCM "Line Out" Speaker Headphone; do
  amixer -q -c 0 sset "$control" 100% unmute 2>/dev/null || true
done
alsactl store 2>/dev/null || true

# Touch panels the kernel misreports (e.g. the Dell's 1ddb:0001, which shows up
# as a pen that libinput then ignores): classify them as touchscreens.
install -m 644 "$TABLET_DIR/files/90-kidsos-touchpanel.rules" /etc/udev/rules.d/90-kidsos-touchpanel.rules
udevadm control --reload
udevadm trigger --action=change --subsystem-match=input

# ...and smooth over the momentary lifts some panels report mid-drag (the
# Dell's), which otherwise turn a scroll into taps. Exits quietly without one.
install -m 755 "$TABLET_DIR/files/tablet-touch-debounce" /usr/local/sbin/tablet-touch-debounce
cat > /etc/systemd/system/tablet-touch-debounce.service <<'EOF'
[Unit]
Description=Kids OS touch debounce (drags that blip into taps)
Before=display-manager.service tablet-parent-switch.service

[Service]
ExecStart=/usr/local/sbin/tablet-touch-debounce
Restart=on-failure
RestartSec=2

[Install]
WantedBy=multi-user.target
EOF
systemctl daemon-reload
systemctl enable tablet-touch-debounce.service
systemctl restart tablet-touch-debounce.service
