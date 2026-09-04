# Milestone 15B — Four-stroke in production, the valvetrain, and the second visual pass

**Status: implemented in `src/main/`.** This is the milestone where the design
frozen across `milestone-15-four-stroke-design.md`,
`milestone-15-valvetrain-design.md` and `milestone-15-production-migration.md`
stops being a prototype and becomes the engine the player runs.

Nothing in those three documents was renegotiated here. Where this document
repeats a number, it is repeating a frozen one; where it introduces a number, it
says so and says what it is solving for.

---

## 0. What the branch is

One integration branch, `claude/milestone-15b-four-stroke-integration`, built
from the current heads of the two branches it has to reconcile:

| Parent | Branch | Head |
| --- | --- | --- |
| first | `claude/ponder-highlight-precision-fhyzoa` | `9e341a7` |
| second | `claude/four-stroke-engine-architecture-1wezho` | `3193f05` |

They were joined by a real merge (`0c2af7b`), not a squash. Both histories are
intact, because the 15A prototype is not brainstorming that can be thrown away
once it has been read: it is the reference model the production engine is
checked against, and `FourStrokeBalanceTests` still compiles the prototype and
the production engine side by side and requires them to agree.

The merge itself was clean apart from one conflict, in `build.gradle`'s
`simulationTestClasses` list: both sides had added suites. Resolved by keeping
all of them.

---

## 1. The cycle, as production represents it

The frozen representation, implemented in
`content/engine/fourstroke/CyclePosition`:

```java
long  cycleIndex;      // which complete 720-degree cycle we are in
float angleDegrees;    // where in it, always in [0, 720)
float lastDeltaDegrees;
```

Two fields rather than one unbounded float, for the reason the design document
gives: an unbounded `float` loses a degree of resolution roughly every three
days of continuous running, and an engine whose ignition point drifts is a bug
nobody will ever reproduce.

Everything else is derived, and derived is the important word. There is exactly
one clock in the engine:

| Quantity | Derivation |
| --- | --- |
| physical crank angle | `angleDegrees mod 360` |
| stroke | `FourStrokePhase.at(angleDegrees)` |
| camshaft angle | `angleDegrees / 2` |
| valve lift | `ValveTiming.lift(angleDegrees)` |
| firing identity | `(cylinder, cycleIndex)` at the crossing |

The camshaft angle in particular is a division, not a second counter. A cam
that counted its own revolutions could drift out of phase with the crank that
drives it, and a valvetrain that is half a stroke out of phase with its own
engine is precisely the class of bug the player cannot see and cannot report.

### Stroke boundaries

```
   0                180               360               540              720
   |  COMPRESSION    |     POWER       |    EXHAUST      |    INTAKE      |
   BDC              TDC               BDC               TDC              BDC
   sealed, rising    sealed, falling   open, rising      open, falling
                     ^ ignition                          ^ arming
```

`CrankMath.pistonPosition = 0.5 - 0.5 cos(theta)` puts BDC at 0 and TDC at 180,
which is why the cycle starts on compression rather than on intake. It is the
same convention the 360-degree engine used, so a saved crank angle still means
the same physical thing after migration.

---

## 2. Crank geometry is not ignition timing

The single most load-bearing separation in the whole milestone.
`FourStrokeFiringOrder` holds one table per layout and derives two different
sets of offsets from it:

| Layout | Crank throws (mod 360) | Ignition offsets (mod 720) | Firing intervals |
| --- | --- | --- | --- |
| R1 | 0 | 0 | 720 |
| R2 (frozen default, uneven) | 0, 180 | 0, 180 | 180, 540 |
| R3 | 0, 120, 240 | 0, 240, 480 | 240, 240, 240 |
| R4 | 0, 180, 180, 0 | 0, 540, 180, 360 | 180, 180, 180, 180 |

