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
import importlib.util
import json
import math
import os
import pathlib
import re
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
# Two of them, because an engine has two kinds of bay and the interesting
# joints are different in each. The inline-1 is the whole machine; the inline-4
# bay is a middle section with a neighbour bolted to it, which is the only place
# the shared intake manifold, the cross-seam fins and the main bearing cap ever
# meet another block.
ASSEMBLIES = {
    "an inline-1": [
        ("oil_sump.json", (0, -16, 0)),
        ("crankshaft.json", (0, 0, 0)),
        ("crank_assembly_x.json", (0, 0, 0)),
        ("cylinder.json", (0, 16, 0)),
        ("spark_plug_x.json", (0, 16, 0)),
        ("carburetor.json", (0, 32, 0)),
        ("air_filter_x.json", (0, 32, 0)),
        ("flywheel_wheel_x.json", (16, 0, 0)),
    ],
    "a middle bay of an inline-4": [
        ("oil_sump.json", (0, -16, 0)),
        ("crankshaft_joined.json", (0, 0, 0)),
        ("crank_assembly_x.json", (0, 0, 0)),
        ("cylinder_manifold_both.json", (0, 16, 0)),
        ("spark_plug_x.json", (0, 16, 0)),
        ("carburetor.json", (0, 32, 0)),
        ("air_filter_x.json", (0, 32, 0)),
        # The section ahead of it, so the seam itself is checked: two decks, two
        # pan lips, two galleries, two fin stacks and two halves of a manifold
        # collar all meet in the plane at x = 16.
        ("crankshaft_joined.json", (16, 0, 0)),
        ("cylinder_manifold_both.json", (16, 16, 0)),
    ],
}


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
    hits = []
    for assembly in ASSEMBLIES.values():
        parts = [(name, placed(name, offset)) for (name, offset) in assembly]
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
             for name in ("cylinder.json", "cylinder_manifold_negative.json",
                          "cylinder_manifold_positive.json",
                          "cylinder_manifold_both.json", "spark_plug_x.json")
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
STACKED_ONLY_VERTICALLY = ["cylinder.json", "cylinder_manifold_negative.json",
                           "cylinder_manifold_positive.json",
                           "cylinder_manifold_both.json",
                           "spark_plug_x.json", "spark_plug_z.json",
                           "carburetor.json", "oil_sump.json"]

# THE ONE DELIBERATE EXCEPTION, and it is bounded rather than waved through.
#
# Milestone 15B's valve gear stands proud of the intake flank, because that is
# what an exposed overhead-valve engine looks like and because the horizontal
# lines it draws - cam, pushrod gallery, rocker shaft - are what make four
# cylinders read as one machine rather than four. It earns the exception by
# being the opposite of the floating spark plug the rule was written for: it is
# one assembly running the whole length of the engine, carried at every seam,
# rather than a fragment beside one block.
#
# What the bound buys is that it can never grow into the problem again:
#
#   * only towards NEGATIVE Z - the intake flank. The +Z flank stays clear for
#     the exhaust manifold a later milestone will put there, and nothing may
#     quietly start using it;
#   * never past VALVETRAIN_REACH, so the gear hugs the engine instead of
#     hanging off it. A block placed against the flank overlaps a couple of
#     units of pushrod, the way a Create cogwheel overlaps its neighbour;
#   * never past the block in X at all, so an engine's ends stay square and two
#     engines end to end do not interpenetrate.
#
# Must match VALVETRAIN_REACH in tools/generate_engine_models.py.
VALVETRAIN_REACH = 2.0


