#!/usr/bin/env python3
"""Checks that everything the player can see actually exists and lines up.

The mod's UX is spread across four kinds of file - advancement JSON, Ponder
structures, Java storyboards and two language files - and the failure modes are
all the same shape: something references a name that is not there. A missing
translation key renders as `engineered_combustion.ponder.x.text_3` on screen; a
mistyped parent id makes an advancement silently vanish from the tree; a scene
whose schematic is absent throws when a player opens it.

None of that is caught by compiling, and none of it is caught by the simulation
tests, because none of it is code. It is caught here.

    python3 tools/validate_ux.py

Exits non-zero on any problem, so CI fails on a broken reference rather than a
player finding it.

**This does not pretend to test Ponder visuals.** Nothing here says a scene looks
right, is paced well, or teaches what it means to. It says every id resolves,
every key exists, and the structures contain the blocks the scenes reach for.
Whether the tutorial is any good is a question for a person with the game open.
"""
import gzip
import json
import pathlib
import re
import struct
import sys

ROOT = pathlib.Path(__file__).resolve().parents[1]
NS = "engineered_combustion"
DATA = ROOT / "src/main/resources/data" / NS
ASSETS = ROOT / "src/main/resources/assets" / NS
LANG = ASSETS / "lang"
PONDER_NBT = ASSETS / "ponder"
PONDER_SRC = ROOT / "src/main/java/dev/engineeredcombustion/ponder"
ENGINE_SRC = ROOT / "src/main/java/dev/engineeredcombustion/content/engine"

problems = []


def problem(message):
    problems.append(message)


def ok(message, since=None):
    """Reports a check as passing - unless it recorded a problem while running.

    `since` is the problem count from before the check started, so a check that
    both found faults and reached its summary line does not print a reassuring
    "ok" above its own failures.
    """
    if since is not None and len(problems) > since:
        return
    print(f"  ok   {message}")


# ---------------------------------------------------------------------------
# NBT, just enough to read a structure back
# ---------------------------------------------------------------------------

class Reader:
    def __init__(self, data):
        self.data, self.at = data, 0

    def u8(self):
        value = self.data[self.at]
        self.at += 1
        return value

    def u16(self):
        value = struct.unpack_from(">H", self.data, self.at)[0]
        self.at += 2
        return value

    def i32(self):
        value = struct.unpack_from(">i", self.data, self.at)[0]
        self.at += 4
        return value

    def string(self):
        length = self.u16()
        value = self.data[self.at:self.at + length].decode("utf-8")
        self.at += length
        return value

    def payload(self, tag):
        if tag == 3:
            return self.i32()
        if tag == 8:
            return self.string()
        if tag == 9:
            element, count = self.u8(), self.i32()
            return [self.payload(element) for _ in range(count)]
        if tag == 10:
            out = {}
            while True:
                kind = self.u8()
                if kind == 0:
                    return out
                # Name first, THEN value. Written as two statements on purpose:
                # `out[self.string()] = self.payload(kind)` evaluates the
                # right-hand side first and reads the two out of order.
                name = self.string()
                out[name] = self.payload(kind)
        raise ValueError(f"unhandled NBT tag {tag}")


def read_structure(path):
    reader = Reader(gzip.decompress(path.read_bytes()))
    if reader.u8() != 10:
        raise ValueError("root is not a compound")
    reader.string()
    return reader.payload(10)


# ---------------------------------------------------------------------------
# The checks
# ---------------------------------------------------------------------------

def load_lang():
    return {name: json.loads((LANG / f"{name}.json").read_text(encoding="utf-8"))
            for name in ("en_us", "de_de")}


def check_languages_agree(lang):
    """Every key must exist in both files. A key in one only ships untranslated."""
    english, german = set(lang["en_us"]), set(lang["de_de"])
    for missing in sorted(english - german):
        problem(f"de_de is missing {missing}")
    for extra in sorted(german - english):
        problem(f"de_de has {extra}, which en_us does not")
    if english == german:
        ok(f"both language files carry the same {len(english)} keys")

    # An empty string renders as nothing at all, which looks like a bug.
    for name, entries in lang.items():
        for key, value in sorted(entries.items()):
            if not value.strip():
                problem(f"{name}: {key} is empty")

    # A German string identical to the English is usually a forgotten
    # translation. Proper nouns and a few genuinely identical words are not, so
    # this reports rather than fails.
    shared = {key for key in english & german
              if lang["en_us"][key] == lang["de_de"][key]}
    if shared:
        print(f"  note {len(shared)} key(s) identical in both languages "
              f"(names and loanwords, mostly)")


def check_advancements(lang):
    started = len(problems)
    files = sorted((DATA / "advancement").glob("*.json"))
    if not files:
        problem("no advancements found")
        return
    ids = {path.stem for path in files}

    for path in files:
        advancement = json.loads(path.read_text(encoding="utf-8"))
        name = path.stem

        parent = advancement.get("parent")
        if parent is not None:
            if not parent.startswith(f"{NS}:"):
                problem(f"{name}: parent {parent} is not this mod's")
            elif parent.split(":", 1)[1] not in ids:
                problem(f"{name}: parent {parent} does not exist")

        display = advancement.get("display", {})
        for field in ("title", "description"):
            key = display.get(field, {}).get("translate")
            if key is None:
                problem(f"{name}: display.{field} has no translate key")
            elif key not in lang["en_us"]:
                problem(f"{name}: {key} is missing from en_us")

        frame = display.get("frame")
        if frame not in ("task", "goal", "challenge"):
            problem(f"{name}: frame {frame!r} is not a real frame type")

        criteria = advancement.get("criteria", {})
        if not criteria:
            problem(f"{name}: has no criteria and can never be earned")
        for criterion, body in criteria.items():
            trigger = body.get("trigger", "")
            if trigger == f"{NS}:engine_event":
                conditions = body.get("conditions", {})
                if "event" not in conditions:
                    problem(f"{name}/{criterion}: engine_event with no event")

        # Every criterion must appear in requirements, or it is unreachable.
        required = {entry for group in advancement.get("requirements", []) for entry in group}
        for criterion in criteria:
            if criterion not in required:
                problem(f"{name}: criterion {criterion} is in no requirement group")

    roots = [path.stem for path in files
             if json.loads(path.read_text(encoding="utf-8")).get("parent") is None]
    if len(roots) != 1:
        problem(f"expected exactly one root advancement, found {roots}")
    else:
        ok(f"{len(files)} advancements, one root ({roots[0]}), every parent resolves")


