# Milestone 12 — Hardening

A correctness, performance, test and licensing pass over the state of `main`
after the modular inline engines of milestone 11. No new blocks, no new items, no
new player-facing mechanics: seven defect classes fixed, the test infrastructure
made part of the build, and the project's licensing written down.

The theme running through the first six fixes is the same one: **two things that
were being decided by one signal.** Capacity and speed. Layout and visibility.
"Can a new charge be lit" and "is a charge already burning". Each fix separates a
pair that was never actually the same question.

---

## Fix 1 — Stress Capacity invalidation

### Cause

Create does not ask a source what its capacity is. It caches one figure per
source in `KineticNetwork#sources` and multiplies it by that source's generated
speed on demand:

```java
public float getActualCapacityOf(KineticBlockEntity be) {
    return sources.get(be) * getStressMultiplierForSpeed(be.getGeneratedSpeed());
}
```

That cache is refreshed only when something explicitly refreshes it, and the only
thing that did was the engine republishing its **speed**, through
`GeneratingKineticBlockEntity#updateGeneratedRotation`.

Speed and capacity are not the same event. This mod scales capacity by
`EngineState#getFiringCylinderCount()`, so:

- an inline-4 runs, all four cylinders firing, 4× capacity registered;
- another source on the network holds the shaft at a steady speed;
- a Spark Plug is pulled;
- the engine drops to three firing cylinders — a quarter of its capacity — but
  its published RPM does not move by a single quantum, because the speed is being
  imposed from outside and the quantised published value is unchanged;
- nothing refreshes the cache, so the network is still told the engine supports
  four cylinders' worth of machinery.

### Fix

The tick now separates three questions that were being answered by one:

| Question | Signal |
| --- | --- |
| generated speed | `EngineState#tickSimulation` returns true |
| capacity basis | `getFiringCylinderCount()` changed |
| passive load | `isActivelyGenerating()` changed |

A speed change still goes through `updateGeneratedRotation`, which already
refreshes both stress figures — so the two paths must not be doubled up, and are
not. A change to the capacity basis alone goes through the new
`EngineFlywheelBlockEntity#onEngineCapacityChanged()`, which does only the stress
half of what `updateGeneratedRotation` does:

```java
notifyStressCapacityChange(calculateAddedStressCapacity());
getOrCreateNetwork().updateStressFor(this, calculateStressApplied());
```

Both of those recompute the network total themselves (`updateCapacityFor` calls
`updateCapacity`, `updateStressFor` calls `updateStress`), so nothing further is
needed. It deliberately does **not** call `updateGeneratedRotation`, which would
re-evaluate the generated speed, potentially call `applyNewSpeed` — detaching and
re-attaching the whole kinetic network — queue rotation indicators and send a
block entity update, all for what is only a multiplier.

It is guarded on `hasNetwork()`, because `getOrCreateNetwork()` would otherwise
build a network for a block that is not on one and then hand it a capacity to
remember.

### Regression test

`EngineCapacityTests` — in particular `capacityChangesWhileSpeedIsPinned()`,
which holds the engine at a fixed external speed, pulls a plug, and asserts that
the published RPM is unchanged **and** the capacity basis moved 4 → 3. Also
covers R4 → R3 → R2, refitting plugs not restoring capacity until those cylinders
actually fire again, a dry motored engine at 192 RPM contributing zero, and a
reload not resurrecting an old cylinder count.

---

## Fix 2 — Controller migration when a run is extended at its negative end

### Cause

The section at the negative end of a crank run is the controller: it runs the
simulation and holds the controls. Adding a section at that end makes the new
block the controller and demotes the old one.

Everything the player configured lives on the controller — the ignition switch
position, the Redstone Control Module, and the selected `ControlMode`. None of it
moved. The demoted section kept the module flag, so `CrankshaftBlock#onRemove`
would drop the module from the wrong block; the new controller came up with
defaults; and the selected mode stayed on a `ScrollOptionBehaviour` nothing would
ever read again.

