# Milestone 8 - Survival Foundation

The point at which the mod stops being a technical prototype. Before this the
engine could only be built in Creative: five of its components had no recipe at
all, and both of its fluids were unobtainable by any means. Now every part of a
working engine can be made, and the fuel and oil it burns come out of the ground.

Nothing in the established engine changed. Power, RPM, throttle physics, fuel
consumption, oil consumption, the flywheel resolver, the control architecture and
every sound are exactly as milestone 7 left them. The one behavioural change is
deliberate and is the subject of part 1.

## 1. The Spark Plug became a component

It used to be eight cuboids inside `cylinder.json` - decoration that every
cylinder had for free. It is now an item you make, install, can take back out,
and without which the engine will not run.

### Ignition, in two gates

The rule the simulation enforces, in `EngineState#tickSimulation`:

```
assembled + ignition on + spark plug   ->  a spark may happen
spark + a charge that can be paid for  ->  combustion may happen
```

The order is load-bearing and is asserted by a test. **Fuel must never be what
decides whether the plug sparks**, because the two failures are meant to be
distinguishable by looking at the engine:

| what you see | what is wrong |
| --- | --- |
| plug sparks, engine will not catch | no fuel |
| no spark at all, engine turns over | no plug, or ignition off |

A missing plug is explicitly *not* a structural fault.
`EngineComponents#isMechanicallyValid` still means "can this thing turn", and an
engine with no plug turns perfectly - any other Create source will motor it, the
piston and rod animate, the flywheel drives its network. It simply never fires.
Folding the plug into structural validity would have stopped the crank, which is
the wrong failure to show a player.

### Installing and removing

Both of the cylinder's parts are items installed into the placed block, the same
pattern the Piston Assembly already used and the Air Filter and Redstone Control
Module use elsewhere.

* **install** - right-click the Cylinder holding a Spark Plug, anywhere on the
  block. One is consumed, except in Creative.
* **remove** - sneak + right-click with an empty hand.

The Cylinder now holds *two* removable parts, so the gesture needs to say which.
It resolves the way the machine is built rather than by an arbitrary rule:

* with only one part fitted, the gesture removes that part wherever you clicked -
  a player who has fitted only a piston should never have to aim;
* with both fitted, **the top two units of the block are the head** and take the
  plug; anything lower is the barrel and takes the piston. That is the same
  y = 14 the head slab starts at in the model, so the interaction area is the
  casting the plug is visibly screwed into.

A removed part goes to the player's inventory, or drops at the block if the
inventory is full. Breaking the Cylinder drops both parts - `onRemove` pops them
explicitly, because a loot table cannot see block entity state.

### Persistence

`CylinderBlockEntity` stores `SparkPlugInstalled` alongside `PistonInstalled`
and both go through `saveAdditional`, which `getUpdateTag` also uses. So one flag
covers save/reload, chunk unload, server restart *and* client synchronisation,
with no separate packet. A cylinder saved before this milestone reads the key as
absent, i.e. false: existing worlds load with no plug fitted, which is correct -
nobody has ever installed one.

The crankshaft additionally carries a copy of the flag in its own synchronised
data. That copy is for the client overlays only; the server overwrites it from
the world on the next tick.

### What the HUD says

Deliberately three different amounts of detail, so the running HUD stays quiet:

* **Cylinder, with goggles** - the inventory of installed parts. `Piston
  Assembly`, `Spark Plug`, `Piston Position`. This is where a player goes to ask
  what is fitted, so both parts are always listed, present or not.
* **Crankshaft, with goggles** - `Spark Plug: Missing` **only when it is
  missing**, next to the flywheel-conflict warning. A line saying a plug is
  fitted would appear on every working engine forever; the fault is what belongs
  on the overlay you read when the engine will not start. Sneak still shows it
  either way, with the diagnostics.
* **Crankshaft, no goggles** - `Spark Plug: Missing` only when it is missing
  *and* the ignition is on. Someone who has switched the ignition on is trying to
  run the engine and otherwise has nothing at all to explain why it never fires.
  With the ignition off, an absent plug is not yet their problem.

### Rendering

The plug moved from `cylinder.json` into its own `spark_plug.json`, drawn by
`CylinderRenderer` only when one is installed - the same arrangement as the Air
Filter on the Carburetor. The head keeps its boss either way, because the boss is
the threaded seat cast into the head and is there whether or not anything is
screwed into it.

