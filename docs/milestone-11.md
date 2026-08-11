# Milestone 11 - Modular Inline Multi-Cylinder Engines

The single-cylinder engine becomes a modular inline engine: **Inline-1 through
Inline-4**, built by extending the crankshaft along its own axis.

Not implemented, deliberately: V, boxer, radial and opposed-piston layouts,
multiple crankshafts, DOHC/SOHC, four-stroke valve timing, multiple carburetors,
turbocharging.

---

## 1. The rule everything else follows

> A multi-cylinder engine is **one engine**, not several sharing a shaft.

```
 Cylinder 1    Cylinder 2    Cylinder 3    Cylinder 4
     |             |             |             |
 Piston 1      Piston 2      Piston 3      Piston 4
     |             |             |             |
 Throw 1       Throw 2       Throw 3       Throw 4
     \_____________|_____________|_____________/
                         |
                  Shared Crankshaft
                         |
                      Flywheel
                         |
                   Create Network
```

One simulation, one master crank angle, one momentum, one throttle, one ignition,
one Carburetor, one Oil Sump, one Flywheel, one kinetic source, one Stress
budget. Cylinders differ only in the **phase** at which they take their turn.

This is enforced structurally, not by convention. `CrankshaftBlockEntity#tick`
returns immediately for any section that is not the engine's controller, so a
follower has no simulation to run and nothing to publish. There is no code path
by which a second cylinder can become a second engine.

---

## 2. Assembly

### Layout

```
                  [Carburetor]                  above any one cylinder
 [Cylinder] [Cylinder] [Cylinder] [Cylinder]    one per crankshaft section
     |          |          |          |
 ...[Crank]  [Crank]   [Crank]   [Crank] [Flywheel] [Create shaft]...
     |
 [Oil Sump]                                     below any one section
```

Adjacent Crankshaft sections sharing an axis are one engine. The Carburetor and
the Oil Sump may sit anywhere along it - above any cylinder, below any section -
and the lowest-indexed one wins, so the answer is deterministic even if a player
fits several.

### The controller

The section at the **negative end** of the run. Arbitrary, but deterministic,
which is the property that matters: every section resolves the same controller
from block states alone, in the same handful of lookups, on either side, at any
time. No block entity reference is ever serialised.

A follower locates its controller by arithmetic - `pos.relative(negative, index)`
- and reads its state back for the crank angle, the overlay and the controls.
Clicking any crankcase of an inline-4 works the same ignition switch, and all
four tell-tales light together.

### Discovery

`EngineComponents` is still the one resolver; it now walks the run.

| | cost | used by |
|---|---|---|
| `locate` | ~10 block-state lookups, no block entities | followers, renderers, every frame |
| `resolve` | ~20 lookups including block entities | the controller, once per server tick |

Both walks are bounded by `EngineTuning.MAX_CYLINDERS` - the single place the
limit of 4 lives - so this is a fixed deterministic cost, never a world scan. A
run longer than four sections reports `oversized` and is not an engine; the
diagnostics say why rather than silently splitting it.

### Cylinder index

0-based along the crank axis from the controller, and nothing depends on block
entity tick order. It fixes the crank phase now and will fix the firing order
later.

---

## 3. Crank phases

```
phaseOffset(i) = i * 360 / cylinderCount
```

| layout | phases |
|---|---|
| Inline-1 | 0 |
| Inline-2 | 0, 180 |
| Inline-3 | 0, 120, 240 |
| Inline-4 | 0, 90, 180, 270 |

**Prototype two-stroke-like phases**: this engine still has one power event per
360 degrees. A real four-stroke spreads firing over 720 and needs an explicit
crank configuration and firing order; that is not this milestone.

### One master angle

```java
localAngle(i) = normalize(masterCrankAngle + phaseOffset(i))
```

There is exactly one crank angle in the engine. A cylinder does not own an angle
that could drift - it owns an **offset**. Every question about a cylinder (has it
crossed its firing angle, is it on its power stroke, where is its piston, where
is its crank pin) is asked of that one function, so four pistons are
mechanically synchronised by construction rather than by four counters happening
to agree.

---

## 4. Combustion, per cylinder

`EngineState#tickSimulation` makes one pass over the cylinders. Each is offered
its own firing opportunity at its own phase and pays for its own charge:

- its own spark event, at its own plug;
- its own combustion event, its own chamber flash, its own bang;
- its own `FUEL_PER_COMBUSTION_MB`;
- its own torque impulse.

There is no global combustion event multiplied by a cylinder count. What is
shared is the crankshaft those impulses feed, integrated once at the bottom of
the pass.

### Torque and why it is divided

