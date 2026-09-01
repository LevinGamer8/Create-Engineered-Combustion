# Milestone 14 — Player experience, Ponder and progression

The question this milestone exists to answer:

> Could somebody who knows Minecraft, has barely used Create, knows almost nothing
> about real engines, and has never seen this mod work out how to build one, fuel
> it, start it, connect it, expand it, diagnose it and maintain it — **without**
> reading the README, reading the source, asking the developer, or watching a
> video?

The intended answer is yes, and the way it is answered is by putting each piece of
that in the place a player already knows to look for it.

> **Followed by [Milestone 14.1](milestone-14-visual-coherence.md).** The first
> real play-through found two things nothing in this document's validation could
> see: an inline-4 that looked like four inline-1s, and Ponder scenes that pointed
> at the wrong parts. Both are fixed there, and the highlight targets are checked
> by the build now.

---

## 1. The information hierarchy

Five layers, each answering exactly one question. The discipline is in what each
one *refuses* to say: none of them is a manual, and a fact that belongs in two of
them is a fact that will be wrong in one of them.

| Layer | Answers | Where |
| --- | --- | --- |
| **Advancements** | *What should I try next?* | 22 advancements, one tab |
| **Recipes** | *How do I make this?* | Vanilla + Create recipe types, no viewer required |
| **Ponder** | *How does this machine work?* | 8 scenes, in Create's own screen |
| **Tooltips** | *What is this item for?* | 1–2 grey lines, 15 items |
| **Goggles** | *What is my engine doing right now?* | Overlay, plus sneak diagnostics |
| **Action bar** | *Why did that just not work?* | Only on a real interaction |

---

## 2. Ponder

Registered through Ponder's own `PonderPlugin`, so the scenes appear beside
Create's, respond to the same key hint on item tooltips, and are indexed where a
player already looks. There is no competing tutorial screen.

**Category:** `engineered_combustion:engines`, titled *Engineered Combustion*,
iconned with the Crankshaft, added to the index. Every engine component is filed
under it, so opening the index on any one part finds the rest.

### Scenes

| Scene | Teaches | Opened from |
| --- | --- | --- |
| `assembling_an_engine` | Building a real inline-1, component by component | Crankshaft, Cylinder, Piston Assembly, Flywheel, Spark Plug, Air Filter |
| `fuel_and_lubrication` | Gasoline in, Engine Oil in, what each protects | Carburetor, Oil Sump, Gasoline, Engine Oil, Air Filter |
| `starting_an_engine` | Cranking, catching, throttle, ignition, coasting | Crankshaft, Spark Plug, Flywheel |
| `inline_engines` | R1 → R4, firing order, active cylinders, the limit | Crankshaft, Cylinder, Piston Assembly |
| `engine_controls` | Manual first, then the optional Redstone module | Redstone Control Module, Carburetor |
| `engine_maintenance` | The rebalanced wear reality, and no-free-repair | Piston Assembly, Crankshaft, Air Filter, Engine Oil |
| `diagnosing_an_engine` | The goggles, and the common faults | Crankshaft, Flywheel, Spark Plug |
| `from_shale_to_fuel` | The real petroleum chain, conceptually | Oil Shale, Crushed Oil Shale, Crude Oil, Gasoline, Engine Oil |

### The scenes are technically true

Every scene shows the game as it actually behaves. **None** of them demonstrates
a self-start from zero, two valid Flywheels, a mandatory Air Filter, mandatory
redstone, a throttle that teleports RPM, an externally driven engine producing
power, one Spark Plug serving two cylinders, an R4 as four engines, or a healthy
engine rapidly destroying itself.

Where the real behaviour is awkward to present, the **presentation** is scripted
and the game is not touched. Starting is genuinely multi-cycle and partly random;
the scene shows cranking, then firing attempts, then a catch, at a readable pace,
without reaching into the simulation to make real starts deterministic.

### The section lifecycle, and the crash it caused

The first real in-game test of this milestone hard-crashed the client:

```
NullPointerException: Cannot invoke "Selection.substract(Selection)"
because "this.section" is null
    at WorldSectionElementImpl.erase(WorldSectionElementImpl.java:112)
    from PonderSceneBuilder$PonderWorldInstructions.lambda$hideSection$3
```

