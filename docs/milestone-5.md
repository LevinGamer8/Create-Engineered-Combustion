# Milestone 5 - both crankshaft outputs, visible combustion, air filter, throttle

Four focused changes. No new visual language, no new engine layout, and nothing
started that belongs to a later milestone: still no four-stroke timing, valves,
camshafts, coolant, temperature, wear, diesel, extra cylinders, transmission or
starter motor.

## 1. The crankshaft drives shafts on both ends

### The bug

The crankshaft model has always shown a main journal leaving the crankcase at
*both* ends. Only one of them did anything: a Create Shaft against the journal on
the far side from the Flywheel received no rotation at all.

### Root cause

`CrankshaftBlock` was a plain `Block` and `CrankshaftBlockEntity` a plain
`BlockEntity`. Neither `IRotate` nor `KineticBlockEntity` was anywhere near them,
which was a deliberate milestone-1 decision to keep the simulation free of Create
internals.

Create's `RotationPropagator` only ever walks between positions where **both**
of these hold:

* `level.getBlockState(pos).getBlock() instanceof IRotate`
  (`findConnectedNeighbour`), and
* `level.getBlockEntity(pos) instanceof KineticBlockEntity` (same method).

The crankshaft satisfied neither, so it was not merely unconnected - it was
invisible to the kinetic graph. The whole engine reached Create through exactly
one block, `EngineFlywheelBlockEntity`, and therefore through exactly one free
face: the flywheel's.

### The fix

`CrankshaftBlock extends HorizontalAxisKineticBlock implements IBE<…>` and
`CrankshaftBlockEntity extends KineticBlockEntity`.

That is Create's own connectivity rather than a reimplementation of it:

| Create API | What it now returns for the crankshaft |
| --- | --- |
| `IRotate#hasShaftTowards` | `face.getAxis() == state.getValue(HORIZONTAL_AXIS)` - inherited; true on **both** ends of the crank axis, false everywhere else |
| `IRotate#getRotationAxis` | `HORIZONTAL_AXIS` - inherited, so it agrees with the above by construction |
| `KineticBlockEntity#getGeneratedSpeed` | **0**, inherited and not overridden |
| `BlockStressValues` | nothing registered - no capacity, no impact |
| `IRotate#hideStressImpact` | `true`, so a relay does not print an empty stress line |

`HORIZONTAL_AXIS` is the same `BlockStateProperties.HORIZONTAL_AXIS` instance the
old hand-rolled declaration used, so `blockstates/crankshaft.json` is untouched.
`getStateForPlacement` is still overridden to take the axis of the direction the
player faces - `HorizontalAxisKineticBlock` would otherwise use the *clockwise*
direction, which is right for a cogwheel and wrong for a machine you line up by
eye.

### Still exactly one source

The crankshaft is a **relay**, not a generator:

* it never generates, so `isSource()` is false and it can never win a
  propagation contest;
* it adds no stress capacity, so the engine's power budget is unchanged;
* crankshaft and flywheel are adjacent along a shared axis, which
  `RotationPropagator.getRotationSpeedModifier` resolves as a 1:1 axis
  connection (modifier `1`, same sign).

So the two blocks are one network, at one speed, fed by one source. A shaft on
either end sees the same RPM and draws on the same finite capacity. Adding a
second `GeneratingKineticBlockEntity` would have doubled the stress capacity for
one engine, which is precisely what this must not do.

External cranking through the new side works for free, because propagation is
symmetric: shaft → crankshaft → flywheel, all at 1:1.

The engine now reads its own `getSpeed()` for the crank angle instead of the
flywheel's. Identical while the two are coupled, and still correct when the
engine is driven from the crankshaft's own face.

### Model

The journals now stop flush with the block face and step down to a 4×4 stub as
they leave it - exactly a Create Shaft's cross-section (6..10) - so a shaft on
either end continues the journal instead of swallowing a wider boss. Both ends
are now genuinely kinetic, so both are modelled as outputs.

## 2. Visible ignition and combustion

### Spark plug

Static geometry on the `+X` flank of the cylinder head - the one face the intake
(`-Z`) and exhaust (`+Z`) bosses leave free on both engine axes. Six boxes:
electrode and earth strap breaking through into the chamber, threaded shell,
spanner hex, two ceramic insulator sections, brass terminal.

**Visual only.** There is no spark plug item, no block entity and no gameplay
requirement; ignition hardware becoming a real modular component is a later
milestone.

### Spark timing

Server-authoritative and event-driven. `EngineState` raises a one-tick
`sparkEvent` when

```
firing angle crossed
+ structure valid
+ ignition on
+ turning forwards
+ at or above the phase's required speed
```

and `CrankshaftBlockEntity` consumes it and emits one `ELECTRIC_SPARK` particle
at the electrode. **Fuel is deliberately not part of that condition** - the coil
is wired to the crank, not to the fuel system. That is the mechanically honest
model and the useful one: a plug that visibly sparks while the engine refuses to
catch says "fuel", and a plug that stays dark says "ignition". Ignition off
means no spark at all.

One particle per firing: 1.07/s at idle, 3.2/s at full throttle.

