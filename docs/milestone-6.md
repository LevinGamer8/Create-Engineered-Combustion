# Milestone 6 - either-side Flywheel, manual ignition, optional redstone

Two corrections to the engine's *control architecture*, made before survival
progression is built on top of it. No new gameplay systems: still no spark plug
item requirement, no recipes, no wear, no temperature, no coolant, no four-stroke
timing, no valves, no second cylinder, no diesel, no load sensing.

## 1. The Flywheel may sit at either end of the crankshaft

### What was wrong

The rule for finding the Flywheel already looked at both neighbours along the
crank axis - `EngineComponents.findFlywheelPos` iterated `AxisDirection.values()`
and accepted a match on either - but it **stopped at the first hit**, and it
returned nothing but a nullable `BlockPos`. Two consequences:

* the positive end silently won. With a Flywheel bolted to *each* end the engine
  picked one, generated through it, and left the other looking connected and
  doing nothing - an arbitrary choice the player had no way to see;
* every caller could only distinguish "flywheel" from "no flywheel", so an
  engine that was wrong in an interesting way could only ever report `Invalid`.

### The rule now

One resolver, `EngineComponents.findFlywheel`, examines **both** candidates and
says which one matched:

```java
BlockPos candidate = crankshaftPos.relative(Direction.get(side, axis));   // side: POSITIVE | NEGATIVE
```

`axis` is the crankshaft's own `HORIZONTAL_AXIS`. No world direction appears
anywhere in the lookup, so an engine built along X and the same engine built
along Z resolve by the same rule, and mirroring a build end for end changes
nothing but which `BlockPos` comes back.

The answer is a `FlywheelAttachment(placement, pos)` with

| `FlywheelPlacement` | meaning | `pos` |
| --- | --- | --- |
| `NONE` | no Flywheel on either end | `null` |
| `POSITIVE` | one Flywheel, on `Direction.get(POSITIVE, axis)` | that block |
| `NEGATIVE` | one Flywheel, on `Direction.get(NEGATIVE, axis)` | that block |
| `AMBIGUOUS` | a Flywheel on **both** ends | `null` |

`pos` is deliberately null for `AMBIGUOUS`: a caller that does not ask about the
placement can never be handed one of two competing flywheels.

Everything that needs the Flywheel goes through that one resolver - structure
validity (`EngineComponents.isMechanicallyValid`), the kinetic coupling
(`CrankshaftBlockEntity.getFlywheel`), the generated-speed lookup Create calls
(`getGeneratedRpmFor`), the goggle overlay and the sneak diagnostics. There is no
second flywheel lookup anywhere in the mod, and no `relative(SOME_FIXED_DIRECTION)`
left in it.

### Two Flywheels

Explicitly unsupported for this milestone, and explicitly *reported* rather than
silently resolved:

* `getFlywheel()` is null, so neither flywheel is coupled to the engine;
* `getGeneratedRpmFor` answers 0 to both, so neither becomes a Create source and
  no stress capacity is duplicated - a `GeneratingKineticBlockEntity` generating
  0 detaches instead of registering capacity;
* the structure reads invalid, and the overlay adds
  *"Two Flywheels - only one is supported"* so the player is told why rather than
  left to guess;
* the sneak diagnostics print `Flywheel: Both ends`.

Losing a coupling has to be *announced*, not just forgotten. Create asks a source
for its generated speed only when something tells it to, so a flywheel this
crankshaft stops driving would otherwise sit there holding the last speed it was
given and keep the network turning on a number that no longer means anything -
free power from an engine that has stopped combusting. `onSurroundingsChanged`
therefore re-resolves immediately and, if the coupling moved or was lost, calls
`onEngineOutputChanged()` on the flywheel it used to drive; that reads 0 and
detaches it.

A later milestone may make a twin-flywheel engine mean something. Until it does,
it means nothing at all, loudly.

### Rotation direction: nothing to flip

Mirroring the Flywheel does **not** reverse the engine, and no sign flip was
added anywhere. From Create's own propagation:

* `RotationPropagator.getRotationSpeedModifier` treats crankshaft and flywheel as
  an *axis connection* (`connectedByAxis`), because both blocks report
  `hasShaftTowards` along the shared axis;
* the modifier for that case is `getAxisModifier(from, dir) * 1/getAxisModifier(to, dir.getOpposite())`,
  and `getAxisModifier` returns **1** for anything that is not a
  `DirectionalShaftHalvesBlockEntity` (gearbox, split shaft). Neither of ours is.

So the modifier is 1 in both directions: same speed, same sign, whichever end the
flywheel is on. `EngineFlywheelBlockEntity.getGeneratedSpeed()` returns the
engine's published RPM as a positive number in both cases, and everything visible
- crank angle, piston, connecting rod, flywheel disc, the Shaft on the free end -
is derived from `KineticBlockEntity#getSpeed()` on the crankshaft, so all of it
follows that one number. Moving the flywheel changes the engine's power,
capacity, RPM, fuel use, inertia and throttle response by exactly nothing.

## 2. Redstone is no longer the ignition

### What was wrong

`CrankshaftBlockEntity.tick` read `level.getBestNeighborSignal(worldPosition)`
every tick and fed `signal > 0` straight into the simulation as the ignition. Any
redstone that happened to reach the crankcase - a repeater run past it, a lamp
line, a lever placed on the block by accident - started or stopped the engine,
and there was no way to run one without wiring.

### Manual ignition, by default

The crankcase now carries a small mechanical ignition switch, modelled on both
flanks beside the tell-tale lamp. Right-clicking the Crankshaft bare-handed works
it. Feedback is the switch itself moving on the model, the tell-tale lighting, a
lever click, and the new position on the action bar - no chat output.

The switch position (`ManualIgnition`) is a *stored player setting*, not the
engine's state. It persists across saves, chunk unloads and restarts, exactly
like the throttle lever's position and the fuel in the float bowl: a physical
switch does not flip itself because a chunk was unloaded. That cannot produce a
spurious start event, because the phase is restored alongside it and the start
sounds are emitted from phase *transitions* computed inside a tick - an engine
that was running resumes running, and one that was stopped stays stopped until it
is cranked.

Throttle 0 % remains an idle, not a stop: the bottom of the throttle range is a
closed main throttle with the idle circuit still feeding the engine. Only the
ignition stops the engine.

### The optional Redstone Control Module

`redstone_control_module` (de: *Redstone-Steuermodul*) is an **item**, installed
into a placed Crankshaft, following the same interaction philosophy as the Piston
Assembly and the Air Filter:

| | |
| --- | --- |
| install | right-click the Crankshaft holding the module |
| remove | sneak + right-click the Crankshaft empty-handed |
| block broken | dropped, never voided (`CrankshaftBlock.onRemove`) |
| persistence | `ControlModule` in the block entity's NBT, plus Create's own storage of the selected mode |
| configure | hold a Wrench, right-click and hold on the value box on either crankcase flank, drag to a mode |

The value box is a Create `ScrollOptionBehaviour<ControlMode>`: Create's icon box,
Create's value-settings screen, Create's packet, Create's persistence. Two of its
options matter:

* `onlyActiveWhen(this::hasControlModule)` - with no module there is no box at
  all: not drawn, not hit-tested, not editable;
* `requiresWrench()` - the ignition switch is worked bare-handed, so the box must
  not swallow that click. Gating it on a Wrench keeps the two interactions apart,
  and keeps a configuration widget off the crankcase during normal play.

### Control modes

```
MANUAL                 redstone ignored; switch + carburetor are the only inputs
IGNITION               signal 0 -> off, 1-15 -> on;   throttle stays manual
THROTTLE               ignition stays manual;         throttle follows the signal
IGNITION_AND_THROTTLE  signal 0 -> off, 1-15 -> on and the throttle follows it
```

Redstone-to-throttle mapping, as implemented in
`ControlMode.commandedThrottlePercent`:

| mode | signal 0 | signal 15 | in between |
| --- | --- | --- | --- |
| `THROTTLE` | 0 % (idle) | 100 % | `round(signal * 100 / 15)` |
| `IGNITION_AND_THROTTLE` | ignition off, no throttle commanded | 100 % | `round((signal - 1) * 100 / 14)`, so 1 -> 0 % |

The two differ because 0 means something different in each. In `THROTTLE` mode it
is the bottom of the range; in `IGNITION_AND_THROTTLE` it is reserved for
switching the engine off, so the running range starts at 1 and the whole throttle
span is stretched over 1-15 rather than losing its bottom step.

New constants must be **appended** to the enum: Create stores the selection as an
ordinal. Only `controlsIgnition()` and `controlsThrottle()` decide what a mode
means, and they are read in exactly one place, so a future mode (maintain RPM,
auto-start on demand, shutdown on a warning) is a constant plus its answers -
none of which are implemented now.

### One resolution, in one place

```java
EngineControlState resolveControlState() {
    ControlMode mode = getControlMode();                       // MANUAL unless a module is installed
    int signal = mode.usesRedstone() ? redstoneSignal : 0;
    boolean ignition = mode.controlsIgnition() ? signal > 0 : manualIgnition;
    int throttle = mode.controlsThrottle() ? mode.commandedThrottlePercent(signal)
                                           : manualThrottlePercent();
    return new EngineControlState(mode, ignition, throttle, signal);
}
```

The simulation, the tell-tale, the switch model, the HUD and the diagnostics all
read that one record. Nothing else in the mod calls `getBestNeighborSignal`, and
nothing else decides what a mode means.

**How a default engine ignores redstone.** `readRedstoneSignal()` returns 0
without sampling the neighbours at all unless a mode that uses them is selected,
and no such mode can be selected without a module. Redstone dust, a lever or a
repeater against a default engine therefore changes the ignition, the throttle,
and even the number the overlay prints by nothing.

**Manual versus effective throttle.** The manual setting lives where the player
put it - the Carburetor's own scroll value - and automation never writes to it.
A throttle mode produces its own *commanded* value in the resolution above and
leaves the stored one alone, so removing the module (or selecting `MANUAL`)
restores the last hand-dialled setting: manual 35 %, redstone 100 %, back to
manual, 35 % again. There is no second copy of the effective throttle anywhere -
the client re-runs the same resolution from the same synchronised values, which
is also why none of it needs a packet of its own.

### HUD

Without goggles, only what a person standing next to the engine could tell:

```
Engine
Running
Ignition: On
Control: Redstone        <- only while automation is actually driving it
Throttle: Medium
```

With goggles, the readings:

```
Engine
State: Running
Speed: 128 RPM
Ignition: On
Throttle: 100%
Manual Throttle: 35%     <- only while a redstone throttle is overriding it
Control: Redstone - Throttle
Signal: 8 / 15           <- only in a mode that actually reads redstone
Fuel / Lubrication ...
Redstone Control Module  <- only when one is installed
Mode: Throttle
```

Sneaking adds `Flywheel: Positive end | Negative end | Both ends | Missing`,
`Control Module: Installed | Missing` and `Ignition Switch: On | Off` - the switch
position, which is not the same thing as the live ignition while a redstone
ignition mode is driving the engine. The raw signal never appears without goggles,
and never at all in a manual configuration.

## Assets

Regenerated from the tools, as always - nothing under `assets/` is hand-edited:

* `crankcase_elements(ignition_on)` now emits the ignition switch on both flanks,
  in one of two positions. `block/crankshaft.json` gets the dropped lever and the
  dark lens, `block/crankshaft_lit.json` the raised lever and the lit lens; the
  existing `LIT` blockstate picks between them from the engine's *effective*
  ignition, so no new block state was needed;
* `block/control_module.png` and `item/redstone_control_module.json` - a moulded
  phenolic housing with a redstone inlay and a brass edge connector.

`python3 tools/check_models.py` passes with 0 findings.
