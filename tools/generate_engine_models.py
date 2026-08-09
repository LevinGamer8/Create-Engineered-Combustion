#!/usr/bin/env python3
"""Generates every engine block/item model for Create: Engineered Combustion.

The whole assembled engine is laid out here in ONE coordinate system so that
parts which belong to different blocks still line up. World Y is measured from
the bottom of the Crankshaft block:

    crankshaft block   y   0 .. 16
    cylinder  block    y  16 .. 32
    carburetor block   y  32 .. 48

Models are authored for a crank axis along X; the _z variants are produced by
transposing X and Z (the mechanism is mirror-symmetric about its swing plane at
crank angle 0, so a transpose places it correctly).

UVs are written explicitly and clamped to [0,16]. Several elements deliberately
poke out of their block to knit neighbouring blocks together, and Minecraft's
default UVs would run off the edge of the sprite for those.
"""
import json
import os
import pathlib

ROOT = str(pathlib.Path(__file__).resolve().parents[1]
            / "src/main/resources/assets/engineered_combustion/models")
NS = "engineered_combustion:"

# ---------------------------------------------------------------------------
# Mechanism geometry. Must match CrankMath in the Java sources.
# ---------------------------------------------------------------------------
CRANK_AXIS_Y = 8.0    # world Y of the main journal centreline
CRANK_R = 3.0         # crank radius -> 6 unit stroke
ROD_L = 14.5          # wrist pin to crank pin
BORE_MIN, BORE_MAX = 3.4, 12.6
WRIST_AUTHOR_Y = 8.0  # piston/rod are authored with the wrist pin here


def r2(v):
    v = round(v + 0.0, 3)
    return int(v) if float(v).is_integer() else v


def fit(a0, a1):
    """Slides a UV span back inside [0,16] without shrinking it.

    Parts that stick out of their block would otherwise index past the edge of
    their sprite in the atlas and bleed into a neighbouring texture. Sliding
    rather than clamping keeps the texel density identical to every other face.
    """
    if a1 - a0 >= 16.0:
        return 0.0, 16.0
    if a0 < 0.0:
        return 0.0, a1 - a0
    if a1 > 16.0:
        return a0 - (a1 - 16.0), 16.0
    return a0, a1


def uv(a0, b0, a1, b1):
    a0, a1 = fit(a0, a1)
    b0, b1 = fit(b0, b1)
    return [r2(a0), r2(b0), r2(a1), r2(b1)]


def el(frm, to, tex, faces=None, uvs=None):
    """One cuboid. `tex` is the default texture ref for all six faces;
    `faces` overrides individual ones, `uvs` overrides individual face UVs."""
    faces = faces or {}
    uvs = uvs or {}
    x0, y0, z0 = frm
    x1, y1, z1 = to
    # world-aligned UVs: horizontal axes map straight through, vertical is
    # flipped so v grows downwards like every other Minecraft texture.
    default_uv = {
        "down": uv(x0, z0, x1, z1),
        "up": uv(x0, z0, x1, z1),
        "north": uv(x0, 16 - y1, x1, 16 - y0),
        "south": uv(x0, 16 - y1, x1, 16 - y0),
        "west": uv(z0, 16 - y1, z1, 16 - y0),
        "east": uv(z0, 16 - y1, z1, 16 - y0),
    }
    out = {}
    for face in ("north", "east", "south", "west", "up", "down"):
        out[face] = {"uv": uvs.get(face, default_uv[face]),
                     "texture": "#" + faces.get(face, tex)}
    return {"from": [r2(c) for c in frm], "to": [r2(c) for c in to],
            "faces": out, "shade": True}


def transpose(element):
    """X <-> Z. Swaps the horizontal faces to match."""
    f, t = element["from"], element["to"]
    swapped = dict(element)
    swapped["from"] = [f[2], f[1], f[0]]
    swapped["to"] = [t[2], t[1], t[0]]
    src = element["faces"]
    swapped["faces"] = {
        "north": src["west"], "west": src["north"],
        "south": src["east"], "east": src["south"],
        "up": src["up"], "down": src["down"],
    }
    return swapped


def shift(element, dx, dy, dz):
    f, t = element["from"], element["to"]
    moved = dict(element)
    moved["from"] = [r2(f[0] + dx), r2(f[1] + dy), r2(f[2] + dz)]
    moved["to"] = [r2(t[0] + dx), r2(t[1] + dy), r2(t[2] + dz)]
    return moved


