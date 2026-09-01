#!/usr/bin/env python3
"""Generates every datapack file the Survival progression needs.

Recipes, fluid tags, block tags, the Oil Shale loot table and the Oil Shale
worldgen, all from the tables at the top of this file. It is a generator rather
than thirty hand-written JSON files for one reason: **the balance numbers only
exist once.** A yield that appears in two recipes drifts; a yield that appears
in one Python constant referenced by two recipes cannot.

The milestone brief asks explicitly for the important numbers to be centralised
and documented rather than perfectly balanced, so this is where the dedicated
balance pass will happen: change a constant in the YIELDS section, re-run, commit
what it wrote.

    python3 tools/generate_survival_data.py

Nothing here is guessed. Every Create recipe type, every field name and every
`c:` tag used below was read out of Create 6.0.10's own published recipes and
codecs (`ProcessingRecipeParams`, `SequencedAssemblyRecipeSerializer`) and
NeoForge 1.21.1's `Tags.Fluids`. See docs/milestone-8.md.
"""
import json
import os
import pathlib

DATA = pathlib.Path(__file__).resolve().parents[1] / "src/main/resources/data"
NS = "engineered_combustion"


def me(path):
    return f"{NS}:{path}"


# ===========================================================================
# YIELDS - the whole balance surface of this milestone, in one place
# ===========================================================================
# Ratios first, because those are what a balance pass actually tunes.
#
# One Oil Shale block through Crushing Wheels and the retort is 500 mB of crude;
# through a Millstone it is 250. Two blocks is therefore one full refining cycle,
# which is the unit everything below is sized in:
#
#     2 Oil Shale -> 4 Crushed -> 1000 mB Crude -> 600 mB Gasoline + 1 Residue
#     2 Residue + 1 zinc nugget                 ->  500 mB Engine Oil
#
# So four Oil Shale blocks yield 1200 mB of gasoline and 500 mB of engine oil:
# 1700 mB of product from 2000 mB of crude. The missing 300 mB is refining loss,
# and it is deliberate - no recipe here may produce more fluid than it consumed.
CRUSHED_PER_SHALE_CRUSHING = 2      # Crushing Wheels: the better route
CRUSHED_PER_SHALE_MILLING = 1       # Millstone: available far earlier, half as good
CRUSHED_BONUS_CHANCE = 0.25
SHALE_COAL_CHANCE = 0.10            # kerogen that came out solid

CRUSHED_PER_RETORT = 2
WATER_PER_RETORT_MB = 500
CRUDE_PER_RETORT_MB = 500

CRUDE_PER_CRACK_MB = 1000
GASOLINE_PER_CRACK_MB = 600
RESIDUE_PER_CRACK = 1

RESIDUE_PER_BLEND = 2
ENGINE_OIL_PER_BLEND_MB = 500

# Processing times, in ticks. Create's own ores crush in 250 and its wheat mills
# in 150; petroleum is deliberately slower than either.
CRUSHING_TIME = 250
MILLING_TIME = 350

# --- worldgen --------------------------------------------------------------
# Conservative on purpose, and explicitly a first pass. Zinc - Create's own
# overworld ore - is count 8, size 12, y -63..70 with no air-exposure discard.
# Oil Shale is rarer than that in every dimension: fewer veins per chunk, a
# narrower and deeper band, and half of any vein that breaks into a cave is
# thrown away, which is what stops it lining cave walls.
SHALE_VEIN_SIZE = 12
SHALE_VEINS_PER_CHUNK = 5
SHALE_AIR_DISCARD = 0.5
SHALE_MIN_Y = -40
SHALE_MAX_Y = 24


# ===========================================================================
# ingredient shorthands
# ===========================================================================
def item(id):
    return {"item": id}


def tag(id):
    return {"tag": id}


def result(id, count=None, chance=None):
    """One output. `count` is omitted when it is 1 and `chance` when it is
    certain, so the written JSON matches the shape Create's own generator emits
    rather than carrying defaults around."""
    out = {"id": id}
    if count is not None and count != 1:
        out["count"] = count
    if chance is not None:
        out["chance"] = chance
    return out


def fluid(id, amount):
    """A fluid ingredient naming one exact fluid."""
    return {"type": "neoforge:single", "amount": amount, "fluid": id}


def fluid_tag(id, amount):
    """A fluid ingredient naming a tag, i.e. anything a pack has declared to be
    this kind of fluid. Every petroleum input in this file uses one."""
    return {"type": "neoforge:tag", "amount": amount, "tag": id}


def fluid_result(id, amount):
    return {"amount": amount, "id": id}


