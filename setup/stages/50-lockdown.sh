#!/usr/bin/env bash
# Linux-side lockdown so key mashing can't escape the kiosk or power off the box.
# (Cage itself already blocks VT switching and has no shortcuts or menus.)
set -euo pipefail

systemctl mask ctrl-alt-del.target

echo 'kernel.sysrq = 0' > /etc/sysctl.d/90-tablet.conf
sysctl -q --system

# Power/suspend keys do nothing; holding the case button still hard-powers off.
mkdir -p /etc/systemd/logind.conf.d
cat > /etc/systemd/logind.conf.d/90-tablet.conf <<'EOF'
[Login]
HandlePowerKey=ignore
HandleSuspendKey=ignore
HandleHibernateKey=ignore
HandleRebootKey=ignore
EOF

# The kid's session may not change networking, power, disks or software.
# Only login1's power/seat actions are blocked: Cage needs the rest of login1
# to activate its own session.
cat > /etc/polkit-1/rules.d/90-tablet-kid.rules <<EOF
polkit.addRule(function(action, subject) {
    if ((subject.user == "$KID_USER" || subject.isInGroup("kidsos-kids")) &&
        (action.id.indexOf("org.freedesktop.NetworkManager.") == 0 ||
         /^org\.freedesktop\.login1\.(power-off|reboot|halt|suspend|hibernate|set-reboot|set-wall-message|attach-device|flush-devices|inhibit-handle)/.test(action.id) ||
         action.id.indexOf("org.freedesktop.udisks2.") == 0 ||
         action.id.indexOf("org.freedesktop.packagekit.") == 0 ||
         action.id.indexOf("com.ubuntu.") == 0)) {
        return polkit.Result.NO;
    }
});
EOF

# Boot straight in: no GRUB menu or recovery entries to poke at.
# "quiet splash": the Kids OS boot splash instead of scrolling text.
sed -i -E \
  -e 's/^#?GRUB_TIMEOUT_STYLE=.*/GRUB_TIMEOUT_STYLE=hidden/' \
  -e 's/^#?GRUB_TIMEOUT=.*/GRUB_TIMEOUT=0/' \
  -e 's/^#?GRUB_DISABLE_RECOVERY=.*/GRUB_DISABLE_RECOVERY=true/' \
  -e 's/^#?GRUB_CMDLINE_LINUX_DEFAULT=.*/GRUB_CMDLINE_LINUX_DEFAULT="quiet splash"/' \
  /etc/default/grub
grep -q '^GRUB_DISABLE_RECOVERY=' /etc/default/grub || echo 'GRUB_DISABLE_RECOVERY=true' >> /etc/default/grub
# After a power cut Ubuntu otherwise shows the GRUB menu with a 30 s countdown.
# (A parent with a keyboard can still get it by holding Shift/Esc at boot.)
grep -q '^GRUB_RECORDFAIL_TIMEOUT=' /etc/default/grub \
  && sed -i 's/^GRUB_RECORDFAIL_TIMEOUT=.*/GRUB_RECORDFAIL_TIMEOUT=0/' /etc/default/grub \
  || echo 'GRUB_RECORDFAIL_TIMEOUT=0' >> /etc/default/grub
update-grub

# Kids OS boot splash (Plymouth): the cartoon sky with the title, plus a status
# line, so a long boot-time update ("Updating Kids OS...") doesn't look like a
# frozen screen someone might switch off. Built at the monitor's own size.
DEBIAN_FRONTEND=noninteractive apt-get -y install plymouth plymouth-themes librsvg2-bin >/dev/null
install -m 755 "$TABLET_DIR/files/tablet-screen-size" /usr/local/bin/tablet-screen-size
theme=/usr/share/plymouth/themes/kidsos
before="$( (cat "$theme"/* 2>/dev/null || true) | sha256sum)"
install -d "$theme"
install -m 644 "$TABLET_DIR/files/kidsos.plymouth" "$TABLET_DIR/files/kidsos.script" "$theme/"
size="$(tablet-screen-size)"
python3 "$TABLET_DIR/files/make-bootanimation.py" --still "${size%x*}" "${size#*x}" "$theme/splash.png.new"
cmp -s "$theme/splash.png.new" "$theme/splash.png" && rm "$theme/splash.png.new" || mv "$theme/splash.png.new" "$theme/splash.png"
# Ubuntu picks the theme through the default.plymouth alternative. The splash
# lives in the initramfs: rebuild it only when the theme changed.
default=/usr/share/plymouth/themes/default.plymouth
update-alternatives --install "$default" default.plymouth "$theme/kidsos.plymouth" 300 >/dev/null
if [[ "$(cat "$theme"/* | sha256sum)" != "$before" || "$(readlink -f "$default")" != "$theme/kidsos.plymouth" ]]; then
  update-alternatives --set default.plymouth "$theme/kidsos.plymouth"
  update-initramfs -u
fi
