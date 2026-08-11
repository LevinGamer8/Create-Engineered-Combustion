# Milestone 9 - Engine Stability

A blocking correctness pass before Survival testing. Two of the four parts are
bug fixes to behaviour that manual testing found wrong; two are quality-of-life
and feel. Nothing was added: no wear, no cooling, no second cylinder, no
four-stroke timing, and no recipe or progression was touched. Every Survival
Foundation system from milestone 8 is intact.

---

## 1. The multi-engine free-power exploit

### What happened

Two or more engines on one Create kinetic network. One has gasoline and runs;
the others run dry. The dead engines keep being rotated by the shared shaft - and
kept contributing a full engine's worth of Stress Capacity while doing it.

Ten engines, one tank of fuel, ten engines of power.

### Root cause

Three lines, in two files.

`EnginePhase#generatesPower()` counted **COASTING** as a generating phase:

```java
public boolean generatesPower() {
    return this == RUNNING || this == COASTING;
}
```

and `EngineState#tickSimulation` pinned a coasting engine's speed to whatever
Create was doing to the shaft:

```java
if (externallyDriven && !phase.simulationOwnsSpeed())   // COASTING is not in that set
    simulatedRpm = mechanicalRpm;
```

so `updatePublishedRpm` published it:

```java
float target = phase.generatesPower() ? simulatedRpm : 0.0F;
```

An engine that ran out of fuel went `RUNNING -> COASTING`, which is correct. But
because a neighbour kept the shaft turning, its simulated speed was overwritten
with the network's speed *every tick*. It never decayed, so it never fell below
stall speed, so it never left COASTING. It sat there for ever, publishing the
network's own speed back as its own generated speed.

Create then did exactly what it should. From `KineticNetwork`:

```java
public float getActualCapacityOf(KineticBlockEntity be) {
    return sources.get(be) * getStressMultiplierForSpeed(be.getGeneratedSpeed());
}
```

Capacity per RPM times the source's generated speed. The dead engine claimed a
generated speed, so it was paid for one.

### The fix: one predicate

`EngineState#isActivelyGenerating()` is now the mod's single authority on whether
an engine is producing power. It is evaluated once per server tick, latched into
a field, and synchronised, so client and server cannot hold different opinions
and no subsystem assembles its own version out of phase, fuel and speed.

| | old condition | new condition |
|---|---|---|
| generated speed | `phase == RUNNING \|\| phase == COASTING` | `isActivelyGenerating()` |
| stress capacity | *(none - inherited)* | `isActivelyGenerating()` |
| stress impact | *(none registered)* | `!isActivelyGenerating()` |
| HUD | phase | `isActivelyGenerating()` |
| audio | phase | real combustion events |

The predicate, in the order the machine imposes the conditions:

```java
private boolean evaluateActiveGeneration() {
    return phase.mayGenerate()          // caught, and still running - RUNNING only
        && structureValid               // piston in a cylinder, exactly one flywheel
        && ignitionEnabled              // the effective ignition, module or switch
        && sparkPlugInstalled           // somewhere for the coil to discharge
        && fuelAvailable                // a charge can still be paid for
        && simulatedRpm >= STALL_RPM    // above stall, and turning forwards
        && combustionIsCurrent();       // a charge really burned, recently
}
```

The last condition is the one an external source cannot fake. A dead engine spun
at 200 RPM by its neighbour satisfies every mechanical test in the list and fails
that one. Its allowance is scaled by speed -
`GENERATION_COMBUSTION_REVOLUTIONS = 2.5` revolutions, capped at 60 ticks -
because the firing interval is 18.75 ticks at idle and 6.25 at full throttle, and
a fixed tick budget would be far too tight at one end and far too slack at the
other.

Note what is deliberately **not** in the list: whether the engine is externally
driven. Two fuelled engines on one shaft are both genuinely burning fuel and only
one of them can be Create's source. Being spun by a neighbour is not
disqualifying; producing no combustion is.

### Generated speed and capacity for an inactive engine

Both are zero, and they are gated independently:

* `EngineState#updatePublishedRpm` publishes `0` unless actively generating, so
  `EngineFlywheelBlockEntity#getGeneratedSpeed()` answers 0 and Create's
  `getActualCapacityOf` multiplies whatever it has cached by zero;
* `EngineFlywheelBlockEntity#calculateAddedStressCapacity()` also returns 0. This
  is belt as well as braces: Create caches the per-source capacity in
  `KineticNetwork#sources` and only refreshes it when told to, so gating the
  cached value alone would not have been enough - and gating both means the
  invariant survives even if Create stopped scaling capacity by generated speed.