Read out of Ponder 1.0.82's own source, the lifecycle is:

1. `PonderScene` builds its base `WorldSectionElement` with the **no-arg**
   constructor, so its `section` field is `null`, and `setEmpty()` puts it back to
   `null` on every replay.
2. `showSection(sel, dir)` schedules a 15-tick fade. Only when that fade
   **completes** does `mergeOnto(baseWorldSection)` run and give the base a
   non-null selection.
3. `hideSection(sel, dir)` immediately calls `getBaseWorldSection().erase(sel)`,
   and `erase()` dereferences `section`.

Three scenes called `hideSection` in their opening moments — as a way of saying
"start with this hidden" — and every one of them dereferenced that null. **Ponder
structure blocks are not visible until a `showSection` reveals them**, so hiding
them was never necessary in the first place; the fix is to show less, not to hide
more. Two other `hideSection` calls, which genuinely remove a Flywheel the scene
had already shown, were correct and are unchanged.

`validate_ux.py` now encodes that rule (§10), and no scene uses independent
sections or `ElementLink`s at all.

### The structures are generated, not hand-built

Ponder stages each scene on a vanilla structure NBT at
`assets/engineered_combustion/ponder/<scene>.nbt`. Those are normally exported
from a running game by hand. Here they are produced by
`tools/generate_ponder_structures.py`, which writes gzipped NBT directly — the
same approach `generate_engine_textures.py` already takes with PNG, and for the
same reason: **a file nobody can regenerate is a file nobody can safely change.**

The engine's geometry is not duplicated there. It is transcribed once into one
`OFFSETS` table, and `validate_ux.py` asserts that table agrees with
`EngineComponents`. If the two ever diverge, the scenes would teach a layout the
game refuses to build, which is the one thing a tutorial must never do.

---

## 3. Advancements

22 advancements in one tab, 13 visible and 9 hidden.

### Every one is the same criterion

There is exactly one criterion type in the mod: `engineered_combustion:engine_event`.
Each advancement is that trigger with a different filter, so adding one is a row
in `tools/generate_advancements.py` rather than a Java class that differs from
twenty others by one comparison.

Filters are all optional and absent means *no opinion*, never *no match*. Counts
are explicit ranges rather than bare numbers, because the difference between
`{"min": 4}` and `{"min": 3, "max": 3}` is the entire active-versus-healthy
distinction the mod is built on.

### The tree

```
Engineered Combustion                     (root, task)
  └─ Black Gold... Sort Of                (task)
       └─ Refined Taste                   (task)
            └─ Keep It Slippery           (task)
                 ├─ Some Assembly Required (task)
                 │    └─ First Cranking    (task)
                 │         └─ It Really Started!        (GOAL)
                 │              └─ Mechanical Power     (task)
                 │                   └─ Double Trouble  (task)
                 │                        └─ Third Time's the Charm (task)
                 │                             └─ Four of a Kind    (GOAL)
                 ├─ Fresh Internals        (task)
                 │    └─ Back in Service   (GOAL)
                 └─ Oil Is Optional, Right?          (hidden)
                      └─ Mechanical Sympathy?        (hidden)
                           └─ Warranty Void          (hidden, CHALLENGE)
                                ├─ Are You Trying to Kill It? (hidden, CHALLENGE)
                                ├─ This Is Fine               (hidden)
                                └─ Still Runs!                (hidden, GOAL)

Hanging off the cylinder branch:
  Four of a Kind ─┬─ Three Out of Four Ain't Bad  (hidden)
                  └─ More Cylinders!              (hidden)
  Mechanical Power ─ Two Flywheels, Zero Problems (hidden)
```

### Exact criteria