# Create materials, named once. `c:` tags wherever one exists, so another mod's
# iron sheet or brass nugget works exactly as well as Create's.
IRON_SHEET = tag("c:plates/iron")
COPPER_SHEET = tag("c:plates/copper")
BRASS_SHEET = tag("c:plates/brass")
IRON_NUGGET = tag("c:nuggets/iron")
ZINC_NUGGET = tag("c:nuggets/zinc")
IRON_BLOCK = tag("c:storage_blocks/iron")
COPPER_INGOT = tag("c:ingots/copper")
QUARTZ = tag("c:gems/quartz")
REDSTONE = tag("c:dusts/redstone")
WOOL = tag("minecraft:wool")
ANDESITE_ALLOY = item("create:andesite_alloy")
ANDESITE_CASING = item("create:andesite_casing")
SHAFT = item("create:shaft")
COGWHEEL = item("create:cogwheel")
FLUID_PIPE = item("create:fluid_pipe")
ELECTRON_TUBE = item("create:electron_tube")


def shaped(pattern, key, out, count=1):
    return {"type": "minecraft:crafting_shaped", "category": "misc",
            "key": key, "pattern": pattern,
            "result": {"count": count, "id": out}}


def processing(kind, ingredients, results, time=None, heat=None):
    recipe = {"type": "create:" + kind, "ingredients": ingredients}
    if heat:
        recipe["heat_requirement"] = heat
    if time is not None:
        recipe["processing_time"] = time
    recipe["results"] = results
    return recipe


def deploy(held, on):
    """One Deployer step of a sequenced assembly: apply `held` to the work in
    progress and hand the work in progress back."""
    return {"type": "create:deploying", "ingredients": [item(on), held],
            "results": [result(on)]}


def press(on):
    return {"type": "create:pressing", "ingredients": [item(on)],
            "results": [result(on)]}


def sequenced(base, transitional, sequence, out, loops=1):
    return {"type": "create:sequenced_assembly", "ingredient": base,
            "transitional_item": result(transitional), "loops": loops,
            "sequence": sequence, "results": [result(out)]}


# ===========================================================================
# THE ENGINE'S COMPONENTS
# ===========================================================================
# Tier: andesite to early brass. Every one of these is reachable with a
# Mechanical Press (andesite casing + shaft + iron block) and, for the two
# sequenced assemblies, a Deployer. Nothing needs a Mechanical Crafter, a
# Precision Mechanism, or any nether material - which is the whole point of
# putting the first engine where a player actually is when they want one.
#
# The shapes are meant to be read. A hollow centre is a bore, a part on each
# side is a shaft end, a sheet top and bottom is a deck and a sump face.
INCOMPLETE_PISTON = me("incomplete_piston_assembly")
INCOMPLETE_CARBURETOR = me("incomplete_carburetor")

