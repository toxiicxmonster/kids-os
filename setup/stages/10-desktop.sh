#!/usr/bin/env bash
# Minimal GNOME desktop (for the parent account and the GDM login screen),
# NetworkManager in charge of networking, and the tools the later stages need.
set -euo pipefail
export DEBIAN_FRONTEND=noninteractive

apt-get update
apt-get -y full-upgrade
apt-get -y install ubuntu-desktop-minimal gdm3 network-manager \
  cage git curl ca-certificates python3-evdev nftables lxc \
  pciutils usbutils inxi libinput-tools librsvg2-bin libglib2.0-dev-bin

# Firefox for the parent account (snap on 24.04).
snap list firefox &>/dev/null || snap install firefox

# Server installs hand networking to systemd-networkd; the desktop (and the
# family-DNS stage) expect NetworkManager. Keep the installer's own netplan file
# (on a Wi-Fi-only box it holds the network name and password) and make
# NetworkManager the renderer for everything; its definitions don't set one.
if ! grep -qs NetworkManager /etc/netplan/01-network-manager-all.yaml; then
  cat > /etc/netplan/01-network-manager-all.yaml <<'EOF'
network:
  version: 2
  renderer: NetworkManager
EOF
  chmod 600 /etc/netplan/01-network-manager-all.yaml
  mkdir -p /etc/cloud/cloud.cfg.d
  echo 'network: {config: disabled}' > /etc/cloud/cloud.cfg.d/99-disable-network-config.cfg
  netplan generate
  # Applied on reboot; switching renderers live would drop an SSH session.
  echo "NOTE: networking switches to NetworkManager on next reboot"
fi
# networkd manages nothing now; its wait-online would stall every boot for 2 min.
if grep -qs NetworkManager /etc/netplan/01-network-manager-all.yaml; then
  systemctl disable systemd-networkd.service systemd-networkd.socket systemd-networkd-wait-online.service 2>/dev/null || true
  systemctl mask systemd-networkd-wait-online.service
fi

systemctl set-default graphical.target

# Stay on this Ubuntu release: no "upgrade to the new LTS" pop-ups. Waydroid's
# repo, the login theme patch and these scripts all target this release, so a
# release upgrade is a deliberate job for later, not a click on a notification.
# (Normal and security updates still apply.)
sed -i 's/^Prompt=.*/Prompt=never/' /etc/update-manager/release-upgrades
