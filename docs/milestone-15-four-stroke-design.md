# Milestone 15A — Four-stroke architecture and pure prototype

**Status: design and prototype only. Nothing here is wired into the playable
engine.** This document plus `src/prototype/java` is the whole deliverable;
Milestone 15B is the implementation, and this is written to be executable from
cold by someone who has never seen the conversation that produced it.

The engine the mod ships today fires **once per cylinder per 360°**. A real
four-stroke fires **once per cylinder per 720°**:

```
INTAKE  ->  COMPRESSION  ->  POWER  ->  EXHAUST  ->  (repeat)
   180         180           180        180          = 720 crank degrees
```

Halving the firing rate is easy. Doing it without halving the engine's power,
without letting a rocked crank print free combustion, without making an idling
inline-1 flicker in and out of Create's capacity, and without breaking a saved
world, is the actual milestone. Each of those is answered below with the
arithmetic, and where the answer could be argued about the prototype is driven
and the number measured rather than asserted.

---

## 1. What the engine actually does today

Read from source at commit `776e275`, not from memory. Every claim in this
section is a file and a line.

| Fact | Where | Value |
| --- | --- | --- |
| One authoritative crank angle | `EngineState.crankAngleDegrees` | `[0, 360)` |
| Piston position | `CrankMath.pistonPosition` | `0.5 - 0.5·cos θ` |
| **θ = 0 is BDC, θ = 180 is TDC** | same | *not* the automotive convention |
| Firing angle | `EngineTuning.FIRING_ANGLE_DEGREES` | `180` (TDC) |
| Power stroke | `EngineTuning.POWER_STROKE_DEGREES` | `180`, i.e. `[180, 360)` |
| Duty | `EngineTuning.POWER_STROKE_DUTY` | `180/360 = 0.5` |
| Cylinder phase | `EngineTuning.cylinderPhaseOffsetDegrees` | `i · 360 / n` |
| Local angle | `EngineState.localCrankAngleDegrees` | `normalize(master + offset)` |
| Crossing test | `EngineState.crossedFiringAngle` | forward-only, modulo 360 |
| Compression | `EngineTuning.compressionTorqueAt` | `-P·sin θ·(1-cos θ)/2`, every 360° |
| Peak torque solve | `EngineTuning.peakCombustionTorqueFor` | `friction(target) / (DUTY · 0.5)` |
| Per cylinder | `peakCombustionTorqueFor(target, n)` | `peak / n` |
| Fuel | `EngineTuning.FUEL_PER_COMBUSTION_MB` | `1 mB` per firing event |
| Active-cylinder age | `generationCombustionAllowanceTicks` | `2.5 revolutions + 2`, capped at 60 |
| Cylinder numbering | `EngineComponents` | index 0 = negative-most section = controller |
| Saved angle | `CrankshaftBlockEntity.KEY_CRANK_ANGLE` | the 360° crank angle |

### The 360° assumptions, itemised

These are the things that are *only* true for a one-power-event-per-revolution
engine. Milestone 15B is exactly the job of finding each one and correcting it.

1. **`crankAngleDegrees` is the whole state.** A cylinder's situation is fully
   determined by the crank angle. Under four-stroke it is not: the same angle is
   two different strokes.
2. **`crossedFiringAngle` fires on every forward crossing.** Nothing else gates
   it, so a cylinder may fire once per revolution and there is no concept of a
   charge having been *drawn*.
3. **`cylinderPhaseOffsetDegrees` is one number doing two jobs** — crank throw
   *and* ignition timing. `EngineState.localCrankAngleDegrees` (combustion) and
   `getLocalRenderCrankAngleDegrees` (the renderers) both call it.
4. **The compression waveform runs every revolution**, so both of a
   four-stroke's TDCs would be treated as compressions.
5. **`POWER_STROKE_DUTY = 0.5`** is baked into the equilibrium solve.
6. **Fuel is 1 mB per event and events are per-revolution**, so fuel economy is
   pinned to the firing rate.
7. **The generation allowance is measured in revolutions** — correct only while
   a cylinder fires once per revolution.
8. **The saved crank angle is 360°-wide**, so it cannot distinguish the strokes.

### One current behaviour worth knowing about

`localCrankAngleDegrees` computes `master + offset`, and the firing test is a
crossing of a *fixed* angle. With ascending offsets, that makes a cylinder with
a **larger** offset fire **earlier**. For an inline-3 with offsets 0/120/240 the
cylinders reach 180 at master 180, 60 and 300 respectively — so **today's
inline-3 fires 1‑3‑2**, not 1‑2‑3. Even cylinder counts hide it because ±180 and
±90 are symmetric. This is not a bug in a two-stroke-like engine, where firing
order is not a stated feature, but the sign has to be handled deliberately the
moment a firing order becomes a claim. See §7.

---

## 2. The authoritative representation

> **`cycleAngleDegrees` ∈ [0, 720)` replaces `crankAngleDegrees ∈ [0, 360)` as
> the engine's single authoritative angle, and the physical crank angle becomes a
> derived value: `physicalAngle = cycleAngle % 360`.**

One number, widened. Not two numbers kept in step — that is the arrangement that
comes back from a reload disagreeing, and this codebase has already been bitten
by exactly that with `publishedRpm` (see `EngineState.restoreAfterLoad`).

| | Physical crank angle | Engine cycle angle |
| --- | --- | --- |
| Range | `[0, 360)` | `[0, 720)` |
| Period | one revolution | two revolutions |
| Answers | where the piston, rod and crank pin are | which stroke, which valve, when to fire |
| Derived from | `cycleAngle % 360` | authoritative |
| Used by | every renderer, `CrankMath` | ignition, compression, valves, camshaft |

A piston at TDC is at cycle angle **180 or 540** and no amount of looking at the
piston distinguishes them. That single sentence is why the widening is necessary.

---

## 3. Stroke boundaries — and why the cycle starts on compression

The obvious convention puts INTAKE at 0. **This design does not**, and the reason
is worth stating carefully because it saves the entire retune.

`CrankMath.pistonPosition` is `0.5 - 0.5·cos θ`, so the crank angle already on
disk, in the models and in the renderers has **BDC at 0 and TDC at 180**.
Anchoring the cycle to that angle rather than to a textbook diagram gives:

| Cycle angle | Stroke | Piston | Physical angle |
| --- | --- | --- | --- |
| `[0, 180)` | **COMPRESSION** | BDC → TDC, rising | `0 → 180` |
| `[180, 360)` | **POWER** | TDC → BDC, falling | `180 → 360` |
| `[360, 540)` | **EXHAUST** | BDC → TDC, rising | `0 → 180` |
| `[540, 720)` | **INTAKE** | TDC → BDC, falling | `180 → 360` |

```
cycle    0         180         360         540         720
         |----------|-----------|-----------|-----------|