| ID | EN | DE | Frame | Criterion |
| --- | --- | --- | --- | --- |
| `root` | Engineered Combustion | Engineered Combustion | task | holds any starting material |
| `black_gold` | Black Gold... Sort Of | Schwarzes Gold ... irgendwie | task | holds Oil Shale or Crushed Oil Shale |
| `refined_taste` | Refined Taste | Raffinierter Geschmack | task | holds Gasoline |
| `keep_it_slippery` | Keep It Slippery | Immer schön schmieren | task | holds Engine Oil |
| `some_assembly_required` | Some Assembly Required | Zusammenbau erforderlich | task | `ASSEMBLED` — the structure became valid |
| `first_cranking` | First Cranking | Erstes Ankurbeln | task | `CRANKING_STARTED` — `STOPPED → CRANKING` |
| `it_really_started` | It Really Started! | Er läuft wirklich! | **GOAL** | `ENGINE_STARTED` — `STARTING → RUNNING` |
| `mechanical_power` | Mechanical Power | Mechanische Kraft | task | `GENERATION_STARTED` — capacity began flowing |
| `double_trouble` | Double Trouble | Doppelt hält besser | task | `INLINE_RUNNING`, cylinders ≥ 2, active ≥ 2 |
| `third_times_the_charm` | Third Time's the Charm | Aller guten Dinge sind drei | task | `INLINE_RUNNING`, cylinders ≥ 3, active ≥ 3 |
| `four_of_a_kind` | Four of a Kind | Viererpasch | **GOAL** | `INLINE_RUNNING`, cylinders = 4, active ≥ 4 |
| `fresh_internals` | Fresh Internals | Frische Innereien | task | `MAINTENANCE_COMPLETED`, from ≥ Used, to ≤ Good |
| `back_in_service` | Back in Service | Wieder im Dienst | **GOAL** | `MAINTENANCE_COMPLETED`, from ≥ Worn, to ≤ Good |
| `oil_is_optional` | Oil Is Optional, Right? | Öl ist optional, oder? | hidden | `ABUSE_STATE`, kind `dry` |
| `mechanical_sympathy` | Mechanical Sympathy? Never Heard of It. | Technisches Feingefühl? Nie gehört. | hidden | `CONDITION_REACHED`, kind `mechanical`, ≥ Poor |
| `warranty_void` | Warranty Void | Garantie erloschen | hidden **CHALLENGE** | `CONDITION_REACHED`, ≥ Critical |
| `are_you_trying_to_kill_it` | Are You Trying to Kill It? | Willst du ihn absichtlich umbringen? | hidden **CHALLENGE** | `ABUSE_STATE`, kind `all_out` |
| `this_is_fine` | This Is Fine | Alles bestens | hidden | `INLINE_RUNNING`, condition ≥ Critical |
| `still_runs` | Still Runs! | Läuft trotzdem! | hidden **GOAL** | `ENGINE_STARTED`, condition **before** the start ≥ Critical |
| `three_out_of_four` | Three Out of Four Ain't Bad | Drei von vier sind doch auch okay | hidden | `INLINE_RUNNING`, cylinders = 4, active **exactly 3** |
| `two_flywheels` | Two Flywheels, Zero Problems | Zwei Schwungräder, null Probleme | hidden | `INVALID_LAYOUT_ATTEMPT`, `second_flywheel` |
| `more_cylinders` | More Cylinders! | Mehr Zylinder! | hidden | `INVALID_LAYOUT_ATTEMPT`, `too_many_cylinders` |

Rewards are toast and personality. XP where it is earned (10 for the first start,
20 for an R4, a rebuild or starting a wreck, 50 for the two challenges) and no
item rewards anywhere — the milestone is explicit that destroying an engine must
not pay.

### One row was deliberately dropped

**Put It Back** — a joke for reinstalling the same worn part — is not implemented.
It would need `MAINTENANCE_COMPLETED` to fire for a swap that improved nothing,
and the tracker refuses to call that maintenance because it is not maintenance.
Giving the joke its own event, or teaching the tracker to lie, is exactly the
ugliness the milestone says to skip it over. The behaviour is still real, still
taught in the maintenance Ponder, and still asserted by a test — it simply does
not hand out a toast.

---

## 4. Events, not scans

Advancement progress never comes from walking every engine and every player. It
comes from `EngineEventTracker`, which lives on each engine controller, compares
this tick against the last, and almost always has nothing to say.

The layer is **pure** — no Minecraft, no players, no advancement API — which is
what lets every rule about when an achievement may be granted be a unit test in
`EngineEventTests` rather than something only a playthrough could check.

### Two mechanisms, because there are two kinds of claim

