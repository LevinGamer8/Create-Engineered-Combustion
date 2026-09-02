# Milestone 15B follow-up — the four-stroke sound audit

The first real in-game R1 test found the engine visually and mechanically
correct and acoustically wrong: the firing felt out of time with itself even
though every combustion was landing on a genuine event.

This is what the audit found, what was changed, and what was deliberately not.

---

## 1. What drives each effect, before any change

Traced from the source, not from memory.

| Effect | Driven by | Rate |
| --- | --- | --- |
| spark particle | `sparkMask` ← `EngineState.sparkEventIds[c]++` at `crossedForward(180)` on the **720°** cycle | once per cylinder per cycle |
| spark sound | the same bit, and **only while the engine is not RUNNING** | once per cycle, never on a running engine |
| combustion flash | `combustionMask` ← `combustionEventIds[c]++` | once per cylinder per cycle |
| combustion bang | the **same bit**, `CombustionAudio.onCombustion` | once per cylinder per cycle |
| mechanical loop | `EngineLoopSound`, pitched by `mechanicalLayerPitch(rpm)` | continuous |
| start / stall / stop | phase transitions | one-shot |

So the answers to the audit's own questions:

**Is the bang driven by a genuine paid combustion?** Yes. `combustionEventIds[c]++`
happens at exactly one place in `EngineState`, inside the branch where
`fuel.consume(FUEL_PER_COMBUSTION_MB)` succeeded. The torque, the fuel, the
start progress, the flash and the bang are all reactions to that one increment.

**Is anything derived from RPM, TDC, crank angle, `sparkMask`, or per
revolution?** No. `CombustionAudio` measures its rate *from the events*
(`20.0F / (now - lastEventTick)`), and uses that rate only for mixing. Pitch
moves by at most ±10 % across the whole speed range and is explicitly not how
the rate is conveyed.

**Were the sound and the visual synchronised?** Yes, and they cannot come
apart: they are two reads of one bit in one packet.

**Was there a combustion sound on the non-firing TDC?** Not a *combustion*
sound. But there was a sound.

---

## 2. The actual fault

**`engine_mechanical.ogg` encoded a two-stroke.**

The loop was built as two crank revolutions carrying **one compression swell and
one over-centre knock each**. That was right for the engine that fired every
360°. A four-stroke compresses once per **720°**: on the other upstroke the
exhaust valve is open and the piston is pushing burnt gas out against almost
nothing. The production simulation already agreed - `compressionTorqueAt` uses
`gasSpringShape`, which is non-zero only on `[0, 180)` - but the sound asset did
not.

Measured on the committed asset, band-limited to where the knock lives:

```
mech_old.ogg   2.00 s      onsets >= 4 dB / 25 ms in 700-1600 Hz
    cycle 262.2 deg   rise  +9.5 dB   level 0.0109
    cycle 623.2 deg   rise +12.2 dB   level 0.0140      <- 361 deg apart
```

Two percussive events per engine cycle, one per revolution, against one real
bang per cycle. And at the volumes the game actually mixes at, the margin
between them was small:

```
BEFORE   in 700-1600 Hz: a real combustion is only +5.0 dB over the knock
         full band     : a real combustion is only +7.6 dB over the bed
```

A −5 dB percussive event landing between two bangs is heard as a bang.

**And the loop free-runs.** Minecraft gives a looping sound instance a volume
and a pitch and no way to seek, so nothing in the mechanical layer is
phase-locked to the crank. At idle the loop's knock repeats about every 0.94 s
against a real firing every 1.875 s, so the phantom walks slowly through the
gap: sometimes on top of a bang, sometimes exactly between two. That drift is
why it read as "out of sync" rather than as a steady doubled rate.

**Verdict on the audit's A-or-B question: both, and A is the larger half.** It
is a real defect - an asset encoding the wrong engine cycle - not merely old
tuning that no longer suits. The tuning was also wrong for the new cadence, and
that is fixed too, but fixing only the tuning would have left the phantom in.

