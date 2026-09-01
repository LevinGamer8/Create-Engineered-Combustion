# Milestone 15A.3 — Production migration, save compatibility and the 15B blueprint

**Status: design and prototype only. `src/main/` is untouched.** This is the last
architecture stage before Milestone 15B implements four-stroke for real.

Companions: `milestone-15-four-stroke-design.md` (the cycle) and
`milestone-15-valvetrain-design.md` (what the player builds). Everything frozen
there is taken as given.

The question this answers: **how do we convert a live playable engine and every
existing world into the four-stroke engine without corrupting a save, duplicating
power or fuel, losing a player's items, or leaving 15B to guess?**

---

## 1. The problem, stated exactly

A version-1 save records **one crank angle in `[0, 360)`**. That is the whole of
the phase information it contains, because the old engine had no more than that.

A four-stroke engine needs a position in `[0, 720)`. **One physical crank angle
corresponds to two cycle positions on two different strokes:**

```
   saved: crankAngleDegrees = 137
                 |
        ┌────────┴────────┐
   cycle 137            cycle 497
   COMPRESSION           EXHAUST
   rising, sealed        rising, valve open
   fires in 43 degrees   fires in 403 degrees
```

**This information cannot be recovered.** It was never written down. Any scheme
that claims to reconstruct it is inferring from transient fields that describe a
different physical model, and inference that is wrong half the time is worse than
a rule that is honest about being a choice.

So: make a deterministic choice, and make the choice **not matter** by refusing to
carry anything that could turn into free power.

---

## 2. What version 1 actually persists

Read from `CrankshaftBlockEntity` and `CylinderBlockEntity` at `f015e58`.

| Key | Meaning | Migration |
| --- | --- | --- |
| `CrankAngle` | `[0, 360)` — **the only phase information there is** | → `CycleAngle`, see §3 |
| `SimulatedRpm` | signed angular velocity — authoritative in both schemas | **carried untouched** |
| `Phase` | `EnginePhase` id | mapped, see §5 |
| `TicksSinceCombustion` | per cylinder, −1 for never | **discarded** (wrong unit now) |
| `Ignition`, `ManualIgnition`, `ControlModule`, `RedstoneSignal` | player controls | **carried untouched** |
| `StartProgress`, `StartRequired` | transient start attempt | **discarded** |
| `FuelAvailable`, `Lubrication`, `OilWear` | supply state | carried untouched |
| `SparkEvent`, `CombustionEvent` | client event counters | carried (monotonic, meaningless to migration) |
| `Generating`, `StructureValid` | re-derived on the first tick anyway | carried, immediately overwritten |
| `CylinderIndex`, `CylinderCount`, `SparkPlugMask`, `Oversized` | layout | carried untouched |
| `BearingWear`, `EngineBearingWear` | **physical wear** | **carried untouched** |
| `PistonInstalled`, `SparkPlugInstalled`, `PistonWear` (Cylinder) | **installed parts and their wear** | **carried untouched** |

Client-packet-only keys (`PublishedRpm`, `ActiveCylinders`, `CapacityFactor`) are
never on disk and so are not migration inputs at all.

> **Wear needs no migration, and that is by construction rather than by luck.**
> Wear lives on the *parts* — `PistonWear` on the Cylinder, `BearingWear` on the
> Crankshaft section — not in the engine's simulation state. The migration never
> touches those tags, so a worn inline-4 comes back exactly as worn as it was.

---

## 3. The chosen migration rule

> ```
> cycleIndex = 0
> cycleAngle = legacy CrankAngle          // the FIRST half: COMPRESSION / POWER
> armedMask  = 0                          // no charge in any cylinder
> lastFired  = NO_EVENT                   // no opportunity has been taken
> ```

### Why the first half (Option A)

| Option | Verdict |
| --- | --- |
| **A — `cycleAngle = oldAngle`** | **chosen** |
| B — `cycleAngle = oldAngle + 360` | rejected: the other half is no more correct, and it is *arbitrary* rather than merely a choice |
| C — infer the half from transient state | **rejected on principle**: `powerStrokeStrength`, combustion age and the active mask describe a model that is being deleted. Overfitting obsolete fields to guess unrecoverable information is fragile forever and wrong half the time |
| D — force a "safe" phase | rejected as unnecessary once the latch is empty — and it would *move* the piston, breaking priority 3 |

Three things recommend A:

1. **The physical angle is preserved bit-for-bit, for free.** `cycleAngle % 360`
   is the identity on `[0, 360)`. Verified across 2770 angles: worst error `0.0`.
2. **The half chosen is the mechanically matching one.** The old engine fired at
   physical 180 and ran its power stroke over `[180, 360)`. Under the frozen
   convention that *is* `COMPRESSION` then `POWER`. An engine saved at 270 comes
   back on POWER — where it actually was.
3. **It is the simplest rule that exists**, and a migration is a thing you get one
   chance to get right.

### Why the choice is safe regardless — §5's answer

The obvious objection to A: an engine parked at 179° sits **one degree before the
new ignition angle**, and a player nudging the crank would collect a combustion
event nobody paid for.

> **`armedMask = 0` is the whole answer, and it is frozen.** No cylinder may fire
> until it has forward-crossed the intake opening at 540 and then reached 180 —
> a full 720° of honest crank travel, exactly like every other cylinder.

Measured over **1440 rest positions** (four layouts × 360 angles): the cheapest
first bang after migration costs **361°** of crank; the worst 900°. The specific
trap case — R1 parked at 179° — needs **721°**, not one.