**Transitions** compare against last tick. That is precisely why a reload cannot
fake one: a controller waking up calls `primeTo(...)` with whatever it was
restored to, so an engine that loaded as `RUNNING` has never been *seen to become*
`RUNNING`. The tracker is deliberately not persisted; saving it would have been
the bug.

**Sustained states** count consecutive ticks and reset the moment the condition
breaks, so a single bad tick is a mistake and stays unrewarded.

| Window | Ticks | Seconds | Why |
| --- | ---: | ---: | --- |
| `DRY_RUNNING_WINDOW_TICKS` | 300 | 15 | An empty sump can be an accident; a quarter minute of it is a choice |
| `ALL_OUT_ABUSE_WINDOW_TICKS` | 200 | 10 | Three simultaneous abuses cannot happen by accident, so the bar is lower |
| `STEADY_RUNNING_WINDOW_TICKS` | 100 | 5 | Long enough that a cylinder missing one revolution during a start does not read as "running on three" |

### Three rules worth naming

Each is easy to get subtly wrong, and each has a test that fails if it regresses:

1. **Only `STARTING → RUNNING` is a start.** `COASTING → RUNNING` is an engine
   picking its fuel back up, and it had already started.
2. **Generation is not rotation.** An engine motored at 220 RPM by a stronger
   network fires nothing, however fast it turns.
3. **A condition only counts if the engine wore its way there.** Condition events
   are gated on the tick having actually charged wear, so wear arriving from disk,
   from an item, or from any future command changes the number without awarding
   anything.

### Debug protection

The mod has **no debug commands**, so there is nothing to guard against. Its one
development tool is the JVM property
`-Dengineered_combustion.wearMultiplier=…`, which multiplies the rate at which
**real operation** accumulates wear. It cannot set a state, cannot be reached
from inside a running game, and cannot fabricate a transition that did not
happen — an engine worn out under it was genuinely worn out by running, just
faster. Should a wear-editing command ever be added, rule 3 above already
excludes it: it would change the number without passing through `wornThisTick`.

### Player attribution

Some events need a person. Nobody "starts" an engine the way they mine a block —
they crank it, let go, and combustion catches seconds later.

`EngineInteractionMemory` holds **one UUID and one expiry**, refreshed by
interacting with the engine (ignition, throttle, fitting or removing parts) and
lapsing after **15 seconds**. It is not persisted, and there is no engine
ownership: an engine is a structure anyone can walk up to, and a permanent owner
field would be wrong the first time a second player touched it.

Events split by whether the mod can *know* who did it:

- **Attributed exactly** — `ASSEMBLED`, `MAINTENANCE_COMPLETED`. These arrive
  through an interaction with a player attached.
- **Nearby (16 blocks), preferring the recent interactor** — everything else,
  **including cranking and starting**. An engine is turned over with Create's Hand
  Crank, which is Create's block and gives this mod no callback naming the player.
  What there is instead is just as good: the Hand Crank must be *held down,
  adjacent to the engine*, so anybody who cranked one is by construction standing
  next to it. Insisting on exact attribution there would mean never awarding the
  mod's two most important advancements to the person who earned them.

---

## 5. Tooltips

15 items carry one or two grey lines saying what they are for, and nothing else.
Driven entirely by the language file: a line exists if its key exists, numbered
from 1, and the reader stops at the first gap. No player-visible English is
hard-coded in Java.

Worn parts keep their Milestone 13 condition display: a fresh part says nothing
at all, a worn one names its band, and no raw float is ever shown.

---

## 6. Contextual feedback

Action bar, never chat, and only in response to a real interaction — so there is
no tick-by-tick nagging anywhere.

Switching the ignition **on** while the engine is not running says, once, the
first thing that is actually blocking it: no Spark Plug, no Piston Assembly, no
Gasoline — or, when nothing is wrong at all, **"Crank the engine to start it."**
That last line is not a fault; it is the single most useful thing the mod can say
to somebody who has just finished building their first engine.

**Low or missing oil is deliberately not a start blocker**, because the
simulation genuinely permits running dry. Saying "Needs Oil" would be the
interface telling a lie the game does not back up. The goggles carry that as a
*danger* instead, which is what it is.

---

## 7. Goggle readability