def check_engine_event_values():
    """The strings in the advancement JSON must be ones the Java can parse."""
    started = len(problems)
    def enum_ids(path, pattern):
        source = (ENGINE_SRC / path).read_text(encoding="utf-8")
        return set(re.findall(pattern, source))

    events = enum_ids("EngineEvent.java", r'^\t([A-Z_]+)\("([a-z_]+)"\)')
    events = {second for _, second in
              re.findall(r'([A-Z_]+)\("([a-z_]+)"\)',
                         (ENGINE_SRC / "EngineEvent.java").read_text(encoding="utf-8"))}
    conditions = {second for _, second in
                  re.findall(r'([A-Z]+)\("([a-z]+)",',
                             (ENGINE_SRC / "WearCondition.java").read_text(encoding="utf-8"))}
    tracker = (ENGINE_SRC / "EngineEventTracker.java").read_text(encoding="utf-8")
    kinds = {second for _, second in re.findall(r'([A-Z_]+)\("([a-z_]+)"\)', tracker)}
    layouts = {second for _, second in
               re.findall(r'([A-Z_]+)\("([a-z_]+)"\)',
                          (ENGINE_SRC / "EngineEventRecord.java").read_text(encoding="utf-8"))}

    seen_events = set()
    for path in sorted((DATA / "advancement").glob("*.json")):
        advancement = json.loads(path.read_text(encoding="utf-8"))
        for criterion, body in advancement.get("criteria", {}).items():
            if body.get("trigger") != f"{NS}:engine_event":
                continue
            conditions_block = body.get("conditions", {})
            event = conditions_block.get("event")
            seen_events.add(event)
            if event not in events:
                problem(f"{path.stem}: event {event!r} is not an EngineEvent")
            for field in ("min_condition", "max_condition", "improved_to", "improved_from"):
                value = conditions_block.get(field)
                if value is not None and value not in conditions:
                    problem(f"{path.stem}: {field} {value!r} is not a WearCondition")
            for field in ("condition_kind", "abuse_kind"):
                value = conditions_block.get(field)
                if value is not None and value not in kinds:
                    problem(f"{path.stem}: {field} {value!r} is not a tracker kind")
            layout = conditions_block.get("layout")
            if layout is not None and layout not in layouts:
                problem(f"{path.stem}: layout {layout!r} is not an InvalidLayout")

            # Ranges have to be satisfiable, or the advancement is dead.
            for field in ("cylinders", "active_cylinders"):
                span = conditions_block.get(field)
                if span and "min" in span and "max" in span and span["min"] > span["max"]:
                    problem(f"{path.stem}: {field} min > max, so it can never match")
    ok(f"every advancement filter uses a value the Java can parse "
       f"({len(seen_events)} distinct event(s))", started)


def scene_texts():
    """Each Ponder scene id, its title, and its texts in Ponder's own order."""
    literal = re.compile(r'"((?:[^"\\]|\\.)*)"')

    def concatenated(text):
        parts, at = [], 0
        while True:
            match = literal.match(text, at)
            if not match:
                break
            parts.append(match.group(1))
            at = match.end()
            joined = re.match(r"\s*\+\s*", text[at:])
            if not joined:
                break
            at += joined.end()
        return "".join(parts).replace('\\"', '"')

    scenes = {}
    for path in sorted(PONDER_SRC.glob("*.java")):
        source = path.read_text(encoding="utf-8")
        starts = [(m.start(), m.group(1), m.group(2))
                  for m in re.finditer(r'scene\.title\("([^"]+)",\s*"([^"]+)"\)', source)]
        for index, (start, scene_id, title) in enumerate(starts):
            end = starts[index + 1][0] if index + 1 < len(starts) else len(source)
            body = source[start:end]
            texts = [concatenated(body[m.end():]) for m in re.finditer(r"\.text\(", body)]
            if scene_id in scenes:
                problem(f"duplicate ponder scene id: {scene_id}")
            scenes[scene_id] = (title, texts)
    return scenes


