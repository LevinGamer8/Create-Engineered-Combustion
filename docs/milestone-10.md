# Milestone 10 - Save/Reload RPM Reconciliation

A focused correctness pass on one bug found in manual testing: a running engine
that survives a world save comes back turning at a speed that no longer has
anything to do with what it is physically doing, and never corrects itself.

No new gameplay. No new blocks, items, recipes or sounds. The engine's power,
fuel, oil, throttle and starting figures are untouched.

---

## 1. The symptom

Save a running, unloaded engine. Quit. Rejoin. The goggle diagnostics read:

```
State: Running            State: Running
Speed: 68 RPM             Speed: 184 RPM
Generation: Active        Generation: Active
Throttle: 0%              Throttle: 100%
Simulated RPM: 57.4       Simulated RPM: 192.9
Target RPM: 64            Target RPM: 192
Generated RPM: 68         Generated RPM: 184
```

Both engines are running correctly *as engines* - 57.4 RPM is exactly the
equilibrium of a low-on-oil idle, and 192.9 is exactly the equilibrium of a
healthy wide-open throttle. What is wrong is the number Create is running on. It
is not converging on the engine's speed; it is simply parked, and it stays parked
for as long as the engine runs.

---

## 2. Root cause

Three faults stacked on top of each other. Only the third makes the state
permanent, and it is the one that mattered.

### 2.1 The published RPM was persisted as if it were authoritative

`CrankshaftBlockEntity#write` put `PublishedRpm` on disk beside `SimulatedRpm`,
and `read` restored both. But the published RPM is not a second physical fact
about the engine - it is a *representation* of the first one, latched, filtered
and quantised on its way to Create.

Worse, it was a third copy, not a second. Create persists its own view as well:
`KineticBlockEntity#write` saves `Speed`, `Source` and a `Network` tag that
carries the cached Stress Capacity of every source. So a save wrote the engine's
speed three times - as physics, as our latched publication, and as Create's
network state - and a load restored all three independently. Whichever loaded
first won by accident.

### 2.2 The reload dropped the combustion clock, so the engine disowned its network

`ticksSinceCombustion` was not persisted, so it came back as `-1`. That makes
`combustionIsCurrent()` false, which makes `evaluateActiveGeneration()` false, so
on its very first tick back a perfectly healthy running engine published **zero**.
Create's `GeneratingKineticBlockEntity#applyNewSpeed` handles a source going to
zero by tearing the network down (`detachKinetics(); setSpeed(0); setNetwork(null)`),
and the engine then rebuilt it a tick or two later at whatever speed it happened
to be at.

So the reload was not a reconciliation. It was a transient, and where it left the
published value was luck.

### 2.3 The publishing rule could not correct a small error - ever

This is the actual bug.

```java
if (publishedRpm != 0.0F) {
    if (Math.abs(target - publishedRpm) < EngineTuning.NETWORK_RPM_DEADBAND)   // 8 RPM
        return false;
    ...
}
```

The deadband existed for a real reason: a single cylinder firing once per
revolution genuinely swings the crankshaft about +/-2 RPM, and pushing that
ripple onto the kinetic network would re-propagate every machine downstream once
per revolution. But a deadband silences ripple by *refusing corrections*, and it
cannot tell a 3 RPM ripple from a 3 RPM error. Anything below 8 RPM was
unreachable, for ever.

Combine the three: the reload produces a transient (2.2), the transient parks the
published value somewhere within a deadband of the truth, the saved copy makes
that parked value survive the next save too (2.1), and nothing can ever move it
again (2.3).

### Proved, not guessed

`tools/EngineReloadTests.java` runs the real `EngineState` against a model of
Create's kinetic layer taken from the 6.0.10 source. Against the code as it was:

```
idle,      simulated 64.9   published 60    <- permanently 4.9 RPM low
half,      simulated 129.1  published 124   <- permanently 5.1 RPM low
full,      simulated 191.6  published 188   <- permanently 3.6 RPM low
```

