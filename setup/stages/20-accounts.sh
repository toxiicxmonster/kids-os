#!/usr/bin/env bash
# Accounts: password-protected parent, password-less kid that GDM logs in
# automatically into the Android kiosk session. The parent's Users app
# (tablet-users) can add more kids, each with their own tablet; every kid is in
# the kidsos-kids group, and tablet-users-admin sync sets the login screen up
# for however many there are.
set -euo pipefail

if ! id "$KID_USER" &>/dev/null; then
  adduser --disabled-password --gecos "Kid" "$KID_USER"
fi
getent group kidsos-kids >/dev/null || addgroup --system kidsos-kids
usermod -aG kidsos-kids "$KID_USER"
# No password at all (not just locked) so the greeter lets the kid back in with a tap.
passwd -d "$KID_USER" >/dev/null
for g in sudo adm lpadmin netdev; do
  gpasswd -d "$KID_USER" "$g" &>/dev/null || true
done

# Parent's home (Claude Code credentials live here) is private.
parent_home="$(getent passwd "$PARENT_USER" | cut -d: -f6)"
chmod 700 "$parent_home"
chmod 750 "/home/$KID_USER"

# GDM logs the kid in with timed login, both at boot and after the parent
# switch ends the kid's session. (AutomaticLogin can't be used: it fires again
# the moment a session ends, so the parent would never reach the password
# prompt.) Touching the login screen restarts the countdown, giving the parent
# time to sign in; untouched, the kid is back in Android after the delay.
cat > /etc/gdm3/custom.conf <<EOF
[daemon]
AutomaticLoginEnable=false
TimedLoginEnable=true
TimedLogin=$KID_USER
TimedLoginDelay=10
WaylandEnable=true

[security]

[xdmcp]

[chooser]

[debug]
EOF

# Touch-only box: on-screen keyboard on the login screen for the parent
# password, and Power Off in the top-right menu (owner's choice: the kid only sees
# this screen for ~10 s and Power Off asks to confirm). Ubuntu's GDM
# ignores /etc/dconf/db/gdm.d; it compiles /usr/share/gdm/dconf at each start.
rm -rf /etc/dconf/db/gdm.d /etc/dconf/db/gdm
cat > /usr/share/gdm/dconf/90-tablet-os <<'EOF'
[org/gnome/desktop/a11y/applications]
screen-keyboard-enabled=true

[org/gnome/login-screen]
disable-restart-buttons=false
logo=''
EOF
/usr/share/gdm/generate-config

# Login screen background: the cartoon sky. GDM takes it from its theme
# gresource, so build a copy of Ubuntu's Yaru theme with the sky added and
# select it through the gdm-theme.gresource alternative. (Re-run this stage
# after a gnome-shell/Yaru upgrade so the copy matches the new theme.)
DEBIAN_FRONTEND=noninteractive apt-get -y install librsvg2-bin libglib2.0-dev-bin >/dev/null
yaru=/usr/share/gnome-shell/theme/Yaru/gnome-shell-theme.gresource
ours=/usr/share/gnome-shell/theme/tablet-os/gnome-shell-theme.gresource
work="$(mktemp -d)"
prefix=/org/gnome/shell/theme
{
  echo "<?xml version=\"1.0\" encoding=\"UTF-8\"?><gresources><gresource prefix=\"$prefix\">"
  for res in $(gresource list "$yaru"); do
    rel="${res#$prefix/}"
    mkdir -p "$work/$(dirname "$rel")"
    gresource extract "$yaru" "$res" > "$work/$rel"
    if [[ $rel == *.css ]]; then
      cat >> "$work/$rel" <<'EOF'

/* tablet-os: cartoon sky behind the login screen */
#lockDialogGroup {
  background: #2f9be8 url("resource:///org/gnome/shell/theme/tablet-sky.png");
  background-size: cover;
  background-position: center; }
