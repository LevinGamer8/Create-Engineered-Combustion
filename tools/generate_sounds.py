#!/usr/bin/env python3
"""
Generates the engine sounds for Create: Engineered Combustion.

Everything is synthesised from noise, impulses and filters - there is no sampled
material of any kind, so the resulting .ogg files are original assets of this
project. Run it to regenerate them:

    pip install numpy soundfile
    python3 tools/generate_sounds.py

Output goes to src/main/resources/assets/engineered_combustion/sounds/.

Two layers, because the engine has two voices
---------------------------------------------
The mod plays this engine as a *hybrid*: one continuous mechanical loop, plus one
short one-shot per combustion event that actually happened. So the assets come in
two families and they must not contain each other.

  engine_mechanical      The rotating machine and nothing else - bearings, the
                         flywheel, and the piston pumping against compression
                         once per revolution. It is pitched by mechanical RPM and
                         plays whenever the crank turns, including when the engine
                         is dead and being spun by something else. It therefore
                         contains NO firing whatsoever: a single trace of
                         combustion in here would make a fuel-starved engine sound
                         like it was still burning.

  engine_fire_1..3       One charge burning. Three interchangeable variants, since
                         the game picks one at random per event with a little
                         pitch and volume spread - a single-cylinder engine is
                         never metronomic, and one sample on a metronome is
                         exactly what that sounds like.

  engine_combustion_loop The aggregate of firing events, for a future engine that
                         fires too fast to hear individually. Unused at the
                         current 3.2 Hz maximum.

A combustion pulse is built from three layers, in this order and with these
weights on purpose:

  * pressure body    - a low sine sweeping downward as the charge expands. It
                       LEADS, and it has a few milliseconds of attack rather than
                       starting instantaneously. A hard transient at sample zero
                       is what makes a synthesised bang read as a gunshot.
  * exhaust bark     - band-limited noise following the body out of the port,
                       with a fast decay. No long tail: a long noise tail reads as
                       an explosion.
  * mechanical tick  - a brief high resonance a few milliseconds later, standing
                       in for piston slap and valve gear. Quiet - it is texture,
                       and at any real level it turns the pulse into a click.

Those layers are then driven through resonators tuned to a few fixed frequencies,
which is what gives the engine a consistent metallic *body* rather than sounding
like separate noises played together, and what makes the mechanical loop and the
pulses audibly the same machine.

Three details that matter for quality:

  * The loops place their events with wrap-around, so the decay of the last one
    lands at the start of the buffer.
  * IIR filters are run over three tiled copies of a loop and only the middle
    copy is kept. Filtering a loop linearly leaves a start-up transient at sample
    zero, which is audible as a tick every time the loop repeats.
  * Every sound draws from its own RNG, seeded from its name. Sounds can then be
    added, removed or reordered without silently re-rolling the noise every other
    sound is built from.

Mono throughout: Minecraft only applies distance attenuation to mono sounds, so a
stereo file would play at full volume everywhere and break positional audio.

Everything here is synthesised from noise, impulses and filters. Nothing is
sampled, recorded or derived from any other work.
"""

import os
import zlib

import numpy as np
import soundfile as sf

SR = 44100
OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "src", "main", "resources",
                   "assets", "engineered_combustion", "sounds")

# Replaced per sound by main(), so each asset is reproducible on its own.
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