def check_ponder(lang):
    started = len(problems)
    scenes = scene_texts()
    if not scenes:
        problem("no ponder scenes found")
        return

    # Every scene needs the schematic it is staged on.
    for scene_id in scenes:
        if not (PONDER_NBT / f"{scene_id}.nbt").exists():
            problem(f"{scene_id}: no structure at ponder/{scene_id}.nbt")

    # Every structure should belong to a scene, or it is dead weight.
    for path in sorted(PONDER_NBT.glob("*.nbt")):
        if path.stem not in scenes:
            problem(f"ponder/{path.name} belongs to no scene")

    # Every line of every scene needs a key in BOTH languages, numbered the way
    # Ponder numbers them, and the English must match what the Java passes.
    for scene_id, (title, texts) in sorted(scenes.items()):
        header = f"{NS}.ponder.{scene_id}.header"
        if header not in lang["en_us"]:
            problem(f"{scene_id}: no header key")
        elif lang["en_us"][header] != title:
            problem(f"{scene_id}: header key says {lang['en_us'][header]!r}, "
                    f"the Java says {title!r}")
        for index, text in enumerate(texts):
            key = f"{NS}.ponder.{scene_id}.text_{index}"
            for name in ("en_us", "de_de"):
                if key not in lang[name]:
                    problem(f"{scene_id}: {name} is missing {key}")
            if key in lang["en_us"] and lang["en_us"][key] != text:
                problem(f"{scene_id}: {key} has drifted from the Java\n"
                        f"        lang: {lang['en_us'][key]!r}\n"
                        f"        java: {text!r}")

    # And no stale keys for lines that no longer exist.
    expected = {f"{NS}.ponder.{scene_id}.header" for scene_id in scenes}
    expected |= {f"{NS}.ponder.{scene_id}.text_{index}"
                 for scene_id, (_, texts) in scenes.items()
                 for index in range(len(texts))}
    expected |= {f"{NS}.ponder.tag.engines", f"{NS}.ponder.tag.engines.description"}
    for key in sorted(k for k in lang["en_us"] if k.startswith(f"{NS}.ponder.")):
        if key not in expected:
            problem(f"stale ponder key with no scene line behind it: {key}")

    total = sum(len(texts) for _, texts in scenes.values())
    ok(f"{len(scenes)} ponder scenes, {total} lines, all keyed in both languages", started)


def check_structures():
    """The structures have to be loadable and to contain real block states."""
    started = len(problems)
    blockstates = ROOT / "src/main/resources/assets" / NS / "blockstates"
    for path in sorted(PONDER_NBT.glob("*.nbt")):
        try:
            structure = read_structure(path)
        except Exception as error:  # noqa: BLE001 - any failure here is the finding
            problem(f"{path.name}: not readable as a structure ({error})")
            continue

        if structure.get("DataVersion") != 3955:
            problem(f"{path.name}: DataVersion {structure.get('DataVersion')} is not 1.21.1's 3955")
        size = structure.get("size", [])
        if len(size) != 3 or any(value <= 0 for value in size):
            problem(f"{path.name}: size {size} is not a box")

        palette = structure.get("palette", [])
        for entry in structure.get("blocks", []):
            if not 0 <= entry.get("state", -1) < len(palette):
                problem(f"{path.name}: block at {entry.get('pos')} has no palette entry")
            position = entry.get("pos", [])
            if len(position) == 3 and any(not 0 <= position[axis] < size[axis] for axis in range(3)):
                problem(f"{path.name}: block at {position} is outside {size}")

        # Our own blocks must use a state the game can actually load.
        for entry in palette:
            name = entry.get("Name", "")
            if not name.startswith(f"{NS}:"):
                continue
            path_part = name.split(":", 1)[1]
            definition = blockstates / f"{path_part}.json"
            if not definition.exists():
                problem(f"{path.name}: {name} has no blockstate file")
                continue
            variants = json.loads(definition.read_text(encoding="utf-8")).get("variants", {})
            properties = entry.get("Properties", {})
            wanted = ",".join(sorted(f"{key}={value}" for key, value in properties.items()))
            available = {",".join(sorted(filter(None, key.split(",")))) for key in variants}
            if wanted not in available:
                problem(f"{path.name}: {name}[{wanted}] is not a variant "
                        f"in {path_part}.json")
    ok(f"{len(list(PONDER_NBT.glob('*.nbt')))} structures load and use real block states", started)


def check_structure_geometry():
    """The scenes must be staged on the layout the game actually enforces."""
    started = len(problems)
    source = (ENGINE_SRC / "EngineComponents.java").read_text(encoding="utf-8")

    def offset_of(method):
        body = re.search(rf"public static BlockPos {method}\(BlockPos \w+\) \{{(.*?)\n\t\}}",
                         source, re.S)
        if not body:
            return None
        text = body.group(1)
        return (text.count(".above()"), text.count(".below()"))

    generator = (ROOT / "tools/generate_ponder_structures.py").read_text(encoding="utf-8")
    table = generator.split("OFFSETS = {")[1].split("}")[0]
    # Only the Y component matters - the engine stacks vertically, and X and Z
    # are zero for all three of these.
    declared = {name: int(y) for name, _x, y, _z in
                re.findall(r'"(\w+)": \((-?\d+), (-?\d+), (-?\d+)\)', table)}

    java = {"cylinder": offset_of("cylinderPos"), "carburetor": offset_of("carburetorPos"),
            "oil_sump": offset_of("oilSumpPos")}
    for name, pair in java.items():
        if pair is None:
            problem(f"could not read {name} offset out of EngineComponents")
            continue
        ups, downs = pair
        expected_y = ups - downs
        if declared.get(name) != expected_y:
            problem(f"the ponder generator places {name} at y{declared.get(name):+d}, "
                    f"but EngineComponents says y{expected_y:+d}")
    ok("the ponder structures use the same engine layout as EngineComponents", started)


