#!/usr/bin/env python3
"""Generates the language keys the Ponder scenes need, in both languages.

Ponder builds a translation key for every line of scene text - `text_0`,
`text_1`, ... in the order the storyboard calls `.text(...)` - and looks it up
through `I18n`, which returns the KEY rather than the default when it is
missing. So a scene whose keys are absent renders as
`engineered_combustion.ponder.starting_an_engine.text_3` on screen. Every line
needs an entry, and the entries have to be numbered in call order.

Numbering by hand would be miserable and would silently break the moment a line
was inserted in the middle of a scene, so this reads the English straight out of
the Java:

    python3 tools/generate_ponder_lang.py

**The Java is the source of the English.** It has to be - `.text(...)` takes the
default text - and having a second copy in a table here is how the two drift.
This finds each `scene.title(id, title)`, collects the `.text(...)` calls that
follow it until the next scene begins, and numbers them exactly as Ponder will.

The German is the one thing that cannot be derived, so it lives in the TRANSLATIONS
table below, keyed by the English. A line whose English is not in that table is a
hard error rather than a silent fallback: an untranslated scene should fail the
build, not ship reading half in English.
"""
import json
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parents[1]
SRC = ROOT / "src/main/java/dev/engineeredcombustion/ponder"
LANG = ROOT / "src/main/resources/assets/engineered_combustion/lang"
NS = "engineered_combustion"

# Ponder's own key shape - see PonderLocalization.langKeyForSpecific.
#   <namespace>.ponder.<sceneId>.header
#   <namespace>.ponder.<sceneId>.text_<n>
#   <namespace>.ponder.tag.<tagId>  / .description
PREFIX = f"{NS}.ponder."

TAG_ID = "engines"
TAG_TITLE_EN = "Engineered Combustion"
TAG_TITLE_DE = "Engineered Combustion"
TAG_DESCRIPTION_EN = ("Inline combustion engines that burn Gasoline to drive a Create network. "
                      "Built from parts, fuelled, lubricated, cranked by hand, and maintained.")
TAG_DESCRIPTION_DE = ("Reihenmotoren, die Benzin verbrennen, um ein Create-Netzwerk anzutreiben. "
                      "Aus Einzelteilen gebaut, betankt, geschmiert, von Hand angekurbelt "
                      "und gewartet.")

# --------------------------------------------------------------------------
# German
# --------------------------------------------------------------------------
# Keyed by the English, which is what the Java holds. Written to sound like
# something a German-speaking player would say rather than like a transliteration
# - "Zündung" for Ignition and "Drosselklappe"/"Gas" for throttle are the words
# the workshop actually uses.