---

## 3. What changed

### The mechanical loop, rebuilt on the 720° cycle

Still 2.0 s, still two crank revolutions, so the pitch mapping, the rumble and
the whirr are untouched. What changed is the load pattern inside it:

```
cycle    0 ......... 180 ......... 360 ......... 540 ....... 720
stroke   COMPRESSION  |   POWER     |  EXHAUST    |  INTAKE
piston   BDC -> TDC   | TDC -> BDC  | BDC -> TDC  | TDC -> BDC
valves   shut         | shut        | exhaust     | intake
load     builds       | gives back  | breathing   | breathing
```

- **one** compression swell and **one** over-centre knock per cycle, at the
  compression TDC where a knock belongs
- the knock at **half** its old level, because a free-running percussive event
  must not compete with the real bangs whatever phase it drifts into
- a much quieter, bright-only **tick at the exhaust TDC**: the piston really
  does reverse there, but with a valve open and no gas spring behind it, so it
  is a tick and not a thud
- **breathing** across the exhaust and intake strokes, band-limited to
  1100–3200 Hz and driven by valve lift × piston speed, which is the gas
  actually moving. It fills the non-firing revolution with something the ear
  reads as a machine working, and it carries no low content at all, so it can
  never be mistaken for a bang
- a **cam whirr** at half the gear whirr's rate, which is what a camshaft turns
  at, and an integer number of cycles over the loop so it still wraps

After:

```
engine_mechanical.ogg   2.00 s      onsets >= 4 dB / 25 ms in 700-1600 Hz
    cycle 172.4 deg   rise +10.2 dB   level 0.0064      <- the compression TDC
    cycle 396.9 deg   rise  +5.0 dB   level 0.0032      \
    cycle 581.9 deg   rise  +4.2 dB   level 0.0034       > breathing swells
    cycle 610.6 deg   rise  +4.0 dB   level 0.0085      /

AFTER    in 700-1600 Hz: a real combustion is +9.4 dB over the knock
         full band     : a real combustion is +10.3 dB over the bed
```

One transient per cycle instead of two, at less than half the level, and the
margin over a real bang roughly doubled.

### The combustion pulse, given a blowdown

A four-stroke single fires every 1.9 s at idle. A pulse tuned for an engine
that fired twice as often is a short event in a long silence, and a short event
in a long silence does not read as an engine labouring.

So each pulse now carries the **exhaust blowdown**: the burnt charge leaving
through the valve after the bang, rolled off above 520 Hz and below 120, at an
eighth of the level, about a tenth of a second long. Deliberately *not* a longer
bark - the bark is the bright layer that would read as an explosion if it rang
on, and it still decays inside 40 ms. The body's own sweep was lengthened a
little with it.

```
              peak    t-30 dB    centroid
before        0.532   132.8 ms     450 Hz
after         0.529   151.4 ms     433 Hz
```

Longer and darker at the same peak: the difference between a crack and a putt.

### The sparse-pulse gain

One new mixing term, `EngineTuning.combustionSparseGain(rateHz)`: full
`SOUND_COMBUSTION_SPARSE_GAIN` (1.35) at and below 2 Hz, sliding to 1.0 by 6 Hz.

It is driven by the rate measured from the events themselves, it scales a real
pulse, and it can never add one. What it buys is that one term now separates the
layouts instead of one pulse design having to suit all of them:

| | 64 RPM | 128 RPM | 192 RPM |
| --- | --- | --- | --- |
| R1 | 0.53 Hz, gain 1.35 | 1.07 Hz, 1.35 | 1.60 Hz, 1.35 |
| R2 | 1.07 Hz, 1.35 | 2.13 Hz, 1.34 | 3.20 Hz, 1.25 |
| R3 | 1.60 Hz, 1.35 | 3.20 Hz, 1.25 | 4.80 Hz, 1.11 |
| R4 | 2.13 Hz, 1.34 | 4.27 Hz, 1.15 | 6.40 Hz, **1.00** |