### Combustion flash

A thin translucent disc filling the top of the bore, drawn at full brightness
and fading over `COMBUSTION_FLASH_TICKS = 3`. It appears only when a charge was
actually paid for and burned, so cranking with ignition on and no gasoline gives
a spark and **no** flash.

It is re-derived on the client from the authoritative crank angle plus the
already-synchronised phase, ignition, structure and fuel flags - the same rule
the server ran - rather than sent as a packet per revolution. Cost is one small
model per frame regardless of engine speed, and the flash cannot outlive its own
event. The single divergence by construction: the client cannot know whether the
server's fuel *draw* succeeded, so the two can disagree for one revolution at the
moment a tank runs dry.

No fire, no explosion particles, no permanent flame, and nothing escaping the
cylinder.

## 3. Visible fuel, and the fuel line

Gasoline is shown where a gasoline engine actually shows it - in the carburetor,
never sloshing inside the cylinder.

* The **float bowl** is now a floor plus three walls, open on `+Z`. That opening
  is the sight window, in the same cutaway idiom as the crankcase and the
  cylinder.
* `CarburetorRenderer` fills it with the **real tank contents** through catnip's
  fluid renderer, so the amber in the bowl is literally gasoline's own texture
  and tint.
* The height is quantised to `FUEL_LEVEL_RENDER_STEPS = 16`. 1000 mB is full,
  500 mB is half, 0 mB is empty; any non-empty tank shows at least one step.
* **No new sync channel.** The renderer reads the tank the client already has.
  Quantising is what allows the tank's own synchronisation to be throttled: a
  block update goes out the moment the visible step changes, and otherwise no
  more than once per `TANK_SYNC_INTERVAL_TICKS = 10`. At full throttle that
  costs the goggle readout under 2 mB of accuracy.
* **Fuel line**: inlet banjo, union nut and supply line above the bowl (clear of
  the sight window), plus a union and pipe running from the carburetor body down
  onto the cylinder head's intake flange. Purely visual - the Carburetor remains
  the one authoritative gasoline tank and there is no second fluid network.

## 4. Air Filter

An **item** installed onto a placed Carburetor, not another full block. The
engine is already five blocks tall, and an air cleaner is a part you bolt to a
carburetor rather than a machine beside it - the same call the Piston Assembly
makes about the Cylinder.

* right-click a Carburetor holding an Air Filter → fitted, consumed unless
  creative;
* sneak + right-click empty-handed → taken back off and handed over;
* breaking the Carburetor drops it;
* stored in `CarburetorBlockEntity`, so it persists and synchronises with
  everything else on that block entity.

Create's value UI would normally swallow a right-click that lands on the
throttle box; `ScrollValueBehaviour#bypassesInput` is overridden so a fluid
container or an Air Filter in hand stands it aside.

The model is an old oil-bath style cleaner clamped to the air horn: mounting
neck, dark pressed-steel canister with rolled seams, a visible gauze element
band, an overhanging lid and a wing nut. Not a modern cone filter.

**A missing filter does not stop the engine.** It is a state, not a fault:

```
Air Intake: Filtered      /      Air Intake: Unfiltered
```

on the Carburetor's goggle overlay. Nothing consumes that state yet - it exists
so a later wear model can.

## 5. Throttle and variable RPM

### The control

Create's own `ScrollValueBehaviour` on the Carburetor, which brings its own value
box, its own hover tip, its own `ValueSettingsPacket`, its own persistence and
its own server-authoritative path. No custom GUI and no invented API. The value
box is a `ValueBoxTransform.Sided` on the horizontal faces - the Carburetor has
no facing, so there is no front to prefer.

Range **0-100 %**, whole percent, default 0. Displayed as `Throttle  50%`.

### Range and targets

| | |
| --- | --- |
| `IDLE_RPM` (0 % throttle) | 64 |
| `FULL_THROTTLE_RPM` (100 %) | 192 |
| `MAX_RPM` (absolute clamp) | 208 - headroom for inertial overshoot |
| runtime cap | `min(MAX_RPM, AllConfigs.server().kinetics.maxRotationSpeed)` |

That last row matters: `maxRotationSpeed` is a server config whose *minimum* is
64, and exceeding it makes `RotationPropagator` **destroy the block**. It is
read live and clamps both the simulated speed and the published speed, rather
than trusting the 256 default.

0 % still idles on purpose: it is a closed main throttle with an idle circuit,
not a shut-off. The ignition switch is still what stops the engine.

### How throttle becomes speed

Not by setting one. The throttle chooses **torque**:

```
target      = IDLE + (FULL_THROTTLE - IDLE) * throttle          (capped at the runtime limit)
governor(r) = clamp01(1 - (r - (target - RANGE/2)) / RANGE)      RANGE = 32
peak        = friction(target) / (DUTY * 0.5)
torque(r)   = peak * governor(r)
```

`governor(target) == 0.5` by construction, so `peak` is solved to make `target`
the equilibrium where average combustion torque cancels friction. At 0 % this
reproduces the old constant exactly, so idle behaviour is unchanged.

Everything else falls out of integrating that torque against friction and
flywheel inertia, as before:

* 0 → 100 %: ≈ 3.4 RPM/tick during the power stroke, ≈ 29 RPM/s averaged, so
  **≈ 4.4 s** from idle to 192 - and it may overshoot slightly on the way.
* 100 → 0 %: combustion torque goes to zero above the new band and the engine
  coasts on friction alone, ≈ 19 RPM/s, so **≈ 6.6 s** back towards idle.

There is no clamping to the target and no teleporting.

### Load response

```
drag = friction(rpm, lubrication) + LOAD_DRAG_TORQUE * min(1, stress / capacity)
```

`LOAD_DRAG_TORQUE = 2.5`. Both stress and capacity scale with speed in Create, so
the *ratio* is speed-independent and cannot run away. At 100 % load an idling
engine sags to ≈ 46 RPM while a wide-open one barely moves off 190 - open the
throttle to pull the load. Zero when Create's stress system is disabled.

Create's own overstress rule is untouched and still applies on top: past
capacity Create reports speed 0, the crank stops and the engine stalls.

### Stress capacity

Unchanged: `32 SU/RPM` registered on the Flywheel only. Create multiplies it by
the generator's actual speed, so capacity rises with throttle *only by actually
turning faster* - 2048 SU at idle, 6144 SU at full throttle. Nothing scales
capacity by the throttle setting itself, so there is no way to conjure a bigger
power budget out of a lever.

### Fuel

No new formula. One combustion event still costs `FUEL_PER_COMBUSTION_MB = 1`,
charged per firing and never per tick, so a faster engine fires more often:

| | firings/s | 1000 mB lasts |
| --- | --- | --- |
| 64 RPM | 1.07 | ≈ 15.6 min |
| 192 RPM | 3.20 | ≈ 5.2 min |

Throttle does not change fuel *per event*.

### Physical throttle lever

A `throttle_lever` partial model pivoting on the carburetor's throttle shaft,
rotated from the authoritative throttle value: 40° closed at 0 %, −20° open at
100 %, linear between. The old static throttle arm and rod are gone; only the
shaft remains in the baked model.

### Sound

The loop lifecycle is untouched - loop identity depends on the engine's *phase*
only, so no throttle movement can start a second loop. Only the pitch mapping was
retuned: `SOUND_PITCH_EXPONENT` 0.35 → **0.20** and `SOUND_MAX_PITCH` 1.30 →
**1.28**. The old exponent saturated against the clamp at about 110 RPM, so with
a throttle fitted the top two thirds of the range would all have sounded
identical. At 0.20 the idle-to-full sweep spans 1.00 → 1.25.

## HUD

Engine, with goggles:

```
Engine
  State: Running
  Speed: 128 RPM
  Ignition: On
  Throttle: 50%
  Fuel: Gasoline
  Fuel Level: 640 / 1000 mB
  Lubrication: Normal
  Oil: 820 / 1000 mB
```

Engine, without goggles - still no numbers, plus a qualitative lever position
(`Low` ≤ 15 %, `High` ≥ 70 %, otherwise `Medium`):

```
Engine
  Running Smoothly
  Ignition: On
  Throttle: Medium
```

Carburetor, with goggles: Fuel, Amount, **Air Intake**, **Throttle**.

Sneak diagnostics gained `Target RPM`, read from the Carburetor rather than from
the simulation's server-only copy of the throttle. Network load is deliberately
*not* shown - Create already reports stress on the Flywheel.

## Files worth knowing about

| Concern | Where |
| --- | --- |
| Two-sided kinetic connectivity | `content.engine.crankshaft.CrankshaftBlock` |
| Crankshaft as a kinetic relay | `content.engine.crankshaft.CrankshaftBlockEntity` |
| The one kinetic source | `content.engine.flywheel.EngineFlywheelBlockEntity` |
| Throttle torque, governor, load drag | `content.engine.EngineTuning`, `EngineState#integrate` |
| Per-tick simulation inputs | `content.engine.EngineInputs` |
| Spark event, combustion flash | `content.engine.EngineState` |
| Throttle value, air filter, tank sync | `content.engine.carburetor.CarburetorBlockEntity` |
| Fuel level, lever, filter rendering | `client.CarburetorRenderer` |
| Combustion flash rendering | `client.CylinderRenderer` |
| All geometry | `tools/generate_engine_models.py` |
| All textures | `tools/generate_engine_textures.py` |

## Not verified in this environment

`./gradlew build` could not run: this session's egress policy returns 403 for
`maven.neoforged.net`, `maven.createmod.net`, `maven.parchmentmc.org` and
Mojang's `piston-data` / `libraries.minecraft.net`, so NeoForge, Create,
Parchment and Minecraft itself cannot be resolved. Only Maven Central and GitHub
are reachable. The client was therefore never launched and none of the manual
tests were run.

Every Create API used here was checked against the real sources at tag
`mc1.21.1-6.0.10` (and catnip at Ponder `mc1.21.1/dev`) rather than assumed, and
the Java sources parse cleanly under `javac`; but only symbol *resolution*
against the real classpath, and actual play, can confirm the milestone.