The engine remains fully rotatable. It is a passive kinetic block, and
`GeneratingKineticBlockEntity#applyNewSpeed` keeps a generator attached to its
external source precisely while it generates 0.

```
Engine A: gasoline, running       Engine B: empty
  Mechanical RPM   60               Mechanical RPM   60      <- the network turns it
  Generated RPM    60               Generated RPM     0
  Capacity      1920 su             Capacity          0 su
  Turned by     Engine               Turned by     External
```

### An overspeed cap, while we were here

An engine may now claim to generate no more than its own combustion could
sustain: `min(simulatedRpm, targetRpm + GOVERNOR_RANGE_RPM / 2)`, which is where
the governor's torque reaches zero. It never binds in normal running - an engine
sits on its target with a couple of RPM of ripple - and only bites when something
else is spinning the engine faster than it could ever drive itself. Without it,
motoring an idling engine at 200 RPM would have tripled the capacity it hands out
for no extra fuel.

### Passive drag - yes, implemented

An engine that is not generating now imposes a small parasitic load on whatever
is turning it: compression, bearing friction, pumping losses.
`PASSIVE_DRAG_STRESS_PER_RPM = 1.0`, against the 32.0 per RPM a running engine
supplies, so one dead engine at 64 RPM costs 64 SU where one running engine
supplies 2048 SU.

It is registered per block like any Create impact, and switched per block entity
in `EngineFlywheelBlockEntity#calculateStressApplied()` - zero while generating,
because a running engine already fights exactly this friction inside its own
simulation and billing the network as well would be charging for it twice.
Create refreshes the value at precisely the moments it changes, through
`updateGeneratedRotation` and `applyNewSpeed`, so nothing has to poll it.

**This is the one balance-shaped change in the milestone**, and it is a single
constant if it wants tuning. Ten dead engines cost 640 SU. Around forty would
overstress a single-engine network - which is realistic, and is the intended
discouragement, but is worth knowing before building a wall of them.

`hideStressImpact()` stays true on the Flywheel block so the *item* tooltip does
not print a static impact beside "Generated Speed"; the live figure still appears
on Create's own goggle overlay, on exactly the engines being charged for it.

---

## 2. The RPM snap

### What happened

An engine idling at ~64 RPM, connected to a faster external network that drives
it at 200. Disconnect the network. The engine jumped straight back to ~64.

### Root cause

Two numbers for one crankshaft. `mechanicalRpm` followed Create; `simulatedRpm`
was the engine's own integrated speed. While an external source drove the engine,
`simulatedRpm` was overwritten each tick *only in phases the simulation did not
own* - so a RUNNING engine kept its own stale ~64 underneath the imposed 200. The
moment the external source vanished, the simulation resumed from the number that
had been hiding under it.

### The fix: one momentum

`simulatedRpm` is now **the** angular velocity of the crankshaft, in every phase,
and `EngineState#tickRotation` - which runs on both sides - reconciles it with
Create once per tick:

```java
public void tickRotation(float shaftSpeed, boolean shaftDriven, boolean externallyDriven) {
    this.externallyDriven = externallyDriven;
    this.freeRotation = !shaftDriven;
    if (shaftDriven)
        absorbImposedSpeed(shaftSpeed);
    advanceCrankAngle(freeRotation ? simulatedRpm : shaftSpeed);
}
```

`absorbImposedSpeed` takes on Create's speed unless the speed is already this
engine's own work. Two exemptions, both necessary:

* **the engine is the network's source** - then Create's speed came *from* the
  engine, and absorbing it back would pin the engine to its own published value
  and silently cancel the load sag that makes it respond to work;
* **combustion has already carried it faster** than it is being turned - a firing
  kick during a start, or a spin-up. Without this a hand crank would hold a
  running engine down at cranking speed for ever.

Sign is carried through untouched.

### What happens on disconnect

When nothing on the network is driving the shaft, the engine free-runs on its own
momentum and the crank angle advances from `simulatedRpm` instead of from Create.
Measured, from the regression test:

```
200 RPM -> 199 -> 180 -> 162 -> 146 -> 131 -> 117 -> 104 -> ... -> settles at 62.5
```

against an idle target of 64. With the ignition off it runs the same curve all
the way to a complete stop. Driven backwards at -120 RPM it coasts back up
towards zero **without ever crossing into forward rotation**.

Two supporting changes made that possible:

* `COASTING` and `CRANKING` now end at `REST_RPM` (1 RPM) rather than at
  `STALL_RPM` (10). Stalling is a question about *combustion* - below 10 RPM no
  charge can carry the engine to the next one - and coming to rest is a question
  about *rotation*. Ending the phase at stall speed called a visibly turning
  flywheel stopped, and `stop()` zeroed its speed, which snapped away the last of
  every spin-down.
* The speed ceiling in `integrate()` never *reduces* an existing speed
  (`max(speedLimitRpm, |simulatedRpm|)`), so an engine a fast network has oversped
  coasts back down through friction instead of being clamped. `setSimulatedRpm`
  was widened to `ABSOLUTE_MAX_RPM` for the same reason - a chunk reload was the
  one path that could still hide a snap.

An overstressed network is treated as *held at zero*, not absent
(`shaftDriven = shaftSpeed != 0 || isOverStressed()`), so a jammed network stops
the engine rather than releasing it to freewheel.

### The client

A freewheeling engine generates nothing, so Create has no speed left to
synchronise for it. The client integrates the spin-down itself, through the same
`integrate()` with no combustion and no network load - a freewheeling engine is
by definition on no network, so its load factor really is zero. It starts from a
speed the server synchronised at the moment generation stopped and is confirmed
every `COAST_RESYNC_INTERVAL = 20` ticks, roughly eight updates over a whole
spin-down. `syncAndRearmResync` restarts the timer on *every* update, not only
the timed ones, or the first confirmation of a coast could have arrived after the
coast was over.

---

## 3. Ignition on by default

A fresh Crankshaft is placed with **Control: Manual, Ignition: On**.

The switch was never a start button. Leaving it on starts nothing: the engine
still needs a valid structure, a Spark Plug, gasoline, and several successful
cranking cycles before it can catch. All an off-by-default switch achieved was
one mandatory click on every engine a player ever built.

```
build engine -> fill gasoline and oil -> hand crank -> it starts
```

Off is still a real, sticky choice. The mechanism is one line in `read`:

```java
if (tag.contains(KEY_MANUAL_IGNITION))
    manualIgnition = tag.getBoolean(KEY_MANUAL_IGNITION);
```

`write` always emits the key, so any engine that has ever been saved or
synchronised carries its own answer and gets it back verbatim - including one the
player deliberately switched off, across chunk reloads and restarts. A tag
without the key is not an engine with the ignition off; it is not an engine's
saved state at all, and the field initialiser is the right answer for a fresh
one. Reading unconditionally is what would have quietly turned "new engines start
switched on" into "every engine loads switched off". The same rule handles a
Crankshaft item that carries block entity data, should one ever do so.

The Redstone Control Module is untouched:

| module | ignition | throttle |
|---|---|---|
| none | manual switch, **on** by default | Carburetor lever |
| IGNITION mode | redstone | Carburetor lever |
| THROTTLE mode | manual switch (authoritative) | redstone |
| combined | redstone | redstone |

---

## 4. Engine feel - the audio, rebuilt in layers

### The problem

One loop for cranking, another for running, swapped at a phase change. The
running loop had firing baked into it at a fixed 8 Hz, so:

* the audible firing rate was a property of the *recording*, pitched up and down,
  not of the engine. Sweeping the throttle changed the pitch of a rhythm, not the
  rhythm;
* an engine that ran out of fuel went on sounding like it was burning some, until
  it fell far enough to trigger the swap;
* starting or stopping combustion replaced the entire sound of the machine.

The engine fires **once per crankshaft revolution**: 1.07 times a second at idle,
3.2 at full throttle. The ear resolves every one of those. The correct sound is a
train of separate bangs over a mechanical bed - `PUT ... PUT ... PUT` at idle,
`PUT-PUT-PUT-PUT` with the throttle open - and never a smooth loop.

### The architecture

Two independent layers, each with its own lifetime and its own clock.

**A. Mechanical rotation** - `engine_mechanical`, one continuous loop, pitched by
mechanical RPM through `EngineTuning#mechanicalLayerPitch`. It plays whenever the
crank turns: cranking, starting, running, coasting, or being motored by another
engine. It contains **no combustion whatsoever** - bearing and flywheel rumble, a
compression swell once per revolution, a soft over-centre knock, a quiet gear
whirr. That is what lets a fuel-starved engine still being spun sound like the
dead weight it is. Volume is state-dependent:

