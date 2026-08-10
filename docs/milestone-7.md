# Milestone 7 - spark plug placement and authoritative ignition feedback

A presentation fix, not a gameplay one. The Spark Plug is still visual only -
no item, no requirement - and nothing about the Flywheel resolver or the control
architecture from [milestone 6](milestone-6.md) is touched.

Three complaints from manual testing, and what each one turned out to be.

## 1. The spark plug was in the wrong place

The old plug was a horizontal stack of boxes on the head's +X flank whose inner
end - labelled "electrode + earth strap" - was a 1.1 x 1.1 x 1.0 block of steel
sitting at x 12.6 .. 13.7, i.e. entirely *outside* the 3.4 .. 12.6 bore and
buried in the barrel wall. It straddled the head's underside rather than passing
through the casting, so nothing about it read as a plug screwed into a head, and
the part that should have been in the chamber never reached it.

### The chamber it has to fit in

Every number in the new plug comes from geometry that already existed:

| | |
| --- | --- |
| chamber roof (head underside) | y **14.00** |
| piston crown at top dead centre | y **13.50** |
| clearance volume | **0.50** units |
| bore | x/z 3.4 .. 12.6 |

The crown figure is not measured off a render: it is the piston model's own top
(12.0) plus `CrankMath.wristPinHeight(180) - WRIST_PIN_MODEL_HEIGHT`, which is
`(8 + 3 + 14.5 - 16) - 8 = 1.5`. Half a unit is all there is, and a real engine's
clearance volume is about a tenth of its stroke - here 0.6 - so that is right
rather than merely tight.

### The plug now

Drilled through the head from its +X flank, exactly as a real head is drilled:

* **outside** - spanner hex, ceramic insulator, ribbed insulator, brass terminal,
  standing proud of the head's face at x 14.9 .. 18.6;
* **through the casting** - the threaded shell, y 14.05 .. 15.15, buried in the
  head slab where nobody can see it and where it cannot touch the bore;
* **in the chamber** - the centre electrode (its last 0.16 below the roof) and
  the ground strap, and nothing else. Lowest point 13.60, which clears the
  piston at TDC by 0.10. The spark gap between the electrode tip (13.84) and the
  strap (13.74) is where `CrankshaftBlockEntity` aims the spark particle.

### Enforced, not eyeballed

`tools/check_models.py` gained `check_chamber`: it swings the real piston model
through a full revolution using CrankMath's own slider-crank relation and
intersects it with every element of the Cylinder. Anything fixed that stands in
the piston's path fails the check. Moving the crank throw, the rod, the piston
or the head now breaks the build rather than bending a plug in a world.

It also asserts the combustion flash stays under the head and inside the bore.

## 2. The flash was too small - because of its UVs

The old flash was a 0.95-tall disc at y 13.0 .. 13.95, which is mostly *inside*
the piston at TDC, so at most 0.45 of it was ever uncovered. That was half the
problem. The other half was invisible in a diff:

`combustion_flash.png` is a single soft radial blob - white-hot core, orange rim,
transparent edge. The model generator emits **world-aligned** UVs, which is right
for every casting in this mod and exactly wrong for a sprite whose meaning is its
own middle: a face at y 13.0 .. 13.95 sampled `v` 2.05 .. 3.00, a thin and almost
fully transparent strip from the top of the blob. The bright core was never
drawn.

The flash now takes the **whole sprite on every face**, and is built as two
crossed slabs plus a core rather than one disc, so it presents a face roughly
square-on from any horizontal angle and its translucent overlap makes the middle
the brightest part.

| | |
| --- | --- |
| width | 5.2 units, **57 %** of the 9.2 bore |
| height | y 12.10 .. 13.96 - roof down to the top of the cutaway window |
| lifetime | `COMBUSTION_FLASH_TICKS` = **3** ticks (0.15 s) |
| colour | from the sprite: white-yellow core, orange rim |

It is deliberately taller than the clearance volume. The piston is opaque and
drawn first, so the depth buffer clips the flash to the real chamber for free: at
TDC the crown covers all but the top 0.46, and as the charge drives the piston
down the same flash is uncovered - 0.46, then 0.66, then 1.22, then the full
1.86 by the third tick at idle. A burn that grows as it pushes the piston is what
combustion looks like, and it costs no per-frame geometry to say so.

If a fast engine ever looks permanently lit rather than firing,
`COMBUSTION_FLASH_TICKS` is the knob; shortening it cannot desynchronise
anything, because the flash is *started* by the event counter below and this only
says how long it lingers.

## 3. The sound did not line up with the flash - because they came from different mechanisms

The firing cough was played by the server from a real event (start progress
advancing). The flash was re-derived on the *client* from the crank angle by
`EngineState.updateClientVisuals()`. Two different mechanisms describing one
event, so they could land a tick or two apart - and the client's copy was only
approximately right anyway: it cannot know whether the server's fuel draw
succeeded, so an engine whose tank had just run dry went on flashing for a
revolution it produced no torque on.

### One event, one number

`EngineState` now carries two counters, incremented on the server at exactly the
point the thing they name happens:

```java
sparkEventId++       // the coil fired: ignition on, turning forwards, fast enough
                     // - with or without any gasoline to light
combustionEventId++  // a charge was paid for and burned, in the same branch that
                     // consumes the fuel, delivers the torque and banks the start cycle
```

Both ride the existing block entity synchronisation - no new packet type, and no
per-tick traffic: the crankshaft syncs on the tick a counter moves, which is at
most once per revolution, about three times a second at full throttle.

The client reacts to the counters changing and never predicts:

| counter moved | client does |
| --- | --- |
| `sparkEventId` | one `ELECTRIC_SPARK` particle at the electrode gap, plus a tiny electrical tick - **not** while RUNNING, where the running loop masks it anyway |
| `combustionEventId` | starts the chamber flash **and** plays the firing cough, in that order, in the same call |

The first update a block entity sees only *adopts* the counters, so loading a
chunk near a running engine does not fire a spark and a flash for events that
happened before the player arrived. Only inequality is tested, so two events
arriving in one client tick produce one flash and one puff rather than a double
bang.

Therefore these five now describe the same successful event by construction:
gasoline consumption, combustion torque, start-attempt progress, chamber flash,
firing sound.

### Sound

New: `engine_spark.ogg`, 0.06 s, synthesised like every other asset here - band
-limited noise above 1.8 kHz with a 2.2 ms decay and one high resonance, and
deliberately *none* of what the other one-shots are made of. No cylinder thump,
no body resonance, nothing low. It has to be impossible to mistake for
combustion, because the case it exists for is a plug ticking away while the
engine refuses to catch.

Unchanged: the cranking loop, the running loop, the catch, the stall and the
shutdown. `tools/generate_sounds.py` draws from one seeded RNG in dict order, so
the new sound is appended at the end - inserting it anywhere else would silently
re-roll every asset after it. (Verified: the six existing files decode
sample-for-sample identical after regeneration.)

The starting rhythm is now driven end to end by one counter:

```
rrrrrrrr...   cranking loop
   tick       spark  - coil fired, particle at the gap
   PUFF       combustion - flash and cough together
rrrrrrrr...
   tick
   PUFF
   ...
   PUFF-BRUMM  the charge that catches: flash, then the catch sound
rrrrrrrr...   running loop
```

While RUNNING there is no per-revolution one-shot at all - the flash still fires
on every combustion, the loop carries the audio.
