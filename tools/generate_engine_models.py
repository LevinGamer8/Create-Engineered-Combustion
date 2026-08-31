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

<h2>Why elements are specs and not model JSON</h2>
Geometry is authored as plain cuboid specs and only turned into model JSON by
{@link bake}, which runs two passes first:

*face culling* drops quads that other elements of the same model bury. Nothing
can ever see them, but the chunk mesh still carries them.

*separation* pulls apart quads that face the same way, sit in exactly the same
plane and overlap in area. The depth buffer cannot order such a pair, so it
shimmers as the camera moves - and on the parts a block entity renderer turns
(crankshaft, connecting rod, flywheel) the geometry itself moves, so the
shimmer becomes a flicker that follows the engine's speed.

Both passes need to see a whole model at once, which is why they cannot happen
in the helpers that build the parts. `tools/check_models.py` re-derives both
invariants from the written JSON, so they stay enforced rather than assumed.
"""
import copy
import json
import math
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


FACE_ORDER = ("north", "east", "south", "west", "up", "down")
# face -> (axis index, which end of the box the face sits on)
FACE_AXIS = {"east": (0, 1), "west": (0, 0), "up": (1, 1),
             "down": (1, 0), "south": (2, 1), "north": (2, 0)}
# X <-> Z renames the horizontal faces.
FACE_SWAP = {"north": "west", "west": "north", "south": "east", "east": "south",
             "up": "up", "down": "down"}

# How far a losing quad steps out of a contested plane, in model units. Small
# enough to be a fortieth of a texel at the 32px the block textures are drawn
# at - invisible - and still far enough apart for the depth buffer to order the
# pair at any distance the model is more than a few pixels tall at.
SEPARATION = 0.02


def el(frm, to, tex, faces=None, uvs=None):
    """One cuboid, as a spec. `tex` is the default texture ref for all six
    faces; `faces` overrides individual ones, `uvs` overrides individual face
    UVs. `bake` turns a list of these into model JSON."""
    return {"from": [float(c) for c in frm], "to": [float(c) for c in to],
            "tex": tex, "faces": dict(faces or {}), "uvs": dict(uvs or {})}


def transpose(spec):
    """X <-> Z. Swaps the horizontal faces to match."""
    f, t = spec["from"], spec["to"]
    out = dict(spec)
    out["from"] = [f[2], f[1], f[0]]
    out["to"] = [t[2], t[1], t[0]]
    out["faces"] = {FACE_SWAP[k]: v for k, v in spec["faces"].items()}
    out["uvs"] = {FACE_SWAP[k]: v for k, v in spec["uvs"].items()}
    return out


# A quarter turn about Y, the way a blockstate's `"y": 90` turns a baked model:
# clockwise seen from above, so north becomes east. Partial models are NOT
# affected by that blockstate rotation - see ECPartialModels - so anything a
# block entity renderer draws on an engine running along Z needs its own copy,
# and this is what produces it from the X original rather than from a second
# hand-authored table that could drift.
FACE_ROT_Y90 = {"north": "east", "east": "south", "south": "west",
                "west": "north", "up": "up", "down": "down"}


def rotate_y90(spec):
    """(x, z) -> (16 - z, x). Renames the horizontal faces to match."""
    f, t = spec["from"], spec["to"]
    out = dict(spec)
    out["from"] = [16.0 - t[2], f[1], f[0]]
    out["to"] = [16.0 - f[2], t[1], t[0]]
    out["faces"] = {FACE_ROT_Y90[k]: v for k, v in spec["faces"].items()}
    out["uvs"] = {FACE_ROT_Y90[k]: v for k, v in spec["uvs"].items()}
    return out


def shift(spec, dx, dy, dz):
    f, t = spec["from"], spec["to"]
    moved = dict(spec)
    moved["from"] = [f[0] + dx, f[1] + dy, f[2] + dz]
    moved["to"] = [t[0] + dx, t[1] + dy, t[2] + dz]
    return moved


def steps(half, flat, corner):
    """The three-step section the engine was authored with.

    Returned widest-first as (half extent along x, half extent along z) pairs:
    `half` is the outer radius across the flats, `flat` the half width of the
    flat itself, `corner` the radius of the chamfer box.
    """
    return [(half, flat), (corner, corner), (flat, half)]


def fine_steps(half, flat, corner):
    """The same section with the two chamfers split in half again.

    Twenty sides instead of twelve. Worth it on the parts a player watches move
    - the piston travelling in an open bore is the whole point of the cutaway
    cylinder - and not worth the elements anywhere else.
    """
    return [(half, flat), ((half + corner) / 2, (flat + corner) / 2),
            (corner, corner), ((flat + corner) / 2, (half + corner) / 2),
            (flat, half)]


def round_section_at(cx, cz, y0, y1, section, tex, top_tex=None):
    """A round section, centred anywhere in the block, built as strips.

    The obvious construction - one box per step, all sharing the section's full
    height - makes every step's top and bottom face land in the same plane as
    every other step's, so a piston crown is a stack of quads the depth buffer
    has to guess between. Cutting the same silhouette into vertical strips
    instead partitions it: the boxes touch but never overlap, so there is no
    contested plane to begin with, and the shared side walls are buried and get
    culled. It also keeps the translucent combustion flash from blending
    against itself where two steps would otherwise cross.

    `section` is a widest-first list of (x half extent, z half extent) pairs.
    """
    faces_top = {"up": top_tex} if top_tex else None
    out = []
    for k in range(len(section) - 1):
        (a, b), (inner, _) = section[k], section[k + 1]
        for sign in (-1, 1):
            x0, x1 = sorted((cx + sign * inner, cx + sign * a))
            out.append(el((x0, y0, cz - b), (x1, y1, cz + b), tex, faces_top))
    a, b = section[-1]
    out.append(el((cx - a, y0, cz - b), (cx + a, y1, cz + b), tex, faces_top))
    return out


def round_section(y0, y1, section, tex, top_tex=None):
    """The same round section, centred on the block - the piston and the hubs."""
    return round_section_at(8, 8, y0, y1, section, tex, top_tex)


# ---------------------------------------------------------------------------
# baking: cull what cannot be seen, separate what cannot be ordered
# ---------------------------------------------------------------------------
def _face_rect(box, axis):
    """The two extents of a box other than `axis`, as ((lo, hi), (lo, hi))."""
    return tuple((box[0][i], box[1][i]) for i in range(3) if i != axis)


def _face_area(box, axis):
    return math.prod(box[1][i] - box[0][i] for i in range(3) if i != axis)


def _overlap_area(a, b, axis):
    """Area shared by two boxes projected along `axis`; 0 if they miss."""
    area = 1.0
    for i in range(3):
        if i == axis:
            continue
        span = min(a[1][i], b[1][i]) - max(a[0][i], b[0][i])
        if span <= 1e-9:
            return 0.0
        area *= span
    return area


def cull(specs):
    """Drop every face that another element of the same model buries.

    Conservative on purpose: a face only goes when one single element both
    stands on its outward side and spans it on both other axes. A face covered
    by two elements between them stays, because proving that case needs real
    area subtraction and getting it wrong punches a hole in a model.

    Runs after separation, on final coordinates, so "spans it" can be exact:
    nothing is going to move afterwards and open a gap behind a culled quad.
    """
    boxes = [(s["from"], s["to"]) for s in specs]
    for i, spec in enumerate(specs):
        a = boxes[i]
        for face in list(spec["live"]):
            axis, side = FACE_AXIS[face]
            plane = a[side][axis]
            others = [k for k in range(3) if k != axis]
            rect = _face_rect(a, axis)
            for j, b in enumerate(boxes):
                if i == j:
                    continue
                # the neighbour has to stand on the side the face looks at
                if side == 1:
                    if not (b[0][axis] <= plane + 1e-9
                            and b[1][axis] > plane + 1e-9):
                        continue
                elif not (b[1][axis] >= plane - 1e-9
                          and b[0][axis] < plane - 1e-9):
                    continue
                if all(b[0][k] <= lo + 1e-9 and b[1][k] >= hi - 1e-9
                       for k, (lo, hi) in zip(others, rect)):
                    spec["live"].discard(face)
                    break


def separate(specs):
    """Step co-planar, same-facing, overlapping quads out of each other's plane.

    Every quad sharing a plane is a node; two are joined when they overlap in
    area, because only then can the depth buffer see both at one pixel. Greedy
    colouring on that graph gives each node the lowest step that none of its
    neighbours took, so the widest face in a contested plane never moves at all
    and the rest give way by the smallest amount that separates them.
    """
    boxes = [(s["from"], s["to"]) for s in specs]
    planes = {}
    for i, spec in enumerate(specs):
        for face in spec["live"]:
            axis, side = FACE_AXIS[face]
            planes.setdefault((axis, side, round(boxes[i][side][axis], 4)),
                              []).append(i)

    moves = []
    for (axis, side, _plane), members in sorted(planes.items()):
        if len(members) < 2:
            continue
        members.sort(key=lambda i: -_face_area(boxes[i], axis))
        taken = {}
        for i in members:
            used = {taken[j] for j in taken
                    if _overlap_area(boxes[i], boxes[j], axis) > 0.0}
            step = 0
            while step in used:
                step += 1
            taken[i] = step
            if step:
                moves.append((i, axis, side, step))

    for (i, axis, side, step) in moves:
        spec = specs[i]
        end = "to" if side == 1 else "from"
        spec[end][axis] += -SEPARATION * step if side == 1 else SEPARATION * step
        thickness = spec["to"][axis] - spec["from"][axis]
        if thickness < 0.1:
            raise AssertionError(
                f"separation collapsed an element to {thickness:.3f} units "
                f"thick: {spec['from']} -> {spec['to']}")


def bake(specs):
    """Specs -> model JSON elements, culled and separated."""
    live = []
    for spec in specs:
        spec = copy.deepcopy(spec)
        spec["live"] = set(FACE_ORDER)
        live.append(spec)
    separate(live)
    cull(live)

    out = []
    for spec in live:
        if not spec["live"]:
            continue      # wholly enclosed by other elements - it draws nothing
        x0, y0, z0 = spec["from"]
        x1, y1, z1 = spec["to"]
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
        faces = {}
        for face in FACE_ORDER:
            if face not in spec["live"]:
                continue
            faces[face] = {"uv": spec["uvs"].get(face, default_uv[face]),
                           "texture": "#" + spec["faces"].get(face, spec["tex"])}
        out.append({"from": [r2(c) for c in spec["from"]],
                    "to": [r2(c) for c in spec["to"]],
                    "faces": faces, "shade": True})
    return out


def write(path, model):
    full = os.path.join(ROOT, path)
    os.makedirs(os.path.dirname(full), exist_ok=True)
    with open(full, "w") as f:
        json.dump(model, f, indent=2)
        f.write("\n")
    elements = model.get("elements", [])
    quads = sum(len(e["faces"]) for e in elements)
    print(f"wrote {path:34s} {len(elements):3d} elements {quads:4d} quads")


def model(textures, specs, parent="minecraft:block/block", display=None):
    out = {"parent": parent,
           "textures": {k: (v if ":" in v else NS + "block/" + v)
                        for k, v in textures.items()}}
    if display:
        # Only the contexts named here are overridden; the rest still come from
        # block/block, so held/framed poses stay consistent with every other
        # block item.
        out["display"] = display
    out["elements"] = bake(specs)
    return out


# Parts that deliberately do not fill their block look lost in an inventory
# slot at the standard 0.625, so their icons are scaled up.
def gui_scale(scale, rotation=(30, 225, 0)):
    return {"gui": {"rotation": list(rotation), "translation": [0, 0, 0],
                    "scale": [scale, scale, scale]}}


def sprite_item(path):
    """A flat 16x16 item icon.

    Used for the small parts and the consumables rather than a cuboid model.
    A spark plug is three millimetres of hex and a ceramic body: rendered as
    geometry at inventory scale it is a grey stick, where a drawn icon keeps the
    silhouette a player recognises without reading the name. Vanilla makes the
    same call for every one of its own small items.
    """
    return {"parent": "minecraft:item/generated",
            "textures": {"layer0": NS + "item/" + path}}


# ===========================================================================
# CRANKCASE - the static half of the Crankshaft block
# ===========================================================================
# Interior cavity: x 2..14, y 3..13, z 2.5..13.5. Everything that rotates is
# sized to stay inside it (max swept radius 4.87 against a 5.0 limit).
CASE_TEX = {"particle": "crankshaft", "case": "crankshaft",
            "cast": "cast_iron", "deck": "crankcase_deck", "steel": "journal",
            "brass": "brass", "ind": "indicator_off"}


# The ignition switch. It is the engine's primary control now that redstone is
# optional, so it is modelled rather than implied - a player has to be able to
# see whether the engine is switched on without wearing goggles.
#
# It lives on the solid lower rail beside the tell-tale, for the same reason the
# tell-tale does: over a window it would float in the opening with the crank
# visible behind it. That rail is only three units tall, so the switch is small
# and its travel is short; what makes the two positions readable at a glance is
# that the knob moves from below the pivot to above it.
SWITCH_X0, SWITCH_X1 = 10.6, 12.8


def ignition_switch_elements(ignition_on, near_flank):
    """One ignition switch, on one flank of the crankcase.

    `near_flank` selects the flank at low z; the other one is the same switch
    mirrored through the middle of the block, so the engine reads the same from
    whichever side the player walks up to.

    Nothing reaches z = 0 exactly: a face in the block's own boundary plane
    would z-fight with whatever solid block is placed against the crankcase.
    """
    def part(x0, y0, x1, y1, za, zb, tex):
        z0, z1 = (za, zb) if near_flank else (16.0 - zb, 16.0 - za)
        return el((x0, y0, z0), (x1, y1, z1), tex)

    e = [
        part(SWITCH_X0, 2.2, SWITCH_X1, 4.9, 0.1, 0.7, "cast"),      # mounting plate
        part(11.25, 3.1, 12.15, 4.0, 0.05, 0.9, "steel"),            # pivot boss
    ]
    if ignition_on:
        e.append(part(11.45, 3.55, 11.95, 4.65, 0.2, 0.78, "steel"))   # arm, raised
        e.append(part(11.15, 4.05, 12.25, 4.75, 0.06, 0.98, "brass"))  # knob
    else:
        e.append(part(11.45, 2.45, 11.95, 3.55, 0.2, 0.78, "steel"))   # arm, dropped
        e.append(part(11.15, 2.35, 12.25, 3.05, 0.06, 0.98, "brass"))
    return e


def crankcase_elements(ignition_on=False, joined=False):
    """One crankcase section.

    `joined` means another crankshaft section sits against this one's NEGATIVE
    axial face, i.e. this is not the first cylinder of its engine. It is what
    turns a row of sections into ONE casting, and it does four things:

    * the machined top deck, the bottom joint band, the pan lip and the oil
      gallery all run 0.5 PAST the negative boundary instead of stopping 0.5
      short of it, so each meets the neighbouring section's own face to face and
      the four run unbroken from one end of an inline-4 to the other;
    * a main bearing cap straddles the seam, so the joint reads as the bolted
      main bearing it is rather than as two machines standing next to each
      other;
    * and the IGNITION SWITCH is left off. An engine has one ignition, on its
      controller - the section at the negative end of the run, which is the only
      section with nothing joined behind it - so an inline-4 carries one switch,
      not four. Four identical controls in a row is the single loudest way of
      saying "these are four separate engines", and it was never true.

    Only the negative side is tested, because a seam has two sides and only one
    of them needs to reach across it - testing both would put two decks, two
    bands and two bearing caps in the same place.

    The running tell-tale stays on every section, and deliberately: it is an
    indicator rather than a control, and a lamp per bore is how a long engine
    shows at a glance that it is live from wherever the player is standing.

    Nothing else is conditional. The end walls stay: two of them between
    adjacent throws is a 4-unit main bearing web, which is exactly what an inline
    engine has there, and their touching faces point away from each other so
    nothing z-fights.
    """
    # Everything that has to cross a seam starts here when there is a section
    # behind, and 0.5 inside the block when there is not. The far end always
    # stops at 15.5, where the next section's -0.5 meets it exactly - so an
    # engine of any length is continuous in the middle and cleanly finished at
    # both ends, from one flag.
    back = -0.5 if joined else 0.5
    e = []
    # --- crankcase floor and the machined joint face the Oil Sump bolts to.
    # The pan itself is NOT here: an Oil Sump block lives at crankshaft.below()
    # and carries it, so duplicating a pan would give the engine two of them.
    e.append(el((1.5, 0.6, 2.0), (14.5, 3.0, 14.0), "case"))
    e.append(el((back, 0.0, 0.9), (15.5, 1.4, 15.1), "deck"))
    # --- the shallow pan lip, hanging under the block.
    # Every section has one and it crosses every seam, so the underside of an
    # inline-4 is one continuous pan with the deep Oil Sump bolted under one
    # bay of it - which is what the single shared sump actually is. Where a sump
    # IS fitted this is entirely inside that block's top flange and draws
    # nothing, so it costs the common case nothing and needs no block state to
    # know whether a sump is there.
    e.append(el((back, -1.0, 1.3), (15.5, 0.0, 14.7), "cast"))
    e.append(el((back, -1.7, 2.6), (15.5, -1.0, 13.4), "cast"))
    # --- side walls, cut away so the crank is visible ---------------------
    for z0, z1 in ((0.5, 2.5), (13.5, 15.5)):
        e.append(el((1.0, 2.0, z0), (15.0, 5.0, z1), "case"))      # lower rail
        e.append(el((1.0, 12.8, z0), (15.0, 14.0, z1), "case"))    # upper rail
        e.append(el((1.0, 2.0, z0), (3.5, 14.0, z1), "case"))      # post
        e.append(el((12.5, 2.0, z0), (15.0, 14.0, z1), "case"))    # post
    # --- the main oil gallery, along both flanks just above the joint band.
    # It is the lubrication system made visible: one pipe fed from the one Oil
    # Sump and running the whole length of the engine, past every bearing, so an
    # inline-4 reads as one lubricated machine rather than as four that happen to
    # touch. The Oil Sump's own risers come up to meet it.
    #
    # The union boss at each end is what saves this from needing a second block
    # state: at a seam two of them meet and read as a coupling, and at the end of
    # the engine the one that is left reads as the blank cap on a pipe run.
    for za, zb in ((0.15, 0.95), (15.05, 15.85)):
        e.append(el((back, 1.5, za), (15.5, 2.15, zb), "steel"))
        e.append(el((0.1, 1.25, za - 0.1), (1.4, 2.4, zb + 0.1), "cast"))
        e.append(el((14.6, 1.25, za - 0.1), (15.9, 2.4, zb + 0.1), "cast"))
    # --- end walls carrying the main bearings ------------------------------
    e.append(el((0.0, 2.0, 0.5), (2.0, 14.0, 15.5), "case"))
    e.append(el((14.0, 2.0, 0.5), (16.0, 14.0, 15.5), "case"))
    e.append(el((0.0, 3.5, 3.5), (3.5, 12.5, 12.5), "case"))
    e.append(el((12.5, 3.5, 3.5), (16.0, 12.5, 12.5), "case"))
    # --- the main bearing cap over the seam, when there is one.
    # Straddling the boundary rather than sitting beside it: the two end walls
    # either side of it are one 4-unit bearing web, and this is the cap bolted
    # over the journal running through it.
    if joined:
        for za, zb in ((0.05, 1.0), (15.0, 15.95)):
            e.append(el((-1.8, 4.6, za), (1.8, 10.4, zb), "cast"))
            for y0 in (5.2, 9.0):
                e.append(el((-1.3, y0, za - 0.05), (-0.3, y0 + 1.0, zb + 0.05), "brass"))
                e.append(el((0.3, y0, za - 0.05), (1.3, y0 + 1.0, zb + 0.05), "brass"))
    # --- machined top deck, with the slot the connecting rod swings through.
    # It runs 0.5 past the top of the block so the Cylinder lands on a collar
    # that plainly belongs to the crankcase rather than butting a seam.
    e.append(el((back, 13.0, 0.5), (15.5, 16.5, 2.5), "deck"))
    e.append(el((back, 13.0, 13.5), (15.5, 16.5, 15.5), "deck"))
    e.append(el((back, 13.0, 2.0), (5.0, 16.5, 14.0), "deck"))
    e.append(el((11.0, 13.0, 2.0), (15.5, 16.5, 14.0), "deck"))
    # --- running tell-tale, one on each side so it reads from either flank.
    # It sits on the solid lower rail rather than over a window, so it has
    # crankcase behind it instead of floating in the opening. The outward faces
    # take the whole lamp texture rather than a world-aligned slice of it -
    # otherwise a 3x2.4 boss samples a corner of the sprite and the lens, which
    # is drawn in the middle, never appears at all.
    lens = {"north": [0, 0, 16, 16], "south": [0, 0, 16, 16]}
    e.append(el((6.5, 2.4, 0.1), (9.5, 4.8, 1.1), "ind", uvs=lens))
    e.append(el((6.5, 2.4, 14.9), (9.5, 4.8, 15.9), "ind", uvs=lens))
    # --- the ignition switch, on both flanks beside the tell-tale, and only on
    # the section that actually owns the engine's ignition.
    if not joined:
        for near in (True, False):
            e += ignition_switch_elements(ignition_on, near)
    # --- head studs standing proud of the deck, so the joint the Cylinder
    # lands on plainly bolts down rather than just meeting.
    for x0, x1 in ((2.2, 3.8), (12.2, 13.8)):
        e.append(el((x0, 13.6, 0.1), (x1, 15.2, 0.5), "steel"))
        e.append(el((x0, 13.6, 15.5), (x1, 15.2, 15.9), "steel"))
    return e


# ===========================================================================
# OIL SUMP - the lower half of the crankcase, at crankshaft.below()
# ===========================================================================
# Its top flange runs 0.8 up into the crankshaft block and is wider than the
# crankcase's own joint face, so the two swallow each other at the seam and the
# pair reads as one crankcase assembly with a removable pan.
SUMP_TEX = {"particle": "oil_sump", "sump": "oil_sump",
            "deck": "crankcase_deck", "steel": "journal", "brass": "brass"}


def oil_sump_elements():
    """The one shared pan, and the two risers that say so.

    A multi-cylinder engine has ONE Oil Sump, and the thing that has to be
    visible is that the oil in it reaches every bearing rather than only the bay
    it hangs under. The crankcase carries the gallery that does the reaching -
    one pipe along each flank, running the whole length of the engine - and
    these risers are where it is fed from: they leave the pan's flange at the
    same z as the gallery and stand up into the crankcase block to meet it.

    The top flange is as wide as the block will allow rather than inset, so it
    lands under the shallow pan lip every crankcase section hangs and the two
    read as one pan with a deep sump bolted into one bay of it - which is what
    the single shared sump is. A twentieth of a unit inside the boundary on each
    side, for the same reason nothing else here touches it exactly: a face in the
    block's own boundary plane fights with whatever is placed against it.
    """
    e = [
        el((0.05, 13.2, 0.05), (15.95, 16.8, 15.95), "deck"),  # bolted top flange
        el((1.6, 8.5, 1.6), (14.4, 14.0, 14.4), "sump"),      # pan, tapering down
        el((2.8, 4.5, 2.8), (13.2, 9.2, 13.2), "sump"),
        el((4.2, 2.0, 4.2), (11.8, 5.2, 11.8), "sump"),
        el((7.0, 1.2, 3.6), (9.0, 3.0, 5.0), "steel"),        # drain plug
    ]
    for x0, x1 in ((2.2, 3.8), (12.2, 13.8)):                 # flange bolts
        e.append(el((x0, 13.8, 0.1), (x1, 15.4, 1.1), "steel"))
        e.append(el((x0, 13.8, 14.9), (x1, 15.4, 15.9), "steel"))
    # Ribs cast down the pan's flanks. A pressed or thinly cast oil pan is the
    # one panel on an engine that is always ribbed, because otherwise it drums,
    # and they give the largest blank face on the whole assembly something to
    # catch the light with.
    for x0, x1 in ((4.4, 5.8), (10.2, 11.6)):
        e.append(el((x0, 9.0, 1.2), (x1, 13.6, 1.65), "sump"))
        e.append(el((x0, 9.0, 14.35), (x1, 13.6, 14.8), "sump"))
    # Dipstick, up the +Z flank. It has to stand proud of the top flange in Z
    # rather than run up through it: the flange is one solid box across the
    # whole top of the pan, so a tube inside its footprint would simply be
    # swallowed and the handle would never appear.
    e.append(el((6.9, 9.0, 15.45), (7.9, 15.2, 15.95), "steel"))
    e.append(el((6.6, 15.2, 15.4), (8.2, 16.0, 16.0), "brass"))
    # Oil feed risers, up to the crankcase's gallery. They stand at the same
    # x as the gallery's union bosses and reach 1.6 into the block above, which
    # is where that pipe runs - see crankcase_elements. Authored for a crank
    # axis along X like everything else here; the blockstate turns the pan with
    # the engine, so on a Z engine they come up under the gallery on that axis
    # instead.
    for x0, x1 in ((2.4, 3.6), (12.4, 13.6)):
        for za, zb in ((0.2, 1.0), (15.0, 15.8)):
            e.append(el((x0, 12.0, za), (x1, 17.6, zb), "steel"))
            e.append(el((x0 - 0.3, 16.2, za - 0.1), (x1 + 0.3, 17.2, zb + 0.1), "brass"))
    return e


# ===========================================================================
# CRANKSHAFT - the rotating half, authored at crank angle 0 (pin at BDC)
# ===========================================================================
CRANK_TEX = {"particle": "journal", "steel": "journal", "web": "crank_web"}


def crank_elements():
    e = [
        # The main journals run out through the crankcase's end walls, which
        # means their end faces land on the block boundary - the exact plane
        # those walls' own outer faces are in. One of the two is turning, so
        # that is a 5x5 patch of shimmer on both ends of every engine. A twenty
        # thousandth of a block proud of the wall is enough to order them and
        # far too little to see.
        el((-0.05, 5.5, 5.5), (4.8, 10.5, 10.5), "steel"),    # main journal
        el((11.2, 5.5, 5.5), (16.05, 10.5, 10.5), "steel"),   # main journal
        # Both journals step down to a stub as they leave the block, on a
        # Create Shaft's cross-section (6..10) so a shaft bolted to either end
        # continues the journal instead of swallowing a wider boss - and both
        # ends really are kinetic outputs now, so both have to look like it.
        #
        # Held a hair inside 6..10 rather than exactly on it. The stub reaches
        # a unit into whatever is next door, and the Flywheel's own shaft is
        # modelled at exactly 6..10 through its whole block: matching it would
        # put four pairs of identical faces in identical planes, on two parts
        # that turn together, which is a shimmer that never stops. Slightly
        # under, the stub simply disappears inside the shaft it feeds.
        el((-1.0, 6.15, 6.15), (0.0, 9.85, 9.85), "steel"),
        el((16.0, 6.15, 6.15), (17.0, 9.85, 9.85), "steel"),
        el((5.0, 3.6, 6.6), (11.0, 6.4, 9.4), "steel"),     # offset crank pin
    ]
    for x0, x1 in ((4.0, 6.4), (9.6, 12.0)):
        # The disc is a full octagon rather than a cross: it is the largest
        # thing turning behind the crankcase windows, and a cross reads as two
        # bars flipping past each other rather than as a wheel going round. The
        # chamfer corner sits 4.67 from the axis, inside the 4.87 the cavity
        # allows, so the swept envelope is unchanged.
        e.append(el((x0, 4.2, 5.4), (x1, 11.8, 10.6), "web"))   # web disc
        e.append(el((x0, 5.4, 4.2), (x1, 10.6, 11.8), "web"))
        e.append(el((x0, 4.7, 4.7), (x1, 11.3, 11.3), "web"))
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
    # The one part of the engine that is both round and in constant motion in
    # plain sight, so it is the one part that gets the twenty-sided section.
    e = []
    e += round_section(10.2, 12.0, fine_steps(4.25, 2.5, 3.4), "side",
                       top_tex="crown")                                # crown
    e += round_section(9.3, 10.3, fine_steps(3.75, 2.2, 2.95), "side")  # groove
    e += round_section(8.6, 9.4, fine_steps(4.25, 2.5, 3.4), "side")    # land
    e += round_section(5.5, 8.7, fine_steps(4.0, 2.35, 3.18), "side")   # skirt
    return e


# ===========================================================================
# CYLINDER - cutaway finned barrel with an integrated head
# ===========================================================================
# No ceramic or brass here any more: the spark plug moved to its own model, and
# a texture reference that nothing uses is a texture that quietly rots.
CYL_TEX = {"particle": "cylinder", "barrel": "cylinder", "fin": "cylinder_fin",
           "head": "cylinder_head", "deck": "crankcase_deck",
           "steel": "journal", "case": "crankshaft", "brass": "brass"}

# ---------------------------------------------------------------------------
# Spark plug
# ---------------------------------------------------------------------------
# Screwed vertically down through the head, on the +X side of the bore, and
# standing up out of the head's boss beside the Carburetor.
#
# It used to be mounted horizontally through the head's +X flank. That is what a
# real small engine does and in a 16-unit block it does not work: a plug long
# enough to read as hex, insulator and terminal needs about 3.8 units, the
# head's flank is at x 14.8, and the 1.2 units of block left over are nowhere
# near enough - so two thirds of the plug hung in the sky beside the machine
# with nothing behind it, which is exactly what it looked like.
#
# Vertical solves that without shortening anything. The column at
# x 11.25 .. 12.55, z 7.35 .. 8.65 is inside the bore at the bottom and empty
# all the way up: the Carburetor's nearest parts on that side are its air horn
# and clamp ring at x <= 11.1, and its throttle lever swings no further than
# z 6.6. So the plug passes through 3.2 units of solid head casting, emerges
# from the top of the head's boss, and stands next to the carburetor - inside
# the engine's own silhouette from every angle.
#
# The plug is drilled through the *casting*, exactly as a real head is: the
# threaded shell is buried in metal from the chamber roof to the top of the
# boss and is therefore invisible. Everything a player can see is either above
# the head - hex, insulator, terminal - or inside the chamber - electrode and
# ground strap - and nothing else.
#
# CHAMBER GEOMETRY, which decides every number below:
#
#   head underside (chamber roof)                    y = 14.00
#   piston crown at top dead centre                  y = 13.50
#     = piston model crown (12.0) + wristPinHeight(180) - WRIST_PIN_MODEL_HEIGHT
#     = 12.0 + (8 + 3 + 14.5 - 16) - 8
#   clearance volume                                 0.50 units tall
#   bore                                             x/z 3.4 .. 12.6
#
# So the whole of the plug's business end has to live in half a unit, and
# nothing may reach below 13.50 or the piston would drive through it at TDC.
# The lowest part of this plug is the ground strap at 13.60.
CHAMBER_ROOF = 14.0
PISTON_TDC_CROWN = 13.5

# Where the spark actually happens: the gap between the electrode tip and the
# strap under it, on the bore side of the roof. CrankshaftBlockEntity aims its
# particle here, so the two must not drift apart.
SPARK_PLUG_AXIS_X, SPARK_PLUG_AXIS_Z = 11.90, 8.0
SPARK_PLUG_ELECTRODE = (SPARK_PLUG_AXIS_X, 13.79, SPARK_PLUG_AXIS_Z)


SPARK_PLUG_TEX = {"particle": "spark_plug_ceramic", "steel": "journal",
                  "ceramic": "spark_plug_ceramic", "brass": "brass"}


def spark_plug_elements():
    """The plug itself, in Cylinder block space.

    Its own model since the plug became an installable component: the block
    entity renderer draws it only when the head has one in it, so no transform
    is applied and these coordinates are the final ones.

    The threaded shell is kept even though the head casting hides it. It used to
    be dropped by `bake` for being wholly enclosed - by elements of the *same*
    model, back when the plug was part of the Cylinder - and on its own nothing
    encloses it any more. Six quads inside opaque metal cost nothing, and they
    are the reason the parts above and below the head line up.
    """
    return [
        # --- in the chamber: electrode and strap, and nothing else ---------
        # The electrode comes through the roof; only its last 0.16 is in the
        # chamber, which is all that fits under a 0.5 unit clearance volume and
        # all a real plug shows anyway.
        el((11.75, 13.84, 7.85), (12.05, 14.60, 8.15), "steel"),   # centre electrode
        el((11.30, 13.60, 7.80), (11.55, 14.30, 8.20), "steel"),   # ground strap, leg
        el((11.30, 13.60, 7.80), (12.15, 13.74, 8.20), "steel"),   # ground strap, tip
        # --- through the head: buried in the casting, never in the bore ----
        # Wholly enclosed by the head slab and the boss above it, so `bake`
        # drops it and it costs nothing. It is kept because it is the reason the
        # parts above and below line up, and because it would reappear the
        # moment the head stopped covering it.
        el((11.25, 14.05, 7.35), (12.55, 17.15, 8.65), "steel"),   # threaded shell
        # --- above the head, standing beside the Carburetor ---------------
        # The hex starts inside the boss rather than on top of it: seated in the
        # casting it can only read as screwed in, where a hex resting exactly on
        # the surface reads as balanced on it.
        el((11.15, 17.05, 7.25), (12.65, 18.35, 8.75), "steel"),    # spanner hex
        el((11.30, 18.35, 7.40), (12.50, 19.75, 8.60), "ceramic"),  # insulator
        el((11.45, 19.75, 7.55), (12.35, 20.45, 8.45), "ceramic"),  # insulator, ribbed
        el((11.55, 20.45, 7.65), (12.25, 21.00, 8.35), "brass"),    # terminal
    ]


# ---------------------------------------------------------------------------
# The shared intake manifold
# ---------------------------------------------------------------------------
# A multi-cylinder engine has ONE Carburetor, and on a real engine that is
# entirely ordinary: the carburetor sits on a manifold, and the manifold feeds
# every bore through a runner apiece. Without the manifold drawn, one carburetor
# over one of four cylinders reads as three cylinders with nothing feeding them -
# which is the single strongest reason an inline-4 used to look like four
# one-cylinder engines standing in a row.
#
# It is geometry on the CYLINDER rather than a block of its own, because the
# manifold is not a thing a player builds - it is what the engine looks like once
# the cylinders are adjacent. Two cosmetic block state properties say which way
# the run continues (see CylinderBlock.MANIFOLD_NEGATIVE / _POSITIVE) and each
# section draws its own share: a rail spanning its block, a runner down into its
# own port, and half a bolted collar at each seam, so the halves either side of a
# seam meet as one collar and the rail runs unbroken from one end to the other.
#
# It lives in the block ABOVE the cylinder, at the height of the head's intake
# flange - which is where a manifold goes, and which is empty air on every
# section except the one carrying the Carburetor. There, the Carburetor's own
# mounting flange lands inside the rail, so the one carburetor is visibly bolted
# to the manifold that feeds all four.
#
# An inline-1 gets NONE of this: with nothing to share, its Carburetor sits
# straight down on its head exactly as it always has.
RAIL_Y0, RAIL_Y1 = 17.55, 19.45
RAIL_Z0, RAIL_Z1 = 0.55, 3.95
# Where the rail stops when the run does. Far enough inside the block to leave
# room for the end cap, and to read as a manifold that ends rather than as one
# that was cut off at the block boundary.
RAIL_END_BACK, RAIL_END_AHEAD = 3.0, 13.0


def manifold_elements(link_back, link_ahead):
    """The rail, this cylinder's runner, and whatever finishes each end."""
    x0 = 0.0 if link_back else RAIL_END_BACK
    x1 = 16.0 if link_ahead else RAIL_END_AHEAD
    e = [
        el((x0, RAIL_Y0, RAIL_Z0), (x1, RAIL_Y1, RAIL_Z1), "deck"),   # the rail
        # The runner: down out of the rail and onto the head's intake flange,
        # which reaches up to 17.75 and is therefore already inside it.
        el((4.4, 17.1, 0.75), (11.6, RAIL_Y0 + 0.15, 3.75), "head"),
        # The joint face over this bore, so every cylinder visibly has its own
        # runner bolted to the shared rail rather than the rail merely passing by.
        el((4.4, 17.75, 0.15), (11.6, 19.25, 0.6), "deck"),
    ]
    for x in (5.2, 10.0):
        e.append(el((x, 18.1, 0.05), (x + 0.8, 18.9, 0.35), "brass"))
    # Half a collar at each seam, and a cap wherever the run ends.
    if link_back:
        e.append(el((0.0, 17.35, 0.35), (1.5, 19.65, 4.15), "head"))
    else:
        e.append(el((RAIL_END_BACK - 0.45, 17.45, 0.45),
                    (RAIL_END_BACK + 0.15, 19.55, 4.05), "head"))
    if link_ahead:
        e.append(el((14.5, 17.35, 0.35), (16.0, 19.65, 4.15), "head"))
    else:
        e.append(el((RAIL_END_AHEAD - 0.15, 17.45, 0.45),
                    (RAIL_END_AHEAD + 0.45, 19.55, 4.05), "head"))
    return e