| state | volume | why |
|---|---|---|
| cranking / starting | 0.42 | it is the entire sound of the machine |
| coasting | 0.34 | nothing masking it, nothing driving it |
| running | 0.26 | a *bed*; the pulses are the voice |

One reference speed for every state, so cranking, running and coasting are one
continuous curve and no state change can make the pitch jump.

**B. Combustion pulse** - `engine_fire`, a short positional one-shot, played once
per charge that actually burned. Driven from `EngineState#getCombustionEventId()`,
the same server-authoritative counter that consumed the fuel, delivered the
torque, advanced the start attempt and lit the chamber flash. There is no audio
timer anywhere in the mod, and nothing re-derives when a combustion "should" have
happened from the client's crank angle.

**C. Start / catch** - `engine_start`, on the `STARTING -> RUNNING` transition.

**D. Stop / stall** - `engine_stop` and `engine_stall`, when rotation finally
comes to rest.

### Positional origin

| layer | origin |
|---|---|
| combustion pulse | Cylinder block, 0.78 up - the combustion chamber, under the head |
| mechanical loop | Crankshaft block centre - bearings and flywheel |

A block and a half apart on a five-block-tall engine, and audible.

### What each state now sounds like

| state | sound |
|---|---|
| cranked, ignition off | mechanical only: `rrrr... rrrr... rrrr...` |
| starting | `rrrr...` + a spark tick + `PUT` per real firing, then the catch |
| running, idle | `PUT ... PUT ... PUT` over a quiet bed |
| throttle opened | the same pulses, arriving faster, because the engine fires faster |
| coasting | pulses stop dead; the bed carries on and slows with the flywheel |
| motored, no fuel | mechanical only. No pulses, no capacity, no flashes |

The coasting row is the important one: the player *hears* combustion stop while
the engine is still physically spinning.

### Higher RPM

Pulse cadence follows the events, exactly. At 64 RPM that is 1.07 pulses a
second; at 192 RPM, 3.2. Pitch bends by at most a tenth across the whole range
(`SOUND_COMBUSTION_PITCH_RANGE`) and is emphatically not how the rate is
conveyed - the rate is conveyed by there being one pulse per combustion.

### Scaling past one slow cylinder

`CombustionAudio` measures the rate the events are actually arriving at, from the
intervals between them. Above `SOUND_COMBUSTION_PULSE_MAX_RATE_HZ = 12` it thins
the one-shots by an integer stride while `engine_combustion_loop` fades in
underneath them, reaching full at 24 Hz. The decay of the measured rate needs no
timer: an engine that last fired *n* ticks ago cannot be firing faster than once
every *n* ticks, so capping the rate at that ceiling each tick makes it fall off
on its own.

The current engine tops out at 3.2 Hz, so all of that is dormant. It exists so
that a four-stroke, a faster engine or a second cylinder is a tuning change
rather than an audio rewrite.

### Sound assets

All original, all synthesised from noise, impulses and filters by
`tools/generate_sounds.py`. Nothing sampled or recorded.

The generator now seeds a fresh RNG per sound, from that sound's own name, so
**milestone 7's rule about appending new sounds at the end no longer applies**.
Adding, removing or reordering an entry can no longer re-roll the noise any other
asset is built from, and each file is reproducible on its own. A rerun also
deletes the three retired assets rather than leaving orphans behind.

| file | status |
|---|---|
| `engine_mechanical.ogg` | **new** - rotation, no combustion |
| `engine_fire_1/2/3.ogg` | **new** - the combustion pulse, three variants |
| `engine_combustion_loop.ogg` | **new** - dormant aggregate layer |
| `engine_start.ogg` | **rebuilt** - no longer contains firing |
| `engine_stall.ogg` | **rebuilt** - rotation dying, not a bang |
| `engine_stop.ogg` | **rebuilt** - the same, without the drama |
| `engine_spark.ogg` | unchanged |
| `engine_running.ogg` | **removed** |
| `engine_cranking.ogg` | **removed** |
| `engine_fire_attempt.ogg` | **removed** |

The pulse is the asset that matters, and it is shaped against the four things it
must not sound like:

| must not be | how it is avoided | measured |
|---|---|---|
| gunshot | 4 ms raised-cosine attack on the pressure body; no broadband crack at sample zero | 9 ms to peak |
| explosion | noise decays inside 40 ms; no long tail | -20 dB at 62-73 ms |
| click | the high transient sits 7 ms in, at a fifth of the level, behind the body | centroid 331-425 Hz |
| bass drum | the low body *sweeps* downward as the charge expands; band-limited noise, not a pure tone, carries it out of the port | 83 % of energy below 200 Hz |
| electric motor | nothing periodic; every layer is an impulse response | - |