TRANSLATIONS = {
    # scene titles
    "Building a Basic Engine": "Einen einfachen Motor bauen",
    "Fuel and Lubrication": "Treibstoff und Schmierung",
    "Starting an Engine": "Einen Motor starten",
    "Inline Engines": "Reihenmotoren",
    "Engine Controls": "Motorsteuerung",
    "Engine Maintenance": "Motorwartung",
    "Diagnosing an Engine": "Einen Motor diagnostizieren",
    "From Oil Shale to Fuel": "Vom Ölschiefer zum Treibstoff",

    # assembling_an_engine
    "The Crankshaft forms the mechanical base of the engine.":
        "Die Kurbelwelle bildet die mechanische Basis des Motors.",
    "Each Crankshaft section supports one Cylinder, directly above it.":
        "Jedes Kurbelwellensegment trägt genau einen Zylinder, direkt darüber.",
    "Piston Assemblies are installed inside Cylinders, not placed as blocks.":
        "Kolbeneinheiten werden in Zylinder eingebaut, nicht als Block gesetzt.",
    "Each Cylinder needs its own Spark Plug to ignite fuel.":
        "Jeder Zylinder braucht seine eigene Zündkerze, um Treibstoff zu zünden.",
    "The Carburetor holds Gasoline and controls the throttle.":
        "Der Vergaser fasst das Benzin und regelt das Gas.",
    "The Oil Sump hangs under the crankcase and supplies lubrication.":
        "Die Ölwanne hängt unter dem Kurbelgehäuse und versorgt den Motor mit Schmierung.",
    "The Flywheel transfers engine power into a Create kinetic network.":
        "Das Schwungrad überträgt die Motorleistung in ein kinetisches Create-Netzwerk.",
    "Either end of the crankshaft will do.":
        "Beide Enden der Kurbelwelle funktionieren.",
    "One engine uses one Flywheel. Two is not a valid engine.":
        "Ein Motor nutzt genau ein Schwungrad. Zwei ergeben keinen gültigen Motor.",
    # The four-stroke scene.
    "The Four-Stroke Cycle":
        "Der Viertakt-Zyklus",
    "A running engine turns the Crankshaft twice for every combustion.":
        "Ein laufender Motor dreht die Kurbelwelle zweimal pro Verbrennung.",
    "1. Intake. The Camshaft opens a valve and the piston draws fuel in.":
        "1. Ansaugen. Die Nockenwelle öffnet ein Ventil, der Kolben saugt Kraftstoff an.",
    "2. Compression. Both valves shut and the piston squeezes the charge.":
        "2. Verdichten. Beide Ventile sind zu, der Kolben verdichtet das Gemisch.",
    "3. Power. The Spark Plug lights it, and the Crankshaft is pushed round.":
        "3. Arbeiten. Die Zündkerze zündet es, und die Kurbelwelle wird angetrieben.",
    "4. Exhaust. The other valve opens and the burnt charge is pushed out.":
        "4. Ausstoßen. Das andere Ventil öffnet, das verbrannte Gemisch wird ausgeschoben.",
    "Only one stroke pushes. The Flywheel carries the engine through the rest.":
        "Nur ein Takt treibt an. Das Schwungrad trägt den Motor durch die übrigen.",
    "That is why a single thumps, and why more cylinders run smoother.":
        "Deshalb stampft ein Einzylinder, und deshalb laufen mehr Zylinder runder.",

    "An engine with no Camshaft will crank for ever and never catch.":
        "Ein Motor ohne Nockenwelle dreht ewig und springt nie an.",
    "One Camshaft is installed into the Crankshaft, and works every valve.":
        "Eine Nockenwelle wird in die Kurbelwelle eingebaut und betätigt alle Ventile.",
    "Without one the Cylinder cannot draw fuel in, so it never fires.":
        "Ohne sie kann der Zylinder keinen Kraftstoff ansaugen und zündet nie.",
    "An Air Filter clamps onto the Carburetor, and is optional.":
        "Ein Luftfilter wird auf den Vergaser geklemmt und ist optional.",
    "Without one, the cylinders take more long-term wear.":
        "Ohne ihn verschleißen die Zylinder langfristig stärker.",
    "This engine is mechanically complete.":
        "Dieser Motor ist mechanisch vollständig.",

    # fuel_and_lubrication
    "Gasoline goes into the Carburetor, and is consumed during combustion.":
        "Benzin kommt in den Vergaser und wird bei der Verbrennung verbraucht.",
    "Engine Oil goes into the Oil Sump, and protects the moving parts.":
        "Motoröl kommt in die Ölwanne und schützt die beweglichen Teile.",
    "Both accept fluids from Create's pipes and tanks as well as from buckets.":
        "Beide nehmen Flüssigkeit aus Create-Rohren und -Tanks an, nicht nur aus Eimern.",
    "Proper lubrication keeps major component wear very low.":
        "Richtige Schmierung hält den Verschleiß der großen Bauteile sehr gering.",
    "Low oil increases friction and wear.":
        "Wenig Öl erhöht Reibung und Verschleiß.",
    "Running dry can seriously damage an engine.":
        "Trockenlauf kann einen Motor ernsthaft beschädigen.",
    "The engine runs without an Air Filter, but long-term cylinder wear increases.":
        "Der Motor läuft auch ohne Luftfilter, aber der Zylinderverschleiß steigt langfristig.",

    # starting_an_engine
    "A fuelled and lubricated engine, with its Ignition already on.":
        "Ein betankter und geschmierter Motor, dessen Zündung bereits an ist.",
    "New engines have their Ignition switched on by default.":
        "Bei neuen Motoren ist die Zündung standardmäßig eingeschaltet.",
    "Ignition alone does not start the engine.":
        "Die Zündung allein startet den Motor nicht.",
    "Combustion engines must be cranked before they can run on their own.":
        "Verbrennungsmotoren müssen angekurbelt werden, bevor sie von allein laufen.",
    "Cylinders begin firing, but the engine has not caught yet.":
        "Die Zylinder zünden bereits, aber der Motor hat noch nicht angesprungen.",
    "Once it catches, combustion keeps the crankshaft turning by itself.":
        "Sobald er anspringt, hält die Verbrennung die Kurbelwelle von allein in Bewegung.",
    "Scroll on the Carburetor to set the throttle, from 0% to 100%.":
        "Scrolle am Vergaser, um das Gas von 0 % bis 100 % einzustellen.",
    "More throttle increases available torque and the governed operating speed.":
        "Mehr Gas erhöht das verfügbare Drehmoment und die geregelte Betriebsdrehzahl.",
    "Ignition stops combustion.":
        "Die Zündung beendet die Verbrennung.",
    "The engine keeps turning while it coasts, but is no longer producing power.":
        "Der Motor dreht im Auslauf weiter, erzeugt aber keine Leistung mehr.",

    # inline_engines
    "One Crankshaft section is an inline-1.":
        "Ein einzelnes Kurbelwellensegment ist ein Einzylinder.",
    "Adjacent Crankshaft sections form one shared engine, not several.":
        "Benachbarte Kurbelwellensegmente bilden einen gemeinsamen Motor, nicht mehrere.",
    "One crankshaft, one Carburetor, one Oil Sump, one Flywheel - and several cylinders.":
        "Eine Kurbelwelle, ein Vergaser, eine Ölwanne, ein Schwungrad - und mehrere Zylinder.",
    "Each cylinder fires at a different point in the crankshaft's rotation.":
        "Jeder Zylinder zündet an einem anderen Punkt der Kurbelwellenumdrehung.",
    "More cylinders provide more power, and consume more fuel.":
        "Mehr Zylinder liefern mehr Leistung und verbrauchen mehr Treibstoff.",
    "Remove a Spark Plug and the goggles read: Active Cylinders 3 / 4.":
        "Nimm eine Zündkerze heraus, und die Brille zeigt: Aktive Zylinder 3 / 4.",
    "The piston still moves, but that cylinder no longer produces combustion power.":
        "Der Kolben bewegt sich weiter, aber dieser Zylinder liefert keine Verbrennungsleistung mehr.",
    "Inline engines currently support up to four cylinders.":
        "Reihenmotoren unterstützen derzeit bis zu vier Zylinder.",

    # engine_controls
    "Right-click the Crankshaft with an empty hand to work the Ignition.":
        "Rechtsklicke die Kurbelwelle mit leerer Hand, um die Zündung zu schalten.",
    "Scroll on the Carburetor to set the throttle.":
        "Scrolle am Vergaser, um das Gas einzustellen.",
    "Redstone is not required to run an engine.":
        "Redstone wird nicht benötigt, um einen Motor zu betreiben.",
    "The Redstone Control Module lets Redstone drive the ignition, the throttle, or both.":
        "Das Redstone-Steuermodul lässt Redstone die Zündung, das Gas oder beides übernehmen.",
    "Its mode is set with a wrench: Manual, Ignition, Throttle, or both.":
        "Der Modus wird mit dem Schraubenschlüssel gewählt: Manuell, Zündung, Gas oder beides.",
    "Removing the module returns the engine to manual control.":
        "Wird das Modul entfernt, läuft der Motor wieder rein manuell.",

    # engine_maintenance
    "A properly lubricated and filtered engine wears extremely slowly.":
        "Ein richtig geschmierter und gefilterter Motor verschleißt außerordentlich langsam.",
    "Crankshafts and Piston Assemblies are not routine consumables.":
        "Kurbelwellen und Kolbeneinheiten sind keine regelmäßigen Verschleißteile.",
    "Unfiltered operation increases long-term cylinder wear.":
        "Betrieb ohne Filter erhöht den Zylinderverschleiß auf lange Sicht.",
    "Low oil increases friction and component wear.":
        "Wenig Öl erhöht Reibung und Bauteilverschleiß.",
    "Running without lubrication can cause serious damage.":
        "Betrieb ohne Schmierung kann schwere Schäden verursachen.",
    "External machines can force an engine past its intended speed. "
    "Sustained overspeed wears it quickly.":
        "Fremde Maschinen können einen Motor über seine vorgesehene Drehzahl treiben. "
        "Dauerhafte Überdrehzahl verschleißt ihn schnell.",
    "Mechanical Condition: Poor. Cylinder 3 Compression: Poor.":
        "Mechanischer Zustand: Schlecht. Kompression Zylinder 3: Schlecht.",
    "Worn components reduce performance and make starting harder.":
        "Verschlissene Bauteile senken die Leistung und erschweren das Starten.",
    "Stop the engine before servicing it.":
        "Stelle den Motor ab, bevor du ihn wartest.",
    "Sneak and right-click to take the Piston Assembly out. It keeps its condition.":
        "Schleichen und Rechtsklick nimmt die Kolbeneinheit heraus. Sie behält ihren Zustand.",
    "Putting the same worn part back does not repair it.":
        "Dasselbe verschlissene Teil wieder einzubauen repariert es nicht.",
    "A fresh Piston Assembly restores that cylinder's compression completely.":
        "Eine neue Kolbeneinheit stellt die Kompression dieses Zylinders vollständig wieder her.",
    "A worn Crankshaft section is replaced the same way, by mining and replacing it.":
        "Ein verschlissenes Kurbelwellensegment wird genauso ersetzt: abbauen und neu setzen.",
    "Major internal parts normally need replacing only after severe or "
    "very long-term wear.":
        "Große Innenteile müssen normalerweise nur nach schwerem oder sehr langfristigem "
        "Verschleiß ersetzt werden.",

    # diagnosing_an_engine
    "Engineer's Goggles show what an engine is doing right now.":
        "Die Ingenieursbrille zeigt, was ein Motor gerade tut.",
    "State, Speed, Generation, Active Cylinders, Throttle, Fuel, "
    "Lubrication and Condition.":
        "Zustand, Drehzahl, Erzeugung, aktive Zylinder, Gas, Treibstoff, "
        "Schmierung und Verschleiß.",
    "Sneak while looking at it for per-cylinder diagnostics.":
        "Schleiche beim Hinsehen, um die Diagnose je Zylinder zu sehen.",
    "No Gasoline: nothing can burn, so the engine will not run.":
        "Kein Benzin: Es kann nichts verbrennen, also läuft der Motor nicht.",
    "A Cylinder with no Spark Plug cannot contribute. Active Cylinders drops.":
        "Ein Zylinder ohne Zündkerze trägt nichts bei. Die aktiven Zylinder sinken.",
    "Low or no oil shows as a Wear Risk warning.":
        "Wenig oder kein Öl erscheint als Warnung unter Verschleißrisiko.",
    "A worn Piston shows as poor compression, and the engine makes less power.":
        "Ein verschlissener Kolben zeigt sich als schlechte Kompression, "
        "und der Motor leistet weniger.",
    "An engine turned by another machine reads Speed above zero, "
    "Generation Inactive, Capacity 0.":
        "Ein von außen gedrehter Motor zeigt Drehzahl über null, "
        "Erzeugung inaktiv und Kapazität 0.",
    "Rotation does not necessarily mean the engine is producing power.":
        "Drehung bedeutet nicht zwangsläufig, dass der Motor Leistung erzeugt.",
    "Active Cylinders counts cylinders that are firing, not healthy ones. "
    "4 / 4 with one worn bore is normal.":
        "Aktive Zylinder zählt zündende Zylinder, nicht gesunde. "
        "4 / 4 mit einer verschlissenen Laufbuchse ist normal.",

    # from_shale_to_fuel
    "Oil Shale is found deep underground, and is where every engine's fuel starts.":
        "Ölschiefer findet sich tief unter der Erde und ist der Anfang jedes Treibstoffs.",
    "Crush or mill it into Crushed Oil Shale. Crushing Wheels give twice the yield.":
        "Zerkleinere ihn zu zerkleinertem Ölschiefer. Mahlräder liefern die doppelte Ausbeute.",
    "Heat Crushed Oil Shale with Water in a Basin to retort it into Crude Oil.":
        "Erhitze zerkleinerten Ölschiefer mit Wasser im Becken, um Rohöl auszutreiben.",
    "Crack the Crude Oil to get Gasoline, and Petroleum Residue alongside it.":
        "Cracke das Rohöl zu Benzin, wobei Erdölrückstand anfällt.",
    "Blend the Residue with a Zinc Nugget to make Engine Oil.":
        "Vermenge den Rückstand mit einem Zinkklumpen zu Motoröl.",
    "Gasoline runs the engine. Engine Oil keeps it alive. "
    "Check a recipe viewer for exact amounts.":
        "Benzin treibt den Motor an. Motoröl hält ihn am Leben. "
        "Genaue Mengen zeigt ein Rezeptbrowser.",
}