RECIPES = {
    # --- the stack ---------------------------------------------------------
    # Cast case, a crank with a shaft out of each end, machined faces top and
    # bottom. The casing is Create's own crankcase-shaped part and is what puts
    # this at andesite tier rather than below it.
    "crafting/crankshaft": shaped(
        ["AIA", "SCS", "AIA"],
        {"A": ANDESITE_ALLOY, "I": IRON_SHEET, "S": SHAFT, "C": ANDESITE_CASING},
        me("crankshaft")),

    # Substantial cast and machined metal, with the bore left open in the
    # middle of the pattern. Thirteen-odd ingots of iron all told, which is
    # what "substantial" costs.
    "crafting/cylinder": shaped(
        ["AIA", "IBI", "AIA"],
        {"A": ANDESITE_ALLOY, "I": IRON_SHEET, "B": IRON_BLOCK},
        me("cylinder")),

    # Heavy and mechanically simple, and the recipe says exactly that: a rim of
    # pressed iron around a shaft, no precision parts anywhere in it.
    "crafting/flywheel": shaped(
        ["III", "ISI", "III"],
        {"I": IRON_SHEET, "S": SHAFT},
        me("flywheel")),

    # The cheap one. A pressed pan, open at the top, and nothing else.
    "crafting/oil_sump": shaped(
        ["I I", "III"],
        {"I": IRON_SHEET},
        me("oil_sump")),

    # --- parts that install into the stack ---------------------------------
    # Housing, filter media, clamp.
    "crafting/air_filter": shaped(
        ["IWI", " N "],
        {"I": IRON_SHEET, "W": WOOL, "N": IRON_NUGGET},
        me("air_filter")),

    # Terminal, insulator, threaded body - drawn in the pattern in that order,
    # top to bottom, exactly as the item is drawn. Quartz stands in for the
    # ceramic; a whole ceramic industry for one part would be the wrong trade
    # in this milestone, and the brief says so.
    "crafting/spark_plug": shaped(
        ["C", "Q", "I"],
        {"C": COPPER_INGOT, "Q": QUARTZ, "I": IRON_SHEET},
        me("spark_plug")),

    # The engine's other shaft, and the recipe says so: it is the Crankshaft's own
    # pattern with the casing swapped for a Cogwheel. That Cogwheel IS the timing
    # drive - the reason there is no separate Timing Gear item - and swapping it in
    # for the casing is what makes the two shafts read as a pair rather than as two
    # unrelated parts at the same tier.
    #
    # Same tier as the engine internals, nothing from this mod in it, so it is
    # craftable before an engine has ever run. That matters: an engine cannot run
    # without one, and a component gated behind a running engine would be a
    # circular dependency the player could not break.
    "crafting/camshaft": shaped(
        ["AIA", "SCS", "AIA"],
        {"A": ANDESITE_ALLOY, "I": IRON_SHEET, "S": SHAFT, "C": COGWHEEL},
        me("camshaft")),

    # The expensive one, and deliberately: redstone control is optional
    # automation on an engine that runs perfectly without it, so it is the one
    # component allowed to want an Electron Tube.
    "crafting/redstone_control_module": shaped(
        ["BRB", "RER", "BRB"],
        {"B": BRASS_SHEET, "R": REDSTONE, "E": ELECTRON_TUBE},
        me("redstone_control_module")),

    # --- sequenced assemblies ----------------------------------------------
    # Two, not seven. A sequenced assembly is worth it where the part is a
    # genuinely machined assembly of several pieces; everywhere else it is
    # ceremony, and the brief warns against exactly that.
    #
    # Piston Assembly: a pressed blank gets rod stock deployed onto it, is
    # forged, and takes its wrist pin. Two loops - six operations - which is a
    # third of what a Precision Mechanism costs.
    "sequenced_assembly/piston_assembly": sequenced(
        IRON_SHEET, INCOMPLETE_PISTON,
        [deploy(ANDESITE_ALLOY, INCOMPLETE_PISTON),
         press(INCOMPLETE_PISTON),
         deploy(IRON_NUGGET, INCOMPLETE_PISTON)],
        me("piston_assembly"), loops=2),

    # Carburetor: brass body, copper float bowl, then the fuel fitting. One
    # loop, because the brief asks for a *short* sequence and because three
    # deployer steps on a brass sheet is already the shape of the part.
    "sequenced_assembly/carburetor": sequenced(
        BRASS_SHEET, INCOMPLETE_CARBURETOR,
        [deploy(COPPER_SHEET, INCOMPLETE_CARBURETOR),
         press(INCOMPLETE_CARBURETOR),
         deploy(FLUID_PIPE, INCOMPLETE_CARBURETOR)],
        me("carburetor"), loops=1),

    # ======================================================================
    # THE PETROLEUM CHAIN
    # ======================================================================
    # Four recipes, and every one of them is an ordinary Create machine. There
    # is no refinery block in this mod and this milestone does not add one.
    #
    # 1. Oil Shale -> Crushed Oil Shale. Two routes on purpose: the Millstone
    #    is available at andesite tier and gives one, the Crushing Wheels need a
    #    Mechanical Crafter and give two. That is Create's own flour pattern,
    #    and it is what keeps the fuel chain reachable before brass.
    "crushing/oil_shale": processing(
        "crushing", [item(me("oil_shale"))],
        [result(me("crushed_oil_shale"), count=CRUSHED_PER_SHALE_CRUSHING),
         result(me("crushed_oil_shale"), chance=CRUSHED_BONUS_CHANCE),
         result("minecraft:coal", chance=SHALE_COAL_CHANCE)],
        time=CRUSHING_TIME),

    "milling/oil_shale": processing(
        "milling", [item(me("oil_shale"))],
        [result(me("crushed_oil_shale"), count=CRUSHED_PER_SHALE_MILLING)],
        time=MILLING_TIME),

    # 2. The retort: crushed shale, water and heat give up their petroleum.
    #    Heated rather than superheated, so a Blaze Burner on ordinary fuel is
    #    enough and the chain does not additionally gate on a Blaze Cake.
    #
    #    This is a gameplay abstraction and is not claimed to be otherwise -
    #    real shale retorting is a dry pyrolysis and produces no water at all.
    #    What it buys is a Create process a player already knows how to build.
    "mixing/crude_oil": processing(
        "mixing",
        [item(me("crushed_oil_shale"))] * CRUSHED_PER_RETORT
        + [fluid("minecraft:water", WATER_PER_RETORT_MB)],
        [fluid_result(me("crude_oil"), CRUDE_PER_RETORT_MB)],
        heat="heated"),

    # 3. Cracking. One input, two outputs, one machine: the light fraction
    #    leaves as gasoline and the heavy bottoms stay behind as residue. An
    #    item and a fluid rather than two fluids, deliberately - a basin with
    #    two fluid outputs needs two drains and reads terribly in JEI, where an
    #    item output just falls out of the basin.
    #
    #    The input is the *tag*, not this mod's fluid. Feed it another mod's
    #    crude oil and it refines that instead, with nothing here mentioning it.
    "mixing/gasoline": processing(
        "mixing", [fluid_tag(me("crude_oil"), CRUDE_PER_CRACK_MB)],
        [fluid_result(me("gasoline"), GASOLINE_PER_CRACK_MB),
         result(me("petroleum_residue"), count=RESIDUE_PER_CRACK)],
        heat="heated"),

    # 4. Blending. The heavy fraction plus an anti-wear additive becomes a
    #    finished lubricant, which is why oil takes one more step than fuel.
    #    Zinc is not a decorative choice: the anti-wear additive in real motor
    #    oil is a zinc compound, and zinc is Create's own early metal.
    "mixing/engine_oil": processing(
        "mixing",
        [item(me("petroleum_residue"))] * RESIDUE_PER_BLEND + [ZINC_NUGGET],
        [fluid_result(me("engine_oil"), ENGINE_OIL_PER_BLEND_MB)],
        heat="heated"),
}

