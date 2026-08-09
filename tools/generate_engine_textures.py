#!/usr/bin/env python3
"""Generates the 32x32 engine texture set for Create: Engineered Combustion.

Pure stdlib (zlib + struct) PNG writer - no Pillow in this environment.
Every texture is deterministic: the noise uses a fixed-seed LCG so re-running
the script produces byte-identical output.
"""
import os
import pathlib
import struct
import zlib

OUT = str(pathlib.Path(__file__).resolve().parents[1]
           / "src/main/resources/assets/engineered_combustion/textures")
S = 32  # texture size; 2 texels per model unit


# --------------------------------------------------------------------------
# tiny deterministic PRNG so textures are reproducible
# --------------------------------------------------------------------------
class Rng:
    def __init__(self, seed):
        self.s = seed & 0xFFFFFFFF

    def next(self):
        self.s = (self.s * 1103515245 + 12345) & 0x7FFFFFFF
        return self.s

    def rangef(self, lo, hi):
        return lo + (hi - lo) * (self.next() / 0x7FFFFFFF)

    def rint(self, lo, hi):
        return lo + self.next() % (hi - lo + 1)


def write_png(path, pixels):
    """pixels: list of rows, each row a list of (r,g,b,a)."""
    raw = bytearray()
    for row in pixels:
        raw.append(0)
        for (r, g, b, a) in row:
            raw += bytes((r & 255, g & 255, b & 255, a & 255))

    def chunk(tag, data):
        c = struct.pack(">I", len(data)) + tag + data
        return c + struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF)

    header = struct.pack(">IIBBBBB", len(pixels[0]), len(pixels), 8, 6, 0, 0, 0)
    png = (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", header)
           + chunk(b"IDAT", zlib.compress(bytes(raw), 9)) + chunk(b"IEND", b""))
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "wb") as f:
        f.write(png)


def blank(color=(0, 0, 0, 0)):
    return [[color for _ in range(S)] for _ in range(S)]


def clamp(v):
    return 0 if v < 0 else (255 if v > 255 else int(v))


def shade(c, d):
    return (clamp(c[0] + d), clamp(c[1] + d), clamp(c[2] + d), 255)


def mix(a, b, t):
    return (clamp(a[0] + (b[0] - a[0]) * t), clamp(a[1] + (b[1] - a[1]) * t),
            clamp(a[2] + (b[2] - a[2]) * t), 255)


def fill(px, color):
    for y in range(S):
        for x in range(S):
            px[y][x] = color


def rect(px, x0, y0, x1, y1, color):
    for y in range(max(0, y0), min(S, y1)):
        for x in range(max(0, x0), min(S, x1)):
            px[y][x] = color


def noise(px, rng, amount, density=1.0):
    """Per-pixel brightness jitter - the grain that makes cast iron read as cast."""
    for y in range(S):
        for x in range(S):
            if density < 1.0 and rng.rangef(0, 1) > density:
                continue
            px[y][x] = shade(px[y][x], int(rng.rangef(-amount, amount)))


def specks(px, rng, count, delta, size=1):
    for _ in range(count):
        x = rng.rint(0, S - size)
        y = rng.rint(0, S - size)
        for dy in range(size):
            for dx in range(size):
                px[y + dy][x + dx] = shade(px[y + dy][x + dx], delta)


def border(px, light, dark):
    """A lit top/left and shadowed bottom/right edge: makes cube edges legible."""
    for i in range(S):
        px[0][i] = shade(px[0][i], light)
        px[i][0] = shade(px[i][0], light)
        px[S - 1][i] = shade(px[S - 1][i], dark)
        px[i][S - 1] = shade(px[i][S - 1], dark)


def disc(px, cx, cy, r, color, ring=None):
    for y in range(S):
        for x in range(S):
            d = ((x + 0.5 - cx) ** 2 + (y + 0.5 - cy) ** 2) ** 0.5
            if d <= r - 0.6:
                px[y][x] = color
            elif ring is not None and d <= r + 0.4:
                px[y][x] = ring