def cylinder_elements(link_back=False, link_ahead=False):
    """One cylinder, and how much of its neighbours' castings it reaches for.

    `link_back` and `link_ahead` say whether another cylinder of the same engine
    sits against this one's negative and positive axial faces. Beyond the intake
    manifold above, they do one more thing: the cooling fins and the base flange
    run all the way to the block boundary on a linked side instead of stopping
    1.6 short of it, so adjacent barrels meet face to face and an inline-4 has
    four continuous fin bands running its whole length rather than four separate
    finned towers with a gap between each pair.

    The bores themselves are untouched, and so are the seams: a player can still
    count the cylinders, which is the point of building an engine out of them.
    """
    # How far the shared castings reach on each side.
    lo = 0.0 if link_back else 1.6
    hi = 16.0 if link_ahead else 14.4
    base_lo = 0.0 if link_back else 1.5
    base_hi = 16.0 if link_ahead else 14.5
    e = []
    # --- flange bolted to the crankcase deck ------------------------------
    e.append(el((base_lo, 0.0, 1.5), (base_hi, 1.6, BORE_MIN), "case"))
    e.append(el((base_lo, 0.0, BORE_MAX), (base_hi, 1.6, 14.5), "case"))
    e.append(el((base_lo, 0.0, 3.0), (BORE_MIN, 1.6, 13.0), "case"))
    e.append(el((BORE_MAX, 0.0, 3.0), (base_hi, 1.6, 13.0), "case"))
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
    # bored out to the cylinder bore so the piston is never touched. Four of
    # them on a 2.8 pitch rather than three on 3.2: closer-packed fins are what
    # an air-cooled barrel actually looks like, and the extra ring fills the
    # bare stretch that used to sit between the top fin and the head.
    # Side faces take the whole fin texture height on purpose - that is what
    # gives every fin its lit top edge and shadowed root.
    fin_uv = {"north": [0, 0, 16, 16], "south": [0, 0, 16, 16],
              "east": [0, 0, 16, 16], "west": [0, 0, 16, 16]}
    flat = {"up": "barrel", "down": "barrel"}
    for y0 in (2.9, 5.7, 8.5, 11.3):
        y1 = y0 + 0.9
        e.append(el((lo, y0, 0.6), (hi, y1, BORE_MIN), "fin", flat, fin_uv))
        e.append(el((lo, y0, BORE_MAX), (hi, y1, 15.4), "fin", flat, fin_uv))
        e.append(el((0.0 if link_back else 0.6, y0, 1.6), (BORE_MIN, y1, 14.4),
                    "fin", flat, fin_uv))
        e.append(el((BORE_MAX, y0, 1.6), (16.0 if link_ahead else 15.4, y1, 14.4),
                    "fin", flat, fin_uv))
    # --- head. Reaches past the top of the block so the Carburetor lands on
    # a flange belonging to the head instead of sitting on a bare block face.
    e.append(el((1.2, 14.0, 1.2), (14.8, 15.6, 14.8), "head"))
    e.append(el((2.5, 15.4, 2.5), (13.5, 17.2, 13.5), "head"))
    for x0, x1 in ((1.6, 3.2), (12.8, 14.4)):
        for z0, z1 in ((1.6, 3.2), (12.8, 14.4)):
            e.append(el((x0, 14.8, z0), (x1, 17.8, z1), "steel"))
    e.append(el((5.4, 13.8, 0.0), (10.6, 17.6, 4.6), "head"))       # intake port
    # The flange stops just under 17.8 so its top face misses the plane the
    # Carburetor's float bowl floor sits in, one block up.
    e.append(el((4.6, 16.6, 0.2), (11.4, 17.75, 4.2), "deck"))      # intake flange
    e.append(el((6.0, 13.8, 11.6), (10.0, 16.4, 16.0), "head"))     # exhaust boss
    e.append(el((5.2, 12.9, 15.0), (10.8, 16.9, 16.0), "deck"))     # exhaust flange
    # The spark plug is NOT part of this model any more. It is an installable
    # component now, so it lives in its own model that the block entity renderer
    # draws only when one is fitted - see spark_plug_elements. The head keeps its
    # boss either way, because the boss is the threaded seat cast into the head
    # and is there whether or not anything is screwed into it.
    if link_back or link_ahead:
        e += manifold_elements(link_back, link_ahead)
    return e


