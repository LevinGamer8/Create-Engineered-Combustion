#!/usr/bin/env python3
"""Generates the whole advancement tree, and the strings that name it.

One table, three outputs: the advancement JSON, the `en_us` strings and the
`de_de` strings. It is a generator rather than sixty hand-written files for the
same reason the recipes are - **a fact only exists once.** An advancement's id
appears in its own file, in its children's `parent` fields, and in two language
files, and four copies of a string drift. One row here cannot.

    python3 tools/generate_advancements.py

Every advancement is the same criterion - `engineered_combustion:engine_event` -
with a different filter. See EngineEventTrigger for why that is one Java class
rather than twenty-three, and EngineEventTracker for the rules that decide when
an event may fire at all.

One row the milestone offers is deliberately absent. "Put It Back" - a joke for
reinstalling the same worn part - would need MAINTENANCE_COMPLETED to fire for a
swap that improved nothing, and the tracker refuses to call that maintenance
because it is not maintenance. Giving the joke its own event, or teaching the
tracker to lie, is exactly the ugliness the milestone says to skip it over. The
behaviour it was about is still real, still taught in the maintenance Ponder, and
still asserted by a test - it simply does not hand out a toast.

The tree is laid out to read left to right in the vanilla advancement screen:
the petroleum chain, then assembly, then the first start, then more cylinders,
with maintenance branching off lubrication. Hidden advancements hang off
whichever visible parent is thematically closest, which keeps them out of the
way until they are earned.
"""
import json
import os
import pathlib

ROOT = pathlib.Path(__file__).resolve().parents[1]
DATA = ROOT / "src/main/resources/data"
LANG = ROOT / "src/main/resources/assets/engineered_combustion/lang"
NS = "engineered_combustion"

TRIGGER = f"{NS}:engine_event"


def me(path):
    return f"{NS}:{path}"


# ===========================================================================
# THE TREE
# ===========================================================================
# Each row is one advancement:
#
#   id          the file name, and the id other rows name as their parent
#   parent      None for the root
#   icon        item id shown on the tile
#   frame       task | goal | challenge
#   hidden      keeps it off the screen until it is earned
#   en / de     title and description, in both languages
#   criteria    the trigger filter, or a raw vanilla criterion for the few that
#               are genuinely about having an item rather than about an engine
#   xp          optional experience reward. Deliberately small or absent - the
#               milestone asks for toast and personality, not loot.
#
# The `event` values and every filter field below are read by EngineEventTrigger.
# `cylinders` and `active_cylinders` are ranges: {"min": 4} is "at least four",
# {"min": 3, "max": 3} is "exactly three", and the difference between those two
# is the entire active-versus-healthy distinction the mod is built on.

def has_item(*items):
    """A plain vanilla inventory criterion, for the pure "obtain a thing" rows."""
    return {"has": {"trigger": "minecraft:inventory_changed",
                    "conditions": {"items": [{"items": list(items)}]}}}


def engine(**filters):
    """One engine-event criterion, with whatever filter the row needs."""
    return {"event": {"trigger": TRIGGER, "conditions": filters}}


