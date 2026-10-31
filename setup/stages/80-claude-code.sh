#!/usr/bin/env bash
# Claude Code for the parent account only, plus a copy of the project brief and
# these setup scripts in the parent's home so future sessions have context.
set -euo pipefail

parent_home="$(getent passwd "$PARENT_USER" | cut -d: -f6)"

if ! sudo -u "$PARENT_USER" -i bash -c 'command -v claude' &>/dev/null; then
  sudo -u "$PARENT_USER" -i bash -c 'curl -fsSL https://claude.ai/install.sh | bash'
fi

mkdir -p "$parent_home/tablet-os"
if [[ "$(realpath "$TABLET_DIR/..")" != "$(realpath "$parent_home/tablet-os")" ]]; then
  cp -r "$TABLET_DIR" "$parent_home/tablet-os/"
  [[ -f "$TABLET_DIR/../CLAUDE.md" ]] && cp "$TABLET_DIR/../CLAUDE.md" "$parent_home/tablet-os/"
fi
chown -R "$PARENT_USER:" "$parent_home/tablet-os"
chmod 700 "$parent_home"
