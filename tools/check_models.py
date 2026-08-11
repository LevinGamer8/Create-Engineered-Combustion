#!/usr/bin/env python3
"""Static checks on the generated block models.

Two classes of problem show up in a hand-authored cuboid model and neither one
is visible in a JSON diff:

*coplanar overlap* - two elements whose same-facing quads sit in exactly the
same plane and overlap in area. The depth buffer has no way to order them, so
the pair shimmers as the camera moves. On the parts drawn by a block entity
renderer - crankshaft, connecting rod, flywheel - the geometry itself moves, so
the shimmer turns into a flicker.

*buried quads* - a face completely covered by other elements of the same model.
It can never be seen, but it is still stitched into the chunk mesh and still
drawn.

Run this after `generate_engine_models.py`; it exits non-zero if it finds
anything, which is what makes the two invariants enforceable rather than
aspirational.
"""
import json
import math
import os
import pathlib
import sys

ASSETS = pathlib.Path(__file__).resolve().parents[1] \
    / "src/main/resources/assets/engineered_combustion"
ROOT = ASSETS / "models/block"
NS = "engineered_combustion:"

AXES = {"x": 0, "y": 1, "z": 2}
FACE_AXIS = {"east": ("x", 1), "west": ("x", 0), "up": ("y", 1),
             "down": ("y", 0), "south": ("z", 1), "north": ("z", 0)}
EPS = 1e-6


def box(element):
    return [float(c) for c in element["from"]], [float(c) for c in element["to"]]


def overlap_area(a, b, axis):
    """Area of the overlap of two boxes projected along `axis`."""
    others = [i for i in range(3) if i != axis]
    area = 1.0
    for i in others:
        lo = max(a[0][i], b[0][i])
        hi = min(a[1][i], b[1][i])
        if hi - lo <= EPS:
            return 0.0
        area *= hi - lo
    return area


def coplanar_overlaps(elements):
    """Pairs of same-facing quads sharing a plane and overlapping in area."""
    hits = []
    boxes = [box(e) for e in elements]
    for i in range(len(elements)):
        for j in range(i + 1, len(elements)):
            a, b = boxes[i], boxes[j]
            for face, (axis_name, side) in FACE_AXIS.items():
                axis = AXES[axis_name]
                if abs(a[side][axis] - b[side][axis]) > EPS:
                    continue
                if face not in elements[i]["faces"] or face not in elements[j]["faces"]:
                    continue
                area = overlap_area(a, b, axis)
                if area > EPS:
                    hits.append((i, j, face, round(area, 3)))
    return hits


def buried_faces(elements):
    """Faces whose whole area is covered by other elements standing on them.

    Only exact, axis-aligned coverage counts: a face is buried when a single
    other element both touches its plane from the far side and spans the face
    in the two remaining axes. That is deliberately conservative - it never
    reports a face that is actually visible from some angle.
    """
    hits = []
    boxes = [box(e) for e in elements]
    for i, e in enumerate(elements):
        a = boxes[i]
        for face, (axis_name, side) in FACE_AXIS.items():
            if face not in e["faces"]:
                continue
            axis = AXES[axis_name]
            plane = a[side][axis]
            others = [k for k in range(3) if k != axis]
            for j, b in enumerate(boxes):
                if i == j:
                    continue
                # the neighbour has to reach the plane from the outward side
                if side == 1:
                    if b[0][axis] > plane + EPS or b[1][axis] < plane + EPS:
                        continue
                else:
                    if b[1][axis] < plane - EPS or b[0][axis] > plane - EPS:
                        continue
                if all(b[0][k] <= a[0][k] + EPS and b[1][k] >= a[1][k] - EPS
                       for k in others):
                    hits.append((i, face, j))
                    break
    return hits


# ---------------------------------------------------------------------------
# the assembled engine
# ---------------------------------------------------------------------------
# Half of this mod's geometry deliberately crosses block boundaries so the
# stack reads as one machine, which means a contested plane does not have to
# live inside a single model to flicker. These are the parts whose pose
# relative to each other never changes, in the Crankshaft block's coordinates.
#
# The piston and connecting rod are left out on purpose: they move against the
# cylinder every tick, so any plane they share with it is momentary rather than
# a permanent shimmer, and the clearances that keep them apart are asserted by
# the geometry in the model generator instead.
ASSEMBLY = [
    ("oil_sump.json", (0, -16, 0)),
    ("crankshaft.json", (0, 0, 0)),
    ("crank_assembly_x.json", (0, 0, 0)),
    ("cylinder.json", (0, 16, 0)),
    ("spark_plug.json", (0, 16, 0)),
    ("carburetor.json", (0, 32, 0)),
    ("air_filter.json", (0, 32, 0)),
    ("flywheel_wheel_x.json", (16, 0, 0)),
]