def bolt(px, cx, cy, r, base):
    """A hex-ish bolt head: bright top-left, dark ring, dark slot."""
    disc(px, cx, cy, r, shade(base, 26), shade(base, -34))
    disc(px, cx - 0.4, cy - 0.4, r * 0.45, shade(base, 40))
    px[int(cy)][int(cx)] = shade(base, -22)


# --------------------------------------------------------------------------
# palette
# --------------------------------------------------------------------------
CAST = (74, 76, 82, 255)          # primary cast iron
CAST_DARK = (54, 56, 61, 255)
HEAD = (80, 77, 76, 255)          # cylinder head casting, a touch warmer
DECK = (112, 117, 124, 255)       # machined deck / gasket faces
STEEL = (168, 175, 184, 255)      # machined steel: journals, bolts
FORGED = (136, 143, 154, 255)     # forged steel: connecting rod
WEB = (98, 104, 114, 255)         # crank webs / counterweights, cast-dark
ALU = (176, 181, 187, 255)        # piston
FLY = (62, 63, 69, 255)           # flywheel cast iron, darkest
CARB = (88, 91, 97, 255)          # carburetor body
BRASS = (182, 143, 66, 255)
CERAMIC = (222, 214, 196, 255)    # spark plug insulator porcelain
FILTER = (58, 58, 62, 255)        # air cleaner canister, painted dark
MESH = (96, 88, 74, 255)          # filter element behind the grille


def t_cast_iron(seed=11, base=CAST, grain=9):
    px = blank()
    fill(px, base)
    rng = Rng(seed)
    noise(px, rng, grain)
    specks(px, rng, 22, -16)
    specks(px, rng, 10, 13)
    border(px, 12, -16)
    return px


def t_crankcase():
    """Cast crankcase wall: grain, a cast rib, and four bolt heads."""
    px = t_cast_iron(23)
    rng = Rng(77)
    # raised cast rib running across the panel
    rect(px, 0, 14, S, 15, shade(CAST, 16))
    rect(px, 0, 15, S, 17, shade(CAST, 6))
    rect(px, 0, 17, S, 18, shade(CAST, -18))
    noise(px, rng, 5)
    for (bx, by) in ((5, 5), (26, 5), (5, 26), (26, 26)):
        bolt(px, bx, by, 2.6, STEEL)
    return px


def t_crankcase_deck():
    """Machined mating surface: brushed, lighter, with bolt holes."""
    px = blank()
    fill(px, DECK)
    rng = Rng(41)
    for y in range(S):
        rect(px, 0, y, S, y + 1, shade(DECK, int(rng.rangef(-7, 7))))
    noise(px, rng, 4)
    for (bx, by) in ((4, 4), (27, 4), (4, 27), (27, 27), (15.5, 4), (15.5, 27)):
        disc(px, bx, by, 2.2, shade(DECK, -40), shade(DECK, 22))
    border(px, 14, -18)
    return px


def t_cylinder():
    """Cylinder barrel casting - vertical draft marks from the mould."""
    px = t_cast_iron(31, CAST, 8)
    rng = Rng(53)
    for x in range(0, S, 4):
        d = int(rng.rangef(-9, 9))
        for y in range(S):
            px[y][x] = shade(px[y][x], d)
    border(px, 12, -16)
    return px


def t_cylinder_fin():
    """Cooling fin: lit top edge, shadowed root. Read as stacked cast fins."""
    px = blank()
    fill(px, CAST)
    rng = Rng(67)
    rect(px, 0, 0, S, 3, shade(CAST, 30))
    rect(px, 0, 3, S, 7, shade(CAST, 14))
    rect(px, 0, 7, S, 22, CAST)
    rect(px, 0, 22, S, 27, shade(CAST, -12))
    rect(px, 0, 27, S, S, shade(CAST, -26))
    noise(px, rng, 6)
    specks(px, rng, 14, -12)
    return px