# ===========================================================================
# COMBUSTION FLASH - the burn, drawn inside the chamber for a few ticks
# ===========================================================================
# Rendered translucent, at full brightness and fading out, so it reads as light
# inside the cylinder rather than as a solid object appearing in it.
#
# THE SPRITE IS THE EFFECT. combustion_flash.png is one soft radial blob -
# white-hot core, orange rim, transparent edge - and every face here takes the
# *whole* sprite rather than the world-aligned slice the rest of this generator
# emits. That is the difference between a fireball and what this used to be: a
# 0.95-tall disc whose side faces sampled a thin, almost fully transparent strip
# from the top of the blob, which is why the old flash read as a dim smear.
#
# SHAPE. Two crossed slabs plus a core, not one disc. A single disc has a
# silhouette that collapses to a line from some angles and the engine is meant
# to be looked at from all of them; crossed slabs always present one face
# roughly square-on, and their translucent overlap in the middle is what makes
# the centre the brightest part of the burn.
#
# PLACEMENT. The top sits just under the chamber roof and the bottom reaches
# down to the top of the cutaway window, so the burn fills the space the player
# can actually see into. It is *not* clipped to the clearance volume: at TDC the
# piston crown covers all but the top half unit of it, and the depth buffer
# does that for free because the piston is opaque and drawn first. As the crank
# turns the charge down the bore, more of the same flash is uncovered - which is
# precisely what a burning charge pushing a piston looks like.
FLASH_TEX = {"particle": "combustion_flash", "flash": "combustion_flash"}