Three variants differ only in sweep, brightness and tick colour - the same
cylinder firing under slightly different conditions. Minecraft picks one per play
from the three files behind the `engine_fire` event, and
`CombustionAudio#playPulse` adds +-4.5 % pitch and +-10 % volume on top. Both
halves matter: a real single-cylinder engine is never metronomic, and one sample
on a metronome is exactly what that sounds like.

`engine_start`, `engine_stall` and `engine_stop` were rebuilt because they used
to contain synthetic firing trains. They must not now: real pulses are already
playing, one per charge, so a run-up of fake ones on top would double every bang
at the moment the player most wants to hear it clearly. What is left is
everything *else* about catching and stopping - the drag tone lifting as the load
comes off, the intake drawing harder, the rotation sagging away, the mechanism
rocking to rest - which layers cleanly under the real events. A stall judders and
stops against compression twice; a deliberate shutdown settles once, cleanly. The
difference is the information: the player knows from the sound alone whether the
engine stopped because they wanted it to.

---

## 5. Goggles

The main overlay gained two lines:

```
Engine
  State: Running
  Speed: 60 RPM
  Generation: Active            <- new
  Turned By: External           <- new, only when it is not simply this engine
  Ignition: On
  ...
```

`Generation` is the line to read on a multi-engine network: every engine on a
shared shaft turns at the same speed, so speed alone cannot tell a fuelled engine
from a dead one being spun by its neighbour. Sneaking adds
`Generated Capacity`, which is 0 su for anything inactive however fast the
network is spinning it. The non-goggle hover is unchanged and still says nothing
about numbers.

`RotationSource` gained `MOMENTUM` - nothing is driving the crankshaft and it is
turning on what the flywheel has stored.

---

## 6. Tests

```
javac -d /tmp/ec-sim $(ls src/main/java/dev/engineeredcombustion/content/engine/*.java \
                        | grep -v EngineComponents | grep -v CombustionAudio)
javac -cp /tmp/ec-sim -d /tmp/ec-sim tools/SparkPlugTests.java tools/EngineStabilityTests.java
java  -cp /tmp/ec-sim SparkPlugTests
java  -cp /tmp/ec-sim EngineStabilityTests
```

`tools/EngineStabilityTests.java` is new and covers this milestone's whole
regression matrix. It is an integration-style test, not a mock: `Network`
reproduces two pieces of arithmetic taken verbatim from Create 6.0.10 -
`getActualCapacityOf` is `capacity per RPM * |generated speed|`, and the fastest
generator becomes the network's source - and nothing else. That is the whole of
what the exploit depended on, so testing against it tests the real thing. Mocking
more of Create would be testing the mock.

| test | asserts |
|---|---|
| A | one fuelled engine generates |
| B | an empty engine rotates externally, generates 0, total stays one engine |
| C | two fuelled engines both generate |
| D | a starved engine loses capacity at once, keeps rotating, total falls back |
| E | **ten engines, one fuelled: 1920 su, not 19200 su** |
| extra | fuel starvation ends generation within one tick |
| extra | ignition off ends generation within one tick |
| extra | overspeed does not inflate generated capacity |
| 5 | external drive is absorbed; disconnect coasts smoothly and settles on target |
| 6 | an unlit engine coasts from 200 to a complete stop |
| extra | backwards drive coasts backwards and never rotates forwards |
| extra | a hand crank still starts the engine and is then out-run |

"No snap" is asserted as a *curve*, not a single sample: every step of the
spin-down must be downward and smaller than a threshold. Checking only the first
tick would pass an engine that snapped on the second.

`tools/SparkPlugTests.java` is unchanged in coverage and still passes; only its
harness was updated to the new rotation API.

### Still manual

Everything that needs Create or Minecraft itself. The whole of the audio - no
automated test can say whether an engine sounds right. The goggle overlay layout.
Default ignition on placement, which lives in block entity NBT. And the real
`KineticNetwork` behaviour, which the tests model but do not execute. The manual
matrix in the milestone brief is the checklist for those.

---

## 7. Not in this milestone

Engine Wear, cooling, multiple cylinders, four-stroke timing, recipe or petroleum
rebalancing. `EngineTuning`'s power, RPM, throttle, fuel and oil figures are
untouched; the only new gameplay-visible constant is
`PASSIVE_DRAG_STRESS_PER_RPM`.