EOF
    fi
    echo "<file>$rel</file>"
  done
  echo "<file>tablet-sky.png</file></gresource></gresources>"
} > "$work/theme.gresource.xml"
rsvg-convert -w 1920 -h 1080 "$TABLET_DIR/files/sky.svg" -o "$work/tablet-sky.png"
install -d "$(dirname "$ours")"
glib-compile-resources --sourcedir="$work" --target="$ours" "$work/theme.gresource.xml"
rm -rf "$work"
update-alternatives --install /usr/share/gnome-shell/gdm-theme.gresource gdm-theme.gresource "$ours" 100
update-alternatives --set gdm-theme.gresource "$ours"

# --- the Users app and the kids' themes --------------------------------------
DEBIAN_FRONTEND=noninteractive apt-get -y install python3-pil zenity gir1.2-adw-1 >/dev/null
install -m 755 "$TABLET_DIR/files/tablet-users-admin" /usr/local/sbin/tablet-users-admin
install -m 755 "$TABLET_DIR/files/tablet-users" /usr/local/bin/tablet-users
install -m 644 "$TABLET_DIR/files/tablet-users.desktop" /usr/share/applications/tablet-users.desktop
install -m 644 "$TABLET_DIR/files/org.kidsos.users.policy" /usr/share/polkit-1/actions/org.kidsos.users.policy
# Themes as pictures: the parent's desktop wallpaper, the kids' login pictures.
install -d /usr/share/tablet-os/themes
for svg in "$TABLET_DIR"/files/themes/*.svg; do
  name="$(basename "$svg" .svg)"
  install -m 644 "$svg" "/usr/share/tablet-os/themes/$name.svg"
  rsvg-convert -w 1920 -h 1080 "$svg" -o "/usr/share/tablet-os/themes/$name.png"
done
# A kid's picture follows their theme, which they can change themselves (kept
# in the Movies & TV bridge's config): refresh the login screen when it changes.
cat > /etc/systemd/system/tablet-users-sync.path <<'UNIT'
[Unit]
Description=Kids OS login screen pictures follow the kids' themes

[Path]
PathChanged=/var/lib/tablet-movies/config.json

[Install]
WantedBy=multi-user.target
UNIT
cat > /etc/systemd/system/tablet-users-sync.service <<'UNIT'
[Unit]
Description=Kids OS login screen: kids, timed login and pictures

[Service]
Type=oneshot
ExecStart=/usr/local/sbin/tablet-users-admin sync
UNIT
systemctl daemon-reload
systemctl enable --now tablet-users-sync.path
/usr/local/sbin/tablet-users-admin sync || echo "WARNING: tablet-users-admin sync failed"

# GDM only reads its config at start, but restarting it ends every graphical
# session, the kid's included: only do that when nobody is using the screen.
# (At boot, updates run before GDM starts, so they apply straight away.)
in_use=0
for s in $(loginctl list-sessions --no-legend | awk '{print $1}'); do
  [[ "$(loginctl show-session "$s" -p Type --value)" =~ ^(wayland|x11)$ ]] \
    && [[ "$(loginctl show-session "$s" -p Class --value)" == user ]] && in_use=1
done
if systemctl is-active --quiet gdm; then
  if [[ $in_use -eq 0 ]]; then
    rm -f /var/lib/tablet-os/gdm-restart-pending
    systemctl restart gdm
  else
    echo "NOTE: GDM login settings apply after the next reboot"
  fi
fi

# Parent gets GNOME (each kid's kiosk session is set by tablet-users-admin).
# Only these keys: AccountsService keeps the account's picture in the same file.
mkdir -p /var/lib/AccountsService/users
parent_accounts="/var/lib/AccountsService/users/$PARENT_USER"
[[ -f $parent_accounts ]] || echo "[User]" > "$parent_accounts"
for kv in Session=ubuntu SystemAccount=false; do
  if grep -q "^${kv%%=*}=" "$parent_accounts"; then
    sed -i "s/^${kv%%=*}=.*/$kv/" "$parent_accounts"
  else
    echo "$kv" >> "$parent_accounts"
  fi
done