# ===========================================================================
# NO BUCKET RECIPES, ON PURPOSE
# ===========================================================================
# A filled bucket is not crafted here and must not be. Every one of this mod's
# fluids registers its bucket through `BaseFlowingFluid.Properties#bucket`, so
# NeoForge's own bucket capability fills a vanilla bucket from any tank, pipe,
# Create Spout or Item Drain that holds it, and empties it back. Adding a
# crafting recipe on top would put a second, worse route in JEI beside the one
# that already works.


# ===========================================================================
# TAGS
# ===========================================================================
# Two layers, and the indirection is the compatibility story - see ECFluidTags.
# The `c:` tag holds the actual fluids; this mod's own tag holds the `c:` tag.
# A pack can therefore join either one, and joining the conventional one makes
# the fluid work in every mod that reads the same convention rather than only
# in this one.
def fluid_pair(name):
    return [me(name), me("flowing_" + name)]


# Every block ECBlocks registers, in registration order. The one list both tags
# are built from, so a block can never be mineable without a tier or the reverse.
MINEABLE_BLOCKS = [me(name) for name in (
    "crankshaft", "cylinder", "flywheel", "carburetor", "oil_sump", "oil_shale")]


TAGS = {
    "c/tags/fluid/gasoline": {"replace": False, "values": fluid_pair("gasoline")},
    "c/tags/fluid/engine_oil": {"replace": False, "values": fluid_pair("engine_oil")},
    "c/tags/fluid/crude_oil": {"replace": False, "values": fluid_pair("crude_oil")},

    f"{NS}/tags/fluid/gasoline": {"replace": False, "values": ["#c:gasoline"]},
    f"{NS}/tags/fluid/engine_oil": {"replace": False, "values": ["#c:engine_oil"]},
    f"{NS}/tags/fluid/crude_oil": {"replace": False, "values": ["#c:crude_oil"]},

    # EVERY block this mod places, and Oil Shale.
    #
    # THIS LIST IS NOT DECORATION. All of these blocks are registered with
    # `requiresCorrectToolForDrops()`, and that flag is satisfied by being in a
    # `mineable/*` tag and nothing else - so a machine block absent from here does
    # not merely mine slowly. It mines slowly AND DROPS NOTHING, whatever tool is
    # used, for ever. That is exactly what happened: only Oil Shale was listed, so
    # a player who mined a Crankshaft, a Cylinder, a Flywheel, a Carburetor or an
    # Oil Sump destroyed it and got back an empty hand. The loot tables were
    # correct the whole time and never ran.
    #
    # `validate_ux.py` now fails the build if a registered block is missing from
    # either list below, so this cannot silently drift again.
    #
    # Stone tier for all of them: the parts are andesite-and-iron tier, and an
    # engine is not something a wooden pickaxe should take apart. Deliberately NOT
    # `needs_iron_tool` - by the time a player can craft a Crankshaft they have
    # iron anyway, so an iron requirement would gate nothing and only punish
    # somebody dismantling an engine with the pickaxe they happen to be holding.
    #
    # Oil Shale is here for its own reason: petroleum should not be punchable out
    # of the ground. It is deliberately NOT in `c:ores` - it drops itself rather
    # than a raw material, so an ore-doubling mod that saw it in that tag would
    # either duplicate the block outright or fail to find a smelting result for
    # it. It is a rock that is processed, not an ore that is refined.
    "minecraft/tags/block/mineable/pickaxe": {"replace": False, "values": MINEABLE_BLOCKS},
    "minecraft/tags/block/needs_stone_tool": {"replace": False, "values": MINEABLE_BLOCKS},
}