def check_sideways_reach():
    problems = []
    for name in STACKED_ONLY_VERTICALLY:
        for element in json.loads((ROOT / name).read_text())["elements"]:
            lo, hi = box(element)
            # X is absolute: nothing may leave the block along the crank axis.
            if lo[0] < -EPS or hi[0] > 16.0 + EPS:
                problems.append(
                    f"{name}: {lo}->{hi} reaches out of the block along the crank "
                    f"axis (x {lo[0]:.2f}..{hi[0]:.2f}) - it will collide with the "
                    "next section")
                continue
            if hi[2] > 16.0 + EPS:
                problems.append(
                    f"{name}: {lo}->{hi} reaches out of the block on the EXHAUST "
                    f"flank (z up to {hi[2]:.2f}) - that side is reserved for the "
                    "exhaust manifold and must stay clear")
                continue
            if lo[2] < -VALVETRAIN_REACH - EPS:
                problems.append(
                    f"{name}: {lo}->{hi} reaches {-lo[2]:.2f} out of the block on "
                    f"the intake flank, past the {VALVETRAIN_REACH} the valve gear "
                    "is allowed - it will hang in the air beside the engine")
    return problems


# The moving valve gear's own bound.
#
# The static rule above cannot see these: a block entity's parts are authored
# about the block centre and TRANSLATED onto their real axis at render time, so
# a model whose own numbers all sit inside the block still ends up wherever the
# renderer puts it. That is exactly how the camshaft came to sweep almost three
# units past the flank without anything noticing.
#
# So they are bounded here instead, by the arithmetic the renderer actually
# does: a part on an axis at (cy, cz) whose geometry reaches R from the centre it
# is authored about can, at some angle, reach cz - R. That is the number checked.
VALVETRAIN_MOVING_REACH = 4.0

# Every part a renderer turns about an axis out on the intake flank, and how far
# out that axis is. Matches EngineValvetrain in the Java and the constants in
# generate_engine_models.py.
#
# An engine running along Z has its own copy of each model with X and Z swapped,
# and it turns about Z rather than X - so the plane the part sweeps, and the axis
# the flank offset is measured on, swap with it.
MOVING_PARTS = {
    "camshaft_running": -0.9,
    "camshaft_running_drive": -0.9,
    "timing_gear": -0.9,
}


def check_moving_reach():
    problems = []
    for stem, offset in MOVING_PARTS.items():
        for suffix, plane in (("_x", (1, 2)), ("_z", (0, 1))):
            name = stem + suffix + ".json"
            path = ROOT / name
            if not path.exists():
                problems.append(f"{name}: is in MOVING_PARTS but does not exist")
                continue
            radius = 0.0
            for element in json.loads(path.read_text())["elements"]:
                lo, hi = box(element)
                for axis in plane:
                    radius = max(radius, abs(lo[axis] - 8.0), abs(hi[axis] - 8.0))
            reach = radius - offset
            if reach > VALVETRAIN_MOVING_REACH + EPS:
                problems.append(
                    f"{name}: turns to {reach:.2f} out of the block on the intake "
                    f"flank, past the {VALVETRAIN_MOVING_REACH} the moving valve gear "
                    f"is allowed (radius {radius:.2f} about an axis {-offset:.1f} out)")
    return problems


# THE CAM LOBES HAVE TO AGREE WITH THE VALVES THEY LIFT.
#
# The lobes are static geometry turned by the cam angle; the valve lift is a
# function the simulation owns. Nothing connects them but a pair of authored
# directions, so the day one of those is changed - or the valve windows move -
# the engine draws a lobe pointing nowhere near the follower it is supposed to be
# pushing, and every other check in this file still passes.
#
# The peak of each valve's window, in CAM degrees, from ValveTiming: the intake
# is open across [540, 720) and the exhaust across [360, 540), so they peak at
# cycle 630 and 450, which halve to 315 and 225.
LOBE_PEAK_CAM_DEGREES = {"intake": 315.0, "exhaust": 225.0}
LOBE_X_BAND = {"intake": (3.6, 6.4), "exhaust": (9.6, 12.4)}