# ---------------------------------------------------------------------------
# Ponder highlight targets
# ---------------------------------------------------------------------------
# Added after the first in-game test of the scenes, which found the one class of
# mistake nothing here could see: a scene that points at the wrong thing.
#
# The line about the Air Filter drew a box around most of an engine. The line
# about two Flywheels drew one box spanning both of them AND the crankshaft
# between them. Both compiled, both had correct English in both languages, both
# had a structure that loaded - and both taught the reader something false,
# because a Ponder scene teaches by pointing.
#
# The fix in the Java was to stop writing coordinates by hand: every position now
# comes from a PonderEngine built from the same three numbers this generator uses
# to stamp the schematic. This is the other half - the part a compiler cannot do.
# It resolves every position a scene names and checks that the block really is
# there, in that scene's own structure file.

# Which block each accessor of PonderEngine claims is at the position it returns.
# The Vec3 accessors are here too: a point on a part is a claim about the block
# that holds the part, and pointing at where an Air Filter would be if there were
# a Carburetor is exactly the failure this exists to catch.
TARGET_BLOCKS = {
    "crankshaft": "crankshaft",
    "lastCrankshaft": "crankshaft",
    "ignition": "crankshaft",
    "cylinder": "cylinder",
    "lastCylinder": "cylinder",
    "bore": "cylinder",
    "sparkPlug": "cylinder",
    "carburetor": "carburetor",
    "airFilter": "carburetor",
    "throttle": "carburetor",
    "floatBowl": "carburetor",
    "oilSump": "oil_sump",
    "dipstick": "oil_sump",
    "flywheel": "flywheel",
    "farFlywheel": "flywheel",
}

# Accessors that name a POSITION rather than a block: where a Carburetor or an
# Oil Sump would go on a given section, which on every section but one is empty
# air. The inline scene uses them to reveal a whole section's column at a time.
TARGET_SEATS = {"carburetorSeat", "oilSumpSeat"}

# Which accessors take a section index, and therefore have to be checked against
# every section when the argument is not a constant this can read - a loop
# variable, say. Checking all of them is the right answer for a loop anyway.
INDEXED_TARGETS = {"crankshaft", "cylinder", "bore", "sparkPlug",
                   "carburetorSeat", "oilSumpSeat"}


def generator_offsets():
    """The OFFSETS table out of tools/generate_ponder_structures.py."""
    source = (ROOT / "tools/generate_ponder_structures.py").read_text(encoding="utf-8")
    table = source.split("OFFSETS = {")[1].split("}")[0]
    return {name: (int(x), int(y), int(z)) for name, x, y, z in
            re.findall(r'"(\w+)": \((-?\d+), (-?\d+), (-?\d+)\)', table)}


def generator_engines():
    """The ENGINES table out of tools/generate_ponder_structures.py."""
    source = (ROOT / "tools/generate_ponder_structures.py").read_text(encoding="utf-8")
    table = source.split("ENGINES = {")[1].split("\n}")[0]
    engines = {}
    for scene, x, y, z, sections, accessories in re.findall(
            r'"(\w+)":\s*\(\((-?\d+),\s*(-?\d+),\s*(-?\d+)\),\s*(\d+),\s*"(\w+)"\)', table):
        engines[scene] = ((int(x), int(y), int(z)), int(sections), accessories)
    return engines


def scene_engines():
    """Every PonderEngine a scene uses, by scene id, out of the Java."""
    declaration = re.compile(
        r"PonderEngine (\w+) = PonderEngine\.(of|endLoaded)"
        r"\((-?\d+), (-?\d+), (-?\d+), (\d+)\)")
    constant = re.compile(r"static final int (\w+) = (\d+);")
    scenes = {}
    for path in sorted(PONDER_SRC.glob("*.java")):
        source = path.read_text(encoding="utf-8")
        engines = {name: ((int(x), int(y), int(z)), int(sections),
                          "first" if kind == "of" else "last")
                   for name, kind, x, y, z, sections in declaration.findall(source)}
        integers = {name: int(value) for name, value in constant.findall(source)}
        starts = [(m.start(), m.group(1))
                  for m in re.finditer(r'scene\.title\("([^"]+)",\s*"[^"]+"\)', source)]
        for index, (start, scene_id) in enumerate(starts):
            end = starts[index + 1][0] if index + 1 < len(starts) else len(source)
            scenes[scene_id] = (path.name, engines, integers, source[start:end])
    return scenes


def check_ponder_targets():
    """Every block a scene names has to be in that scene's structure."""
    started = len(problems)
    declared = generator_engines()
    scenes = scene_engines()
    call = re.compile(r"\b([A-Z][A-Z_]*)\.(\w+)\(([^()]*)\)")
    checked = 0

    for scene_id, (filename, engines, integers, body) in sorted(scenes.items()):
        blocks = {}
        try:
            structure = read_structure(PONDER_NBT / f"{scene_id}.nbt")
        except Exception as error:  # noqa: BLE001 - any failure here is the finding
            problem(f"{scene_id}: cannot read its structure ({error})")
            continue
        palette = structure.get("palette", [])
        for entry in structure.get("blocks", []):
            name = palette[entry["state"]].get("Name", "")
            blocks[tuple(entry["pos"])] = name.split(":", 1)[-1]
        size = structure.get("size", [0, 0, 0])

        used = sorted({name for name, _, _ in call.findall(body) if name in engines})
        for name in used:
            if engines[name] != declared.get(scene_id):
                problem(f"{scene_id} ({filename}): {name} is "
                        f"{engines[name]}, but the structure generator stands this "
                        f"scene's engine at {declared.get(scene_id)}")

        origin, sections, accessories = declared.get(scene_id, ((0, 0, 0), 0, "first"))
        accessory = sections - 1 if accessories == "last" else 0

        for name, accessor, arguments in call.findall(body):
            if name not in engines or accessor not in TARGET_BLOCKS.keys() | TARGET_SEATS:
                continue
            arguments = arguments.strip()
            if accessor in INDEXED_TARGETS:
                if re.fullmatch(r"\d+", arguments):
                    indices = [int(arguments)]
                elif arguments in integers:
                    indices = [integers[arguments]]
                else:
                    # A loop variable or an expression. Every section has to hold
                    # up, which is what a loop over the sections means anyway.
                    indices = list(range(sections))
            else:
                indices = [accessory]

            for index in indices:
                if not 0 <= index < sections:
                    problem(f"{scene_id} ({filename}): {name}.{accessor}({index}) is outside "
                            f"an engine with {sections} section(s)")
                    continue
                position = target_position(origin, accessor, index, sections)
                if any(not 0 <= position[axis] < size[axis] for axis in range(3)):
                    problem(f"{scene_id} ({filename}): {name}.{accessor}({index}) is at "
                            f"{position}, outside the {size} structure")
                    continue
                checked += 1
                if accessor in TARGET_SEATS:
                    continue
                found = blocks.get(position)
                wanted = TARGET_BLOCKS[accessor]
                if found != wanted:
                    problem(f"{scene_id} ({filename}): {name}.{accessor}"
                            f"({index if accessor in INDEXED_TARGETS else ''}) points at "
                            f"{position}, where the structure has "
                            f"{found or 'nothing'} rather than a {wanted}")

    ok(f"{len(scenes)} ponder scenes: {checked} highlight target(s), each on the block "
       f"its scene names", started)