# Whole sprite on every face. See above - this is load-bearing, not decoration.
FULL_SPRITE = {face: [0.0, 0.0, 16.0, 16.0] for face in FACE_ORDER}

FLASH_TOP = 13.96          # a hair under the chamber roof at 14.0
FLASH_BOTTOM = 12.10       # the top of the cutaway window between fin and head
FLASH_HALF = 2.6           # 5.2 across: 57 % of the 9.2 bore
FLASH_HALF_THICK = 0.45


def combustion_flash_elements():
    c = 8.0
    return [
        # crossed slabs, one along each horizontal axis
        el((c - FLASH_HALF, FLASH_BOTTOM, c - FLASH_HALF_THICK),
           (c + FLASH_HALF, FLASH_TOP, c + FLASH_HALF_THICK), "flash", uvs=FULL_SPRITE),
        el((c - FLASH_HALF_THICK, FLASH_BOTTOM, c - FLASH_HALF),
           (c + FLASH_HALF_THICK, FLASH_TOP, c + FLASH_HALF), "flash", uvs=FULL_SPRITE),
        # the core, drawn on top of both so the middle blows out to white
        el((c - 1.3, FLASH_BOTTOM + 0.35, c - 1.3),
           (c + 1.3, FLASH_TOP - 0.1, c + 1.3), "flash", uvs=FULL_SPRITE),
    ]


