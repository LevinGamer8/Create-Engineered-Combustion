#!/usr/bin/env python3
"""Generates the player-visible strings that are not Ponder and not advancements.

Item tooltips and contextual action-bar feedback, in both languages, from one
table. Same reason as every other generator here: a string that exists twice
drifts, and a string that exists in English only ships half-translated.

    python3 tools/generate_ui_lang.py

Tooltip keys are read at runtime by ECItemTooltips, which walks
`<modid>.tooltip.<item>.1`, `.2`, ... and stops at the first gap. So the ORDER of
the lines in a row below is the order they appear under the item's name, and
there must be no holes.

The guiding rule for tooltips, from the milestone: answer "what is this item
for?" and nothing else. Ponder explains how the machine works; the recipe viewer
explains how to make it; the goggles explain what it is doing right now.
"""
import json
import pathlib

ROOT = pathlib.Path(__file__).resolve().parents[1]
LANG = ROOT / "src/main/resources/assets/engineered_combustion/lang"
NS = "engineered_combustion"

# ===========================================================================
# ITEM TOOLTIPS - (english, german) per line, in display order
# ===========================================================================

TOOLTIPS = {
    "crankshaft": [
        ("Forms the mechanical base of an engine.",
         "Bildet die mechanische Basis eines Motors."),
        ("Extend along its axis for additional cylinders.",
         "Entlang seiner Achse erweitern für weitere Zylinder."),
    ],
    "cylinder": [
        ("Houses a Piston Assembly and a Spark Plug.",
         "Nimmt eine Kolbeneinheit und eine Zündkerze auf."),
    ],
    "piston_assembly": [
        ("Install inside a Cylinder.",
         "Wird in einen Zylinder eingebaut."),
        ("Worn assemblies reduce compression.",
         "Verschlissene Einheiten senken die Kompression."),
    ],
    "flywheel": [
        ("Transfers engine power into a kinetic network.",
         "Überträgt die Motorleistung in ein kinetisches Netzwerk."),
        ("One Flywheel per engine.",
         "Ein Schwungrad pro Motor."),
    ],
    "carburetor": [
        ("Holds Gasoline and controls the throttle.",
         "Fasst Benzin und regelt das Gas."),
    ],
    "oil_sump": [
        ("Holds Engine Oil for lubrication.",
         "Fasst Motoröl für die Schmierung."),
    ],
    "air_filter": [
        ("Optional.",
         "Optional."),
        ("Protects cylinders from long-term wear.",
         "Schützt die Zylinder vor langfristigem Verschleiß."),
    ],
    "spark_plug": [
        ("Required for a Cylinder to ignite fuel.",
         "Wird benötigt, damit ein Zylinder Treibstoff zündet."),
    ],
    "redstone_control_module": [
        ("Optional engine automation.",
         "Optionale Motorautomatisierung."),
        ("Allows Redstone control of ignition and/or throttle.",
         "Erlaubt Redstone-Steuerung von Zündung und/oder Gas."),
    ],
    "gasoline_bucket": [
        ("Fuel for combustion engines.",
         "Treibstoff für Verbrennungsmotoren."),
    ],
    "engine_oil_bucket": [
        ("Protects moving engine components from wear.",
         "Schützt bewegliche Motorbauteile vor Verschleiß."),
    ],
    "oil_shale": [
        ("A source of petroleum, found deep underground.",
         "Eine Petroleumquelle, tief unter der Erde zu finden."),
    ],
    "crushed_oil_shale": [
        ("Retort with Water to produce Crude Oil.",
         "Mit Wasser ausheizen, um Rohöl zu gewinnen."),
    ],
    "crude_oil_bucket": [
        ("Crack it to produce Gasoline.",
         "Cracken, um Benzin zu gewinnen."),
    ],
    "petroleum_residue": [
        ("Blend with a Zinc Nugget to make Engine Oil.",
         "Mit einem Zinkklumpen zu Motoröl vermengen."),
    ],
}

# ===========================================================================
# CONTEXTUAL FEEDBACK - the action bar, never the chat
# ===========================================================================
# Sent only in response to an actual interaction that did not do what the player
# wanted, so there is no tick-by-tick nagging anywhere. Each explains the ONE
# thing that is wrong and, where there is one, what to do about it.

MESSAGES = {
    # Servicing
    "gui.stop_engine_before_servicing": (
        "Stop the engine before servicing it.",
        "Stelle den Motor ab, bevor du ihn wartest."),
    "gui.spark_plug_installed": (
        "A Spark Plug is already installed.",
        "Es ist bereits eine Zündkerze eingebaut."),
    "gui.piston_installed": (
        "A Piston Assembly is already installed.",
        "Es ist bereits eine Kolbeneinheit eingebaut."),

    # Layout limits
    "gui.one_flywheel_only": (
        "Only one Flywheel is supported per engine.",
        "Pro Motor wird nur ein Schwungrad unterstützt."),
    "gui.too_many_cylinders": (
        "Inline engines currently support up to 4 cylinders.",
        "Reihenmotoren unterstützen derzeit bis zu 4 Zylinder."),

    # Fluids
    "gui.not_gasoline": (
        "This fluid cannot be used as Gasoline.",
        "Diese Flüssigkeit lässt sich nicht als Benzin verwenden."),
    "gui.not_engine_oil": (
        "This fluid cannot be used as Engine Oil.",
        "Diese Flüssigkeit lässt sich nicht als Motoröl verwenden."),

    # Start blockers. Only the ones that genuinely prevent running - low oil is
    # NOT one of them, because the simulation permits running dry and saying
    # otherwise would be a lie. The goggles carry that warning instead.
    "gui.start_no_gasoline": (
        "No Gasoline.",
        "Kein Benzin."),
    "gui.start_no_spark_plug": (
        "One or more cylinders have no Spark Plug.",
        "Einem oder mehreren Zylindern fehlt die Zündkerze."),
    "gui.start_no_piston": (
        "One or more cylinders have no Piston Assembly.",
        "Einem oder mehreren Zylindern fehlt die Kolbeneinheit."),
    "gui.start_needs_cranking": (
        "Crank the engine to start it.",
        "Kurble den Motor an, um ihn zu starten."),
}


def merge(filename, additions):
    path = LANG / filename
    with open(path, encoding="utf-8") as handle:
        existing = json.load(handle)
    existing.update(additions)
    with open(path, "w", encoding="utf-8") as handle:
        json.dump(existing, handle, indent=2, ensure_ascii=False)
        handle.write("\n")
    return len(additions)


def main():
    english, deutsch = {}, {}

    for item, lines in TOOLTIPS.items():
        for index, (en, de) in enumerate(lines, start=1):
            key = f"{NS}.tooltip.{item}.{index}"
            english[key] = en
            deutsch[key] = de

    for suffix, (en, de) in MESSAGES.items():
        key = f"{NS}.{suffix}"
        english[key] = en
        deutsch[key] = de

    print(f"{len(TOOLTIPS)} items with tooltips, {len(MESSAGES)} contextual messages")
    print(f"wrote en_us.json (+{merge('en_us.json', english)} keys)")
    print(f"wrote de_de.json (+{merge('de_de.json', deutsch)} keys)")


if __name__ == "__main__":
    main()