def target_position(origin, accessor, index, sections):
    """Where one PonderEngine accessor lands, in structure coordinates.

    The same arithmetic the Java does, from the same OFFSETS table
    check_structure_geometry has already compared against EngineComponents - so
    this is not a third opinion about where a Carburetor goes.
    """
    offsets = generator_offsets()

    if accessor in ("flywheel",):
        return (origin[0] + sections, origin[1], origin[2])
    if accessor == "farFlywheel":
        return (origin[0] - 1, origin[1], origin[2])
    if accessor == "ignition":
        return origin
    if accessor == "lastCrankshaft":
        index = sections - 1
    if accessor == "lastCylinder":
        index = sections - 1
    section = (origin[0] + index, origin[1], origin[2])
    part = {"crankshaft": None, "lastCrankshaft": None,
            "cylinder": "cylinder", "lastCylinder": "cylinder",
            "bore": "cylinder", "sparkPlug": "cylinder",
            "carburetor": "carburetor", "airFilter": "carburetor",
            "throttle": "carburetor", "floatBowl": "carburetor",
            "carburetorSeat": "carburetor",
            "oilSump": "oil_sump", "dipstick": "oil_sump",
            "oilSumpSeat": "oil_sump"}[accessor]
    if part is None:
        return section
    delta = offsets[part]
    return (section[0] + delta[0], section[1] + delta[1], section[2] + delta[2])


# ---------------------------------------------------------------------------
# Ponder section lifecycle
# ---------------------------------------------------------------------------
# Added after a real 1.21.1 client crash that every other check here missed:
#
#   NullPointerException: Cannot invoke "Selection.substract(Selection)"
#   because "this.section" is null
#       at WorldSectionElementImpl.erase(WorldSectionElementImpl.java:112)
#       from PonderSceneBuilder$PonderWorldInstructions.lambda$hideSection$3
#
# The lifecycle, read out of Ponder 1.0.82's own source:
#
#   * PonderScene creates its base WorldSectionElement with the NO-ARG
#     constructor, so `section` is null, and resets it to null on every replay
#     (PonderScene.java:143 and :238).
#   * showSection(sel, dir) schedules a DisplayWorldSectionInstruction with a
#     15-tick fade. Only when that fade COMPLETES does it call
#     element.mergeOnto(baseWorldSection), which is what finally gives the base
#     a non-null selection.
#   * hideSection(sel, dir) immediately calls getBaseWorldSection().erase(sel),
#     and erase() dereferences `section`.
#
# So a hideSection is only safe once some earlier base-merging show has had 15
# ticks to land. Anything sooner is a hard client crash, and it is a crash a
# compiler cannot see because every one of those calls type-checks perfectly.
#
# This cannot execute Ponder. It reads the scene sources in order and applies
# that one rule, plus the weaker rule that hiding a selection nobody ever showed
# is meaningless even when it does not crash.

# DisplayWorldSectionInstruction's fade length, from the Ponder source.
BASE_MERGE_TICKS = 15


def _call_argument(source, at):
    """The text of a call's argument list, given the index of its opening paren."""
    depth = 0
    for index in range(at, len(source)):
        if source[index] == "(":
            depth += 1
        elif source[index] == ")":
            depth -= 1
            if depth == 0:
                return source[at + 1:index]
    return ""


def _selection_text(arguments):
    """A call's Selection argument, normalised enough to compare two of them."""
    # Everything up to the trailing Direction / Pointing argument.
    cut = arguments.rfind(", Direction.")
    if cut == -1:
        cut = len(arguments)
    text = arguments[:cut]
    text = text.replace("util.select()", "").replace("scene.getSceneBuildingUtil().select()", "")
    return re.sub(r"\s+", "", text)