ADVANCEMENTS = [
    # --- root ---------------------------------------------------------------
    dict(
        id="root", parent=None, icon=me("crankshaft"), frame="task",
        en=("Engineered Combustion",
            "Build engines piece by piece and turn fuel into mechanical power."),
        de=("Engineered Combustion",
            "Baue Motoren Stück für Stück und mach aus Treibstoff mechanische Kraft."),
        # Deliberately generous: holding ANY of the mod's starting materials
        # reveals the tab. A player who has found Oil Shale in a cave, or been
        # given a Crankshaft, should not have to guess what to do to see the
        # progression - the milestone asks explicitly that nothing obscure be
        # required merely to make the tab appear.
        criteria=has_item(me("oil_shale"), me("crushed_oil_shale"), me("crankshaft"),
                          me("cylinder"), me("gasoline_bucket"), me("engine_oil_bucket")),
        background="minecraft:block/deepslate",
    ),

    # --- petroleum ----------------------------------------------------------
    dict(
        id="black_gold", parent="root", icon=me("oil_shale"), frame="task",
        en=("Black Gold... Sort Of", "Discover a source of petroleum."),
        de=("Schwarzes Gold ... irgendwie", "Finde eine Petroleumquelle."),
        criteria=has_item(me("oil_shale"), me("crushed_oil_shale")),
    ),
    dict(
        id="refined_taste", parent="black_gold", icon=me("gasoline_bucket"), frame="task",
        en=("Refined Taste", "Turn petroleum into engine fuel."),
        de=("Raffinierter Geschmack", "Mach aus Petroleum Motortreibstoff."),
        criteria=has_item(me("gasoline_bucket")),
    ),
    dict(
        id="keep_it_slippery", parent="refined_taste", icon=me("engine_oil_bucket"), frame="task",
        en=("Keep It Slippery", "Fuel makes it run. Oil keeps it running."),
        de=("Immer schön schmieren", "Treibstoff lässt ihn laufen. Öl lässt ihn weiterlaufen."),
        criteria=has_item(me("engine_oil_bucket")),
    ),

    # --- assembly and the first start ---------------------------------------
    dict(
        id="some_assembly_required", parent="keep_it_slippery", icon=me("cylinder"), frame="task",
        en=("Some Assembly Required", "Put the pieces together."),
        de=("Zusammenbau erforderlich", "Setz die Teile zusammen."),
        # The STRUCTURE becoming valid, not a part being crafted.
        criteria=engine(event="assembled"),
    ),
    dict(
        id="first_cranking", parent="some_assembly_required", icon="minecraft:iron_ingot", frame="task",
        en=("First Cranking", "Turn the engine over and see what happens."),
        de=("Erstes Ankurbeln", "Dreh den Motor durch und schau, was passiert."),
        criteria=engine(event="cranking_started"),
    ),
    dict(
        id="it_really_started", parent="first_cranking", icon=me("spark_plug"), frame="goal",
        en=("It Really Started!", "Get an engine running under its own power."),
        de=("Er läuft wirklich!", "Bring einen Motor dazu, aus eigener Kraft zu laufen."),
        # STARTING -> RUNNING, and that transition alone. Not a reload, not a
        # chunk load, not an external motor, not cranking.
        criteria=engine(event="engine_started"),
        xp=10,
    ),
    dict(
        id="mechanical_power", parent="it_really_started", icon=me("flywheel"), frame="task",
        en=("Mechanical Power", "Put combustion to work."),
        de=("Mechanische Kraft", "Lass die Verbrennung arbeiten."),
        # Generation, not rotation. An engine being motored fires nothing.
        criteria=engine(event="generation_started"),
    ),

    # --- more cylinders -----------------------------------------------------
    dict(
        id="double_trouble", parent="mechanical_power", icon=me("crankshaft"), frame="task",
        en=("Double Trouble", "Two cylinders, one crankshaft."),
        de=("Doppelt hält besser", "Zwei Zylinder, eine Kurbelwelle."),
        criteria=engine(event="inline_running", cylinders={"min": 2},
                        active_cylinders={"min": 2}),
    ),
    dict(
        id="third_times_the_charm", parent="double_trouble", icon=me("piston_assembly"), frame="task",
        en=("Third Time's the Charm", "Three cylinders, all of them firing."),
        de=("Aller guten Dinge sind drei", "Drei Zylinder, und alle zünden."),
        criteria=engine(event="inline_running", cylinders={"min": 3},
                        active_cylinders={"min": 3}),
    ),
    dict(
        id="four_of_a_kind", parent="third_times_the_charm", icon=me("carburetor"), frame="goal",
        en=("Four of a Kind", "Four cylinders. One crankshaft. A lot more fuel."),
        de=("Viererpasch", "Vier Zylinder. Eine Kurbelwelle. Deutlich mehr Treibstoff."),
        criteria=engine(event="inline_running", cylinders={"min": 4, "max": 4},
                        active_cylinders={"min": 4}),
        xp=20,
    ),

    # --- maintenance --------------------------------------------------------
    dict(
        id="fresh_internals", parent="keep_it_slippery", icon=me("piston_assembly"), frame="task",
        en=("Fresh Internals", "Restore compression with fresh parts."),
        de=("Frische Innereien", "Stell die Kompression mit neuen Teilen wieder her."),
        # A real improvement, from something worth replacing to something better.
        criteria=engine(event="maintenance_completed", improved_from="used",
                        improved_to="good"),
    ),
    dict(
        id="back_in_service", parent="fresh_internals", icon=me("crankshaft"), frame="goal",
        en=("Back in Service", "Bring a badly worn engine back to health."),
        de=("Wieder im Dienst", "Bring einen stark verschlissenen Motor wieder in Form."),
        # WORN or worse, back to GOOD or better, through actual part replacement.
        criteria=engine(event="maintenance_completed", improved_from="worn",
                        improved_to="good"),
        xp=20,
    ),

    # --- hidden: the jokes --------------------------------------------------
    dict(
        id="oil_is_optional", parent="keep_it_slippery", icon="minecraft:bucket",
        frame="task", hidden=True,
        en=("Oil Is Optional, Right?", "Technically, it did keep running."),
        de=("Öl ist optional, oder?", "Technisch gesehen lief er noch."),
        criteria=engine(event="abuse_state", abuse_kind="dry"),
    ),
    dict(
        id="mechanical_sympathy", parent="oil_is_optional", icon="minecraft:iron_nugget",
        frame="task", hidden=True,
        en=("Mechanical Sympathy? Never Heard of It.",
            "Every noise is just another diagnostic."),
        de=("Technisches Feingefühl? Nie gehört.",
            "Jedes Geräusch ist doch nur eine weitere Diagnose."),
        # Bearings specifically, POOR or worse, worn there by actually running.
        criteria=engine(event="condition_reached", condition_kind="mechanical",
                        min_condition="poor"),
    ),
    dict(
        id="warranty_void", parent="mechanical_sympathy", icon=me("piston_assembly"),
        frame="challenge", hidden=True,
        en=("Warranty Void", "The manufacturer would like a word."),
        de=("Garantie erloschen", "Der Hersteller hätte da mal eine Frage."),
        criteria=engine(event="condition_reached", min_condition="critical"),
        xp=50,
    ),
    dict(
        id="are_you_trying_to_kill_it", parent="warranty_void", icon="minecraft:blaze_powder",
        frame="challenge", hidden=True,
        en=("Are You Trying to Kill It?", "At this point it feels personal."),
        de=("Willst du ihn absichtlich umbringen?", "Langsam wird es persönlich."),
        # Dry AND oversped AND heavily loaded, sustained. See EngineEventTracker.
        criteria=engine(event="abuse_state", abuse_kind="all_out"),
        xp=50,
    ),
    dict(
        id="this_is_fine", parent="warranty_void", icon="minecraft:campfire",
        frame="task", hidden=True,
        en=("This Is Fine", "Everything sounds completely normal."),
        de=("Alles bestens", "Klingt doch völlig normal."),
        # Running, generating, and critical all at once.
        criteria=engine(event="inline_running", min_condition="critical"),
    ),
    dict(
        id="still_runs", parent="warranty_void", icon=me("spark_plug"),
        frame="goal", hidden=True,
        en=("Still Runs!", "Compression is more of a suggestion anyway."),
        de=("Läuft trotzdem!", "Kompression ist sowieso eher ein Vorschlag."),
        # The engine's condition BEFORE it caught - see EngineEventTracker.
        criteria=engine(event="engine_started", min_condition="critical"),
        xp=20,
    ),
    dict(
        id="three_out_of_four", parent="four_of_a_kind", icon=me("spark_plug"),
        frame="task", hidden=True,
        en=("Three Out of Four Ain't Bad", "One cylinder has decided to take the day off."),
        de=("Drei von vier sind doch auch okay", "Ein Zylinder hat sich heute freigenommen."),
        # EXACTLY three of four alight, sustained. This is the row that proves
        # active cylinders and healthy cylinders are different questions.
        criteria=engine(event="inline_running", cylinders={"min": 4, "max": 4},
                        active_cylinders={"min": 3, "max": 3}),
    ),
    dict(
        id="two_flywheels", parent="mechanical_power", icon=me("flywheel"),
        frame="task", hidden=True,
        en=("Two Flywheels, Zero Problems", "Correction: one problem."),
        de=("Zwei Schwungräder, null Probleme", "Korrektur: ein Problem."),
        criteria=engine(event="invalid_layout_attempt", layout="second_flywheel"),
    ),
    dict(
        id="more_cylinders", parent="four_of_a_kind", icon=me("crankshaft"),
        frame="task", hidden=True,
        en=("More Cylinders!", "The current answer is no."),
        de=("Mehr Zylinder!", "Die Antwort lautet derzeit nein."),
        criteria=engine(event="invalid_layout_attempt", layout="too_many_cylinders"),
    ),
]