def cylinder_thump(f_start=58.0, f_end=38.0, decay=0.055, length=0.30, attack=0.0):
    """
    The pressure pulse: a low sine sweeping down as the charge expands.

    `attack` gives the pulse a short rise instead of starting at full amplitude on
    sample zero. That rise is the single most important difference between "an
    engine firing" and "a gunshot": real cylinder pressure takes a few
    milliseconds to build, and an instantaneous onset is what the ear labels a
    weapon.
    """
    n = int(SR * length)
    t = np.arange(n) / SR
    freq = f_end + (f_start - f_end) * np.exp(-t / (decay * 0.7))
    phase = 2.0 * np.pi * np.cumsum(freq) / SR
    envelope = np.exp(-t / decay)
    if attack > 0.0:
        # Raised cosine, so the rise has no corner in it to click on.
        k = max(1, int(SR * attack))
        envelope[:k] *= 0.5 - 0.5 * np.cos(np.linspace(0.0, np.pi, k))
    return np.sin(phase) * envelope


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
    """
    One firing event, before it is placed in a buffer. Used by the aggregate
    combustion loop; the standalone pulses have their own, more carefully shaped
    builder in `combustion_pulse`.
    """
    n = int(SR * 0.30)
    out = np.zeros(n)
    add_at(out, 0, cylinder_thump(f_start=thump_f, f_end=thump_f * 0.66, attack=0.003))
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