### Fix

`CrankshaftBlockEntity#migrateControllerConfigurationTo` runs on the tick this
section's index leaves 0, **before** it stops being a controller.

What moves:

- `manualIgnition` — a switch the player turned off stays off;
- `controlModuleInstalled`, as an **ownership transfer**: set on the successor,
  cleared here. Mining either section afterwards drops exactly one module;
- the selected mode, through `ScrollValueBehaviour#setValue`, which is the
  behaviour's own API — it also marks the successor changed and sends its data.
  Writing the NBT field directly would have left the behaviour holding a
  different value and overwriting it on the next save.

What deliberately does not move:

- the running engine state, crank angle and momentum. A shape change stops the
  engine by design; resuming a follower's never-ticked `EngineState` would be
  arbitrary;
- `redstoneSignal`. It is a live input the new controller samples from its own
  neighbours on its next tick. Carrying it over would let a lever nowhere near
  the new block go on commanding the engine. The old controller's copy is zeroed
  so the overlay stops printing it.

Idempotency and tick order: the migration is reached only on the tick the index
leaves 0, and the index is written immediately afterwards, so it cannot run twice
for one rebuild. It is independent of block entity tick order because it
*overwrites* the successor's fields — whether the successor has already ticked
with its own defaults or has not ticked at all, the result is the same.

### Test

Covered by `EngineLayoutTests` at the layout level (exactly one controller, at
the negative end, for every valid run length on both axes). The migration itself
touches `Level`, `BlockEntity` and Create's behaviour system, so it is verified by
the manual in-game matrix below — see **Manual test D**.

---

## Fix 3 — Runs longer than four sections

### Cause

`EngineComponents` had two entry points — a cheap block-state-only `locate()` and
a full `resolve()` — and they walked the run **separately, with different
bounds**. For any run longer than `MAX_CYLINDERS` they disagreed:

- a run of five, asked from its **last** section, walked back 4 steps, hit the
  real end, and reported `count = 5` with `oversized = false`. A count larger
  than `MAX_CYLINDERS` reached `EngineState`, and an index of 4 reached code
  indexing four-element arrays;
- the same run asked from its **first** section reported oversized;
- for a run of ten, `locate()` reported a controller position five sections in —
  an inner section naming itself the head of a sub-engine.

### Fix

One scan, in the new Minecraft-free `EngineLayout`:

- both walks are bounded at `MAX_CYLINDERS + 1`. That one extra step is the whole
  detection mechanism: a walk still on crankcase after five steps has already
  proven the run too long, so the scan stops rather than measuring how much too
  long it is. Cost is fixed at ten probes for a run of any length;
- length is measured **globally** — `stepsBack + stepsForward + 1` — so every
  section of a run agrees about it;
- `index` is clamped to `[0, MAX_CYLINDERS)` and `count` to
  `[1, MAX_CYLINDERS]`, so a caller that reads them before checking the status
  gets a harmless number rather than an exception;
- `isController()` requires index 0 **and** a usable status, so an oversized run
  nominates no controller anywhere and cannot split into partial engines. The
  index-0 condition *is* the negative-end rule, since the index comes from the
  backwards walk.

`oversized` is stored on `CrankshaftBlockEntity`, persisted and synchronised —
not merely computed and discarded. `isEngineController()` reads it, so every
section of an over-long run declines to simulate; `getFlywheel()` returns null for
one, so no generator can be asked for capacity on its behalf.

The renderer, the goggle overlay and the plain hovering tooltip all report it, in
English and German (`gui.unsupported_layout`, `gui.unsupported_layout_hint`).

### Regression test

`EngineLayoutTests`: runs of 1–4 complete, runs of 5, 6 and 10 oversized from
**every** section on **both** axes, exactly one controller for valid runs and none
for oversized ones, and an exhaustive sweep over runs of 1–24 × every section ×
every unload window asserting `index ∈ [0, count)` and `count ∈ [1, 4]`.

---