`check_models.py` follows the split: the plug is now checked against the piston's
swept volume *as a separate model*, and is in the assembled-engine seam check and
the sideways-reach check. Splitting a model must not be what quietly stops a
clearance from being enforced - the electrode still clears the crown at top dead
centre by 0.10 units.

## 2. Survival recipes

Every registered item and block is now obtainable. The tier is andesite to early
brass throughout: everything below needs at most a **Mechanical Press**, a
**Deployer**, and for the fuel chain a **Millstone** (or Crushing Wheels), a
**Basin + Mechanical Mixer** and a **Blaze Burner**. Nothing needs a Mechanical
Crafter, a Precision Mechanism or any nether material.

Recipes are ordinary datapack files under `data/engineered_combustion/recipe/`,
so they all appear in JEI and every other recipe viewer. No progression is hidden
behind a right-click.

### Normal crafting

| | shape | materials |
| --- | --- | --- |
| **Crankshaft** | `AIA` / `SCS` / `AIA` | 4 Andesite Alloy, 2 Iron Sheet, 2 Shaft, 1 Andesite Casing |
| **Cylinder** | `AIA` / `IBI` / `AIA` | 4 Andesite Alloy, 4 Iron Sheet, 1 Block of Iron |
| **Flywheel** | `III` / `ISI` / `III` | 8 Iron Sheet, 1 Shaft |
| **Oil Sump** | `I I` / `III` | 5 Iron Sheet |
| **Air Filter** | `IWI` / `_N_` | 2 Iron Sheet, 1 Wool, 1 Iron Nugget |
| **Spark Plug** | `C` / `Q` / `I` | 1 Copper Ingot, 1 Quartz, 1 Iron Sheet |
| **Redstone Control Module** | `BRB` / `RER` / `BRB` | 4 Brass Sheet, 4 Redstone, 1 Electron Tube |

The shapes are meant to be read: a hollow centre is a bore, a part on each side
is a shaft end, a sheet top and bottom is a deck and a sump face. The Spark
Plug's pattern is terminal / insulator / body, top to bottom, exactly as the item
is drawn.

Every metal is taken through a `c:` tag (`c:plates/iron`, `c:nuggets/zinc`, …),
so another mod's iron sheet works as well as Create's.

### Create processing

| | machine | recipe |
| --- | --- | --- |
| Iron / brass / copper sheets | Mechanical Press | Create's own `create:pressing` |
| **Piston Assembly** | Depot + Press + Deployer | `create:sequenced_assembly` |
| **Carburetor** | Depot + Press + Deployer | `create:sequenced_assembly` |
| Oil Shale → Crushed | Crushing Wheels | `create:crushing` |
| Oil Shale → Crushed | Millstone | `create:milling` |
| Crushed + water → Crude Oil | Basin + Mixer + heat | `create:mixing` |
| Crude Oil → Gasoline + Residue | Basin + Mixer + heat | `create:mixing` |
| Residue + zinc → Engine Oil | Basin + Mixer + heat | `create:mixing` |

### The two sequenced assemblies

Two, not seven. A sequenced assembly is worth it where the part really is a
machined assembly of several pieces; everywhere else it is ceremony.

**Piston Assembly** - base `c:plates/iron`, 2 loops:
deploy Andesite Alloy (rod stock) → press (forge the body) → deploy Iron Nugget
(wrist pin). Six operations, a third of what a Precision Mechanism costs.

**Carburetor** - base `c:plates/brass`, 1 loop:
deploy Copper Sheet (float bowl) → press (form the body and venturi) → deploy
Fluid Pipe (fuel fitting).

Each carries its own transitional item, `incomplete_piston_assembly` and
`incomplete_carburetor`, because Create stores assembly progress against the
recipe on the item itself - two recipes sharing one would each see the other's
half-finished work.

### Buckets have no recipe, on purpose

Every fluid registers its bucket through `BaseFlowingFluid.Properties#bucket`, so
NeoForge's bucket capability fills a vanilla bucket from any tank, pipe, Create
Spout or Item Drain holding the fluid, and empties it back. A crafting recipe on
top would put a second, worse route in JEI beside the one that already works.

> All three buckets are plain `BucketItem`s and must stay that way. NeoForge
> attaches the fluid-handler capability only to items whose class is *exactly*
> `BucketItem`, and Create re-checks the same thing in
> `GenericItemFilling#isFluidHandlerValid`. A subclass - however small the
> override - would silently lose Spout and Item Drain support and the ability to
> fill a Carburetor from a bucket at all.