Two fixes, both consequences of the Phase A rebalance, both documented in
[`milestone-13-wear-maintenance.md` § 9](milestone-13-wear-maintenance.md):

- **`No Air Filter` is suppressed while there is no Carburetor.** The filter
  mounts on the Carburetor, so an engine without one has nowhere to put a filter,
  and the overlay used to print `Fuel: No Carburetor` and `Wear Risk: No Air
  Filter` together — asking the player to fix the wrong thing. **Show the root
  cause, not the cascade below it.**
- **`Heavy Load` is never shown alone.** At 1.6× on a baseline of thousands of
  hours, an engine hauling a full network is doing its job. It appears only when
  compounding poor lubrication or overspeed.

---

## 8. Accessibility

Nothing is communicated by colour alone. Active cylinders read as `3 / 4` first,
with the `● ● ○ ●` dots as reinforcement; every warning has a name as well as a
colour; conditions are words (`Worn`, `Poor`) and never a bar or a percentage.
Ponder camera motion is slow and there is no flashing anywhere.

---

## 9. The Golden Path

Every step has at least one discoverable in-game information path. Nothing here
requires the README, the source, or an external video.

| # | Step | Found through |
| ---: | --- | --- |
| 1 | Discover Oil Shale | Worldgen; **Black Gold... Sort Of**; tooltip |
| 2 | Produce Crude Oil | Recipes (crushing/milling → mixing); Ponder *From Oil Shale to Fuel* |
| 3 | Produce Gasoline | Recipe; **Refined Taste**; tooltip |
| 4 | Produce Engine Oil | Recipe; **Keep It Slippery**; tooltip |
| 5–12 | Craft the nine components | Recipes, all vanilla or Create types; tooltips |
| 13 | Assemble an Inline-1 | Ponder *Building a Basic Engine*; **Some Assembly Required** |
| 14–15 | Install Piston and Spark Plug | Ponder shows the real interaction; tooltips say "install inside a Cylinder" |
| 16–17 | Fill Gasoline and Engine Oil | Ponder *Fuel and Lubrication* |
| 18 | Hand-crank it | Ponder *Starting an Engine*; action bar: "Crank the engine to start it." |
| 19 | It catches | **First Cranking**, then **It Really Started!** |
| 20 | Connect Create machinery | Ponder; **Mechanical Power** |
| 21 | Adjust throttle | Ponder *Engine Controls*; scroll on the Carburetor |
| 22 | Expand to R2/R3/R4 | Ponder *Inline Engines*; three advancements |
| 23 | Diagnose problems | Ponder *Diagnosing an Engine*; goggles; action bar |
| 24 | Maintain only when necessary | Ponder *Engine Maintenance*; goggles condition line |

### Recipe audit

Every one of the fifteen craftable or refinable things has a recipe, and every
recipe uses a standard type a viewer already understands — `crafting_shaped`,
`create:crushing`, `create:milling`, `create:mixing`, `create:sequenced_assembly`.
There is **no mandatory JEI or EMI dependency** and **no Survival dead end**.

---

## 10. Automated validation

`tools/validate_ux.py`, run by `./gradlew validateUx` and by `check`. It needs no
Maven host, because none of what it checks is compiled.

| Check | What it catches |
| --- | --- |
| Both language files carry the same keys | A key added to one only, which ships untranslated |
| No empty strings | A line that renders as nothing and looks like a bug |
| Advancement parents resolve, exactly one root | A mistyped parent that silently deletes a branch |
| Title and description keys exist | An advancement showing a raw key |
| Frame types are real; criteria are non-empty and all required | An advancement that can never be earned |
| Every filter value parses as a Java enum | `min_condition: "brokwn"` matching nothing, forever |
| Count ranges are satisfiable | `{min: 4, max: 3}` |
| Every scene has its structure, and every structure a scene | A scene that throws when opened |
| Every scene line is keyed in **both** languages | `…ponder.starting_an_engine.text_3` on screen |
| Scene lang matches the Java verbatim | An edited line whose translation silently stayed behind |
| No stale Ponder keys | A translation for a line that no longer exists |
| Structures load, and use real block states | A palette entry the game cannot load |
| Structure geometry matches `EngineComponents` | A tutorial teaching a layout the game refuses |
| **`hideSection` never runs before the base section exists** | **The 1.0.82 client crash: erasing from a base `WorldSectionElement` whose `section` is still null** |
| `hideSection` only hides selections a `showSection` revealed | A hide that does nothing, or an attempt to keep something hidden that was never shown |
| `hideSection` is not mixed with independent sections | Selection-based hiding silently missing an independent element |
| `hideIndependentSection` has an `ElementLink` behind it | A hide aimed at a section that was never created |
| Tooltip lines numbered from 1 without gaps | A line the reader stops before reaching |