The R4 row is the one to read twice. Its pistons move in two pairs, 1+4
together and 2+3 together, which is what a flat-plane crank is. Its cylinders
fire 1-3-4-2, evenly, because each of those pairs is on opposite strokes. A
model that stored one offset per cylinder and used it for both would have to
choose which of those two facts to get wrong.

The inline-2 stays the **180-degree opposed, uneven-fire** twin that 15A
selected on character grounds, firing at 180 and then waiting 540. It is the
rougher of the two candidates by measurement, 15.60 % speed ripple at idle
against the even twin's 9.41 %, and that is the point of it. `MultiCylinderTests`
asserts the throws, the intervals and `evenFire() == false` so it cannot quietly
become the smoother 360-degree twin later.

Verified on the shipped engine (`ProductionEngineFourStrokeTests` section A):

```
R1 fires 1        intervals 719
R2 fires 1-2      intervals 177 / 539
R3 fires 1-2-3    intervals 230 / 243 / 243
R4 fires 1-3-4-2  intervals 179 / 179 / 179 / 179
```

The one-degree shortfalls are the tick quantisation, not a phase error: each
cylinder still fires exactly once per 720 degrees.

---

## 3. What stops a cylinder firing twice

Three independent guards, because each of them fails differently.

**The arming latch** is physical. A cylinder draws a charge when it crosses 540
forwards, on the intake stroke, and burns it when it crosses 180. Without this,
an engine rocked back and forth across the ignition point, 179 to 181 to
reverse to 181, fires on every forward crossing and pays for none of them. With
it, the exploit costs a full intake stroke per bang and stops being an exploit.
`ProductionEngineFourStrokeTests` section G rocks a crank for a minute and
measures zero combustions and zero fuel.

**The event key** is bookkeeping: `lastFiredCycle[cylinder]` records the
`cycleIndex` the crossing belonged to, and a repeat of the same key is
rejected. `CyclePosition.crossingCycleIndex` is careful about which cycle a
crossing belongs to when the crossing is the wrap itself.

**The per-tick cap** is the failsafe. At absurd angular speeds a cylinder can
sweep past its ignition point more than once in a single tick, and the engine
fires it at most once regardless. Section H drives an engine at speeds no
Create contraption can produce and measures 800 events over 200 ticks against
800 arming opportunities, and fuel drawn exactly matching, 1600 mB.

Reverse rotation fires nothing at all: a backward crossing disarms rather than
ignites.

---

## 4. Fuel and duty, in the same commit

This is the pairing that had to be atomic, because either half alone is a
balance bug that ships.

| | 360-degree engine | four-stroke |
| --- | --- | --- |
| combustion events | 1 per 360 degrees | 1 per 720 degrees |
| fuel per combustion | 1 mB | **2 mB** |
| power-stroke duty | 180/360 = 0.5 | **180/720 = 0.25** |
| peak combustion torque | solved for duty 0.5 | solved for duty 0.25 |

The duty is the balance lever. The equilibrium condition is
`peak * DUTY * 0.5 = friction(target)`, so halving the duty doubles the peak
automatically and the mean torque comes out where it was. It is not a tuned
number; it is the same equation with a different constant, and
`FourStrokeBalanceTests` prints the peak it lands on (72.96, exactly twice the
360-degree one) and checks it.

The result is that the player's fuel bill did not move at all:

```
R1 1 mB per revolution      R3 3 mB per revolution
R2 2 mB per revolution      R4 4 mB per revolution
```

measured at 0.994 / 2.000 / 3.000 / 4.000 on the shipped engine. Oil
consumption and cylinder wear were rescaled by the same factor for the same
reason, so a tank of fuel and a bottle of oil last exactly as long as they did.

`FLYWHEEL_INERTIA` is unchanged at 20.0. It was not retuned, and the smoothness
ladder came out of the geometry rather than out of a per-layout fudge factor:

```
speed ripple at 64 RPM, no load:  R1 23.81%   R2 15.60%   R3 3.74%   R4 0.72%
```