# ===========================================================================
# CARBURETOR - a compact intake component hung off the head's intake side,
# not a box centred in its own block.
# ===========================================================================
CARB_TEX = {"particle": "carburetor", "body": "carburetor",
            "brass": "brass", "steel": "journal"}

# --- float bowl -----------------------------------------------------------
# The bowl is built as a floor plus three walls, deliberately open on +Z. That
# opening is the sight window: the renderer draws the real tank contents inside
# it, so the fuel level a player sees is the amount the engine will actually
# burn, not a decoration. An open front rather than a modelled glass pane keeps
# it in the same cutaway idiom as the crankcase and the cylinder.
BOWL_X0, BOWL_X1 = 5.2, 10.8
BOWL_Z0, BOWL_Z1 = 4.0, 7.8
BOWL_WALL = 1.0
BOWL_FLOOR_TOP = 1.8          # inner floor - fuel sits on this
BOWL_RIM = 4.4                # underside of the clamp ring - fuel stops here

# The air cleaner and the throttle lever both hang off the intake side, so the
# carburetor's own centreline in Z is worth having in one place.
HORN_CZ = 2.65
THROTTLE_PIVOT = (12.0, 5.6, 2.6)


def carburetor_elements():
    e = [
        # Deliberately a touch wider and deeper than the Cylinder's intake
        # flange, which reaches 1.8 up into this block. Matching it exactly -
        # which it used to - shares three planes with it across two different
        # blocks, and the pair flickers wherever the seam is visible. Larger,
        # the carburetor's flange simply swallows the head's, the same way the
        # Oil Sump's flange swallows the crankcase's joint face.
        el((4.5, 1.4, 0.1), (11.5, 2.8, 4.7), "body"),      # mounting flange
        el((5.6, 2.6, 0.9), (10.4, 4.6, 4.4), "body"),      # throat
        el((6.3, 4.4, 1.5), (9.7, 6.4, 3.9), "body"),       # venturi waist
        el((5.6, 6.2, 0.9), (10.4, 8.4, 4.4), "body"),
        el((4.9, 8.2, 0.3), (11.1, 9.8, 5.0), "body"),      # air horn
    ]
    # --- float bowl, open on +Z so the fuel level reads from outside -------
    e.append(el((BOWL_X0, 1.0, BOWL_Z0),
                (BOWL_X1, BOWL_FLOOR_TOP, BOWL_Z1), "body"))          # floor
    e.append(el((BOWL_X0, BOWL_FLOOR_TOP, BOWL_Z0),
                (BOWL_X0 + BOWL_WALL, BOWL_RIM + 0.1, BOWL_Z1), "body"))
    e.append(el((BOWL_X1 - BOWL_WALL, BOWL_FLOOR_TOP, BOWL_Z0),
                (BOWL_X1, BOWL_RIM + 0.1, BOWL_Z1), "body"))
    e.append(el((BOWL_X0 + BOWL_WALL, BOWL_FLOOR_TOP, BOWL_Z0),
                (BOWL_X1 - BOWL_WALL, BOWL_RIM + 0.1, BOWL_Z0 + 1.0), "body"))
    e.append(el((4.9, BOWL_RIM, 4.1), (11.1, 5.3, 8.1), "brass"))     # clamp ring / lid
    # --- fuel inlet and the line feeding the bowl --------------------------
    # Sits above the clamp ring on purpose: anything at bowl height would hang
    # in front of the sight window and hide the very thing it is next to.
    e.append(el((7.2, 5.3, 7.4), (8.8, 6.8, 9.6), "brass"))    # inlet banjo
    e.append(el((6.9, 6.5, 9.0), (9.1, 7.7, 10.3), "brass"))   # union nut
    e.append(el((7.5, 7.5, 9.3), (8.5, 9.8, 10.0), "brass"))   # supply line
    # --- fuel/mixture pipe running down onto the head's intake flange ------
    # Straddles z 3.2 rather than ending on it: that is where the head's corner
    # stud boss, in the block below, has its own outward face.
    e.append(el((3.0, 1.0, 1.75), (4.4, 2.4, 3.25), "brass"))  # union on the head
    e.append(el((4.2, 1.5, 2.2), (5.6, 2.3, 2.8), "brass"))    # pipe into the body
    # --- throttle. Only the shaft is static; the lever is a partial model so
    # its angle can follow the authoritative throttle setting.
    e.append(el((9.8, 5.2, 2.2), (12.4, 6.0, 3.0), "brass"))   # throttle shaft
    e.append(el((3.6, 3.0, 1.8), (5.8, 4.0, 3.0), "brass"))    # idle screw
    return e


