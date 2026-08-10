#!/usr/bin/env python3
"""
Generates the engine sounds for Create: Engineered Combustion.

Everything is synthesised from noise, impulses and filters - there is no sampled
material of any kind, so the resulting .ogg files are original assets of this
project. Run it to regenerate them:

    pip install numpy soundfile
    python3 tools/generate_sounds.py

Output goes to src/main/resources/assets/engineered_combustion/sounds/.

How the engine sound is built
-----------------------------
A single-cylinder four-stroke fires once every two revolutions, so at idle the
ear hears a train of widely spaced, individually distinguishable events rather
than a hum. Each event is layered:

  * cylinder thump   - a short, downward-swept low sine. The pressure pulse.
  * exhaust bark     - band-limited noise, fast decay. The sound leaving the port.
  * mechanical clack - a brief high transient a little after the bark, standing in
                       for valvetrain and piston slap.

Those layers are then driven through resonators tuned to a few fixed frequencies,
which is what gives the engine a consistent metallic *body* rather than sounding
like separate noises played together. Cycle-to-cycle amplitude and timing are
varied slightly, because a single-cylinder engine is never metronomic.

Two details that matter for quality:

  * The loops place their pulses with wrap-around, so the decay of the last event
    lands at the start of the buffer.
  * IIR filters are run over three tiled copies of a loop and only the middle
    copy is kept. Filtering a loop linearly leaves a start-up transient at sample
    zero, which is audible as a tick every time the loop repeats.

Mono throughout: Minecraft only applies distance attenuation to mono sounds, so a
stereo file would play at full volume everywhere and break positional audio.

These remain placeholders in the sense that a recorded or professionally designed
engine would still be better, but they are built to be usable rather than merely
indicative.
"""

import os

import numpy as np
import soundfile as sf

SR = 44100
OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "src", "main", "resources",
                   "assets", "engineered_combustion", "sounds")

rng = np.random.default_rng(0x0C0FFEE)


# ------------------------------------------------------------------ filters

def biquad(x, b0, b1, b2, a1, a2):
    y = np.empty_like(x)
    x1 = x2 = y1 = y2 = 0.0
    for i in range(len(x)):
        v = x[i]
        out = b0 * v + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
        x2, x1 = x1, v
        y2, y1 = y1, out
        y[i] = out
    return y


def resonator(x, f0, q):
    """Constant-peak bandpass. Several of these in parallel make a body."""
    w0 = 2.0 * np.pi * f0 / SR
    alpha = np.sin(w0) / (2.0 * q)
    b0, b1, b2 = alpha, 0.0, -alpha
    a0, a1, a2 = 1.0 + alpha, -2.0 * np.cos(w0), 1.0 - alpha
    return biquad(x, b0 / a0, b1 / a0, b2 / a0, a1 / a0, a2 / a0)


def lowpass(x, f0, q=0.707):
    w0 = 2.0 * np.pi * f0 / SR
    alpha = np.sin(w0) / (2.0 * q)
    cw = np.cos(w0)
    b0, b1, b2 = (1.0 - cw) / 2.0, 1.0 - cw, (1.0 - cw) / 2.0
    a0, a1, a2 = 1.0 + alpha, -2.0 * cw, 1.0 - alpha
    return biquad(x, b0 / a0, b1 / a0, b2 / a0, a1 / a0, a2 / a0)


def highpass(x, f0, q=0.707):
    w0 = 2.0 * np.pi * f0 / SR
    alpha = np.sin(w0) / (2.0 * q)
    cw = np.cos(w0)
    b0, b1, b2 = (1.0 + cw) / 2.0, -(1.0 + cw), (1.0 + cw) / 2.0
    a0, a1, a2 = 1.0 + alpha, -2.0 * cw, 1.0 - alpha
    return biquad(x, b0 / a0, b1 / a0, b2 / a0, a1 / a0, a2 / a0)


def periodic(x, fn):
    """
    Applies an IIR filter as if the buffer looped forever.

    Filtering a loop directly leaves the filter's start-up transient at sample
    zero, which the ear hears as a tick on every repeat. Running three copies and
    keeping the middle one lets the filter state arrive already settled.
    """
    n = len(x)
    tiled = np.concatenate([x, x, x])
    return fn(tiled)[n:2 * n]