There is no `if (cylinders == 1) multiply by` anywhere in the engine.

---

## 5. Compression, once per cycle

The gas spring now acts on the compression stroke only, `[0, 180)`, and not on
exhaust. A four-stroke that resisted twice per cycle would be a two-stroke
wearing a costume, and the difference is visible on a motored engine: section D
counts 11 compression peaks over 10.7 cycles, one each.

`ValveTiming.sealed()` and `FourStrokePhase.sealed()` agree everywhere except
exactly at the four dead centres, where one is asking "is the valve shut" and
the other "is the piston between the valve events". That difference is real and
is asserted rather than papered over.

---

## 6. Starting

`START_ATTEMPT_TIMEOUT_TICKS` stays at **30**. It was not raised to 250. It
answers a different question than the one that needed answering: it is a
staleness rule about whether anything is happening at all, and the thing that
had to change was the rule about whether anything is happening *usefully*.

So a start attempt now also lapses after **two full cycles of crank travel
without combustion** (`START_ATTEMPT_TRAVEL_DEGREES = 1440`). Travel, not time,
because a player inching a crank round by hand for five minutes is still making
progress and should not be told they failed, while an engine spinning at speed
through two complete cycles without catching genuinely has not caught.

Measured worst cases, over every possible rest position:

| Layout | worst first bang | mean |
| --- | --- | --- |
| R1 | 720 degrees | 361 |
| R2 | 540 degrees | 226 |
| R3 | 240 degrees | 121 |
| R4 | 180 degrees | 91 |

Arming a cylinder that comes to rest mid-intake is what caps R1 at one cycle;
without it the worst case is 1080 degrees of silence, and three revolutions of
nothing happening reads as a broken machine.

**A behaviour change worth knowing before you test:** a single-cylinder engine
now takes roughly 326 ticks, about 16 seconds, of 32 RPM hand cranking to catch,
against roughly 131 before. The number of firing opportunities it needs is
unchanged; they are simply 720 degrees apart now instead of 360. Multi-cylinder
engines are barely affected.

The STARTING to RUNNING transition also became stricter, and had to. An engine
can now catch one bang and then bleed below the stall speed during the 540
degrees before its next one, so it is only declared RUNNING when
`EngineTuning.carriesToNextCombustion(rpm, cylinders, frictionScale)` says its
current speed survives *its own* firing gap against *its own* friction. The
friction scale matters: an early version used a single global RPM bar, and a
critically worn single passed a bar computed for pristine friction and then
stalled anyway.

Consequence, and it is a deliberate one: **a single-cylinder engine worn to 1.0
on both bearings and bore will no longer idle.** It settles around 32 RPM,
which cannot carry it 540 degrees against 1.8x friction. It still runs at about
126 RPM on throttle, and it needs more than a hand crank to start. Every
multi-cylinder engine still idles at the service limit, and everything at 0.875
wear or below degrades smoothly.

---

## 7. Active cylinders and Create capacity

The active-cylinder mask means "genuinely participating in combustion", not
"currently in a power stroke". On a four-stroke the second reading would blink
every cylinder off for three quarters of every cycle, and the Stress Capacity
derived from it would pulse in time with the engine.

The hard 60-tick ceiling is gone. The allowance is now
`GENERATION_COMBUSTION_CYCLES = 2.5` firing intervals, computed from the
engine's own speed and cylinder count, clamped at the stall speed so it cannot
run away as the engine slows. 2.5 rather than 2.0 because 2.0 sits exactly on
the boundary where one missed firing drops a cylinder, and a constant on a
boundary is a constant that will be wrong on some machine:

```
tolerance 1.5 (58 ticks vs a 38-tick interval): DROPS the cylinder
tolerance 2.0 (77 ticks): survives one missed firing
tolerance 2.5 (96 ticks): survives one missed firing, with margin
```