`EngineEventTests` covers the rules the validator cannot see: reload safety, the
three named rules above, the sustained-abuse timers, and the maintenance
improvement rule.

**This does not pretend to test Ponder visuals.** Nothing anywhere says a scene
looks right, is paced well, or teaches what it means to.

---

## 11. What was and was not tested

### Actually run

| | |
| --- | --- |
| `./gradlew simulationTest` | ✅ 10 suites, all green |
| `./gradlew checkModels` | ✅ 0 problems |
| `./gradlew validateUx` | ✅ 0 problems |
| `./gradlew build check` (full, with Minecraft/NeoForge/Create) | ✅ in GitHub Actions |
| Generator reproducibility | ✅ re-running all seven changes nothing |

### NOT performed

The development environment for this milestone had **no network access to
`maven.neoforged.net`, `maven.createmod.net` or `maven.ithundxr.dev`**, so
Minecraft, NeoForge and Create could not be resolved locally and the game could
not be launched. All Minecraft-facing code was compiled through GitHub Actions
instead, which is a real compiler but not a running game.

The following are therefore **NOT PERFORMED**, and nothing in this document
should be read as claiming otherwise:

- **Ponder scenes have not been viewed in Minecraft.** They compile, their
  structures load as valid NBT with real block states, every translation key
  resolves, and the section-lifecycle rule that caused the first crash is now
  statically enforced — but nobody has watched one *since the fix*. Camera
  framing, pacing, text overlap and whether the highlights land on the right
  blocks are all unverified, and static analysis cannot execute Ponder.
- **Advancements have not been earned in Minecraft.** The trigger compiles, the
  JSON validates, and the event rules are unit-tested — but no advancement has
  actually fired in a running game.
- **The fresh-Survival flow has not been played.** The Golden Path above is
  derived from the recipes, advancements and scenes that exist, not from someone
  walking it.
- **Multiplayer has not been tested.** Attribution and the nearby-player
  fallback are single-player reasoning.

---

## 12. Remaining player-confusion risks

Honest list of the places a first-time player could still get stuck.

1. **The Hand Crank is Create's, and nothing in this mod points at it.** The
   starting Ponder shows one and the action bar says "crank the engine", but a
   player who has never used Create may not know what to craft. This is the
   single most likely place to lose someone.
2. **Ponder pacing is unverified.** The `idle` durations were chosen by reading,
   not by watching. Some lines may sit too briefly to finish or too long to hold
   attention.
3. **Oil Shale's depth and rarity are not stated anywhere in game.** The
   advancement tells a player to find petroleum; nothing tells them where to dig.
4. **`LOW` oil is hard to encounter deliberately**, because a sump drains through
   it into `DRY`. Its warning may rarely be seen at the moment it would teach the
   most.
5. **Nothing explains the Flywheel's *axis* requirement** except the assembly
   scene's placement. A player who places it 90° off gets a non-engine and no
   message.
6. **The two-Flywheel and fifth-section jokes fire on placement**, which means a
   player who builds the invalid layout in the other order may not see them.
7. **Wear is invisible on the models.** Diagnosis is entirely through goggles and
   Ponder; an engine cannot be read by looking at it.

---

## 13. What this milestone deliberately does NOT add

No oil aging, oil quality, filter clogging, spark-plug wear, temperature, cooling,
four-stroke timing, valves, camshafts, additional layouts, diesel, or forced
induction. Nothing here forecloses any of them, and the lubrication wording was
chosen so that a future oil-condition system does not make it retroactively a lie
— see [`milestone-13-wear-maintenance.md` § 5](milestone-13-wear-maintenance.md).
