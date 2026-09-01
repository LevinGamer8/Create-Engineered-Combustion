# Milestone 14.1 — What the play-test found

The first real in-game play-through of the Ponder scenes got past the 1.0.82
crash and into the part nothing in the build can check: **whether the thing on
screen says what the words say.** Two findings, both about visual language rather
than behaviour, and no gameplay rule changed for either.

> **Nothing in this pass is a simulation change.** One Carburetor, one Oil Sump,
> one Flywheel, one controller, one crankshaft, inline-1 to inline-4, the same
> fuel, oil and capacity semantics. `EngineComponents` resolves an engine from
> crankshaft block states exactly as it did, and would give the same answer if
> every property added here were wrong.

---

## 1. An inline-4 looked like four inline-1s

The architecture was already right — one engine, one `EngineState`, one shared
crankshaft, one of each accessory — and that was the problem: built out of four
repetitions of the same three blocks, an inline-4 **read** as four
one-cylinder engines standing shoulder to shoulder.

Nothing in the geometry crossed a seam. Each bay was a full vertical stack of its
own; the fins of adjacent barrels stopped 1.6 units short of the boundary on both
sides, leaving a visible gap between four separate finned towers; the one
Carburetor sat over one bore with nothing connecting it to the other three; and
every section carried its own ignition switch, so a four-cylinder engine showed
four identical controls in a row.

### The fix is connecting geometry, not more components

Four castings now cross the seams. All of them are cosmetic, and all of them are
driven by block state properties computed from the neighbouring block states
alone.

| What | Where | Says |
| --- | --- | --- |
| **Shared intake manifold** | Cylinder | The one Carburetor is bolted to a rail running the length of the engine, with a runner down into every bore |
| **Continuous cooling fins** and base flange | Cylinder | Adjacent barrels are one casting with several bores, not several barrels |
| **Continuous joint band, pan lip and oil gallery**, and a **main bearing cap** over every seam | Crankshaft | One crankcase, and one lubrication system reaching every bearing |
| **Oil feed risers** up to that gallery | Oil Sump | *This* pan is what feeds it |
| **One ignition switch**, on the controller | Crankshaft | One engine has one ignition — which was always true |

An **inline-1 is untouched**. With nothing to share there is no manifold, and its
Carburetor sits straight down on its head exactly as before; `cylinder.json` is
byte-identical to what it was. R2, R3 and R4 gain the shared castings and get
progressively longer, which is the whole point of the layout.

**Seams stay visible, and so do the bores.** A player can still count the
cylinders and still see the pistons, the crank webs and the connecting rods
moving behind the cutaway windows. The engine is still visibly built out of
modules; it is now visibly built out of modules *of one machine*.

### Why the manifold, specifically

One carburetor feeding four cylinders is not a compromise the mod invented — it
is what a real engine does, through an intake manifold. Without the manifold
drawn, the single Carburetor read as three cylinders with nothing feeding them,
which is the single strongest reason the engine looked like four engines. Drawing
it costs nothing in gameplay: the Carburetor is still one block, still the only
one, and is now visibly bolted to the thing that distributes what it meters.

The manifold lives in the block *above* each cylinder, at the height of the
head's intake flange — empty air on every section except the one carrying the
Carburetor, where the Carburetor's own mounting flange lands inside the rail.

### The Carburetor is not forced to one end

Worth stating because the play-test raised it: nothing in the structure rules
pins the Carburetor to the extreme end of an engine. `EngineComponents.resolve`
walks the cylinders and accepts a Carburetor above **any** of them, and the same
for the Oil Sump. A player can already mount either over the middle bore of an
inline-4. No rule needed changing here — what was missing was the manifold that
makes a Carburetor at one end visibly feed the whole engine, and that is what was
added.

### The Cylinder, Carburetor and Oil Sump now turn with the crank axis

They had no orientation at all. The intake port and its flange are on one flank
of the head and the exhaust boss on the other, and with the block never turned,
an engine running along Z had the intake of each cylinder pointing straight into
the exhaust of the next — and a manifold along the run had nowhere to go.

All three take their axis from the block they are stacked on: the Cylinder from
the crankshaft below, the Carburetor from the Cylinder below, the Oil Sump from
the crankshaft above. Existing engines built along Z will look a quarter turn
different, and correct.

The parts a block entity renderer draws are **not** turned by a blockstate — the
same rule the connecting rod has always followed — so the Spark Plug, the Air
Filter and the throttle lever gain a quarter-turned copy each, produced from the
X original by `rotate_y90` in the generator rather than authored twice. The spark
particle makes the same turn, or it would fire beside the plug instead of at it.

### Old worlds

The properties are maintained by `updateShape`, which vanilla only calls when a
neighbour changes — so an engine already standing in a save would keep the look
it was saved with until the player happened to disturb a block next to it.
`CrankshaftBlockEntity` knits its own section and its stack once, on the first
tick after it loads. Four positions, once per section per load, and the write is
skipped entirely when the state is already right.