Age is the fallback, not the mechanism. Losing a spark plug, a piston, the
structure, ignition or the Camshaft clears the affected bits on the **same
tick**, without waiting for anything to age out. Section J and the balance
suite's fault tests both check this, including the case where the age alone
would still have said the cylinder was fine.

Capacity follows the mask, so: an engine turned by an external motor with no
combustion publishes **zero** capacity, not a fraction of one. A dry, motored
inline-4 spinning at 192 RPM reports `mask 0, capacity 0.0`.

---

## 8. Save schema version 2

`EngineSchema.VERSION_FOUR_STROKE = 2`, written explicitly, so a version-1 save
is recognised rather than guessed at.

The migration is deliberately lossy, and the reason is in the production
migration document: a version-1 save records one crank angle in `[0, 360)`, and
one crank angle corresponds to two cycle positions on two different strokes.
That information was never written down and cannot be recovered. Inferring it
would be wrong half the time.

| Field | Migration |
| --- | --- |
| `CycleAngle` | = the old `CrankAngle`, unchanged |
| `CycleIndex` | 0 |
| `ArmedCylinders` | **0, always** |
| `activeCylinderMask` | reset to 0 |
| the paid-for power stroke in progress | **discarded** |
| `Phase` RUNNING or COASTING | COASTING |
| `Phase` anything else | STOPPED |
| wear, fuel, oil, control settings | carried untouched |

The engine wakes up coasting, unarmed, and has to draw a charge before it can
burn one. That costs a returning player one intake stroke. No free energy is
worth more than that, and every one of those rules exists to make sure a
reloaded world cannot produce a joule it did not pay for.

Existing worlds also get **no free Camshaft**. See below.

---

## 9. The Camshaft

### What the player does

One Camshaft, crafted, right-clicked onto the Crankshaft. Once per engine, not
once per cylinder, so an inline-4 costs the same one extra interaction as an
inline-1.

The recipe is the same tier as the rest of the engine and has no circular
dependency on anything an engine produces:

```
A I A      A = Andesite Alloy
S C S      I = Iron Sheet
A I A      S = Shaft
           C = Cogwheel
```

Shaft and cogwheel are in it because the thing being built is a timing drive.
It is craftable before the player's first engine has ever run, which it has to
be, because without it no engine can run.

### What it does mechanically

It is the gate on combustion, and only on combustion. Without one the engine is
still a completely valid machine: it turns, its structure lines read green, its
plugs still spark, its compression still resists. It simply never draws a
charge, so it never fires, never burns fuel, never generates and never makes
engine noise. Section E measures exactly that: 0 combustions, 0 mB, mask 0,
0.0 RPM published, and the crank still turning at 96 RPM.

The plugs continuing to spark is intentional. It is the reading that tells a
player the fault is not their ignition.

### What the goggles say

**"Camshaft: Missing"**, specifically, in red, above the spark plug warning.
Not a vague INVALID state, because the engine is not invalid, and a player sent
looking for a structure fault that does not exist will not find it. Above the
plug warning because it is upstream of it: an engine with no Camshaft never
draws a charge, so fixing the plug first fixes the second thing wrong with the
machine.

### Ownership

The Camshaft follows the Redstone Control Module's model exactly, through the
same method:

```java
successor.camshaftInstalled = handOverOneOf(camshaftInstalled,
    successor.camshaftInstalled, ECItems.CAMSHAFT.get(), newControllerPos);
```

A **move**, not a copy. When the controller position changes because the player
extended or shortened the engine, the part moves to the new controller and the
old flag is cleared, which is what `CrankshaftBlock#onRemove` reads when it
decides whether to drop anything. If both the old and the new controller
somehow hold one, the duplicate is ejected into the world exactly once.

`InstalledComponentConservationTests` covers the invariant across every
rebuild, split and merge path: the number of Camshafts in the world is
constant, and it is never zero-with-a-flag-set or two-with-one-flag.