## Fix 4 — Chunk boundaries and partially loaded engines

### Cause

`isCrankshaftOn` returns false for an unloaded position, and the run walk could
not tell that apart from "no crankcase here". An R4 across a chunk boundary whose
far half unloaded therefore read as an R2 — or, from the other side, as a second
engine with a controller of its own. A follower whose controller had unloaded
found nothing on its negative side and promoted itself.

### Fix

Three levels, all fail-closed.

**1. The scan.** `level.isLoaded(pos)` is asked before the block state.
An unloaded position is `Section.UNLOADED`, which yields
`EngineAssemblyStatus.INCOMPLETE_CHUNKS` rather than an end of the run. Oversize
outranks it, because a run already proven too long cannot become short enough by
loading more of it — a stable answer rather than one that flickers with chunk
traffic.

**2. The placement update.** On `INCOMPLETE_CHUNKS`, `updateEnginePlacement`
adopts **nothing**: no new cylinder count, no new controller, no migration, no
claimed shape change, no overwritten configuration. It suspends instead.

**3. The tick.** After components resolve, `!chunksLoaded()` — a Cylinder, the
Flywheel, the Carburetor or the Oil Sump in an unloaded chunk — suspends too.

Suspension (`setAssemblySuspended`) is fail-closed and non-destructive:

- the engine stops and Create's cached capacity is forced to zero through
  `reconcileEngineOutput`, so no ghost capacity is left on a network;
- no combustion, no fuel drawn, no oil drawn, no start progress;
- the stored layout, ignition switch, module and mode are untouched;
- both edges are idempotent, so an engine at the edge of the loaded area does not
  re-propagate a network twenty times a second.

Suspension is deliberately **not** part of `isEngineController()`. A controller
that suspended itself has to keep being the controller, or nothing would run the
check that releases it. On release the engine force-republishes and reconciles
with Create, and because it was stopped on the way in there is no stale momentum
and no old combustion event to replay.

No chunk is ever force-loaded, and no block entity reference is held across ticks.

### Regression test

`EngineLayoutTests`: `unloadedChunksNeverShortenARun`,
`unloadedNeverInventsAController`, `oversizeOutranksUnloaded`, and the safety
sweep above. The block-entity-level suspension is verified by **Manual test F**.

---

## Fix 5 — Combustion events as a payload

### Cause

The spark, the chamber flash and the firing bang were triggered on the client by
the per-cylinder event counters moving, and those counters travel in the block
entity's data. Every spark and every combustion therefore forced a **full block
entity synchronisation** — crank angle, phase, speed, fuel flag, lubrication
state, four spark counters, four combustion counters — to deliver eight bits of
news.

A single cylinder fires 3.2 times a second at full throttle. An inline-4 fires
four times per revolution, on different ticks, so the updates could not even
coalesce.

### Fix

`EngineCombustionEventsPayload`: the controller's `BlockPos` and two bytes of
bitmask, one bit per cylinder. The server diffs the counters across the simulated
tick and sends only the difference, through
`PacketDistributor.sendToPlayersTrackingChunk(level, chunkPos, payload)` so it
reaches only players actually tracking the engine.

**At most one packet per engine per tick**, whatever is happening inside it.

**Why a bitmask is lossless.** The simulation asks each cylinder exactly once per
tick whether it has crossed its firing angle — `crossedFiringAngle` is a boolean
test evaluated once per cylinder per tick — so a cylinder can register at most one
spark and one combustion in any tick. One bit is therefore exactly as much
information as the server produced, not an approximation.

That also holds physically, which is the thing a future speed increase would have
to re-check: a cylinder fires once per revolution, so two firings in one tick
needs more than 720° of crank rotation per tick — 2400 RPM. The engine's own
ceiling is `MAX_RPM` = 208 RPM (≈62°/tick) and an external Create network at the
default `maxRotationSpeed` of 256 RPM turns the crank ≈77°/tick. An order of
magnitude of headroom.

