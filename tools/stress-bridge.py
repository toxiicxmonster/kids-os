"""Stress + fuzz the bridge. DEV VM ONLY: it changes schedules, pauses and TV
levels, so back up /var/lib/tablet-movies first and restore it after.

    sudo cp -a /var/lib/tablet-movies /root/tm-backup
    sudo -u tablet-movies python3 tools/stress-bridge.py [reads writes fuzz idle streams]

Prints a report (over loopback, with a throwaway web page session).

Phases: concurrent reads, concurrent writes (config integrity), malformed input on
every route, idle-connection flood, then leaks (threads, fds, ffmpeg).
Never touches: power, portal-pin, link, profile, real deletes, coloring downloads.
"""
import collections, hashlib, http.client, importlib.machinery, importlib.util, json, os, random, secrets
import socket, statistics, subprocess, sys, threading, time

loader = importlib.machinery.SourceFileLoader("bridge", "/usr/local/sbin/tablet-movies-bridge")
spec = importlib.util.spec_from_loader("bridge", loader)
b = importlib.util.module_from_spec(spec)
loader.exec_module(b)

HOST, PORT = "127.0.0.1", 8766
token = secrets.token_urlsafe(24)
digest = hashlib.sha256(token.encode()).hexdigest()
with b.lock:
    cfg = b.load()
    cfg.setdefault("portal_sessions", {})[digest] = time.time() + 3600
    b.save(cfg)
COOKIE = f"{b.PORTAL_COOKIE}={token}"


def req(method, path, body=None, headers=None, raw=None, timeout=30):
    c = http.client.HTTPConnection(HOST, PORT, timeout=timeout)
    h = {"Cookie": COOKIE}
    if headers:
        h.update(headers)
    data = raw if raw is not None else (json.dumps(body).encode() if body is not None else None)
    if data is not None and "Content-Type" not in h:
        h["Content-Type"] = "application/json"
    t = time.time()
    try:
        c.request(method, path, body=data, headers=h)
        r = c.getresponse()
        payload = r.read()
        return r.status, payload, time.time() - t
    except Exception as e:
        return f"EXC {type(e).__name__}: {e}", b"", time.time() - t
    finally:
        c.close()


def bridge_pid():
    return int(subprocess.run(["systemctl", "show", "-p", "MainPID", "--value", "tablet-movies-bridge"],
                              capture_output=True, text=True).stdout.strip())


def vitals():
    pid = bridge_pid()
    status = open(f"/proc/{pid}/status").read()
    get = lambda k: next(l.split()[1] for l in status.splitlines() if l.startswith(k))
    ff = subprocess.run(["pgrep", "-c", "-x", "ffmpeg"], capture_output=True, text=True).stdout.strip()
    return {"pid": pid, "threads": int(get("Threads:")), "rss_mb": int(get("VmRSS:")) // 1024,
            "fds": len(os.listdir(f"/proc/{pid}/fd")), "meters": int(ff or 0)}


report = collections.OrderedDict()
print("before:", vitals(), flush=True)

# sample ids
movies = json.loads(req("GET", "/api/movies")[1])["movies"]
songs = json.loads(req("GET", "/api/dance")[1])["songs"]
pages = json.loads(req("GET", "/api/coloring")[1])["pages"]
mkey = movies[0]["key"] if movies else "1"
sid = songs[0]["id"] if songs else "x"
pid_ = pages[0]["id"] if pages else "1"

READS = [("GET", "/api/movies"), ("GET", "/api/dance"), ("GET", "/api/allowed"), ("GET", "/api/theme"),
         ("GET", "/api/coloring"), ("GET", f"/api/poster/{mkey}"), ("GET", f"/api/dance-thumb/{sid}"),
         ("GET", f"/api/coloring-page/{pid_}"), ("GET", "/"),
         ("GET", "/api/portal/status"), ("GET", "/api/portal/kids"), ("GET", "/api/portal/schedule"),
         ("GET", "/api/portal/week"), ("GET", "/api/portal/history"), ("GET", "/api/portal/coloring"),
         ("GET", "/api/portal/tv-levels"), ("GET", "/api/portal/setup-tv"), ("GET", "/api/portal/dance"),
         ("GET", f"/api/portal/poster/{mkey}"), ("GET", f"/api/portal/coloring-thumb/{pid_}"),
         ("GET", "/api/portal/system"), ("GET", "/api/setup"), ("GET", "/api/themes")]