# ===========================================================================
# OUTPUT
# ===========================================================================

def title_key(entry):
    return f"advancements.{NS}.{entry['id']}.title"


def description_key(entry):
    return f"advancements.{NS}.{entry['id']}.description"


def build(entry):
    display = {
        "icon": {"id": entry["icon"]},
        "title": {"translate": title_key(entry)},
        "description": {"translate": description_key(entry)},
        "frame": entry.get("frame", "task"),
        "show_toast": True,
        "announce_to_chat": not entry.get("hidden", False),
        "hidden": entry.get("hidden", False),
    }
    if "background" in entry:
        display["background"] = entry["background"]

    advancement = {"display": display, "criteria": entry["criteria"]}
    if entry["parent"] is not None:
        advancement["parent"] = me(entry["parent"])
    # Explicit rather than implied: with one criterion the default is the same,
    # but a row that later grows a second must not silently start requiring both.
    advancement["requirements"] = [[name] for name in entry["criteria"]]
    if entry.get("xp"):
        advancement["rewards"] = {"experience": entry["xp"]}
    return advancement


def write_json(path, content):
    os.makedirs(path.parent, exist_ok=True)
    with open(path, "w", encoding="utf-8") as f:
        json.dump(content, f, indent=2, ensure_ascii=False)
        f.write("\n")


