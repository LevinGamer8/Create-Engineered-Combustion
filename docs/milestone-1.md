# Milestone 1 - single-cylinder mechanical prototype

The point of this milestone is to prove one thing:

> player-built components -> structure detection -> synchronized crank/piston
> motion -> Create rotational output

There is no combustion, no fuel, no fluids, no valves, no timing, no thermal or
lubrication model, and only one cylinder. A redstone signal stands in for the
power source.

## Supported orientation

Exactly one engine shape is supported. It is hard-coded, not a multiblock
framework.

```
                  [ Cylinder ]          crankshaft.above()
                       |                connecting rod (implicit)
   [ Flywheel ] [ Crankshaft ]          flywheel adjacent along the crank axis
        |
   [ Create Shaft ] ...
```

* The **Crankshaft** is horizontal. Its `axis` block state value is `x` or `z`
  and defines the engine's axis. When placed, it takes the axis of the
  horizontal direction the player is facing.
* The **Cylinder** must be at `crankshaft.above()`. The cylinder is always
  vertical - the piston travels straight up and down. It has no orientation
  property.
* The **Flywheel** must be directly next to the crankshaft *along the
  crankshaft's own axis*, with a matching `axis` value. When placed next to a
  crankshaft it adopts that crankshaft's axis automatically.
* The Create shaft (or cogwheel, or anything kinetic) attaches to the far end of
  the flywheel, as it would to any Create kinetic block.

Both ends of the crankshaft are accepted for the flywheel. The crankshaft's axis
is an *axis*, not a direction, so requiring one specific end would make the
block's placement rotation silently significant with no visual cue to explain
it. If both ends carry a flywheel, the one on the positive axis direction wins
and the other stays inert.

Everything else - diagonal placement, vertical crankshafts, a cylinder to the
side, more than one cylinder - is deliberately unsupported.

## Where each responsibility lives

| Concern | Class | Depends on Create? |
| --- | --- | --- |
| Crank angle, running flag, output RPM | `content.engine.EngineState` | no |
| Crank angle -> piston position | `content.engine.CrankMath` | no |
| Structure layout + detection | `content.engine.EngineStructure` | no (Minecraft only) |
| Engine controller, redstone, persistence, sync, debug readout | `content.engine.crankshaft.CrankshaftBlockEntity` | only the goggle interface |
| Piston installed state | `content.engine.cylinder.CylinderBlockEntity` | only the goggle interface |
| **Create kinetic adapter** | `content.engine.flywheel.EngineFlywheelBlockEntity` | yes - this is the boundary |
| Stress registration | `registry.ECStressValues` | yes |

`EngineState` deliberately imports nothing from Minecraft, NeoForge or Create.

### Why the kinetic generator is on the Flywheel, not the Crankshaft

Create decides everything about a kinetic source from the *block*:
`IRotate#getRotationAxis` and `IRotate#hasShaftTowards` decide where rotation may
leave a block, and `RotationPropagator` only walks between `KineticBlockEntity`
instances. Making the crankshaft a `GeneratingKineticBlockEntity` would have
forced it to expose Create shafts on its own faces, which contradicts the
intended `crankshaft - flywheel - shaft` layout and would have welded the engine
simulation to Create internals.

Keeping the generator on the flywheel gives the split the milestones need:

* the crankshaft is a plain `BlockEntity` with zero Create kinetic coupling, so
  milestone 2 can replace the whole simulation without touching Create code;
* `EngineFlywheelBlockEntity` is the single file that has to follow if Create's
  kinetic API changes.

The interface between the two halves is one method:
`CrankshaftBlockEntity#getOutputRpmFor(BlockPos)`.

## Crank angle

`EngineState` holds a single authoritative `crankAngleDegrees` in `[0, 360)`.
Per tick it advances by `rpm * 360 / 60 / 20` degrees - the same rpm-to-degrees
conversion Create uses in `KineticBlockEntity#convertToAngular`.

Everything mechanical is derived from it:

* piston position: `CrankMath.pistonPosition(angle) = 0.5 - 0.5 * cos(angle)`,
  normalized so `0` is bottom dead centre and `1` is top dead centre (a
  sinusoidal approximation, i.e. an infinitely long connecting rod);
* the spinning flywheel disc;
* the debug readout.

There is no separate cosmetic animation timer anywhere in the codebase.

## Debug power (milestone 1 only)

The engine runs when **structure is valid AND the crankshaft has a redstone
signal**. While running it produces a fixed `EngineState.DEBUG_TARGET_RPM`
(32 RPM) and the flywheel registers 32 SU/RPM of capacity, i.e. 1024 SU at
32 RPM.

Removing the redstone signal, or breaking any required component, stops output.
Replacing this with real combustion means changing `EngineState` and the one
line in `CrankshaftBlockEntity#refresh` that reads `hasNeighborSignal`.

## Structure validation and performance

`EngineStructure.detect` touches at most three block positions and never scans
the world. It is called:

* on the next tick after `neighborChanged` fires on the crankshaft (which covers
  every block placement or removal around it, and redstone changes);
* on the next tick after the cylinder's piston is installed or removed (which is
  not a block change, so the cylinder notifies the crankshaft explicitly);
* every 20 ticks as a safety net.

Deferring to the next tick coalesces the burst of neighbour updates a single
block placement produces.

## Client/server split

The server is authoritative for structure validity, the redstone signal and the
running flag. The client only integrates the crank angle with the same formula
from the same synced starting point, so rendering is smooth without per-tick
packets.

Sync happens when running or structure state actually changes, plus one resync
every 200 ticks while running to bound drift. A running engine therefore costs
roughly one packet every ten seconds.

## Persistence

* **Cylinder** - `PistonInstalled` is saved and restored. This is the state that
  must survive save, chunk unload and restart.
* **Crankshaft** - the crank angle is persisted deliberately. It is one float,
  and keeping it means a chunk reload does not visibly snap the piston to a new
  position; later milestones (combustion timing, firing order) want that
  continuity too.
* **Crankshaft** - `Running` and `StructureValid` are also written, but only so
  the client has something to draw before the first server tick. The server
  re-derives both from the actual world on its next tick.

## Debug readout

Three ways, none of which log per tick:

* Create goggles pointed at the crankshaft or the cylinder;
* right-clicking the crankshaft with an empty hand prints the same report to
  chat;
* the values are structure valid/invalid, piston installed/missing,
  running/stopped, crank angle, piston position and output RPM.

## Manual test procedure

1. Launch a client with NeoForge 1.21.1, Create 6.0.10 and this mod.
2. Grab a Crankshaft, Cylinder, Flywheel and Piston Assembly from the
   *Create: Engineered Combustion* creative tab.
3. Place the **Crankshaft** while facing along the direction the engine should
   run.
4. Place the **Cylinder** directly on top of it.
5. Right-click the cylinder holding the **Piston Assembly**. The piston appears
   inside the cage.
6. Place the **Flywheel** directly next to the crankshaft, on either end along
   the crankshaft's axis. It should adopt the crankshaft's axis automatically.
7. Attach a Create **Shaft** to the far side of the flywheel, and a cogwheel or
   small machine after it.
8. Look at the crankshaft with goggles (or right-click it): structure should read
   *Valid*, state *Stopped*.
9. Apply a redstone signal to the crankshaft - a lever on it is enough.
10. State should read *Running*, output *32 RPM*, and the crank angle should
    advance every tick.
11. The piston should move up and down inside the cylinder, and the flywheel disc
    should spin.
12. The attached Create shaft should rotate, and a Stressometer should show
    1024 SU of capacity.
13. Remove the redstone signal - everything stops.
14. Re-apply it - everything restarts.
15. Break the flywheel, or sneak-right-click the cylinder to pull the piston back
    out - the structure reads *Invalid* and the kinetic network loses power.
16. Rebuild it - the engine becomes valid again.
17. Save and quit, reload the world - the piston is still installed in the
    cylinder.
