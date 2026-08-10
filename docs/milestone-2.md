# Milestone 2 - real mechanical rotation and hand-crank starting

> **Superseded in part by [milestone 6](milestone-6.md).** The redstone ignition
> enable described below is gone: the engine is started and stopped by a
> mechanical switch on the crankcase, and a default engine ignores redstone
> entirely. Redstone control is now opt-in, through a Redstone Control Module
> item. Everything else here - the angular velocity, the inertia, the friction,
> the firing rule and the hand-crank start - is unchanged.

Milestone 1's engine was a switch: redstone on meant a fixed 32 RPM appeared out
of nothing. That is gone. The engine now has an angular velocity of its own and
**cannot start itself** - something has to physically turn it first.

Still no fuel, fluids, oil, spark plug, injector, valves, temperature, four-stroke
cycle or multiple cylinders.

## What redstone means now

| | Redstone OFF | Redstone ON |
| --- | --- | --- |
| External Create source can turn the engine | yes | yes |
| Crank angle advances, piston moves, flywheel spins | yes | yes |
| Combustion can occur | **no** | yes, if cranked above `START_RPM` forwards |
| Engine can start from standstill | no | **no** |

Redstone is now a *debug ignition enable*, nothing more. Replacing it with real
fuel and ignition later means changing one condition in
`CrankshaftBlockEntity#tick` and the `ignitionEnabled` input of
`EngineState#tickSimulation`.

## Two speeds

| | Source | Used for |
| --- | --- | --- |
| **Mechanical RPM** | `EngineFlywheelBlockEntity#getSpeed()` - Create's real kinetic speed | the crank angle, and therefore *everything visible* |
| **Simulated RPM** | integrated from combustion torque, friction and inertia | combustion decisions, and quantised into the generated speed |
| **Generated RPM** | latched, quantised simulated RPM | what Create is told this engine produces |

The crank angle only ever advances from mechanical RPM, on both client and
server. Create already synchronises the flywheel's kinetic speed, so both sides
integrate the same number and the piston, the flywheel disc, the crankshaft and
every attached Create shaft agree without this mod sending a packet per tick.

While the engine is not making its own power, simulated RPM simply tracks
mechanical RPM, so combustion always starts from the speed the engine really has.

## Motoring: how an external source turns a stopped engine

`EngineFlywheelBlockEntity#getGeneratedSpeed()` returns **0** whenever the engine
is not producing power. In Create 6.0.10 that is exactly what makes a
`GeneratingKineticBlockEntity` behave as an ordinary passive kinetic block:
`applyNewSpeed(prevSpeed, 0)` returns early while the block has a source, leaving
it attached and driven. No custom starter interaction, no fake kinetic system -
a Hand Crank, a windmill or a Creative Motor all motor the engine identically.

The flywheel already exposes Create shafts on both ends of its axis (inherited
from `HorizontalAxisKineticBlock`), so the far end is a normal attachment point.

## Starting sequence

```
hand crank turns the shaft            ->  CRANKING, mechanical = 32 RPM
crank passes the firing angle
  with ignition on, forwards, >= 24   ->  RUNNING, first power stroke
simulated RPM climbs past 32          ->  generated RPM published above 32
Create hands the source to the engine ->  rotation source: ENGINE
player releases the crank             ->  engine keeps running, settles at ~64
```

Measured in the offline harness (`EngineState` has no Minecraft dependencies, so
it can be run directly): takeover happens **23 ticks** after cranking begins, and
the engine settles at **63.6 RPM** against a 64 RPM target.

### Direction

The engine only fires while turning **forwards**, which means positive Create
speed along the flywheel's axis (`+X` or `+Z`). Cranked the other way it turns,
the piston moves, the crank angle counts down - and nothing ignites.

Which way a Hand Crank turns depends on its own facing and on whether the player
is sneaking (`HandCrankBlockEntity#getGeneratedSpeed` negates for `backwards`,
then `convertToDirection` negates again for a negative-axis facing). Rather than
memorise that: **look at the goggles readout. If Mechanical RPM is negative,
sneak-right-click the crank instead** (or place it on the other side).