### Animation and performance

Every piece of this is static baked geometry. The manifold sits above the head,
clear of the bore; the crankcase castings sit outside the cavity the crank sweeps
(`checkModels` re-derives that clearance from `CrankMath` and swings the real
piston through a full revolution against every fixed element). Nothing new is
drawn per frame, nothing new scans the world, and no renderer gained work beyond
choosing between two partial models by a block state it already had in hand.

---

## 2. Ponder pointed at the wrong things

The words were fine. The **visual target** was not.

The line *"An Air Filter is optional. It protects the cylinders from long-term
wear"* drew a box around most of an engine. The line about two Flywheels drew
**one** box spanning both of them *and the crankshaft between them*, so the part
being called invalid was outlined together with a part that is perfectly fine.

Both compiled. Both had correct English and German. Both had a schematic that
loaded. And a Ponder scene teaches by pointing, so both taught something false.

### The cause was hand-written coordinates

Every scene carried its own `new BlockPos(3, 4, 2)` constants. "The Carburetor"
was a number somebody had worked out once, and a highlight could be a block off
without anything noticing: a schematic is a binary file, and a wrong outline
still compiles.

`PonderEngine` now holds one scene's engine — origin, axis, section count, which
section carries the accessories — and derives every position from the layout
`EngineComponents` enforces. A scene asks for `ENGINE.carburetor()` and gets the
Carburetor. If a scene's engine ever moves, every highlight in it moves too.

It also names the parts **smaller than a block**, because several of these
sentences are about one: the Spark Plug standing above the head, the Air Filter
on the air horn, the throttle lever on its shaft, the float bowl's sight window,
the dipstick, the ignition switch on the crankcase flank. A step can now outline
the block *and* point at the part inside it, which is what "highlight the Air
Filter" actually requires when the Air Filter is not a block.

### Every step was re-read against one question

*What physical object is this sentence teaching?*

| Line | Now marks |
| --- | --- |
| Air Filter is optional | the Carburetor it clamps to, pointing at the filter |
| One engine uses one Flywheel | two outlines, one per Flywheel — no crankshaft between them |
| Gasoline goes into the Carburetor | the Carburetor, pointing at the float bowl |
| Engine Oil goes into the Oil Sump | the Sump, pointing at the dipstick |
| Each cylinder fires at a different point | each of the four bores, separately |
| One crankshaft, one Carburetor, one Oil Sump, one Flywheel | those four, and the row of cylinders |
| Cylinder 3 Compression: Poor | cylinder 3, and only cylinder 3 |
| Sustained overspeed wears it quickly | the Flywheel, which is where an outside machine is connected |

Where a step is genuinely about a **relationship** — a filter protecting the
bores, combustion turning the crank, redstone reaching both the ignition and the
throttle — both ends are outlined *separately*, rather than as one box with
everything in between inside it. Where a step really is about the whole machine —
*"this engine is mechanically complete"*, what the goggles read — one box around
the engine is the honest highlight, and those are now the only steps that have
one.

### The fuel chain stopped pointing at the same block five times

`from_shale_to_fuel` has one block in its structure and five steps about
materials, so every line pointed at the Oil Shale. Each step now holds up the
item it is about, laid out left to right along the plate so the chain reads as a
chain, with cracking showing both of its products at once.

### And the scenes are staged on the final engine

A Ponder structure is loaded as-is and never sees a neighbour update, so the
generator writes the cosmetic block states out itself, from the same run it is
already stamping. The inline scene shows an engine with its shared manifold and
crankcase — the one the player will actually build — rather than the one that has
been replaced.

---

## 3. What now fails the build

Two new checks, both for failure modes that previously could only be found by
opening the game.

| Check | What it catches |
| --- | --- |
| **Every Ponder highlight lands on the block it names** | `ENGINE.carburetor()` pointing at empty air, an engine whose scene and schematic disagree, an index outside the engine |
| Seam geometry of a middle bay of an inline-4 | Coplanar faces between two adjacent sections' castings — the shimmer a shared manifold or a crossing fin would otherwise introduce |

The highlight check resolves all **209** targets across the eight scenes and
compares each against that scene's own structure file. Both halves were verified
by breaking them on purpose: moving a scene's engine one block, and removing a
Flywheel the scene highlights. Both fail, with the position and the block that is
actually there.

`tools/preview_engine.py` also renders whole inline engines now rather than only
an inline-1, which is how the multi-cylinder work above was looked at without a
client — `python3 tools/preview_engine.py <dir>` writes R1 to R4 from two angles.

---

## 4. What is still untested

The same honest limit as the milestone itself, and it is the reason this pass
exists at all: **nothing here can say a scene looks right.** The checks say every
highlight is on a real block of the right kind; they cannot say the outline reads
clearly, that the pacing works, or that the engine looks like one machine. That
is a play-test, and the visual matrix (R1–R4, the Carburetor feeding all
cylinders, the shared crankcase, the Flywheel terminating one end, animation
clearance) has to be walked in a running game.
