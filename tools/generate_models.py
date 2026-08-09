#!/usr/bin/env python3
"""
Generates the block/item models and textures for Create: Engineered Combustion.

Everything here is drawn from scratch out of flat fills, bevels and hand-placed
bolts - there is no sampled or traced artwork, so the output is original to this
project. Run it to regenerate:

    python3 tools/generate_models.py

Design rules, applied consistently so the set reads as one machine:

* One palette, three materials. Cast iron for structural bodies, machined steel
  for anything that turns or slides, brass only as an accent on fittings. A
  player should be able to tell what moves by its colour.
* Shape carries the detail, texture only supports it. Minecraft/Create art
  reads at a distance because of silhouette and large flat planes with crisp
  edges, not because of pixel noise, so textures get bevels, seams, recesses
  and bolts - never procedural speckle everywhere.
* Bevels go light on top/left, dark on bottom/right, matching Minecraft's own
  directional face shading rather than fighting it.
* Element counts stay modest. These are blocks that can appear many times.
"""

import json
import os
import struct
import zlib

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "src", "main", "resources")
NS = "engineered_combustion"
ASSETS = os.path.normpath(os.path.join(ROOT, "assets", NS))

# ---------------------------------------------------------------- palette
# Deliberately small. Every block below draws from these and nothing else.
IRON_DARK = (46, 50, 58, 255)      # recesses, cast shadow
IRON = (74, 80, 90, 255)           # cast iron bodies
IRON_LIGHT = (98, 106, 118, 255)   # lit faces of cast iron
STEEL = (140, 148, 160, 255)       # machined, moving parts
STEEL_LIGHT = (176, 185, 198, 255)
STEEL_DARK = (104, 112, 124, 255)
BRASS = (166, 128, 62, 255)        # fittings only
BRASS_LIGHT = (204, 168, 92, 255)
BRASS_DARK = (118, 90, 42, 255)
BORE = (30, 32, 38, 255)           # inside of the cylinder, oil-wetted metal
OIL = (52, 40, 20, 255)


def shade(c, f):
    return tuple(max(0, min(255, int(v * f))) for v in c[:3]) + (c[3],)


# ---------------------------------------------------------------- png writer
def write_png(path, rows):
    h = len(rows)
    w = len(rows[0])
    raw = b"".join(b"\x00" + b"".join(struct.pack("BBBB", *px) for px in row) for row in rows)

    def chunk(tag, data):
        c = struct.pack(">I", len(data)) + tag + data
        return c + struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF)

    png = b"\x89PNG\r\n\x1a\n"
    png += chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 6, 0, 0, 0))
    png += chunk(b"IDAT", zlib.compress(raw, 9))
    png += chunk(b"IEND", b"")
    os.makedirs(os.path.dirname(path), exist_ok=True)
    open(path, "wb").write(png)


def fill(color, w=16, h=16):
    return [[color for _ in range(w)] for _ in range(h)]


def rect(rows, x0, y0, x1, y1, color):
    """Inclusive-exclusive, clipped."""
    for y in range(max(0, y0), min(len(rows), y1)):
        for x in range(max(0, x0), min(len(rows[0]), x1)):
            rows[y][x] = color


def bevel(rows, x0, y0, x1, y1, light=1.28, dark=0.68):
    """Lit top/left edge, shaded bottom/right edge - the single most useful
    readability trick in Minecraft pixel art."""
    for x in range(max(0, x0), min(len(rows[0]), x1)):
        if 0 <= y0 < len(rows):
            rows[y0][x] = shade(rows[y0][x], light)
        if 0 <= y1 - 1 < len(rows):
            rows[y1 - 1][x] = shade(rows[y1 - 1][x], dark)
    for y in range(max(0, y0), min(len(rows), y1)):
        if 0 <= x0 < len(rows[0]):
            rows[y][x0] = shade(rows[y][x0], light)
        if 0 <= x1 - 1 < len(rows[0]):
            rows[y][x1 - 1] = shade(rows[y][x1 - 1], dark)


def bolt(rows, x, y, base=None):
    """A 2x2 fastener: lit head, shaded seat. Reads as a bolt at any zoom."""
    if not (0 <= x < 15 and 0 <= y < 15):
        return
    b = base if base else rows[y][x]
    rows[y][x] = shade(b, 1.45)
    rows[y][x + 1] = shade(b, 1.15)
    rows[y + 1][x] = shade(b, 1.05)
    rows[y + 1][x + 1] = shade(b, 0.62)