### And existing worlds

Get none. A version-1 engine loads without a Camshaft and has to be given one.
Handing out a free item to every saved engine would be the wrong side of the
same trade as the arming reset.

---

## 10. The valvetrain, and what it is for

> **Superseded in part.** The first in-game R1 test found the Camshaft
> unreadable as an item, invisible as an installed part, and the 2:1 timing
> relationship drawn nowhere at all. The redesign, the timing drive it gained,
> and the two geometry clashes it turned up are in
> `milestone-15b-camshaft-visuals.md`. The mechanism described below is
> unchanged; what it looks like is not.


Side-mounted **OHV with pushrods and rockers**, not overhead cam. That was a
deliberate design choice and it earns its keep twice over here.

Mechanically it is a raised-cosine lift curve, `(1 - cos(2 pi t)) / 2`, with a
simple timing map. `CamshaftTiming` derives everything from `cycleAngle / 2`:
lobe angle, pushrod lift, rocker angle, and whether the chamber is sealed.

Visually it is the thing that solves the problem the first visual pass did not.
The cam sits low on the -Z flank of the crankcase, in a shared cradle that runs
the whole length of the engine, and the pushrods and rocker shaft carry the
motion up to the head. That gives every multi-cylinder engine a **continuous
horizontal element at two heights**, which is what an inline engine reads as
and what four separate cylinder towers do not.

The +Z flank was deliberately left empty and the model checker enforces it, so
there is room for an exhaust manifold later without redesigning anything.

---

## 11. The second visual pass

The first pass made the engine tidier. The complaint it did not answer was that
it still read as several machines standing in a row. Four changes, all of them
about continuity rather than detail:

**A shared crankcase silhouette.** The crank line is one unbroken casting along
the whole engine rather than a repeated per-block form.

**A shared oil pan.** The sump's tray is now continuous under the crankcase for
the full length of the engine. Before, an inline-4's oil pan looked like a small
tank hanging under cylinder 1, which is exactly what it was.

**A shared intake manifold.** Runners leave the Carburetor and reach each
cylinder, so the fuel path is a visible object rather than an implied one.

**Top-end coherence**, via the valvetrain above: rocker shaft and cam cradle
run the length of the engine at two different heights, which is what ties the
towers together.

`tools/check_models.py` gained a directional reach rule to keep this honest:
sideways extent is bounded absolutely in X, must be **empty** in +Z (reserved),
and is bounded by `VALVETRAIN_REACH = 2.0` in -Z. The old rule was "nothing
leaves the block", which the valvetrain legitimately does; deleting it would
have removed the check that catches accidental overhang.

### The X/Z orientation bug

`CylinderRenderer` asked the *Crankshaft's* block entity for the engine axis
and fell back to `Axis.X` when it could not reach it, so a Z-aligned engine
rendered its cylinders rotated. It now reads `CylinderBlock.AXIS` from its own
blockstate first. This was a real bug, reproducible on any Z-aligned engine,
and it is fixed.

---

## 12. Client phase, and the mistake it prevents

A 360-degree phase error on a four-stroke is invisible in the worst possible
way. The piston is in exactly the same place. The valves are exactly one
stroke-cycle half out. A client that lands there stays wrong forever, and
nobody can report it because nothing looks broken.

So the client is corrected by **event-based anchors** rather than by streaming
state: `EngineTickPayload` carries `(controllerPos, sparkMask, combustionMask,
cycleAngleDegrees, armedMask)`, sent on combustion events and on a periodic
countdown, not every tick for every engine.

Correction has two regimes, in `EngineState.correctCyclePhase`:

- error above `PHASE_SNAP_DEGREES` (90): **snap**. A stroke-out client is wrong
  by 360 and gets put right in one anchor rather than sliding visibly through
  half a cycle.
- error below it: close `PHASE_CORRECTION_FRACTION` (0.5) of the shortest-way
  error per anchor, so ordinary jitter converges within a couple of seconds and
  never jumps.

