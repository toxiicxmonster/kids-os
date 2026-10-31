#!/usr/bin/env python3
"""Builds the "Kids OS" Android boot animation (bootanimation.zip).

    make-bootanimation.py WIDTH HEIGHT OUT.zip
    make-bootanimation.py --still WIDTH HEIGHT OUT.png   (one frame, for the boot splash)

Draws each frame as SVG (same cartoon sky as sky.svg: bouncing rainbow
letters, drifting clouds, a turning sun, bouncing dots) and renders it with
rsvg-convert. Android doesn't scale boot animations, so build it at the
screen's own size.
"""
import math
import pathlib
import subprocess
import sys
import tempfile
import zipfile

FRAMES = 36
FPS = 18
TITLE = "Kids OS"
LETTER_COLORS = ["#ff5252", "#ff9800", "#ffd600", "#4caf50", "#2196f3", "#7c4dff", "#ec407a"]

CLOUD = (
    "M40 0 Q-10 0 10 -45 Q20 -95 85 -85 Q100 -150 175 -140 Q215 -185 275 -150 "
    "Q345 -165 350 -95 Q410 -85 395 -35 Q395 0 345 0 Z"
)


def cloud(x, y, scale):
    return (
        f'<g transform="translate({x:.1f} {y:.1f}) scale({scale})">'
        f'<path d="{CLOUD}" fill="#fff" stroke="#9fd3f2" stroke-width="10" stroke-linejoin="round"/>'
        '<path d="M40 -6 Q15 -6 22 -28 Q120 -10 360 -30 Q385 -12 345 -6 Z" fill="#e3f4fd"/></g>'
    )


def frame_svg(w, h, i):
    t = i / FRAMES  # 0..1 around the loop
    u = min(w / 1920, h / 1080)  # layout unit
    parts = [
        f'<svg xmlns="http://www.w3.org/2000/svg" width="{w}" height="{h}" viewBox="0 0 {w} {h}">',
        '<defs><linearGradient id="sky" x1="0" y1="0" x2="0" y2="1">'
        '<stop offset="0" stop-color="#2f9be8"/><stop offset="0.6" stop-color="#6cc4f5"/>'
        '<stop offset="1" stop-color="#b8e6fc"/></linearGradient>'
        '<radialGradient id="glow"><stop offset="0" stop-color="#fff6a8" stop-opacity="0.9"/>'
        '<stop offset="1" stop-color="#fff6a8" stop-opacity="0"/></radialGradient></defs>',
        f'<rect width="{w}" height="{h}" fill="url(#sky)"/>',
    ]

    # Sun, rays turning one step per loop so the loop is seamless.
    sx, sy, sr = w - 220 * u, 190 * u, 90 * u
    parts.append(f'<circle cx="{sx}" cy="{sy}" r="{sr * 2.5}" fill="url(#glow)"/>')
    rays = []
    for k in range(8):
        a = math.radians(k * 45 + t * 45)
        rays.append(
            f'<line x1="{sx + math.cos(a) * sr * 1.28:.1f}" y1="{sy + math.sin(a) * sr * 1.28:.1f}" '
            f'x2="{sx + math.cos(a) * sr * 1.67:.1f}" y2="{sy + math.sin(a) * sr * 1.67:.1f}"/>'
        )
    parts.append(f'<g stroke="#ffcf3f" stroke-width="{18 * u}" stroke-linecap="round">{"".join(rays)}</g>')
    parts.append(f'<circle cx="{sx}" cy="{sy}" r="{sr}" fill="#ffe14d" stroke="#ffbf1f" stroke-width="{10 * u}"/>')

    # Clouds drifting left; each moves a whole multiple of the width per loop.
    for base_x, y, scale, laps in [(0.05, 0.23, 0.9, 1), (0.45, 0.16, 0.6, 2), (0.7, 0.36, 0.7, 1),
                                   (0.0, 0.97, 1.3, 1), (0.35, 1.0, 1.1, 1), (0.7, 0.95, 1.25, 1)]:
        span = w + 500 * u
        x = ((base_x * span - t * laps * span) % span) - 450 * u
        parts.append(cloud(x, y * h, scale * u))

    # Title: one centred line (so the font's own letter widths apply), each
    # letter bobbing on its own phase via relative dy shifts.
    size = 230 * u
    cy = h * 0.5
    offsets = [math.sin(2 * math.pi * t - k * 0.7) * 28 * u for k in range(len(TITLE))]
    attrs = (f'x="{w / 2:.1f}" y="{cy:.1f}" font-family="Ubuntu Sans, Ubuntu, sans-serif" '
             f'font-weight="800" font-size="{size:.0f}" text-anchor="middle" letter-spacing="{6 * u:.1f}"')

    def spans(colored):
        out, prev = [], 0.0
        for k, ch in enumerate(TITLE):
            fill = f' fill="{LETTER_COLORS[k % len(LETTER_COLORS)]}"' if colored else ""
            out.append(f'<tspan dy="{offsets[k] - prev:.1f}"{fill}>{ch}</tspan>')
            prev = offsets[k]
        return "".join(out)

    parts.append(f'<text {attrs} fill="none" stroke="#0d47a1" stroke-width="{30 * u:.1f}" '
                 f'stroke-linejoin="round" xml:space="preserve">{spans(False)}</text>')
    parts.append(f'<text {attrs} stroke="#fff" stroke-width="{12 * u:.1f}" stroke-linejoin="round" '
                 f'paint-order="stroke" xml:space="preserve">{spans(True)}</text>')

    # Three loading dots hopping in turn.
    for k in range(3):
        hop = max(0.0, math.sin(2 * math.pi * (t * 2) - k * 0.9)) * 30 * u
        parts.append(f'<circle cx="{w / 2 + (k - 1) * 70 * u:.1f}" cy="{cy + 150 * u - hop:.1f}" '
                     f'r="{20 * u:.1f}" fill="#fff" stroke="#0d47a1" stroke-width="{6 * u:.1f}"/>')

    parts.append("</svg>")
    return "".join(parts)


def main():
    if sys.argv[1] == "--still":
        # One frame as a PNG, for the Linux boot splash (Plymouth).
        w, h, out = int(sys.argv[2]), int(sys.argv[3]), pathlib.Path(sys.argv[4])
        with tempfile.TemporaryDirectory() as tmp:
            svg = pathlib.Path(tmp) / "still.svg"
            svg.write_text(frame_svg(w, h, 0))
            subprocess.run(["rsvg-convert", "-o", str(out), str(svg)], check=True)
        print(f"wrote {out} ({w}x{h})")
        return
    w, h, out = int(sys.argv[1]), int(sys.argv[2]), pathlib.Path(sys.argv[3])
    with tempfile.TemporaryDirectory() as tmp:
        tmp = pathlib.Path(tmp)
        pngs = []
        for i in range(FRAMES):
            svg = tmp / f"{i:03d}.svg"
            png = tmp / f"{i:03d}.png"
            svg.write_text(frame_svg(w, h, i))
            subprocess.run(["rsvg-convert", "-o", str(png), str(svg)], check=True)
            pngs.append(png)
        # Android needs the zip uncompressed (stored).
        with zipfile.ZipFile(out, "w", zipfile.ZIP_STORED) as z:
            z.writestr("desc.txt", f"{w} {h} {FPS}\np 0 0 part0\n")
            for png in pngs:
                z.write(png, f"part0/{png.name}")
    print(f"wrote {out} ({w}x{h}, {FRAMES} frames)")


if __name__ == "__main__":
    main()
