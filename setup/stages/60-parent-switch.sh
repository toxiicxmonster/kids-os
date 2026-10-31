#!/usr/bin/env bash
# Hidden way out for the parent: tap the four screen corners clockwise from
# top-left within 6 s (or hold Ctrl+Alt+Shift+P if a keyboard is plugged in).
# That ends the kid's session and leaves GDM's login screen, where the parent
# signs in with the on-screen keyboard; after 30 s idle it logs the kid back in.
set -euo pipefail

# In a VM, clicks arrive from an absolute pointer rather than a touchscreen.
pointers=0
[[ "$(systemd-detect-virt || true)" != none ]] && pointers=1

# Screen sleep (in the same daemon): pactl tells whether Android is playing
# sound (then the screen stays on); Tablet Home does the darkening.
DEBIAN_FRONTEND=noninteractive apt-get -y install pulseaudio-utils >/dev/null
install -m 755 "$TABLET_DIR/files/tablet-parent-switch" /usr/local/sbin/tablet-parent-switch
cat > /etc/systemd/system/tablet-parent-switch.service <<EOF
[Unit]
Description=Tablet OS hidden parent switch (corner taps / Ctrl+Alt+Shift+P) and screen sleep
After=systemd-udev-settle.service

[Service]
Environment=KID_USER=$KID_USER
Environment=TABLET_SWITCH_POINTERS=$pointers
Environment=TABLET_IDLE_MINUTES=10
ExecStart=/usr/local/sbin/tablet-parent-switch
Restart=always
RestartSec=2

[Install]
WantedBy=multi-user.target
EOF
systemctl daemon-reload
systemctl enable tablet-parent-switch.service
systemctl restart tablet-parent-switch.service