def engine_mechanical():
    """
    The rotating machine, with no combustion anywhere in it.

    This is the layer that plays whenever the crank turns - cranked by hand,
    running, coasting down, or being motored by another engine on the network -
    so it has to be the sound of an engine that is NOT firing. Anything that
    reads as a bang would make a dead engine sound alive, which is precisely the
    confusion the two-layer split exists to remove.

    ONE ENGINE CYCLE, NOT ONE REVOLUTION
    ------------------------------------
    This loop used to carry one compression swell and one over-centre knock per
    CRANK REVOLUTION, which was right for an engine that fired every 360 degrees
    and is wrong for a four-stroke. A four-stroke compresses once per 720: on the
    other upstroke the exhaust valve is open and the piston is pushing burnt gas
    out against almost nothing. Leaving the old shape in place put a rhythmic
    mid-range knock on the non-firing revolution, exactly halfway between two real
    bangs - which is heard as a second firing event, and is the reason the engine
    sounded out of time with itself even though every combustion pulse was landing
    on a genuine paid event.

    So the loop is now one full 720-degree cycle. It still spans two crank
    revolutions in two seconds, so the pitch mapping, the rumble and the whirr are
    untouched; what changed is that the load pattern inside it is the four-stroke's:

        cycle    0 ......... 180 ......... 360 ......... 540 ....... 720
        stroke   COMPRESSION  |   POWER     |  EXHAUST    |  INTAKE
        piston   BDC -> TDC   | TDC -> BDC  | BDC -> TDC  | TDC -> BDC
        valves   shut         | shut        | exhaust     | intake
        load     builds       | gives back  | breathing   | breathing

    Built from what actually makes noise while a four-stroke crank goes round:

      * bearing and flywheel rumble  - low, continuous, the bed of the whole thing
      * compression swell            - ONCE per cycle, over the compression stroke,
                                       releasing over top dead centre. This is the
                                       "rrrr ... rrrr" of cranking
      * over-centre knock            - a soft mechanical thud as it goes over the
                                       compression TDC, not a firing event: no bark,
                                       no low pressure body, and about a fifth of
                                       the level
      * exhaust-TDC tick             - the piston also reverses at 540, but with a
                                       valve open and no gas spring behind it. It
                                       gets a tick, bright only and a third of the
                                       knock, because that reversal is real and a
                                       thud there is what caused the problem
      * breathing                    - band-limited hiss over the exhaust and intake
                                       strokes, driven by valve lift times piston
                                       speed, which is the gas actually moving. It
                                       is what fills the non-firing revolution with
                                       something the ear reads as a machine working
                                       rather than as a machine missing a beat
      * gear and cam whirr           - quiet fixed tones, integer cycles so they
                                       loop seamlessly. The cam one is at half the
                                       gear rate because that is what a camshaft
                                       turns at

    Two revolutions per two-second loop, one cycle, so it wraps; one revolution per
    second is close enough to the engine's 1.07 rev/s idle that the rates read as
    this engine at its reference speed.
    """
    rev_hz = 1.0
    dur = 2.0                        # 1 engine cycle = 2 revolutions -> seamless
    n = int(SR * dur)
    period = SR / rev_hz             # one CRANK revolution, in samples
    cycle = 2.0 * period             # one 720-degree ENGINE cycle
    buf = np.zeros(n)
    t = np.arange(n) / SR

    # Where we are in the 720-degree cycle, and in the revolution inside it.
    cyc = (t * rev_hz / 2.0) % 1.0                    # 0..1 over 720 degrees
    theta = 2.0 * np.pi * ((t * rev_hz) % 1.0)        # crank angle, radians
    piston_speed = np.abs(np.sin(theta))              # 0 at both dead centres

    # Compression: a slow asymmetric build over the compression stroke only, then
    # a fast release over the top. Zero everywhere else - there is no second one.
    comp_phase = np.clip(cyc / 0.25, 0.0, None)
    load = np.where(cyc < 0.25 * 0.72, (comp_phase / 0.72) ** 1.8,
                    np.where(cyc < 0.5, np.exp(-(cyc - 0.25 * 0.72) / (0.25 * 0.05)), 0.0))
    load = np.clip(load, 0.0, 1.0)

    # Pumping: how hard the engine is shifting gas. Exhaust and intake only, and
    # shaped by the same raised-cosine lift the simulation runs, times piston
    # speed - so it peaks mid-stroke, where the flow really is fastest.
    lift = np.where((cyc >= 0.5) & (cyc < 1.0),
                    0.5 - 0.5 * np.cos(2.0 * np.pi * ((cyc - 0.5) / 0.25 % 1.0)), 0.0)
    pump = lift * piston_speed

    rumble = periodic(noise(n), lambda v: lowpass(v, 220.0))
    buf += rumble * (0.22 + 0.30 * load + 0.07 * pump)

    # A low tone that sags as the compression load comes on, like the crank
    # being held back - and recovers as it goes over.
    drag_f = 44.0 - 8.0 * load
    buf += np.sin(2.0 * np.pi * np.cumsum(drag_f) / SR) * (0.12 + 0.22 * load)

    # Breathing. Deliberately high and quiet: it must be audible on the non-firing
    # revolution and impossible to mistake for a bang, so it carries no low content
    # at all and sits ABOVE the knock's own tones rather than across them. Air
    # moving, underneath everything else.
    breath = periodic(noise(n), lambda v: highpass(lowpass(v, 3200.0), 1100.0))
    buf += breath * pump * 0.08

    # The over-centre knock, once, at the compression TDC (cycle 180 degrees).
    # Deliberately mid-range and quiet. A low thump here would be a firing pulse,
    # and this layer must never contain one.
    #
    # AND HALF THE LEVEL IT USED TO BE, which is the other half of the fix. This
    # loop FREE-RUNS: Minecraft gives a looping instance a volume and a pitch and
    # no way to seek, so nothing in here is phase-locked to the crank. A percussive
    # event in a free-running loop therefore walks slowly in and out of step with
    # the real combustion pulses, and at four-stroke rates it spends part of that
    # walk sitting squarely between two bangs, which is heard as a firing rate
    # twice what the engine has. The transients have to come from the events; the
    # bed keeps this one as texture and no more.
    add_wrapped(buf, 0.25 * cycle - 0.02 * period,
                mechanical_clack(decay=0.016, length=0.10, tone=1150.0) * 0.16)
    add_wrapped(buf, 0.25 * cycle,
                mechanical_clack(decay=0.030, length=0.14, tone=430.0) * 0.07)

    # The exhaust TDC, at cycle 540 degrees. The piston really does reverse here,
    # so it is not silent - but there is no gas spring behind it, so it is a tick
    # and not a thud: bright only, a third of the knock, and no 430 Hz component.
    add_wrapped(buf, 0.75 * cycle,
                mechanical_clack(decay=0.010, length=0.06, tone=1750.0) * 0.06)

    # Gear whirr: 156 cycles over 2 s, an integer, so it wraps. The cam whirr is
    # at half of it, which is both an integer and the speed a camshaft turns.
    buf += np.sin(2.0 * np.pi * 78.0 * t) * 0.05 * (0.6 + 0.4 * load)
    buf += np.sin(2.0 * np.pi * 39.0 * t) * 0.030 * (0.5 + 0.5 * lift)

    return normalize(body_periodic(buf, 0.5), 0.58)