# ===========================================================================
# LOOT
# ===========================================================================
# Drops itself. There is no silk-touch branch and no fortune branch because
# there is no raw material to have either about: the block *is* the input to
# the crusher.
LOOT = {
    f"{NS}/loot_table/blocks/oil_shale": {
        "type": "minecraft:block",
        "random_sequence": me("blocks/oil_shale"),
        "pools": [{
            "rolls": 1.0,
            "bonus_rolls": 0.0,
            "conditions": [{"condition": "minecraft:survives_explosion"}],
            "entries": [{"type": "minecraft:item", "name": me("oil_shale")}],
        }],
    },
}


# ===========================================================================
# WORLDGEN
# ===========================================================================
# Data-driven, as 1.21.1 wants: a configured feature, a placed feature, and a
# NeoForge biome modifier to attach it. No `BiomeLoadingEvent`, no
# `addFeature` call from Java - those are the pre-1.19 pattern and do not exist
# any more.
#
# The block replaces both stone and deepslate with the same state. Oil shale is
# a sedimentary rock and a deepslate variant would be geologically silly, but
# targeting only `stone_ore_replaceables` would make the deeper half of the band
# silently empty, so one block state is listed against both targets.
WORLDGEN = {
    f"{NS}/worldgen/configured_feature/oil_shale": {
        "type": "minecraft:ore",
        "config": {
            "discard_chance_on_air_exposure": SHALE_AIR_DISCARD,
            "size": SHALE_VEIN_SIZE,
            "targets": [
                {"state": {"Name": me("oil_shale")},
                 "target": {"predicate_type": "minecraft:tag_match",
                            "tag": "minecraft:stone_ore_replaceables"}},
                {"state": {"Name": me("oil_shale")},
                 "target": {"predicate_type": "minecraft:tag_match",
                            "tag": "minecraft:deepslate_ore_replaceables"}},
            ],
        },
    },
    f"{NS}/worldgen/placed_feature/oil_shale": {
        "feature": me("oil_shale"),
        "placement": [
            {"type": "minecraft:count", "count": SHALE_VEINS_PER_CHUNK},
            {"type": "minecraft:in_square"},
            {"type": "minecraft:height_range",
             "height": {"type": "minecraft:uniform",
                        "min_inclusive": {"absolute": SHALE_MIN_Y},
                        "max_inclusive": {"absolute": SHALE_MAX_Y}}},
        ],
    },
    f"{NS}/neoforge/biome_modifier/oil_shale": {
        "type": "neoforge:add_features",
        "biomes": "#minecraft:is_overworld",
        "features": me("oil_shale"),
        "step": "underground_ores",
    },
}


def write(rel, content):
    path = DATA / (rel + ".json")
    os.makedirs(path.parent, exist_ok=True)
    with open(path, "w") as f:
        json.dump(content, f, indent=2)
        f.write("\n")
    print(f"wrote data/{rel}.json")


def main():
    for name, recipe in RECIPES.items():
        write(f"{NS}/recipe/{name}", recipe)
    for name, content in TAGS.items():
        write(name, content)
    for name, content in LOOT.items():
        write(name, content)
    for name, content in WORLDGEN.items():
        write(name, content)

    crude = 2 * CRUDE_PER_RETORT_MB
    print(f"\n2 Oil Shale (crushing) -> {2 * CRUSHED_PER_SHALE_CRUSHING} crushed"
          f" -> {crude} mB crude"
          f" -> {crude // CRUDE_PER_CRACK_MB * GASOLINE_PER_CRACK_MB} mB gasoline"
          f" + {crude // CRUDE_PER_CRACK_MB * RESIDUE_PER_CRACK} residue")
    print(f"{RESIDUE_PER_BLEND} residue + 1 zinc nugget"
          f" -> {ENGINE_OIL_PER_BLEND_MB} mB engine oil")


if __name__ == "__main__":
    main()