def octagon(y0, y1, half, flat, corner, tex, top_tex=None):
    """Three boxes approximating a round section - the piston and the hubs.

    `half` is the outer radius across the flats, `flat` the half width of the
    flat itself, `corner` the radius of the chamfer box.
    """
    a, b, c = 8 - half, 8 + half, 8 - flat
    d, e, g = 8 + flat, 8 - corner, 8 + corner
    faces_top = {"up": top_tex} if top_tex else None
    return [
        el((a, y0, c), (b, y1, d), tex, faces_top),
        el((c, y0, a), (d, y1, b), tex, faces_top),
        el((e, y0, e), (g, y1, g), tex, faces_top),
    ]


def write(path, model):
    full = os.path.join(ROOT, path)
    os.makedirs(os.path.dirname(full), exist_ok=True)
    with open(full, "w") as f:
        json.dump(model, f, indent=2)
        f.write("\n")
    print("wrote", path, "-", len(model.get("elements", [])), "elements")


def model(textures, elements, parent="minecraft:block/block", display=None):
    out = {"parent": parent,
           "textures": {k: (v if ":" in v else NS + "block/" + v)
                        for k, v in textures.items()}}
    if display:
        # Only the contexts named here are overridden; the rest still come from
        # block/block, so held/framed poses stay consistent with every other
        # block item.
        out["display"] = display
    out["elements"] = elements
    return out


# Parts that deliberately do not fill their block look lost in an inventory
# slot at the standard 0.625, so their icons are scaled up.
def gui_scale(scale):
    return {"gui": {"rotation": [30, 225, 0], "translation": [0, 0, 0],
                    "scale": [scale, scale, scale]}}


# ===========================================================================
# CRANKCASE - the static half of the Crankshaft block
# ===========================================================================
# Interior cavity: x 2..14, y 3..13, z 2.5..13.5. Everything that rotates is
# sized to stay inside it (max swept radius 4.87 against a 5.0 limit).
CASE_TEX = {"particle": "crankshaft", "case": "crankshaft",
            "cast": "cast_iron", "deck": "crankcase_deck", "steel": "journal"}


def crankcase_elements():
    e = []
    # --- oil pan: the lower crankcase, tapered, with a drain plug ----------
    e.append(el((3.0, 0.0, 4.0), (13.0, 1.2, 12.0), "cast"))
    e.append(el((1.5, 0.6, 2.0), (14.5, 3.0, 14.0), "case"))
    e.append(el((6.5, 0.4, 1.6), (9.5, 2.2, 4.5), "steel"))
    # --- side walls, cut away so the crank is visible ---------------------
    for z0, z1 in ((0.5, 2.5), (13.5, 15.5)):
        e.append(el((1.0, 2.0, z0), (15.0, 5.0, z1), "case"))      # lower rail
        e.append(el((1.0, 12.8, z0), (15.0, 14.0, z1), "case"))    # upper rail
        e.append(el((1.0, 2.0, z0), (3.5, 14.0, z1), "case"))      # post
        e.append(el((12.5, 2.0, z0), (15.0, 14.0, z1), "case"))    # post
    # --- end walls carrying the main bearings ------------------------------
    e.append(el((0.0, 2.0, 0.5), (2.0, 14.0, 15.5), "case"))
    e.append(el((14.0, 2.0, 0.5), (16.0, 14.0, 15.5), "case"))
    e.append(el((0.0, 3.5, 3.5), (3.5, 12.5, 12.5), "case"))
    e.append(el((12.5, 3.5, 3.5), (16.0, 12.5, 12.5), "case"))
    # --- machined top deck, with the slot the connecting rod swings through.
    # It runs 0.5 past the top of the block so the Cylinder lands on a collar
    # that plainly belongs to the crankcase rather than butting a seam.
    e.append(el((0.5, 13.0, 0.5), (15.5, 16.5, 2.5), "deck"))
    e.append(el((0.5, 13.0, 13.5), (15.5, 16.5, 15.5), "deck"))
    e.append(el((0.5, 13.0, 2.0), (5.0, 16.5, 14.0), "deck"))
    e.append(el((11.0, 13.0, 2.0), (15.5, 16.5, 14.0), "deck"))
    return e