**Measured packet rate**, from the firing rate the simulation produces:

| Engine | Firing events/s | Event packets/s | Old full BE syncs/s |
| --- | --- | --- | --- |
| R1 idle (64 RPM) | 1.07 | ≤ 1.07 | ≈ 2.1 (spark + combustion) |
| R1 full (192 RPM) | 3.20 | ≤ 3.20 | ≈ 6.4 |
| R4 idle (64 RPM) | 4.27 | ≤ 4.27 | ≈ 8.5 |
| R4 full (192 RPM) | 12.80 | ≤ 12.80 | ≈ 25.6 |

The packet counts are upper bounds: a tick in which two cylinders of an R4 fire
together sends **one** packet, not two, so the real R4 figure is lower than the
event count. The old column is full block entity syncs; the new one is an 11-byte
payload.

Two properties fall out rather than needing code: a chunk coming into view carries
no events, so arriving at a running engine no longer replays a burst of bangs; and
there is no client-side counter-adoption state left to get wrong.

The counters themselves stay and stay persisted — they are the engine's record,
what the server diffs, and what the goggle diagnostics read. Normal block entity
synchronisation is unchanged for real visible state changes, the slow resync, the
coast resync and the post-load reconcile.

Registration is common code, because a server must have a clientbound payload in
its registry to send it. The handler is behind `FMLEnvironment.dist.isClient()`,
so `ClientEngineEvents` — and through it `Minecraft` — is never resolved on a
dedicated server.

### Test

`ClientEngineEvents` and the packet path need a client and a server, so they are
verified by **Manual test G**. The lossless-bitmask argument is a property of
`EngineState`, covered by the per-cylinder event assertions in
`LastPowerStrokeTests` and `MultiCylinderTests`.

---

## Fix 6 — The last paid charge finishes its stroke

### Cause

```java
powerStrokeActive[cylinder] = firedThisRevolution[cylinder] && combustionPossible
    && lastAngleDeltaDegrees > 0.0F && isWithinPowerStroke(localAngle);
```

`combustionPossible` includes `fuelAvailable`. So the tick after the tank read
empty, a charge that had already been paid for and lit stopped delivering torque.
The engine paid a millibucket for work it never received, and Create was told it
generated nothing while it was still measurably accelerating the crankshaft.

A second bug sat next to it: `powerStrokeStrength` was a single engine-wide value
recomputed each tick from the current phase, so a kick lit during a start attempt
silently became a full power stroke the instant the engine caught.

### Fix

`canIgniteNewCharge` gates **ignition**. The power stroke of a charge already paid
for is gated only by things that can really end it:

- `structureValid` — the engine was taken apart under it;
- `lastAngleDeltaDegrees > 0` — the crank is not turning forwards, which covers a
  stall and an overstressed network alike (Create reports speed 0 for both).
  Without it a stalled crank would stay latched and deliver free torque for ever;
- `isWithinPowerStroke` — the crank reached the end of the stroke.

`powerStrokeStrength` is now `float[MAX_CYLINDERS]`, latched at the moment the
charge is bought. A later phase change cannot revalue a charge that is already
burning.

RUNNING is left once no charge can be lit **and** none is still pushing.
Generation follows the same rule through `stillMakingCombustionTorque()`, so:

- there is no tick where real combustion torque is delivered while Create is told
  the engine produces none;
- and no tick where a dry engine keeps claiming capacity on the strength of a
  stale `ticksSinceCombustion`, because both halves go false together the tick the
  last stroke ends.

**Measured:** with the tank emptied mid-stroke, the stroke runs on for 9 further
ticks at idle — half a revolution at 64 RPM is 9.4 ticks — and generation ends on
the tick after it.

### Regression test

`LastPowerStrokeTests`: exactly 1 mB buys exactly one complete stroke and never a
second combustion; the tank emptying mid-stroke does not truncate it and the
engine counts as generating for every tick of it; ignition off lets the current
charge finish but lights no new one; structural destruction and a jammed shaft
both end it on the very next tick; no latched free torque survives a standstill;
an R4 run dry draws exactly the charges left, never goes negative, and ends with
no phantom capacity.

