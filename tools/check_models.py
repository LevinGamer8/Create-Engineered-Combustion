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
    # The flywheel end of an inline-4, which is where the exhaust stack stands:
    # the log's last seam, the stack, and the flywheel the stack stands beside.
    "the outlet end of an inline-4": [
        ("crankshaft_joined.json", (0, 0, 0)),
        ("crank_assembly_x.json", (0, 0, 0)),
        ("cylinder_manifold_both.json", (0, 16, 0)),
        ("crankshaft_joined.json", (16, 0, 0)),
        ("cylinder_manifold_negative.json", (16, 16, 0)),
        ("spark_plug_x.json", (16, 16, 0)),
        ("flywheel_wheel_x.json", (32, 0, 0)),
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
#   * only towards NEGATIVE Z - the intake flank. The +Z flank belongs to the
#     exhaust, which has a bound of its own below;
#   * never past VALVETRAIN_REACH, so the gear hugs the engine instead of
#     hanging off it. A block placed against the flank overlaps a couple of
#     units of pushrod, the way a Create cogwheel overlaps its neighbour;
#   * never past the block in X at all, so an engine's ends stay square and two
#     engines end to end do not interpenetrate.
#
# Must match VALVETRAIN_REACH in tools/generate_engine_models.py.
VALVETRAIN_REACH = 2.0

# THE EXHAUST, on the other flank, and the same kind of bound. The header, log
# and outlet stack are one run from the first port to the last, carried on every
# head, so they earn the exception the valve gear earned - and only the Cylinder
# may take it. A Carburetor, a sump or a plug reaching out over the exhaust side
# is still exactly the floating part this rule exists to stop.
#
# Must match EXHAUST_REACH in tools/generate_engine_models.py.
EXHAUST_REACH = 2.75
EXHAUST_CARRIERS = {"cylinder.json", "cylinder_manifold_negative.json",
                    "cylinder_manifold_positive.json", "cylinder_manifold_both.json"}


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
            reach = EXHAUST_REACH if name in EXHAUST_CARRIERS else 0.0
            if hi[2] > 16.0 + reach + EPS:
                problems.append(
                    f"{name}: {lo}->{hi} reaches {hi[2] - 16.0:.2f} out of the block on "
                    f"the EXHAUST flank, past the {reach} allowed there - only the "
                    "Cylinder's exhaust may stand proud of that side, and only that far")
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


# THE EXHAUST PUFF HAS TO COME OUT OF THE PIPE.
#
# The stack's mouth is authored in the generator and the puff is emitted from a
# point in EngineFlowEffects.java, and nothing else connects them. Moving the
# stack without moving the point puts smoke in the air beside an engine whose
# outlet is somewhere else - so the Java's point is checked against the
# generator's EXHAUST_OUTLET, and against the written models: there has to be a
# soot-floored recess directly under it, which is what the stack's mouth is.
FLOW_JAVA = ASSETS.parents[2] / "java/dev/engineeredcombustion/content/engine/EngineFlowEffects.java"


def check_exhaust_outlet():
    problems = []
    spec = importlib.util.spec_from_file_location("_generator", TOOLS / "generate_engine_models.py")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    expected = module.EXHAUST_OUTLET
    java_text = FLOW_JAVA.read_text(encoding="utf-8") if FLOW_JAVA.exists() else ""
    for axis, value in zip("XYZ", expected):
        match = re.search(rf"\bOUTLET_{axis}\s*=\s*(-?[0-9.]+)D", java_text)
        if not match:
            problems.append(f"EXHAUST_OUTLET: no OUTLET_{axis} found in EngineFlowEffects.java")
        elif abs(float(match.group(1)) - value) > 1e-6:
            problems.append(
                f"EXHAUST_OUTLET: the generator puts the stack's mouth at {axis} {value} and "
                f"EngineFlowEffects.java's OUTLET_{axis} says {match.group(1)}")
    match = re.search(r"\bEXHAUST_PORT_X\s*=\s*(-?[0-9.]+)D", java_text)
    if not match or abs(float(match.group(1)) - module.EXHAUST_HEADER_X) > 1e-6:
        problems.append(f"EXHAUST_HEADER_X: the generator puts the header at x {module.EXHAUST_HEADER_X}, "
                        f"EngineFlowEffects.java's EXHAUST_PORT_X says "
                        f"{match.group(1) if match else 'nothing'}")
    x, y, z = expected
    # The two variants an engine's LAST cylinder can be: an inline-1's, and the
    # positive end of a longer run. Those are the only ones that carry the stack.
    for name in ("cylinder.json", "cylinder_manifold_negative.json"):
        model = json.loads((ROOT / name).read_text())
        soot = model["textures"].get("soot")
        floors = [e for e in model["elements"]
                  if "up" in e["faces"]
                  and model["textures"].get(e["faces"]["up"]["texture"].lstrip("#")) == soot
                  and e["from"][0] < x < e["to"][0] and e["from"][2] < z < e["to"][2]
                  and e["to"][1] < y]
        if not floors:
            problems.append(f"{name}: no sooted stack mouth under the exhaust outlet at {expected}")
    return problems


# A Z ENGINE IS AN X ENGINE TURNED A QUARTER, NOT MIRRORED.
#
# The baked blocks of a Z engine are turned by the blockstate's "y": 90, which
# carries (x, z) to (16 - z, x). The parts a block entity renderer draws are not
# turned by anything, so each has its own _z model - and that model has to be the
# SAME quarter turn of the _x one, or it lands on the other flank from the
# casting it belongs in. It was a mirror for the whole valve gear, and nothing
# noticed, because every _x model was fine and nobody had built along Z.
#
# Checked as geometry, not as JSON: every _z model's boxes must be its _x
# model's boxes turned, to within the separation pass's nudge. A symmetric part
# like the crank passes whichever way it was built, which is correct.
TURN_TOLERANCE = 0.05


def _turned(lo, hi):
    return ([16.0 - hi[2], lo[1], lo[0]], [16.0 - lo[2], hi[1], hi[0]])


def check_z_is_turned():
    problems = []
    for x_path in sorted(ROOT.glob("*_x.json")):
        z_path = x_path.with_name(x_path.name[:-len("_x.json")] + "_z.json")
        if not z_path.exists():
            problems.append(f"{x_path.name}: has no _z partner")
            continue
        want = [_turned(*box(e)) for e in json.loads(x_path.read_text())["elements"]]
        have = [box(e) for e in json.loads(z_path.read_text())["elements"]]
        unmatched = list(have)
        for lo, hi in want:
            match = next((h for h in unmatched
                          if all(abs(h[0][i] - lo[i]) <= TURN_TOLERANCE
                                 and abs(h[1][i] - hi[i]) <= TURN_TOLERANCE for i in range(3))), None)
            if match is None:
                problems.append(f"{z_path.name}: has nothing where {x_path.name}'s {lo}->{hi} turns to - "
                                "the Z model is not the X model turned a quarter (a mirror puts it on "
                                "the other flank)")
                break
            unmatched.remove(match)
    return problems


# ---------------------------------------------------------------------------
# The ports and the pan, as topology
# ---------------------------------------------------------------------------
# What the geometry pass promised is that a player can FOLLOW the air, the gas
# and the oil by eye. Those are claims about connectivity, so they are checked as
# connectivity - is this point open, is that one solid, is there a lining - and
# against the generator's own named cavities and constants, never against
# coordinates written out here. An artistic edit that moves a port moves the
# probes with it; an edit that blocks a passage, leaves a runner ending on a
# wall or gives one bay a pan the others lack fails.
def _generator():
    spec = importlib.util.spec_from_file_location("_generator_ports", TOOLS / "generate_engine_models.py")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def _inside(point, elements, tex=None, textures=None):
    for element in elements:
        lo, hi = box(element)
        if all(lo[i] + EPS < point[i] < hi[i] - EPS for i in range(3)):
            if tex is None:
                return True
            refs = {textures.get(f["texture"].lstrip("#")) for f in element["faces"].values()}
            if tex in refs:
                return True
    return False


def _open_along(points, elements):
    """The first sample on a polyline that is inside a solid, or None."""
    for a, b in zip(points, points[1:]):
        steps = max(1, int(max(abs(b[i] - a[i]) for i in range(3)) / 0.2))
        for k in range(steps + 1):
            t = k / steps
            q = tuple(a[i] + (b[i] - a[i]) * t for i in range(3))
            if _inside(q, elements):
                return q
    return None


def _centre(cavity):
    lo, hi = cavity
    return tuple((lo[i] + hi[i]) / 2.0 for i in range(3))


def check_ports():
    g = _generator()
    problems = []
    ix, ex, cz = g.VALVE_X[0], g.VALVE_X[1], g.VALVE_CZ
    mid = (g.PORT_FLOOR + g.PORT_ROOF) / 2.0
    seat_y = (g.CHAMBER_ROOF + g.PORT_FLOOR) / 2.0
    window = _centre(g.INTAKE_WINDOW)
    plenum = _centre(g.INTAKE_PLENUM)
    trough = g.EXHAUST_TROUGH
    mouth = g.EXHAUST_MOUTH
    intake_path = [(window[0], mid, window[2]), (plenum[0], mid, plenum[2]),
                   (ix, mid, plenum[2]), (ix, mid, cz), (ix, seat_y, cz)]
    exhaust_path = [(ex, seat_y, cz), (ex, mid, cz),
                    (g.EXHAUST_HEADER_X, mid, trough[0][2] + 0.3),
                    (g.EXHAUST_HEADER_X, mid, trough[1][2] - 0.2)]
    for name in ("cylinder.json", "cylinder_manifold_negative.json",
                 "cylinder_manifold_positive.json", "cylinder_manifold_both.json"):
        model = json.loads((ROOT / name).read_text())
        elements, textures = model["elements"], model["textures"]
        port, soot = textures.get("port"), textures.get("soot")
        blocked = _open_along(intake_path, elements)
        if blocked:
            problems.append(f"{name}: the intake passage from the window to the intake valve's seat "
                            f"is blocked at {tuple(round(v, 2) for v in blocked)}")
        blocked = _open_along(exhaust_path, elements)
        if blocked:
            problems.append(f"{name}: the exhaust passage from the exhaust valve's seat to the port "
                            f"is blocked at {tuple(round(v, 2) for v in blocked)}")
        # Each passage is a cavity, not a hole in nothing: there is casting under
        # its floor beside the seat, over its roof at the valve, and a dark lining.
        for label, x in (("intake", ix), ("exhaust", ex)):
            if not _inside((x, seat_y, cz + g.SEAT_HALF + 0.2), elements):
                problems.append(f"{name}: no floor round the {label} valve's seat")
            if not _inside((x + 0.3, g.PORT_ROOF + 0.1, cz + 0.3), elements):
                problems.append(f"{name}: no roof over the {label} valve's port")
        if not _inside((plenum[0], g.PORT_FLOOR + 0.05, plenum[2]), elements, port, textures):
            problems.append(f"{name}: the intake plenum has no lining - it would read as a hole in nothing")
        if not _inside((g.EXHAUST_HEADER_X, g.PORT_FLOOR + 0.05, (trough[0][2] + trough[1][2]) / 2.0),
                       elements, soot, textures):
            problems.append(f"{name}: the exhaust trough has no soot lining")
        # The header ends IN the port mouth, not against the face beside it.
        nose = [e for e in elements
                if textures.get(next(iter(e["faces"].values()))["texture"].lstrip("#")) == textures.get("exhaust")
                and all(mouth[0][i] - EPS <= box(e)[0][i] and box(e)[1][i] <= mouth[1][i] + 0.2
                        for i in (0, 1))
                and box(e)[0][2] < mouth[1][2] - 0.5]
        if not nose:
            problems.append(f"{name}: no exhaust header reaches into the port mouth")
    # Where a runner feeds the head, it lands on the inlet: the casting stops at
    # the inlet's recess and the runner stands on it.
    inlet = g.INTAKE_INLET
    inlet_top = ((inlet[0][0] + inlet[1][0]) / 2.0, inlet[1][1] + 0.3, (inlet[0][2] + inlet[1][2]) / 2.0)
    for name in ("cylinder_manifold_negative.json", "cylinder_manifold_positive.json",
                 "cylinder_manifold_both.json"):
        model = json.loads((ROOT / name).read_text())
        if not _inside(inlet_top, model["elements"], model["textures"].get("intake"), model["textures"]):
            problems.append(f"{name}: the intake runner does not land on the head's inlet")
    return problems


def check_oil_pan():
    g = _generator()
    problems = []
    floor = g.PAN_BOTTOM
    bays = [("crankshaft.json", 0)] + [("crankshaft_joined.json", 16 * i) for i in range(1, 4)]
    pan = []
    for name, dx in bays:
        elements = [e for e in placed(name, (dx, 0, 0)) if box(e)[1][1] <= 0.0 + EPS]
        if not elements:
            problems.append(f"{name}: carries no length of the oil pan")
            continue
        bottom = min(box(e)[0][1] for e in elements)
        if abs(bottom - floor) > EPS:
            problems.append(f"{name}: its pan bottoms out at y {bottom}, not at PAN_BOTTOM {floor} - "
                            "every bay's pan must be the same depth")
        pan += elements
    # One pan, four bays long: no gap anywhere under an inline-4.
    for y in (floor / 2.0, floor + 0.3):
        x = 0.6
        while x < 63.4:
            if not _inside((x, y, 8.0), pan):
                problems.append(f"the oil pan of an inline-4 has a gap at x {x:.1f}, y {y:.1f}")
                break
            x += 0.2
    # And exactly one deep bowl under it: the Oil Sump, deeper than the pan, its
    # lip in the pan's floor - so one tank reads as part of the one pan.
    sump = placed("oil_sump.json", (0, -16, 0))
    deepest = min(box(e)[0][1] for e in sump)
    if deepest > floor - 4.0:
        problems.append(f"oil_sump.json: reaches only y {deepest}, which is not a deep bowl under a pan "
                        f"whose floor is at {floor}")
    if not any(box(e)[0][1] < floor < box(e)[1][1] for e in sump):
        problems.append("oil_sump.json: nothing crosses the pan's floor - the bowl would hang under it "
                        "with a gap instead of being bolted to it")
    if any(box(e)[0][1] < floor - EPS for e in pan):
        problems.append("the crankcase's pan reaches below PAN_BOTTOM - only the Oil Sump's bowl may")
    return problems


def check_valves_clear_piston():
    """A valve head opening into the chamber must never meet the piston.

    The valve heads are new: the disc that sits in each seat drops into the
    chamber as its valve opens, and the chamber is half a unit tall at top dead
    centre. So both valves are swung through a whole cycle with the lift the
    simulation gives them and the piston CrankMath puts under them.
    """
    g = _generator()
    problems = []
    valves = (("intake_valve_x.json", g.VALVE_X[0], 540.0), ("exhaust_valve_x.json", g.VALVE_X[1], 360.0))
    for name, x, opens in valves:
        elements = [box(e) for e in json.loads((ROOT / name).read_text())["elements"]]
        for cycle in range(0, 720, 4):
            since = (cycle - opens) % 720.0
            lift = 0.0 if since >= 180.0 else (1.0 - math.cos(2.0 * math.pi * since / 180.0)) / 2.0
            drop = lift * g.VALVE_LIFT
            moved = [([lo[0] + x - 8.0, lo[1] - drop, lo[2]], [hi[0] + x - 8.0, hi[1] - drop, hi[2]])
                     for lo, hi in elements]
            piston = piston_boxes(float(cycle % 360))
            if any(intersects(v, q) for v in moved for q in piston):
                problems.append(f"{name}: meets the piston at cycle {cycle} (lift {drop:.2f})")
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
        models.add(path.relative_to(ASSETS / "models").as_posix().replace(".json", ""))

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
    chamber += check_valves_clear_piston()
    print(f"\n{'ok ' if not chamber else 'BAD'} combustion chamber"
          f" - piston crown at TDC y {tdc:.2f}, head at y {CHAMBER_ROOF:.2f}")
    for problem in chamber:
        print("      " + problem)
    bad += len(chamber)

    valvetrain = (check_moving_reach() + check_lobe_phase() + check_cam_sweep()
                  + check_shared_constants() + check_exhaust_outlet()
                  + check_z_is_turned())
    print(f"\n{'ok ' if not valvetrain else 'BAD'} the moving valve gear"
          f" - reach within {VALVETRAIN_MOVING_REACH} units, lobes in phase with"
          " the valves they lift, nothing swept through, one set of constants,"
          " the exhaust puff on the stack's mouth, and every Z part turned rather"
          " than mirrored")
    for problem in valvetrain:
        print("      " + problem)
    bad += len(valvetrain)

    flow = check_ports() + check_oil_pan()
    print(f"\n{'ok ' if not flow else 'BAD'} ports and pan - every intake and exhaust passage open"
          " from its opening to its valve and lined, runners and headers ending in their ports,"
          " one pan the same depth under every bay with no gap, one deep bowl under it")
    for problem in flow:
        print("      " + problem)
    bad += len(flow)

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