def placed(name, offset):
    model = json.loads((ROOT / name).read_text())
    out = []
    for e in model.get("elements", []):
        moved = dict(e)
        moved["from"] = [c + offset[i] for i, c in enumerate(e["from"])]
        moved["to"] = [c + offset[i] for i, c in enumerate(e["to"])]
        out.append(moved)
    return out


def check_assembly():
    """Coplanar overlaps between two different blocks of the built engine."""
    parts = [(name, placed(name, offset)) for (name, offset) in ASSEMBLY]
    hits = []
    for a in range(len(parts)):
        for b in range(a + 1, len(parts)):
            name_a, elements_a = parts[a]
            name_b, elements_b = parts[b]
            joined = elements_a + elements_b
            for (i, j, face, area) in coplanar_overlaps(joined):
                # only pairs that straddle the two models are new information
                if i < len(elements_a) <= j:
                    hits.append((name_a, name_b, i, j - len(elements_a),
                                 face, area, joined))
    return hits


# ---------------------------------------------------------------------------
# Combustion chamber clearances. See check_chamber.
#
# These four have to match CrankMath and the model generator; the piston's own
# travel is re-derived from them rather than written down.
BORE_MIN, BORE_MAX = 3.4, 12.6      # cylinder bore footprint, x and z
CHAMBER_ROOF = 14.0                 # underside of the head
CRANK_AXIS_HEIGHT, CRANK_RADIUS, ROD_LENGTH = 8.0, 3.0, 14.5   # = CrankMath
WRIST_PIN_MODEL_HEIGHT = 8.0


def wrist_pin_height(degrees):
    """CrankMath.wristPinHeight, in the Cylinder block's own space."""
    theta = math.radians(degrees)
    along = math.sqrt(ROD_LENGTH ** 2 - (CRANK_RADIUS * math.sin(theta)) ** 2)
    return CRANK_AXIS_HEIGHT - CRANK_RADIUS * math.cos(theta) + along - 16.0


def piston_boxes(degrees):
    """The piston's own elements, lifted to where this crank angle puts them."""
    lift = wrist_pin_height(degrees) - WRIST_PIN_MODEL_HEIGHT
    out = []
    for element in json.loads((ROOT / "piston_head.json").read_text())["elements"]:
        lo, hi = box(element)
        out.append(([lo[0], lo[1] + lift, lo[2]], [hi[0], hi[1] + lift, hi[2]]))
    return out


def intersects(a, b):
    return all(a[1][i] > b[0][i] + EPS and b[1][i] > a[0][i] + EPS for i in range(3))


def check_chamber():
    """Nothing on the Cylinder may stand in the volume the piston sweeps.

    The clearance volume of this engine is half a unit tall, so "is the spark
    plug clear of the piston" is not something to eyeball in a render - a tenth
    of a unit either way is the difference between a plug and a bent plug. This
    swings the real piston model through a whole revolution using CrankMath's
    own relation and intersects it with every fixed element, so moving the crank
    throw, the rod, the piston or the head fails here rather than in a world.

    The combustion flash is checked the other way round: it is *meant* to be
    inside the bore, where the piston will happily cover part of it, and only
    has to stay under the head and inside the walls.
    """
    problems = []
    tdc_crown = max(hi[1] for _, hi in piston_boxes(180.0))

    # The spark plug is checked alongside the cylinder even though it is a
    # separate model now. It is still bolted into the same head, its electrode
    # and strap are still the two parts of this engine closest to the piston at
    # top dead centre, and splitting the model must not be what quietly stops
    # that clearance from being enforced.
    fixed = [(name, element)
             for name in ("cylinder.json", "spark_plug.json")
             for element in json.loads((ROOT / name).read_text())["elements"]]
    for degrees in range(0, 360, 5):
        for piston in piston_boxes(float(degrees)):
            for name, element in fixed:
                lo, hi = box(element)
                if not intersects((lo, hi), piston):
                    continue
                problems.append(
                    f"{name}: {lo}->{hi} is inside the piston at crank "
                    f"angle {degrees} deg (crown reaches y {tdc_crown:.2f} at TDC)")
    # One report per offending element, however many angles hit it.
    problems = sorted(set(problems))

    for element in json.loads((ROOT / "combustion_flash.json").read_text())["elements"]:
        lo, hi = box(element)
        if hi[1] > CHAMBER_ROOF + EPS:
            problems.append(f"combustion_flash.json: {lo}->{hi} pokes through the head")
        if lo[0] < BORE_MIN - EPS or hi[0] > BORE_MAX + EPS \
                or lo[2] < BORE_MIN - EPS or hi[2] > BORE_MAX + EPS:
            problems.append(f"combustion_flash.json: {lo}->{hi} reaches outside the bore")

    problems += check_sideways_reach()
    return problems, tdc_crown