# ===========================================================================
# CRANKSHAFT - the rotating half, authored at crank angle 0 (pin at BDC)
# ===========================================================================
CRANK_TEX = {"particle": "journal", "steel": "journal", "web": "crank_web"}


def crank_elements():
    e = [
        el((-1.0, 5.5, 5.5), (4.8, 10.5, 10.5), "steel"),   # main journal
        el((11.2, 5.5, 5.5), (17.0, 10.5, 10.5), "steel"),  # main journal
        el((5.0, 3.6, 6.6), (11.0, 6.4, 9.4), "steel"),     # offset crank pin
    ]
    for x0, x1 in ((4.0, 6.4), (9.6, 12.0)):
        e.append(el((x0, 4.2, 5.4), (x1, 11.8, 10.6), "web"))   # web disc
        e.append(el((x0, 5.4, 4.2), (x1, 10.6, 11.8), "web"))
        e.append(el((x0, 3.4, 6.4), (x1, 8.0, 9.6), "web"))     # throw arm
        e.append(el((x0, 10.0, 5.2), (x1, 11.9, 10.8), "web"))  # counterweight
        e.append(el((x0, 10.0, 6.6), (x1, 12.6, 9.4), "web"))
    return e


# ===========================================================================
# CONNECTING ROD - authored hanging straight down from the wrist pin at
# (8, 8, 8); the renderer pivots it about exactly that point.
# ===========================================================================
ROD_TEX = {"particle": "conrod", "rod": "conrod", "steel": "journal"}


def rod_elements():
    return [
        el((6.0, 6.4, 5.6), (10.0, 9.8, 10.4), "rod"),      # small end
        el((6.8, 3.0, 6.4), (9.2, 7.0, 9.6), "rod"),        # shank, tapering
        el((6.8, -1.5, 6.0), (9.2, 3.5, 10.0), "rod"),
        el((6.8, -5.0, 5.7), (9.2, -1.0, 10.3), "rod"),
        el((6.5, -6.7, 5.9), (9.5, -4.8, 10.1), "rod"),     # big end
        el((6.5, -8.2, 5.9), (9.5, -6.3, 10.1), "steel"),   # bearing cap
    ]


# ===========================================================================
# PISTON - authored with the wrist pin at y 8, so the renderer only translates
# ===========================================================================
PISTON_TEX = {"particle": "piston", "side": "piston", "crown": "piston_crown"}


def piston_elements():
    e = []
    e += octagon(10.2, 12.0, 4.25, 2.5, 3.4, "side", top_tex="crown")  # crown
    e += octagon(9.3, 10.3, 3.75, 2.2, 2.95, "side")                   # groove
    e += octagon(8.6, 9.4, 4.25, 2.5, 3.4, "side")                     # land
    e += octagon(5.5, 8.7, 4.0, 2.35, 3.18, "side")                    # skirt
    return e


# ===========================================================================
# CYLINDER - cutaway finned barrel with an integrated head
# ===========================================================================
CYL_TEX = {"particle": "cylinder", "barrel": "cylinder", "fin": "cylinder_fin",
           "head": "cylinder_head", "deck": "crankcase_deck",
           "steel": "journal", "case": "crankshaft"}