def engine_combustion_loop():
    """
    Firing so fast the individual events have merged - the aggregate layer.

    Unused by the current engine, which tops out at 3.2 events a second, and
    kept deliberately plain: it exists so that a faster engine, a four-stroke or
    a second cylinder is a tuning change rather than an audio rewrite. The game
    fades it in only above 12 Hz, thinning the one-shots out as it does.

    16 events per second over one second: seamless, and fast enough that the
    events genuinely fuse instead of sounding like a fast train of separate bangs.
    """
    fire_hz = 16.0
    dur = 1.0
    n = int(SR * dur)
    period = SR / fire_hz
    buf = np.zeros(n)

    for i in range(int(round(dur * fire_hz))):
        strength = 0.55 * (1.0 + 0.10 * np.sin(i * 2.399) + 0.05 * np.sin(i * 5.1))
        jitter = 0.0012 * SR * np.sin(i * 1.77)
        add_wrapped(buf, i * period + jitter,
                    combustion_event(strength=strength, thump_f=62.0, bark_level=0.34,
                                     clack_level=0.12))

    buf += periodic(noise(n), lambda v: lowpass(v, 300.0)) * 0.14
    return normalize(body_periodic(buf, 0.60), 0.72)


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


def combustion_pulse(sweep_from, sweep_to, decay, bark_hi, bark_decay, tick_tone, tick_level, peak,
                     blowdown=0.13, blowdown_decay=0.105):
    """
    One charge burning: the PUT.

    The single most important asset in the mod, because it is the engine's voice.
    Every one of these the player hears is one real combustion event, so this has
    to sound like a cylinder firing and like nothing else:

      * it must not be a GUNSHOT      - so the pressure body leads with a 4 ms
                                        raised-cosine attack instead of an
                                        instantaneous onset, and there is no
                                        broadband crack at sample zero
      * it must not be an EXPLOSION   - so the BRIGHT noise decays inside 40 ms and
                                        the only thing that outlasts it is the dark,
                                        quiet blowdown described below
      * it must not be a CLICK        - so the high transient sits 7 ms in, at a
                                        fifth of the level, behind the body
      * it must not be a BASS DRUM    - so the low body SWEEPS downward as the
                                        charge expands rather than holding a
                                        pitch, and it is band-limited noise, not a
                                        pure tone, that carries it out of the port
      * it must not be an ELECTRIC    - so nothing here is periodic; every layer
                        MOTOR           is an impulse response

    The variants differ only in sweep, brightness and tick colour. They are the
    same cylinder firing under slightly different conditions, which is what
    cycle-to-cycle variation in a real single is.

    THE BLOWDOWN, AND WHY A FOUR-STROKE NEEDS IT
    --------------------------------------------
    A four-stroke fires once per 720 degrees, which at this engine's speeds is
    every 1.9 seconds at idle. A pulse tuned for an engine that fired twice that
    often is a short event with a long silence after it, and a short event in a
    long silence does not read as an engine labouring - it reads as something
    tapping. Each bang has to be worth the wait.

    So the pulse now carries the exhaust blowdown: the burnt charge leaving through
    the valve after the bang, dark and quiet and about a tenth of a second long. It
    is deliberately NOT a longer version of the bark. The bark is bright, it is what
    would read as an explosion if it were allowed to ring on, and it still decays
    inside 40 ms. This is underneath it, rolled off above 520 Hz, at an eighth of
    the level - the difference between a crack and a PUTT.
    """
    n = int(SR * 0.46)
    buf = np.zeros(n)

    # 1. Pressure body. Leads, sweeps down, and rises rather than starting.
    add_at(buf, 0, cylinder_thump(f_start=sweep_from, f_end=sweep_to, decay=decay,
                                  length=0.30, attack=0.004))

    # 2. Gas leaving the port, just behind the pressure peak. Short.
    add_at(buf, SR * 0.005, exhaust_bark(lo=170.0, hi=bark_hi, decay=bark_decay, length=0.16) * 0.46)

    # 3. Piston slap / valve gear. Texture only.
    add_at(buf, SR * 0.007, mechanical_clack(decay=0.007, length=0.05, tone=tick_tone) * tick_level)

    # A short breath of low noise underneath, which is what stops the body from
    # sounding like a synthesised tone with things stuck on it.
    t = np.arange(n) / SR
    buf += lowpass(noise(n), 240.0) * np.exp(-t / 0.045) * 0.18

    # 4. The blowdown: burnt gas leaving the port after the event. Dark - rolled
    # off hard above 520 Hz and below 120 - quiet, and the only layer allowed to
    # outlast the bark. Starts 22 ms in, behind the pressure peak, because the
    # valve does not open until the charge has done its work.
    add_at(buf, SR * 0.022,
           highpass(lowpass(noise(int(SR * 0.34)), 520.0), 120.0)
           * np.exp(-np.arange(int(SR * 0.34)) / SR / blowdown_decay) * blowdown)

    return normalize(declick(body(buf, 0.66), ms=4.0), peak)