def seam(rows, y, color=None, x0=0, x1=16):
    """A horizontal casting seam: dark groove with a lit lip under it."""
    for x in range(x0, x1):
        base = color if color else rows[y][x]
        rows[y][x] = shade(base, 0.58)
        if y + 1 < len(rows):
            rows[y + 1][x] = shade(rows[y + 1][x], 1.18)


def vgroove(rows, x, y0, y1):
    for y in range(y0, y1):
        rows[y][x] = shade(rows[y][x], 0.62)
        if x + 1 < len(rows[0]):
            rows[y][x + 1] = shade(rows[y][x + 1], 1.16)


# ---------------------------------------------------------------- textures

def tex_crankcase():
    """Cast iron crankcase wall: bolted flanges top and bottom, seam across."""
    r = fill(IRON)
    rect(r, 0, 0, 16, 3, IRON_LIGHT)      # top mounting flange
    rect(r, 0, 13, 16, 16, IRON_LIGHT)    # bottom flange
    seam(r, 3)
    seam(r, 12)
    bevel(r, 0, 0, 16, 16)
    for x in (1, 12):
        bolt(r, x, 0)
        bolt(r, x, 13)
    # shallow cast relief in the middle so the wall is not one flat plane
    rect(r, 4, 6, 12, 10, shade(IRON, 0.9))
    bevel(r, 4, 6, 12, 10, light=0.8, dark=1.15)
    return r


def tex_crank_steel():
    """Machined steel: what turns and slides. Bright, with turning marks."""
    r = fill(STEEL)
    for y in range(16):
        if y % 4 == 0:
            for x in range(16):
                r[y][x] = shade(STEEL, 1.10)
        if y % 4 == 2:
            for x in range(16):
                r[y][x] = shade(STEEL, 0.93)
    bevel(r, 0, 0, 16, 16)
    return r


def tex_cylinder():
    """Cast cylinder body with cooling-style ribs and bolted flanges."""
    r = fill(IRON)
    rect(r, 0, 0, 16, 2, IRON_LIGHT)
    rect(r, 0, 14, 16, 16, IRON_LIGHT)
    for y in range(3, 13, 3):             # ribs
        rect(r, 1, y, 15, y + 1, shade(IRON, 1.22))
        rect(r, 1, y + 1, 15, y + 2, shade(IRON, 0.82))
    seam(r, 2)
    bevel(r, 0, 0, 16, 16)
    bolt(r, 1, 0)
    bolt(r, 12, 0)
    bolt(r, 1, 14)
    bolt(r, 12, 14)
    return r


def tex_bore():
    """Honed cylinder wall: dark, faintly vertically streaked, oil-wetted."""
    r = fill(BORE)
    for x in range(0, 16, 3):
        for y in range(16):
            r[y][x] = shade(BORE, 1.35)
    bevel(r, 0, 0, 16, 16, light=1.5, dark=0.8)
    return r


def tex_piston():
    """Piston: bright crown, two ring grooves, darker skirt."""
    r = fill(STEEL)
    rect(r, 0, 0, 16, 4, STEEL_LIGHT)     # crown
    rect(r, 0, 5, 16, 6, IRON_DARK)       # ring groove
    rect(r, 0, 7, 16, 8, IRON_DARK)       # ring groove
    rect(r, 0, 9, 16, 16, STEEL_DARK)     # skirt
    for y in (6, 8):
        rect(r, 0, y, 16, y + 1, shade(STEEL_LIGHT, 1.05))
    bevel(r, 0, 0, 16, 16)
    return r


def tex_flywheel():
    """Machined iron wheel: heavy rim, bolt circle on the web."""
    r = fill(IRON)
    rect(r, 0, 0, 16, 2, STEEL_DARK)      # rim edge
    rect(r, 0, 14, 16, 16, STEEL_DARK)
    rect(r, 3, 3, 13, 13, shade(IRON, 0.86))   # recessed web
    bevel(r, 3, 3, 13, 13, light=0.78, dark=1.18)
    for bx, by in ((4, 4), (10, 4), (4, 10), (10, 10)):
        bolt(r, bx, by)
    seam(r, 2)
    bevel(r, 0, 0, 16, 16)
    return r


def tex_carburetor():
    """Carburetor body: compact iron casting with a bolted mounting face."""
    r = fill(IRON)
    rect(r, 0, 0, 16, 2, IRON_LIGHT)
    rect(r, 2, 4, 14, 12, shade(IRON, 1.1))
    bevel(r, 2, 4, 14, 12)
    seam(r, 12)
    bolt(r, 1, 13)
    bolt(r, 12, 13)
    bevel(r, 0, 0, 16, 16)
    return r


