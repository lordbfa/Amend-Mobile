#!/usr/bin/env python3
"""
amend's own notification and ringtone sounds, synthesised from scratch.

Every sound is generated here from sine partials (modal synthesis of marimba, kalimba,
glass and bell tones), soft envelopes and a small algorithmic reverb. Nothing is sampled
or downloaded, so the sounds are original works of the amend project and ship under the
app's own licence, with no third-party rights or attribution.

    python3 tools/sounds/generate.py            # writes app/src/main/res/raw/amend_*.ogg

Needs numpy and ffmpeg (libvorbis).
"""
import os
import subprocess
import sys
import tempfile
import wave

import numpy as np

SR = 44_100
ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
OUT = os.path.join(ROOT, "app", "src", "main", "res", "raw")


def note(name):
    """'A4' -> Hz (equal temperament)."""
    names = {"C": -9, "D": -7, "E": -5, "F": -4, "G": -2, "A": 0, "B": 2}
    semis = names[name[0]] + (1 if "#" in name else 0) - (1 if "b" in name[1:] else 0)
    octave = int(name[-1])
    return 440.0 * 2 ** ((semis + (octave - 4) * 12) / 12)


def silence(seconds):
    return np.zeros(int(SR * seconds))


def modal(freq, seconds, partials, attack=0.004, brightness=1.0):
    """Struck tone: each partial (ratio, gain, decay seconds) rings and fades on its own."""
    t = np.arange(int(SR * seconds)) / SR
    out = np.zeros_like(t)
    for ratio, gain, decay in partials:
        f = freq * ratio
        if f > SR / 2.2:
            continue
        out += gain * (brightness ** (ratio - 1)) * np.sin(2 * np.pi * f * t) * np.exp(-t / decay)
    env = np.minimum(1.0, t / attack)  # click-free attack
    return out * env


MARIMBA = [(1.0, 1.0, 0.55), (3.93, 0.35, 0.12), (9.2, 0.10, 0.04)]
KALIMBA = [(1.0, 1.0, 0.9), (2.0, 0.12, 0.35), (5.4, 0.18, 0.08), (8.9, 0.05, 0.03)]
GLASS = [(1.0, 1.0, 1.4), (2.76, 0.32, 0.6), (5.4, 0.12, 0.25), (8.93, 0.05, 0.12)]
BELL = [(0.5, 0.35, 2.2), (1.0, 1.0, 1.6), (1.19, 0.45, 1.1), (1.56, 0.30, 0.8), (2.0, 0.28, 0.6), (2.74, 0.16, 0.4), (3.76, 0.08, 0.25)]
WOOD = [(1.0, 1.0, 0.18), (2.57, 0.45, 0.06), (4.9, 0.2, 0.03)]


def place(track, sound, at, gain=1.0):
    i = int(SR * at)
    end = min(len(track), i + len(sound))
    track[i:end] += gain * sound[: end - i]
    return track


def drop(freq, seconds=0.5):
    """A water drop: a sine that glides up quickly, then rings."""
    t = np.arange(int(SR * seconds)) / SR
    glide = freq * (1 + 0.55 * (1 - np.exp(-t / 0.012)))
    phase = 2 * np.pi * np.cumsum(glide) / SR
    return np.sin(phase) * np.exp(-t / 0.09) * np.minimum(1.0, t / 0.002)


def chirp(f0, f1, seconds):
    """A soft bird-like chirp (frequency sweep with a gentle envelope)."""
    t = np.arange(int(SR * seconds)) / SR
    f = f0 + (f1 - f0) * (t / seconds) ** 0.7
    phase = 2 * np.pi * np.cumsum(f) / SR
    env = np.sin(np.pi * t / seconds) ** 2
    return np.sin(phase) * env