# ===========================================================================
# THROTTLE LEVER - authored with its pivot on the block centre, so the
# renderer can rotateCentered() and then translate it onto the real shaft.
# ===========================================================================
THROTTLE_TEX = {"particle": "brass", "brass": "brass", "steel": "journal"}


def throttle_lever_elements():
    return [
        el((7.1, 7.1, 7.1), (8.9, 8.9, 8.9), "steel"),      # shaft end / pivot boss
        el((7.5, 7.4, 8.6), (8.5, 8.6, 11.6), "brass"),     # lever arm
        el((7.3, 7.1, 11.2), (8.7, 8.9, 12.2), "brass"),    # cable pin
    ]


# ===========================================================================
# AIR FILTER - an old oil-bath style cleaner clamped onto the air horn
# ===========================================================================
# Authored directly in Carburetor block space, so the renderer draws it with no
# transform at all when one is installed.
FILTER_TEX = {"particle": "air_filter", "case": "air_filter",
              "mesh": "air_filter_mesh", "steel": "journal", "brass": "brass"}


def air_filter_elements():
    e = [el((6.6, 9.4, HORN_CZ - 1.4), (9.4, 10.6, HORN_CZ + 1.4), "steel")]
    e += round_section_at(8, HORN_CZ, 10.4, 11.6,
                          steps(2.65, 1.55, 2.1), "case")     # canister
    e += round_section_at(8, HORN_CZ, 11.6, 13.2,
                          steps(2.45, 1.45, 1.95), "mesh")    # element
    e += round_section_at(8, HORN_CZ, 13.2, 14.6, steps(2.65, 1.55, 2.1), "case")
    e += round_section_at(8, HORN_CZ, 14.6, 15.3,
                          steps(2.85, 1.65, 2.25), "case")    # lid
    e.append(el((7.3, 15.3, HORN_CZ - 0.7), (8.7, 16.1, HORN_CZ + 0.7), "brass"))
    return e