def tex_brass():
    """Brass fittings. Used sparingly - throat collar, float bowl, drain plug."""
    r = fill(BRASS)
    for y in range(0, 16, 5):
        rect(r, 0, y, 16, y + 1, BRASS_LIGHT)
        rect(r, 0, y + 3, 16, y + 4, BRASS_DARK)
    bevel(r, 0, 0, 16, 16)
    return r


def tex_oil_sump():
    """Oil pan: dark pressed steel, bolted flange, faint oil sheen low down."""
    r = fill(shade(IRON, 0.82))
    rect(r, 0, 0, 16, 3, IRON)            # upper flange
    seam(r, 3)
    rect(r, 2, 9, 14, 15, shade(OIL, 1.5))   # oil-darkened lower pan
    bevel(r, 2, 9, 14, 15, light=0.85, dark=1.1)
    for x in (1, 6, 12):
        bolt(r, x, 0)
    bevel(r, 0, 0, 16, 16)
    return r


def tex_indicator_off():
    r = fill(IRON_DARK)
    rect(r, 4, 4, 12, 12, shade((90, 40, 34, 255), 0.7))
    bevel(r, 4, 4, 12, 12, light=1.3, dark=0.6)
    bevel(r, 0, 0, 16, 16)
    return r


def tex_indicator_on():
    r = fill(IRON_DARK)
    rect(r, 3, 3, 13, 13, (168, 62, 30, 255))
    rect(r, 5, 5, 11, 11, (240, 148, 54, 255))
    rect(r, 6, 6, 10, 10, (255, 214, 130, 255))
    bevel(r, 3, 3, 13, 13, light=1.2, dark=0.75)
    bevel(r, 0, 0, 16, 16)
    return r


def _fluid(base, light, dark):
    """
    A calm fluid surface: broad horizontal bands with a slow diagonal drift.

    Deliberately low-contrast. Fluids tile across a tank face, so a high-contrast
    checker - which is what these were - turns into visual static at any size.
    """
    r = fill(base)
    for y in range(16):
        for x in range(16):
            k = (x + 2 * y) % 8
            if k < 2:
                r[y][x] = light
            elif k in (4, 5):
                r[y][x] = dark
    for y in range(0, 16, 8):                 # gentle horizontal sheen
        rect(r, 0, y, 16, y + 1, shade(light, 1.06))
    return r


def tex_gasoline():
    return _fluid((214, 178, 84, 255), (236, 208, 130, 255), (184, 146, 60, 255))


def tex_engine_oil():
    return _fluid((84, 62, 28, 255), (112, 86, 42, 255), (58, 42, 18, 255))


def tex_item_piston_assembly():
    """Item sprite: a piston with its connecting rod, seen from the side."""
    r = fill((0, 0, 0, 0))
    rect(r, 4, 1, 12, 4, STEEL_LIGHT)     # crown
    rect(r, 4, 4, 12, 5, IRON_DARK)       # ring
    rect(r, 4, 5, 12, 6, STEEL)
    rect(r, 4, 6, 12, 7, IRON_DARK)       # ring
    rect(r, 4, 7, 12, 9, STEEL_DARK)      # skirt
    rect(r, 6, 9, 10, 10, STEEL)          # wrist pin boss
    rect(r, 7, 10, 9, 14, STEEL_DARK)     # rod shank
    rect(r, 6, 14, 10, 16, STEEL)         # big end
    rect(r, 7, 15, 9, 16, IRON_DARK)      # bearing eye
    bevel(r, 4, 1, 12, 9)
    bevel(r, 6, 14, 10, 16)
    return r


TEXTURES = {
    "block/crankcase": tex_crankcase,
    "block/crank_steel": tex_crank_steel,
    "block/cylinder": tex_cylinder,
    "block/cylinder_bore": tex_bore,
    "block/piston": tex_piston,
    "block/flywheel": tex_flywheel,
    "block/carburetor": tex_carburetor,
    "block/brass": tex_brass,
    "block/oil_sump": tex_oil_sump,
    "block/indicator_off": tex_indicator_off,
    "block/indicator_on": tex_indicator_on,
    "block/gasoline_still": tex_gasoline,
    "block/gasoline_flow": tex_gasoline,
    "block/engine_oil_still": tex_engine_oil,
    "block/engine_oil_flow": tex_engine_oil,
    "item/piston_assembly": tex_item_piston_assembly,
}


