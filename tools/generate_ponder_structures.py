#!/usr/bin/env python3
"""Generates the structure files the Ponder scenes are staged on.

A Ponder scene is a storyboard plus a **schematic**: a vanilla structure NBT
holding every block the scene will ever show, which the storyboard then reveals,
hides and modifies. Ponder loads them from
`assets/<namespace>/ponder/<name>.nbt` - see `PonderSceneRegistry.loadSchematic`.

Those files are normally produced in-game with a schematic tool. They are
produced here instead, for the same reason the engine's models and textures are:
a file nobody can regenerate is a file nobody can safely change, and "open the
game, rebuild the scene by hand, re-export" is not a thing a reviewer can check.
Writing NBT by hand is a hundred lines of struct calls - the same approach
`generate_engine_textures.py` already takes with PNG.

    python3 tools/generate_ponder_structures.py

The engine's geometry is NOT duplicated here. It is stated once, in the OFFSETS
table below, and that table is a transcription of `EngineComponents`:

    cylinder   = crankshaft + (0, 1, 0)
    carburetor = crankshaft + (0, 2, 0)
    oil sump   = crankshaft + (0, -1, 0)
    flywheel   = beyond either end of the run, along the crank axis

If those ever diverge the scenes would teach a layout the game refuses to build,
which is the one thing the milestone says a tutorial must never do - so
`PonderStructureTests` asserts the two agree.
"""
import gzip
import os
import pathlib
import struct

ROOT = pathlib.Path(__file__).resolve().parents[1]
OUT = ROOT / "src/main/resources/assets/engineered_combustion/ponder"
NS = "engineered_combustion"

# 1.21.1. Stamped into every structure file so the game does not treat them as
# needing a data fix.
DATA_VERSION = 3955


# ===========================================================================
# A very small NBT writer
# ===========================================================================
# Big-endian, gzipped, exactly as NbtIo.read expects. Only the tag types a
# structure file actually uses are implemented; anything else would be dead code
# pretending to be a library.

TAG_END, TAG_INT, TAG_STRING, TAG_LIST, TAG_COMPOUND = 0, 3, 8, 9, 10


def _string(value):
    encoded = value.encode("utf-8")
    return struct.pack(">H", len(encoded)) + encoded


def _payload(value):
    """The tag id and body for a Python value."""
    if isinstance(value, int):
        return TAG_INT, struct.pack(">i", value)
    if isinstance(value, str):
        return TAG_STRING, _string(value)
    if isinstance(value, dict):
        body = b""
        for key, item in value.items():
            tag, encoded = _payload(item)
            body += struct.pack(">B", tag) + _string(key) + encoded
        return TAG_COMPOUND, body + struct.pack(">B", TAG_END)
    if isinstance(value, list):
        if not value:
            # An empty list still has to declare an element type. END is what
            # vanilla writes and what the reader accepts.
            return TAG_LIST, struct.pack(">Bi", TAG_END, 0)
        element_tags = {_payload(item)[0] for item in value}
        if len(element_tags) != 1:
            raise ValueError("an NBT list must be all one type")
        element_tag = element_tags.pop()
        body = struct.pack(">Bi", element_tag, len(value))
        for item in value:
            body += _payload(item)[1]
        return TAG_LIST, body
    raise TypeError(f"no NBT encoding for {type(value).__name__}")


def write_nbt(path, root):
    tag, body = _payload(root)
    assert tag == TAG_COMPOUND
    # A named root compound, with the empty name vanilla uses.
    data = struct.pack(">B", TAG_COMPOUND) + _string("") + body
    os.makedirs(path.parent, exist_ok=True)
    # mtime=0 so the file is byte-reproducible and CI's "generated assets are up
    # to date" check compares content rather than the clock.
    with open(path, "wb") as raw:
        with gzip.GzipFile(fileobj=raw, mode="wb", mtime=0) as gz:
            gz.write(data)


# ===========================================================================
# Blocks
# ===========================================================================

def block(name, **properties):
    """One palette entry: a block id and its state, if it has one."""
    entry = {"Name": name}
    if properties:
        entry["Properties"] = {key: str(value) for key, value in properties.items()}
    return entry


AIR = block("minecraft:air")


class Structure:
    """A box of blocks, assembled by coordinate and written as a structure file."""

    def __init__(self, size):
        self.size = size
        self.blocks = {}

    def set(self, pos, state):
        x, y, z = pos
        if not (0 <= x < self.size[0] and 0 <= y < self.size[1] and 0 <= z < self.size[2]):
            raise ValueError(f"{pos} is outside a {self.size} structure")
        self.blocks[pos] = state

    def fill(self, start, end, state):
        for x in range(start[0], end[0] + 1):
            for y in range(start[1], end[1] + 1):
                for z in range(start[2], end[2] + 1):
                    self.set((x, y, z), state)

    def to_nbt(self):
        palette = []
        indices = {}
        entries = []
        # Sorted so the file is byte-identical every run regardless of insertion
        # order - the other half of being reproducible.
        for pos in sorted(self.blocks):
            state = self.blocks[pos]
            key = (state["Name"], tuple(sorted(state.get("Properties", {}).items())))
            if key not in indices:
                indices[key] = len(palette)
                palette.append(state)
            entries.append({"pos": list(pos), "state": indices[key]})
        return {
            "DataVersion": DATA_VERSION,
            "size": list(self.size),
            "palette": palette,
            "blocks": entries,
            "entities": [],
        }