def phase_reads(threads=40, per=40):
    results, lat = collections.Counter(), collections.defaultdict(list)

    def worker():
        for _ in range(per):
            m, p = random.choice(READS)
            st, _, dt = req(m, p)
            results[(p.split("/")[2] if p.startswith("/api/portal") else p.split("/")[1:3].__str__(), st)] += 1
            lat[p].append(dt)
    t0 = time.time()
    ts = [threading.Thread(target=worker) for _ in range(threads)]
    [t.start() for t in ts]
    [t.join() for t in ts]
    took = time.time() - t0
    bad = {k: v for k, v in results.items() if k[1] != 200}
    alll = sorted(x for v in lat.values() for x in v)
    slow = sorted(((statistics.median(v), p) for p, v in lat.items()), reverse=True)[:4]
    return {"requests": sum(results.values()), "seconds": round(took, 1),
            "per_s": round(sum(results.values()) / took), "non_200": bad,
            "p50_ms": round(alll[len(alll) // 2] * 1000), "p99_ms": round(alll[int(len(alll) * .99)] * 1000),
            "slowest_median_ms": [(round(m * 1000), p) for m, p in slow]}


def phase_writes(threads=20, per=15):
    """Concurrent config writers + readers; then the config must be intact."""
    errors = collections.Counter()
    sched = json.loads(req("GET", "/api/portal/schedule")[1])["schedule"]

    def worker(i):
        for j in range(per):
            op = random.choice(["sched", "levels", "theme", "extra", "pause", "read", "activity", "item"])
            if op == "sched":
                st = req("POST", "/api/portal/schedule", {**sched, "daily": random.choice([0, 60, 120])})[0]
            elif op == "levels":
                st = req("POST", "/api/portal/tv-levels", {"plex": random.randint(0, 100)})[0]
            elif op == "theme":
                st = req("POST", f"/api/theme/{random.choice(b.THEMES)}")[0]
            elif op == "extra":
                st = req("POST", "/api/portal/extra", {"minutes": 1})[0]
            elif op == "pause":
                st = req("POST", "/api/portal/pause", {"minutes": random.choice([0, 15])})[0]
            elif op == "activity":
                st = req("POST", f"/api/activity?title=Stress{i}")[0]
            elif op == "item":  # add then remove a title already on the list: a no-op pair
                st = req("POST", f"/api/portal/items/{mkey}")[0]
            else:
                st = req("GET", "/api/portal/status")[0]
            if st != 200:
                errors[(op, st)] += 1
    ts = [threading.Thread(target=worker, args=(i,)) for i in range(threads)]
    [t.start() for t in ts]
    [t.join() for t in ts]
    intact = True
    try:
        c = json.load(open(b.CONFIG))
        intact = isinstance(c.get("movies"), list) and len(c["movies"]) == len(movies) and "portal_pin" in c
    except Exception as e:
        intact = f"BROKEN {e!r}"
    for side in (b.USAGE, b.HISTORY, b.TV_LEVELS):
        try:
            json.load(open(side))
        except FileNotFoundError:
            pass
        except Exception as e:
            intact = f"BROKEN {side}: {e!r}"
    req("POST", "/api/portal/schedule", sched)
    req("POST", "/api/portal/pause", {"minutes": 0})
    return {"errors": dict(errors), "config_intact": intact}


GARBAGE_BODIES = [b"", b"{", b"[]", b"[1,2]", b"null", b"42", b'"str"', b"{\"a\":", b"\xff\xfe\x00",
                  json.dumps({"minutes": "lots"}).encode(), json.dumps({"minutes": None}).encode(),
                  json.dumps({"minutes": 10**30}).encode(), json.dumps({"minutes": -5}).encode(),
                  json.dumps({"percent": "50"}).encode(), json.dumps({"level": [1]}).encode(),
                  json.dumps({"step": 10**9}).encode(), json.dumps({"mute": "yes"}).encode(),
                  json.dumps({"enabled": "x", "from": "25:99", "to": None, "daily": "z"}).encode(),
                  json.dumps({"plex": 1e999 if False else 1.5, "netflix": -1, "youtube": "x"}).encode(),
                  json.dumps({"pin": "x" * 5000}).encode(), json.dumps({"ref": "../../etc/passwd"}).encode(),
                  json.dumps({"url": "file:///etc/passwd"}).encode(), json.dumps({"title": {"a": 1}}).encode(),
                  json.dumps({"action": "nope"}).encode()]
GARBAGE_ARGS = ["", "..%2F..%2Fetc%2Fpasswd", "../../etc/passwd", "%00", "a" * 3000, "-1", "99999999999999999999",
                "%E2%9C%93", "null", "c-%2e%2e", "u" + "f" * 12, "nf", "nf12x"]
FUZZ_ROUTES = [  # (method, path prefix) -- destructive ones only with garbage that can't match anything
    ("GET", "/api/poster/"), ("GET", "/api/dance-thumb/"), ("GET", "/api/coloring-page/"), ("POST", "/api/theme/"),
    ("POST", "/api/play-local/"), ("POST", "/api/dance-local/"), ("GET", "/api/hls/"), ("POST", "/api/hls-stop/"),
    ("POST", "/api/hls-next/"), ("GET", "/api/hls-gain/"), ("GET", "/api/android-fix/"), ("POST", "/api/activity"),
    ("POST", "/api/parent-kid/"), ("POST", "/api/themes/"), ("DELETE", "/api/movies/"), ("POST", "/api/movies/"),
    ("GET", "/api/nosuch/"), ("PUT", "/api/movies"),
    ("POST", "/api/portal/schedule"), ("POST", "/api/portal/extra"), ("POST", "/api/portal/pause"),
    ("POST", "/api/portal/volume"), ("POST", "/api/portal/tv-volume"), ("POST", "/api/portal/tv-levels"),
    ("POST", "/api/portal/login"), ("GET", "/api/portal/poster/"), ("GET", "/api/portal/dance-thumb/"),
    ("GET", "/api/portal/coloring-thumb/"), ("GET", "/api/portal/coloring-page/"), ("DELETE", "/api/portal/items/"),
    ("DELETE", "/api/portal/dance/"), ("DELETE", "/api/portal/coloring/"), ("POST", "/api/portal/items/"),
    ("POST", "/api/portal/netflix"), ("POST", "/api/portal/dance-link"), ("POST", "/api/portal/power"),
    ("GET", "/api/portal/history?kid="), ("GET", "/api/portal/week?kid="), ("GET", "/api/portal/nosuch"),
    ("POST", "/api/portal/coloring-upload?title="), ("POST", "/api/portal/airplay-pair"),
]


def phase_fuzz():
    fails = []
    n = 0
    for method, prefix in FUZZ_ROUTES:
        for arg in GARBAGE_ARGS:
            if prefix.endswith("power") or prefix.endswith("login"):
                continue
            st, body, _ = req(method, prefix + (arg if prefix.endswith(("/", "=")) else ""))
            n += 1
            if not isinstance(st, int) or st >= 500:
                fails.append((method, prefix, arg[:30], st, body[:120]))
        for raw in GARBAGE_BODIES:
            if prefix.endswith("login") and b"pin" not in raw:
                continue  # wrong PINs lock the page; one long-PIN try is enough
            if prefix.endswith("power") and b"nope" not in raw:
                continue
            st, body, _ = req(method, prefix + ("x" if prefix.endswith("/") else ""), raw=raw)
            n += 1
            if not isinstance(st, int) or st >= 500:
                fails.append((method, prefix, raw[:30], st, body[:120]))
    # bad headers
    for cl in ["abc", "-5", "99999999999", "1e3"]:
        st, body, _ = req("POST", "/api/portal/schedule", headers={"Content-Length": cl}, raw=b"{}", timeout=10)
        n += 1
        if not isinstance(st, int) or st >= 500:
            fails.append(("POST", "schedule", f"Content-Length {cl}", st, body[:120]))
    st, body, _ = req("GET", "/api/portal/status", headers={"Cookie": "a=b; ;;=; kidsos_portal"})
    n += 1
    if not isinstance(st, int) or st >= 500:
        fails.append(("GET", "status", "odd cookie", st, body[:120]))
    st, body, _ = req("GET", "/" + "a" * 70000, timeout=10)
    n += 1
    if not isinstance(st, int):
        fails.append(("GET", "long path", "", st, body[:120]))
    return {"requests": n, "failures": fails}


def phase_idle_flood(count=300):
    """Idle connections (a stuck phone, or someone on the Wi-Fi): does the bridge still answer?"""
    socks = []
    for _ in range(count):
        s = socket.create_connection((HOST, PORT))
        s.sendall(b"GET /api/allowed HTTP/1.1\r\n")  # never finished
        socks.append(s)
    time.sleep(1)
    mid = vitals()
    st, _, dt = req("GET", "/api/allowed", timeout=10)
    time.sleep(70)
    later = vitals()
    for s in socks:
        s.close()
    time.sleep(2)
    return {"while_flooded": (st, round(dt * 1000)), "threads_flooded": mid["threads"],
            "threads_after_70s": later["threads"], "threads_after_close": vitals()["threads"]}


def phase_streams(cycles=6):
    """Start and stop a tablet stream again and again (meters, Plex transcodes)."""
    req("POST", "/api/portal/pause", {"minutes": 30})
    out = collections.Counter()
    show = next((m["key"] for m in movies if m.get("kind") == "movie"), mkey)
    for _ in range(cycles):
        st, body, _ = req("POST", f"/api/play-local/{show}", timeout=60)
        r = json.loads(body or b"{}") if st == 200 else {}
        s = (r.get("stream") or "").split("/")[1] if r.get("stream") else ""
        out[("play", st, r.get("result"))] += 1
        if s:
            st2, m3u8, _ = req("GET", f"/api/hls/{s}/start.m3u8", timeout=60)
            out[("m3u8", st2)] += 1
            req("POST", f"/api/hls-stop/{s}")
        time.sleep(1)
    req("POST", "/api/portal/pause", {"minutes": 0})
    time.sleep(5)
    return {str(k): v for k, v in out.items()}


which = sys.argv[1:] or ["reads", "writes", "fuzz", "idle", "streams"]
try:
    for name in which:
        t = time.time()
        report[name] = globals()["phase_" + {"reads": "reads", "writes": "writes", "fuzz": "fuzz",
                                              "idle": "idle_flood", "streams": "streams"}[name]]()
        print(f"== {name} ({time.time() - t:.0f}s):", json.dumps(report[name], default=str, indent=1), flush=True)
finally:
    with b.lock:
        cfg = b.load()
        cfg.get("portal_sessions", {}).pop(digest, None)
        b.save(cfg)
    print("after:", vitals(), flush=True)