## Combustion

One power event per 360 degrees, at `FIRING_ANGLE_DEGREES = 180` - which is top
dead centre in `CrankMath`'s convention, so the stroke pushes the piston from TDC
down to BDC over the following 180 degrees, like a two-stroke expansion stroke.

Firing is detected as a **crossing**, never as an angle equality, because ticks
skip over exact values (at idle the crank moves ~19 degrees per tick):

```java
travelledPastFiringAngle = normalize(crankAngle - delta - FIRING_ANGLE);
crossed = travelledPastFiringAngle + delta >= 360;
```

Because it is a crossing test it fires at most once per revolution by
construction - no separate "already fired" guard is needed, and no revolution can
be skipped.

## Rotational model

```
netTorque      = combustionTorque(rpm) - sign(rpm) * frictionTorque(rpm)
angularAccel   = netTorque / FLYWHEEL_INERTIA
simulatedRpm  += angularAccel
```

* `frictionTorque(rpm) = 4.0 + 0.08 * |rpm|`, always opposing motion, exactly
  zero at rest so it can never push a stationary engine into motion.
* `combustionTorque` is applied only during the power stroke, and is scaled by a
  governor that fades from full at 48 RPM to nothing at 80 RPM.
* `PEAK_COMBUSTION_TORQUE` is **derived, not hand-tuned**: it is solved so that
  the governed torque averaged over one revolution exactly cancels friction at
  `IDLE_RPM`. Changing `IDLE_RPM` or the friction constants therefore moves the
  equilibrium correctly instead of desynchronising two magic numbers.

`FLYWHEEL_INERTIA = 20` is the compromise that gives the flywheel real meaning:
it carries the crank between power strokes, keeps the within-revolution ripple to
about +/-2 RPM, spins up in ~4.5 s off a hand crank and coasts for ~8 s.

## Not thrashing the kinetic network

Pushing a floating-point RPM into Create every tick would re-propagate the whole
network every tick. Worse, `RotationPropagator` **destroys the block** when
`flickerScore > 128` (+5 per zero-crossing, -1 per tick), so a dithering
generated speed is not merely slow, it is fatal.

Four guards:

1. **Latched value.** `getGeneratedSpeed()` returns a stored number that only
   changes when the simulation decides to publish. Create calls it during
   propagation and during its 60-tick validation and never sees a drifting value.
2. **Quantisation** to 4 RPM steps.
3. **Deadband** of 8 RPM around the currently published value - comfortably wider
   than the +/-2 RPM within-revolution ripple, so a steady engine publishes
   nothing at all.
4. **Minimum interval** of 4 ticks between non-zero updates.

Transitions to and from zero bypass the interval so the engine engages and
disengages promptly; the 24 vs 10 RPM start/stall gap is what guarantees those
cannot repeat fast enough to trip the flicker limit.

Measured over a full cycle:

| Phase | Generated-speed updates |
| --- | --- |
| standstill -> idle (~100 ticks) | 5 (32, 40, 48, 56, 64) |
| stable idle (600 ticks) | **0** |
| idle -> stalled (~161 ticks) | 7 (56, 48, 40, 32, 24, 16, 0) |

## Stress and overstress

Capacity stays 32 SU per RPM, so it scales with actual speed exactly like
Create's own generators: 2048 SU at idle, 640 SU while coasting at 20 RPM. The
engine is a finite source and Create's normal stress rules apply unchanged.

Overstress needs no special code. `KineticBlockEntity#getSpeed()` already returns
0 when the network is overstressed, and mechanical RPM is read from exactly that
method - so the crank angle stops advancing, no firing crossings occur, friction
bleeds the simulated speed away and the engine stalls. Remove the excess load in
time and it recovers on its own. No reflection, no private state.

## State machine

`STOPPED -> CRANKING -> RUNNING -> COASTING -> STOPPED`, with `COASTING ->
RUNNING` if ignition returns while still above `START_RPM`. Only `RUNNING` and
`COASTING` generate rotation for Create. Losing the structure drops `RUNNING`
straight to `COASTING`, so combustion stops on the same tick.

