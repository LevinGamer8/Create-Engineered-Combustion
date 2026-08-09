#!/usr/bin/env python3
"""
Generates the placeholder engine sounds for Create: Engineered Combustion.

Everything here is synthesised from noise and sine waves - there is no sampled
material of any kind, so the resulting .ogg files are original assets of this
project. Run it to regenerate them:

    pip install numpy soundfile
    python3 tools/generate_sounds.py

Output goes to src/main/resources/assets/engineered_combustion/sounds/.

The files are deliberately mono. Minecraft only applies positional attenuation
to mono sounds; a stereo file is played at full volume everywhere, which would
break the distance falloff the engine sounds rely on.

The two loops are built with wrap-around pulse placement, so the decay tail of
the last pulse lands at the start of the buffer and the loop is seamless.

These are placeholders. They establish the sound architecture and give useful
audible feedback; they are not final production audio.
"""

import os

import numpy as np
import soundfile as sf

SR = 44100
OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "src", "main", "resources",
                   "assets", "engineered_combustion", "sounds")

rng = np.random.default_rng(0x0C0FFEE)


def onepole_lp(x, cutoff_hz):
    """Simple one-pole low-pass. Cheap, and gentle enough to keep the noise warm."""
    a = 1.0 - np.exp(-2.0 * np.pi * cutoff_hz / SR)
    y = np.empty_like(x)
    prev = 0.0
    for i in range(len(x)):
        prev += a * (x[i] - prev)
        y[i] = prev
    return y


def onepole_hp(x, cutoff_hz):
    return x - onepole_lp(x, cutoff_hz)


def bandpass(x, low_hz, high_hz):
    return onepole_hp(onepole_lp(x, high_hz), low_hz)


def noise(n):
    return rng.uniform(-1.0, 1.0, n)


def add_wrapped(buf, start, chunk):
    """Adds chunk into buf at start, wrapping past the end. This is what makes loops seamless."""
    n = len(buf)
    idx = (np.arange(len(chunk)) + int(start)) % n
    np.add.at(buf, idx, chunk)


def combustion_pulse(decay=0.045, thump_hz=62.0, bark_lo=280.0, bark_hi=1400.0,
                     bark_level=0.55, length=None):
    """
    One cylinder firing: a low thump for the body, a band-limited noise bark for
    the exhaust edge. Together they read as a combustion event rather than a beep.
    """
    n = int(SR * (decay * 6 if length is None else length))
    t = np.arange(n) / SR
    env = np.exp(-t / decay)

    # Slight downward sweep - a real pulse loses energy at the top first.
    sweep = thump_hz * (1.0 + 0.35 * np.exp(-t / (decay * 0.6)))
    phase = 2.0 * np.pi * np.cumsum(sweep) / SR
    thump = np.sin(phase) * env

    bark = bandpass(noise(n), bark_lo, bark_hi) * np.exp(-t / (decay * 0.45)) * bark_level

    # Very short click at the very front, so the attack is crisp at any pitch.
    click = np.zeros(n)
    ck = int(SR * 0.0016)
    click[:ck] = noise(ck) * np.linspace(1.0, 0.0, ck) * 0.35

    return thump + bark + click


def mech_clunk(level=0.5):
    """A dull metallic knock, used to end the shutdown sounds."""
    n = int(SR * 0.16)
    t = np.arange(n) / SR
    body = np.zeros(n)
    for f, a, d in ((196.0, 1.0, 0.030), (317.0, 0.5, 0.020), (523.0, 0.25, 0.012)):
        body += np.sin(2.0 * np.pi * f * t) * np.exp(-t / d) * a
    body += bandpass(noise(n), 400.0, 3000.0) * np.exp(-t / 0.010) * 0.5
    return body * level