def t_cylinder_head():
    """Head casting: heavier grain plus stud bosses."""
    px = t_cast_iron(83, HEAD, 10)
    rng = Rng(97)
    rect(px, 0, 24, S, 26, shade(HEAD, -20))
    rect(px, 0, 26, S, 27, shade(HEAD, 14))
    noise(px, rng, 4)
    for (bx, by) in ((6, 7), (25, 7), (6, 20), (25, 20)):
        disc(px, bx, by, 3.4, shade(HEAD, 12), shade(HEAD, -22))
        bolt(px, bx, by, 2.0, STEEL)
    return px


def t_piston():
    """Piston skirt: machined aluminium, ring land, wrist-pin boss.

    Detail rows are placed for *world-aligned* UVs: v = 16 - y, two texels per
    model unit. The ring land element sits at y 8.6-9.4 (texel rows 13-15) and
    the skirt at y 5.5-8.7 (rows 15-21), which is where the boss goes.
    """
    px = blank()
    fill(px, ALU)
    rng = Rng(131)
    for x in range(S):
        d = int(rng.rangef(-5, 5))
        for y in range(S):
            px[y][x] = shade(px[y][x], d)
    # compression ring line, on the land between the two modelled grooves
    rect(px, 0, 12, S, 13, shade(ALU, 20))
    rect(px, 0, 13, S, 15, shade(ALU, -50))
    rect(px, 0, 15, S, 16, shade(ALU, -24))
    # oil control ring, lower down the skirt
    rect(px, 0, 24, S, 25, shade(ALU, -40))
    rect(px, 0, 25, S, 26, shade(ALU, 16))
    # wrist pin boss, centred in the skirt band
    disc(px, 16, 19, 4.6, shade(ALU, -16), shade(ALU, 26))
    disc(px, 16, 19, 2.6, shade(ALU, -48), shade(ALU, -28))
    disc(px, 15.4, 18.4, 1.1, shade(ALU, -62))
    border(px, 16, -20)
    return px