```java
perCylinderPeak = peakCombustionTorqueFor(target) / cylinderCount
```

The throttle is a governor setpoint: 0 % means *hold 64 RPM*, and it has to mean
that for an inline-4 as much as for a single or the whole readout stops making
sense. A real governor achieves it by metering less charge per cylinder the more
cylinders it feeds. Measured: every layout at every throttle settles on its
target.

What more cylinders actually buy:

| | inline-1 | inline-4 |
|---|---|---|
| free-running speed at 0 % | 64 RPM | 64 RPM |
| charges per revolution | 1 | 4 |
| gasoline at the same speed | 1x | **4x** |
| Stress Capacity | 2048 su | **8192 su** |
| speed ripple | 5.7 RPM p-p | **0.0 RPM p-p** |
| cranking to catch | ~76 ticks | ~57 ticks |

Because load factor is *stress over capacity*, an inline-4 with four times the
capacity feels a quarter of the load factor from the same real load - so it sags
far less. "More cylinders pull more" falls out of the existing model rather than
being asserted by it.

### Compression

New, and per cylinder:

```java
torque = -COMPRESSION_PEAK_TORQUE * sin(theta) * (1 - cos(theta)) / 2
```

Resisting from BDC up to TDC, assisting on the way back down, zero at both dead
centres, and it **integrates to exactly zero over a revolution** - a spring, not a
second friction. So it changes no equilibrium speed and costs no fuel; it changes
the *shape* of the rotation.

That is where multi-cylinder smoothness comes from, and nothing in the code says
"more cylinders are smoother": on an inline-1 the sum is one lump per revolution,
on an inline-4 four lumps 90 degrees apart that cancel exactly. It applies to any
assembled engine whether or not it is firing, so motoring a dead engine now feels
like turning an engine over.

### A dead Spark Plug

Plugs are per cylinder. An inline-4 missing one runs on three cylinders:
`RUNNING`, generating, 3 of 4 firing, settled at 60 RPM against a target of 64,
supplying three quarters of the capacity. Diagnosis, not breakage.

### Starting

Progress counts engine-wide firing events, and an inline-4 makes four per
revolution, so the required count is scaled sub-linearly
(`START_CYCLES_PER_EXTRA_CYLINDER = 0.5`). Result: an inline-4 catches in about
75 % of the time an inline-1 takes. Easier and smoother, as real multi-cylinder
engines are; never instant, and never without being cranked.

---

## 5. Create: still exactly one generator

There is one `GeneratingKineticBlockEntity` per engine - the Flywheel - beyond
either end of the **whole run**. Every section reports the same one, so four
sections cannot disagree about who generates. Crankshaft sections remain relays
with no capacity and no impact; four in a row are four relays, not four
generators.

### Capacity

```java
capacity = registeredCapacityPerRpm * firingCylinderCount
```

Scaled by cylinders that are **genuinely burning fuel**, never by cylinder count.
`getFiringCylinderCount()` asks each cylinder whether a charge burned within its
last few revolutions - the condition an external source cannot fake.

**Free-power regression (TEST M7):** an inline-4 with no gasoline, motored at
160 RPM by another Create source. All four pistons move, all four throws turn,
0 firing, 0 RPM generated, **0 su**. Fuel it and the very same engine supplies
its full capacity.

---

## 6. Visuals

- **Per-cylinder crank throws.** Each section draws the same crank assembly at
  its own phase, so an inline-4 shows four throws 90 degrees apart on one shaft.
- **Per-cylinder pistons and rods.** Each cylinder animates from its own local
  angle, so at any moment the four pistons are at four different heights, each
  rod still attached to its own pin.
- **Continuous crankcase.** A new `joined` block state, true when another section
  sits against a crankcase's negative face, runs the machined top deck across the
  seam instead of stopping 0.5 short of it - which would have left a 1-unit groove
  at every joint. Only the negative side reaches across, so two decks never
  occupy the same place. The end walls stay: two of them between adjacent throws
  is a 4-unit main bearing web, which is what an inline engine has there, and
  their touching faces point away from each other so nothing z-fights. Like
  `lit`, `joined` is outside `areStatesKineticallyEquivalent`, so growing an
  engine never re-propagates its kinetic network.
- **Per-cylinder spark and flash.** Which cylinder fired decides where every
  effect happens: the spark at that plug's electrode, the flash in that bore, the
  bang from that chamber. An inline-4 firing in sequence is four effects walking
  down the engine.

### Not implemented: the intake manifold

The brief allows a visual-only shared manifold "if practical". It is deferred and
documented rather than guessed at: new geometry spanning up to four blocks cannot
be judged without rendering it, and this environment cannot build or run the game
(see section 9). The Carburetor feeds an abstract shared intake; nothing in the
Cylinder Head's geometry has been claimed, so both a manifold and a future
exhaust manifold still have room.