def normalize(x, peak=0.85):
    # Subtracting the mean first matters for the loops: a DC offset survives the
    # wrap and turns the loop point into an audible click. Removing a constant
    # cannot break seamlessness the way a filter would.
    x = x - np.mean(x)
    m = np.max(np.abs(x))
    return x if m == 0 else x * (peak / m)


def declick(x, ms=4.0):
    """One-shots only: keeps the very first and last sample from popping."""
    k = int(SR * ms / 1000.0)
    x = x.copy()
    x[:k] *= np.linspace(0.0, 1.0, k)
    x[-k:] *= np.linspace(1.0, 0.0, k)
    return x


# --------------------------------------------------------------------------
# loops
# --------------------------------------------------------------------------

def engine_running():
    """
    Recorded at the engine's idle character. The game shifts this by pitch, so
    the firing rate here is the reference point the pitch mapping is tuned around.
    """
    # 8 Hz, not the firing rate of a real engine - the point is that individual
    # combustion pulses stay separately audible ("put... put... put") at the low
    # end of the pitch range, and only blend together as pitch rises. A faster
    # base rate turns into an undifferentiated buzz at every speed, which is the
    # one thing a single-cylinder engine must not sound like.
    fire_hz = 8.0
    dur = 1.0                        # 8 pulses exactly -> loops without a seam
    n = int(SR * dur)
    buf = np.zeros(n)

    # Short decay relative to the 125 ms gap between pulses, so each one has died
    # away before the next arrives and the rhythm reads clearly.
    pulse = combustion_pulse(decay=0.042, thump_hz=62.0, bark_level=0.60)
    period = SR / fire_hz
    for i in range(int(round(dur * fire_hz))):
        # A single cylinder is never perfectly even; a touch of jitter stops the
        # loop from sounding like a synthesised tone.
        jitter = 1.0 + 0.035 * np.sin(i * 1.7)
        add_wrapped(buf, i * period, pulse * jitter)

    # Continuous bed: intake/mechanical rumble under the pulses. Kept well below
    # them so it fills the gaps without masking the rhythm.
    bed = onepole_lp(noise(n), 220.0) * 0.16
    t = np.arange(n) / SR
    bed *= 1.0 + 0.30 * np.sin(2.0 * np.pi * fire_hz * t)  # integer cycles -> seamless
    buf += bed

    return normalize(buf, 0.80)


def engine_cranking():
    """
    Being turned over without catching: a slow lopey chug plus starter whirr.
    Deliberately weaker and duller than the running loop.
    """
    fire_hz = 5.0
    dur = 0.8                        # 4 chugs, and 144 whine cycles - both integers
    n = int(SR * dur)
    buf = np.zeros(n)

    chug = combustion_pulse(decay=0.055, thump_hz=44.0, bark_lo=160.0, bark_hi=700.0,
                            bark_level=0.30)
    period = SR / fire_hz
    for i in range(int(round(dur * fire_hz))):
        add_wrapped(buf, i * period, chug * (0.85 + 0.15 * (i % 2)))

    t = np.arange(n) / SR
    whine = np.sin(2.0 * np.pi * 180.0 * t) * 0.10
    whine += np.sin(2.0 * np.pi * 360.0 * t) * 0.04
    whine *= 1.0 + 0.45 * np.sin(2.0 * np.pi * fire_hz * t)
    buf += whine

    buf += onepole_lp(noise(n), 160.0) * 0.14

    return normalize(buf, 0.62)


# --------------------------------------------------------------------------
# one-shots
# --------------------------------------------------------------------------

def engine_fire_attempt():
    """One failed-to-catch cough. Short, so it can fire once per start cycle."""
    n = int(SR * 0.30)
    buf = np.zeros(n)
    p = combustion_pulse(decay=0.040, thump_hz=58.0, bark_lo=300.0, bark_hi=2200.0,
                         bark_level=0.85)
    buf[:len(p)] += p[:n]

    # The trailing hiss is what makes it read as "didn't take" rather than "fired".
    t = np.arange(n) / SR
    buf += bandpass(noise(n), 500.0, 4000.0) * np.exp(-t / 0.055) * 0.35
    return normalize(declick(buf), 0.75)