# ===========================================================================
# The engine's geometry - a transcription of EngineComponents
# ===========================================================================
# Offsets from a crankshaft section to the component that belongs to it. Asserted
# against the Java by PonderStructureTests.

OFFSETS = {
    "cylinder": (0, 1, 0),
    "carburetor": (0, 2, 0),
    "oil_sump": (0, -1, 0),
}


def me(path):
    return f"{NS}:{path}"


# ===========================================================================
# Where each scene's engine stands
# ===========================================================================
# Declared in one table rather than inside the scene builders, because the Java
# has to agree with it: every highlight in every scene comes from a PonderEngine
# built from these same three numbers, and `tools/validate_ux.py` compares the
# two tables and then checks that each block a scene names really is where it
# says. An engine that moves in this table therefore moves in the scenes, or the
# build fails.
#
#   origin       the first (negative-end) crankshaft section
#   sections     how many, i.e. which inline layout
#   accessories  which section carries the single Carburetor and Oil Sump
ENGINES = {
    "assembling_an_engine": ((3, 2, 2), 1, "first"),
    "fuel_and_lubrication": ((3, 2, 2), 1, "first"),
    "starting_an_engine": ((3, 2, 2), 1, "first"),
    "inline_engines": ((2, 2, 2), 4, "last"),
    "engine_controls": ((3, 2, 2), 1, "first"),
    "engine_maintenance": ((2, 2, 2), 4, "first"),
    "diagnosing_an_engine": ((3, 2, 2), 2, "first"),
    "the_four_stroke_cycle": ((3, 2, 2), 1, "first"),
}


def place_engine(structure, scene, axis="x", flywheel_at="end"):
    """Stamps a whole inline engine into a structure, in the game's own layout.

    Sections run along `axis` from the origin in ENGINES, and the Flywheel goes
    beyond whichever end `flywheel_at` names - both ends are valid in the real
    game, and the assembly scene shows that.

    `accessories` picks which section carries the single Carburetor and Oil
    Sump. It exists for the inline scene, which grows an engine one section at a
    time and therefore has to START from a section that is already a complete,
    runnable engine - which means the Carburetor, the Oil Sump and the Flywheel
    all have to be on the same end.

    THE COSMETIC STATES ARE SET HERE TOO, and that is the point of this pass. An
    engine's shared castings - the crankcase seams, the intake manifold running
    the length of the engine, the way the Carburetor and Oil Sump are turned -
    all live in block state properties that the game computes from the
    neighbours. A Ponder structure is loaded as-is and never sees a neighbour
    update, so a scene showing an inline-4 with those properties unset would show
    a machine the player will never build. They are written out here instead,
    from the same run this function is already stamping.
    """
    origin, sections, accessories = ENGINES[scene]
    step = (1, 0, 0) if axis == "x" else (0, 0, 1)
    positions = [tuple(origin[i] + step[i] * n for i in range(3)) for n in range(sections)]

    accessory_index = sections - 1 if accessories == "last" else 0

    for index, crank in enumerate(positions):
        behind = index > 0
        ahead = index < sections - 1
        # JOINED says a section has a neighbour further along the run, which is
        # what makes an inline engine render as one crankcase rather than as a
        # row of separate ones - and what leaves the ignition switch off every
        # section but the controller.
        structure.set(crank, block(me("crankshaft"), axis=axis,
                                   joined="true" if behind else "false",
                                   lit="false"))
        # The Cylinder carries its share of the shared intake manifold: which way
        # the row of bores continues decides whether its length of rail runs
        # through to the block boundary or is capped inside it.
        structure.set(offset(crank, OFFSETS["cylinder"]),
                      block(me("cylinder"), axis=axis,
                            manifold_negative=lower(behind),
                            manifold_positive=lower(ahead)))
        if index == accessory_index:
            structure.set(offset(crank, OFFSETS["carburetor"]),
                          block(me("carburetor"), axis=axis))
            structure.set(offset(crank, OFFSETS["oil_sump"]),
                          block(me("oil_sump"), axis=axis))

    if flywheel_at is not None:
        end = positions[-1] if flywheel_at == "end" else positions[0]
        direction = 1 if flywheel_at == "end" else -1
        flywheel = tuple(end[i] + step[i] * direction for i in range(3))
        structure.set(flywheel, block(me("flywheel"), axis=axis))
    return positions


def lower(flag):
    return "true" if flag else "false"


def offset(pos, delta):
    return tuple(pos[i] + delta[i] for i in range(3))