# ---------------------------------------------------------------- models
FACES = ["north", "east", "south", "west", "up", "down"]


def box(frm, to, tex, faces=None):
    """One cuboid. `tex` is a texture key like '#iron'; `faces` overrides
    individual sides, which is how bores and end caps get their own material."""
    f = {}
    for name in FACES:
        f[name] = {"texture": (faces or {}).get(name, tex)}
    return {"from": frm, "to": to, "faces": f}


def model(textures, elements, parent="block/block"):
    return {"parent": parent, "textures": textures, "elements": elements}


def write_json(path, obj):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w") as fh:
        json.dump(obj, fh, indent=2)
        fh.write("\n")


T_CRANKCASE = f"{NS}:block/crankcase"
T_STEEL = f"{NS}:block/crank_steel"
T_CYL = f"{NS}:block/cylinder"
T_BORE = f"{NS}:block/cylinder_bore"
T_PISTON = f"{NS}:block/piston"
T_FLY = f"{NS}:block/flywheel"
T_CARB = f"{NS}:block/carburetor"
T_BRASS = f"{NS}:block/brass"
T_SUMP = f"{NS}:block/oil_sump"


def crankshaft_model(lit):
    """
    Crankcase along X: a bolted lower case, two bearing bosses carrying the main
    journal, and an open top so the rotating crank throw inside stays visible.
    The exposed-mechanism look is deliberate - watching it turn is the point.
    """
    ind = f"{NS}:block/indicator_" + ("on" if lit else "off")
    tex = {"particle": T_CRANKCASE, "case": T_CRANKCASE, "steel": T_STEEL, "bore": T_BORE, "ind": ind}
    # Geometry is dictated by the crank throw's swept circle: the throw reaches
    # 5.7 units from the block centre, so nothing static may sit within that
    # radius of (y=8, and z=8 / x=8 for the two axes) between the bearing bosses.
    # That is why the case is a floor plus low side rails rather than tall walls -
    # the whole middle of the block has to stay clear, and staying clear is also
    # what lets the mechanism actually be watched.
    e = [
        box([1, 0, 1], [15, 2, 15], "#case"),                       # crankcase floor
        box([1, 2, 1], [15, 4.5, 3], "#case", {"south": "#bore"}),   # side rail
        box([1, 2, 13], [15, 4.5, 15], "#case", {"north": "#bore"}),  # side rail
        box([1, 2, 3], [4, 11, 13], "#case", {"east": "#bore"}),     # bearing boss, -X
        box([12, 2, 3], [15, 11, 13], "#case", {"west": "#bore"}),   # bearing boss, +X
        box([0, 6, 6], [2, 10, 10], "#steel"),                      # main journal, -X stub
        box([14, 6, 6], [16, 10, 10], "#steel"),                    # main journal, +X stub
        box([3, 5.5, 5.5], [4, 10.5, 10.5], "#steel"),              # bearing collar
        box([12, 5.5, 5.5], [13, 10.5, 10.5], "#steel"),            # bearing collar
        box([1.5, 11, 5.5], [3.5, 12.5, 8.5], "#ind"),              # ignition indicator lamp
    ]
    return model(tex, e)


def crank_throw_model(axis):
    """
    The rotating part: two counterweights and the offset crank pin between them.
    Modelled with the pin BELOW the block centre so that at crank angle 0 it sits
    at bottom dead centre, matching CrankMath.pistonPosition(0) == 0. The
    renderer spins this about the block centre, so the main journal axis must
    pass through it - hence everything is centred on y=8.
    """
    tex = {"particle": T_STEEL, "steel": T_STEEL}
    if axis == "x":
        e = [
            box([4, 6, 6], [12, 10, 10], "#steel"),      # journal through the middle
            box([4.5, 3.5, 6.5], [6.5, 8, 9.5], "#steel"),   # counterweight web
            box([9.5, 3.5, 6.5], [11.5, 8, 9.5], "#steel"),  # counterweight web
            box([4.5, 2.5, 6.5], [6.5, 4.5, 9.5], "#steel"),  # counterweight mass
            box([9.5, 2.5, 6.5], [11.5, 4.5, 9.5], "#steel"),
            box([5, 3, 7], [11, 5, 9], "#steel"),        # crank pin (big-end journal)
        ]
    else:
        e = [
            box([6, 6, 4], [10, 10, 12], "#steel"),
            box([6.5, 3.5, 4.5], [9.5, 8, 6.5], "#steel"),
            box([6.5, 3.5, 9.5], [9.5, 8, 11.5], "#steel"),
            box([6.5, 2.5, 4.5], [9.5, 4.5, 6.5], "#steel"),
            box([6.5, 2.5, 9.5], [9.5, 4.5, 11.5], "#steel"),
            box([7, 3, 5], [9, 5, 11], "#steel"),
        ]
    return model(tex, e)


