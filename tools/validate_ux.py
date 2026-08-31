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


def main():
    print("checking the player-visible surface\n")
    lang = load_lang()
    check_languages_agree(lang)
    check_advancements(lang)
    check_engine_event_values()
    check_ponder(lang)
    check_structures()
    check_structure_geometry()
    check_tooltips(lang)

    print()
    if problems:
        print(f"{len(problems)} problem(s):")
        for message in problems:
            print(f"  - {message}")
        sys.exit(1)
    print("no problems")


if __name__ == "__main__":
    main()
