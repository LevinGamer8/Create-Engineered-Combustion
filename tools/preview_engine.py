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


def assemble(theta, with_carb=True, flywheel=True, spark_plug=True):
    q = []
    q += quads_of("block/crankshaft.json", mk_xform())
    q += quads_of("block/crank_assembly_x.json",
                  mk_xform(pivot=(8, 8, 8), angle=math.radians(theta), axis="x"))
    q += quads_of("block/cylinder.json", mk_xform((0, 16, 0)))
    # Its own model since it became an installable component, and drawn here for
    # the same reason the block entity renderer draws it: a preview of a finished
    # engine is a preview of one with a plug in it.
    if spark_plug:
        q += quads_of("block/spark_plug.json", mk_xform((0, 16, 0)))
    wl = wrist_local(theta)
    q += quads_of("block/piston_head.json", mk_xform((0, 16 + wl - 8.0, 0)))
    q += quads_of("block/connecting_rod_x.json",
                  mk_xform((0, 16 + wl - 8.0, 0), pivot=(8, 8, 8),
                           angle=rod_swing(theta), axis="x"))
    if with_carb:
        q += quads_of("block/carburetor.json", mk_xform((0, 32, 0)))
    if flywheel:
        q += quads_of("block/flywheel_wheel_x.json",
                      mk_xform((16, 0, 0), pivot=(8, 8, 8),
                               angle=math.radians(theta), axis="x"))
    return q


if __name__ == "__main__":
    out = sys.argv[1] if len(sys.argv) > 1 else "/tmp/preview"
    os.makedirs(out, exist_ok=True)
    views = [
        ("3q", math.radians(-38), math.radians(20)),
        ("side", math.radians(0), math.radians(4)),
        ("end", math.radians(-90), math.radians(4)),
    ]
    for theta in (0, 90, 180, 270):
        for name, yaw, pitch in views:
            render(assemble(theta), 340, 420, yaw, pitch, (12, 22, 8), 11.0,
                   os.path.join(out, f"{name}_{theta:03d}.png"))
            print("rendered", name, theta)