**Because safety comes from the latch and not from the angle, the choice of half
is free to be made on the grounds above.** That is the property that makes this
design robust rather than lucky.

---

## 4. The paid power stroke

> ### Discarded, not converted. (Option B.)

A version-1 engine may be saved mid-power-stroke with `powerStrokeStrength` set —
a charge already paid for at **1 mB per 360° event**. Version 2 charges **2 mB per
720° event**. Mapping one onto the other means inventing an exchange rate for
energy that has already been spent.

Losing at most one impulse — under a fifth of a second of torque, once, during a
version change — is strictly better than any rule that might create some. This is
migration priority 2 and it is not tradeable.

Verified: an engine saved at 200° comes back **on POWER, with nothing burning and
no combustion age**. Its first tick makes zero combustion torque. (Its *net*
torque is positive, and correctly so — past compression TDC the gas spring is
handing back what it took.)

---

## 5. Run state

| Version 1 phase | Version 2 phase | Momentum |
| --- | --- | --- |
| STOPPED | STOPPED | kept |
| CRANKING | STOPPED | kept |
| STARTING | STOPPED | kept — the attempt itself is discarded |
| **RUNNING** | **COASTING** | **kept** |
| COASTING | COASTING | kept |

**A previously RUNNING engine must not stay RUNNING.** Version 2 requires a
Camshaft; no version-1 world contains one; RUNNING is the only phase that may
generate. Leaving it RUNNING would be a claim that the engine is producing power
it has no valvetrain to produce.

> **COASTING is not a compromise — it is the exactly correct word.**
> `EnginePhase` already defines it as *"No more combustion, but the flywheel is
> still spinning down"*, which is precisely what a migrated engine is. It keeps
> the flywheel's speed so nothing snaps to a halt, generates nothing so there is
> no ghost capacity, and reaches STOPPED by itself through the ordinary spin-down
> the player can watch.

---

## 6. Camshafts in existing worlds

> ### Policy B: existing engines need a real, crafted Camshaft. No auto-grant, no legacy flag.

| Option | Verdict |
| --- | --- |
| A — auto-install a virtual Camshaft | **rejected** |
| **B — require the player to craft and install one** | **chosen** |
| C — legacy compatibility flag | **rejected** |

**Why not A.** A granted-but-not-real Camshaft creates a duplication surface
immediately: the player crafts one too and either the install is refused
(confusing) or they now have two (an item duplication bug in the migration path,
which is the worst place to have one). And a virtual part that cannot be removed
is a hidden special case that lives in the code forever.

**Why not C.** A legacy flag is A with extra steps, and it is exactly the
long-lived dual-engine path §35 of the brief forbids: a permanent branch
distinguishing "old engine" from "new engine" at runtime.

**Why B is acceptable**, with three mitigations that make it a service task rather
than a breakage:

1. **The engine is not broken — it is unstarted.** It stays *mechanically valid*:
   it turns, the pistons and rods animate, it resists compression, a Create
   network can motor it. It simply will not fire. That is what an engine missing a
   valvetrain does.
2. **The diagnosis is already there.** The goggle overlay lists installed
   components with `Installed`/`Missing` in green and red — the Spark Plug line
   works exactly this way today. `Camshaft: Missing` is one more row in a list the
   player already reads.
3. **It is honest.** A new required part that arrives with a milestone is a
   normal event in a development-stage mod, and the alternative is a permanent
   lie in the code.

---

## 7. Camshaft ownership — following an existing precedent exactly

> ### The authoritative flag lives on the **controller** `CrankshaftBlockEntity`. (Option A.)

This is not a new pattern. The **Redstone Control Module** is already an
engine-wide item installed into a Crankshaft section, and the code that handles it
answers every question in §§9–13:

| Concern | How the Control Module already solves it |
| --- | --- |
| Engine-wide read | `engineHasControlModule()` → `getEngineController().controlModuleInstalled` |
| Local read, for drops | `hasControlModule()` → the **local** field. The comment is explicit: *"an engine-wide answer there would have every section of an inline-4 drop a module the player only ever crafted one of"* |
| Controller migration | `migrateControllerConfigurationTo` copies to the successor **and clears the original**: *"One module, one owner. Clearing it here is what makes the transfer a move rather than a duplication"* |
| Drops | `CrankshaftBlock#onRemove` reads the **local** flag |

**The Camshaft is the same kind of part and takes the same three rules:**

1. **Local flag, engine-wide read.** `hasCamshaft()` is the section's own field;
   `engineHasCamshaft()` goes through `getEngineController()`.
2. **Move, never copy.** Controller migration sets the successor's flag and clears
   its own, in `migrateControllerConfigurationTo`.
3. **Drop from the flag holder only.** `onRemove` drops one Camshaft if and only
   if the local flag is set.

Those three give the required invariant for free:

> **Across any structural change, `Σ(sections with the flag) + (items in the world)
> = the number of Camshafts ever crafted.`** One owner at a time; a transfer sets
> one and clears one; a removal drops one and clears one.

### §11 — shrink and split

| Event | Outcome |
| --- | --- |
| A **non-owning** section is mined | flag untouched; the surviving engine keeps its Camshaft |
| The **owning** section is mined | `onRemove` drops one Camshaft as an item. It is **not** silently reassigned to a survivor |
| An engine splits into two runs | the run containing the flag keeps it; the other has none and must be serviced |
| An engine grows at the negative end | the flag **moves** to the new controller via the existing transfer |

