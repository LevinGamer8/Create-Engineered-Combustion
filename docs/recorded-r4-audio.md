# Recorded inline-4 audio (experimental, first pass)

This is the first pass at voicing an engine with **real recordings** instead of
synthetic sound. It is deliberately narrow: one layout, one phase, two recordings.
Its job is to be listened to in game next to the synthetic engines, before the
remaining speed bands are recorded.

## The recordings

Three clips of a warm VW Golf 1 Cabrio four-cylinder petrol engine, recorded by
and for this project. No manufacturer supplied or endorsed them. Files, formats
and licensing are listed in [`assets.md`](assets.md#engine-audio-synthetic-and-recorded).

| Sound event | File | Native speed |
| --- | --- | --- |
| `engine_recorded_r4_idle` | `golf_r4_idle_1000_loop.ogg` | ~1000 RPM |
| `engine_recorded_r4_1500` | `golf_r4_1500_loop.ogg` | ~1500 RPM |
| `engine_recorded_r4_warm_start` | `golf_r4_warm_start.ogg` | registered, **never played** |

The warm start is the real car's electric starter motor. Engines in this mod are
started with a Hand Crank, so playing it would be wrong; it is kept for future
starter-motor work.

## When the recording plays

`RecordedEngineAudio.isActive`:

```
engine.getCylinderCount() == 4
&& engine.getPhase() == EnginePhase.RUNNING
&& engine.isActivelyGenerating()
```

`isActivelyGenerating()` is the existing, server-authoritative, synchronised
predicate for "combustion is genuinely happening": RUNNING, structure valid,
ignition on, fuel or a power stroke still in progress, above stall speed, and at
least one cylinder recently fired. So:

| Engine | Sound |
| --- | --- |
| R1, R2, R3, any phase | unchanged synthetic/event-driven sound |
| R4 STOPPED, CRANKING, STARTING, COASTING | unchanged mechanical/event-driven sound |
| R4 spun by the network without combusting | unchanged mechanical sound (never RUNNING + generating) |
| R4 RUNNING and generating | recorded layers only |

## What the recording replaces

While active, the recording already contains the combustion, the valvetrain and
the block's mechanical texture, so `RecordedEngineAudio.syntheticVoiceAudible` is
false and three synthetic sounds stand down:

- the `ENGINE_FIRE` one-shot per combustion event (`CombustionAudio`);
- the aggregate `ENGINE_COMBUSTION_LOOP` layer, which an inline-4 at full throttle
  (4 x 3.2 Hz = 12.8 Hz) would otherwise just start to fade in;
- the `ENGINE_MECHANICAL` bed (option A: suppressed outright, so the recording is
  heard honestly).

Only audio changes. Combustion events are still generated, fuel still burns,
flashes and particles still happen, wear and phase logic are untouched, and
`CombustionAudio` still measures the firing rate from every event. Nothing in the
simulation reads `RecordedEngineAudio`. `EngineAudioTests` proves this by running
two identical seeded engines, one of which consults the policy every tick, and
requiring bit-identical fuel use, speed, phase and combustion event counters.

The one-shot transition sounds (`engine_start` on catching, `engine_stall` and
`engine_stop` on dying) are unchanged.

## Speed mapping (prototype)

```
sampleRpm = 1000 + clamp((|mechanicalRpm| - 64) / (192 - 64), 0, 1) * 500
```

Idle (64) maps to 1000, full throttle (192) to 1500, linear between, clamped
outside. This is an audio map only. Engine physics, cycle timing, fuel, wear and
Create's output speed are untouched.

## Cross-fade

Equal-power between neighbouring bands. With `t` the position of `sampleRpm`
across the gap between two recordings:

```
lower band weight = cos(t * pi/2)
upper band weight = sin(t * pi/2)
```

The squares sum to exactly 1, which is constant power for two different takes
(uncorrelated signals), so there is no dip in the middle. At 1000 the idle loop
plays alone, at 1250 both sit at 0.707, at 1500 the 1500 loop plays alone.
Volume of each layer is `0.75 * weight`. The recordings keep their own relative
loudness; the 1500 RPM loop is measured at about 5 dB louder on average than
idle, as a real engine under more throttle is.

## Pitch

```
pitch = clamp(sampleRpm / nativeRpm, 1/1.15, 1.15)
```

So each recording is only nudged towards the target speed: the idle loop goes at
most to 1.15x, the 1500 loop down to 0.87x. At the midpoint both are on their
clamps, and past it they are fading out. The cross-fade, not pitch, carries the
change. The existing dry and worn roughness wobbles are applied on top, as for
every other layer.

## Start and stop

All layers fade at the existing `SOUND_FADE_PER_TICK` (0.08 per tick), so the
0.75 recorded level takes about nine ticks.

- **Catching:** the tick the engine is RUNNING and generating, the mechanical bed
  starts fading out and both recorded layers start fading in (the band with zero
  weight fades straight back to silence). The hand-crank sound hands over to the
  recording with no gap and no pop. `golf_r4_warm_start.ogg` is not used.
- **Ignition off, out of fuel, stall:** the engine leaves RUNNING or stops
  generating, the recorded layers fade out, and the mechanical layer fades back
  in at its coasting level and follows the crank down.

A recorded layer is kept alive, silent, while its weight is zero but the engine
is still running in recorded mode (`EngineSoundLayer#isWanted`). Retiring it
would restart it whenever the speed rippled across the end of the cross-fade.

## Adding the next recordings

1. Drop the loop into `assets/engineered_combustion/sounds/`, add a sound event
   to `ECSounds`, an entry to `sounds.json` and subtitles.
2. Append a `Band` to `RecordedEngineAudio.R4_BANDS` (ascending order).
3. Add one `EngineSoundLayer` constant bound to that band index.
4. Widen the speed map (`SAMPLE_RPM_AT_FULL_THROTTLE`, or a non-linear map).

`bandWeight` already walks any number of ascending bands and only ever blends
the two neighbours; `EngineAudioTests` checks it with a five-band list.

## Tests

`src/simulationTest/java/EngineAudioTests.java`, part of `./gradlew simulationTest`:

- the activation condition over every layout, phase and generation flag;
- real simulated R1, R2 and R3 at idle and full throttle never select it;
- a real running R4 does, continuously, at both ends of the throttle;
- R4 cranked with ignition off, and R4 during a real start, never select it
  before RUNNING;
- R4 coasting after ignition off does not;
- R4 motored at 128 RPM with no fuel, and R4 that runs dry while motored, do not;
- the synthetic voice is suppressed for a running R4 only;
- consulting the policy changes nothing in the simulation, and a recorded R4
  still fires every cylinder and burns fuel;
- the speed map's endpoints, clamps and monotonicity;
- weights in 0..1, equal power everywhere, no amplitude dip, no step larger than
  0.002 per 0.5 RPM, and the same continuity from mechanical RPM to volume;
- pitch within its clamps and unshifted at each native speed;
- a five-band list only ever blends neighbours, at constant power.