def noise(n):
    return rng.uniform(-1.0, 1.0, n)


def add_wrapped(buf, start, chunk):
    """Adds chunk into buf at start, wrapping past the end - what makes loops seamless."""
    n = len(buf)
    idx = (np.arange(len(chunk)) + int(round(start))) % n
    np.add.at(buf, idx, chunk)


def add_at(buf, start, chunk):
    """Adds chunk into buf at start, clipped at the end. For one-shots."""
    s = max(0, int(round(start)))
    end = min(len(buf), s + len(chunk))
    if end > s:
        buf[s:end] += chunk[:end - s]


# ------------------------------------------------------------------ layers

def cylinder_thump(f_start=58.0, f_end=38.0, decay=0.055, length=0.30):
    """The pressure pulse: a low sine sweeping down as the charge expands."""
    n = int(SR * length)
    t = np.arange(n) / SR
    freq = f_end + (f_start - f_end) * np.exp(-t / (decay * 0.7))
    phase = 2.0 * np.pi * np.cumsum(freq) / SR
    return np.sin(phase) * np.exp(-t / decay)


def exhaust_bark(lo=180.0, hi=1600.0, decay=0.030, length=0.18):
    """Gas leaving the port: band-limited noise with a fast decay."""
    n = int(SR * length)
    t = np.arange(n) / SR
    x = highpass(lowpass(noise(n), hi), lo)
    return x * np.exp(-t / decay)


def mechanical_clack(decay=0.008, length=0.05, tone=2400.0):
    """Valvetrain / piston slap. Short, bright, quiet - texture, not an event."""
    n = int(SR * length)
    t = np.arange(n) / SR
    x = resonator(noise(n), tone, 6.0)
    return x * np.exp(-t / decay)


def body(x, mix=0.75):
    """
    Sends a dry layer through three fixed resonances and blends it back.

    This is what makes the separate layers sound like one object. The
    frequencies are the engine's 'voice' and stay fixed across every asset, so
    cranking, firing and running audibly belong to the same machine.
    """
    wet = 1.5 * resonator(x, 96.0, 3.0) + 1.0 * resonator(x, 232.0, 5.0) + 0.55 * resonator(x, 620.0, 7.0)
    return (1.0 - mix) * x + mix * wet


def body_periodic(x, mix=0.75):
    return periodic(x, lambda v: body(v, mix))


def combustion_event(strength=1.0, thump_f=58.0, bark_level=0.55, clack_level=0.22):
    """One firing event, before it is placed in a buffer."""
    n = int(SR * 0.30)
    out = np.zeros(n)
    add_at(out, 0, cylinder_thump(f_start=thump_f, f_end=thump_f * 0.66))
    add_at(out, SR * 0.002, exhaust_bark() * bark_level)
    add_at(out, SR * 0.030, mechanical_clack() * clack_level)
    return out * strength


def normalize(x, peak=0.85):
    # Subtracting the mean first matters for the loops: a DC offset survives the
    # wrap and turns the loop point into an audible click. Removing a constant
    # cannot break seamlessness the way a filter would.
    x = x - np.mean(x)
    m = np.max(np.abs(x))
    return x if m == 0 else x * (peak / m)


def declick(x, ms=6.0):
    """One-shots only: keeps the first and last sample from popping."""
    k = int(SR * ms / 1000.0)
    x = x.copy()
    x[:k] *= np.linspace(0.0, 1.0, k)
    x[-k:] *= np.linspace(1.0, 0.0, k)
    return x


# ------------------------------------------------------------------ loops