## Constants

All in `EngineTuning`:

| | |
| --- | --- |
| `START_RPM` | 24 (below Create's 32 RPM Hand Crank, on purpose) |
| `STALL_RPM` | 10 |
| `IDLE_RPM` | 64 |
| `MAX_RPM` | 192 (under Create's 256 RPM `maxRotationSpeed`) |
| `FLYWHEEL_INERTIA` | 20 |
| `FRICTION_BASE_TORQUE` | 4.0 |
| `FRICTION_TORQUE_PER_RPM` | 0.08 |
| `FIRING_ANGLE_DEGREES` | 180 (TDC) |
| `POWER_STROKE_DEGREES` | 180 |
| `NETWORK_RPM_QUANTUM` | 4 |
| `NETWORK_RPM_DEADBAND` | 8 |
| `STRESS_CAPACITY_PER_RPM` | 32 |

## Goggle overlay

Create draws the overlay icon at a fixed offset over the top-left of the tooltip
box. Every Create machine leaves room for it because `LangBuilder#forGoggles`
indents **every** line - the title included - by four spaces (five for detail
lines). This mod builds its tooltip from plain `Component`s, so it reproduces
that margin explicitly; without it the icon lands on top of the title.

On top of that, `getIcon(boolean)` returns `ItemStack.EMPTY` so no Engineer's
Goggles icon is drawn at all. Create renders it unconditionally through
`GuiGameElement.of(item)`, and catnip's `GuiItemRenderBuilder#renderItemIntoGUI`
has no empty check of its own - it relies on vanilla `ItemRenderer#render`,
which draws nothing for an empty stack. The indentation alone already prevents
the overlap, so the override can be removed if it ever misbehaves.

`Redstone Signal: 0-15` is a developer line, read from
`Level#getBestNeighborSignal` - the same call `ChainGearshiftBlockEntity` uses.
Ignition is simply `signal > 0`; structure validity stays a separate line so the
two can be diagnosed independently. Combustion still requires both.

## Manual test procedure

Build the engine as in [milestone 1](milestone-1.md), then:

1. **Redstone alone.** Valid engine, stationary, apply redstone. Expect
   `Redstone Signal: 15`, `Ignition: Enabled`, `State: Stopped`, 0 RPM and no
   piston movement. Ignition enabled must **not** mean the engine starts.
   Removing the lever must return `Redstone Signal: 0` / `Ignition: Disabled`
   immediately.
2. **Hand crank, ignition off.** Attach a Create Hand Crank to a shaft on the
   flywheel's free end and hold right-click. Expect the flywheel, crankshaft and
   piston to move, `Mechanical RPM: 32.0`, `State: Cranking`,
   `Rotation Source: External`, `Generated RPM: 0.0`. It must not start.
3. **Hand crank startup.** Redstone on, crank again. Expect `State: Running`
   within a second or so, `Rotation Source` flipping to `Engine`, generated RPM
   climbing 32 -> 40 -> 48 -> 56 -> 64. Release the crank: the engine keeps
   running at ~64 RPM.
3b. **Below the start threshold.** Drive the engine at ~16 RPM with redstone on.
   Expect `Ignition: Enabled`, `State: Cranking`, and no ignition, because 16 is
   below `START_RPM` of 24.
4. **Wrong direction.** Sneak-right-click the crank (or use one facing the other
   way) so Mechanical RPM reads negative. The engine turns and the piston moves,
   but it never ignites.
5. **Stop.** Remove redstone. `State: Coasting`, RPM falls in steps over ~8
   seconds, then `Stopped` at 0.
6. **Structural failure.** While running, sneak-right-click the cylinder to pull
   the piston assembly out. `Structure: Invalid` and `Power Stroke: No`
   immediately; the engine coasts down and stops.
7. **Restart.** Reinstall the piston, redstone on, crank again - starts normally.
8. **Create load.** Attach a small machine. Power flows; a Stressometer shows
   2048 SU at idle. Overload it deliberately and the engine stalls.
