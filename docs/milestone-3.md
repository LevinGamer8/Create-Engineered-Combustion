# Milestone 3 - gasoline, multi-cycle starting, HUD polish

Debug combustion is gone: the engine now burns an actual fluid, and it no longer
snaps to RUNNING the instant it crosses `START_RPM`.

Still no diesel, oil, cooling, spark plugs, injectors, valves, camshafts,
four-stroke timing or multiple cylinders.

## Gasoline

Registered as a NeoForge `BaseFlowingFluid` pair (`gasoline` + `flowing_gasoline`)
with its own `FluidType` and a `gasoline_bucket`.

**There is deliberately no placeable fluid block.** Gasoline exists to be piped,
stored and burned, so `createLegacyBlock` returns air - the same arrangement
Create uses for its own `VirtualFluid`. Buckets, tanks, pipes and every other
fluid-capability consumer work normally; only pouring pools does not. Adding a
`LiquidBlock` later is purely additive.

### Fuel is a tag, not a fluid

The engine never compares against a `Fluid` instance. It asks
`EngineFuel.isValidFuel`, which tests the `engineered_combustion:gasoline` fluid
tag. Another mod's gasoline can be accepted by a datapack alone, with no code
change.

No IDs for Create: Diesel Generators or TFMG are hardcoded - their 1.21.1 fluid
identifiers could not be verified here, and guessing would produce a silent
breakage rather than a clean absence. Neither is a dependency.

## Carburetor

A 1000 mB tank sitting **directly on top of the cylinder** - two blocks above the
crankshaft:

```
            [ Carburetor ]     cylinder.above()   (optional)
             [ Cylinder ]      crankshaft.above()
 [ Flywheel ] [ Crankshaft ]
```

It exposes only `Capabilities.FluidHandler.BLOCK`, so Create pipes, buckets and
anything else use one code path. The tank validator rejects non-gasoline, so
pipes cannot push junk in. Right-clicking with any fluid container routes through
`FluidUtil.interactWithFluidHandler`, so no bucket-specific code exists.

### Mechanically valid vs. ready to combust

These are now separate questions. Crankshaft + cylinder + piston + flywheel is
*mechanically valid* and any Create source can motor it. Combustion additionally
requires the carburetor **and** fuel in it. An engine with no fuel can be spun
all day and will never produce power.

## Fuel consumption

`FUEL_PER_COMBUSTION_MB = 1`, charged **per firing event**, never per tick. One
revolution, one charge - so consumption scales with speed automatically. Measured
in the offline harness: 32 mB over 600 ticks at idle (~32 revolutions), not 600.

Pre-start firing attempts are charged the same amount; a real engine burns fuel
while you crank it. Ignition off burns nothing at all.

## Multi-cycle starting

The old behaviour was: cross `START_RPM` with ignition on, become RUNNING. Now a
start attempt banks **firing cycles**:

```
CRANKING  --first successful firing-->  STARTING (roll required cycles, 2..5)
STARTING  --each further firing------>  progress 1/4, 2/4, 3/4 ...
STARTING  --progress >= required----->  RUNNING
```

The required count is rolled **once per attempt** from a server-side `Random` on
the block entity and then held - never re-rolled per tick or per revolution, and
never evaluated client-side. Firing opportunities are detected by the same
crank-angle *crossing* test as combustion, so no floating-point angle equality is
involved.

A firing opportunity only counts when the structure is mechanically valid, a
carburetor is attached with fuel, ignition is on, rotation is forwards, speed is
at or above `START_RPM`, and the crank actually crosses the firing angle.

### Partial kicks

Each pre-start firing delivers `START_KICK_TORQUE_FACTOR` (0.35) of normal
combustion torque. This was safe to add because `STARTING` owns the simulated
speed but **does not** generate power for Create, so the kicks cannot disturb the
source handoff at all - the published speed stays 0 throughout starting.

### Reset

`START_ATTEMPT_TIMEOUT_TICKS = 30`. If 30 ticks pass with no usable firing
opportunity - the crank stopped, the tank ran dry, ignition went off, the
structure broke - the attempt is discarded and progress returns to 0. A
half-finished start is never remembered indefinitely.

## Running dry

Fuel is part of `combustionPossible`, so an empty tank drops RUNNING to COASTING
on the same tick. The flywheel then coasts down on inertia and friction and
stalls - about 157 ticks from idle in the harness - rather than snapping to zero.

## HUD

Overlays are now built with catnip's `LangBuilder` (`ECLang`, mirroring Create's
`CreateLang`) instead of raw `Component`s. `forGoggles` applies the same
font-width-aware indentation every Create tooltip uses, which is exactly the
margin the icon occupies - so the icon/title overlap is gone by construction
rather than by a hardcoded offset.

Icons are real now: the Crankshaft item for the Engine, the Cylinder for the
Cylinder, the Carburetor for itself.

Normal goggles show gameplay state; **sneaking** adds a `Diagnostics` block with
crank angle, simulated/generated RPM, raw redstone signal, rotation source and
structure validity. `Power Stroke: No` is gone - it told the player nothing.

```
[icon] Engine                       [icon] Carburetor
  State: Starting                     Fuel: Gasoline
  Speed: 32 RPM                       Amount: 742 / 1000 mB
  Ignition: Enabled
  Fuel: Gasoline                    [icon] Cylinder
  Fuel Available: 742 mB              Piston Assembly: Installed
  Start Progress: 2 / 4               Piston Position: 0.27
```

## Constants

All in `EngineTuning`: `FUEL_PER_COMBUSTION_MB = 1`,
`CARBURETOR_CAPACITY_MB = 1000`, `MIN_START_CYCLES = 2`, `MAX_START_CYCLES = 5`,
`START_KICK_TORQUE_FACTOR = 0.35`, `START_ATTEMPT_TIMEOUT_TICKS = 30`.