def t_piston_crown():
    """Crown face: turned finish with a shallow dish."""
    px = blank()
    fill(px, ALU)
    rng = Rng(139)
    for r in range(15, 2, -2):
        disc(px, 16, 16, r, shade(ALU, 6 if (r // 2) % 2 else -6))
    disc(px, 16, 16, 9, shade(ALU, -14), shade(ALU, 18))
    disc(px, 16, 16, 5, shade(ALU, -24))
    noise(px, rng, 4)
    border(px, 14, -18)
    return px


def t_conrod():
    """Forged rod: bright I-beam web down the middle, shadowed flanges."""
    px = blank()
    fill(px, FORGED)
    rng = Rng(151)
    rect(px, 0, 0, 9, S, shade(FORGED, -34))
    rect(px, 9, 0, 12, S, shade(FORGED, -12))
    rect(px, 12, 0, 20, S, shade(FORGED, 30))
    rect(px, 20, 0, 23, S, shade(FORGED, -12))
    rect(px, 23, 0, S, S, shade(FORGED, -34))
    noise(px, rng, 6)
    specks(px, rng, 10, -12)
    return px


def t_crank_web():
    """Crank web / counterweight: forged, with a machined balance pad.

    Deliberately darker than the connecting rod so the two never read as one
    lump of grey when they overlap in the crankcase window.
    """
    px = blank()
    fill(px, WEB)
    rng = Rng(163)
    noise(px, rng, 8)
    specks(px, rng, 18, -14)
    disc(px, 16, 16, 10, shade(WEB, 12), shade(WEB, -22))
    disc(px, 16, 16, 5, shade(WEB, -18), shade(WEB, 16))
    for (bx, by) in ((7, 25), (25, 25)):
        bolt(px, bx, by, 2.2, STEEL)
    border(px, 12, -18)
    return px


def t_journal():
    """Machined journal / shaft / bolt: bright, fine turning lines.

    Deliberately uniform along both axes: journals and bolts are cut from many
    different element sizes, and a repeating pattern looks right at any of them.
    """
    px = blank()
    fill(px, STEEL)
    rng = Rng(173)
    for y in range(S):
        d = -14 if y % 4 == 0 else (11 if y % 4 == 2 else 0)
        rect(px, 0, y, S, y + 1, shade(STEEL, d + int(rng.rangef(-4, 4))))
    return px


def t_flywheel():
    """Flywheel rim tread: darkest cast iron with circumferential grooves."""
    px = t_cast_iron(191, FLY, 8)
    rng = Rng(197)
    for y in (6, 15, 24):
        rect(px, 0, y, S, y + 1, shade(FLY, -20))
        rect(px, 0, y + 1, S, y + 2, shade(FLY, 12))
    noise(px, rng, 4)
    return px


def t_flywheel_face():
    """Wheel side face: cast with a machined hub ring and a bolt circle."""
    px = t_cast_iron(211, FLY, 7)
    rng = Rng(223)
    disc(px, 16, 16, 13, shade(FLY, 8), shade(FLY, -18))
    disc(px, 16, 16, 8, shade(FLY, -6), shade(FLY, 16))
    disc(px, 16, 16, 4, shade(FLY, 22), shade(FLY, -20))
    for (bx, by) in ((16, 6), (26, 16), (16, 26), (6, 16)):
        bolt(px, bx, by, 1.8, STEEL)
    noise(px, rng, 3)
    return px


def t_carburetor():
    """Carburetor body: dark cast alloy, fine grain, a couple of screws."""
    px = blank()
    fill(px, CARB)
    rng = Rng(229)
    noise(px, rng, 7)
    specks(px, rng, 26, -12)
    rect(px, 0, 12, S, 13, shade(CARB, -20))
    rect(px, 0, 13, S, 14, shade(CARB, 12))
    bolt(px, 7, 24, 2.0, STEEL)
    bolt(px, 25, 24, 2.0, STEEL)
    border(px, 14, -18)
    return px


def t_oil_sump():
    """Oil pan casting: cast iron with a wet, slightly darker lower band."""
    px = t_cast_iron(251, CAST, 9)
    rng = Rng(257)
    rect(px, 0, 20, S, 21, shade(CAST, -22))
    rect(px, 0, 21, S, S, shade(CAST, -12))
    rect(px, 0, 6, S, 7, shade(CAST, 16))
    noise(px, rng, 4)
    specks(px, rng, 12, -14)
    border(px, 12, -18)
    return px


def _indicator(lens, glow):
    """Tell-tale lamp: a cast bezel around a round lens."""
    px = blank()
    fill(px, shade(CAST, -8))
    rng = Rng(263)
    noise(px, rng, 6)
    disc(px, 16, 16, 11, shade(CAST, 10), shade(CAST, -26))
    disc(px, 16, 16, 8, lens, shade(CAST, -30))
    disc(px, 16, 16, 5, glow)
    disc(px, 13.5, 13.5, 2.2, shade(glow, 34))
    border(px, 10, -16)
    return px


def t_indicator_off():
    return _indicator((58, 46, 30, 255), (74, 60, 38, 255))


def t_indicator_on():
    return _indicator((214, 150, 44, 255), (255, 214, 120, 255))


def t_brass():
    px = blank()
    fill(px, BRASS)
    rng = Rng(239)
    noise(px, rng, 10)
    specks(px, rng, 14, -18)
    rect(px, 0, 0, S, 3, shade(BRASS, 26))
    rect(px, 0, S - 3, S, S, shade(BRASS, -30))
    border(px, 16, -22)
    return px


def t_spark_plug_ceramic():
    """Spark plug insulator: glazed porcelain with the usual corrugations.

    The ribs run across the texture so that, with the world-aligned UVs the
    model generator emits, they come out perpendicular to the plug's axis on
    every side face - which is what makes a 1x1 stub read as a spark plug
    rather than as a white peg.
    """
    px = blank()
    fill(px, CERAMIC)
    rng = Rng(269)
    for x in range(0, S, 6):
        rect(px, x, 0, x + 1, S, shade(CERAMIC, -34))
        rect(px, x + 1, 0, x + 3, S, shade(CERAMIC, 18))
        rect(px, x + 3, 0, x + 4, S, shade(CERAMIC, -12))
    noise(px, rng, 4)
    # the glaze catches the light along one edge
    rect(px, 0, 0, S, 2, shade(CERAMIC, 22))
    rect(px, 0, S - 3, S, S, shade(CERAMIC, -28))
    return px


def t_air_filter():
    """Oil-bath air cleaner canister: dark painted steel with a rolled seam."""
    px = blank()
    fill(px, FILTER)
    rng = Rng(277)
    noise(px, rng, 7)
    specks(px, rng, 20, -10)
    specks(px, rng, 8, 12)
    # rolled seams top and bottom, as a pressed-steel canister has
    for y in (3, 26):
        rect(px, 0, y, S, y + 1, shade(FILTER, 26))
        rect(px, 0, y + 1, S, y + 3, shade(FILTER, -22))
    border(px, 14, -18)
    return px


def t_air_filter_mesh():
    """The filter element itself: a coarse woven gauze seen through slots."""
    px = blank()
    fill(px, MESH)
    rng = Rng(281)
    for y in range(S):
        for x in range(S):
            if (x // 2 + y // 2) % 2 == 0:
                px[y][x] = shade(MESH, 16)
            else:
                px[y][x] = shade(MESH, -18)
    noise(px, rng, 6)
    # the retaining band the gauze is clamped behind
    for y in (0, 1, S - 2, S - 1):
        rect(px, 0, y, S, y + 1, shade(FILTER, 10))
    return px


def t_combustion_flash():
    """The burn itself: a soft, mostly transparent orange-to-white core.

    Alpha rather than colour carries the shape, because the renderer draws this
    on a translucent quad at full brightness and fades it out over three ticks.
    """
    px = blank()
    core = (255, 246, 214)
    edge = (255, 138, 32)
    for y in range(S):
        for x in range(S):
            d = (((x + 0.5 - 16) / 16.0) ** 2 + ((y + 0.5 - 16) / 16.0) ** 2) ** 0.5
            if d >= 1.0:
                px[y][x] = (edge[0], edge[1], edge[2], 0)
                continue
            t = d ** 0.7
            c = mix(core + (255,), edge + (255,), t)
            px[y][x] = (c[0], c[1], c[2], clamp(255 * (1.0 - t) ** 1.2))
    return px


TEXTURES = {
    "block/cast_iron": t_cast_iron,
    "block/crankshaft": t_crankcase,
    "block/crankcase_deck": t_crankcase_deck,
    "block/cylinder": t_cylinder,
    "block/cylinder_fin": t_cylinder_fin,
    "block/cylinder_head": t_cylinder_head,
    "block/piston": t_piston,
    "block/piston_crown": t_piston_crown,
    "block/conrod": t_conrod,
    "block/crank_web": t_crank_web,
    "block/journal": t_journal,
    "block/flywheel": t_flywheel,
    "block/flywheel_face": t_flywheel_face,
    "block/carburetor": t_carburetor,
    "block/brass": t_brass,
    "block/oil_sump": t_oil_sump,
    "block/indicator_off": t_indicator_off,
    "block/indicator_on": t_indicator_on,
    "block/spark_plug_ceramic": t_spark_plug_ceramic,
    "block/air_filter": t_air_filter,
    "block/air_filter_mesh": t_air_filter_mesh,
    "block/combustion_flash": t_combustion_flash,
}

if __name__ == "__main__":
    for name, fn in TEXTURES.items():
        write_png(os.path.join(OUT, name + ".png"), fn())
        print("wrote", name + ".png")