def engine_start():
    """
    The catch: two hesitant pulses, then the firing rate runs up and settles.
    This is the cue that the player can let go of the hand crank.
    """
    dur = 1.0
    n = int(SR * dur)
    buf = np.zeros(n)

    # Pulse times from a rate that accelerates from cranking speed to idle.
    times, tt, rate = [], 0.0, 4.5
    while tt < dur - 0.08:
        times.append(tt)
        rate += (13.0 - rate) * 0.30
        tt += 1.0 / rate
    for i, tt in enumerate(times):
        strength = 0.55 + 0.45 * min(1.0, i / 4.0)
        p = combustion_pulse(decay=0.050, thump_hz=48.0 + 16.0 * min(1.0, i / 5.0),
                             bark_level=0.6)
        s = int(tt * SR)
        end = min(n, s + len(p))
        buf[s:end] += p[:end - s] * strength

    t = np.arange(n) / SR
    buf += onepole_lp(noise(n), 260.0) * np.clip(t / 0.4, 0.0, 1.0) * 0.20
    return normalize(declick(buf), 0.85)


def engine_stall():
    """The engine dying: the firing rate drags down, pitch falls, then a clunk."""
    dur = 1.1
    n = int(SR * dur)
    buf = np.zeros(n)

    times, tt, rate = [], 0.0, 11.0
    while tt < dur - 0.22 and rate > 1.6:
        times.append((tt, rate))
        rate *= 0.74
        tt += 1.0 / rate
    for i, (tt, rate) in enumerate(times):
        strength = max(0.12, 1.0 - i * 0.16)
        p = combustion_pulse(decay=0.055, thump_hz=58.0 * (0.55 + 0.45 * rate / 11.0),
                             bark_level=0.35)
        s = int(tt * SR)
        end = min(n, s + len(p))
        buf[s:end] += p[:end - s] * strength

    clunk = mech_clunk(0.45)
    s = int((dur - 0.19) * SR)
    end = min(n, s + len(clunk))
    buf[s:end] += clunk[:end - s]

    return normalize(declick(buf), 0.75)


def engine_stop():
    """A deliberate shutdown: shorter than a stall, no laboured dying pulses."""
    dur = 0.7
    n = int(SR * dur)
    buf = np.zeros(n)

    times, tt, rate = [], 0.0, 12.0
    while tt < dur - 0.2:
        times.append(tt)
        rate *= 0.62
        tt += 1.0 / rate
    for i, tt in enumerate(times):
        p = combustion_pulse(decay=0.050, thump_hz=54.0, bark_level=0.30)
        s = int(tt * SR)
        end = min(n, s + len(p))
        buf[s:end] += p[:end - s] * max(0.15, 0.8 - i * 0.22)

    clunk = mech_clunk(0.40)
    s = int((dur - 0.17) * SR)
    end = min(n, s + len(clunk))
    buf[s:end] += clunk[:end - s]

    return normalize(declick(buf), 0.70)


SOUNDS = {
    "engine_running": engine_running,
    "engine_cranking": engine_cranking,
    "engine_fire_attempt": engine_fire_attempt,
    "engine_start": engine_start,
    "engine_stall": engine_stall,
    "engine_stop": engine_stop,
}


def main():
    out = os.path.normpath(OUT)
    os.makedirs(out, exist_ok=True)
    for name, fn in SOUNDS.items():
        data = fn().astype(np.float32)
        path = os.path.join(out, name + ".ogg")
        sf.write(path, data, SR, format="OGG", subtype="VORBIS")
        print("%-22s %6.2f s  %6d bytes" % (name + ".ogg", len(data) / SR, os.path.getsize(path)))


if __name__ == "__main__":
    main()