def scene_section_events():
    """Every section-lifecycle call in every scene, in source order."""
    calls = re.compile(
        r"\.(showSection|hideSection|showSectionAndMerge|showIndependentSectionImmediately"
        r"|showIndependentSection|makeSectionIndependent|hideIndependentSection|glueBlockOnto)\s*\(")
    idles = re.compile(r"scene\.(idle|idleSeconds)\s*\(\s*(\d+)")
    plate = re.compile(r"scene\.showBasePlate\s*\(")

    scenes = {}
    for path in sorted(PONDER_SRC.glob("*.java")):
        source = path.read_text(encoding="utf-8")
        starts = [(m.start(), m.group(1))
                  for m in re.finditer(r'scene\.title\("([^"]+)",\s*"[^"]+"\)', source)]
        for index, (start, scene_id) in enumerate(starts):
            end = starts[index + 1][0] if index + 1 < len(starts) else len(source)
            body = source[start:end]
            events = []
            for match in plate.finditer(body):
                events.append((match.start(), "showBasePlate", "", 0))
            for match in idles.finditer(body):
                ticks = int(match.group(2)) * (20 if match.group(1) == "idleSeconds" else 1)
                events.append((match.start(), "idle", "", ticks))
            for match in calls.finditer(body):
                arguments = _call_argument(body, match.end() - 1)
                events.append((match.start(), match.group(1), _selection_text(arguments), 0))
            events.sort()
            scenes[scene_id] = (path.name, events)
    return scenes


def check_ponder_section_lifecycle():
    """A hideSection must never run before the base section exists."""
    started = len(problems)
    scenes = scene_section_events()
    checked = 0

    for scene_id, (filename, events) in sorted(scenes.items()):
        # Ticks elapsed since the FIRST show that merges into the base section.
        # None until one has been scheduled.
        since_first_base_show = None
        shown = set()
        links_created = 0
        independent_used = False

        for _, kind, selection, ticks in events:
            if kind == "idle":
                if since_first_base_show is not None:
                    since_first_base_show += ticks

            elif kind in ("showBasePlate", "showSection"):
                if since_first_base_show is None:
                    since_first_base_show = 0
                if kind == "showSection":
                    shown.add(selection)
                else:
                    shown.add("__baseplate__")

            elif kind in ("showIndependentSection", "showIndependentSectionImmediately",
                          "makeSectionIndependent"):
                links_created += 1
                independent_used = True

            elif kind == "hideIndependentSection":
                if links_created == 0:
                    problem(f"{scene_id} ({filename}): hideIndependentSection with no "
                            f"ElementLink ever created")

            elif kind == "hideSection":
                checked += 1
                if since_first_base_show is None:
                    problem(f"{scene_id} ({filename}): hideSection({selection}) runs before ANY "
                            f"showSection or showBasePlate - the base world section is still "
                            f"null and Ponder will crash in WorldSectionElementImpl.erase")
                elif since_first_base_show < BASE_MERGE_TICKS:
                    problem(f"{scene_id} ({filename}): hideSection({selection}) runs only "
                            f"{since_first_base_show} tick(s) after the first show, but the base "
                            f"section does not exist until that fade completes at "
                            f"{BASE_MERGE_TICKS} ticks - Ponder will crash")
                elif selection not in shown:
                    problem(f"{scene_id} ({filename}): hideSection({selection}) hides a selection "
                            f"no showSection ever revealed. Ponder blocks are invisible until "
                            f"shown, so this hides nothing - and if it is meant to keep something "
                            f"hidden, simply do not show it")
                if independent_used:
                    problem(f"{scene_id} ({filename}): hideSection({selection}) is used in a scene "
                            f"that also creates independent sections - Selection-based hiding acts "
                            f"on the BASE section and cannot hide an independent one; use "
                            f"hideIndependentSection(link, ...) instead")

    ok(f"{len(scenes)} ponder scenes: {checked} hideSection call(s), each acting on a base "
       f"section that exists by then", started)


def check_ponder_highlight_lifetimes():
    """No text step may be read while an EARLIER step's highlight is still drawn.

    THIS IS THE ONE THE PLAY-TEST FOUND, and it is not what "the highlight points
    at the wrong block" sounds like. Every outline in these scenes points at the
    right block for the step that started it. What went wrong is that outlines
    OUTLIVE their step: `showOutline(..., 80)` draws for eighty ticks, and if the
    step it belongs to has moved on after fifty, the next sentence is read with the
    previous sentence's box still on screen. A player being told about the Air
    Filter while two red boxes sit round the flywheels does not conclude that the
    boxes are stale - they conclude the mod is pointing at the flywheels.

    So the timeline is walked the way Ponder walks it. `idle(n)` advances the
    clock; an outline is alive for its declared duration from the moment it is
    declared; a text step begins when `showText` is declared. Any outline still
    alive when the NEXT step's text begins, and not re-declared for that step, is a
    stale highlight and is reported with the sentence it would have contaminated.

    Deliberately not a check that the outline names the right block - that is
    check_ponder_targets, and it passes. This is the other half.
    """
    started = len(problems)
    checked = 0
    statement = re.compile(
        r"\.idle\((\d+)\)"
        r'|showOutline\(\s*PonderPalette\.(\w+),\s*"([^"]+)",.*?,\s*(\d+)\s*\)\s*;'
        r"|showText\((\d+)\)")

    for path in sorted(PONDER_SRC.glob("*Scenes.java")):
        source = re.sub(r"\s+", " ", path.read_text(encoding="utf-8"))
        for scene in re.finditer(r'scene\.title\("([^"]+)"', source):
            scene_id = scene.group(1)
            end = source.find("scene.title(", scene.end())
            body = source[scene.end():end if end > 0 else len(source)]

            clock = 0
            # name -> the clock time at which it stops being drawn
            alive = {}
            # what the step currently being built has (re-)declared
            declared = set()
            step = 0
            for match in statement.finditer(body):
                if match.group(1):
                    clock += int(match.group(1))
                elif match.group(2):
                    alive[match.group(3)] = clock + int(match.group(4))
                    declared.add(match.group(3))
                else:
                    step += 1
                    checked += 1
                    stale = sorted(name for name, until in alive.items()
                                   if until > clock and name not in declared)
                    if stale:
                        problem(f"{scene_id}: step {step} is read with "
                                f"{', '.join(stale)} still outlined from an earlier "
                                f"step - re-declare the outline for this step or "
                                f"shorten it so it ends first")
                    alive = {name: until for name, until in alive.items()
                             if until > clock and name in declared}
                    declared = set()
    ok(f"{checked} ponder text step(s), none read under a stale highlight", started)