---

## 7. Audio

The event-based architecture needed no rewrite - it needed feeding per cylinder.
Each combustion plays its pulse at its own chamber, so the firing cadence a player
hears is the engine's real one: an inline-1 at idle is 1.07 pulses a second, an
inline-4 at full throttle 12.8. One mechanical layer per engine, keyed by the
controller's position, so four cylinders are not four loops. Above
`SOUND_COMBUSTION_PULSE_MAX_RATE_HZ` the existing stride thins the one-shots out,
which an inline-4 at full throttle now reaches - the seam that was built for this
and has finally been crossed.

---

## 8. Save, reload, and taking an engine apart

The assembly is **rebuilt from world blocks**, never from a serialised list of
block entity references. What is persisted per section is its layout (index and
count) and, on the controller, the simulation: master crank angle, signed
simulated RPM, phase, per-cylinder combustion ages, and the per-cylinder spark
and combustion counters.

The layout is persisted for one reason: the first tick after a load compares the
layout it derives against the layout the engine actually had. Without it every
reload would look like the player had just rebuilt the engine - and rebuilding
stops it.

Milestone 10's post-load reconciliation is unchanged and now covers the whole
assembly: it waits for the chunks of *every* section before judging the engine,
which matters more for an engine four blocks long than one.

### Chunk boundaries

An inline-4 spans five blocks along its axis and three vertically, so it will
cross chunk boundaries. `EngineComponents#resolve` reports `chunksLoaded`, and
the reconciliation waits (bounded at 100 ticks) rather than declaring the engine
broken because a neighbour loaded a tick later. Nothing is force-loaded.

### Modification

Removing a section or a cylinder changes the shape, and a change of shape
**stops the engine** - both halves of a cut inline-4, cleanly, with the generated
speed and the cached Stress Capacity forced to zero. Restarting is required. That
is the documented, predictable behaviour the brief permits, and it is what
guarantees no ghost capacity: an engine is never resumed from a state that
describes a machine that has been taken apart.

---

## 9. Testing

`tools/MultiCylinderTests.java` is new - 31 checks, no Minecraft required:

| part | asserts |
|---|---|
| 1 | phase offsets for R1-R4, and that every angle derives from one master angle |
| 2 | an inline-4 runs on one shared speed with all four cylinders firing |
| 3 | every layout at 0 % and 100 % settles on its throttle target |
| 4 | a single is far lumpier than any multi; the inline-4 is smoothest |
| 5 | R1-R4 burn 1-4 charges per revolution; R4 burns 4x R1 |
| 6 | capacity is 1x, 2x, 3x, 4x a single |
| 7 | an inline-4 with a dead plug: 3 of 4 firing, below target, 3/4 capacity |
| 8 | **a motored dry inline-4: 0 firing, 0 RPM, 0 su** |
| 9 | every layout catches when cranked; R4 sooner than R1 but not trivially |

`EngineStabilityTests`, `SparkPlugTests` and `EngineReloadTests` are unchanged in
coverage and still pass - 75 checks in total across the four suites.

### What could not be verified here

`./gradlew build` cannot run in this environment: `maven.neoforged.net`,
`maven.createmod.net`, `maven.ithundxr.dev`, Mojang's and Parchment's hosts are
all refused by the session's egress policy, so Minecraft, NeoForge, Parchment and
Create cannot be resolved at all. The whole Minecraft-free simulation layer
compiles with `-Xlint:all` and no warnings; every source file parses; every
mod-internal symbol resolves; `tools/check_models.py` passes on the regenerated
models. **The Minecraft-facing code has not been compiled or run**, and the manual
matrix (M1-M12) is untested.

---

## 10. Limitations before four-stroke work

- One power event per 360 degrees. Real four-stroke timing needs a 720-degree
  cycle, explicit crank configuration and firing orders - and will replace
  `cylinderPhaseOffsetDegrees` when it arrives.
- With that simplified cycle an inline-4's combined torque is *exactly* constant
  (two cylinders are always mid-power-stroke and the four compression terms
  cancel), so its speed ripple is zero. Correct arithmetic for this model,
  smoother than a real engine, and something the four-stroke cycle will undo.
- Maximum four cylinders. One constant.
- One Carburetor and one Oil Sump for the whole engine; no per-cylinder intake,
  no manifold geometry, no airflow simulation.
- Structure changes stop the engine rather than converting an R4 into an R3 while
  it spins.
- Control module and value box live on the controller section, wherever the
  player clicked.