The stability suite's starvation assertion was updated: it demanded generation
stop on the very next tick after the tank emptied, which is half a tick too eager
for the reason above. It now asserts that generation ends within one revolution
and that capacity is zero the instant it does — the upper bound still closes the
free-power exploit.

---

## Fix 7 — Coast-down

### Cause

A spin-down took about ten seconds from idle. The obvious knob is the wrong one:
`FLYWHEEL_INERTIA` also sets combustion ripple within a revolution, spin-up time
from a hand crank, and how much smoother an inline-4 is than an inline-1. Cutting
it would have bought a shorter coast with a rougher, twitchier running engine.

### Fix

The inertia is untouched. The loss is added only to an engine that is
**free-running without firing**:

```java
coastDragTorqueAt(rpm, lubrication)
    = frictionTorqueAt(rpm, lubrication) * (COAST_FRICTION_MULTIPLIER - 1) + PUMPING_DRAG_TORQUE
```

`COAST_FRICTION_MULTIPLIER = 2.5`, `PUMPING_DRAG_TORQUE = 6.0`. Returned as the
*extra* over ordinary friction, so that term is still counted exactly once.

Three gates, each a way this drag would be wrong:

- **only while free-running.** An engine Create is holding at a speed takes that
  speed on rather than integrating its own momentum, so subtracting drag would
  corrupt the one number that must equal the shaft's — and would bill the same
  losses twice, since motoring a dead engine is already charged to the network
  through `PASSIVE_DRAG_STRESS_PER_RPM`;
- **not while RUNNING**, so every governor equilibrium stays put;
- **not while STARTING**, nor while a paid charge is still pushing. A start
  attempt spends most of its ticks between firing kicks, and charging it coast
  drag in those gaps would smother the attempt.

Every input is synchronised or provably identical on both sides, so the client's
spin-down still traces the server's curve exactly.

### Measured

| From | Time to below `REST_RPM` | Target |
| --- | --- | --- |
| 64 RPM (idle) | **2.70 s** | 2.0 – 3.5 s |
| 32 RPM (hand crank) | **1.45 s** | 1.0 – 2.5 s |
| 192 RPM (full throttle) | **6.10 s** | longer than idle, not disproportionate |
| 64 RPM, inline-4 | **2.90 s** | — |
| 64 RPM, dry | **1.20 s** | faster than lubricated |

Equilibria unmoved: idle **66.0**, half throttle **129.9**, full throttle
**192.5** RPM. Hand crank still starts the engine (catches after 132 ticks and
settles at 66.5 RPM). Inline-4 ripple 0.000 RPM against an inline-1's 5.999.

### Regression test

`EngineCoastDownTests` — the times above, the equilibria, monotonic spin-down with
no oscillation around zero and no friction-driven reversal, landing exactly on
zero, a backwards-spun engine coasting *up* to zero and never through it, hand
crank starting, no coast drag on a driven engine, and R4 still smoother than R1.

---

## Test and CI structure

### What kind of test is what

| Suite | Kind | Needs |
| --- | --- | --- |
| `EngineStabilityTests` | pure simulation | JDK only |
| `EngineReloadTests` | pure simulation | JDK only |
| `MultiCylinderTests` | pure simulation | JDK only |
| `SparkPlugTests` | pure simulation | JDK only |
| `EngineLayoutTests` | pure simulation | JDK only |
| `EngineCapacityTests` | pure simulation | JDK only |
| `LastPowerStrokeTests` | pure simulation | JDK only |
| `EngineCoastDownTests` | pure simulation | JDK only |
| `checkModels` | asset validation | `python3` |
| generated-assets-current | asset validation | `python3`, CI only |
| licence-files-present | repository check | CI only |
| Manual tests A–I below | in-game | client and/or dedicated server |