# ===========================================================================
# REDSTONE CONTROL MODULE - an item, plugged into a placed Crankshaft
# ===========================================================================
# Never drawn in the world: the module lives inside the crankcase's control
# area, and what the player sees of it there is the value box Create draws for
# its mode. This geometry exists only so the item has an icon that reads as a
# plug-in module rather than as a flat card.
MODULE_TEX = {"particle": "control_module", "case": "control_module",
              "brass": "brass", "steel": "journal"}


def control_module_elements():
    return [
        el((5.2, 4.6, 6.5), (10.8, 12.4, 9.5), "case"),      # moulded housing
        el((4.6, 5.8, 6.9), (11.4, 11.2, 9.1), "case"),      # side flanges
        el((6.3, 12.4, 7.1), (9.7, 13.3, 8.9), "steel"),     # cable gland
        el((6.6, 3.2, 6.9), (9.4, 4.6, 9.1), "brass"),       # edge connector
        el((5.3, 2.6, 7.1), (6.5, 4.8, 8.9), "brass"),       # locating pins
        el((9.5, 2.6, 7.1), (10.7, 4.8, 8.9), "brass"),
    ]


# ===========================================================================
# FLYWHEEL - large diameter, narrow, heavy rim, four spokes
# ===========================================================================
FLY_TEX = {"particle": "flywheel", "rim": "flywheel", "face": "flywheel_face",
           "hub": "cast_iron", "steel": "journal"}