def merge_lang(filename, additions):
    """Adds our keys to a language file, leaving everything else alone."""
    path = LANG / filename
    with open(path, encoding="utf-8") as f:
        existing = json.load(f)
    existing.update(additions)
    with open(path, "w", encoding="utf-8") as f:
        json.dump(existing, f, indent=2, ensure_ascii=False)
        f.write("\n")
    return path


def main():
    ids = [entry["id"] for entry in ADVANCEMENTS]
    if len(ids) != len(set(ids)):
        raise SystemExit("duplicate advancement id")
    for entry in ADVANCEMENTS:
        parent = entry["parent"]
        if parent is not None and parent not in ids:
            raise SystemExit(f"{entry['id']} names a parent that does not exist: {parent}")

    for entry in ADVANCEMENTS:
        write_json(DATA / NS / "advancement" / f"{entry['id']}.json", build(entry))
        print(f"wrote data/{NS}/advancement/{entry['id']}.json")

    for filename, key in (("en_us.json", "en"), ("de_de.json", "de")):
        additions = {}
        for entry in ADVANCEMENTS:
            title, description = entry[key]
            additions[title_key(entry)] = title
            additions[description_key(entry)] = description
        print(f"wrote {merge_lang(filename, additions).name} ({len(additions)} keys)")

    hidden = sum(1 for entry in ADVANCEMENTS if entry.get("hidden"))
    print(f"\n{len(ADVANCEMENTS)} advancements, {hidden} hidden, "
          f"{len(ADVANCEMENTS) - hidden} visible")


if __name__ == "__main__":
    main()
