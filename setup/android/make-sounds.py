#!/usr/bin/env python3
"""Generates the apps' tap sound: a short, soft rising "pop" (16-bit mono WAV).

    make-sounds.py OUT_DIR      writes OUT_DIR/pop.wav
"""
import math
import pathlib
import struct
import sys
import wave

RATE = 44100


def pop():
    samples = []
    length = 0.12
    for i in range(int(RATE * length)):
        t = i / RATE
        env = math.sin(math.pi * t / length) ** 2  # soft attack and release, no click
        # Pitch rises 520 -> 1420 Hz: cheerful rather than clicky.
        phase = 2 * math.pi * (520 * t + 450 * t * t / length)
        samples.append(0.35 * env * (math.sin(phase) + 0.25 * math.sin(2 * phase)))
    return samples


def main():
    out = pathlib.Path(sys.argv[1])
    out.mkdir(parents=True, exist_ok=True)
    with wave.open(str(out / "pop.wav"), "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(RATE)
        w.writeframes(b"".join(struct.pack("<h", int(max(-1, min(1, s)) * 32767)) for s in pop()))


if __name__ == "__main__":
    main()