Shortest-way across the 720 wrap in both directions, and a correction is never
counted as crank travel, so it cannot feed the starting logic or the wear model.
Target accuracy is 15 to 30 crank degrees in steady state. Section L2 covers all
of it, including the case that motivated the whole thing: a client exactly one
revolution out has the same piston position and the opposite stroke, and one
anchor puts it on the right one.

---

## 13. Sound

The **event wiring** needed no change and got none. `CombustionAudio` measures
its rate from the events themselves:

```java
float instant = 20.0F / Math.max(1L, now - lastEventTick);
```

It never derived a rate from RPM, so the four-stroke cadence, including the
uneven twin's 180/540 limp, emerges from the events. R4 at full throttle peaks
at 6.4 Hz against the 12 Hz threshold where individual pulses stop being
distinguishable, so nothing is ever aggregated or thinned either.

The **assets** did need changing, and the first in-game test is what found it.
`engine_mechanical.ogg` carried one compression swell and one over-centre knock
per crank *revolution*, which is a two-stroke's load pattern: on a four-stroke
that puts a percussive event squarely between two real bangs, and it was heard
as an engine firing twice as often as it did. The loop is now built on the
720° cycle, the pulse carries an exhaust blowdown so each bang is worth the
wait, and a sparse-pulse gain separates the lumpy single from the smooth four.

The whole audit, the measurements, and an honest costing of the engine-RPM
versus Create-RPM question are in `milestone-15b-audio.md`.

---

## 14. Taking the engine apart

The reported bug was real and worse than reported. **All five machine blocks
were registered with `requiresCorrectToolForDrops()` and were absent from every
`mineable/*` tag**, and `requiresCorrectToolForDrops` is satisfied only by tag
membership. They dropped nothing, to any tool, forever.

Fixed by a `MINEABLE_BLOCKS` list in `tools/generate_survival_data.py` that
feeds both `mineable/pickaxe` and `needs_stone_tool`, and by a new
`validate_ux.py` check that fails the build if a registered block is not
minable, tiered, dropping and named. The check was proved by reverting the tag
file: five explicit failures, one per block.

The removal path is **exactly one**, not zero and not two. Every route into it
converges on `Block#onRemove`:

- pickaxe mining → loot table → `onRemove`
- Creative removal → `onRemove`
- Create Wrench, sneaking → Create's own inherited `onSneakWrenched`, which
  calls `Block.getDrops` (the loot table, with the block entity, so
  `copy_components` carries wear) and then `level.destroyBlock(pos, false)`,
  which runs `onRemove`

No custom Wrench item was created. The Crankshaft and Flywheel were already
wrenchable through Create's `IRotate extends IWrenchable`; the Cylinder,
Carburetor and Oil Sump now implement `IWrenchable` with
`onWrenched` returning `PASS`, so a non-sneak wrench does nothing to them
(they have no rotation to cycle) while sneak-wrench dismantling works through
Create's inherited path. On 1.21.1 `onWrenched` returns `InteractionResult`,
not `ItemInteractionResult`; getting that wrong is what failed CI run 23.

Wear survives every one of those paths, because it travels in the item's
components via the loot table rather than being re-derived. Cylinder contents
are preserved the same way. Dismantling a *running* engine leaves no ghost
capacity, no orphaned controller and no phantom cylinders, because the
controller migration runs on the tick the section index changes and the mask is
recomputed from the structure rather than remembered.

---

## 15. Ponder

All nine scenes were audited against a rule that is now enforced by the build:
**for every text step, the highlight must point at the object the sentence is
teaching**. Broad boxes covering half the machine because they were convenient
are gone.

`tools/validate_ux.py` gained `check_ponder_highlights_teach_their_sentence`,
which parses the nouns in each text step and checks the highlight geometry
against them, understanding that a `fromTo` spans everything between its
endpoints. Two documented exemptions exist and are listed in the source:
"Active Cylinders" as a goggle readout and "Manual, Ignition, Throttle" as
control-module mode names, neither of which is a noun pointing at a block.