def reverb(x, mix=0.25, room=0.82):
    """Small Schroeder reverb: four combs and two all-passes. Keeps tails smooth and airy."""
    out = np.zeros(len(x) + int(SR * 1.2))
    src = np.concatenate([x, np.zeros(len(out) - len(x))])
    wet = np.zeros_like(out)
    for d_ms, g in [(29.7, room), (37.1, room * 0.97), (41.1, room * 0.95), (43.7, room * 0.93)]:
        d = int(SR * d_ms / 1000)
        y = np.zeros_like(out)
        for i in range(d, len(out), d):  # vectorised in blocks of one delay length
            y[i:i + d] = src[i:i + d] + g * y[i - d:i - d + len(y[i:i + d])]
        y[:d] = src[:d]
        wet += y
    wet /= 4
    for d_ms, g in [(5.0, 0.7), (1.7, 0.7)]:
        d = int(SR * d_ms / 1000)
        y = np.zeros_like(wet)
        buf = np.concatenate([np.zeros(d), wet])
        ybuf = np.zeros(len(wet) + d)
        for i in range(len(wet)):
            ybuf[i + d] = -g * buf[i + d] + buf[i] + g * ybuf[i]
        wet = ybuf[d:]
    return (1 - mix) * src + mix * wet


def finish(x, peak_db=-3.0, fade=0.03, tail_db=-60):
    """Trim the silent tail, fade the ends and normalise."""
    x = x / (np.max(np.abs(x)) or 1)
    thresh = 10 ** (tail_db / 20)
    idx = np.where(np.abs(x) > thresh)[0]
    x = x[: (idx[-1] + 1) if len(idx) else len(x)]
    n = int(SR * fade)
    x[:8] *= np.linspace(0, 1, 8)
    x[-n:] *= np.linspace(1, 0, n)
    return x * 10 ** (peak_db / 20)


def loop_ready(x, seconds):
    """Fixed length for a ringtone loop, tail folded back to the start so the loop is seamless."""
    n = int(SR * seconds)
    if len(x) > n:
        tail = x[n:]
        x = x[:n].copy()
        x[: len(tail)] += tail[: n]
    else:
        x = np.concatenate([x, np.zeros(n - len(x))])
    # a few ms of fade at both ends: the loop restarts without a click
    f = int(SR * 0.012)
    x[:f] *= np.linspace(0, 1, f)
    x[-f:] *= np.linspace(1, 0, f)
    x = x / (np.max(np.abs(x)) or 1) * 10 ** (-3 / 20)
    return x


# ------------------------------------------------------------------- messages (short)

def msg_dewdrop():
    t = silence(1.0)
    place(t, drop(note("E6") * 0.66, 0.45), 0.0, 0.9)
    place(t, drop(note("B6") * 0.66, 0.45), 0.11, 0.6)
    return finish(reverb(t, 0.28))


def msg_leaf():
    t = silence(1.3)
    place(t, modal(note("G5"), 1.0, MARIMBA, brightness=0.9), 0.0, 0.85)
    place(t, modal(note("D6"), 1.0, MARIMBA, brightness=0.9), 0.12, 0.75)
    return finish(reverb(t, 0.22))


def msg_bamboo():
    t = silence(1.0)
    place(t, modal(note("A4"), 0.6, WOOD), 0.0, 0.8)
    place(t, modal(note("E5"), 0.6, WOOD), 0.09, 0.7)
    place(t, modal(note("A5"), 0.9, KALIMBA), 0.18, 0.5)
    return finish(reverb(t, 0.25))


def msg_petal():
    t = silence(1.8)
    for i, n in enumerate(["C6", "E6", "G6"]):
        place(t, modal(note(n), 1.6, GLASS, brightness=0.8), i * 0.07, 0.6)
    return finish(reverb(t, 0.32))


# ------------------------------------------------------------------- other notifications

def note_sprout():
    t = silence(1.6)
    for i, n in enumerate(["D5", "F#5", "A5"]):
        place(t, modal(note(n), 1.2, KALIMBA), i * 0.13, 0.75)
    return finish(reverb(t, 0.25))


def note_sunbeam():
    t = silence(2.2)
    place(t, modal(note("F5"), 2.0, BELL, brightness=0.85), 0.0, 0.7)
    place(t, modal(note("C6"), 2.0, GLASS, brightness=0.8), 0.18, 0.45)
    return finish(reverb(t, 0.35))