Dropping rather than reassigning is the rule a player can predict: **the Camshaft
comes out of the block you broke.** Silent reassignment would mean mining the left
end of an R4 leaves the Camshaft somewhere the player did not put it.

### §10 — one correction to the 15A design document

Milestone 15A stated that *"the existing code already transfers simulation state on
controller change; 15B must add the cycle angle and armed mask to whatever it
transfers."* **That is wrong, and the correction matters.**
`migrateControllerConfigurationTo` transfers **configuration only** and explicitly
not simulation state: *"What deliberately does not move: the running engine state,
the crank angle, the momentum and the redstone signal. A shape change stops the
engine by design."*

The recommendation therefore changes shape. 15B should **add** the cycle position
and the arming latches to that transfer — not because the code already does
something similar, but because a valvetrain makes the omission newly visible: a
stopped engine still has a position, and resetting it would jump every valve to a
different stroke in front of the player. It is two more lines in a method that
exists for exactly this purpose.

---

## 7a. Removal architecture audit — what the wrench branch must not break

This section is an audit of the **existing** removal code at `f015e58`, not of
anything this branch changes. It exists because the wrench/dismantle branch is
about to modify exactly the code the conservation invariant depends on.

### There is already one canonical destruction path

Audited: every `popResource` call site in production, every `onRemove` override,
and every place the mod destroys a block itself.

| Path | Call sites | Reached by |
| --- | --- | --- |
| **`Block#onRemove`** | `CrankshaftBlock`, `CylinderBlock`, `CarburetorBlock`, `OilSumpBlock` | pickaxe, creative, explosion, piston, `/setblock`, **and any wrench that removes the block normally** |
| **`useItemOn` service removal** | the same three blocks | a deliberate right-click, engine stopped |
| **Controller handover** | `migrateControllerConfigurationTo` | move-and-clear; **emits no item at all** |
| The block's own item | **loot table** + `copy_components` | carries wear on the stack |

> **The mod destroys no engine block itself.** There is no `destroyBlock`,
> `removeBlock` or `setBlock(AIR)` anywhere in `src/main/java`. Every destruction
> today therefore originates outside the mod and funnels through `onRemove` — which
> is already the single canonical path the future invariant needs.

That is the good news: **the architecture the wrench branch is building on is
correct.** The risk is entirely in what the wrench branch does next.

### The one thing that would break it

Create's `IWrenchable` is not resolvable in this sandbox (the proxy blocks
`maven.createmod.net`), so this is stated as a **contract to verify**, not as a
claim about Create's internals:

> **Requirement.** The wrench dismantle must remove the block through a path that
> runs `Block#onRemove`, and must **not** hand-write its own item drop for
> installed components.
>
> **The check:** wrench a Crankshaft holding a Redstone Control Module. Exactly one
> module must appear. Two means the wrench added a second drop path on top of the
> canonical one; zero means it bypassed `onRemove` entirely.

Both failure modes are cheap to introduce and expensive to notice, because today
only one engine-wide component exists and it is optional.

### The three rules, and why they are enough

| # | Rule | Where it already lives |
| --- | --- | --- |
| 1 | **Local flag, controller read.** The drop test reads the section's own field; "does this engine have one" resolves through the controller | `hasControlModule()` vs `engineHasControlModule()` |
| 2 | **Handover moves, never copies.** Set the successor, clear the original | `migrateControllerConfigurationTo` |
| 3 | **Destruction drops from the holder**, whatever the cause | `onRemove` |

Production states rule 1's rationale outright: *"an engine-wide answer there would
have every section of an inline-4 drop a module the player only ever crafted one
of."* Rule 2's likewise: *"One module, one owner. Clearing it here is what makes
the transfer a move rather than a duplication."*

### The invariant, made executable

`InstalledComponentOwnership` models these rules with no reference to any
particular item, and `InstalledComponentConservationTests` drives them through
every audited case:

```
installed flags across every surviving run  +  loose item stacks  =  constant
```

| Audited case | Result |
| --- | --- |
| 1 — controller destroyed, sections survive | item **drops**; survivors get none; **not** silently reassigned |
| 2 — engine extended, controller changes | flag **moves**; old owner left holding nothing |
| 3 — R4 shrinks to R3 at the far end | nothing drops, nothing moves |
| 4 — middle section splits the engine | the run holding it keeps it; the orphan must be serviced |
| 5, 6, 7 — pickaxe / wrench / creative | **identical ledgers** — three ways to reach one behaviour |
| merge of two equipped engines | both conserved; the spare is stranded but **recoverable** |
| 48 000 random structural events | total never moves |

The last section of the test file removes each rule in turn and shows the ledger
going out of balance, so the guarantees are demonstrably load-bearing rather than
trivially true.

### One case the audit list did not name

**Joining two engines that each already have a component.** The merged engine's
controller keeps its own; the other becomes a *stranded spare* on a follower
section — invisible to the engine-wide read, but still carried by that section's
local flag, so breaking it returns the item.

Untidy and conservative, which is the correct order of priorities: nothing is
duplicated and nothing is deleted. Making it tidy (auto-ejecting the spare on
merge) is possible later and is **not** required for the invariant.

### Does a future engine-wide component need a special hook?

**No.** A future part following the Control Module's three rules needs:

* one `boolean` on `CrankshaftBlockEntity`, saved and loaded;
* one line in `migrateControllerConfigurationTo` (set successor, clear self);
* one line in `CrankshaftBlock#onRemove` (`if (local flag) popResource(...)`);
* one branch in `useItemOn` for install/remove.

**No new dismantling system, no second drop path, no hook.** The requirement on
the wrench branch is therefore purely negative: *do not create a second path.*

---

## 8. Camshaft service interaction

Modelled on the four existing installable parts, so it needs no new player
vocabulary.

| | Behaviour |
| --- | --- |
| **Install** | Hold a Camshaft, right-click **any** Crankshaft section of the engine. The flag is set on the **controller**, not on the clicked section |
| **Any section?** | **Yes.** The Carburetor sits *above any one cylinder* and the Oil Sump *below any one section*; forcing the player to find the controller would be the only part in the mod that demands it |
| **Already installed** | Refused, with an action-bar message naming the engine, exactly as a second Piston Assembly is refused today |
| **Remove** | Right-click with an empty hand while **stopped**; one Camshaft is returned |
| **Running engine** | Service refused with an action-bar message. Consistent with the mod's existing posture that you do not reach into a running engine |
| **Feedback** | Goggle overlay gains one row in the installed-components list: `Camshaft: Installed / Missing`, green/red — the Spark Plug row's exact shape |

---

## 9. Structure validity — two questions, not one

> ### The Camshaft must **not** invalidate the structure. It gates combustion.

The existing code already draws this distinction and documents it:
`isMechanicallyValid()` covers *"only what the engine needs in order to turn"* —
sections with cylinders and pistons, one flywheel — while *"a Spark Plug is not
structural either, and is deliberately per cylinder: an inline-4 with one plug
missing is a complete, sound machine that runs on three cylinders."*

| Missing part | Mechanically assembled? | Can combust? |
| --- | --- | --- |
| Piston Assembly | **no** | no |
| Flywheel | **no** | no |
| Spark Plug (one cylinder) | yes | that cylinder only, no |
| Carburetor | yes | no (no fuel) |
| Oil Sump | yes | yes — runs dry, more friction |
| **Camshaft** | **yes** | **no — the whole engine** |

A single `INVALID` state for a diagnosable missing service part would be a
regression in exactly the direction Milestone 13's diagnostics were built to
avoid. The player should read *"this engine has no camshaft"*, never *"invalid"*.

### §15 — missing-Camshaft semantics, formalised

| Aspect | Behaviour |
| --- | --- |
| External / Create rotation | **allowed** |
| Hand Crank rotation | **allowed** |
| Piston, rod, crank animation | **works normally** |
| Valve animation | **static, shut** |
| Camshaft / pushrods / rockers | **not rendered** — the part is not there |
| Compression resistance | **normal** — see §10 |
| Combustion | **never** |
| Fuel consumption | **none** |
| Combustion sound | **none** |
| `activeCylinderMask` | **0** |
| `isActivelyGenerating()` | **false** |
| Published capacity | **0** |

> **No new machinery is needed to implement any of this.** The arming latch
> already gates ignition on having inhaled. No camshaft ⇒ the intake never opens ⇒
> the latch never sets ⇒ the cylinder never fires. Every row above then follows
> from rules that already exist.

Verified: 3000° of cranking a migrated, camshaft-less engine produces **0
ignitions and mask 0**, while the gas spring still resists — a real engine that
will not start, not a dead block.

### §16 — compression without a camshaft

> ### Keep normal mechanical compression. Gate only combustion and arming.

Physically a valvetrain-less engine has its valves shut, so it does compress —
this is not a fiction. The alternative (modelling trapped-charge behaviour for a
state the player will spend two minutes in) is thermodynamics for no gameplay.

The result is the right *feel*: cranking it is as heavy as cranking a working
engine, which is what tells the player the problem is not mechanical.

---

## 10. Save schema

### Versioning

```java
ENGINE_STATE_VERSION = 2      // written on every save, read on every load
version 1 = the simplified 360-degree engine
version 2 = the four-stroke engine
```

An absent tag reads as `0` from `getInt`, which is the signature of a save written
before versioning existed — that is, **version 1**.

> **Do not infer the version from a missing key.** *"No `CycleAngle`, so it must be
> old"* works exactly once. For the migration *after* this one it is ambiguous
> between a version-1 save and a version-3 save that dropped the field, and by then
> the code that could tell them apart is gone. An explicit integer costs one tag
> and never becomes ambiguous. Tested, including that a hypothetical version 3 is
> not treated as legacy.

### Persisted versus derived

| Persisted — authoritative | Why |
| --- | --- |
| `EngineStateVersion` | the schema tag |
| `CycleIndex` (`long`) | which cycle — the counter half of the position |
| `CycleAngle` (`float`, `[0,720)`) | where in it. **Replaces** `CrankAngle` |
| `SimulatedRpm` | signed angular velocity, unchanged |
| `ArmedMask` (`int`) | per cylinder: a charge inducted and unburnt. Not derivable |
| `LastFiredCycle` (`long[]`) | the event keys |
| `PowerStrokeStrength` (`float[]`) | a charge already paid for, mid-stroke |
| `Phase` | the run state |
| `CamshaftInstalled` (`boolean`) | on the controller |
| controls, layout, supply, **all wear** | unchanged from version 1 |