# --------------------------------------------------------------------------
# Reading the scenes
# --------------------------------------------------------------------------

LITERAL = re.compile(r'"((?:[^"\\]|\\.)*)"')


def concatenated(text):
    """The full value of a Java string expression, following `+` across lines."""
    parts, pos = [], 0
    while True:
        match = LITERAL.match(text, pos)
        if not match:
            break
        parts.append(match.group(1))
        pos = match.end()
        joined = re.match(r"\s*\+\s*", text[pos:])
        if not joined:
            break
        pos += joined.end()
    return "".join(parts).replace('\\"', '"')


def read_scenes():
    """Every scene, its title, and its texts in the order Ponder will number them."""
    scenes = {}
    for path in sorted(SRC.glob("*.java")):
        source = path.read_text(encoding="utf-8")
        # A file may hold several scenes, and the text index restarts at each one.
        starts = [(m.start(), m.group(1), m.group(2))
                  for m in re.finditer(r'scene\.title\("([^"]+)",\s*"([^"]+)"\)', source)]
        for index, (start, scene_id, title) in enumerate(starts):
            end = starts[index + 1][0] if index + 1 < len(starts) else len(source)
            body = source[start:end]
            texts = [concatenated(body[m.end():]) for m in re.finditer(r"\.text\(", body)]
            if scene_id in scenes:
                raise SystemExit(f"duplicate ponder scene id: {scene_id}")
            scenes[scene_id] = (title, texts)
    return scenes