def cylinder_model():
    """
    A cutaway cylinder: full mounting flanges top and bottom, four corner posts
    and a rib up the middle of each face, with the honed bore visible behind.
    Enclosing it completely would hide the piston, which is the most legible
    moving part in the whole engine.
    """
    tex = {"particle": T_CYL, "cyl": T_CYL, "bore": T_BORE}
    e = [
        box([0, 0, 0], [16, 2, 16], "#cyl", {"up": "#bore"}),      # base flange
        box([0, 14, 0], [16, 16, 16], "#cyl", {"down": "#bore"}),  # head flange
        box([1, 2, 1], [4, 14, 4], "#cyl"),                        # corner posts
        box([12, 2, 1], [15, 14, 4], "#cyl"),
        box([1, 2, 12], [4, 14, 15], "#cyl"),
        box([12, 2, 12], [15, 14, 15], "#cyl"),
        box([6.5, 2, 1], [9.5, 14, 2.5], "#cyl"),                  # face ribs
        box([6.5, 2, 13.5], [9.5, 14, 15], "#cyl"),
        box([1, 2, 6.5], [2.5, 14, 9.5], "#cyl"),
        box([13.5, 2, 6.5], [15, 14, 9.5], "#cyl"),
    ]
    return model(tex, e)


def piston_head_model():
    """
    Crown, ring band, skirt, wrist-pin boss and the top of the connecting rod.
    The rod is vertical: a correctly articulated rod would have to swing with
    crank angle, and a rod that tilts wrongly looks far worse than one that
    simply disappears into the crankcase. Travel is unchanged, so the existing
    piston animation is untouched.
    """
    tex = {"particle": T_PISTON, "piston": T_PISTON, "steel": T_STEEL}
    return model(tex, [
        box([3.5, 2.5, 3.5], [12.5, 4, 12.5], "#piston"),   # crown
        box([3.5, 1, 3.5], [12.5, 2.5, 12.5], "#piston"),   # ring band
        box([4, 0, 4], [12, 1, 12], "#piston"),             # skirt
        box([6.5, -0.5, 6.5], [9.5, 0, 9.5], "#steel"),     # wrist-pin boss
        box([7, -2, 7], [9, -0.5, 9], "#steel"),            # connecting rod
    ])


def flywheel_hub_model():
    """Static hub. Refined only - the flywheel already read well."""
    tex = {"particle": T_FLY, "fly": T_FLY, "steel": T_STEEL}
    return model(tex, [
        box([0, 6, 6], [16, 10, 10], "#steel"),      # shaft
        box([4.5, 4.5, 4.5], [6, 11.5, 11.5], "#fly"),   # bearing collars
        box([10, 4.5, 4.5], [11.5, 11.5, 11.5], "#fly"),
    ])


def flywheel_wheel_model(axis):
    """The spinning disc: heavy rim, recessed web, visible hub."""
    tex = {"particle": T_FLY, "fly": T_FLY, "steel": T_STEEL}
    if axis == "x":
        e = [
            box([6, 1, 3.5], [10, 15, 12.5], "#fly"),     # disc, tall axis
            box([6, 3.5, 1], [10, 12.5, 15], "#fly"),     # disc, wide axis
            box([6.5, 2.5, 2.5], [9.5, 13.5, 13.5], "#fly"),  # corner fill -> rounder
            box([5, 5.5, 5.5], [11, 10.5, 10.5], "#steel"),   # hub
        ]
    else:
        e = [
            box([3.5, 1, 6], [12.5, 15, 10], "#fly"),
            box([1, 3.5, 6], [15, 12.5, 10], "#fly"),
            box([2.5, 2.5, 6.5], [13.5, 13.5, 9.5], "#fly"),
            box([5.5, 5.5, 5], [10.5, 10.5, 11], "#steel"),
        ]
    return model(tex, e)


