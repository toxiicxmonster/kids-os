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
- **Watching on the tablet**: while movie time is paused or no TV is set up
  (`tablet_mode`, `"local"` in `/api/movies` and `/api/dance`), picks play on
  the tablet. `POST /api/play-local/<key>` / `dance-local/<id>` check tablet
  time and the list. Netflix titles are dimmed (TV only).
  - **Plex** plays in Movies & TV's own `PlayerActivity` (themed "Here it
    comes!" until the first frame, big 🏠 and ⏸/▶, no seek bar; a show goes on
    to its next episode in order via `POST /api/hls-next/<session>`, while
    tablet time and the list allow; a movie goes back to the posters). The bridge streams it: `/api/hls/<session>/…` passes
    each HLS request to the server's universal transcoder as the kid's profile
    (offset 0, screen-sized, ≤3 Mbps h264/aac), so no Plex token reaches
    Android. The transcoder answers `start.m3u8` with a bare 400 unless
    `decision` was asked first with the same query. The player is a
    SurfaceView with its own MediaPlayer on a separate thread, never a
    VideoView: Android's software h264 decoder can hang while flushing, and
    VideoView's `reset()` on the UI thread then froze the whole app (ANR).
    A hung decoder also blocks every later video, so the player watches for
    it (a MediaPlayer call stuck 10 s, no picture 25 s after prepared, a
    release that never returned) and asks the bridge (`/api/android-fix`,
    kid route) for a fix by root `tablet-android-restart` (.path unit):
    first `media` (kill the OMX HAL and `media.swcodec`; init restarts them;
    not mediaserver, which stayed unregistered), then the video starts over
    with a fresh transcode; if that fails, "Oops! A system restart is
    needed" and `restart` (the Waydroid container; `tablet-android`'s loop
    brings Android back under the splash). Rate-limited (1 min / 10 min),
    only while a kid is on screen; the last fix shows on the web page.
    The Plex Android app
    can't be used: none of its links start a title (tested on the Dell).
  - **Dance** hands the song to YouTube Kids (one song, no party):
    `https://kids.youtube.com/watch?v=<id>` starts it at once (youtube.com
    links don't resolve there). Tablet Home puts YouTube Kids (and Plex) on
    the lock-task allowlist (`PlayerCover.PLAYERS`) without adding them to the
    carousel; after the hand-off Dance broadcasts to Tablet Home's
    `PlayerCover`: a themed overlay swallows every tap until media sound has
    run 1.5 s, with the floating home button (`HomeBubble`) on top while the
    player is open; after 90 s with no sound it says to tap home.
- **Paint** (`android/paint`): fill/brush on coloring pages or blank paper,
  undo (wipe included), each page's painting saved in the kid's Android. The
  bridge downloads each page once, makes it pure black lines on white
  (threshold 170, lines thickened so fills don't leak) and keeps it in
  `/var/lib/tablet-movies/coloring/`. Sources: **Crayola's free coloring pages**
  (personal use, so downloaded to the box only, never committed or shown in
  the README; page names from their sitemap, cached a week; the picture is the
  one `coloring-page-images/` image on the print page), ids `c-<name>`;
  Openclipart (public domain) ids are numbers; the parents' own pages (a link
  or a file upload, SVG via rsvg-convert) are `u<12 hex>`. A Crayola starter
  set is added on first run (`coloring_starter`: 2 replaced the first
  Openclipart set). Parents manage the shared list on the web page's Coloring
  tab. publicdomainpictures.net search is behind a Cloudflare challenge, but
  its picture links work through "Add your own page".
- **Carousel order**: the app opened most recently first (Tablet Home prefs
  `used_<package>`, so per kid). `BUILT_IN` packages (ours, plus Khan Academy
  Kids) are switched on once when first seen.
- **Favorites** in Movies & TV: titles the kid picked twice or more in the last
  30 days come first (up to 8); the web page has a "This week" card
  (`/api/portal/week`).
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
  TVSeries marker is ~2 MB in, read incrementally). That art is wide, and the
  title's own portrait boxshot isn't on its page, so shows get their poster
  from TVmaze (`api.tvmaze.com/search/shows`, no key; best match only if the
  name is close). Movies, or shows TVmaze can't match, fall back to the wide
  art composited onto a 2:3 card.
- **Kid lists** (Movies & TV, Dance) have a `ScrollRail` on the right (▲, a
  draggable track, ▼), so a kid can scroll without touching a poster. The
  same class is copied into each app's package, so keep the copies in step.
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
- **LG webOS TVs** (`cfg["tv"]["kind"] == "lg"`; Roku is the default): SSAP over
  a WebSocket (wss 3001, else ws 3000; a minimal stdlib client in the bridge),
  found by SSDP `urn:lge-com:service:webos-second-screen:1`. Choosing one
  pairs (Accept on the TV; the client key goes in the config, never to
  Android). Netflix: `launch netflix` with `contentId
  m=https://www.netflix.com/watch/<id>&source_type=4`; YouTube:
  `youtube.leanback.v4` with `contentTarget https://www.youtube.com/tv?v=<id>`;
  Plex (`cdp-30`): close, launch, ENTER on "Who's watching?", then Companion
  playMedia directly (ports 32500/8324) or relayed by the server
  (`/clients` by IP), then seekTo 0. Buttons go over the pointer socket.
  Play state is `com.webos.media/getForegroundAppInfo`; the LG gives no
  position, so there's no Netflix rewind and a dance party plays only its
  first song. `--lg` is the on-box tester. On a real LG (NU710BAUA,
  2026-11-09): pairing, buttons, Netflix and YouTube work; **Plex doesn't**.
  Its webOS app has no Companion port, no GDM reply, isn't in the server's
  `/clients` and has no remote-control setting; the phone app casts to it
  only over **AirPlay** (the TV's foreground app is `airplay`; Accept + code
  once per device). ENTER 12 s after launch came before "Who's watching?".
  - **So Plex on an LG goes over AirPlay** (`files/tablet-airplay`, venv
    `/opt/kidsos-airplay`, pinned pyatv 0.18.0 + srptools 1.0.1: 2.0 breaks
    pairing). The bridge sends the TV the same HLS stream as the tablet's
    player (1080p, 8 Mbps, from the start, the kid's profile) and lets only
    that TV fetch only that stream. Pair once: `--lg airplay-pair` (PIN on
    the TV; credentials in `cfg["tv"]["airplay"]`, never in a reply).
    Unpaired, the old Plex-app route is the fallback. Roku is unchanged.
    A TV's stream asks as "Plex for Android": as Plex Web the transcoder
    gives HLS MP3 audio (whatever the profile extra says), silent on the LG.
    The LG ignores pyatv's unicast `hosts=` scan; scan the network.
  - The LG is AirPlay video v2 only: `POST /play` is a 404. What works (as
    macOS does it, cf. pyatv PR #2941): a second SETUP for a remote-control
    stream (type 130, controlType 1), then `POST /command` with
    `insertPlayQueueItem` (mediaType "streaming" for HLS), `actionAtItemEnd`,
    `setRate`; reports come back as `/command` on the event channel.
    Closing the connection stops playback (that's Stop). At the end of a
    show's episode (`itemPlayedToEnd`, or "stopped" within 15 s of the end)
    the bridge sends the next one, unless there was a newer pick, a pause or
    the kid's time ran out. On a Roku, Plex's own app autoplays the next one.