| **Not** persisted — derived | Derived from |
| --- | --- |
| physical crank angle | `cycleAngle % 360` |
| current stroke | `FourStrokePhase.at(cycleAngle)` |
| piston position | `CrankMath.pistonPosition(physicalAngle)` |
| **camshaft angle** | `cycleAngle / 2` |
| **valve lift, pushrod, rocker** | `ValveTiming.lift(localCycleAngle)` |
| `activeCylinderMask` | combustion ages + structural viability |
| `publishedRpm`, `outputRpm`, `publishedCapacityFactor` | the simulated RPM and the mask |

The rule the codebase already follows, and this keeps: **never persist a
representation beside the thing it represents.** `restoreAfterLoad` exists because
that mistake was made once with `publishedRpm`.

### §19 — a reload is not a migration

The two paths must never be confused, and the version tag is what keeps them
apart.

| | Version-2 reload | Version-1 migration |
| --- | --- | --- |
| Cycle index | preserved | reset to 0 |
| Arming latches | **preserved** | **cleared** |
| Event keys | **preserved** | **cleared** |
| Paid power stroke | preserved | discarded |
| Active mask | rebuilt from preserved ages | 0 |
| Phase | preserved | RUNNING → COASTING |

Both are tested side by side on the same physical position, asserting that the
reload keeps exactly what the migration throws away.

---

## 11. Create capacity and the network

> ### The existing post-load reconciliation already does this. It must not be weakened.

`CrankshaftBlockEntity` documents the rule: *"loading restores only the engine's
own physics… and raises `needsPostLoadReconcile`; the first server tick that can
actually see the engine's blocks re-derives generation from the world and
force-publishes the result… whatever Create came back holding. Nothing touches the
kinetic network from inside `read`."*

That is exactly the required invalidation sequence, and it already exists. For a
migrated engine the sequence resolves to:

1. `read` restores physics only. **No capacity is published from `read`.**
2. Migration sets phase COASTING, `activeCylinderMask = 0`.
3. The first reconciled tick re-derives from the world: no Camshaft ⇒ not
   generating ⇒ **capacity 0, force-published.**
4. Create's cached Stress Capacity for that source is overwritten with zero.

**No ghost capacity is possible**, because the migrated engine cannot pass
`evaluateActiveGeneration()` — it is not RUNNING and its mask is empty — and the
reconciliation force-publishes regardless of what Create restored.

15B must add exactly one thing: **the migration must raise
`needsPostLoadReconcile`**, which the load path already does unconditionally.

---

## 12. Networking

### §22 — the packet does not change

| Concern | Answer |
| --- | --- |
| `EngineCombustionEventsPayload` | **unchanged.** One bit per cylinder per tick is still lossless, and four-stroke *halves* the event rate — its own headroom argument already reasons in 720° terms |
| Full cycle state per tick | **never.** No new per-tick traffic |
| Valve positions | **never synced** — derived on the client from the cycle angle |
| Piston angles | **never synced** — already derived |
| `CrankAngle` in the BE update | **widened, not added**: the same float now carries `[0, 720)` |
| New slow state | **one boolean** — `CamshaftInstalled`, on the existing BE sync |

> **The client sync does not grow by a single byte.** One float widens its range;
> one boolean joins a tag that already carries a dozen.

`CycleIndex` is deliberately **not** synced. The client renders from
`cycleAngle mod 720`; the counter exists for event identity and save integrity,
both server-side.

### §23 — how the client knows which half of the cycle it is in

This is the one genuinely new client problem, and it is worth stating precisely.

Today the client does **not** receive a crank angle every tick. It *integrates*
one, from the speed Create already synchronises — the class comment says so:
*"Steps 1 to 3 read only values Create already synchronises, so client and server
derive the same crank angle from the same input without this mod sending a packet
per tick."* The BE resync carries `CrankAngle` every **200 ticks** running, or
**20** while freewheeling.

At 360° that is fine: an integration error shows up as a small visual offset, and
*nothing depends on which revolution you are in.* Under four-stroke, **cycle parity
is binary and permanently visible** — 360° of accumulated drift animates every
valve exactly one stroke out of phase, for ever, while the piston still looks
right.

**The arithmetic:**

```
drift per tick = Δrpm × 360 / 1200  =  Δrpm × 0.3 °/tick
```

Sustained client speed error is bounded by the publishing rules: anything at or
above `NETWORK_RPM_MAJOR_DELTA` (**6 RPM**) is republished promptly. So worst-case
sustained drift is `6 × 0.3 = 1.8 °/tick`:

| Resync interval | Worst-case drift | Verdict |
| --- | --- | --- |
| 200 ticks (today) | **360°** | **exactly one full stroke — a coin flip** |
| **100 ticks** | **180°** | half a stroke: visibly slightly out, never inverted |
| 20 ticks (coasting) | 36° | ample |

> ### Three anchors, in order of how much work they do.
>
> 1. **Combustion events — the primary anchor, and free.** A combustion event
>    already reaches the client on the tick it happens, and it is an *exact* phase
>    fix: cylinder *i* fired ⇒ its cycle angle is 180 ⇒ the master cycle angle is
>    known. These arrive at **0.53–6.4 Hz** depending on layout and speed, far more
>    often than any BE resync. A *running* engine is therefore re-anchored
>    continuously at no packet cost.
> 2. **The BE resync**, carrying the widened `CycleAngle`. Covers the states with
>    no combustion: cranking, coasting, and an engine being motored by another
>    Create source. **Halve the running interval to 100 ticks.**
> 3. **Client-side integration** between anchors, exactly as today.

### §24 — resynchronisation cases