# What each engine part is CALLED in a sentence, and which PonderEngine accessors
# put a box round it. The left column is what a player reads; the right is what
# they must be looking at while they read it.
#
# Two accessors sit under more than one noun on purpose. A Piston Assembly and a
# Spark Plug are installed INSIDE a Cylinder block, so the finest box either can
# have is that block - and a sentence about a piston is correctly answered by an
# outlined cylinder. Everything else is one part, one box.
HIGHLIGHT_NOUNS = {
    "crankshaft": {"crankshaft", "lastCrankshaft", "ignition"},
    "cylinder": {"cylinder", "lastCylinder", "bore", "sparkPlug"},
    "piston": {"cylinder", "lastCylinder", "bore"},
    "spark plug": {"cylinder", "lastCylinder", "sparkPlug"},
    "camshaft": {"crankshaft", "lastCrankshaft"},
    "carburetor": {"carburetor", "carburetorSeat", "throttle", "floatBowl", "airFilter"},
    "throttle": {"carburetor", "carburetorSeat", "throttle"},
    "gasoline": {"carburetor", "carburetorSeat", "floatBowl"},
    "air filter": {"carburetor", "carburetorSeat", "airFilter"},
    "oil sump": {"oilSump", "oilSumpSeat", "dipstick"},
    "engine oil": {"oilSump", "oilSumpSeat", "dipstick"},
    "flywheel": {"flywheel", "farFlywheel"},
}

# A fromTo draws ONE box from one corner to the other, so it contains everything
# between - and a sentence about something in the middle is correctly answered by
# it. Each entry is the pair of endpoints and what the box between them swallows.
#
# Both of these are boxes a scene genuinely wants. Sump to carburetor is the whole
# engine, which is what "this engine is complete" and the goggle readout steps are
# about. Flywheel to flywheel spans the crank run between them, which is what
# "either end of the crankshaft will do" is about.
SPANS = [
    ({"oilSump", "oilSumpSeat"}, {"carburetor", "carburetorSeat"}, None),
    ({"flywheel"}, {"farFlywheel"}, {"crankshaft", "lastCrankshaft", "ignition"}),
]

# Steps where a part's NAME appears without the sentence being about the part.
# Each one is a user-interface label that happens to contain a part's name, and
# each is listed with the label so the exemption can be checked rather than
# trusted. Keyed by scene and step number; the list may only grow with a reason.
HIGHLIGHT_LABEL_EXEMPTIONS = {
    # "Manual, Ignition, Throttle, or both" are the Redstone Control Module's four
    # MODE names, not a reference to the Carburetor. The module and its value box
    # are in the Crankshaft, which is what the step correctly outlines.
    ("engine_controls", 5): {"throttle"},
}


def check_ponder_highlights_teach_their_sentence():
    """Whatever a sentence NAMES has to be inside the box drawn while it is read.

    The milestone's rule, made mechanical: "for every text step, identify exactly
    what object the sentence teaches, and highlight THAT object". The check cannot
    read English, so it does the half it can - it finds the engine parts a sentence
    names by name, and insists each one is inside one of the outlines alive while
    that sentence is up. A step that says "One crankshaft, one Carburetor, one Oil
    Sump, one Flywheel" and boxes only the cylinders fails; a step that says
    "The Flywheel transfers engine power" and boxes the Flywheel passes.

    What it deliberately does NOT do is insist the box is tight. A sentence about a
    Spark Plug is answered by an outlined Cylinder, because that is the block the
    plug is inside and there is no finer box to draw - see HIGHLIGHT_NOUNS.

    Steps with no outline at all are skipped rather than failed: several teach a
    Create process that has no block in the scene to point at, and pointing at
    something anyway would be worse than pointing at nothing.
    """
    started = len(problems)
    checked = 0
    accessor = re.compile(r"\b[A-Z][A-Z_]*\.(\w+)\(")
    statement = re.compile(
        r"\.idle\((\d+)\)"
        r'|showOutline\(\s*PonderPalette\.\w+,\s*"[^"]+",(.*?),\s*\d+\s*\)\s*;'
        r'|showText\(\d+\)\s*((?:\.\w+\([^;]*?\))*?)\s*\.text\(\s*"([^"]*)"')

    for path in sorted(PONDER_SRC.glob("*Scenes.java")):
        source = re.sub(r"\s+", " ", path.read_text(encoding="utf-8"))
        for scene in re.finditer(r'scene\.title\("([^"]+)"', source):
            scene_id = scene.group(1)
            end = source.find("scene.title(", scene.end())
            body = source[scene.end():end if end > 0 else len(source)]

            outlined = set()
            step = 0
            for match in statement.finditer(body):
                if match.group(2) is not None:
                    outlined.update(accessor.findall(match.group(2)))
                    continue
                if match.group(4) is None:
                    continue
                step += 1
                text = match.group(4).lower()
                if outlined:
                    checked += 1
                    covered = set(outlined)
                    everything = False
                    for lower, upper, swallowed in SPANS:
                        if not (lower & outlined and upper & outlined):
                            continue
                        if swallowed is None:
                            everything = True
                        else:
                            covered |= swallowed
                    exempt = HIGHLIGHT_LABEL_EXEMPTIONS.get((scene_id, step), set())
                    for noun, accessors in HIGHLIGHT_NOUNS.items():
                        if noun in exempt or everything:
                            continue
                        if noun in text and not (accessors & covered):
                            problem(f"{scene_id}: step {step} says \"{noun}\" but "
                                    f"outlines only {sorted(covered)} - highlight the "
                                    "thing the sentence teaches")
                outlined = set()
    ok(f"{checked} outlined ponder step(s), each boxing what its sentence names", started)