New content:

- **`the_four_stroke_cycle`**, a scene that walks intake, compression, power
  and exhaust on an inline-1 and then shows the flywheel consequence. Written
  for a player who has never taken an engine apart. It is the scene the sneak
  goggle readout's vocabulary comes from.
- Camshaft installation, in the assembly scene, shown as required before the
  engine will start.

---

## 16. Advancements, and one that had to change

"Some Assembly Required" fired on the structure becoming *valid*. It now fires
on the structure becoming **complete**, which since this milestone includes the
Camshaft. The old behaviour would have congratulated the player on their engine
at exactly the moment they were about to spend twenty minutes cranking a machine
that was never going to catch.

The starting advancement still requires a genuine STARTING to RUNNING
transition, which is now a stricter event than it was, per section 6.

---

## 17. Test coverage

19 suites, all passing. The five that are new or substantially rewritten here:

| Suite | Checks | What it pins |
| --- | --- | --- |
| `ProductionFourStrokeTests` | 102 | the production primitives directly: cycle arithmetic, phase boundaries, firing orders, arming, valve and cam timing, schema migration |
| `ProductionEngineFourStrokeTests` | 89 | the shipped `EngineState`: firing schedules, fuel, equilibria, missing Camshaft, rocking, reverse, overspeed, mask stability, reload on each stroke, client phase correction |
| `FourStrokeBalanceTests` | 157 | prototype and production agree within 3 % at 24 operating points; fuel identical at 12; the recorded 15A ripple figures reproduce exactly |
| `InstalledComponentConservationTests` | 68 | Camshaft and Control Module are conserved across every rebuild path |
| `EngineMigrationTests` | 57 | version-1 saves, including that nothing is armed and nothing is free |

Rigs that had been calibrated against the 360-degree engine were fixed rather
than loosened: `EngineCoastDownTests` cranked a fixed 60 ticks and never
started a four-stroke at all; instantaneous RPM samples were replaced with means
because a 15 RPM four-stroke ripple is not a regression; `EngineWearTests` now
motors the worn engines that a hand crank can no longer start.

Three assertions in the new suites were wrong when first written, and in all
three cases the assertion was fixed rather than the code:
`crossedForward(160, 700, 180)` starts exactly on the target and correctly
returns false; sealed-by-valve and sealed-by-stroke legitimately differ at the
four dead centres; and a cam seat's slope was being compared against the curve's
peak, where the slope is zero, instead of its steepest point.

`FourStrokeRig.FOUR_STROKE_TORQUE_SCALE` was removed. Once production carried
the correct duty itself, the rig's 2.0x correction double-counted and every
engine settled 11 % high. It is now derived, `EngineTuning.POWER_STROKE_DUTY /
FourStrokeCycle.POWER_STROKE_DUTY`, so it cannot drift out of step again.

### Validation that is now part of the build

`tools/validate_ux.py` fails the build if a registered block lacks a mineable
tag, a loot table, a tier or a name; if a registered item lacks a name or a
model in either language; if a Ponder highlight outlives its text step; or if a
highlight does not point at what its sentence teaches. Those are the four ways
a block has silently shipped broken in this repository before.

---

## 18. What has not been verified

No Minecraft client or server was launched in the environment that produced this
branch. Gradle cannot resolve the ModDevGradle plugin offline here, so the real
NeoForge and Create compilation runs in GitHub CI, and the simulation suites run
against a plain `javac` mirror of the `simulationTest` source set.

That means the following are **unverified** and are what the manual acceptance
checklist is for: Ponder scene appearance and timing, model and texture
appearance in-world, Wrench behaviour against the real Create 6.0.10 item,
animation smoothness, multiplayer client sync, and engine sound.