| Case | Behaviour |
| --- | --- |
| Player enters the chunk | full BE update carries `CycleAngle` — correct on the first frame |
| Player joins the server | the same |
| Engine already running | anchored by the next combustion event, within a fraction of a second |
| Delayed / lost BE update | drift bounded by §23's arithmetic; the next anchor corrects it |
| Engine starts | the first combustion event is an exact anchor |
| Engine stops / reverses | the phase change is an event-driven update, which carries the angle |
| Engine motored, unfuelled | no combustion events — the 100-tick resync is the only anchor, and its 180° bound applies |

**One authoritative anchor, always the server's.** The client never derives cycle
parity from a rule of its own; it snaps to whatever the server last told it and
integrates forward.

---

## 13. Sound and fuel

### §25 — sound needs nothing

The architecture is already correct and requires **no migration work**:

- pulses fire from the **authoritative combustion counter**, so one paid charge is
  one sound, and no client-side firing prediction exists to go wrong;
- the counters are monotonic and persisted, so a reload cannot double-play;
- the R1/R2/R3/R4 cadence falls out of the event spacing rather than being
  authored — the frozen 720/180+540/240/180 patterns produce it for free.

The only follow-up is documentation: comments quoting Hz figures become wrong when
the rate halves.

### §26 — the one-millibucket engine

An engine saved with exactly **1 mB** cannot pay a 2 mB four-stroke charge.

> **Documented, not fixed.** No fractional credit, no tank migration, no top-up.
> The fluid itself is untouched, so nothing is lost or duplicated — the player
> simply adds fuel, which is what they would have done one event later anyway.
> Inventing a credit would be inventing fuel, which is the one thing a migration
> may never do.

---

## 14. The player's update experience

> A world updated from version 1 to version 2:
>
> 1. **The world loads normally.** No reset, no repair prompt, no chunk damage.
> 2. **Every engine is still physically there** — every crankcase, cylinder,
>    flywheel, carburetor and sump exactly where it was built.
> 3. **Nothing is lost.** Piston Assemblies, Spark Plugs, Air Filters, Control
>    Modules and all their wear come across untouched. No item disappears and none
>    duplicates.
> 4. **Pistons are exactly where they were left**, to the last float bit.
> 5. **Running engines spin down** rather than stopping dead — they migrate to
>    COASTING with their momentum intact, and come to rest naturally.
> 6. **No engine produces power it has not earned.** Capacity is zero until real
>    four-stroke combustion happens.
> 7. **Each engine needs one Camshaft.** Until then it turns over, feels its
>    compression and will not catch — and the goggles say `Camshaft: Missing`.
> 8. **After fitting one, the engine starts and runs**, now as a four-stroke: half
>    the firing rate, twice the impulse, the same power and the same fuel economy.

The one visible change beyond the valvetrain: **an inline-4's pistons now move in
pairs** (1+4 against 2+3) instead of a 90° stagger, because that is the crank a
four-stroke inline-4 has.

---

## 15. Impact map for Milestone 15B

`UNCHANGED` / `MODIFIED` / `REPLACED` / `NEW`, with the migration risk and what
protects it.

### Simulation

| Item | Verdict | Risk | Protected by |
| --- | --- | --- | --- |
| `EngineState.crankAngleDegrees` | **REPLACED** by `CyclePosition` | high — every consumer | `FourStrokeStateTests`, `EngineStabilityTests` |
| `EngineState.getCrankAngleDegrees()` | **MODIFIED** — returns `cycleAngle % 360` | low, API preserved | renderers unchanged |
| `EngineState.getCycleAngleDegrees()` | **NEW** | — | `ValvetrainTests` |
| `EngineState.crossedFiringAngle` | **REPLACED** — 720° crossing + latch + event key | **highest** | `FourStrokeCycleTests`, `FourStrokeStateTests` |
| `EngineState.armed[]`, `lastFiredCycle[]` | **NEW** | medium — must persist | `EngineMigrationTests`, `FourStrokeStateTests` |
| `EngineState.compressionTorqueSum` | **MODIFIED** — gated to the sealed half | low | `FourStrokeCycleTests` |
| `EngineState.deriveActiveCylinderMask` | **MODIFIED** — firing intervals + structural term | medium | `FourStrokeBalanceTests`, `EngineCapacityTests` |
| `EngineState.stop()` | **MODIFIED** — arms on rest | low | `FourStrokeBalanceTests` |
| `EngineState.restoreAfterLoad` | **UNCHANGED** | low | `EngineReloadTests` |
| `EnginePhase` | **UNCHANGED** | none | — |

### Tuning

| Item | Verdict | Risk |
| --- | --- | --- |
| `POWER_STROKE_DUTY` → `/720` | **MODIFIED** | **must land with the firing change** |
| `FUEL_PER_COMBUSTION_MB` 1→2 | **MODIFIED** | same commit as above |
| `cylinderPhaseOffsetDegrees` | **REPLACED** by the two-offset table | medium |
| `generationCombustionAllowanceTicks` | **MODIFIED**; 60-tick ceiling **removed** | medium |
| `MIN/MAX_START_CYCLES` → 1/3 | **MODIFIED** | low |
| `START_ATTEMPT_TIMEOUT_TICKS` | **UNCHANGED** — see 15A.1 §21.7 | — |
| `FIRING_ANGLE_DEGREES`, `POWER_STROKE_DEGREES`, `COMPRESSION_PEAK_TORQUE`, `FLYWHEEL_INERTIA` | **UNCHANGED** | — |