The lumpy single gets the weight. The smooth four is left exactly where it was.

### Nothing else

No change to the four-stroke firing physics. No change to what drives any
effect. No faked events, no rate implied by pitching a loop, no combustion
content added to the mechanical layer.

---

## 4. Cadence, measured on a render of the actual mix

Rendered offline from the shipped assets and the shipped mix constants - the
mechanical loop at `SOUND_MECHANICAL_RUNNING_VOLUME`, plus one one-shot per real
combustion event - then run through an onset detector. Intervals in seconds at
idle:

```
R1    1.88 1.88 1.88 1.88 1.88                one bang per 720 deg
R2    1.41 0.47 1.41 0.46 1.41 0.46           540 then 180: the uneven twin
R3    0.62 0.62 0.63 0.62 0.63                240 even
R4    0.46 0.48 0.46 0.47 0.47                180 even
```

All four are intact and audibly distinct, R2's limp included. `EngineAudioTests`
pins the rates these come from.

---

## 5. Engine RPM against Create RPM

The play-test raises the fair question behind all of this: a real small engine
turns at 1000–7000 RPM and fires 10–50 times a second. This one turns at 64–192
and fires 0.53–6.4 times a second, because its crankshaft speed *is* its Create
output speed.

**Recommendation: keep the direct mapping (option A). Do not introduce an
internal reduction ratio now.**

What the direct mapping buys:

- **One clock.** The whole of Milestone 15B rests on there being a single
  authoritative cycle position that everything else divides down from - the
  stroke, the cam, the valves, the firing identity, the client anchors. A
  reduction ratio adds a second rate that all of those have to agree about, and
  a valvetrain half a cycle out of phase with its own crank is the exact class
  of bug 15B was built to make impossible.
- **A watchable machine.** 15B's headline is a visible valvetrain: cam, pushrod,
  rocker, valve, each a function of the cycle. At 8× the speed it is a blur.
- **A legible number.** Every other machine in a Create pack lives at
  8/16/32/64/128/192 RPM. An engine that read 1500 on the goggles and drove a
  Millstone at 64 would need the ratio explained before the first use.

What it costs is exactly what the play-test heard: a four-stroke at these speeds
is a **big slow single**, not a small fast one. That is a coherent machine - a
stationary hit-and-miss engine or a ship's auxiliary fires at a few Hz and
sounds like a train of separate putts - and the sound now commits to being that
engine rather than a lawnmower played too slowly.

**Option B, an internal crank speed above the output speed, honestly costed.**
It would give a conventional engine note. It would also mean: re-deriving every
angle-driven animation at the internal rate and losing the valvetrain to motion
blur; 8× the angular drift per unit of latency, so the client phase budget needs
revisiting; every per-combustion constant - fuel, oil, cylinder wear, the
active-cylinder allowance - divided by the ratio, which is a full re-run of
Milestone 13's balance; and the R1→R4 smoothness ladder flattened, because more
and smaller impulses per output revolution is precisely what removes the ripple
that ladder is made of. It is possible. It is not a sound fix, it is a new
milestone, and it would trade away two of 15B's deliverables to get it.

**Option C, a cosmetic multiplier on the audio only, is rejected outright.**
Implying a firing rate the engine does not have is the one thing this
architecture forbids, and it is what the two-layer split exists to prevent.

If the character is ever revisited, the cheaper and more honest lever is to
change what the engine *is* - lean further into the large slow single, in the
assets and in the Ponder text - rather than to change how fast it turns.

---

## 6. What is still unverified

No Minecraft was launched to produce this. The event wiring, the rates, the
cadences and the level margins above are all measured, but measured offline:
from the source, from the assets, and from a render of the mix maths. Whether
the result *sounds* right in game is F11–F15 of the acceptance checklist.