# Blocks whose geometry may only leave their own cube *vertically*, and why.
# The engine is a stack, so a part reaching up or down lands inside the next
# block of the same machine and reads as one assembly. A part reaching sideways
# lands in whatever the player happened to build next to it - usually nothing -
# and hangs in the air beside the engine, which is precisely how the spark plug
# came to look like it was floating outside the cylinder.
#
# The Crankshaft is exempt on purpose: its main journals deliberately reach a
# unit into the Flywheel and into whatever Shaft is bolted to the far end, which
# is what makes the output side read as one continuous shaft.
STACKED_ONLY_VERTICALLY = ["cylinder.json", "spark_plug.json",
                           "carburetor.json", "oil_sump.json"]


def check_sideways_reach():
    problems = []
    for name in STACKED_ONLY_VERTICALLY:
        for element in json.loads((ROOT / name).read_text())["elements"]:
            lo, hi = box(element)
            for axis, label in ((0, "x"), (2, "z")):
                if lo[axis] < -EPS or hi[axis] > 16.0 + EPS:
                    problems.append(
                        f"{name}: {lo}->{hi} reaches out of the block sideways "
                        f"({label} {lo[axis]:.2f}..{hi[axis]:.2f}) - it will hang "
                        "in the air beside the engine")
                    break
    return problems


def check_references():
    """Every reference an asset makes has to land on something that exists.

    All three of these fail the same way in game - a black and magenta model,
    or a silently missing one - and all three are a one-character typo away at
    any time, so they are worth asserting rather than discovering.
    """
    problems = []
    models = set()
    for path in sorted((ASSETS / "models").rglob("*.json")):
        models.add(str(path.relative_to(ASSETS / "models")).replace(".json", ""))

    for path in sorted((ASSETS / "models").rglob("*.json")):
        name = path.relative_to(ASSETS)
        model = json.loads(path.read_text())
        textures = model.get("textures", {})
        for key, ref in textures.items():
            if not ref.startswith(NS):
                continue
            sprite = ASSETS / "textures" / (ref[len(NS):] + ".png")
            if not sprite.exists():
                problems.append(f"{name}: texture '{key}' -> missing {ref}")
        for i, element in enumerate(model.get("elements", [])):
            for face, data in element["faces"].items():
                ref = data["texture"].lstrip("#")
                if ref not in textures:
                    problems.append(f"{name}: #{i} {face} uses undeclared #{ref}")
                if min(data["uv"]) < 0 or max(data["uv"]) > 16:
                    problems.append(f"{name}: #{i} {face} uv {data['uv']} "
                                    "runs off the sprite")

    for path in sorted((ASSETS / "blockstates").glob("*.json")):
        name = path.relative_to(ASSETS)
        for variant in json.loads(path.read_text()).get("variants", {}).values():
            for entry in (variant if isinstance(variant, list) else [variant]):
                ref = entry["model"]
                if ref.startswith(NS) and ref[len(NS):] not in models:
                    problems.append(f"{name}: variant -> missing model {ref}")
    return problems


def main():
    bad = 0
    for path in sorted(ROOT.glob("*.json")):
        model = json.loads(path.read_text())
        elements = model.get("elements")
        if not elements:
            continue
        overlaps = coplanar_overlaps(elements)
        buried = buried_faces(elements)
        quads = sum(len(e["faces"]) for e in elements)
        status = "ok " if not overlaps and not buried else "BAD"
        print(f"{status} {path.name:28s} {len(elements):3d} elements {quads:4d} quads")
        for (i, j, face, area) in overlaps:
            print(f"      coplanar {face:5s} area {area:6.2f}"
                  f"  #{i} {elements[i]['from']}->{elements[i]['to']}"
                  f"  #{j} {elements[j]['from']}->{elements[j]['to']}")
        for (i, face, j) in buried:
            print(f"      buried   {face:5s} of #{i}"
                  f" {elements[i]['from']}->{elements[i]['to']} behind #{j}")
        bad += len(overlaps) + len(buried)

    references = check_references()
    print(f"\n{'ok ' if not references else 'BAD'} asset references")
    for problem in references:
        print("      " + problem)
    bad += len(references)

    chamber, tdc = check_chamber()
    print(f"\n{'ok ' if not chamber else 'BAD'} combustion chamber"
          f" - piston crown at TDC y {tdc:.2f}, head at y {CHAMBER_ROOF:.2f}")
    for problem in chamber:
        print("      " + problem)
    bad += len(chamber)

    seams = check_assembly()
    print(f"\n{'ok ' if not seams else 'BAD'} assembled engine"
          f" - {len(ASSEMBLY)} blocks")
    for (name_a, name_b, i, j, face, area, joined) in seams:
        print(f"      coplanar {face:5s} area {area:6.2f}"
              f"  {name_a} #{i}  vs  {name_b} #{j}")
    bad += len(seams)

    print(f"\n{bad} problem(s)")
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main())