### Block entities, items, network, client

| Item | Verdict | Risk | Protected by |
| --- | --- | --- | --- |
| `CrankshaftBlockEntity` `read`/`write` | **MODIFIED** — version tag, cycle state, camshaft | **highest** | `EngineMigrationTests`, `EngineReloadTests` |
| `CrankshaftBlockEntity.migrateControllerConfigurationTo` | **MODIFIED** — camshaft, cycle position | medium — duplication surface | new controller-migration test |
| `CrankshaftBlockEntity` camshaft install/remove | **NEW** | medium | — |
| `CrankshaftBlockEntity` goggle overlay | **MODIFIED** — one row | low | — |
| `CrankshaftBlock#onRemove` | **MODIFIED** — drop the Camshaft | medium — **coordinate with the wrench branch** | — |
| `RESYNC_INTERVAL` 200→100 | **MODIFIED** | low | §12 arithmetic |
| `EngineCombustionEventsPayload` | **UNCHANGED** | none | — |
| `CylinderBlockEntity` | **UNCHANGED** | none | — |
| `EngineComponents.isMechanicallyValid` | **UNCHANGED** — camshaft is not structural | low | — |
| `EngineFlywheelBlockEntity` | **UNCHANGED** | none | `EngineCapacityTests` |
| `CrankshaftRenderer` | **MODIFIED** — camshaft, lobes, gears | low, visual | — |
| `CylinderRenderer` | **MODIFIED** — pushrods, rockers, valves; geometric offset | low, visual | — |
| `CrankMath` | **UNCHANGED** | none | — |
| `ECItems` | **NEW** — Camshaft | low | — |
| `ECSounds`, `CombustionAudio` | **UNCHANGED** (doc comments only) | none | — |
| Ponder, advancements, recipes | **MODIFIED** — §16 | low | — |

---

## 16. Ponder, advancements, recipes

**Not implemented here.** Milestone 14 content is untouched.

| Item | Change |
| --- | --- |
| Ponder *Building a Basic Engine* | add the Camshaft install step |
| Ponder *Starting the Engine* | show the 2:1 timing gears; the crank turns twice per cam turn |
| Ponder — **new** *The Four Strokes* | the five-beat scene in the valvetrain doc §13 |
| Advancement *Some Assembly Required* | criterion must become Camshaft-aware, or it completes on an engine that cannot run |
| Advancement *It Really Started!* | **unchanged** — it keys off a genuine STARTING → RUNNING transition, which still means the same thing |

### Recipe progression

- **Same tier as the Crankshaft and Cylinder.** The Camshaft is a core engine
  part, not an upgrade, and gating it later would strand every existing engine
  behind unrelated progression.
- **No ingredient may require a running engine.** That is a progression loop:
  you would need an engine to make the part that makes an engine run.
- **No separate Timing Gear recipe** — the gears are part of the Camshaft, per the
  frozen valvetrain decision.
- Exact ingredients deliberately left open; the recipe-balance branch may still
  move, and this only constrains the tier.

---

## 17. The 15B commit sequence

Ordered so that **every commit compiles, passes the full suite, and leaves the
game playable.** The prompt's suggested shape is close; the differences below come
from reading the source.

| # | Commit | Behaviour change | Green after? |
| --- | --- | --- | --- |
| 1 | Promote the prototype into `content/engine` (`CyclePosition`, `FourStrokePhase`, `FourStrokeCycle`, firing table, `ValveTiming`, `CamshaftTiming`) | **none** — nothing calls it | ✅ |
| 2 | Add `ENGINE_STATE_VERSION`, write it, read it, migrate nothing yet | **none** | ✅ |
| 3 | Widen the stored angle to `CyclePosition`; `getCrankAngleDegrees()` returns `% 360`; persist `CycleIndex`/`CycleAngle`; **implement the version-1 migration** | **none visible** — still fires every 360° | ✅ |
| 4 | Add the two-offset firing table; renderers take `geometricOffsetDegrees` | **R4 throws become 0/180/180/0** | ✅ |
| 5 | Add the Camshaft item, install/remove, persistence, controller transfer, drops, overlay row | **engines need a Camshaft** | ✅ |
| 6 | **Replace the firing opportunity** (720° crossing, arming latch, event key) **and** `POWER_STROKE_DUTY`/`FUEL_PER_COMBUSTION_MB` **in one commit** | **four-stroke goes live** | ✅ |
| 7 | Gate compression to the sealed half | smoother motoring | ✅ |
| 8 | Active-cylinder allowance in firing intervals; drop the 60-tick ceiling; structural term | fixes R1 capacity flicker | ✅ |
| 9 | Start cycles 1/3; arm on rest; the travel-based lapse rule | starting feels right | ✅ |
| 10 | Client phase sync: resync 100 ticks, combustion-event anchoring | correct valve phase | ✅ |
| 11 | Render camshaft, gears, pushrods, rockers, valves | the engine looks four-stroke | ✅ |
| 12 | Ponder, advancements, recipes | content | ✅ |
| 13 | Update the existing suites; delete the 360° assumptions | none | ✅ |

**Three ordering constraints that are not negotiable:**

1. **Commit 3 before commit 6.** Migration must exist *before* behaviour changes,
   so no build ever loads a version-1 save with four-stroke firing.
2. **Commit 5 before commit 6.** The Camshaft must be installable before it is
   required, or commit 6 makes every engine in the world unstartable with no cure.
