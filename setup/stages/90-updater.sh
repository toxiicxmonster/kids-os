#!/usr/bin/env bash
# Boot-time updates from the private kids-os GitHub repo (see files/tablet-update).
# The box follows one branch: the dev VM follows "main", the Dell "stable".
# Needs a read-only deploy key for the repo; this stage makes one and prints its
# public half, which goes into GitHub (repo Settings > Deploy keys, read-only).
set -euo pipefail

REPO="${TABLET_REPO:-git@github.com:toxiicxmonster/kids-os.git}"
BRANCH="${TABLET_BRANCH:-stable}"

apt-get -y install git openssh-client >/dev/null
install -d -m 755 /etc/tablet-os
if [[ ! -f /etc/tablet-os/deploy_key ]]; then
  ssh-keygen -q -t ed25519 -N "" -C "kids-os $(hostname) read-only" -f /etc/tablet-os/deploy_key
fi
chmod 600 /etc/tablet-os/deploy_key
# Keep the branch if this box already has one (the VM is on main).
if [[ -f /etc/tablet-os/update.conf && -z "${TABLET_BRANCH:-}" ]]; then
  BRANCH="$(sed -n 's/^BRANCH=//p' /etc/tablet-os/update.conf)"
fi
cat > /etc/tablet-os/update.conf <<EOF
REPO=$REPO
BRANCH=$BRANCH
PARENT_USER=$PARENT_USER
EOF

install -m 755 "$TABLET_DIR/files/tablet-update" /usr/local/sbin/tablet-update
cat > /etc/systemd/system/tablet-update.service <<'EOF'
[Unit]
Description=Kids OS: apply updates from GitHub before the login screen
Wants=network-online.target
After=network-online.target
Before=display-manager.service

[Service]
Type=oneshot
ExecStart=/usr/local/sbin/tablet-update
TimeoutStartSec=45min
StandardOutput=journal

[Install]
WantedBy=multi-user.target
EOF
systemctl daemon-reload
systemctl enable tablet-update.service

echo "Updates follow branch '$BRANCH' of $REPO. Read-only deploy key for GitHub:"
cat /etc/tablet-os/deploy_key.pub