def check_lobe_phase():
    problems = []
    for name in ("camshaft_running_x.json", "camshaft_running_drive_x.json"):
        elements = json.loads((ROOT / name).read_text())["elements"]
        for valve, peak in LOBE_PEAK_CAM_DEGREES.items():
            lo_x, hi_x = LOBE_X_BAND[valve]
            best, best_at = -1e9, None
            for degrees in range(0, 360):
                angle = math.radians(degrees)
                cos, sin = math.cos(angle), math.sin(angle)
                top = -1e9
                for element in elements:
                    lo, hi = box(element)
                    if lo[0] < lo_x - EPS or hi[0] > hi_x + EPS:
                        continue
                    for y in (lo[1], hi[1]):
                        for z in (lo[2], hi[2]):
                            top = max(top, (y - 8.0) * cos - (z - 8.0) * sin)
                if top > best:
                    best, best_at = top, degrees
            if best_at is None:
                problems.append(f"{name}: found no {valve} lobe between x {lo_x} and {hi_x}")
                continue
            error = abs((best_at - peak + 180.0) % 360.0 - 180.0)
            if error > 8.0:
                problems.append(
                    f"{name}: the {valve} lobe stands highest at cam {best_at} degrees, "
                    f"but its valve peaks at cam {peak:.0f} - the nose is {error:.0f} "
                    "degrees away from the follower it is meant to be lifting")
    return problems


# THE CAMSHAFT SWEEPS A CIRCLE, AND THE CRANKCASE HAS TO BE OUTSIDE IT.
#
# This is the check that would have caught the thing nobody saw: the lobes turn
# about an axis less than a unit outside the block, so their noses swing INTO
# the crankcase's own flank at every angle they point inboard. Nothing else in
# this file could see it - each model is fine on its own, and the pair only
# overlaps for part of a revolution, so no static test of coplanar faces or
# buried quads has anything to report.
#
# Must match CAM_CY/CAM_CZ in generate_engine_models.py and EngineValvetrain.
CAM_AXIS_Y, CAM_AXIS_Z = 4.5, -0.9
CAM_SWEEP_CLEARANCE = 0.05

# Only the ECCENTRIC parts are checked, and the base circle's radius is where
# eccentric starts. Everything at or inside it - the journals, the lobes' own
# base circles - is round about the axis, so it is inside its bearing at every
# angle and always will be, which is what a journal in a bearing IS. It is the
# noses that appear and disappear as the shaft turns, and only they can sweep
# through something that was not there a moment ago.
CAM_BASE_R = 1.3


def _distance_to_box(cy, cz, lo, hi):
    """Shortest distance from a point to a box, in the plane the cam turns in."""
    dy = max(lo[1] - cy, 0.0, cy - hi[1])
    dz = max(lo[2] - cz, 0.0, cz - hi[2])
    return math.hypot(dy, dz)


def check_cam_sweep():
    problems = []
    for cam_name, case_names in (
            ("camshaft_running_x.json", ("crankshaft.json", "crankshaft_joined.json")),
            ("camshaft_running_drive_x.json", ("crankshaft.json",))):
        cam = json.loads((ROOT / cam_name).read_text())["elements"]
        for case_name in case_names:
            case = json.loads((ROOT / case_name).read_text())["elements"]
            worst = None
            for turning in cam:
                t_lo, t_hi = box(turning)
                radius = max(abs(v - 8.0) for v in (t_lo[1], t_hi[1], t_lo[2], t_hi[2]))
                if radius <= CAM_BASE_R + EPS:
                    continue
                for static in case:
                    s_lo, s_hi = box(static)
                    if s_hi[0] <= t_lo[0] + EPS or s_lo[0] >= t_hi[0] - EPS:
                        continue
                    gap = _distance_to_box(CAM_AXIS_Y, CAM_AXIS_Z, s_lo, s_hi) - radius
                    if gap < CAM_SWEEP_CLEARANCE and (worst is None or gap < worst[0]):
                        worst = (gap, radius, s_lo, s_hi, t_lo[0], t_hi[0])
            if worst is not None:
                gap, radius, s_lo, s_hi, x0, x1 = worst
                problems.append(
                    f"{cam_name} vs {case_name}: a part of the camshaft turning at "
                    f"radius {radius:.2f} over x {x0:.1f}..{x1:.1f} passes "
                    f"{-gap:.2f} into {s_lo}->{s_hi} - the lobes sweep through the "
                    "casting")
    return problems