def carburetor_model():
    """
    Reads as a primitive carburetor: mounting flange down onto the cylinder,
    brass float bowl, iron body, intake throat open at the top, and a brass fuel
    fitting on one side.
    """
    tex = {"particle": T_CARB, "carb": T_CARB, "brass": T_BRASS, "bore": T_BORE}
    return model(tex, [
        box([2, 0, 2], [14, 2, 14], "#carb"),               # mounting flange
        box([4, 2, 4], [12, 6, 12], "#brass"),              # float bowl
        box([3, 6, 3], [13, 12, 13], "#carb"),              # body
        box([5.5, 12, 5.5], [10.5, 16, 10.5], "#carb",      # intake throat
            {"up": "#bore"}),
        box([5, 11.5, 5], [11, 12.5, 11], "#brass"),        # throat collar
        box([13, 7, 6.5], [16, 9, 9.5], "#brass"),          # fuel fitting
        box([1, 7.5, 7], [3, 8.5, 9], "#brass"),            # mixture screw
    ])


def oil_sump_model():
    """
    An oil pan, not a tank: wide bolted flange up against the crankcase,
    stepping down to a deeper sump with a brass drain plug underneath.
    """
    tex = {"particle": T_SUMP, "sump": T_SUMP, "brass": T_BRASS}
    return model(tex, [
        box([0, 13, 0], [16, 16, 16], "#sump"),      # flange to the crankcase
        box([1.5, 7, 1.5], [14.5, 13, 14.5], "#sump"),   # upper pan
        box([3.5, 2, 3.5], [12.5, 7, 12.5], "#sump"),    # deep sump
        box([6.5, 0.5, 6.5], [9.5, 2, 9.5], "#brass"),   # drain plug
    ])


def main():
    for name, fn in TEXTURES.items():
        write_png(os.path.join(ASSETS, "textures", name + ".png"), fn())

    models = os.path.join(ASSETS, "models")
    bs = os.path.join(ASSETS, "blockstates")

    write_json(os.path.join(models, "block", "crankshaft.json"), crankshaft_model(False))
    write_json(os.path.join(models, "block", "crankshaft_lit.json"), crankshaft_model(True))
    write_json(os.path.join(models, "block", "crank_throw_x.json"), crank_throw_model("x"))
    write_json(os.path.join(models, "block", "crank_throw_z.json"), crank_throw_model("z"))
    write_json(os.path.join(models, "block", "cylinder.json"), cylinder_model())
    write_json(os.path.join(models, "block", "piston_head.json"), piston_head_model())
    write_json(os.path.join(models, "block", "flywheel.json"), flywheel_hub_model())
    write_json(os.path.join(models, "block", "flywheel_wheel_x.json"), flywheel_wheel_model("x"))
    write_json(os.path.join(models, "block", "flywheel_wheel_z.json"), flywheel_wheel_model("z"))
    write_json(os.path.join(models, "block", "carburetor.json"), carburetor_model())
    write_json(os.path.join(models, "block", "oil_sump.json"), oil_sump_model())

    # Crankshaft carries the ignition indicator, so it varies on lit as well as axis.
    write_json(os.path.join(bs, "crankshaft.json"), {"variants": {
        f"axis={a},lit={l}": {
            "model": f"{NS}:block/crankshaft" + ("_lit" if l == "true" else ""),
            **({"y": 90} if a == "z" else {}),
        }
        for a in ("x", "z") for l in ("false", "true")
    }})
    write_json(os.path.join(bs, "flywheel.json"), {"variants": {
        "axis=x": {"model": f"{NS}:block/flywheel"},
        "axis=z": {"model": f"{NS}:block/flywheel", "y": 90},
    }})
    write_json(os.path.join(bs, "cylinder.json"), {"variants": {"": {"model": f"{NS}:block/cylinder"}}})
    write_json(os.path.join(bs, "carburetor.json"), {"variants": {"": {"model": f"{NS}:block/carburetor"}}})
    write_json(os.path.join(bs, "oil_sump.json"), {"variants": {"": {"model": f"{NS}:block/oil_sump"}}})

    for name in ("crankshaft", "cylinder", "flywheel", "carburetor", "oil_sump"):
        write_json(os.path.join(models, "item", name + ".json"), {"parent": f"{NS}:block/{name}"})
    write_json(os.path.join(models, "item", "piston_assembly.json"), {
        "parent": "minecraft:item/generated",
        "textures": {"layer0": f"{NS}:item/piston_assembly"},
    })

    print("generated %d textures and %d models" % (len(TEXTURES), 11))


if __name__ == "__main__":
    main()