## 3. Petroleum

A small, deliberately unambitious chain. It exists so the mod can be played
without Create: Diesel Generators or TFMG, not to compete with a refinery mod.

```
Oil Shale  --crushing--> 2 Crushed Oil Shale   (Millstone: 1)
2 Crushed + 500 mB water --heated mixing--> 500 mB Crude Oil
1000 mB Crude Oil --heated mixing--> 600 mB Gasoline + 1 Petroleum Residue
2 Residue + 1 Zinc Nugget --heated mixing--> 500 mB Engine Oil
```

Read as a balance sheet, four Oil Shale blocks give **1200 mB of gasoline and
500 mB of engine oil**: 1700 mB of product out of 2000 mB of crude. The missing
300 mB is refining loss. **No recipe here produces more fluid than it consumed**,
which was the one hard constraint on the design.

Two design notes:

* **Cracking is one recipe with two outputs, one fluid and one item.** The light
  fraction leaves as gasoline; the heavy bottoms stay behind as a solid residue.
  Two *fluid* outputs would have needed two drains on one basin and reads badly
  in JEI, where an item output simply falls out.
* **Engine Oil takes one step more than gasoline**, which is what makes it feel
  like a manufactured lubricant. Zinc is not decorative: the anti-wear additive
  in real motor oil is a zinc compound, and zinc is Create's own early metal.

This is a gameplay abstraction and is not claimed to be otherwise - real shale
retorting is a dry pyrolysis and produces no water at all. What the abstraction
buys is a chain built entirely out of Create machines a player already knows.

The Millstone route exists so the chain is reachable *before* brass: Crushing
Wheels need a Mechanical Crafter, a Millstone needs an Andesite Casing. Half the
yield for a much earlier start, which is Create's own flour pattern.

### Oil Shale

A plain block that drops itself - there is no raw material item, because the
block *is* the input to the crusher, and that removes any need for a silk-touch
or fortune branch in the loot table. Needs a stone pickaxe
(`minecraft:needs_stone_tool`), so petroleum cannot be punched out of the ground.

Deliberately **not** in `c:ores`. An ore-doubling mod that saw it there would
either duplicate the block outright or fail to find a smelting result for it. It
is a rock that gets processed, not an ore that gets refined.

Worldgen is fully data-driven, as 1.21.1 wants: a configured feature, a placed
feature and a `neoforge:add_features` biome modifier. No `BiomeLoadingEvent`, no
`addFeature` call from Java.

| | | vs Create's zinc |
| --- | --- | --- |
| vein size | 12 | same |
| veins per chunk | 5 | 8 |
| height | uniform y -40 … 24 | -63 … 70 |
| discarded on air exposure | 50 % | 0 % |
| step / biomes | `underground_ores`, `#minecraft:is_overworld` | same |

Rarer than zinc in every dimension, and the air-exposure discard is what stops it
lining cave walls. The block replaces both stone and deepslate with the same
state: oil shale is a sedimentary rock and a deepslate variant would be
geologically silly, but targeting only `stone_ore_replaceables` would leave the
deeper half of the band silently empty.

## 4. Fluid compatibility

The engine has never compared against a `Fluid` instance and still does not.
`ECFluidTags` is now the single place a class of fluid is named, and the tags are
built in two layers:

```
engineered_combustion:gasoline    ->  #c:gasoline    ->  our gasoline
engineered_combustion:engine_oil  ->  #c:engine_oil  ->  our engine oil
engineered_combustion:crude_oil   ->  #c:crude_oil   ->  our crude oil
```

That indirection is the whole compatibility story, and it gives a pack two doors.
Joining the `c:` tag makes a fluid work here **and** in every other mod reading
the same convention; joining the `engineered_combustion:` tag makes it work here
only, which is the right door when a fluid is acceptable to this engine but is
not really the conventional thing.

The Carburetor asks `EngineFuel`, the Oil Sump asks `EngineLubricant`, and both
now resolve through `ECFluidTags`. **The refining recipes take the crude oil
tag, not our fluid** - feed the cracker another mod's crude and it refines that,
with nothing in this mod mentioning it.

On the `c:` names: NeoForge's `Tags.Fluids` for 1.21.1 covers water, lava, milk,
honey, potions, the soups, experience and gases. There is no petroleum convention
to follow and none to violate. Create publishes its own fluids the same way,
under `c:honey`, `c:chocolate` and `c:tea`, so a `c:` tag named after the fluid
is the established convention rather than an invention - and defining them
ourselves means they always exist.