stroke   COMPRESSION   POWER      EXHAUST      INTAKE
piston   BDC ---> TDC ---> BDC ---> TDC ---> BDC
                   ^                  ^
                   |                  |
              FIRE HERE          not here!
           (compression TDC)    (exhaust TDC)
physical 0        180         0         180         0
```

`COMPRESSION → POWER → EXHAUST → INTAKE → COMPRESSION` is the same loop every
textbook draws, entered at a different point. Choosing that point costs nothing
and buys everything:

* `physicalAngle == cycleAngle % 360` **exactly** — the renderers need no new
  arithmetic at all;
* compression TDC stays at **180**, so `FIRING_ANGLE_DEGREES` is unchanged;
* the power stroke stays `[180, 360)`, so `POWER_STROKE_DEGREES` is unchanged;
* the existing compression waveform, written for a crank with BDC at 0, transfers
  **verbatim** onto `[0, 360)` of the cycle.

The only constant that changes is the *modulus*, and `POWER_STROKE_DUTY` — which
is precisely the change that fixes the power balance (§11).

Helpers, in `FourStrokePhase`: `at(cycleAngle)`, `strokeProgress(cycleAngle)`,
`pistonRising()`, `sealed()`, `pumping()`.

---

## 4. Cylinder numbering

> **Cylinder 1 = index 0 = the negative-most crankshaft section = the controller.**
> Unchanged from today. Migration cost: zero.

This is not a free choice. The alternative — "cylinder 1 is the flywheel end" —
**cannot be used**, because `EngineComponents.FlywheelPlacement` accepts a
Flywheel at *either* end of the run (`POSITIVE` or `NEGATIVE`). Numbering from
the flywheel would renumber every cylinder in an existing engine the moment a
player moved the flywheel to the other end, silently reassigning crank throws and
firing order under a running machine.

The negative-most section is already the controller, is resolved from block
states alone, is identical on client and server, and survives chunk reloads
(`EngineComponents`, class comment). It is the only stable answer available.

> **Consequence for players:** "Firing order 1‑3‑4‑2" means, physically, *the
> section furthest in the negative X/Z direction is cylinder 1*. The goggle
> overlay should say so, because the numbering is otherwise invisible.

---

## 5. Crank geometry and firing offsets for R1–R4

Two numbers per cylinder, and they are **not** interchangeable:

* **`ignitionOffsetDegrees(i)`** ∈ `[0, 720)` — crank travel from cylinder 1's
  ignition to cylinder *i*'s. Authoritative, human-readable; the firing order is
  this array sorted.
* **`geometricOffsetDegrees(i)`** ∈ `[0, 360)` — where the crank throw sits.
  **Derived, never authored**, as `cyclePhaseOffset(i) % 360` — the same `% 360`
  that turns a cycle angle into a physical angle. Storing it separately is
  exactly how the two drift apart.

The additive offset, keeping the production `master + offset` idiom, is
`cyclePhaseOffsetDegrees(i) = normalize720(-ignitionOffset(i))`. **The negation
is load-bearing** — see the inline-3 note in §1.

Generated from `FourStrokeFiringOrder`, not typed by hand:

| Config | Cyl | Ignition offset | Cycle phase offset | Crank throw | Firing order | Even? |
| --- | --- | --- | --- | --- | --- | --- |
| **R1** | 1 | 0 | 0 | 0 | `1` | yes (720) |
| **R2_EVEN** | 1 | 0 | 0 | 0 | `1‑2` | yes (360, 360) |
| | 2 | 360 | 360 | 0 | | |
| **R2_UNEVEN** | 1 | 0 | 0 | 0 | `1‑2` | no (180, 540) |
| | 2 | 180 | 540 | 180 | | |
| **R3** | 1 | 0 | 0 | 0 | `1‑2‑3` | yes (240 ×3) |
| | 2 | 240 | 480 | 120 | | |
| | 3 | 480 | 240 | 240 | | |
| **R4** | 1 | 0 | 0 | **0** | `1‑3‑4‑2` | yes (180 ×4) |
| | 2 | 540 | 180 | **180** | | |
| | 3 | 180 | 540 | **180** | | |
| | 4 | 360 | 360 | **0** | | |

Three things to notice:

* **R4's throws are 0 / 180 / 180 / 0** — cylinders 1 and 4 rise together against
  2 and 3. That is the flat-plane crank every inline-4 four-stroke has, and it is
  a **visible change** from today's 0/90/180/270 stagger. Cylinders 1 and 4 share
  a throw yet fire a full revolution apart: the single clearest demonstration
  that one number per cylinder cannot express this engine.
* **R3's throws are 0 / 120 / 240 — exactly the crank the mod already has.** An
  inline-3 looks identical after the switch; only its schedule spreads over two
  revolutions, and its order corrects itself from 1‑3‑2 to 1‑2‑3.
* **R2 has two legitimate cranks** and the choice is a gameplay decision, not a
  physics one. See §6 and §20.

### Why 1‑3‑4‑2 for the inline-4

Both 1‑3‑4‑2 and 1‑2‑4‑3 are even-fire on the same flat-plane crank, both are
used in real engines, and the two are mirror images with **identical torque
profiles** — there is no simulation difference whatsoever. 1‑3‑4‑2 is chosen
because it is what the large majority of real inline-4 road engines use, so it is
the order a player is most likely to recognise and the one any reference a player
checks will name first. It is a recognisability decision, and it is the only
basis on which it could be decided.

---

## 6. Torque: what is modelled and what is abstracted

Three effects, each nonzero on a different part of the cycle. That decomposition
*is* the content of "this is a four-stroke".

| Effect | Active on | Shape | Net over cycle |
| --- | --- | --- | --- |
| Gas spring (compression) | COMPRESSION + POWER | `-P·sin θ·(1-cos θ)/2` | **exactly 0** |
| Combustion | POWER, if lit | flat `+C` | `+C/4` |
| Pumping | INTAKE + EXHAUST | `-Q·sin² θ` | `-(Q_in + Q_ex)/8` |

**Modelled:** when each cylinder is sealed, when it is pushing gas, when it can
burn, and how the crank's leverage varies with angle.

**Abstracted:** everything thermodynamic. No temperature, no volumetric
efficiency, no gas dynamics, no valve lift curves, no ignition advance, no
knock. Deliberately — Milestone 15 changes *when* combustion happens, not what
one bang feels like.

### Compression

The production waveform, unchanged, **gated to the sealed half of the cycle**:

```java
gasSpringTorque(φ) = φ < 360 ? -P·sin(φ)·(1 - cos(φ))/2 : 0
```

Because the sealed half is `[0, 360)` and `physicalAngle = φ` there, this is
literally `EngineTuning.compressionTorqueAt` with a guard in front of it.

* Resists from compression BDC (0) up to compression TDC (180).
* Assists from 180 back down to 360 — the spring returning.
* **Exactly zero across EXHAUST and INTAKE**, so exhaust TDC is not treated as a
  second compression. This is §10 of the brief, and it is a one-line guard.
* **Still integrates to exactly zero over the cycle** (verified by test), so it
  remains a spring and not a second friction, and switching to four-stroke
  compression moves no equilibrium speed.

What *does* change is the rhythm: a cylinder is fought once per two revolutions
instead of once per one. Motoring a dead four-stroke feels different from
motoring a two-stroke, and that difference is free.

`COMPRESSION_PEAK_TORQUE` needs no change — the peak resistance at compression
TDC is identical, only its frequency halves.

### Pumping

`-Q·sin² θ` on the two open-valve strokes. Piston speed goes as `sin θ` and the
crank's leverage goes as `sin θ`, so a resistance proportional to speed reaches
the crank as `sin²`. Crude on purpose.

**Unlike the gas spring this does not integrate to zero**, so switching it on
lowers the speed the engine settles at. Its mean over the cycle is
`-(Q_intake + Q_exhaust)/8` (measured: −0.25 at unit peaks). If it is enabled,
the equilibrium solve must absorb it:

```java
peak = (frictionTorqueAt(target) + (Q_intake + Q_exhaust)/8) / (DUTY · 0.5)
```

> **Recommendation: ship 15B with `Q_intake = Q_exhaust = 0`.** The waveform and
> the correction term are in the prototype so that enabling pumping later is a
> two-constant change with a known formula, not a re-derivation. Adding it in the
> same step as the timing change would mean two things moving the equilibrium at
> once, and only one of them has an exact answer.

---

## 7. Ignition: the event, and why it cannot be duplicated

### The crossing

A tick advances the crank by a finite jump — about 62° at the engine's own
ceiling, ~77° under an external Create network at the default `maxRotationSpeed`
— so equality tests are useless. `FourStrokeCycle.crossedForward` is structurally
`EngineState.crossedFiringAngle`, generalised to an arbitrary target and to the
720° modulus:

```java
if (delta <= 0)   return false;          // backwards never fires
if (delta >= 720) return true;           // a step past the whole cycle passes everything
float past = normalize720(angleAfter - delta - target);
return past + delta >= 720;
```

Half-open: arriving exactly on the target counts, and the next step does not
count it again. Verified at step sizes from 0.5° to 359°.

### The latch — and the hole the prototype's own test found

A crossing test **alone is not enough**, and this is the most important result in
the milestone. With forward-crossing as the only rule, `+1°, −1°, +1°, −1°`
across the ignition point is an infinite, fuel-free bang generator.

The fix is a physical statement, not a guard: **you cannot burn a charge you have
not drawn in.**

* A cylinder **arms** when it forward-crosses `ARMING_ANGLE = 540` — exhaust TDC,
  the start of the intake stroke.
* Ignition requires `armed`, and consumes it.
* A misfire (no spark, no fuel) also consumes it: the exhaust stroke throws the
  charge out, so a misfire costs a whole cycle, as on a real engine.

That was the prototype's first design, **and its own test proved it insufficient**:

```
arm at 540 (forward)          armed = true
wind back to 179              crosses 180 BACKWARDS — no fire, still armed
nudge forward to 181          forward-crosses 180 while armed -> FIRES
                              net travel per bang: 2 degrees