3. **Steps 5 and 6 of the prompt's shape must be one commit.** Between the firing
   change and the duty/fuel change the engine makes **half its power** — that
   build is not merely untuned, it is unplayable, and it must never exist as a
   reviewable state.

### §34 — rollback and feature gating

> ### Recommendation: **no `FOUR_STROKE_ENABLED` switch.**

The commit sequence above already provides the safety a flag would: every commit
is independently green, and commits 1–5 change no combustion behaviour at all, so
the risky change is one reviewable commit with a clean revert.

A flag would cost what §35 forbids — **two live simulation paths**: every firing,
compression, fuel and capacity site would need both branches, the test suite would
need to run twice, and the flag would outlive its usefulness. And it does not even
solve the real risk: the migration is one-way, so a player who loads a world with
the flag on and then turns it off has a version-2 save a version-1 engine cannot
read.

**Legacy code exists only to migrate.** After commit 3, `LegacyEngineState` and
`EngineStateMigration` are read-only conversion code on the load path — not a
second engine. Nothing in the game ever runs a 360° simulation again, and there is
no config toggle to bring one back.

---

## 18. Prototype and tests

| Class | Added | Responsibility |
| --- | --- | --- |
| **`LegacyEngineState`** | 15A.3 | the version-1 schema, written down before it stops existing |
| **`EngineStateMigration`** | 15A.3 | version detection and the one-time conversion; emits the real `FourStrokeEngine.Save` |

**`EngineMigrationTests`** — 9 sections, all passing:

| Requirement | Result |
| --- | --- |
| Version detection, including a future version 3 | 5 checks |
| Physical angle preserved | **bit-for-bit across 2770 angles**, worst error `0.0` |
| Deterministic | 20 legacy states × 8 repeats = 160 identical migrations |
| **No free combustion** | **1440 rest positions; cheapest first bang 361°**; the 179° trap needs 721° |
| M1–M12 matrix | every case: legal angle, physical angle kept, no charge, no capacity, no ghost events, not RUNNING, momentum kept |
| Paid stroke discarded | on POWER, nothing burning, zero combustion torque |
| No capacity survives | a generating R4 migrates to mask 0, and refills only after 4+ genuine ignitions |
| Reload ≠ migration | the reload keeps exactly what the migration clears |
| Missing camshaft | 3000° of cranking → 0 ignitions, mask 0, compression intact |

```
./gradlew simulationTest   ->   15 suites, all "all checks passed", BUILD SUCCESSFUL
./gradlew checkModels      ->   0 problems, BUILD SUCCESSFUL
./gradlew check            ->   blocked by this sandbox's proxy (403 from maven.neoforged.net
                                for net.neoforged:neoform-runtime), verified identical at the
                                base commit 776e275 — environmental, not a regression
```

---

## 19. FROZEN / OPEN

### FROZEN

| # | Decision | Value |
| --- | --- | --- |
| 1 | Legacy angle migration | `cycleAngle = oldCrankAngle`, `cycleIndex = 0` — the first half |
| 2 | Physical angle | preserved bit-for-bit |
| 3 | Arming latch on migration | **`armedMask = 0`**, always |
| 4 | Event keys on migration | all `NO_EVENT` |
| 5 | Paid power stroke | **discarded** |
| 6 | Start attempt | discarded |
| 7 | RUNNING / COASTING | → **COASTING**, momentum kept |
| 8 | CRANKING / STARTING / STOPPED | → STOPPED, momentum kept |
| 9 | `activeCylinderMask` | **reset to 0** |
| 10 | Existing-world Camshafts | **none granted — the player crafts one** |
| 11 | Camshaft ownership | local flag on the **controller**, Control Module pattern |
| 12 | Controller migration | **move and clear**, never copy |
| 13 | Owning section mined | **drops one Camshaft**, no reassignment |
| 14 | Install / remove | any section; removal only while stopped |
| 15 | Missing Camshaft | **mechanically valid, combustion-ineligible** |
| 16 | Compression without a Camshaft | **normal** |
| 17 | Schema version | explicit `ENGINE_STATE_VERSION`; 1 = legacy, 2 = four-stroke |
| 18 | Persisted state | the table in §10 |
| 19 | Capacity invalidation | the existing post-load reconciliation, unweakened |
| 20 | Combustion payload | **unchanged** |
| 21 | Client anchors | combustion events first, BE resync second, integration between |
| 22 | Running resync interval | **200 → 100 ticks** |
| 23 | `CycleIndex` on the wire | **not synced** |
| 24 | 1 mB fuel edge case | documented, never credited |
| 25 | Feature flag | **none** — no dual simulation path |
| 26 | Commit order | migration before behaviour; Camshaft before it is required; duty+fuel with firing |

### OPEN

1. **Exact Camshaft recipe ingredients** — tier is frozen, ingredients wait for the
   recipe-balance branch.
2. **The `onRemove` drop path must be coordinated with the wrench/dismantle
   branch** — that branch is actively changing the code this rule lands in.
3. **Advancement criterion wording** for *Some Assembly Required*.
4. **Whether the goggle overlay should name the section holding the Camshaft**, or
   only report engine-wide presence. Recommendation: engine-wide, matching how the
   Control Module reads today.
5. **Migration telemetry** — whether to log a one-line summary per migrated engine.
   Recommendation: yes, at DEBUG, once per engine; it is cheap and the first
   bug report will want it.