**No external mod compatibility was added.** Create: Diesel Generators and TFMG
are not dependencies, are not on this project's classpath, and their 1.21.1
fluid identifiers could not be verified here. A guessed tag entry silently
matches nothing, which is strictly worse than a documented absence. The
integration point is the tags above.

## 5. Art

**Buckets.** All three were regenerated, and the difference between them is now
structural rather than a tint. `bucket()` takes a `body` parameter from 0 to 1:

* a **thin** fluid is lit right through - bright surface, a strong sheen down the
  lit wall, a long smooth depth gradient. You can see into it. Gasoline: 0.1.
* a **thick** fluid stops light at the surface - the surface is barely brighter
  than the body, the sheen collapses to one specular pixel on the meniscus, and
  it goes dark immediately below the rim. Engine Oil: 0.6. Crude Oil: 1.0.

That is what makes two dark browns distinguishable in a hotbar. The palettes moved
too: gasoline from a saturated yellow that read as lemonade to a pale straw;
engine oil warmer, so it is amber-brown rather than olive; crude near-black with
just enough brown left to keep it from reading as a hole in the world.

**New icons.** Spark Plug, Crushed Oil Shale, Petroleum Residue and both
transitional items are flat 16×16 sprites rather than geometry. A spark plug
rendered as cuboids at inventory scale is a grey stick; drawn, the stack of
widths - terminal, ribbed insulator, wide spanner hex, threaded shell, electrode
- *is* the silhouette, and the hex being the widest thing in the sprite is what
makes it recognisable at that size.

**Retuned icons.** Three existing ones were looked at again and turned rather
than redrawn:

* **Piston Assembly** to 15° of pitch. At the standard 30° the crown filled the
  slot and hid the rod, so it was the same picture as a bare piston.
* **Air Filter** to 20°, scaled up, so the mesh band around its side shows
  instead of the flat lid.
* **Redstone Control Module** to 40°, so the board's face is towards the camera
  rather than edge-on.

**Oil Shale** is drawn at 16×16, not the 32 the engine castings use: every
neighbour it ever has is a vanilla stone texture, and an ore at twice the texel
density of the rock around it reads as a sticker. It is drawn as dark laminae
rather than as scattered blobs, because the petroleum in shale *is* the layering
- and that is also what keeps it from being mistaken for coal ore in a cave.

## 6. A full Survival route

1. Mine **Oil Shale** underground with a stone pickaxe.
2. **Millstone** or **Crushing Wheels** → Crushed Oil Shale.
3. **Basin + Mechanical Mixer + Blaze Burner**, with water → **Crude Oil**.
4. Same setup, crude alone → **Gasoline** + **Petroleum Residue**.
5. Same setup, residue + a zinc nugget → **Engine Oil**.
6. **Mechanical Press** for sheets; craft Crankshaft, Cylinder, Flywheel, Oil
   Sump, Air Filter, Spark Plug, and the Redstone Control Module if wanted.
7. **Depot + Press + Deployer** for the Piston Assembly and the Carburetor.
8. Build the stack, install the Piston Assembly and the Spark Plug, fit the Air
   Filter, fill the Carburetor with gasoline and the Oil Sump with engine oil.
9. Ignition on, hand-crank, run.

## 7. Testing

`tools/SparkPlugTests.java` compiles and runs `EngineState` with nothing but a
JDK - the payoff for keeping the simulation free of Minecraft types. It asserts
tests 1 to 3 of this milestone plus four regressions, including the one that
matters most: **one spark per revolution regardless of fuel**, measured per
revolution rather than per tick, because a fuelled engine that has caught turns
faster and would otherwise appear to spark "more".

```
javac -d /tmp/ec-sim $(ls src/main/java/dev/engineeredcombustion/content/engine/*.java \
                        | grep -v EngineComponents)
javac -cp /tmp/ec-sim -d /tmp/ec-sim tools/SparkPlugTests.java
java  -cp /tmp/ec-sim SparkPlugTests
```

## 8. Balance is explicitly not final

Every number that a balance pass would touch lives at the top of
`tools/generate_survival_data.py`, in one `YIELDS` block and one worldgen block.
Change a constant, re-run, commit what it wrote. A yield that appears in two
hand-written recipe files drifts; a yield that appears in one constant cannot.

Nothing in `EngineTuning` was touched. Power, RPM, throttle physics, gasoline
consumption and oil consumption are exactly as they were.
