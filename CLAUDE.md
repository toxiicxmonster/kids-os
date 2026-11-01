# Kids OS: developer notes

How it fits together, and the details that took a while to learn. (A box's own
notes, with household specifics, can live in `CLAUDE.local.md`, which is
gitignored.)

## Workflow

- **Branches**: `main` is development (a dev VM follows it), `stable` is what
  real boxes run. Test on the VM, then fast-forward `stable` to `main`.
- **Updates**: `tablet-update` (before GDM at boot, and by hand) fetches the
  branch, diffs, and re-runs only affected stages: a changed `stages/NN-*.sh`,
  the stages that install a changed `files/<name>` (by grep), stage 35 for
  `android/` and `files/themes/`. Stages that didn't finish are retried until
  they do (`/var/lib/tablet-os/update-pending-stages`). Every run writes
  `/var/lib/tablet-os/last-update` ("<epoch> <commit> <result>").
- **Stages must be idempotent** and safe to re-run while a kid is playing:
  never restart GDM while any user session is on screen (it ends the kid's).
- **Dev VM** (VirtualBox): EFI, VMSVGA 3D, USB multi-touch, SSH on a forwarded
  port. With Windows Hypervisor Platform, never take live snapshots (the VM
  hangs); snapshot only when powered off.

## The pieces

- **Kid session**: GDM session `tablet-kiosk` → `tablet-kiosk-session` (Cage, with
  a pixman fallback if the GPU renderer dies) → `tablet-android` (Waydroid
  session, splash until `sys.boot_completed` and `ce_available`, installs newer
  app builds into this kid's Android, `show-full-ui`).
- **Accounts**: every kid is a password-less user in group `kidsos-kids` (a tap
  on the login screen logs them in). One kid: GDM TimedLogin straight into
  them. Several: the login screen lists them, each with a picture made from
  their theme and initial (`tablet-users-admin sync`). Each kid has their own
  Android data (`/var/lib/tablet-os/android-data[-<user>]`, symlinked from
  `~/.local/share/waydroid/data`).
- **Tablet Home** (`android/tablet-home`): device owner, lock task, the carousel,
  parent mode (`persist.waydroid.tablet_mode=parent`), floating home bubble,
  theme picker, the sleeping screen (polls the bridge's `/api/allowed`), the
  screen-sleep overlay (`ScreenReceiver`) and the kid volume (`VolumeReceiver`).
  Both receivers require the DUMP permission (shell/system only).
- **Bridge** (`files/tablet-movies-bridge`, Python, user `tablet-movies`, port
  8766): Movies & TV, Dance, themes, schedules, history and the parents' web
  page (`files/movies-portal.html`). Android reaches it at 192.168.240.1;
  parent routes need loopback or the parent on the desktop; the web page
  needs a home-network client and the PIN. Per-kid state goes through
  `KidView` (lists) and `kid_settings` (schedule, overrides); minute-by-minute
  usage and history live in their own files (config writes re-trigger the
  login-screen sync).
- **Parent switch** (`files/tablet-parent-switch`, root, evdev): corner taps /
  key combo end the kids' sessions (SIGTERM everything but
  `gdm-session-worker`; `loginctl terminate-user` would break GDM), restarts GDM
  for pending login-screen changes when the screen is free, and does screen
  sleep.

## Hard-won details

- **Plex on a Roku**: a remote `playMedia` only plays cleanly right after a fresh
  start with a profile picked on Plex's "Who's watching?" screen (with Plex
  "Automatically sign in" off). So: ECP Home, launch Plex, wait for the
  companion port, Select, then `playMedia` with the profile's server token and
  a play queue. With a token to an already-open Plex it shows the user screen
  over the video; without one, a managed profile ignores it. Offset 0 is taken
  as "resume", so a watcher seeks to 0 if playback starts >15 s in.
- **Roku ECP** needs "Control by mobile apps: Default" (Limited blocks
  keypresses, app lists and `/query/media-player`). `media-player` state and
  position are the only reliable "is it really playing" signal; Plex's own
  timeline `location` always says "navigation".
- **Netflix**: `launch/12?contentId=<id>&mediaType=series|movie`; it resumes, so
  the bridge rewinds (Rev x3, then Play as soon as `media-player` shows the
  position under 3 s or stopped near 0; ~position/5 is only the fallback
  limit) when it starts >15 s in.
  Title, art and show/movie come from the public title page (og: tags; the
  TVSeries marker is ~2 MB in, read incrementally).
- **YouTube** (Dance): `launch/837?contentID=<id>` opens on a profile picker
  ~15-25 s later; Select there, never while playing (OK = pause). Ads run on
  their own position counter before the song; YouTube's position stops a few
  seconds short of the listed length. Song lists come from `yt-dlp
  --flat-playlist` on a channel (one request, no downloads); single-video
  extraction trips YouTube's bot check and isn't used (title via oEmbed,
  length from the watch page when present).
- **Android audio**: `DISALLOW_ADJUST_VOLUME` is enforced with AudioFlinger
  master mute, i.e. no sound at all. Don't use it. Also set ALSA and the
  kid's PipeWire sink to 100% so Android's volume is the only control.
- **Touch panels** that report a pen "Stylus" without resolution are ignored by
  libinput: reclassify with udev (`90-kidsos-touchpanel.rules`). Some report
  20-35 ms lifts mid-drag, which Android sees as taps: `tablet-touch-debounce`
  grabs the panel and re-emits a clean uinput touchscreen.
- **loginctl** prints `-p` properties in its own order: parse `key=value`.
- **Cage 0.1.5** (Ubuntu 24.04) has no wlr-output-power-management, so screen
  sleep is a black Android overlay rather than monitor power-off.
- **Android "restricted networking mode"** can cut apps off from the bridge;
  stage 35 turns it off.
- **Waydroid headless** (first boot, no session): needs Cage's headless backend,
  pixman, `dbus-run-session`, its own `XDG_RUNTIME_DIR` and a placeholder
  `pulse/native`; the boot animation is sized from the monitor's native mode.
- **GDM on Ubuntu** reads `/usr/share/gdm/dconf` (not `/etc/dconf/db/gdm.d`);
  the login background is a patched Yaru gresource via `update-alternatives`;
  Plymouth's theme also goes through `update-alternatives` + `update-initramfs`.
- **ARM apps** need libndk (casualsnek/waydroid_script, pinned); libhoudini
  builds expire.
- **Cloudflare-fronted Plex servers** reject Python's default user agent.