and those errors did not shrink after 6000 further ticks.

---

## 3. The fix

### 3.1 One authoritative RPM, and everything else rebuilt from it

```
   engine simulation  --->  filtered output  --->  quantised publication  --->  Create
   (authoritative)          (derived)              (derived)                    (derived)
```

Persisted, because it is physics:

| key | why |
|---|---|
| `SimulatedRpm` | **the** signed angular velocity |
| `CrankAngle` | so the piston does not jump |
| `Phase` | a running engine resumes running |
| `TicksSinceCombustion` | **new** - the condition an external source cannot fake |
| `Generating` | the saved verdict, trusted only until the first tick re-derives it |
| throttle, ignition switch, control module, mode, fuel, oil, spark plug | player state and components, unchanged |

No longer persisted:

| key | why |
|---|---|
| `PublishedRpm` | a cached derivative of `SimulatedRpm`. Now reconstructed on load, and still sent to the client so the goggles can show what Create is really being told |

`EngineState#restoreAfterLoad` does the reconstruction: it seeds the output filter
from the engine's own momentum, derives a provisional published speed from that
same momentum, and demands that the next tick publish for real.

### 3.2 An explicit post-load reconciliation step

Nothing touches the kinetic network from inside `read`. Loading NBT happens while
the chunk is still being assembled - neighbouring block entities may not exist
yet - so `read` only restores physics and raises a flag:

```
read NBT
  -> restore simulation and control state
  -> reconstruct the derived output
  -> needsPostLoadReconcile = true

first server tick with the engine's blocks loaded
  -> resolve components
  -> run one normal simulation tick (structure, plug, fuel, oil, speed)
  -> force-publish the result, bypassing the rate limits
  -> refresh Create's generated speed AND its cached Stress Capacity
  -> notifyUpdate() so the client sees the reconciled state
  -> clear the flag
```

"With the engine's blocks loaded" is a real check, not an assumption: an engine
three blocks long can straddle a chunk boundary, and its Cylinder or Flywheel may
be a tick or two behind its Crankshaft. `engineNeighbourhoodLoaded()` waits for
those chunks rather than declaring the engine broken - and the wait is bounded at
`POST_LOAD_RECONCILE_WAIT_TICKS` (100) so it can never hang. It asks only whether
the chunks are *loaded*, never whether the blocks are present, so a genuinely
incomplete engine still reconciles immediately, as incomplete.

`EngineFlywheelBlockEntity#reconcileEngineOutput` is the Create side. It calls
`updateGeneratedRotation()` and then refreshes the stress figures
**unconditionally**, which `updateGeneratedRotation` itself only does while the
block is turning. That closes a save-shaped route back into the free-power
exploit: Create's `KineticNetwork#addSilently` re-registers a source with the
capacity it had on disk, so an engine that lost its fuel, plug or cylinder while
unloaded would otherwise have kept handing that capacity out.

### 3.3 A filter for ripple, a rate limit for error

The two jobs the old deadband was doing badly are now done separately.

**Ripple** is removed by low-pass filtering the engine's output - and only there.
The instantaneous `simulatedRpm` is untouched: the piston, the crank angle, the
combustion timing and the sound all still see the real, oscillating speed.

```java
if (!activelyGenerating || Math.abs(raw - outputRpm) >= OUTPUT_FILTER_SNAP_RPM)
    outputRpm = raw;                                  // events are adopted at once
else
    outputRpm += (raw - outputRpm) * OUTPUT_FILTER_ALPHA;
```

A time constant of 32 ticks attenuates the 18.75-tick idle ripple by about 90 %,
leaving roughly +/-0.2 RPM. A step larger than 12 RPM - catching, stalling, a
throttle swung open, a source handoff - is adopted immediately, so the filter
never blurs an event.

**Error** is then a plain rate limit on the filtered value:

| difference from what Create holds | published after |
|---|---|
| generation starting or stopping | immediately |
| post-load reconciliation, source handoff | immediately (forced) |
| >= `NETWORK_RPM_MAJOR_DELTA` (6 RPM) | `NETWORK_MIN_UPDATE_INTERVAL_TICKS` (4) |
| >= `NETWORK_RPM_FINE_DELTA` (1.5 RPM) | `NETWORK_RECONCILE_INTERVAL_TICKS` (20) |
| below that | never - already within one quantum |

**Every error above 1.5 RPM is eventually published.** That is the property the
deadband lacked.

`NETWORK_RPM_QUANTUM` drops from 4 to 2, which the filter makes affordable and
which lets the engine's idle, half and full-throttle equilibria land exactly on
64, 128 and 192 instead of up to 4 RPM off. The fine delta is deliberately larger
than half a quantum: the 0.5 RPM either side of every step boundary is the
hysteresis that stops an engine sitting exactly on one from flipping between two
steps once a second, and it only has to beat the +/-0.2 RPM the filter leaves.

---

## 4. What did *not* change

- **The simulation.** No RPM is ever forced to the governor's target. A loaded
  engine still sags below it, and the reload reproduces the sagged equilibrium
  rather than the target - `tools/EngineReloadTests.java` asserts exactly that.
- **External drive.** The momentum/source-handoff work from milestone 9 is
  intact. A reloaded engine on a network something else is driving synchronises
  its momentum to the real network speed and publishes nothing of its own.
- **The free-power gates.** `isActivelyGenerating()` is still the single
  authority, and both gates in `EngineFlywheelBlockEntity` are unchanged. The
  reconciliation only makes sure they are *asked* after a load.
- **The goggle diagnostics.** Same lines, same order.

### Coasting engines

Deliberate decision: a coasting engine keeps its momentum across a save and
resumes coasting from it. It is one float, the coast is already deterministic on
both sides, and stopping the flywheel dead at a save would be the same
discontinuity milestone 9 removed. It cannot come back as a generator - a
coasting engine publishes zero, so Create's restored speed is zero too, and
generation is re-derived from fuel, ignition and structure on the first tick
back.

---

## 5. Testing

`tools/EngineReloadTests.java` is new and needs no Minecraft:

```
javac -d /tmp/ec-sim $(ls src/main/java/dev/engineeredcombustion/content/engine/*.java \
                        | grep -v EngineComponents | grep -v CombustionAudio)
javac -cp /tmp/ec-sim -d /tmp/ec-sim tools/EngineReloadTests.java
java  -cp /tmp/ec-sim EngineReloadTests
```

| part | asserts |
|---|---|
| 1 | published speed tracks the engine at 0/25/50/75/100 % throttle |
| 2 | save and rejoin at each throttle reconciles on the first tick and settles on target |
| 3 | a loaded engine reloads to its **sagged** equilibrium, not to its target |
| 4 | a stopped engine, and one saved with the ignition off, stay stopped |
| 5 | an externally driven engine keeps the external source as mechanical authority and generates nothing |
| 6 | two engines and one tank of fuel: the empty one has zero capacity from the first tick back |
| 7 | eight consecutive reloads do not walk the engine away from its target |
| 8 | a settled engine re-propagates its network **zero** times in 2400 ticks, but a throttle swung wide open is followed within 200 |

The model of Create is deliberately thin - a network runs at its fastest source's
speed, that speed is *held* until a source publishes, and both it and the source
pointers are persisted. That is exactly the shape of the bug, and mocking more of
Create would be testing the mock.

`tools/EngineStabilityTests.java` and `tools/SparkPlugTests.java` are unchanged
and still pass.

### Still manual

Everything needing Create or Minecraft itself: real chunk-boundary timing, the
real `KineticNetwork`, the goggle overlay, and audio.

---

## 6. Not in this milestone

Multiple cylinders, four-stroke timing, wear, cooling. No tuning constant that
affects engine power, fuel use, oil use or starting was touched; every changed
number is in the "publishing to Create's kinetic network" section of
`EngineTuning`.