def note_glow():
    t = silence(2.0)
    place(t, modal(note("A5"), 1.9, BELL, brightness=0.8), 0.0, 0.8)
    return finish(reverb(t, 0.3))


# ------------------------------------------------------------------- ringtones (loops)

def ring_morning_forest():
    beat = 0.24
    melody = ["C5", "E5", "G5", "A5", "G5", "E5", "D5", "G5", None, "E5", "G5", "C6", "A5", "G5", "E5", None]
    t = silence(beat * 16 + 1.5)
    for i, n in enumerate(melody):
        if n:
            place(t, modal(note(n), 1.1, MARIMBA, brightness=0.95), i * beat, 0.8)
    place(t, modal(note("C4"), 1.8, MARIMBA, brightness=0.7), 0.0, 0.35)
    place(t, modal(note("A3"), 1.8, MARIMBA, brightness=0.7), beat * 8, 0.35)
    for at, f0, f1 in [(1.1, 3200, 4300), (1.22, 3400, 4600), (2.9, 3600, 4100)]:
        place(t, chirp(f0, f1, 0.09), at, 0.12)
    return loop_ready(reverb(t, 0.24), beat * 16 + 0.6)


def ring_riverbank():
    beat = 0.2
    arp = ["A4", "C5", "E5", "A5", "E5", "C5", "G4", "B4", "D5", "G5", "D5", "B4", "F4", "A4", "C5", "E5", "D5", "C5", "B4", "G4"]
    t = silence(beat * len(arp) + 1.5)
    for i, n in enumerate(arp):
        place(t, modal(note(n), 1.3, KALIMBA), i * beat, 0.7 if i % 6 == 0 else 0.55)
    return loop_ready(reverb(t, 0.3), beat * len(arp) + 0.4)


def ring_wind_chimes():
    rng = np.random.default_rng(7)  # seeded: the same chimes every time it's built
    scale = ["D6", "E6", "G6", "A6", "B6", "D7"]
    length = 5.0
    t = silence(length + 2.5)
    at = 0.0
    while at < length:
        n = scale[rng.integers(len(scale))]
        place(t, modal(note(n), 2.2, GLASS, brightness=0.8), at, 0.35 + 0.35 * rng.random())
        at += 0.12 + 0.35 * rng.random()
    return loop_ready(reverb(t, 0.38, room=0.86), length + 0.5)


SOUNDS = {
    "amend_msg_dewdrop": msg_dewdrop,
    "amend_msg_leaf": msg_leaf,
    "amend_msg_bamboo": msg_bamboo,
    "amend_msg_petal": msg_petal,
    "amend_note_sprout": note_sprout,
    "amend_note_sunbeam": note_sunbeam,
    "amend_note_glow": note_glow,
    "amend_ring_morning_forest": ring_morning_forest,
    "amend_ring_riverbank": ring_riverbank,
    "amend_ring_wind_chimes": ring_wind_chimes,
}


def write_ogg(name, x):
    with tempfile.TemporaryDirectory() as tmp:
        wav = os.path.join(tmp, name + ".wav")
        with wave.open(wav, "wb") as w:
            w.setnchannels(1)
            w.setsampwidth(2)
            w.setframerate(SR)
            w.writeframes((np.clip(x, -1, 1) * 32767).astype("<i2").tobytes())
        out = os.path.join(OUT, name + ".ogg")
        subprocess.run(["ffmpeg", "-loglevel", "error", "-y", "-i", wav, "-c:a", "libvorbis", "-q:a", "6", "-map_metadata", "-1", out], check=True)
        return out


if __name__ == "__main__":
    os.makedirs(OUT, exist_ok=True)
    only = set(sys.argv[1:])
    for name, make in SOUNDS.items():
        if only and name not in only:
            continue
        path = write_ogg(name, make())
        print(f"{name}.ogg  {os.path.getsize(path) // 1024} kB")
