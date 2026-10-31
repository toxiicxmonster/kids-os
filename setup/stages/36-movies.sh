#!/usr/bin/env bash
# Movies & TV: the bridge service that plays approved Plex (and Netflix) titles on the chosen Roku
# TV (the Movies Android app itself is built and installed by stage 35).
set -euo pipefail

# Pillow: Netflix posters are made from Netflix's wide title art.
DEBIAN_FRONTEND=noninteractive apt-get -y install python3-pil python3-venv >/dev/null

# Dance app: yt-dlp lists the Danny Go! channel's songs (titles, lengths; no
# downloads). Its own venv, kept current weekly: YouTube changes often.
[[ -x /opt/kidsos-ytdlp/bin/yt-dlp ]] || python3 -m venv /opt/kidsos-ytdlp
/opt/kidsos-ytdlp/bin/pip install -q -U yt-dlp
cat > /etc/systemd/system/kidsos-ytdlp-update.service <<'EOF'
[Unit]
Description=Kids OS: update yt-dlp (Dance app song list)
After=network-online.target
Wants=network-online.target

[Service]
Type=oneshot
ExecStart=/opt/kidsos-ytdlp/bin/pip install -q -U yt-dlp
EOF
cat > /etc/systemd/system/kidsos-ytdlp-update.timer <<'EOF'
[Unit]
Description=Kids OS: update yt-dlp weekly

[Timer]
OnCalendar=weekly
Persistent=true
RandomizedDelaySec=1h

[Install]
WantedBy=timers.target
EOF
systemctl daemon-reload
systemctl enable --now kidsos-ytdlp-update.timer

id tablet-movies &>/dev/null || adduser --system --group --home /var/lib/tablet-movies --no-create-home tablet-movies
install -d -m 700 -o tablet-movies -g tablet-movies /var/lib/tablet-movies
install -m 755 "$TABLET_DIR/files/tablet-movies-bridge" /usr/local/sbin/tablet-movies-bridge
# The parents' web portal (http://<hostname>.local:8766/): pause movie time, edit the list.
install -D -m 644 "$TABLET_DIR/files/movies-portal.html" /usr/local/share/tablet-os/movies-portal.html

cat > /etc/systemd/system/tablet-movies-bridge.service <<EOF
[Unit]
Description=Kids OS Movies bridge (Plex to Roku TV)
After=network-online.target
Wants=network-online.target

[Service]
User=tablet-movies
Environment=PARENT_USER=$PARENT_USER
ExecStart=/usr/local/sbin/tablet-movies-bridge
Restart=always
RestartSec=3
NoNewPrivileges=yes
ProtectSystem=strict
ProtectHome=yes
ReadWritePaths=/var/lib/tablet-movies
PrivateTmp=yes

[Install]
WantedBy=multi-user.target
EOF
# The portal's kid-volume slider: the bridge (no privileges) drops a request
# file, and this root helper passes it to Tablet Home inside Android.
install -m 755 "$TABLET_DIR/files/tablet-volume" /usr/local/sbin/tablet-volume
cat > /etc/systemd/system/tablet-volume.path <<'EOF'
[Unit]
Description=Kids OS kid volume requests from the web portal

[Path]
PathChanged=/var/lib/tablet-movies/volume-request

[Install]
WantedBy=multi-user.target
EOF
cat > /etc/systemd/system/tablet-volume.service <<'EOF'
[Unit]
Description=Kids OS kid volume from the web portal

[Service]
Type=oneshot
ExecStart=/usr/local/sbin/tablet-volume --request
EOF

systemctl daemon-reload
systemctl enable --now tablet-volume.path
systemctl enable tablet-movies-bridge.service
systemctl restart tablet-movies-bridge.service