def cylinder_elements():
    e = []
    # --- flange bolted to the crankcase deck ------------------------------
    e.append(el((1.5, 0.0, 1.5), (14.5, 1.6, BORE_MIN), "case"))
    e.append(el((1.5, 0.0, BORE_MAX), (14.5, 1.6, 14.5), "case"))
    e.append(el((1.5, 0.0, 3.0), (BORE_MIN, 1.6, 13.0), "case"))
    e.append(el((BORE_MAX, 0.0, 3.0), (14.5, 1.6, 13.0), "case"))
    for f, t in (((7.2, 1.2, 1.7), (8.8, 2.6, 3.3)),
                 ((7.2, 1.2, 12.7), (8.8, 2.6, 14.3)),
                 ((1.7, 1.2, 7.2), (3.3, 2.6, 8.8)),
                 ((12.7, 1.2, 7.2), (14.3, 2.6, 8.8))):
        e.append(el(f, t, "steel"))
    # --- corner columns: the cast structure the cutaway windows sit between
    for x0, x1 in ((1.5, 4.25), (11.75, 14.5)):
        for z0, z1 in ((1.5, 4.25), (11.75, 14.5)):
            e.append(el((x0, 1.4, z0), (x1, 14.2, z1), "barrel"))
    # --- cooling fins. Full rings, so the silhouette reads as air-cooled;
    # bored out to the cylinder bore so the piston is never touched.
    # Side faces take the whole fin texture height on purpose - that is what
    # gives every fin its lit top edge and shadowed root.
    fin_uv = {"north": [0, 0, 16, 16], "south": [0, 0, 16, 16],
              "east": [0, 0, 16, 16], "west": [0, 0, 16, 16]}
    flat = {"up": "barrel", "down": "barrel"}
    for y0 in (3.2, 6.4, 9.6):
        y1 = y0 + 0.9
        e.append(el((1.6, y0, 0.6), (14.4, y1, BORE_MIN), "fin", flat, fin_uv))
        e.append(el((1.6, y0, BORE_MAX), (14.4, y1, 15.4), "fin", flat, fin_uv))
        e.append(el((0.6, y0, 1.6), (BORE_MIN, y1, 14.4), "fin", flat, fin_uv))
        e.append(el((BORE_MAX, y0, 1.6), (15.4, y1, 14.4), "fin", flat, fin_uv))
    # --- head. Reaches past the top of the block so the Carburetor lands on
    # a flange belonging to the head instead of sitting on a bare block face.
    e.append(el((1.2, 14.0, 1.2), (14.8, 15.6, 14.8), "head"))
    e.append(el((2.5, 15.4, 2.5), (13.5, 17.2, 13.5), "head"))
    for x0, x1 in ((1.6, 3.2), (12.8, 14.4)):
        for z0, z1 in ((1.6, 3.2), (12.8, 14.4)):
            e.append(el((x0, 14.8, z0), (x1, 17.8, z1), "steel"))
    e.append(el((5.4, 13.8, 0.0), (10.6, 17.6, 4.6), "head"))       # intake port
    e.append(el((4.6, 16.6, 0.2), (11.4, 17.8, 4.2), "deck"))       # intake flange
    e.append(el((6.0, 13.8, 11.6), (10.0, 16.4, 16.0), "head"))     # exhaust boss
    e.append(el((5.2, 12.9, 15.0), (10.8, 16.9, 16.0), "deck"))     # exhaust flange
    return e


# ===========================================================================
# CARBURETOR - a compact intake component hung off the head's intake side,
# not a box centred in its own block.
# ===========================================================================
CARB_TEX = {"particle": "carburetor", "body": "carburetor",
            "brass": "brass", "steel": "journal"}


def carburetor_elements():
    return [
        el((4.6, 1.4, 0.2), (11.4, 2.8, 4.6), "body"),      # mounting flange
        el((5.6, 2.6, 0.9), (10.4, 4.6, 4.4), "body"),      # throat
        el((6.3, 4.4, 1.5), (9.7, 6.4, 3.9), "body"),       # venturi waist
        el((5.6, 6.2, 0.9), (10.4, 8.4, 4.4), "body"),
        el((4.9, 8.2, 0.3), (11.1, 9.8, 5.0), "body"),      # air horn
        el((5.2, 1.0, 4.0), (10.8, 4.8, 7.8), "body"),      # float bowl
        el((4.9, 4.4, 4.1), (11.1, 5.3, 8.1), "brass"),     # bowl clamp ring
        el((7.2, 5.0, 7.4), (8.8, 6.5, 9.6), "brass"),      # fuel inlet
        el((10.2, 5.0, 1.6), (12.4, 5.8, 3.2), "brass"),    # throttle arm
        el((11.4, 5.6, 2.0), (12.2, 9.2, 2.8), "brass"),    # throttle rod
        el((3.6, 3.0, 1.8), (5.8, 4.0, 3.0), "brass"),      # idle screw
    ]


# ===========================================================================
# FLYWHEEL - large diameter, narrow, heavy rim, four spokes
# ===========================================================================
FLY_TEX = {"particle": "flywheel", "rim": "flywheel", "face": "flywheel_face",
           "hub": "cast_iron", "steel": "journal"}