```

The correction, and the rule 15B must implement:

> **Any backward crossing of the arming angle or the ignition angle clears the
> latch.**

`FourStrokeCycle.crossedBackward` is the mirror of the forward test, with the
mirrored half-open convention so the two can never both fire on one step.

### What that guarantees

With the backward rule in place the cost of one bang is provable:

* **fire → arm** — must forward-cross 540. From 180 that is 360° forward, or 361°
  back and 1° forward: **360° of path either way**.
* **arm → fire** — must forward-cross 180 without backward-crossing 540, which
  confines it to a 360° forward run: **360° of path**.

> **Minimum 720° of crank *path length* per ignition, however the crank is
> shaken.** Note the invariant is on path length — how far the crank is actually
> turned, both directions added — and *not* on net displacement. A crank wound a
> long way backwards and then run forwards through a whole cycle lower down has
> genuinely inhaled, compressed and fired; that bang is real and may land at
> almost the same absolute position as the last one. What must be impossible is
> getting a bang *cheaply*, and the cost that matters is the crank movement the
> player has to supply.

Rocking therefore costs **740° of turning per bang against 720° for simply
running the engine forwards** — strictly worse, which is what makes the exploit
not worth having. Verified over 200 000 random steps of ±30°: 462 firing
intervals, cheapest 3103° of path.

---

## 8. Reverse and external rotation

The engine can be driven by any Create source, in either direction, and stopped
anywhere.

| Situation | Behaviour |
| --- | --- |
| Forward, running | normal: arm at 540, fire at 180, once per 720° |
| Stopped | angle held; nothing arms, nothing fires |
| Reversed | no ignition (`delta <= 0`); latch cleared on crossing 180 or 540 |
| Oscillating about TDC | latch cleared by the backward crossing; no repeat fire |
| Oscillating about 540 | re-arms idempotently; still needs 360° forward to fire |
| Externally motored forwards, unfuelled | arms and misfires; **no capacity** — unchanged rule |

The production `turningForwards` gate (`lastAngleDeltaDegrees > 0 && simulatedRpm
>= requiredRpm`) stays exactly as it is and stacks on top of all of this.

### Event identity

`FourStrokeCylinderTiming.completedCycles` is a **signed** counter: it increments
on a forward wrap through 720 and **decrements** on a backward wrap, so an engine
rocked across the wrap does not accumulate phantom cycles. It is inferred from
the angle rather than accumulated from deltas, so it follows the authoritative
angle instead of drifting alongside it.

It is for diagnostics and event identity. **The latch, not the counter, is what
enforces correctness** — a counter alone does not stop the wind-back attack.

---

## 9. Starting

### What breaks under four-stroke

`EnginePhase`'s state machine (STOPPED → CRANKING → STARTING → RUNNING →
COASTING/STALLED) needs **no structural change**. Two of its assumptions do:

1. **`MIN_START_CYCLES = 2`, `MAX_START_CYCLES = 5` count firing events.** Under
   four-stroke each event costs 720° instead of 360°, so a start attempt that
   takes 2–5 revolutions today would take **4–10**. At a 32 RPM Hand Crank that
   is 7.5–19 s of cranking, against 3.75–9.4 s today.
   > **Recommendation: halve them to `MIN = 1, MAX = 3`.** That keeps wall-clock
   > start time roughly where it is while still visibly requiring several crank
   > movements. `START_ATTEMPT_TIMEOUT_TICKS = 30` must be re-checked against the
   > new interval: at 32 RPM one cycle is 75 ticks, so a 30-tick timeout would
   > expire *between* consecutive firing opportunities and abandon every attempt.
   > **It must rise to at least one firing interval plus margin — 2.5 cycles at
   > `START_RPM`, i.e. ~250 ticks.** This is the single most likely thing to be
   > missed in 15B, and it makes the engine unstartable if it is.
2. **`coastDragTorque` is suppressed while STARTING** so the gaps between firing
   kicks do not smother the attempt. Those gaps double. The suppression already
   covers it, but the comment explaining *why* needs updating.

### The initial position problem

A cold cylinder is **disarmed**: it must reach 540 and then 180. From cycle angle
0 that is **900° of cranking to the first bang** — measured, not estimated. The
worst case is stopping *just past* the arming angle, at 541: 719° to get back
round to 540, then 360° to fire, so **1079°** — three full revolutions of Hand
Crank before anything happens at all, with no feedback. That is not fun.

Two options were considered:

* **A. Preserve the exact stopped cycle angle.** Authentic; worst case 1080° of
  silent cranking.
* **B. Abstract the start away.** Fun; destroys the point of the milestone.

> **Recommendation — neither, but a third that is both:** preserve the exact
> cycle angle on disk, and **seed `armed = true` for every cylinder of an engine
> that has come to rest**. Physically honest (a stopped engine has been sitting
> with mixture in the bore) and it bounds the first bang at **≤ 360° of
> cranking** while every subsequent one costs the full 720°.

The *visible* "several cranks to start" then comes from `MIN/MAX_START_CYCLES`,
where it already lives and where it is tunable, instead of from an invisible
phase accident the player cannot see or learn.

Implementation: set the armed bits in `EngineState.stop()`.

---

## 10. Fuel and average power — the balance question

Event rate halves for **every** configuration, uniformly:

| RPM | Cyl | Events/s today | Events/s four-stroke |
| --- | --- | --- | --- |
| 64 (idle) | 1 | 1.07 | 0.53 |
| 64 | 4 | 4.27 | 2.13 |
| 192 (full) | 1 | 3.20 | 1.60 |
| 192 | 4 | 12.80 | 6.40 |

Because it is uniform, the **relative R1–R4 balance is preserved automatically**.
Only the absolute level needs answering.

### Three strategies

**A. Double the energy per combustion event.**
`peakCombustionTorqueFor` already *solves* for the peak:

```
peak · POWER_STROKE_DUTY · governorFactor = friction(target)
peak · DUTY · 0.5                          = friction(target)
```

`POWER_STROKE_DUTY` is *defined* as "fraction of the period during which
combustion pushes". Four-stroke changes it from `180/360 = 0.5` to
`180/720 = 0.25`. Substituting **doubles the peak automatically**, exactly
cancelling the halved event rate. Average torque is preserved *analytically*, and
not one number is hand-tuned.

**B. Change fuel per event.** Independent of A, and needed on top of it.

**C. Retune combustion and capacity together.** **Rejected as unnecessary.**
Create capacity is `STRESS_CAPACITY_PER_RPM · rpm · capacityFactor` and contains
no firing-rate term at all, so it needs no compensation. Only the active-cylinder
*age* rule does (§11), and that is a correctness fix, not a retune.

### Recommendation: A + B, and it is a two-line change

```java
// EngineTuning
public static final float POWER_STROKE_DUTY = POWER_STROKE_DEGREES / 720.0F;  // was / 360
public static final int   FUEL_PER_COMBUSTION_MB = 2;                          // was 1
```

Fuel check — the doc comment on `FUEL_PER_COMBUSTION_MB` claims 1000 mB lasts
~15.6 min at idle on a single:

```
today:        1.07 events/s × 1 mB = 1.07 mB/s   ->  1000 mB = 15.6 min
four-stroke:  0.53 events/s × 2 mB = 1.07 mB/s   ->  1000 mB = 15.6 min   identical
```

Integer, so no rounding drift, and mB-per-joule is constant because the torque
per event doubled too. **Every documented figure in `EngineTuning` stays true.**

---

## 11. Create stress capacity and `activeCylinderMask`

### The architecture is already right

The separation §17 of the brief asks for **already exists** and needs no change:

| Quantity | Where | Nature |
| --- | --- | --- |
| Instantaneous torque | `integrate()` | per-tick, rippling |
| Simulated RPM | `simulatedRpm` | ripples with combustion |
| **Published speed** | `outputRpm` | low-pass, `OUTPUT_FILTER_ALPHA = 1/32` |
| **Published capacity** | `publishedCapacityFactor` | from the *mask*, never from torque |

`publishedCapacityFactor` is derived from `activeCylinderMask` and wear — never
from instantaneous torque — so it cannot fall to zero between power strokes. The
invariant *externally rotated non-combusting engine = zero capacity* is enforced
by `evaluateActiveGeneration()` and is untouched.

One thing to re-check: **R1's speed ripple roughly doubles** (one impulse per 720°
instead of per 360°). `OUTPUT_FILTER_ALPHA = 1/32` and
`OUTPUT_FILTER_SNAP_RPM = 12` should be measured against an R1 at idle after the
change; if the filter lets the ripple through, alpha is the knob, and nothing
downstream needs touching.

### `activeCylinderMask` — the one semantic change

**Its meaning must NOT change.** It means *this cylinder has genuinely been
contributing combustion recently enough to count*, not "is currently on its power
stroke". Redefining it would make an R1's capacity blink on and off four times a
cycle.

What must change is the **unit of the allowance**. Today:

```java
allowance = min(round(2.5 × ticksPerRevolution) + 2, 60)
```

`2.5` means *"tolerate one missed firing plus margin"*, and it means that only
because a cylinder fires once per revolution. Under four-stroke a cylinder fires
once per **cycle**, and the constant silently changes meaning.

> **Fix: express the allowance in firing intervals, not revolutions.**
> ```java
> ticksPerFiringInterval = 2400 / rpm;               // 720 deg at this speed
> allowance = round(2.5 × ticksPerFiringInterval) + 2;
> ```
> The constant `2.5` then keeps its meaning for any stroke count, by construction.

**The hard 60-tick ceiling must go, or rise a long way.** Measured:

| RPM | Firing interval (ticks) | Today's rule | Proposed |
| --- | --- | --- | --- |
| 10 (`STALL_RPM`) | 240 | 60 ← **flickers** | 602 |
| 16 | 150 | 60 ← **flickers** | 377 |
| 24 (`START_RPM`) | 100 | 60 ← **flickers** | 252 |
| 32 (hand crank) | 75 | 60 ← **flickers** | 190 |
| 48 | 50 | 60 | 127 |
| 64 (idle) | 37.5 | 49 | 96 |
| 192 (full) | 12.5 | 18 | 33 |

Below ~48 RPM the current ceiling is **shorter than the interval**, so a perfectly
healthy cylinder would drop out of the mask between its own firings — exactly the
`1 active / 0 active / 1 active` flicker §18 warns about. That range is reachable:
a RUNNING engine sags well below idle under load before it stalls at 10 RPM.

> **Recommendation: drop the ceiling.** It exists to stop a crawling engine
> claiming generation for ever, and `evaluateActiveGeneration()` already refuses
> below `STALL_RPM` — the ceiling is redundant behind a gate that does the job
> properly. If a belt-and-braces cap is wanted, derive it from `STALL_RPM`
> (`2.5 × 2400/10 = 600`) rather than leaving a magic 60.

---

## 12. Sound

The architecture needs **no change**, which is the point of it having been built
event-driven. `CombustionAudio` fires one positional pulse per charge that
actually burned, from the authoritative combustion counter; `EngineSoundManager`
cross-fades a mechanical loop under it. Nothing fakes a firing rate from RPM.

What changes is what the player hears, for free:

* **R1** — half as many pulses per revolution; a distinct off-beat thump against
  a mechanical loop that still follows crank speed. The single biggest audible
  win in the milestone.
* **R4** — four pulses per two revolutions, evenly spaced; smoother, and still
  half today's rate.
* `EngineCombustionEventsPayload` needs **no change**: one bit per cylinder per
  tick is still lossless, and four-stroke *halves* the event rate, so its
  headroom argument (which already reasons in 720° terms) only improves.

Two things to re-check rather than assume:

* `SOUND_COMBUSTION_PULSE_MAX_RATE_HZ` and `combustionLoopBlend` are thresholds
  on measured event rate. The rate halves, so the continuous layer fades in even
  later than it does now — harmless, but the doc comments quoting Hz figures
  become wrong and should be corrected.
* `CombustionAudio.eventRateHz` smoothing is tuned for the current interval; at
  0.53 Hz (R1 at idle) the smoothing time constant should be re-measured.

**Future recorded audio.** The architecture is already the right shape for it: a
real engine recording is a sequence of discrete combustion events, and this
engine emits discrete combustion events with correct four-stroke spacing. A
future asset set would replace `ENGINE_FIRE` with per-configuration samples
triggered by the same counter — no new clock, no loop pitching. Recording an R1
and an R4 separately would then be *correct* rather than a cheat, because their
event spacings genuinely differ.

---

## 13. Animation, camshaft and valves

> **The split:** anything mechanical reads `physicalAngle = cycleAngle % 360`.
> Anything about strokes, valves or ignition reads `cycleAngle`.

| Consumer | Reads | Change |
| --- | --- | --- |
| `CrankshaftRenderer` (crank pin) | physical angle + geometric offset | none in kind |
| `CylinderRenderer` (piston, rod) | physical angle + geometric offset | none in kind |
| `EngineFlywheelRenderer` | physical angle | none |
| `CrankMath` (all three functions) | physical angle | **none at all** |
| Combustion flash | combustion event id | none |
| *Future* camshaft | cycle angle | new |
| *Future* valves | cycle angle | new |

The renderers keep calling one method for their cylinder's angle; that method's
*offset* becomes `geometricOffsetDegrees` and its *base* becomes
`cycleAngle % 360`. The visible change is that R4's throws move from 0/90/180/270
to 0/180/180/0 — pistons now move in pairs, which is what an inline-4 does.

### Future camshaft (do not implement now)

A camshaft turns at **half crank speed**: one camshaft revolution per 720° cycle.
So:

```java
camshaftAngle = cycleAngle / 2          // [0, 360)
```

That is the entire integration point. It is a *pure function of the existing
authoritative angle* — **no second clock**, nothing to synchronise, nothing that
can drift. A `CamshaftBlockEntity` would read the controller's cycle angle and
render; it would own no state.

### Future valves (do not implement now)

| Valve | Open roughly over | Cycle angle |
| --- | --- | --- |
| Intake | INTAKE | `[540, 720)` |
| Exhaust | EXHAUST | `[360, 540)` |

Overlap around 540 (exhaust TDC) is where real engines put it and is expressible
by widening the windows; nothing in this design forbids it. **No lift curves are
required** — a boolean per valve is enough for a first implementation, and
`FourStrokePhase.pumping()` already answers it. The architecture must simply not
make lift curves impossible later, and a function of cycle angle never can.

---

## 14. Save and reload

### What must be authoritative on disk

| Key | Today | 15B |
| --- | --- | --- |
| `CrankAngle` | `[0, 360)` | **replaced** by `CycleAngle` ∈ `[0, 720)` |
| *(new)* `ArmedMask` | — | one bit per cylinder |
| everything else | unchanged | unchanged |

**Two fields, one of them replacing an existing one.** The physical angle is
`cycleAngle % 360` and must **not** also be stored — that is one fact on disk
twice, and this codebase has already been burned by it (`restoreAfterLoad`
reconstructs `publishedRpm` rather than restoring it, for exactly this reason).
Per-cylinder cycle angles must **not** be stored either: they are derived from
the master angle every tick anyway.

`ArmedMask` genuinely is per-cylinder physical state — one engine can hold a
charge in cylinder 2 and nothing in cylinder 3 — and is not derivable. Without
it, a reload silently grants or destroys a charge.

Verified: save at cycle angle **473** (deep in exhaust), reload, and the engine
comes back at 473 on EXHAUST — not at 113 on COMPRESSION, which is what a 360°
save cannot distinguish — and fires identically for two whole cycles afterwards.

### Migrating existing worlds

An old save has a 360° angle and no armed mask. Reading it as a cycle angle puts
every engine in the first half of its cycle (COMPRESSION or POWER) with no
charge inducted.

> **Recommendation:** on reading a tag with no `CycleAngle`, take
> `cycleAngle = CrankAngle` and set `ArmedMask = all` (the §9 rest rule). The
> engine restarts within a revolution and no player sees anything but a normal
> start. There is no correct answer here — the information does not exist in the
> old save — so the goal is simply that no engine comes back unstartable.

---

## 15. Controller migration and engine expansion

R1 → R2 → R3 → R4 while the engine exists.

**The key insight: a real engine has a different crankshaft for each cylinder
count.** Going R3 → R4 does not add a throw to the existing crank; it is a
different part. So:

> **On a layout change, keep the master `cycleAngle` and recompute every
> cylinder's offsets from the new `FourStrokeFiringOrder`. Never try to preserve
> per-cylinder offsets.**

Because offsets are a pure function of `(index, configuration)` and both are
resolved from the world by `EngineComponents`, this is automatic — there is
nothing to migrate. Cylinder 2's throw genuinely moves from 240° (R3) to 180°
(R4), and that is correct.

| Change | Master cycle angle | Offsets | Armed bits |
| --- | --- | --- | --- |
| Add a cylinder | **preserved** | recomputed | new cylinder disarmed; existing kept |
| Remove a cylinder | **preserved** | recomputed | truncated |
| Controller moves (section added at the negative end) | **preserved**, transferred | recomputed | transferred by index |

The last row is the one to be careful about. A player placing a crankcase at the
negative end makes a *new* section the controller, and the old controller becomes
index 1. The existing code already transfers simulation state on controller
change; 15B must add the cycle angle and armed mask to whatever it transfers.
**Not transferring the cycle angle would reset every engine's phase whenever it
grew at the negative end** — the failure §24 of the brief asks about.

New cylinders start **disarmed**, so an added cylinder inhales before it fires.
Correct, and free.

---

## 16. Impact map

`UNCHANGED` / `MODIFIED` / `REPLACED` / `NEW`. Nothing in this list was edited in
Milestone 15A.

### Simulation core

| Item | Verdict | Note |
| --- | --- | --- |
| `EngineState.crankAngleDegrees` | **REPLACED** | becomes `cycleAngleDegrees ∈ [0, 720)` |
| `EngineState.getCrankAngleDegrees()` | **MODIFIED** | returns `cycleAngle % 360`; callers unchanged |
| `EngineState.getCycleAngleDegrees()` | **NEW** | the authoritative angle |
| `EngineState.localCrankAngleDegrees` | **MODIFIED** | uses `geometricOffsetDegrees` |
| `EngineState.localCycleAngleDegrees` | **NEW** | uses `cyclePhaseOffsetDegrees` |
| `EngineState.crossedFiringAngle` | **REPLACED** | 720° crossing + arm latch + backward clear |
| `EngineState.isWithinPowerStroke` | **MODIFIED** | window becomes cycle `[180, 360)` |
| `EngineState.compressionTorqueSum` | **MODIFIED** | gated to the sealed half |
| `EngineState.armed[]` | **NEW** | per-cylinder latch |
| `EngineState.deriveActiveCylinderMask` | **MODIFIED** | allowance in firing intervals |
| `EngineState.advanceCrankAngle` | **MODIFIED** | normalises to 720 |
| `EngineState.restoreAfterLoad` | **UNCHANGED** | already re-derives everything derived |
| `EngineState.evaluateActiveGeneration` | **UNCHANGED** | conditions are stroke-agnostic |
| `EngineState.integrate` | **UNCHANGED** | torques are inputs |
| `EngineState.updateOutputFilter` | **UNCHANGED** | *re-measure* R1 ripple (§11) |
| `EngineState.stop()` | **MODIFIED** | seeds the armed bits (§9) |
| `EnginePhase` | **UNCHANGED** | states and transitions all survive |

### Tuning

| Item | Verdict | Note |
| --- | --- | --- |
| `POWER_STROKE_DUTY` | **MODIFIED** | `/ 720` — this is the power fix |
| `FUEL_PER_COMBUSTION_MB` | **MODIFIED** | `1 → 2` |
| `cylinderPhaseOffsetDegrees` | **REPLACED** | by the two-offset firing order table |
| `generationCombustionAllowanceTicks` | **MODIFIED** | firing intervals, ceiling dropped |
| `GENERATION_COMBUSTION_LIMIT_TICKS` | **REPLACED** | see §11 |
| `MIN/MAX_START_CYCLES` | **MODIFIED** | halve to 1/3 |
| `START_ATTEMPT_TIMEOUT_TICKS` | **MODIFIED** | **must rise to ≥ ~250** — §9 |
| `FIRING_ANGLE_DEGREES` | **UNCHANGED** | still 180 |
| `POWER_STROKE_DEGREES` | **UNCHANGED** | still 180 |
| `COMPRESSION_PEAK_TORQUE` | **UNCHANGED** | peak identical, frequency halved |
| `compressionTorqueAt` | **UNCHANGED** | reused verbatim, gated by the caller |
| `peakCombustionTorqueFor` | **UNCHANGED** | the duty change flows through it |
| `frictionTorqueAt`, governor, throttle | **UNCHANGED** | |
| `STRESS_CAPACITY_PER_RPM` | **UNCHANGED** | no firing-rate term |

### Block entities, network, client

| Item | Verdict | Note |
| --- | --- | --- |
| `CrankshaftBlockEntity` save/load | **MODIFIED** | `CycleAngle` + `ArmedMask`; legacy fallback |
| `CrankshaftBlockEntity` controller transfer | **MODIFIED** | carry cycle angle + armed mask |
| `CrankshaftBlockEntity` diagnostics | **MODIFIED** | show stroke and cycle angle |
| `EngineCombustionEventsPayload` | **UNCHANGED** | still lossless, more headroom |
| `EngineFlywheelBlockEntity` | **UNCHANGED** | reads capacity and speed |
| `CrankshaftRenderer`, `CylinderRenderer` | **UNCHANGED** in kind | offset source changes under them |
| `EngineFlywheelRenderer` | **UNCHANGED** | |
| `CrankMath` | **UNCHANGED** | all three functions still take a physical angle |
| `CombustionAudio`, `EngineSound*` | **UNCHANGED** | *re-measure* rate constants (§12) |
| `EngineComponents`, `EngineLayout` | **UNCHANGED** | numbering already correct |
| `EngineWear*`, `WearCondition` | **UNCHANGED** | wear is per event, events still exist |
| Ponder, advancements, models, textures | **UNCHANGED** | |

### Tests

| Item | Verdict |
| --- | --- |
| `MultiCylinderTests` | **MODIFIED** — asserts 360° phase offsets |
| `SparkPlugTests`, `LastPowerStrokeTests` | **MODIFIED** — assume per-revolution firing |
| `EngineCapacityTests` | **MODIFIED** — allowance arithmetic |
| `EngineReloadTests` | **MODIFIED** — cycle angle + armed mask |
| `EngineStabilityTests`, `EngineCoastDownTests`, `EngineWearTests` | **likely UNCHANGED** — verify |
| `EngineLayoutTests` | **UNCHANGED** |
| `FourStroke*Tests` | **NEW** — already present and passing |

---

## 17. Migration plan for Milestone 15B

Ordered so that **the engine is playable and green after every step**. Steps 1–3
change no behaviour at all, which is what makes the risky steps small.

| # | Step | Behaviour change | Guard |
| --- | --- | --- | --- |
| 1 | Promote the prototype into `content/engine` as production classes | none | `FourStroke*Tests` still pass |
| 2 | Widen the stored angle to `[0, 720)`; make `getCrankAngleDegrees()` return `% 360` | **none** — every consumer sees the same value | full suite green |
| 3 | Persist `CycleAngle` + `ArmedMask`, with legacy fallback | none | `EngineReloadTests` |
| 4 | Add the two-offset table; renderers take `geometricOffsetDegrees` | **R4 throws change to 0/180/180/0** | visual check |
| 5 | Replace the firing opportunity: 720° crossing + arm latch + backward clear | **firing rate halves** — engine now weak | expect failures; do not tune yet |
| 6 | `POWER_STROKE_DUTY / 720` and `FUEL_PER_COMBUSTION_MB = 2` | **power and fuel restored** | idle 64 RPM, full 192 RPM |
| 7 | Gate compression to the sealed half | smoother motoring | `EngineStabilityTests` |
| 8 | Allowance in firing intervals; drop the 60-tick ceiling | **fixes R1 capacity flicker** | `EngineCapacityTests` |
| 9 | Start cycles 1/3; **timeout ≥ 250 ticks**; arm on rest | starting feels right | hand-crank an R1 in game |
| 10 | Diagnostics: stroke name, cycle angle, firing order | new readouts | goggles |
| 11 | Re-measure `OUTPUT_FILTER_ALPHA` against R1 ripple; re-measure sound rate constants | polish | in game |
| 12 | Update the existing suites; delete the 360° assumptions | none | full suite green |

**Steps 5 and 6 must land together in one commit.** Between them the engine makes
half its power, and a build in that state is not merely untuned but unplayable.

---

## 18. The prototype

`src/prototype/java/dev/engineeredcombustion/prototype/fourstroke/`

| Class | Responsibility |
| --- | --- |
| `FourStrokePhase` | the four strokes, their boundaries, `at()`, `strokeProgress()` |
| `FourStrokeCycle` | 720° algebra, `crossedForward`/`crossedBackward`, the three torque waveforms |
| `FourStrokeFiringOrder` | R1/R2×2/R3/R4 — ignition offsets, derived geometry, firing order |
| `FourStrokeCylinderTiming` | one cylinder: local angles, the arm latch, signed cycle counter |
| `FourStrokeEngine` | a whole engine: master angle, per-cylinder stepping, net torque, mask, save/restore |

### Isolation is enforced by the build, not by a promise

`src/prototype/java` is added **only** to the `simulationTest` source set in
`build.gradle`. `sourceSets.main` never sees the directory, so the mod jar cannot
contain the prototype and no production class can compile against it. That source
set also has an **empty compile classpath**, so the prototype cannot reference
Minecraft, NeoForge or Create even by accident. Both properties are mechanical.

The prototype deliberately models *only* what has to change — timing and torque
shape. It has no inertia, friction, governor, phase machine, fuel tank or wear:
those exist, correct and tested, in `EngineState`, and 15B grafts this timing
onto them rather than growing a second engine.

---

## 19. Test results

`./gradlew simulationTest` — 11 suites, all passing.

```
PASS  EngineStabilityTests      PASS  EngineCapacityTests
PASS  EngineReloadTests         PASS  LastPowerStrokeTests
PASS  MultiCylinderTests        PASS  EngineCoastDownTests
PASS  SparkPlugTests            PASS  EngineWearTests
PASS  EngineLayoutTests
PASS  FourStrokeCycleTests          <- new
PASS  FourStrokeFiringOrderTests    <- new
```

Coverage against the milestone's checklist:

| | Requirement | Where |
| --- | --- | --- |
| A | cycle wraps exactly at 720 | `cycleWrapsAt720` |
| B | piston geometry repeats after 360 | `pistonGeometryRepeatsEvery360` |
| C | stroke does **not** repeat after 360 | `strokeDoesNotRepeatEvery360` |
| D | R1 fires once per 720 | `everyConfigurationFires…` |
| E | R4 fires four times per 720 | same |
| F | R4 order is 1‑3‑4‑2 | `r4FiresInTheChosenOrder` (declared *and* observed) |
| G | R4 events 180° apart | `r4EventsAre180Apart` |
| H | no cylinder fires twice in a cycle | `noCylinderFiresTwiceInOneCycle` (5 configs × 5 step sizes) |
| I | compression on the correct stroke only | `compressionActsOnOneStrokeOnly` |
| J | exhaust TDC ≠ compression TDC | `exhaustTdcIsNotCompressionTdc` |
| K | crossing survives a skipped timestep | `crossingSurvivesLargeTimesteps` (0.5°–359°) |
| L | reverse oscillation cannot duplicate events | `reverseOscillationCannotDuplicateEvents` |
| M | phase survives save/reload | `cyclePhaseSurvivesSaveAndReload` (the 473° case) |
| N | piston and firing phase independent | `pistonPhaseIsIndependentOfFiringPhase` |

Plus, beyond the checklist: the gas spring integrates to zero over the cycle;
pumping is never positive and its mean matches the formula in §6; the cold-start
distance is 900°; and the smoothness ladder is measured rather than assumed.

### Measured smoothness

Standard deviation of net crank torque over one cycle, all configurations at
**equal average power** (per-cylinder peak divided by cylinder count, as
production does), peak combustion 24, peak compression 6, no pumping:

| Config | Ripple | vs R1 |
| --- | --- | --- |
| R1 | **11.56** | — |
| R2_EVEN | 8.03 | 0.69× |
| R2_UNEVEN | 7.17 | 0.62× |
| R3 | 5.47 | 0.47× |
| R4 | **2.12** | **0.18×** |

**R4 is 5.5× smoother than R1, and nothing in the code says so.** There is no
`smoothnessMultiplier`; it falls out of adding impulses 180° apart instead of one
every 720°.

One result went against expectation and is reported as measured: **the *uneven*
twin has lower torque ripple than the even-fire one** (7.17 vs 8.03). Its opposed
throws make the two gas springs cancel, while the even-fire twin compresses both
cylinders simultaneously and gets one lump of twice the size. Even firing buys an
even *rhythm* — which is what the ear hears — not a smoother crank. See §20.

---

## 20. Decisions that need gameplay judgment

Everything above is settled by physics, by the existing source, or by a measured
number. These are not, and are deliberately left open.

1. **Which inline-2?** `R2_EVEN` (360° crank, both pistons together, even firing,
   even sound) or `R2_UNEVEN` (180° crank, alternating pistons as today, lumpy
   180/540 firing, *lower* measured ripple, classic parallel-twin character).
   The prototype implements both; `forCylinderCount` currently returns
   `R2_EVEN` for the even rhythm. **This is a character decision, and the
   measurement does not settle it.**
2. **Does R4's visible crank change need to be softened?** Pistons will move in
   pairs instead of in a 90° stagger. Mechanically correct, visibly different,
   and players with existing engines will notice.
3. **How hard should starting be?** §9 recommends arming on rest, capping the
   first bang at 360° of cranking. The authentic alternative — up to 1080° of
   silent cranking — is defensible and more demanding.
4. **`MIN/MAX_START_CYCLES = 1/3`?** Chosen to hold wall-clock start time
   constant. A four-stroke that is *meant* to be harder to start would keep 2/5.
5. **Enable pumping losses?** §6 recommends shipping with them at zero. Enabling
   them costs a re-derivation of the equilibrium (the formula is given) and buys
   a small extra realism in how a motored engine feels.
6. **Legacy save policy.** §14 recommends arming every cylinder of a
   pre-15B engine so nothing comes back unstartable. There is no recoverable
   truth here; the alternative is that some engines need an extra revolution.
