#!/usr/bin/env bash
# Tablet OS setup: turns a fresh Ubuntu 24.04 (server or desktop) install into
# the toddler tablet. Safe to re-run; each stage is idempotent.
#
#   sudo ./install.sh              # run every stage
#   sudo ./install.sh 30 40        # run only stages whose names start with 30, 40
#
# Settings can be overridden with env vars, e.g. PARENT_USER=bob sudo -E ./install.sh
set -euo pipefail

export TABLET_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
export PARENT_USER="${PARENT_USER:-${SUDO_USER:-parent}}"
export KID_USER="${KID_USER:-kid}"
export FAMILY_DNS="${FAMILY_DNS:-1.1.1.3 1.0.0.3}"
# auto | hardware | software. "auto" picks software rendering in a VM or on NVIDIA-only machines.
export WAYDROID_RENDER="${WAYDROID_RENDER:-auto}"

if [[ $EUID -ne 0 ]]; then
  echo "run with sudo" >&2
  exit 1
fi
if ! id "$PARENT_USER" &>/dev/null; then
  echo "parent user '$PARENT_USER' does not exist" >&2
  exit 1
fi

exec > >(tee -a /var/log/tablet-os-setup.log) 2>&1
echo "=== tablet-os setup $(date -Is) parent=$PARENT_USER kid=$KID_USER ==="

for stage in "$TABLET_DIR"/stages/*.sh; do
  name="$(basename "$stage")"
  if [[ $# -gt 0 ]]; then
    want=0
    for prefix in "$@"; do [[ $name == "$prefix"* ]] && want=1; done
    [[ $want -eq 1 ]] || continue
  fi
  echo "--- $name"
  bash "$stage"
done

echo "=== done. Reboot to start the kid session: sudo reboot ==="