# THE CONSTANTS THAT LIVE IN THREE PLACES.
#
# The generator authors the geometry, EngineValvetrain.java tells the renderers
# where to put it, and preview_engine.py is where it gets looked at. The header
# of generate_engine_models.py says to change them together or not at all, which
# is advice; this makes it a build failure.
#
# The day any two disagree, a pushrod stops touching its lobe - and nothing else
# in this file can see it, because each file is internally consistent.
SHARED_CONSTANTS = ("CAM_CY", "CAM_CZ", "VALVE_LIFT", "ROCKER_PIVOT_Y", "ROCKER_PIVOT_Z")

# What each one is called in the Java, which uses its own naming.
JAVA_NAMES = {"CAM_CY": "CAM_Y", "CAM_CZ": "CAM_Z", "VALVE_LIFT": "VALVE_LIFT",
              "ROCKER_PIVOT_Y": "ROCKER_PIVOT_Y", "ROCKER_PIVOT_Z": "ROCKER_PIVOT_Z"}

TOOLS = pathlib.Path(__file__).resolve().parent
JAVA = ASSETS.parents[2] / "java/dev/engineeredcombustion/client/EngineValvetrain.java"


def _read_constants(text, pattern, flags=0):
    found = {}
    for name in SHARED_CONSTANTS:
        match = re.search(pattern.format(name=re.escape(name)), text, flags)
        if match:
            found[name] = float(match.group(1))
    return found


def check_shared_constants():
    problems = []
    generator = _read_constants((TOOLS / "generate_engine_models.py").read_text(),
                                r"^{name}\s*=\s*(-?[0-9.]+)", flags=re.M)
    # Imported rather than pattern-matched: the previewer declares several of
    # these as tuples on one line, and a regex that could read those could read
    # almost anything.
    spec = importlib.util.spec_from_file_location("_preview", TOOLS / "preview_engine.py")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    previewer = {name: float(getattr(module, name)) for name in SHARED_CONSTANTS
                 if isinstance(getattr(module, name, None), (int, float))}
    java_text = JAVA.read_text() if JAVA.exists() else ""
    for name in SHARED_CONSTANTS:
        if name not in generator:
            problems.append(f"{name}: not found in generate_engine_models.py")
            continue
        expected = generator[name]
        if name in previewer and abs(previewer[name] - expected) > 1e-6:
            problems.append(
                f"{name}: the generator says {expected} and preview_engine.py says "
                f"{previewer[name]}")
        java_name = JAVA_NAMES[name]
        match = re.search(rf"\b{java_name}\s*=\s*(-?[0-9.]+)F", java_text)
        if not match:
            problems.append(f"{name}: no {java_name} found in EngineValvetrain.java")
        elif abs(float(match.group(1)) - expected) > 1e-6:
            problems.append(
                f"{name}: the generator says {expected} and EngineValvetrain.java's "
                f"{java_name} says {match.group(1)}")
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

    valvetrain = (check_moving_reach() + check_lobe_phase() + check_cam_sweep()
                  + check_shared_constants())
    print(f"\n{'ok ' if not valvetrain else 'BAD'} the moving valve gear"
          f" - reach within {VALVETRAIN_MOVING_REACH} units, lobes in phase with"
          " the valves they lift, nothing swept through, one set of constants")
    for problem in valvetrain:
        print("      " + problem)
    bad += len(valvetrain)

    seams = check_assembly()
    print(f"\n{'ok ' if not seams else 'BAD'} assembled engine - "
          + ", ".join(f"{name} ({len(parts)} blocks)"
                      for name, parts in ASSEMBLIES.items()))
    for (name_a, name_b, i, j, face, area, joined) in seams:
        print(f"      coplanar {face:5s} area {area:6.2f}"
              f"  {name_a} #{i}  vs  {name_b} #{j}")
    bad += len(seams)

    print(f"\n{bad} problem(s)")
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main())
