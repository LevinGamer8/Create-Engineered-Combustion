#!/usr/bin/env python3
"""Offline renderer for the assembled engine.

Loads the real model JSON + the real textures, applies the same transforms the
block entity renderers apply, and rasterises the result. This is how the
redesign gets looked at from several angles without a Minecraft client.
"""
import json
import math
import os
import pathlib
import struct
import sys
import zlib

ASSETS = str(pathlib.Path(__file__).resolve().parents[1]
              / "src/main/resources/assets/engineered_combustion")
CRANK_AXIS_Y, CRANK_R, ROD_L = 8.0, 3.0, 14.5
# Must match the same names in tools/generate_engine_models.py and, for the
# swing, CamshaftTiming.ROCKER_MAX_SWING_DEGREES in the Java.
CAM_CY, CAM_CZ = 4.5, -0.9
TIMING_DRIVE_R, TIMING_CAM_R = 1.5, 3.0
TIMING_DRIVE_CY, TIMING_DRIVE_CZ = CAM_CY + TIMING_DRIVE_R + TIMING_CAM_R, CAM_CZ
VALVE_X = (5.0, 11.0)
ROCKER_PIVOT_Y, ROCKER_PIVOT_Z = 19.9, 0.2
ROCKER_SWING = 10.0


# --------------------------------------------------------------------------- png
def read_png(path):
    d = open(path, "rb").read()
    i, w, h, idat = 8, None, None, b""
    while i < len(d):
        ln = struct.unpack(">I", d[i:i + 4])[0]
        tag, data = d[i + 4:i + 8], d[i + 8:i + 8 + ln]
        if tag == b"IHDR":
            w, h = struct.unpack(">II", data[:8])
        elif tag == b"IDAT":
            idat += data
        i += 12 + ln
    raw = zlib.decompress(idat)
    stride, prev, o, px = w * 4, bytearray(w * 4), 0, []
    for _ in range(h):
        f = raw[o]; o += 1
        line = bytearray(raw[o:o + stride]); o += stride
        for x in range(stride):
            a = line[x - 4] if x >= 4 else 0
            b = prev[x]
            c = prev[x - 4] if x >= 4 else 0
            if f == 1: line[x] = (line[x] + a) & 255
            elif f == 2: line[x] = (line[x] + b) & 255
            elif f == 3: line[x] = (line[x] + (a + b) // 2) & 255
            elif f == 4:
                p = a + b - c
                pa, pb, pc = abs(p - a), abs(p - b), abs(p - c)
                line[x] = (line[x] + (a if pa <= pb and pa <= pc else (b if pb <= pc else c))) & 255
        px.append([tuple(line[x * 4:x * 4 + 4]) for x in range(w)])
        prev = line
    return w, h, px


def write_png(path, buf, W, H):
    raw = bytearray()
    for y in range(H):
        raw.append(0)
        for x in range(W):
            raw += bytes(buf[y * W + x])

    def chunk(t, d):
        return struct.pack(">I", len(d)) + t + d + struct.pack(">I", zlib.crc32(t + d) & 0xFFFFFFFF)
    open(path, "wb").write(
        b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", W, H, 8, 6, 0, 0, 0))
        + chunk(b"IDAT", zlib.compress(bytes(raw), 6)) + chunk(b"IEND", b""))


TEXCACHE = {}


def tex(ref):
    name = ref.split(":", 1)[1]
    if name not in TEXCACHE:
        TEXCACHE[name] = read_png(os.path.join(ASSETS, "textures", name + ".png"))
    return TEXCACHE[name]


# --------------------------------------------------------------------------- geometry
FACE_DEF = {
    "down":  ((0, 0, 0), (1, 0, 0), (1, 0, 1), (0, 0, 1), (0, -1, 0)),
    "up":    ((0, 1, 1), (1, 1, 1), (1, 1, 0), (0, 1, 0), (0, 1, 0)),
    "north": ((1, 1, 0), (0, 1, 0), (0, 0, 0), (1, 0, 0), (0, 0, -1)),
    "south": ((0, 1, 1), (1, 1, 1), (1, 0, 1), (0, 0, 1), (0, 0, 1)),
    "west":  ((0, 1, 0), (0, 1, 1), (0, 0, 1), (0, 0, 0), (-1, 0, 0)),
    "east":  ((1, 1, 1), (1, 1, 0), (1, 0, 0), (1, 0, 1), (1, 0, 0)),
}


def quads_of(path, xform):
    m = json.load(open(os.path.join(ASSETS, "models", path)))
    texs = m.get("textures", {})
    out = []
    for e in m.get("elements", []):
        f, t = e["from"], e["to"]
        for fname, fdata in e["faces"].items():
            corners, uvq = FACE_DEF[fname][:4], fdata["uv"]
            pts = []
            for (cx, cy, cz) in corners:
                pts.append(xform((f[0] + (t[0] - f[0]) * cx,
                                  f[1] + (t[1] - f[1]) * cy,
                                  f[2] + (t[2] - f[2]) * cz)))
            n = xform(FACE_DEF[fname][4], vector=True)
            uvs = [(uvq[0], uvq[1]), (uvq[2], uvq[1]), (uvq[2], uvq[3]), (uvq[0], uvq[3])]
            if fname in ("up", "down"):
                uvs = [(uvq[0], uvq[1]), (uvq[2], uvq[1]), (uvq[2], uvq[3]), (uvq[0], uvq[3])]
            out.append((pts, uvs, tex(texs[fdata["texture"].lstrip("#")]), n))
    return out


def mk_xform(origin=(0, 0, 0), pivot=None, angle=0.0, axis="x"):
    ox, oy, oz = origin

    def f(p, vector=False):
        x, y, z = p
        if pivot is not None and angle:
            px, py, pz = pivot
            if not vector:
                x, y, z = x - px, y - py, z - pz
            c, s = math.cos(angle), math.sin(angle)
            if axis == "x":
                y, z = y * c - z * s, y * s + z * c
            else:
                x, y = x * c - y * s, x * s + y * c
            if not vector:
                x, y, z = x + px, y + py, z + pz
        if vector:
            return (x, y, z)
        return (x + ox, y + oy, z + oz)
    return f


# --------------------------------------------------------------------------- render
def render(quads, W, H, yaw, pitch, centre, scale, path):
    cy, sy = math.cos(yaw), math.sin(yaw)
    cp, sp = math.cos(pitch), math.sin(pitch)

    def project(p):
        x, y, z = p[0] - centre[0], p[1] - centre[1], p[2] - centre[2]
        xr, zr = x * cy - z * sy, x * sy + z * cy
        yr, zr2 = y * cp - zr * sp, y * sp + zr * cp
        return (W / 2 + xr * scale, H / 2 - yr * scale, zr2)

    buf = [(24, 25, 29, 255)] * (W * H)
    zb = [1e9] * (W * H)
    light = (-0.45, 0.82, -0.35)
    ln = math.sqrt(sum(c * c for c in light))
    light = tuple(c / ln for c in light)

    for pts, uvs, texture, n in quads:
        nl = math.sqrt(sum(c * c for c in n)) or 1
        nn = tuple(c / nl for c in n)
        lam = max(0.0, sum(nn[i] * light[i] for i in range(3)))
        bright = 0.55 + 0.45 * lam
        sc = [project(p) for p in pts]
        tw, th, tpx = texture
        for tri in ((0, 1, 2), (0, 2, 3)):
            (x0, y0, z0), (x1, y1, z1), (x2, y2, z2) = (sc[i] for i in tri)
            u0, v0 = uvs[tri[0]]; u1, v1 = uvs[tri[1]]; u2, v2 = uvs[tri[2]]
            minx, maxx = int(min(x0, x1, x2)), int(max(x0, x1, x2)) + 1
            miny, maxy = int(min(y0, y1, y2)), int(max(y0, y1, y2)) + 1
            det = (y1 - y2) * (x0 - x2) + (x2 - x1) * (y0 - y2)
            if abs(det) < 1e-9:
                continue
            for py in range(max(0, miny), min(H, maxy)):
                for px in range(max(0, minx), min(W, maxx)):
                    fx, fy = px + 0.5, py + 0.5
                    l0 = ((y1 - y2) * (fx - x2) + (x2 - x1) * (fy - y2)) / det
                    l1 = ((y2 - y0) * (fx - x2) + (x0 - x2) * (fy - y2)) / det
                    l2 = 1 - l0 - l1
                    if l0 < -0.002 or l1 < -0.002 or l2 < -0.002:
                        continue
                    z = l0 * z0 + l1 * z1 + l2 * z2
                    idx = py * W + px
                    if z >= zb[idx]:
                        continue
                    u = (l0 * u0 + l1 * u1 + l2 * u2) / 16.0 * tw
                    v = (l0 * v0 + l1 * v1 + l2 * v2) / 16.0 * th
                    c = tpx[min(th - 1, max(0, int(v)))][min(tw - 1, max(0, int(u)))]
                    if c[3] == 0:
                        continue
                    zb[idx] = z
                    buf[idx] = (min(255, int(c[0] * bright)), min(255, int(c[1] * bright)),
                                min(255, int(c[2] * bright)), 255)
    write_png(path, buf, W, H)


# --------------------------------------------------------------------------- assembly
def wrist_local(theta_deg):
    t = math.radians(theta_deg)
    return (CRANK_AXIS_Y - CRANK_R * math.cos(t)
            + math.sqrt(ROD_L ** 2 - (CRANK_R * math.sin(t)) ** 2)) - 16.0


def rod_swing(theta_deg):
    return math.asin(CRANK_R * math.sin(math.radians(theta_deg)) / ROD_L)


# WHERE EACH CYLINDER'S THROW SITS, in degrees, and it is no longer an even
# division. Since Milestone 15B the engine is a four-stroke, so crank geometry
# and firing order are different questions: an inline-4's throws are 0/180/180/0
# - the flat-plane crank, cylinders 1 and 4 moving together against 2 and 3 -
# and it fires 1-3-4-2. Must match EngineTuning.cylinderPhaseOffsetDegrees.
THROWS = {1: (0.0,), 2: (0.0, 180.0), 3: (0.0, 120.0, 240.0),
          4: (0.0, 180.0, 180.0, 0.0)}

# ... and where each sits in the 720-degree CYCLE, which is what the valve gear
# follows. The negation of the ignition offset, exactly as
# FourStrokeFiringOrder.cyclePhaseOffsetDegrees computes it.
CYCLE_OFFSETS = {1: (0.0,), 2: (0.0, 540.0), 3: (0.0, 480.0, 240.0),
                 4: (0.0, 180.0, 540.0, 360.0)}


def phase_offset(index, count):
    return THROWS[count][index]


def cycle_offset(index, count):
    return CYCLE_OFFSETS[count][index]


# --------------------------------------------------------------------- valves
# The same arithmetic ValveTiming and CamshaftTiming run in the Java, so what is
# rendered here is what the game draws rather than an impression of it.
VALVE_LIFT = 1.1
INTAKE_OPEN, EXHAUST_OPEN = 540.0, 360.0
STROKE = 180.0


def lift_curve(progress):
    if progress <= 0.0 or progress >= 1.0:
        return 0.0
    return (1.0 - math.cos(2.0 * math.pi * progress)) / 2.0


def valve_lift(cycle_angle, open_angle):
    since = (cycle_angle - open_angle) % 720.0
    if since >= STROKE:
        return 0.0
    return lift_curve(since / STROKE)


def section(theta, index, count, carburetor, sump, spark_plug=True):
    """One bay of an engine: crankcase, throw, bore, and whatever is bolted on.

    `index` and `count` pick the same block state variants the game would: which
    way the run continues decides the crankcase's seam geometry and which share
    of the shared intake manifold this cylinder carries.
    """
    x = index * 16
    back, ahead = index > 0, index < count - 1
    angle = theta + phase_offset(index, count)
    q = []
    q += quads_of("block/crankshaft_joined.json" if back else "block/crankshaft.json",
                  mk_xform((x, 0, 0)))
    q += quads_of("block/crank_assembly_x.json",
                  mk_xform((x, 0, 0), pivot=(8, 8, 8), angle=math.radians(angle), axis="x"))
    # THE VALVE GEAR. The camshaft turns at half crank speed about its own
    # centreline; each pushrod rides its lobe; each rocker swings by the lift its
    # pushrod gave it; each valve is pushed down by the same amount. Every one of
    # them is a function of the ONE cycle angle, which is why they cannot drift
    # from the piston below them.
    cycle = (theta + cycle_offset(index, count)) % 720.0
    # Translate onto the real axis, THEN turn about the block centre - which is
    # exactly the pair CrankshaftRenderer applies, in the same order, because
    # Create's rotateCentered can only pivot about the block's own centre.
    # The first section carries the timing drive - the sprocket on the end of the
    # camshaft, the smaller one on the crank, and the chain between them. One per
    # engine, at the free end, opposite the flywheel.
    q += quads_of("block/camshaft_running_drive_x.json" if index == 0
                  else "block/camshaft_running_x.json",
                  mk_xform((x, CAM_CY - 8.0, CAM_CZ - 8.0), pivot=(8, 8, 8),
                           angle=math.radians(cycle / 2.0), axis="x"))
    if index == 0:
        # The drive gear turns at crank speed and the camshaft's at half, which
        # is the whole point - and it is the same pair of transforms, translate
        # then rotate about the block centre, for the same reason.
        # NEGATED, and that is not a fudge: meshing gears turn in opposite senses,
        # so a drive gear drawn the same way round as the wheel it drives would be
        # visibly impossible at the one place a player looks - where the teeth
        # meet. Its speed is still exactly the crank's, which is what a gear
        # geared 1:1 to the crankshaft through the case turns at.
        q += quads_of("block/timing_gear_x.json",
                      mk_xform((x, TIMING_DRIVE_CY - 8.0, TIMING_DRIVE_CZ - 8.0),
                               pivot=(8, 8, 8), angle=math.radians(-angle), axis="x"))
        q += quads_of("block/timing_case_x.json", mk_xform((x, 0, 0)))
    lifts = (valve_lift(cycle, INTAKE_OPEN), valve_lift(cycle, EXHAUST_OPEN))
    cylinder = "block/cylinder.json"
    if back and ahead:
        cylinder = "block/cylinder_manifold_both.json"
    elif back:
        cylinder = "block/cylinder_manifold_negative.json"
    elif ahead:
        cylinder = "block/cylinder_manifold_positive.json"
    q += quads_of(cylinder, mk_xform((x, 16, 0)))
    if spark_plug:
        q += quads_of("block/spark_plug_x.json", mk_xform((x, 16, 0)))
    wl = wrist_local(angle)
    q += quads_of("block/piston_head.json", mk_xform((x, 16 + wl - 8.0, 0)))
    q += quads_of("block/connecting_rod_x.json",
                  mk_xform((x, 16 + wl - 8.0, 0), pivot=(8, 8, 8),
                           angle=rod_swing(angle), axis="x"))
    for valve_x, lift in zip(VALVE_X, lifts):
        offset = valve_x - 8.0
        q += quads_of("block/pushrod_x.json",
                      mk_xform((x + offset, 16 + lift * VALVE_LIFT, 0)))
        q += quads_of("block/rocker_x.json",
                      mk_xform((x + offset, 16 + ROCKER_PIVOT_Y - 8.0, ROCKER_PIVOT_Z - 8.0),
                               pivot=(8, 8, 8),
                               angle=math.radians(-lift * ROCKER_SWING), axis="x"))
        q += quads_of("block/valve_x.json", mk_xform((x + offset, 16 - lift * VALVE_LIFT, 0)))
    if carburetor:
        q += quads_of("block/carburetor.json", mk_xform((x, 32, 0)))
    if sump:
        q += quads_of("block/oil_sump.json", mk_xform((x, -16, 0)))
    return q


def assemble(theta, sections=1, with_carb=True, flywheel=True, spark_plug=True,
             sump=True):
    """A whole inline-N, laid out exactly as EngineComponents says one is.

    One crankshaft, one Carburetor, one Oil Sump and one Flywheel however many
    cylinders it has - which is the arrangement the shared intake manifold and
    the shared crankcase geometry exist to make legible.
    """
    q = []
    for index in range(sections):
        q += section(theta, index, sections,
                     carburetor=with_carb and index == 0,
                     sump=sump and index == 0, spark_plug=spark_plug)
    if flywheel:
        q += quads_of("block/flywheel_wheel_x.json",
                      mk_xform((sections * 16, 0, 0), pivot=(8, 8, 8),
                               angle=math.radians(theta), axis="x"))
    return q


if __name__ == "__main__":
    out = sys.argv[1] if len(sys.argv) > 1 else "/tmp/preview"
    os.makedirs(out, exist_ok=True)
    # One picture per inline layout, from the two angles the shared castings
    # read from: three-quarter for the manifold and the crankcase seams, and
    # straight down the intake side for how long an inline-4 looks.
    for sections in (1, 2, 3, 4):
        span = sections * 16
        # From the INTAKE side (-Z), which is the side the Carburetor, the air
        # cleaner, the shared manifold and - since Milestone 15B - the whole
        # valve gear are on. The three-quarter view is where the cam, the
        # pushrods and the rocker shaft all read at once, which is the thing
        # worth checking; the head-on one is how long an inline-4 looks.
        #
        # The exhaust view is here too, and it is not decoration: that flank is
        # deliberately kept clear for a future exhaust manifold, and a picture of
        # it is how anyone notices the day something starts creeping onto it.
        for name, yaw, pitch in (("3q", math.radians(38), math.radians(20)),
                                 ("intake", math.radians(2), math.radians(6)),
                                 ("exhaust", math.radians(182), math.radians(6))):
            # One scale for every layout, so the four pictures can be put side
            # by side and compared: an inline-4 has to look four times as long
            # as an inline-1, not four times as small.
            render(assemble(0, sections), 300 + 108 * sections, 520, yaw, pitch,
                   (span / 2.0, 20, 8), 6.4,
                   os.path.join(out, f"r{sections}_{name}.png"))
            print("rendered", f"r{sections}_{name}")
    # And the inline-1 through a revolution, which is the animation check.
    for theta in (0, 90, 180, 270):
        render(assemble(theta, 1), 340, 420, math.radians(38), math.radians(20),
               (10, 22, 6), 11.0, os.path.join(out, f"r1_3q_{theta:03d}.png"))
        print("rendered", f"r1_3q_{theta:03d}")