def base_plate(structure, size, y=0, state=None):
    """The floor every Ponder scene stands on."""
    structure.fill((0, y, 0), (size[0] - 1, y, size[2] - 1),
                   state or block("minecraft:andesite"))


# ===========================================================================
# The scenes
# ===========================================================================
# Each returns a finished Structure. The storyboards in Java decide what is
# revealed when; everything they could ever reveal has to be here.

def assembling_an_engine():
    """An inline-1, built one component at a time, with room for a second Flywheel."""
    size = (7, 6, 5)
    s = Structure(size)
    base_plate(s, size)
    # The sump hangs BELOW the crankshaft, so the crank sits at y=2 to leave it
    # somewhere to go that is not inside the base plate.
    place_engine(s, "assembling_an_engine")
    # The other end is equally valid, and the scene shows that before showing
    # that BOTH at once is not an engine.
    s.set((2, 2, 2), block(me("flywheel"), axis="x"))
    return s


def fuel_and_lubrication():
    """An inline-1 with its Carburetor and Oil Sump, plus somewhere to stand."""
    size = (7, 6, 5)
    s = Structure(size)
    base_plate(s, size)
    place_engine(s, "fuel_and_lubrication")
    return s


def starting_an_engine():
    """An inline-1 with a Create Hand Crank on the far end of the shaft."""
    size = (7, 6, 5)
    s = Structure(size)
    base_plate(s, size)
    place_engine(s, "starting_an_engine")
    # Create's own Hand Crank, which is how an engine is really turned over.
    s.set((2, 2, 2), block("create:hand_crank", facing="west"))
    return s


def inline_engines():
    """Room for four sections, a Flywheel, and a fifth section that is refused.

    The Carburetor and Oil Sump sit on the LAST section, beside the Flywheel,
    because the scene reveals that end first and grows away from it - so the very
    first thing shown is a complete inline-1 rather than a crankshaft with no fuel
    or oil, which is not an engine and must not be presented as one.
    """
    size = (9, 6, 5)
    s = Structure(size)
    base_plate(s, size)
    place_engine(s, "inline_engines")
    # The fifth. Placed in the file so the storyboard can show it appearing and
    # being rejected; it is never part of a valid engine.
    s.set((1, 2, 2), block(me("crankshaft"), axis="x", joined="false", lit="false"))
    return s


def engine_controls():
    """An inline-1 with a lever and a redstone line to drive its control module."""
    size = (7, 6, 5)
    s = Structure(size)
    base_plate(s, size)
    place_engine(s, "engine_controls")
    s.set((5, 1, 2), block("minecraft:redstone_wire", east="none", north="none",
                           power=0, south="none", west="none"))
    s.set((6, 1, 2), block("minecraft:lever", face="floor", facing="north", powered="false"))
    return s


def engine_maintenance():
    """An inline-4 to wear out, service, and put back together."""
    size = (9, 6, 5)
    s = Structure(size)
    base_plate(s, size)
    place_engine(s, "engine_maintenance")
    return s


def diagnosing_an_engine():
    """An inline-2, small enough to read every line of the goggle overlay against."""
    size = (7, 6, 5)
    s = Structure(size)
    base_plate(s, size)
    place_engine(s, "diagnosing_an_engine")
    return s


def the_four_stroke_cycle():
    """An inline-1, complete, for watching one cylinder go round its cycle.

    A single on purpose. Four strokes explained on an inline-4 is four cylinders
    on four different strokes at once, which is the thing to understand SECOND.
    """
    size = (7, 6, 5)
    s = Structure(size)
    base_plate(s, size)
    place_engine(s, "the_four_stroke_cycle")
    return s


def from_shale_to_fuel():
    """The petroleum chain, as somewhere to stand the real processing blocks."""
    size = (9, 5, 5)
    s = Structure(size)
    base_plate(s, size)
    # The raw resource, in the stone it is found in.
    s.set((1, 1, 2), block(me("oil_shale")))
    return s


SCENES = {
    "assembling_an_engine": assembling_an_engine,
    "fuel_and_lubrication": fuel_and_lubrication,
    "starting_an_engine": starting_an_engine,
    "inline_engines": inline_engines,
    "engine_controls": engine_controls,
    "engine_maintenance": engine_maintenance,
    "diagnosing_an_engine": diagnosing_an_engine,
    "the_four_stroke_cycle": the_four_stroke_cycle,
    "from_shale_to_fuel": from_shale_to_fuel,
}


def main():
    for name, builder in sorted(SCENES.items()):
        structure = builder()
        write_nbt(OUT / f"{name}.nbt", structure.to_nbt())
        print(f"wrote assets/{NS}/ponder/{name}.nbt  "
              f"{structure.size[0]}x{structure.size[1]}x{structure.size[2]}, "
              f"{len(structure.blocks)} blocks")
    print(f"\n{len(SCENES)} structures")


if __name__ == "__main__":
    main()
