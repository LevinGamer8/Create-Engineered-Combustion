# Milestone 13 — Engine wear and maintenance

> **Revision 13.1 — realistic wear rebalance.**
> **A properly lubricated, filtered and normally operated engine experiences
> near-negligible major-component wear.** The architecture below is unchanged from
> milestone 13; the healthy-operation rates are not. The first cut wore a
> crankshaft out in about 39 hours of ordinary running, which made bearings and
> pistons into consumables. They are not consumables. See
> [§ 6](#6-gameplay-targets-measured) for the rebalanced calibration and
> [§ 5](#5-how-wear-accumulates) for the constants.

Engines wear out, and the only way to make one young again is to replace the
part that got old — but *wearing out* is now what happens to an engine that was
**abused**, not to one that was merely used.

The point of this milestone is not a durability bar. It is a chain:

```
CAUSE            no air filter, low oil, overspeed, heavy load
  ->
PHYSICAL WEAR    this piston assembly, that crankshaft section
  ->
SYMPTOM          less torque, less capacity, harder starting, more friction
  ->
DIAGNOSIS        the goggles name the part
  ->
MAINTENANCE      fit a new one
```

The reading a player should end up with is *"this engine starts badly because
cylinder 3 has lost its compression"*, never *"my engine is at 43 %"*. Everything
below follows from that one sentence.

---

## 1. Wear belongs to parts, not to engines

There is no `float engineHealth` anywhere. There are two kinds of wear and each
belongs to a physical component:

| Wear | Owned by | Scope |
| --- | --- | --- |
| Bearing wear | each **Crankshaft** section | that section alone |
| Compression wear | each installed **Piston Assembly** | that cylinder alone |

An inline-4 therefore has four independent bearing figures and four independent
compression figures. One tired section and three good ones is a real, diagnosable
state, and it is the state the diagnostics are built to describe.

The engine *derives* from those parts — it never owns them:

- **friction** comes from the **average** bearing wear, because four journals
  share one crankshaft and it is their combined drag the flywheel fights. Using
  the worst would make an inline-4 with one tired section behave like an engine
  with four of them, and using the sum would punish a player for building a bigger
  engine;
- **diagnostics** report the **worst**, because that is the section to go and look
  at;
- **compression** is strictly per cylinder and is never averaged into anything a
  power stroke reads.

### Why not the controller

Because the controller moves. Adding a crankcase to the negative end of a run
promotes a brand-new block to controller and demotes the old one; if the
controller owned the wear, every extension would move, reset or duplicate it. It
owns none, so extension is a non-event for condition — see §11.

---

## 2. Representation

Internally wear is a `float` in `[0, 1]`: 0 is a part fresh out of the crate, 1 is
a part at its service limit. It is clamped on every read and every write, because
it can arrive from outside the simulation — an old world, a command, a hand-edited
item — and the honest response to a value the physics has never seen is to bring
it into range rather than to trust it or throw.

Players never see the number. Every readout goes through `WearCondition`, which is
six bands with the thresholds in exactly one place (`EngineTuning`):

| Band | Wear |
| --- | --- |
| Pristine | 0.00 – 0.10 |
| Good | 0.10 – 0.30 |
| Used | 0.30 – 0.50 |
| Worn | 0.50 – 0.70 |
| Poor | 0.70 – 0.90 |
| Critical | 0.90 – 1.00 |

Each boundary belongs to the *worse* band, so no value is ever in two bands, and
the enum runs best-to-worst so `ordinal()` is a usable severity and
`WearCondition.worst(a, b)` is a comparison rather than a table.

---

## 3. Persistence, and the no-free-repair rule

**The hard requirement:** pull a worn Piston Assembly out and push the same one
back in, and it is still exactly as worn. Mine a worn crankcase and place it
again, and it is still exactly as worn. A *freshly crafted* part is pristine.

Wear therefore has to survive a part being an item rather than a block, and the
only place that survives is the item stack. Both wear types are 1.21 Data
Components, registered in `ECDataComponents`:

- `engineered_combustion:piston_wear`
- `engineered_combustion:crankshaft_bearing_wear`

Both are `Codec.floatRange(0, 1)` persistent and `ByteBufCodecs.FLOAT`
network-synchronised.

### The two round trips

**Piston Assembly** — entirely explicit, because the part is an item installed
into a block entity rather than a block of its own:

| Direction | Mechanism |
| --- | --- |
| out, by hand | `CylinderBlockEntity#takePistonAssemblyWear` removes the part **and** returns its wear in one call, so the two can never happen apart; `CylinderBlock` puts it on the stack |
| out, by mining | `CylinderBlock#onRemove` pops the same stack, built by the same helper |
| in | `CylinderBlock#useItemOn` reads the component and calls `installPistonAssembly(wear)` |

**Crankshaft** — 1.21's implicit-component path, the same pair Create's Toolbox
and Backtank use:

| Direction | Mechanism |
| --- | --- |
| out | `CrankshaftBlockEntity#collectImplicitComponents` puts the wear in the block entity's component map; the block's loot table copies it onto the dropped stack with `minecraft:copy_components` |
| in | `CrankshaftBlockEntity#applyImplicitComponents`, plus `CrankshaftBlock#setPlacedBy` as a second, idempotent path |

Both paths write the same number from the same stack, so having both costs
nothing and makes the result independent of where in the placement sequence each
one runs.

### Absent means new

A component that is not present reads as 0. That is the correct answer for every
stack that can lack one — a freshly crafted part, a creative inventory stack, an
item from a command, and every stack in a world saved before this milestone — and
wear is only ever *written* onto a stack that has some. A pristine part therefore
carries no data at all: it stacks with its siblings, its tooltip stays quiet, and
nothing about it hints that it has been anywhere.

An emptied bore forces its wear back to zero, so it can never hand the previous
occupant's condition to the next assembly fitted into it.

---

## 4. What wear does to the machine

Nothing anywhere reads a wear value and subtracts RPM. Both consequences feed the
physics the engine already solves.

### Compression

```
compressionEfficiency(w) = max(0.65, 1 − 0.25·w − 0.10·w²)
```

| Piston wear | Efficiency |
| --- | --- |
| 0.00 pristine | 1.000 |
| 0.35 used | 0.900 |
| 0.60 worn | 0.814 |
| 0.90 poor | 0.694 |
| 1.00 critical | 0.650 |

Smooth, strictly decreasing, never zero and never negative. It is **the**
multiplier a worn cylinder is worth, and it is latched into
`powerStrokeStrength[i]` at the instant that cylinder's charge is paid for. One
multiplication therefore covers:

- the running power stroke,
- the pre-start firing kick,
- and that cylinder's share of Create's Stress Capacity.

A critical cylinder is a *weak* cylinder, never a dead one. It still fires, still
consumes its charge, and still appears in the active cylinder mask.

### Bearing friction

```
frictionMultiplier = 1 + averageBearingWear · 0.8
```

1.0 pristine, 1.8 at the service limit. It multiplies the friction torque the
engine already fights (and the coast drag with it), so every consequence emerges
from the same equilibrium a healthy engine settles into:

- less reserve torque,
- a lower speed under the same load,
- a shorter coast-down,
- a harder start,
- more combustion events — and therefore more fuel — to hold any given speed.

Measured behaviour, single cylinder, settled after a start:

| Condition | 0 % throttle | 100 % throttle |
| --- | --- | --- |
| pristine | 66 RPM | 192 RPM |
| critical bearings only | 56 RPM | |
| critical compression only | 58 RPM | |
| both critical | 32 RPM | 128 RPM |

Even the last engine still catches and still runs, and replacing its parts puts it
straight back on 63 RPM at idle. Wear never seizes anything.

---

## 5. How wear accumulates

Every rate is quoted **per revolution** or **per combustion event**, never per
tick. Wear follows the work the machine actually did, so a server at 15 TPS wears
its engines at the same rate per revolution as one at 20, and a fast engine wears
faster than a slow one without a line saying so.

```
revolutions = |crankDeltaDegrees| / 360

bearingWear += revolutions · BASE_BEARING · oil · rpmFactor · bearingLoadFactor
cylinderWear += revolutions · BASE_CYLINDER · oil · filter · rpmFactor · cylinderLoadFactor
cylinderWear += BASE_COMBUSTION · oil · filter · rpmFactor · cylinderLoadFactor   (per charge burned)
```

Bearing wear is charged **per section**; cylinder wear **per bore with a Piston
Assembly in it**; combustion wear **only to cylinders whose combustion counter
actually moved this tick**.

### Tuning values

| Constant | 13.0 | **13.1** | Why it moved |
| --- | --- | --- | --- |
| `BASE_BEARING_WEAR_PER_REVOLUTION` | 2.0 × 10⁻⁶ | **1.75 × 10⁻⁸** | ÷114. The anchor: solved so 250 h at 192 RPM / half load costs 0.089 |
| `BASE_CYLINDER_WEAR_PER_REVOLUTION` | 0.5 × 10⁻⁶ | **4.5 × 10⁻⁹** | ÷111, keeping bores slightly behind bearings |
| `CYLINDER_WEAR_PER_COMBUSTION` | 1.0 × 10⁻⁶ | **9.0 × 10⁻⁹** | ÷111, same ratio as before |
| `WEAR_MULTIPLIER_OIL_NORMAL / LOW / DRY` | 1 / 4 / 40 | **1 / 18 / 1000** | Harm is a multiple of a much smaller number, so the multiples grew |
| `WEAR_MULTIPLIER_UNFILTERED` | 4 | **8** | An optional part has to be worth fitting over the long run |
| `RPM_STRESS_COEFFICIENT` | 0.35 | 0.35 | Unchanged — already mild below the rating |
| `OVERSPEED_WEAR_COEFFICIENT` | 24 | **80** | Overspeed is the one thing that still ruins a well-oiled engine |
| `BEARING_LOAD_WEAR_COEFFICIENT` | 0.9 | **0.6** | 1.6× at capacity. Load is work, not abuse |
| `CYLINDER_LOAD_WEAR_COEFFICIENT` | 0.4 | **0.25** | 1.25× at capacity |
| `COMPRESSION_LOSS_LINEAR / QUADRATIC` | 0.25 / 0.10 | **0.05 / 0.30** | Mostly quadratic, so a healthy engine is a full-strength engine |
| `BEARING_FRICTION_LINEAR / QUADRATIC` | (linear 0.8) | **0.10 / 0.70** | Same reshaping; the 1.8× endpoint is deliberately unchanged |
| `MAX_EXTRA_BEARING_FRICTION` | 0.8 | 0.8 | Now the sum of the two terms above |
| `MIN_COMPRESSION_EFFICIENCY` | 0.65 | 0.65 | Unchanged |

**Why the abuse multipliers are so much larger.** Dropping the baseline by a
factor of 114 makes every old multiplier meaningless — 40× a negligible number is
still negligible. Each one below is therefore sized against the *result* it has to
produce, not against the number it replaces, and the results are in § 6.

### Speed

```
stress    = min(1, |rpm| / 192)
overspeed = max(0, |rpm| − 192) / 192
rpmFactor = 1 + 0.35·stress² + 80·overspeed²
```

Two quadratics meeting at the rated speed. The factor is **continuous** through it
— smooth to seven digits either side — and the overspeed term starts from zero
slope, so an engine one RPM over its rating is charged about 0.16 % extra and
there is no threshold for a governor oscillation to trip. A whole minute at the
engine's own hardest possible overshoot (208 RPM) costs under 10⁻⁴ of wear.

The *slope* does step at the join, by about 0.0036 per RPM, because the stress
term stops growing where it clamps. The step is **downwards** — the penalty
briefly gets cheaper per RPM as the engine passes its rating, before the overspeed
term takes over — so it is a kink in the player's favour, not a cliff. Removing it
would mean letting the stress term keep climbing above the rating, double-counting
the overspeed the second term already charges for. (13.0's docs claimed the slope
was continuous; it never was, and the old test only checked the value.)

Below the rating the penalty is mild by design — 1.04× at idle, 1.35× flat out —
because per-revolution accounting has already charged a fast engine three times as
much. Above it the second term takes over hard: about **10×** at Create's default
256 RPM ceiling, on top of four times the revolutions of an idling engine. After
the rebalance this is the *only* way to destroy a well-lubricated engine.

Always the **mechanical** speed. An engine motored at 220 RPM is really turning at
220 RPM, whatever it thinks it generates.

### Load

```
bearingLoadFactor  = 1 + 0.6·load     (1.0 → 1.6)
cylinderLoadFactor = 1 + 0.25·load    (1.0 → 1.25)
```

Linear in the engine's existing normalised load factor. The network's absolute
stress figure is deliberately never used: it scales with speed, and the model
already has a speed term.

**Load is not abuse.** An engine exists to power machinery, and one hauling a full
network with oil in it is doing exactly what it was built for. It costs a little
more bearing life than freewheeling and it never approaches what mistreating the
same engine costs — at full load it is still over a thousand hours from its
service limit, against seven and a half hours run dry. A player who works an
engine hard is playing the game correctly, and 13.1 also stops the goggles from
implying otherwise (§ 9).

### Filtration

The Air Filter stays optional and the engine runs perfectly without one. It
multiplies **cylinder** wear by 8 and bearing wear by nothing — unfiltered air is
abrasive to rings and bores and is not what kills a main bearing. Both halves of
cylinder wear are filtered, because both are about air being drawn down the bore.
No filter durability, and no clogging.

Against the rebalanced baseline that 8× is still measured in hundreds of hours:
five unfiltered minutes ruin nothing, and it takes about 265 hours of unfiltered
running to wear a bore to `WORN`. That is the right shape for an optional part — a
long-term consequence a player can knowingly accept, never a trap.

### Lubrication

Reuses the existing `NORMAL / LOW / DRY`. Separate from the *friction* multipliers
and deliberately much larger: those say how much harder an unlubricated engine is
to turn, these say how much of itself it destroys doing it. Oil consumption is
untouched.

**`NORMAL` means safe.** The 1× is not a placeholder waiting to be made
interesting. It is the statement that *adequate serviceable lubrication is safe*:
an engine with oil in it and a filter on it wears at the base rate, and the base
rate is negligible. A later milestone may split oil **level** from oil
**condition** and put a multiplier between 1 and 18 on tired oil; nothing here
forecloses that, and no player-facing wording promises that oil can never age —
the Ponder and goggle text says *"proper lubrication keeps major component wear
very low"*, which stays true whatever a future milestone adds.

| State | Multiplier | What it feels like |
| --- | --- | --- |
| `NORMAL` | 1× | Safe. Thousands of hours. |
| `LOW` | 18× | Clearly harmful, not fatal. Three seconds is invisible; 100 h takes a fresh engine to `WORN`. |
| `DRY` | 1000× | Serious failure. 30 s costs 0.001 and is forgiven; left running it destroys itself in ~7.7 h. |

---

## 6. Gameplay targets, measured

### The philosophy 13.1 exists to enforce

A correctly lubricated, filtered and normally operated engine should experience
**near-negligible** major internal wear. Crankshaft bearings, pistons and
compression sealing are **not consumables**. The reading a player should end up
with is:

> *"If I lubricate it and treat it properly, this engine will last practically
> forever."*

Major internal replacement should normally happen because the engine was badly
abused, run under severe lubrication failure, repeatedly oversped, or accumulated
genuinely enormous operating time — **never because ordinary gameplay elapsed**.
The interesting story is *"this is the R4 we abused badly enough to wear out
cylinder 3"*, not *"it is Tuesday, time for new pistons"*.

### Calibration table

Fresh engine, continuous running, `NORMAL` oil, Air Filter fitted. **B** =
crankshaft bearing, **P** = piston / compression. Produced by
`EngineWearTests.reportCalibrationTable()` and printed on every test run, so these
numbers cannot drift away from the code.

| RPM | Load | | 10 h | 50 h | 100 h | 250 h | Condition at 250 h |
| ---: | ---: | --- | ---: | ---: | ---: | ---: | --- |
| 64 | 0 % | B | 0.0007 | 0.0035 | 0.0070 | 0.0175 | Pristine |
| | | P | 0.0005 | 0.0027 | 0.0054 | 0.0135 | Pristine |
| 64 | 50 % | B | 0.0009 | 0.0045 | 0.0091 | 0.0227 | Pristine |
| | | P | 0.0006 | 0.0030 | 0.0061 | 0.0151 | Pristine |
| 64 | 100 % | B | 0.0011 | 0.0056 | 0.0112 | 0.0279 | Pristine |
| | | P | 0.0007 | 0.0034 | 0.0067 | 0.0168 | Pristine |
| 128 | 0 % | B | 0.0016 | 0.0078 | 0.0155 | 0.0388 | Pristine |
| | | P | 0.0012 | 0.0060 | 0.0120 | 0.0300 | Pristine |
| 128 | 50 % | B | 0.0020 | 0.0101 | 0.0202 | 0.0505 | Pristine |
| | | P | 0.0013 | 0.0067 | 0.0135 | 0.0337 | Pristine |
| 128 | 100 % | B | 0.0025 | 0.0124 | 0.0248 | 0.0621 | Pristine |
| | | P | 0.0015 | 0.0075 | 0.0150 | 0.0374 | Pristine |
| **192** | **50 %** | **B** | **0.0035** | **0.0177** | **0.0354** | **0.0885** | **Pristine** |
| | | **P** | **0.0024** | **0.0118** | **0.0236** | **0.0590** | **Pristine** |
| 192 | 0 % | B | 0.0027 | 0.0136 | 0.0272 | 0.0680 | Pristine |
| | | P | 0.0021 | 0.0105 | 0.0210 | 0.0525 | Pristine |
| 192 | 100 % | B | 0.0044 | 0.0218 | 0.0435 | 0.1089 | Good |
| | | P | 0.0026 | 0.0131 | 0.0262 | 0.0656 | Pristine |

The bolded row is the calibration point. **At 100 hours a hard-working, well-kept
engine is at 0.035; at 250 hours it is at 0.089** — both comfortably inside the
milestone's 0.03–0.05 and 0.08–0.10 targets, and both still `PRISTINE`. Slower
engines are gentler still, monotonically, at every horizon.

`WORN`, `POOR` and `CRITICAL` are simply not reachable by a properly maintained
engine in an ordinary playthrough.

### Hours to the service limit

| Conditions | 13.0 | **13.1** |
| --- | ---: | ---: |
| full throttle, half load, oiled, filtered | — | **2,826 h** |
| bores, same conditions, filtered | ~63 h | **4,234 h** |
| half throttle, half load, oiled, filtered | ~39 h | ~5,600 h |
| idle, no load, oiled, filtered | ~125 h | ~19,000 h |
| bores, full throttle/half load, **unfiltered** | ~16 h | **529 h** |
| full throttle, half load, sustained **`LOW`** oil | — | **157 h** |
| **dry**, running at its own ~111 RPM | ~1.7 h | **7.7 h** |
| **dry + 256 RPM + full load** | — | **12.3 min to `CRITICAL`** |

### The feel this produces

| Situation | Result |
| --- | --- |
| 3 s of `LOW` oil | Nothing. Not measurable. |
| 30 s run dry | 0.001 wear. Still `PRISTINE`. Forgiven. |
| 5 min run dry | 0.011. Measurable, still `PRISTINE`. |
| 5 min with no Air Filter | Nothing. |
| Brief governor overshoot | Under 10⁻⁴ per minute even at the engine's hardest overshoot. |
| A full playthrough, looked after | Still `PRISTINE`. |
| An afternoon run dry | Serious damage. |
| Sustained external overspeed | The one thing that ruins a well-oiled engine. |
| Dry **and** oversped **and** loaded | `CRITICAL` in about twelve minutes. |
| 15 s of that same all-out abuse | Still `PRISTINE`. No instant destruction. |

The worst damage **emerges from the combination** — there is no hidden timer and
nothing anywhere says "after N minutes, destroy the engine". Dry, oversped and
fully loaded together is over five times worse than the worst of those three
alone, because the multipliers simply multiply.

### Performance loss is gentle where it should be

10 % wear is emphatically **not** 10 % power loss. Both consequence curves were
reshaped from mostly-linear to mostly-quadratic, keeping their endpoints, so a
healthy engine feels healthy and a worn one is unmistakable:

| Wear | Condition | Compression | Friction | Feel |
| ---: | --- | ---: | ---: | --- |
| 0.00 | Pristine | 1.000 | 1.000× | New |
| 0.10 | Good | 0.992 | 1.017× | Essentially 100 % |
| 0.30 | Used | 0.958 | 1.093× | Only a small loss |
| 0.50 | Worn | 0.900 | 1.225× | Clearly noticeable |
| 0.70 | Poor | 0.818 | 1.413× | Large degradation |
| 0.90 | Critical | 0.712 | 1.657× | Severe |
| 1.00 | Critical | 0.650 | 1.800× | The service limit |

Each band costs more than the band before it — the test asserts exactly that — and
the `CRITICAL` endpoints are **deliberately unchanged** from 13.0, which preserves
the worn-engine behaviour documented in § 6a.

### 6a. A critical engine cannot hold a normal idle — on purpose

A fully worn engine idles at about **half** its design idle speed, **stalls** at
0 % throttle under a full load a healthy engine shrugs off, and needs throttle to
carry that load. This is not a bug and it has not been "fixed": it is the torque
and friction model doing its job, and it is exactly how a worn-out engine behaves.

It was checked for the artifacts a numerical model could produce instead, and has
none of them:

| Check | Result |
| --- | --- |
| Numerical oscillation around zero | None — 0 sign reversals over a minute, RPM stays in 24.2–39.1 |
| Spontaneous reversal | Never turns backwards |
| Generation while stalled | Never — generation follows the phase, not the shaft |
| Impossible RPM lock | Not locked — 14.9 RPM of natural ripple between power strokes |
| Throttle response | Still monotonic: 32.5 / 57.2 / 79.9 / 124.8 RPM at 0/25/50/100 % |
| Repair restores idle | Completely — 63.8 RPM after a rebuild, against 63.8 fresh |

Pinned by `EngineWearTests.aCriticalEngineIdlesBadlyOnPurpose()`.

---

## 7. Create Stress Capacity

The active cylinder mask keeps its old meaning exactly: **which** cylinders are
firing. It is not redefined to mean "healthy". A worn cylinder is an active
cylinder.

Beside it there is now a second answer — **how strong** they are:

```
effectiveCylinderCapacity = Σ  active(i) ? compressionEfficiency(i) : 0
```

| Engine | Capacity |
| --- | --- |
| healthy inline-4 | 4.00 |
| one bore at the service limit | 3.65 |
| one Spark Plug pulled | 3.00 |
| motored by a neighbour | 0.00 |

`EngineState#getEffectiveCylinderCapacity()` is the only place that sum exists.
`EngineFlywheelBlockEntity#calculateAddedStressCapacity` multiplies Create's
registered per-RPM capacity by it, and the goggle readout prints Create's own
arithmetic on the same number — the HUD cannot report a figure the flywheel is not
using.

Mechanical wear is deliberately **not** a second multiplier on capacity. Worn
bearings already reduce output honestly, by lowering the speed the engine settles
at, and capacity is per RPM; charging them again here would double-count.

### Rate limiting

Wear moves by about a millionth of a cylinder per revolution, and every capacity
figure Create is handed costs it a re-registration and a network stress recompute.
So `publishedCapacityFactor` is quantised to `CAPACITY_QUANTUM = 0.01` cylinders —
a few dozen updates over a part's entire service life.

Everything that is *not* slow drift still publishes on the tick it happens:

- a cylinder starting or stopping firing (the mask moved),
- the engine catching or stalling,
- a Piston Assembly fitted or removed,
- structure invalidation, suspension, and the post-load reconciliation.

There is no dithering to worry about: wear only increases and compression is a
pure function of it, so the raw sum moves monotonically between events.

---

## 8. Starting

No threshold anywhere; a worn engine is harder to start for two physical reasons.

1. **Every firing kick is weaker**, because the kick torque is multiplied by that
   cylinder's compression — the same latch the running power stroke uses.
2. **More kicks are needed.** `requiredStartCycles` gains a term scaled by how
   much compression is gone, up to `START_CYCLES_WEAR_PENALTY = 3` extra events at
   the service limit. Continuous, so a lightly used engine asks for nothing extra.

The existing multi-cycle feel is unchanged, and an engine at the service limit
still catches — it simply takes noticeably longer. Under favourable conditions it
also keeps running, and opening the throttle pulls it up to ~123 RPM.

---

## 9. Player-facing readouts

**Engine overlay** gains one line:

```
Engine
 State: Running
 Cylinders: 4
 Speed: 192 RPM
 Generation: Active
 Ignition: On
 Throttle: 100%
 Condition: Worn
 Fuel: Gasoline
 Fuel Level: 812 / 1000 mB
 Lubrication: Normal
 Wear Risk: No Air Filter
```

`Condition` is the **worst** of the engine's mechanical condition and any one
cylinder's compression, never an average — three perfect bores must not hide a
fourth that needs a piston.

**Wear Risk** lines appear only while the crank is actually turning and only when
they apply, so a well-kept engine doing easy work prints none. Each corresponds to
a real multiplier: `Low Oil`, `No Oil`, `No Air Filter`, `Overspeed`, `Heavy Load`.
Being motored counts as operating — an engine geared up past its rating by a
stronger network is being destroyed whether or not it is burning anything.

#### Root causes, not cascades (13.1)

A warning is only worth printing if the player can act on it *and* acting on it
would help. Two are therefore conditional on more than their own multiplier.

**`No Air Filter` is suppressed while there is no Carburetor.** The filter mounts
on the Carburetor, so an engine without one has nowhere to put a filter, and the
overlay used to read:

```
Fuel:      No Carburetor      <- the actual problem
Wear Risk: No Air Filter      <- asks the player to fix the wrong thing
```

Now it prints only the first line. The filter warning becomes true and useful the
moment a Carburetor exists. The general rule: **show the root cause, not the
cascade of consequences below it.**

**`Heavy Load` is never shown on its own.** It was defensible at 1.9× against a
baseline of tens of hours; against 13.1's 1.6× on a baseline of thousands, an
engine hauling a full network is simply doing its job, and flagging it made a
correctly-used engine look broken. It appears only when it is *compounding* poor
lubrication or overspeed — which is where the multipliers actually make the
combination bite.

**Sneak diagnostics** break that one word apart:

```
Diagnostics
 Structure: Valid
 Layout: Inline-4
 Active Cylinders: 4 / 4
 Cylinder Status: ● ● ● ●
 Mechanical Condition: Good
 Cylinder 1 Compression: Good
 Cylinder 2 Compression: Good
 Cylinder 3 Compression: Worn
 Cylinder 4 Compression: Good
 Bearing Condition: Good
 ...
```

`Bearing Condition` is **this crankcase's own** journal, not the engine's average,
so walking along an inline-4 with the goggles on points at the section to replace.

**Cylinder overlay** names the part in the bore:

```
Cylinder 3 / 4
 Piston Assembly: Installed
 Spark Plug: Installed
 Compression: Worn
 Piston Position: 0.4
```

**Item tooltips** print a condition line only when the part has wear —
`Condition: Worn` on a Piston Assembly, `Bearing Condition: Used` on a Crankshaft.
A pristine part says nothing, so the line appearing *is* the information.

No exact percentage appears anywhere in normal play.

---

## 10. Maintenance

Diagnose the cylinder, stop the engine, pull the worn Piston Assembly, fit a fresh
one, restart. Replacing a worn Crankshaft section with a freshly crafted one is
the equivalent mechanical repair for bearings.

There is deliberately **no repair kit**. Nothing resets an engine's condition; the
only thing that improves it is a new part. Bearing refurbishment and workshop
processes are a later milestone's business.

Internal service needs a stopped engine. Pulling a Piston Assembly out of a
turning engine is refused with a concise action-bar line — *"Stop the engine
before servicing"* / *"Motor vor der Wartung abstellen"* — and no chat spam and no
item loss. The test is about **rotation**, not phase, so it covers an engine being
motored by another Create source and one still coasting down. The Spark Plug is
not covered: it screws into the head from outside, and pulling one out of a
running engine is an ordinary way to shut a cylinder down.

---

## 11. Extension and migration

Wear belongs to the parts, so the controller migration needs no wear code at all
and did not get any.

- Adding a crankcase at the negative end promotes a new block to controller. Every
  existing section keeps its own bearing wear; the new one is pristine.
- A worn inline-2 plus a fresh third section, cylinder and piston becomes an
  inline-3 whose first two cylinders are as worn as they were and whose third is
  new. Nothing is averaged into the new parts.
- Cutting an engine in half leaves each surviving section holding exactly the wear
  it had.

---

## 12. Network synchronisation

The server owns the exact figure and saves it exactly. The client needs only
enough to name a condition band and to trace the same coast-down curve, so
everything on the wire is quantised to `WEAR_SYNC_QUANTUM = 0.01` and sent only
when the quantised value actually moves — about a hundred updates over a part's
whole life, against one per tick if it were sent live.

| Value | Path |
| --- | --- |
| each Cylinder's piston wear | that Cylinder's block entity data, on quantised change |
| each section's bearing wear | that section's block entity data, on quantised change |
| the engine's average bearing wear | controller's client packet — the client integrates a freewheeling spin-down itself, and worn bearings multiply the friction it fights |
| the capacity factor | controller's client packet — derived from combustion ages the client never sees |

Exact wear is written to disk and is what a removed part receives; the client's
quantised copy is never authoritative for anything. There are no per-revolution
packets.

---

## 13. Sound

No new sound asset. The existing mechanical loop gains a second roughness term
beside the dry-engine flutter it already had: slower, shallower, fading in from
`Worn` and reaching full depth at the service limit. Like the dry wobble it is a
pure function of game time, so it adds no state, cannot accumulate error, and
sounds identical for every player watching the same engine.

Compression wear is already audible indirectly — the engine pulls less hard,
starting takes longer, and the speed under load is lower. Nothing fakes a
combustion event.

---

## 14. Development-only accelerated wear

Real wear is measured in thousands of hours, which makes testing it by playing
impossible. `EngineTuning.wearRateMultiplier()` multiplies every accumulated
increment and is read once, at class initialisation, from a JVM system property:

```
-Dengineered_combustion.wearMultiplier=100000
```

**13.1 raised the useful setting by a factor of fifty.** The rebalanced baseline is
some 2,826 hours to the service limit, so the old 2000× now compresses a whole
engine's life to about an hour and a half rather than a couple of minutes. The
`MAX_WEAR_MULTIPLIER` ceiling of 100000 puts it back at roughly 1.7 minutes, which
is what the manual test matrix needs; 2000× is now the right setting for watching
wear accumulate at a readable pace rather than for reaching the service limit.

A property rather than a command or a config, deliberately: it cannot be reached
from inside a running game, so there is no cheat button on a Survival server, and
it adds no command surface. It is ignored unless it parses to a positive number no
larger than 100000, and a malformed value falls back to the shipping rate rather
than stopping an engine.

At 100000× a whole engine's life fits in a couple of minutes, which is what the
manual matrix below needs.

---

## 15. Tests

### Pure simulation — `EngineWearTests`

Minecraft-free, run by `./gradlew simulationTest`. The curves are checked as
functions — monotonicity, continuity, end points — because a discontinuity in the
overspeed curve is exactly the bug that would punish a player for one tick of
governor ripple. The consequences are checked against a real `EngineState`,
because the design claim is that nothing about a worn engine is written down
separately.

| Ref | Check |
| --- | --- |
| A | wear never leaves `[0, 1]`, including NaN and an engine run to destruction |
| B | normal oil < low oil < dry, as rates and through three real engines |
| C | filtered < unfiltered cylinder wear, and bearings untouched by filtration |
| D | more speed, more wear; the factor never falls; sign-independent |
| E | the overspeed curve is continuous through the rating; no cliff at +1 RPM |
| F | load raises bearing wear most, and never dominates |
| G | compression falls monotonically and smoothly, and never to zero |
| H | bearing friction rises monotonically, landing in 1.5×–2.0× |
| I | a healthy cylinder is worth 1.0 |
| J | a critical cylinder is worth 0.65, inside the intended 0.60–0.70 |
| K | an inline-4 with one dead-compression bore is worth 3.65, and a mixed engine is the sum of its cylinders |
| L | an inactive cylinder contributes 0 however healthy |
| M | a motored dry engine wears bearings and takes no combustion wear |
| N | the condition bands are ordered, tile `[0, 1]`, and each boundary belongs to the worse band |
| O | **(13.1)** a looked-after engine lasts *thousands* of hours; 10 h, 50 h, 100 h and 250 h all measured against the milestone's targets; 64 < 128 < 192 RPM at every horizon; full load is work rather than abuse |
| O2 | **(13.1)** three seconds of low oil is nothing and a hundred hours of it is real damage; five dry minutes is measurable and not yet damage; a dry engine dies in hours, never seconds |
| O3 | **(13.1)** a governor ripple costs a few percent and a whole minute at the engine's own overshoot ceiling is unmeasurable; slight overspeed is modest, significant overspeed climbs steeply, Create's ceiling ruins even a well-oiled engine |
| O4 | **(13.1)** dry + oversped + loaded is over 5× the worst single factor, reaches `CRITICAL` in tens of minutes, and still destroys nothing in fifteen seconds |
| O5 | **(13.1)** `PRISTINE` and `GOOD` are indistinguishable from new in compression, friction and settled RPM; a hundred ordinary hours moves at most one condition band |
| P | capacity does not move over a thousand ticks of real wear, moves once per quantum under accelerated wear, and publishes at once when a cylinder drops out |
| A14 | **(13.1)** a critical engine idles badly *and that is intended* — see § 6a for the six artifact checks |

Plus: a worn cylinder is genuinely weaker (speed, capacity, and fuel per bang
unchanged); worn bearings shorten the coast-down monotonically; a critically worn
engine still catches, still runs, and is completely restored by replacing its
parts.

**Two 13.1 tests were restated rather than retuned.** Check E used to bound the
absolute step between two samples 0.01 RPM apart, which was really a bound on the
overspeed coefficient — and at 570 RPM it was measuring float ULP rather than the
curve at all. It now bounds each step by the curve's own analytic steepness, which
is the property "no cliff" actually means. Check G's endpoints (0.90 at 0.35 wear,
0.81 at 0.60) described the old mostly-linear curve; it now asserts the *shape*
the rebalance requires — every band costing more compression than the band before
it — which is a claim about the design rather than about two sampled points.

### Existing suites

All still green and none weakened: `EngineStabilityTests`, `EngineReloadTests`,
`MultiCylinderTests`, `SparkPlugTests`, `EngineCapacityTests`, `EngineLayoutTests`,
`LastPowerStrokeTests`, `EngineCoastDownTests`, and `checkModels`.

One implementation detail exists purely to keep them bit-identical: the bearing
friction multiplier is applied to each drag term separately rather than to their
sum, so that a pristine engine (multiplier exactly 1) integrates the arithmetic it
always did. Float addition is not associative, and an engine's stall behaviour at
low speed is close enough to the edge for one ULP to move a tick.

One assertion in `EngineCapacityTests` did legitimately change, and it is not a
weakening. Its `capacitySu()` helper reproduces the arithmetic Create performs, and
that arithmetic genuinely moved: the multiplier is now
`getPublishedCapacityFactor()` rather than `getFiringCylinderCount()`. Every engine
in that file is built from new parts, where the two are equal, and a check was
added asserting exactly that rather than leaving it assumed. No expectation in the
file was relaxed.

### Manual, in game

Block-entity and item-drop integration cannot be exercised without Minecraft.
These have to be done by hand, ideally with the accelerated multiplier above:

| Test | Expectation |
| --- | --- |
| W1 pristine R1 | Condition Pristine, normal start, normal capacity, normal coast |
| W2 pristine R4 | all four cylinders pristine, full capacity |
| W3 no Air Filter | compression wear grows several times faster; bearing wear unchanged |
| W4 Low Oil | both wear types faster; `Wear Risk: Low Oil` shown |
| W5 Dry | very fast wear, more friction, still recoverable, no explosion or block loss |
| W6 externally driven dry, ignition off | bearing wear rises, no combustion events, no combustion wear, Generated Capacity 0 |
| W7 worn cylinder 3 | `Active Cylinders: 4 / 4`, `Cylinder 3 Compression: Worn`, lower capacity than a pristine R4 |
| W8 piston round trip | removed item's tooltip reports its condition; refitting the same item restores the same bad compression; a fresh one restores compression |
| W9 crankshaft round trip | mined item keeps its bearing condition; placing it restores it; a freshly crafted one is pristine |
| W10 save/rejoin | exact wear, condition and capacity all preserved |
| W11 controller migration | extending at the negative end leaves every existing section's wear attached to it; the new section is pristine |
| W12 capacity cache | crossing a quantum boundary updates Create once, not every tick |
| W13 service while running | refused with the action-bar line; no item loss or duplication |
| W14 multiplayer | two players see the same condition; no packet spam; no stale overlay after joining |

---

## 16. What milestone 13 deliberately does NOT model

- temperature, cooling or coolant
- oil age, contamination or oil burning
- cylinder **bore** wear as a separate quantity — which is precisely why fitting a
  new Piston Assembly restores a cylinder's compression completely
- spark plug wear
- Air Filter durability or clogging
- random or probabilistic misfires
- catastrophic seizure, explosions, block destruction or item deletion
- four-stroke timing, valves, camshafts, diesel, forced induction, starter motors,
  alternators, or any layout beyond inline-1 to inline-4
- ~~Ponder scenes~~ — added in milestone 14, see
  [`milestone-14-player-experience.md`](milestone-14-player-experience.md)

At Critical an engine is hard to start, weak and stiff — and always recoverable by
replacing the part that is worn. Seizure and catastrophic failure belong with a
future temperature system.

### Maintenance systems a future milestone may add

None of these exist yet, and 13.1's wording is deliberately chosen not to promise
that they never will:

- **oil condition**, separate from oil level — tired oil sitting between `NORMAL`
  and `LOW`
- **Air Filter clogging and durability**, making the filter a serviceable part
  rather than a permanent fitting
- **spark plug service** — fouling, replacement intervals
- coolant, temperature, and the seizure behaviour that belongs with them

This is why no player-facing string says anything like *"oil never wears out"*.
The Ponder and goggle wording is *"proper lubrication keeps major component wear
very low"*, which stays true whatever any of the above later adds.

---

## 17. Known limitations and balancing concerns

- **The rates are calibrated, not played.** They are solved against the targets in
  § 6 and asserted by the tests, but nobody has yet run an engine for 250 hours to
  confirm the feel. Every one lives in `EngineTuning` and nothing outside
  `EngineWearMath` writes an equation, so they remain cheap to adjust.
- **Both bearings and compression at the service limit at once** is the single
  configuration where an engine can no longer idle at 0 % throttle. 13.1
  investigated this rather than removing it and concluded it is **intended
  physical behaviour**, not an artifact — see § 6a for the six checks and the
  regression test that pins it.
- **Dry and unfiltered together** now multiply to 8000× cylinder wear. That sounds
  enormous and is the correct scale against a baseline of 4,234 hours: it is the
  "everything wrong at once" case, and it still takes minutes rather than seconds.
- **Bearing wear paces a well-kept engine and cylinder wear paces an unfiltered
  one.** Preserved deliberately through the rebalance — the ratio between the two
  base rates is unchanged — but whether it is the right pair of pacing items is
  still a play question.
- **An engine with no Carburetor at all counts as unfiltered.** Consistent — it can
  only accumulate motored wear, and being motored with an open intake really is
  unfiltered. 13.1 fixed the *reporting* of this (§ 9) without changing the
  simulation: the engine still wears as unfiltered, the overlay just no longer
  tells the player to fit a filter they cannot fit.
- **Wear is not yet visible on any model.** Diagnosis is entirely through the
  goggles and Ponder.
- **`LOW` oil is hard to sustain deliberately**, because a sump drains through
  `LOW` into `DRY`. Its 18× multiplier is therefore mostly experienced as a brief
  transition rather than as a state a player sits in, which makes it the least
  play-tested part of the model.