def german(english):
    if english not in TRANSLATIONS:
        raise SystemExit(f"no German for: {english!r}\n"
                         f"Add it to TRANSLATIONS in {pathlib.Path(__file__).name}.")
    return TRANSLATIONS[english]


def merge(filename, additions):
    path = LANG / filename
    with open(path, encoding="utf-8") as handle:
        existing = json.load(handle)
    # Drop any ponder key we no longer produce, so a deleted line does not leave
    # a stale translation behind pretending to be current.
    for key in [key for key in existing if key.startswith(PREFIX)]:
        del existing[key]
    existing.update(additions)
    with open(path, "w", encoding="utf-8") as handle:
        json.dump(existing, handle, indent=2, ensure_ascii=False)
        handle.write("\n")
    return len(additions)


def main():
    scenes = read_scenes()
    if not scenes:
        raise SystemExit("no ponder scenes found - has the package moved?")

    english = {f"{PREFIX}tag.{TAG_ID}": TAG_TITLE_EN,
               f"{PREFIX}tag.{TAG_ID}.description": TAG_DESCRIPTION_EN}
    deutsch = {f"{PREFIX}tag.{TAG_ID}": TAG_TITLE_DE,
               f"{PREFIX}tag.{TAG_ID}.description": TAG_DESCRIPTION_DE}

    for scene_id, (title, texts) in sorted(scenes.items()):
        english[f"{PREFIX}{scene_id}.header"] = title
        deutsch[f"{PREFIX}{scene_id}.header"] = german(title)
        for index, text in enumerate(texts):
            english[f"{PREFIX}{scene_id}.text_{index}"] = text
            deutsch[f"{PREFIX}{scene_id}.text_{index}"] = german(text)
        print(f"  {scene_id}: {len(texts)} line(s)")

    print(f"wrote en_us.json (+{merge('en_us.json', english)} ponder keys)")
    print(f"wrote de_de.json (+{merge('de_de.json', deutsch)} ponder keys)")

    unused = set(TRANSLATIONS) - {value for value in english.values()}
    if unused:
        # Not fatal - a line may be about to be added - but always worth saying,
        # because the usual cause is a line that was edited in the Java and left
        # its old German behind.
        print(f"\n{len(unused)} unused translation(s):")
        for value in sorted(unused):
            print(f"  {value!r}")


if __name__ == "__main__":
    main()