All eight simulation suites are Gradle tasks and run under `./gradlew check`.
There are **no automated client tests and no automated dedicated-server tests** —
that would need a game harness this project does not have — so everything in the
manual matrix is exactly that: manual.

### Running them

```
./gradlew check                        # everything
./gradlew simulationTest checkModels   # no Minecraft needed
./gradlew simulationTestEngineLayoutTests
```

The `simulationTest` source set compiles the simulation and its tests with an
**empty compile classpath**, which is what mechanically enforces the
Minecraft-free boundary: importing a Minecraft type into `EngineState`,
`EngineTuning` or `EngineLayout` fails that compile. `EngineComponents` and
`CombustionAudio` are excluded by name, and that exclusion list is the exhaustive
statement of where the boundary runs.

Suites are plain `main` classes rather than JUnit, so running them needs nothing
but a JDK — no test framework to resolve.

---

## Licensing

The project is **source-available, not open source**. Four documents state it:
`LICENSE.md` (the project), `ASSET_LICENSE.md` (models, textures, sounds, logos,
generator output, future engine recordings), `CONTRIBUTING.md` (contributions are
licensed to the project; contributing does not make the project open source), and
`NOTICE.md` (third-party rights — Minecraft, Create, NeoForge, Registrate, Ponder,
Catnip, Flywheel — which the project's licence claims nothing over).

`neoforge.mods.toml` continues to declare `license = "All Rights Reserved"`, and
CI fails if it stops, if any of the four documents goes missing, or if the README
stops saying the project is not open source.

`ASSET_LICENSE.md` draws the line where it matters for a mod: screenshots and
gameplay videos showing the mod running are explicitly permitted; shipping the
assets as a downloadable bundle is not.

---

## Manual acceptance matrix

These need a running game and were not automated. Run them on a client and, where
marked, on a dedicated server.

**A — Single cylinder.** Normal start; ignition off; fuel empty; oil empty;
driven externally; save/reload.

**B — Inline-4.** All cylinders active; one plug missing; two plugs missing; tank
runs dry; oil runs dry; held at a constant external 192 RPM; save/reload.

**C — Capacity.** R4 → R3 immediately on pulling a plug; R3 → R4 only after those
cylinders genuinely fire again; no capacity from a dry motored engine; no stale
capacity after reload. *(Also covered automatically by `EngineCapacityTests`.)*

**D — Controller.** Build an R2, switch manual ignition off, fit a Control Module,
select a non-default mode. Extend at the **negative** end and check: the engine
stopped; ignition still off; module still present; mode preserved; only the new
controller drives the engine; exactly one module drops; no duplicate value box; the
follower holds no second effective module. Repeat for a positive extension,
repeated negative extensions, mining the controller, and unloading/reloading the
controller's chunk.

**E — Oversized.** Runs of 5, 6 and 10 sections, on X and Z, checked from every
section: no generated speed, no capacity, no partial engines, and the overlay says
the build is unsupported. *(Layout side covered by `EngineLayoutTests`.)*

**F — Chunks.** R4 wholly inside one chunk; across an X boundary; across a Z
boundary; controller chunk loaded with the end cylinder's chunk unloaded;
controller chunk unloaded with a follower loaded; Flywheel, Carburetor and Oil
Sump each in the other chunk; save/reload with neighbours loading late; a
dedicated server with a small simulation distance. In every case the engine must
suspend rather than become a smaller engine, and must recover when the chunks
return.

**G — Network.** Spark and combustion appear at the correct cylinder on an R4; no
replay when a chunk loads; no full block entity sync per event (observable as
traffic, or by logging); a dedicated server starts and runs with no client
class-loading error.

**H — Tuning.** Coast-down times as measured above; idle/half/full targets held;
no oscillation around 0 RPM; no increase in kinetic network churn.

**I — Existing suites.** `EngineStabilityTests`, `EngineReloadTests`,
`MultiCylinderTests`, `SparkPlugTests`, `check_models.py` — all automated, all run
by `./gradlew check`.