def flywheel_elements():
    side = {"east": "face", "west": "face"}
    e = [el((0, 6, 6), (16, 10, 10), "steel")]              # through shaft
    # Retaining collars either side of the hub. They are what makes the wheel
    # look keyed to the shaft rather than threaded onto it, and being off the
    # axis they are the part of a spinning flywheel the eye actually tracks.
    e.append(el((3.0, 5.7, 5.7), (4.0, 10.3, 10.3), "steel"))
    e.append(el((12.0, 5.7, 5.7), (13.0, 10.3, 10.3), "steel"))
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
    case = crankcase_elements(ignition_on=False)
    case_lit = crankcase_elements(ignition_on=True)
    case_joined = crankcase_elements(ignition_on=False, joined=True)
    case_joined_lit = crankcase_elements(ignition_on=True, joined=True)
    crank = crank_elements()
    rod = rod_elements()
    piston = piston_elements()
    cyl = cylinder_elements()
    plug = spark_plug_elements()
    carb = carburetor_elements()
    fly = flywheel_elements()
    lever = throttle_lever_elements()
    air_filter = air_filter_elements()

    sump = oil_sump_elements()

    write("block/crankshaft.json", model(CASE_TEX, case))
    # The same crankcase with the ignition live: lit lens, and the switch beside
    # it standing up. The blockstate picks between the two from the engine's
    # effective ignition, so they must never drift apart in anything else - hence
    # one builder, called twice, rather than two element lists.
    write("block/crankshaft_lit.json",
          model({**CASE_TEX, "ind": "indicator_on"}, case_lit))
    # ... and the same pair again for a section whose negative neighbour is
    # another crankcase, so the deck runs across the seam.
    write("block/crankshaft_joined.json", model(CASE_TEX, case_joined))
    write("block/crankshaft_joined_lit.json",
          model({**CASE_TEX, "ind": "indicator_on"}, case_joined_lit))
    write("block/oil_sump.json", model(SUMP_TEX, sump))
    write("block/crank_assembly_x.json", model(CRANK_TEX, crank))
    write("block/crank_assembly_z.json",
          model(CRANK_TEX, [transpose(x) for x in crank]))
    write("block/connecting_rod_x.json", model(ROD_TEX, rod))
    write("block/connecting_rod_z.json",
          model(ROD_TEX, [transpose(x) for x in rod]))
    write("block/piston_head.json", model(PISTON_TEX, piston))
    write("block/cylinder.json", model(CYL_TEX, cyl))
    # ... and the three variants that carry a share of the shared intake
    # manifold. The blockstate picks between them from which way the engine's
    # cylinder run continues, and turns them with the crank axis, so one set of
    # models serves an engine built along either axis.
    write("block/cylinder_manifold_negative.json",
          model(CYL_TEX, cylinder_elements(link_back=True)))
    write("block/cylinder_manifold_positive.json",
          model(CYL_TEX, cylinder_elements(link_ahead=True)))
    write("block/cylinder_manifold_both.json",
          model(CYL_TEX, cylinder_elements(link_back=True, link_ahead=True)))
    # The parts a block entity renderer draws are not turned by the blockstate,
    # so anything whose shape is not symmetric about the cylinder axis needs one
    # model per crank axis - the same rule the connecting rod already follows.
    write("block/spark_plug_x.json", model(SPARK_PLUG_TEX, plug))
    write("block/spark_plug_z.json",
          model(SPARK_PLUG_TEX, [rotate_y90(x) for x in plug]))
    write("block/combustion_flash.json",
          model(FLASH_TEX, combustion_flash_elements()))
    write("block/oil_shale.json",
          {"parent": "minecraft:block/cube_all",
           "textures": {"all": NS + "block/oil_shale"}})
    write("block/carburetor.json", model(CARB_TEX, carb))
    write("block/throttle_lever_x.json", model(THROTTLE_TEX, lever))
    write("block/throttle_lever_z.json",
          model(THROTTLE_TEX, [rotate_y90(x) for x in lever]))
    write("block/air_filter_x.json", model(FILTER_TEX, air_filter))
    write("block/air_filter_z.json",
          model(FILTER_TEX, [rotate_y90(x) for x in air_filter]))
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
    write("item/oil_sump.json", model(SUMP_TEX, sump))
    write("item/cylinder.json", model(CYL_TEX, cyl))
    # The throttle lever is drawn by the block entity renderer in world, so the
    # item has to carry a static copy of it or the icon shows a carburetor with
    # a bare throttle shaft. It is placed at the lever's fully closed angle of
    # 0 degrees - the icon only has to be recognisable, not animated.
    px, py, pz = THROTTLE_PIVOT
    item_lever = [shift(x, px - 8, py - 8, pz - 8) for x in lever]
    write("item/carburetor.json",
          model({**CARB_TEX, **THROTTLE_TEX, "particle": "carburetor"},
                [shift(x, 0, 2.6, 3.4) for x in carb + item_lever],
                display=gui_scale(0.95)))
    # Centred in its slot: the in-world model deliberately sits high and to the
    # intake side of the Carburetor block, which looks lost as an icon.
    #
    # Turned to 20/215 rather than the standard 30/225 and scaled a little
    # harder. The filter is a squat drum whose only distinguishing feature is
    # the mesh band around its side; the shallower pitch shows more of that band
    # and less of the flat lid, which is what separates it at a glance from the
    # other round part in this mod.
    write("item/air_filter.json",
          model(FILTER_TEX, [shift(x, 0, -4.2, 8 - HORN_CZ) for x in air_filter],
                display=gui_scale(1.0, (20, 215, 0))))

    # Small and centred in its slot, so the icon fills the frame the way the
    # other bolt-on parts do. A steeper pitch than the default puts the board's
    # face - the part carrying the redstone and the tube - towards the camera
    # instead of showing it edge-on.
    write("item/redstone_control_module.json",
          model(MODULE_TEX, control_module_elements(),
                display=gui_scale(1.2, (40, 225, 0))))

    # Piston Assembly is piston *and* rod, so the item says so. The rod is
    # shortened to fit the icon; the in-world rod keeps its true length.
    item_rod = [
        el((6.0, 8.4, 5.6), (10.0, 11.8, 10.4), "rod"),
        el((6.8, 5.0, 6.4), (9.2, 9.0, 9.6), "rod"),
        el((6.8, 2.5, 6.0), (9.2, 5.5, 10.0), "rod"),
        el((6.5, 1.2, 5.9), (9.5, 3.0, 10.1), "rod"),
        el((6.5, 0.0, 5.9), (9.5, 1.4, 10.1), "steel"),
    ]
    # Turned to 15 degrees of pitch: at the standard 30 the icon is looked down
    # on, the crown fills the slot and the rod hides behind it, so a Piston
    # Assembly and a bare piston would be the same picture. From nearer the side
    # the rod and its little end are visible under the skirt, which is the
    # difference the name is about.
    write("item/piston_assembly.json",
          model({**PISTON_TEX, **ROD_TEX, "particle": "piston"},
                [shift(x, 0, 2.0, 0) for x in piston] + item_rod,
                display=gui_scale(0.9, (15, 215, 0))))

    write("item/oil_shale.json", {"parent": NS + "block/oil_shale"})

    # --- flat icons ---------------------------------------------------------
    # Everything that is a part or a material rather than a machine. See
    # sprite_item; the two buckets are here too, so that nothing under
    # models/ is hand-maintained any more.
    for name in ("spark_plug", "crushed_oil_shale", "petroleum_residue",
                 "incomplete_piston_assembly", "incomplete_carburetor",
                 "gasoline_bucket", "engine_oil_bucket", "crude_oil_bucket"):
        write(f"item/{name}.json", sprite_item(name))


if __name__ == "__main__":
    main()