def check_tooltips(lang):
    """Tooltip lines are numbered from 1 with no gaps, or the scan stops early."""
    started = len(problems)
    pattern = re.compile(rf"^{re.escape(NS)}\.tooltip\.([a-z_]+)\.(\d+)$")
    lines = {}
    for key in lang["en_us"]:
        match = pattern.match(key)
        if match:
            lines.setdefault(match.group(1), set()).add(int(match.group(2)))
    for item, numbers in sorted(lines.items()):
        if numbers != set(range(1, len(numbers) + 1)):
            problem(f"tooltip for {item} is numbered {sorted(numbers)}, "
                    f"which has a gap - the reader stops at the first miss")
    ok(f"{len(lines)} item tooltips, all numbered from 1 without gaps", started)


def registered_blocks():
    """Every block ECBlocks registers, read out of the Java rather than listed here."""
    source = (ROOT / "src/main/java/dev/engineeredcombustion/registry/ECBlocks.java").read_text()
    return sorted(set(re.findall(r'BLOCKS\.register\("([a-z_]+)"', source)))


def registered_items():
    """Every item ECItems registers, likewise."""
    source = (ROOT / "src/main/java/dev/engineeredcombustion/registry/ECItems.java").read_text()
    return sorted(set(re.findall(r'ITEMS\.register\("([a-z_]+)"', source)))


def check_blocks_are_obtainable(lang):
    """A registered block must be minable, droppable and nameable.

    THIS CHECK EXISTS BECAUSE ALL FIVE MACHINE BLOCKS FAILED IT. Every one of them
    is registered with `requiresCorrectToolForDrops()`, which is satisfied by
    membership of a `mineable/*` tag and by nothing else - and only Oil Shale was
    in one. So mining a Crankshaft, a Cylinder, a Flywheel, a Carburetor or an Oil
    Sump destroyed it and returned nothing, with a perfectly correct loot table
    sitting beside it that never ran. Nothing compiled wrong, no test failed, and
    the only way to find it was to break a block in a real world.

    Four things every placeable block needs, checked against the registry itself
    rather than against a list somebody has to remember to update:

      * a `mineable/pickaxe` entry, or it drops nothing, ever;
      * a mining-tier entry, or the tier is unstated;
      * a loot table, or there is nothing to drop;
      * a name in both languages, or it reads as its own key on screen.
    """
    started = len(problems)
    blocks = registered_blocks()

    def tag_values(path):
        file = DATA.parent / path
        if not file.exists():
            problem(f"{path} is missing entirely")
            return set()
        return set(json.loads(file.read_text())["values"])

    mineable = tag_values("minecraft/tags/block/mineable/pickaxe.json")
    tiered = tag_values("minecraft/tags/block/needs_stone_tool.json")

    for block in blocks:
        full = f"{NS}:{block}"
        if full not in mineable:
            problem(f"block {block} is not in mineable/pickaxe - it will drop "
                    f"NOTHING, because it requires a correct tool for drops")
        if full not in tiered:
            problem(f"block {block} is in no mining-tier tag")
        if not (DATA / "loot_table/blocks" / f"{block}.json").exists():
            problem(f"block {block} has no loot table, so it drops nothing")
        for code in ("en_us", "de_de"):
            key = f"block.{NS}.{block}"
            item_key = f"item.{NS}.{block}"
            if key not in lang[code] and item_key not in lang[code]:
                problem(f"block {block} has no name in {code}")
    ok(f"{len(blocks)} blocks, each minable, tiered, dropping and named", started)


def check_items_are_named(lang):
    """A registered item must have a name in both languages and a model."""
    started = len(problems)
    items = registered_items()
    models = ASSETS / "models/item"
    for item in items:
        for code in ("en_us", "de_de"):
            if f"item.{NS}.{item}" not in lang[code] and f"block.{NS}.{item}" not in lang[code]:
                problem(f"item {item} has no name in {code}")
        if not (models / f"{item}.json").exists():
            problem(f"item {item} has no model, so it renders as a missing texture")
    ok(f"{len(items)} items, each named in both languages and modelled", started)


def main():
    print("checking the player-visible surface\n")
    lang = load_lang()
    check_languages_agree(lang)
    check_advancements(lang)
    check_engine_event_values()
    check_ponder(lang)
    check_structures()
    check_structure_geometry()
    check_ponder_targets()
    check_ponder_section_lifecycle()
    check_ponder_highlight_lifetimes()
    check_ponder_highlights_teach_their_sentence()
    check_tooltips(lang)
    check_blocks_are_obtainable(lang)
    check_items_are_named(lang)

    print()
    if problems:
        print(f"{len(problems)} problem(s):")
        for message in problems:
            print(f"  - {message}")
        sys.exit(1)
    print("no problems")


if __name__ == "__main__":
    main()