def engine_fire_1():
    """The baseline pulse: mid sweep, moderately bright port."""
    return combustion_pulse(sweep_from=76.0, sweep_to=41.0, decay=0.072, bark_hi=1500.0,
                            bark_decay=0.026, tick_tone=2300.0, tick_level=0.20, peak=0.76)


def engine_fire_2():
    """A slightly softer cycle: lower sweep, duller port, a touch longer."""
    return combustion_pulse(sweep_from=70.0, sweep_to=37.0, decay=0.082, bark_hi=1250.0,
                            bark_decay=0.030, tick_tone=1950.0, tick_level=0.16, peak=0.73,
                            blowdown=0.15, blowdown_decay=0.120)


def engine_fire_3():
    """A crisper cycle: higher sweep, brighter port, faster decay."""
    return combustion_pulse(sweep_from=82.0, sweep_to=45.0, decay=0.064, bark_hi=1800.0,
                            bark_decay=0.023, tick_tone=2650.0, tick_level=0.23, peak=0.78,
                            blowdown=0.11, blowdown_decay=0.090)


def engine_start():
    """
    The catch: the moment the engine stops being turned and starts pulling.

    Deliberately contains NO firing events. It did before, back when firing lived
    inside a running loop and the transition had to bridge between two recordings.
    It must not now: real combustion pulses are already playing, one per charge, so
    a run-up of synthetic ones on top would double every bang the player hears at
    exactly the moment they most want to hear it clearly.

    What is left is everything else about catching, and it is plenty: the drag
    tone lifting as the load comes off, the intake drawing harder, and the whole
    machine settling into a rhythm. Layered under the real pulses, that reads as
    "and it's running".
    """
    dur = 0.75
    n = int(SR * dur)
    t = np.arange(n) / SR
    buf = np.zeros(n)

    # The crank speeding up as combustion takes over: 34 Hz to 64 Hz, easing out.
    lift = 1.0 - np.exp(-t / 0.20)
    buf += np.sin(2.0 * np.pi * np.cumsum(34.0 + 30.0 * lift) / SR) * (0.30 + 0.22 * lift)

    # Intake: broad, quiet, swelling with the revs.
    buf += lowpass(noise(n), 900.0) * (0.06 + 0.16 * lift) * np.exp(-t / 0.55)

    # One soft mechanical settle as the flywheel takes up the drive.
    add_at(buf, SR * 0.05, mechanical_clack(decay=0.022, length=0.14, tone=520.0) * 0.30)

    return normalize(declick(body(buf, 0.6)), 0.80)