def engine_running():
    """
    The engine at its reference speed, which the pitch mapping treats as idle.

    8 firing events per second: slow enough that they stay individually audible
    at the bottom of the pitch range and blend as pitch rises, which is the
    single-cylinder character the engine is supposed to have.
    """
    fire_hz = 8.0
    dur = 1.0                        # 8 events exactly -> seamless
    n = int(SR * dur)
    period = SR / fire_hz
    buf = np.zeros(n)

    for i in range(int(round(dur * fire_hz))):
        # Deterministic per-cycle variation. A metronomic engine sounds synthetic.
        strength = 1.0 + 0.09 * np.sin(i * 2.399) + 0.04 * np.sin(i * 5.1)
        jitter = 0.0018 * SR * np.sin(i * 1.77)
        thump_f = 58.0 * (1.0 + 0.03 * np.sin(i * 3.1))
        add_wrapped(buf, i * period + jitter,
                    combustion_event(strength=strength, thump_f=thump_f))

    # Intake and mechanical bed, ducked between events so it fills the gaps
    # instead of masking the rhythm.
    t = np.arange(n) / SR
    bed = periodic(noise(n), lambda v: lowpass(v, 320.0)) * 0.20
    bed *= 0.65 + 0.35 * np.sin(2.0 * np.pi * fire_hz * t + 1.6)
    buf += bed

    return normalize(body_periodic(buf, 0.62), 0.80)


def engine_cranking():
    """
    Being turned over without catching.

    Built around compression rather than around firing: each revolution the load
    rises as the piston comes up on the compression stroke, then releases over
    the top with a mechanical knock. That swell-and-release is what makes
    cranking sound like effort instead of like a motor.
    """
    crank_hz = 5.0
    dur = 0.8                        # 4 revolutions, and 144 whirr cycles - both integers
    n = int(SR * dur)
    period = SR / crank_hz
    buf = np.zeros(n)
    t = np.arange(n) / SR

    # Compression load: a slow asymmetric swell once per revolution.
    phase = (t * crank_hz) % 1.0
    load = np.where(phase < 0.72, (phase / 0.72) ** 1.8, np.exp(-(phase - 0.72) / 0.06))

    rumble = periodic(noise(n), lambda v: lowpass(v, 240.0))
    buf += rumble * (0.18 + 0.42 * load)

    # A low tone that sags under compression, like the crank slowing.
    drag_f = 46.0 - 9.0 * load
    buf += np.sin(2.0 * np.pi * np.cumsum(drag_f) / SR) * (0.10 + 0.26 * load)

    # Knock as each compression releases over top dead centre.
    for i in range(int(round(dur * crank_hz))):
        add_wrapped(buf, i * period + 0.72 * period, mechanical_clack(decay=0.014, length=0.09, tone=1300.0) * 0.5)
        add_wrapped(buf, i * period + 0.74 * period, cylinder_thump(f_start=40.0, f_end=30.0, decay=0.035) * 0.35)

    # Starter whirr, integer cycles so it loops.
    buf += np.sin(2.0 * np.pi * 180.0 * t) * 0.05 * (0.6 + 0.4 * load)

    return normalize(body_periodic(buf, 0.5), 0.62)


# ------------------------------------------------------------------ one-shots

def engine_spark():
    """
    The ignition coil discharging: a tiny electrical tick.

    Deliberately almost nothing. It has to be distinguishable from combustion at
    a glance, which here means having none of what combustion is made of: there
    is no cylinder thump, no body resonance and nothing below 1.8 kHz at all -
    only a few milliseconds of band-limited noise with a very fast decay and one
    high resonance sitting on top of it, which is what reads as a spark rather
    than as a small bang.
    """
    n = int(SR * 0.06)
    t = np.arange(n) / SR
    tick = highpass(noise(n), 1800.0) * np.exp(-t / 0.0022)
    ring = resonator(tick, 5200.0, 26.0) * 0.6
    return normalize(declick(tick * 0.7 + ring, ms=1.5), 0.5)


def engine_fire_attempt():
    """
    One failed-to-catch cough.

    Weighted towards low-frequency body with a soft attack. A hard transient here
    reads as a gunshot rather than as combustion, so the thump leads and the
    noise follows it rather than the other way round.
    """
    n = int(SR * 0.42)
    buf = np.zeros(n)
    add_at(buf, 0, cylinder_thump(f_start=54.0, f_end=32.0, decay=0.075, length=0.40) * 1.0)
    add_at(buf, SR * 0.004, exhaust_bark(lo=140.0, hi=1200.0, decay=0.045, length=0.25) * 0.5)
    add_at(buf, SR * 0.035, mechanical_clack(decay=0.012, length=0.06, tone=1500.0) * 0.28)

    # The trailing hiss is what makes it read as "did not take".
    t = np.arange(n) / SR
    tail = highpass(lowpass(noise(n), 2200.0), 400.0) * np.exp(-t / 0.085) * 0.22
    buf += tail
    return normalize(declick(body(buf, 0.6)), 0.72)