def flywheel_elements():
    side = {"east": "face", "west": "face"}
    e = [el((0, 6, 6), (16, 10, 10), "steel")]              # through shaft
    e.append(el((5.0, 5.2, 6.4), (11.0, 10.8, 9.6), "hub", side))
    e.append(el((5.0, 6.4, 5.2), (11.0, 9.6, 10.8), "hub", side))
    e.append(el((5.0, 5.7, 5.7), (11.0, 10.3, 10.3), "hub", side))
    e.append(el((6.5, 1.6, 6.8), (9.5, 14.4, 9.2), "rim", side))   # spokes
    e.append(el((6.5, 6.8, 1.6), (9.5, 9.2, 14.4), "rim", side))
    for f, t in (((6.4, 13.6, 3.6), (9.6, 15.75, 12.4)),
                 ((6.4, 0.25, 3.6), (9.6, 2.4, 12.4)),
                 ((6.4, 3.6, 0.25), (9.6, 12.4, 2.4)),
                 ((6.4, 3.6, 13.6), (9.6, 12.4, 15.75)),
                 ((6.4, 11.6, 1.1), (9.6, 14.9, 4.4)),
                 ((6.4, 11.6, 11.6), (9.6, 14.9, 14.9)),
                 ((6.4, 1.1, 1.1), (9.6, 4.4, 4.4)),
                 ((6.4, 1.1, 11.6), (9.6, 4.4, 14.9))):
        e.append(el(f, t, "rim", side))
    return e


# ===========================================================================
def main():
    case = crankcase_elements()
    crank = crank_elements()
    rod = rod_elements()
    piston = piston_elements()
    cyl = cylinder_elements()
    carb = carburetor_elements()
    fly = flywheel_elements()

    write("block/crankshaft.json", model(CASE_TEX, case))
    write("block/crank_assembly_x.json", model(CRANK_TEX, crank))
    write("block/crank_assembly_z.json",
          model(CRANK_TEX, [transpose(x) for x in crank]))
    write("block/connecting_rod_x.json", model(ROD_TEX, rod))
    write("block/connecting_rod_z.json",
          model(ROD_TEX, [transpose(x) for x in rod]))
    write("block/piston_head.json", model(PISTON_TEX, piston))
    write("block/cylinder.json", model(CYL_TEX, cyl))
    write("block/carburetor.json", model(CARB_TEX, carb))
    write("block/flywheel_wheel_x.json", model(FLY_TEX, fly))
    write("block/flywheel_wheel_z.json",
          model(FLY_TEX, [transpose(x) for x in fly]))

    # The flywheel block itself draws nothing: every part of it turns, so all
    # of its geometry lives in the block entity renderer. Same shape as
    # vanilla's chest model, which is also renderer-only.
    write("block/flywheel.json",
          {"parent": "minecraft:block/block",
           "textures": {"particle": NS + "block/flywheel"}})

    # --- item models -------------------------------------------------------
    # Blocks whose geometry is split between the chunk mesh and a renderer need
    # a combined model in the inventory, or the item shows half a machine.
    write("item/crankshaft.json",
          model({**CASE_TEX, **CRANK_TEX, "particle": "crankshaft"}, case + crank))
    # The wheel faces along the crank axis, and the Z variant is the one that
    # turns its face towards the camera in the standard block-item pose.
    write("item/flywheel.json", model(FLY_TEX, [transpose(x) for x in fly]))
    write("item/cylinder.json", model(CYL_TEX, cyl))
    write("item/carburetor.json",
          model(CARB_TEX, [shift(x, 0, 2.6, 3.4) for x in carb],
                display=gui_scale(0.95)))

    # Piston Assembly is piston *and* rod, so the item says so. The rod is
    # shortened to fit the icon; the in-world rod keeps its true length.
    item_rod = [
        el((6.0, 8.4, 5.6), (10.0, 11.8, 10.4), "rod"),
        el((6.8, 5.0, 6.4), (9.2, 9.0, 9.6), "rod"),
        el((6.8, 2.5, 6.0), (9.2, 5.5, 10.0), "rod"),
        el((6.5, 1.2, 5.9), (9.5, 3.0, 10.1), "rod"),
        el((6.5, 0.0, 5.9), (9.5, 1.4, 10.1), "steel"),
    ]
    write("item/piston_assembly.json",
          model({**PISTON_TEX, **ROD_TEX, "particle": "piston"},
                [shift(x, 0, 2.0, 0) for x in piston] + item_rod,
                display=gui_scale(0.85)))


if __name__ == "__main__":
    main()