def engine_stall():
    """
    The engine coming to rest after dying on the player.

    Played when rotation finally stops, not when combustion did - by then the
    flywheel has been spinning down for several seconds with the mechanical layer
    following it, so this is the last of that rotation, not a bang. It is uneven
    and drags: something went wrong, and the machine stops like it.
    """
    dur = 0.95
    n = int(SR * dur)
    t = np.arange(n) / SR
    buf = np.zeros(n)

    # The last of the rotation, sagging away with a judder in it.
    fall = np.exp(-t / 0.34)
    judder = 1.0 + 0.22 * np.sin(2.0 * np.pi * 6.5 * t) * fall
    buf += np.sin(2.0 * np.pi * np.cumsum(30.0 * fall + 4.0) / SR) * 0.34 * fall * judder
    buf += periodic(noise(n), lambda v: lowpass(v, 260.0)) * 0.16 * fall

    # Two uneven compression stops as it rocks to a halt, then rest.
    add_at(buf, SR * 0.34, mechanical_clack(decay=0.024, length=0.14, tone=760.0) * 0.34)
    add_at(buf, SR * 0.58, mechanical_clack(decay=0.028, length=0.16, tone=610.0) * 0.40)
    add_at(buf, SR * 0.58, cylinder_thump(f_start=38.0, f_end=26.0, decay=0.045, attack=0.005) * 0.26)

    return normalize(declick(body(buf, 0.6)), 0.70)


def engine_stop():
    """
    A deliberate shutdown coming to rest: the same event without the drama.

    Shorter than a stall, no judder, and it settles on one clean mechanical stop
    rather than rocking against compression twice. The difference between the two
    is the whole information content - the player knows from the sound alone
    whether the engine stopped because they wanted it to.
    """
    dur = 0.70
    n = int(SR * dur)
    t = np.arange(n) / SR
    buf = np.zeros(n)

    fall = np.exp(-t / 0.26)
    buf += np.sin(2.0 * np.pi * np.cumsum(28.0 * fall + 4.0) / SR) * 0.30 * fall
    buf += periodic(noise(n), lambda v: lowpass(v, 240.0)) * 0.12 * fall

    add_at(buf, SR * 0.40, mechanical_clack(decay=0.020, length=0.14, tone=700.0) * 0.34)

    return normalize(declick(body(buf, 0.58)), 0.62)


# Order is NOT load-bearing: main() reseeds the RNG per sound from that sound's
# own name, so adding, removing or reordering an entry cannot silently re-roll the
# noise any other asset is built from. Each file is reproducible on its own.
SOUNDS = {
    # The two continuous layers.
    "engine_mechanical": engine_mechanical,
    "engine_combustion_loop": engine_combustion_loop,
    # One charge burning, three ways.
    "engine_fire_1": engine_fire_1,
    "engine_fire_2": engine_fire_2,
    "engine_fire_3": engine_fire_3,
    # Transitions and the ignition tick.
    "engine_start": engine_start,
    "engine_stall": engine_stall,
    "engine_stop": engine_stop,
    "engine_spark": engine_spark,
}

# Assets from before the audio was split into layers. Removed here so a rerun
# cleans up after itself rather than leaving orphans that sounds.json no longer
# refers to and that nothing would ever play.
RETIRED = ("engine_running", "engine_cranking", "engine_fire_attempt")


def main():
    global rng
    out = os.path.normpath(OUT)
    os.makedirs(out, exist_ok=True)

    for name in RETIRED:
        path = os.path.join(out, name + ".ogg")
        if os.path.exists(path):
            os.remove(path)
            print("%-24s removed" % (name + ".ogg"))

    for name, fn in SOUNDS.items():
        rng = np.random.default_rng(zlib.crc32(name.encode()) ^ 0x0C0FFEE)
        data = fn().astype(np.float32)
        path = os.path.join(out, name + ".ogg")
        sf.write(path, data, SR, format="OGG", subtype="VORBIS")
        print("%-24s %5.2f s  %6d bytes" % (name + ".ogg", len(data) / SR, os.path.getsize(path)))


if __name__ == "__main__":
    main()