def engine_start():
    """
    The catch: two hesitant kicks, then the firing rate runs up and settles at
    the running loop's 8 Hz so the loop can take over without a seam in character.
    """
    dur = 1.15
    n = int(SR * dur)
    buf = np.zeros(n)

    times, tt, rate = [], 0.0, 3.6
    while tt < dur - 0.14:
        times.append((tt, rate))
        rate += (8.0 - rate) * 0.34          # converges on the running loop's rate
        tt += 1.0 / rate
    for i, (tt, rate) in enumerate(times):
        settle = min(1.0, i / 4.0)
        add_at(buf, tt * SR, combustion_event(
            strength=0.55 + 0.45 * settle,
            thump_f=46.0 + 12.0 * settle,
            bark_level=0.40 + 0.20 * settle))

    t = np.arange(n) / SR
    buf += lowpass(noise(n), 300.0) * np.clip(t / 0.45, 0.0, 1.0) * 0.16
    return normalize(declick(body(buf, 0.62)), 0.85)


def engine_stall():
    """
    The engine dying: the firing rate drags down and the pulses get weaker and
    more uneven, ending in a last half-hearted combustion and a mechanical stop.
    Deliberately not explosive - it is energy running out, not a bang.
    """
    dur = 1.5
    n = int(SR * dur)
    buf = np.zeros(n)

    times, tt, rate = [], 0.0, 7.5
    while tt < dur - 0.35 and rate > 1.1:
        times.append((tt, rate))
        rate *= 0.72
        tt += 1.0 / rate
    for i, (tt, rate) in enumerate(times):
        frac = rate / 7.5
        add_at(buf, tt * SR, combustion_event(
            strength=max(0.14, 0.95 - i * 0.17),
            thump_f=34.0 + 24.0 * frac,
            bark_level=0.30 * frac,
            clack_level=0.18))

    # Final rotation dying away, then the mechanism coming to rest.
    tail_start = dur - 0.34
    tail_n = int(SR * 0.30)
    tt = np.arange(tail_n) / SR
    drag = np.sin(2.0 * np.pi * np.cumsum(26.0 - 18.0 * tt / 0.30) / SR) * np.exp(-tt / 0.11) * 0.35
    add_at(buf, tail_start * SR, drag)
    add_at(buf, (dur - 0.13) * SR, mechanical_clack(decay=0.020, length=0.12, tone=900.0) * 0.45)

    return normalize(declick(body(buf, 0.6)), 0.74)


def engine_stop():
    """A deliberate shutdown: shorter than a stall, no laboured dying pulses."""
    dur = 0.85
    n = int(SR * dur)
    buf = np.zeros(n)

    tt, rate = 0.0, 8.0
    for i in range(4):
        if tt >= dur - 0.25:
            break
        add_at(buf, tt * SR, combustion_event(strength=max(0.12, 0.65 - i * 0.18), thump_f=44.0,
                                              bark_level=0.20, clack_level=0.16))
        rate *= 0.58
        tt += 1.0 / rate

    add_at(buf, (dur - 0.16) * SR, mechanical_clack(decay=0.018, length=0.12, tone=820.0) * 0.42)
    return normalize(declick(body(buf, 0.58)), 0.66)


# Order is load-bearing: every generator draws from the one module-level RNG, so
# inserting a sound anywhere but at the end shifts the noise every later sound is
# built from and silently re-rolls assets that were not meant to change.
SOUNDS = {
    "engine_running": engine_running,
    "engine_cranking": engine_cranking,
    "engine_fire_attempt": engine_fire_attempt,
    "engine_start": engine_start,
    "engine_stall": engine_stall,
    "engine_stop": engine_stop,
    "engine_spark": engine_spark,
}


def main():
    out = os.path.normpath(OUT)
    os.makedirs(out, exist_ok=True)
    for name, fn in SOUNDS.items():
        data = fn().astype(np.float32)
        path = os.path.join(out, name + ".ogg")
        sf.write(path, data, SR, format="OGG", subtype="VORBIS")
        print("%-22s %5.2f s  %6d bytes" % (name + ".ogg", len(data) / SR, os.path.getsize(path)))


if __name__ == "__main__":
    main()
