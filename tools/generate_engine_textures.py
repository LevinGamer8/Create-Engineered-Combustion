#!/usr/bin/env python3
"""Generates every texture for Create: Engineered Combustion.

Three sets live here, at the resolution each one belongs at:

* the 32x32 block set for the engine castings - two texels per model unit, so
  the world-aligned UVs the model generator emits land on texel boundaries;
* the 16x16 animated fluid sprites for gasoline and engine oil, plus the
  ``.mcmeta`` files that drive them. Fluid sprites have to be 16 wide: the
  fluid renderer, Create's tanks and the carburetor's sight window all sample
  them at block scale next to vanilla water;
* the 16x16 bucket items, drawn on vanilla's own bucket silhouette so a
  Gasoline Bucket sits in the hotbar next to a Water Bucket without looking
  like it came from a different game.

Pure stdlib (zlib + struct) PNG writer - no Pillow in this environment.
Every texture is deterministic: the noise uses a fixed-seed LCG so re-running
the script produces byte-identical output.
"""
import json
import math
import os
import pathlib
import struct
import zlib

OUT = str(pathlib.Path(__file__).resolve().parents[1]
           / "src/main/resources/assets/engineered_combustion/textures")
S = 32  # block texture size; 2 texels per model unit
ITEM = 16  # item and fluid sprite size; one texel per vanilla texel


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


def blank(color=(0, 0, 0, 0), size=None, height=None):
    size = S if size is None else size
    return [[color for _ in range(size)] for _ in range(height or size)]


def clamp(v):
    return 0 if v < 0 else (255 if v > 255 else int(v))


def shade(c, d):
    return (clamp(c[0] + d), clamp(c[1] + d), clamp(c[2] + d), 255)


def mix(a, b, t):
    return (clamp(a[0] + (b[0] - a[0]) * t), clamp(a[1] + (b[1] - a[1]) * t),
            clamp(a[2] + (b[2] - a[2]) * t), 255)


def fill(px, color):
    for row in px:
        for x in range(len(row)):
            row[x] = color


def rect(px, x0, y0, x1, y1, color):
    for y in range(max(0, y0), min(len(px), y1)):
        row = px[y]
        for x in range(max(0, x0), min(len(row), x1)):
            row[x] = color


def noise(px, rng, amount, density=1.0):
    """Per-pixel brightness jitter - the grain that makes cast iron read as cast."""
    for row in px:
        for x in range(len(row)):
            if density < 1.0 and rng.rangef(0, 1) > density:
                continue
            row[x] = shade(row[x], int(rng.rangef(-amount, amount)))


def _smooth(t):
    return t * t * (3.0 - 2.0 * t)


def value_noise(rng, cells, size=None):
    """A tileable field of smoothly interpolated values in [-1, 1].

    The lattice wraps, so a texture built from it still meets itself at the
    sprite edge - which matters here because the models use world-aligned UVs,
    and two blocks of cylinder barrel stacked on each other sample straight
    through the seam.
    """
    size = S if size is None else size
    grid = [[rng.rangef(-1.0, 1.0) for _ in range(cells)] for _ in range(cells)]
    step = size / float(cells)
    out = []
    for y in range(size):
        gy = y / step
        y0 = int(gy) % cells
        ty = _smooth(gy - int(gy))
        row = []
        for x in range(size):
            gx = x / step
            x0 = int(gx) % cells
            tx = _smooth(gx - int(gx))
            x1, y1 = (x0 + 1) % cells, (y0 + 1) % cells
            top = grid[y0][x0] + (grid[y0][x1] - grid[y0][x0]) * tx
            bottom = grid[y1][x0] + (grid[y1][x1] - grid[y1][x0]) * tx
            row.append(top + (bottom - top) * ty)
        out.append(row)
    return out


def mottle(px, rng, layers):
    """Clustered brightness variation, as `(lattice cells, amplitude)` layers.

    Per-pixel jitter on its own reads as television static, not as metal: a
    cast surface varies in patches the size of the grains that made it, not
    texel by texel. Two or three octaves of lattice noise give it that scale,
    and `noise` then goes on top for the last texel of tooth.
    """
    fields = [(value_noise(rng, cells, len(px)), amp) for (cells, amp) in layers]
    for y in range(len(px)):
        row = px[y]
        for x in range(len(row)):
            row[x] = shade(row[x], int(sum(f[y][x] * amp for (f, amp) in fields)))


def brushed(px, rng, amount, axis="x"):
    """Directional tool marks, the length of the sprite: a machined finish."""
    n = len(px) if axis == "x" else len(px[0])
    for i in range(n):
        d = int(rng.rangef(-amount, amount))
        if axis == "x":
            for x in range(len(px[i])):
                px[i][x] = shade(px[i][x], d)
        else:
            for y in range(len(px)):
                px[y][i] = shade(px[y][i], d)


def specks(px, rng, count, delta, size=1):
    h, w = len(px), len(px[0])
    for _ in range(count):
        x = rng.rint(0, w - size)
        y = rng.rint(0, h - size)
        for dy in range(size):
            for dx in range(size):
                px[y + dy][x + dx] = shade(px[y + dy][x + dx], delta)


def border(px, light, dark):
    """A lit top/left and shadowed bottom/right edge: makes cube edges legible."""
    h, w = len(px), len(px[0])
    for i in range(w):
        px[0][i] = shade(px[0][i], light)
        px[h - 1][i] = shade(px[h - 1][i], dark)
    for i in range(h):
        px[i][0] = shade(px[i][0], light)
        px[i][w - 1] = shade(px[i][w - 1], dark)


def disc(px, cx, cy, r, color, ring=None):
    for y in range(len(px)):
        row = px[y]
        for x in range(len(row)):
            d = ((x + 0.5 - cx) ** 2 + (y + 0.5 - cy) ** 2) ** 0.5
            if d <= r - 0.6:
                row[x] = color
            elif ring is not None and d <= r + 0.4:
                row[x] = ring


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
MODULE = (52, 47, 49, 255)        # control module housing, moulded phenolic
REDSTONE = (168, 42, 36, 255)     # the redstone inlay that names the part
SHALE_STONE = (128, 128, 128, 255)   # the host rock, vanilla stone's own grey
SHALE_DARK = (38, 34, 30, 255)       # the bituminous laminae in it
SHALE_MID = (66, 60, 52, 255)
# Petroleum residue. Deliberately a shade lighter than the crude it comes from:
# at (28,22,18) the lump was a black hole in the slot with no readable outline,
# and it also has to stay apart from a piece of coal, which is why it keeps the
# brown rather than going neutral.
TAR = (52, 40, 30, 255)


def t_cast_iron(seed=11, base=CAST, grain=9):
    """Sand-cast iron: blotchy at the scale of the mould, gritty at texel scale."""
    px = blank()
    fill(px, base)
    rng = Rng(seed)
    mottle(px, rng, ((4, grain * 1.7), (8, grain * 1.0), (16, grain * 0.45)))
    noise(px, rng, max(2, grain // 3))
    specks(px, rng, 18, -17)          # blowholes left by the sand
    specks(px, rng, 8, 14)
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
    mottle(px, rng, ((4, 7), (8, 4)))
    brushed(px, rng, 7)
    noise(px, rng, 3)
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
    rect(px, 0, 0, S, 2, shade(CAST, 36))     # the cast edge catches the light
    rect(px, 0, 2, S, 4, shade(CAST, 22))
    rect(px, 0, 4, S, 8, shade(CAST, 10))
    rect(px, 0, 8, S, 21, CAST)
    rect(px, 0, 21, S, 26, shade(CAST, -14))
    rect(px, 0, 26, S, 30, shade(CAST, -26))
    rect(px, 0, 30, S, S, shade(CAST, -34))   # and the root sits in its shadow
    mottle(px, rng, ((5, 8), (11, 4)))
    noise(px, rng, 3)
    specks(px, rng, 12, -12)
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
    mottle(px, rng, ((6, 5), (12, 3)))
    brushed(px, rng, 5, axis="y")          # the skirt is turned, not cast
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
    rect(px, 0, 0, 8, S, shade(FORGED, -36))
    rect(px, 8, 0, 11, S, shade(FORGED, -16))
    rect(px, 11, 0, 13, S, shade(FORGED, 12))
    rect(px, 13, 0, 19, S, shade(FORGED, 32))
    rect(px, 19, 0, 21, S, shade(FORGED, 12))
    rect(px, 21, 0, 24, S, shade(FORGED, -16))
    rect(px, 24, 0, S, S, shade(FORGED, -36))
    mottle(px, rng, ((5, 7), (10, 4)))
    noise(px, rng, 3)
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
    mottle(px, rng, ((4, 13), (9, 7)))
    noise(px, rng, 3)
    specks(px, rng, 16, -14)
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
        d = -15 if y % 4 == 0 else (12 if y % 4 == 2 else (-5 if y % 4 == 3 else 0))
        rect(px, 0, y, S, y + 1, shade(STEEL, d + int(rng.rangef(-4, 4))))
    mottle(px, rng, ((6, 5),))
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
    mottle(px, rng, ((4, 11), (9, 6), (16, 3)))
    noise(px, rng, 3)
    specks(px, rng, 22, -12)
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
    """Turned brass fittings: a bright band across the crown of the round, and
    the tarnish that collects wherever the polishing rag never reaches."""
    px = blank()
    fill(px, BRASS)
    rng = Rng(239)
    # the sheen a round brass fitting has along its lit side
    for y in range(S):
        t = abs(y - 9.5) / 16.0
        rect(px, 0, y, S, y + 1, mix(shade(BRASS, 34), shade(BRASS, -26),
                                     min(1.0, t)))
    mottle(px, rng, ((7, 9), (15, 5)))
    specks(px, rng, 16, -22)              # patina
    specks(px, rng, 6, 20)
    noise(px, rng, 3)
    rect(px, 0, 0, S, 2, shade(BRASS, 30))
    rect(px, 0, S - 3, S, S, shade(BRASS, -34))
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
        rect(px, x, 0, x + 1, S, shade(CERAMIC, -38))     # root of the groove
        rect(px, x + 1, 0, x + 2, S, shade(CERAMIC, -8))
        rect(px, x + 2, 0, x + 4, S, shade(CERAMIC, 20))  # crown of the rib
        rect(px, x + 4, 0, x + 5, S, shade(CERAMIC, -2))
        rect(px, x + 5, 0, x + 6, S, shade(CERAMIC, -20))
    mottle(px, rng, ((7, 6),))
    noise(px, rng, 3)
    # the glaze catches the light along one edge
    rect(px, 0, 0, S, 2, shade(CERAMIC, 22))
    rect(px, 0, S - 3, S, S, shade(CERAMIC, -28))
    return px


def t_air_filter():
    """Oil-bath air cleaner canister: dark painted steel with a rolled seam."""
    px = blank()
    fill(px, FILTER)
    rng = Rng(277)
    mottle(px, rng, ((5, 9), (10, 5)))     # unevenly worn paint
    noise(px, rng, 3)
    specks(px, rng, 18, -10)
    specks(px, rng, 7, 13)
    # rolled seams top and bottom, as a pressed-steel canister has
    for y in (3, 26):
        rect(px, 0, y, S, y + 1, shade(FILTER, 26))
        rect(px, 0, y + 1, S, y + 3, shade(FILTER, -22))
    border(px, 14, -18)
    return px


def t_air_filter_mesh():
    """The filter element itself: a coarse woven gauze seen through slots.

    Woven rather than chequered. A plain checkerboard is the obvious way to
    draw a mesh and the wrong one: it has no over-and-under, so it reads as
    tiling squares. Giving each strand a lit crown and a shadow where the
    crossing strand passes over it is what turns the same grid into cloth.
    """
    px = blank()
    fill(px, MESH)
    rng = Rng(281)
    pitch = 4
    for y in range(S):
        for x in range(S):
            warp = (x % pitch) < pitch // 2     # vertical strand on top here
            along = (y if warp else x) % pitch
            crown = 15 if along in (1, 2) else -8
            px[y][x] = shade(MESH, crown + (10 if warp else -12))
    # the shadow each strand throws into the gap it crosses
    for y in range(S):
        for x in range(S):
            if x % pitch == 0 or y % pitch == 0:
                px[y][x] = shade(px[y][x], -22)
    mottle(px, rng, ((6, 7),))
    noise(px, rng, 4)
    # the retaining band the gauze is clamped behind
    for y in (0, 1, S - 2, S - 1):
        rect(px, 0, y, S, y + 1, shade(FILTER, 10))
    return px


def t_control_module():
    """Redstone Control Module: moulded phenolic with a recessed red inlay.

    The inlay runs straight across the sprite, so with the world-aligned UVs the
    model generator emits it comes out as one continuous band around the module
    on every side face - which is what makes a 3x8x6 lump read as a part with a
    front rather than as a dark brick.
    """
    px = blank()
    fill(px, MODULE)
    rng = Rng(293)
    mottle(px, rng, ((6, 7), (12, 4)))     # unevenly moulded resin
    noise(px, rng, 3)
    # the inlay, sunk into the housing: dark shoulder, lit face, shadow below
    rect(px, 0, 12, S, 13, shade(MODULE, -30))
    for y in range(13, 19):
        rect(px, 0, y, S, y + 1, shade(REDSTONE, 14 - 5 * (y - 13)))
    rect(px, 0, 19, S, 20, shade(MODULE, -26))
    specks(px, rng, 10, -12)
    # moulding pips, one per corner, so the plain faces are not featureless
    for cx, cy in ((6, 6), (26, 6), (6, 26), (26, 26)):
        bolt(px, cx, cy, 2.4, STEEL)
    border(px, 14, -20)
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


# --------------------------------------------------------------------------
# oil shale - the one block of this mod that stands in vanilla stone
# --------------------------------------------------------------------------
# Drawn at 16, not at the 32 the engine castings use. Every neighbour this
# block ever has is a vanilla stone texture, and an ore at twice the texel
# density of the rock around it reads as a sticker on the wall. There is no
# world-aligned-UV argument here either: it is a plain cube_all.
#
# It is also deliberately not drawn as an ore. Ore textures are blobs of a
# mineral scattered on stone; oil shale is a sedimentary rock whose petroleum is
# in the layering itself, so what is drawn is dark horizontal laminae - which
# also makes it unmistakable next to coal ore, the thing it would otherwise be
# confused with at a glance in a cave.
def t_oil_shale():
    px = blank(size=ITEM)
    rng = Rng(613)
    fill(px, SHALE_STONE)
    mottle(px, rng, ((4, 12), (8, 6)))

    # The bands. Each one wanders by a texel as it crosses the sprite, because a
    # perfectly straight line across 16 pixels reads as a drawn stripe rather
    # than as rock; and each wraps, so stacked blocks still line up.
    # Five bands over sixteen rows, no two of them touching once the wobble is
    # applied. Denser than this and the block stops reading as rock with
    # something in it and starts reading as a black block.
    for y0, thickness, tone in ((2, 1, SHALE_DARK), (5, 1, SHALE_MID),
                                (7, 2, SHALE_DARK), (11, 1, SHALE_MID),
                                (13, 1, SHALE_DARK)):
        for x in range(ITEM):
            wobble = 1 if math.sin(2 * math.pi * (x / ITEM) + y0) > 0.45 else 0
            for dy in range(thickness):
                y = (y0 + wobble + dy) % ITEM
                px[y][x] = shade(tone, int(rng.rangef(-8, 8)))

    # A few oil-wet specks: the giveaway that the rock is worth mining.
    specks(px, rng, 10, -26)
    specks(px, rng, 4, 22)
    noise(px, rng, 4)
    return px


# --------------------------------------------------------------------------
# item icons
# --------------------------------------------------------------------------
# Flat 16x16 sprites, for the parts and materials that are too small to read as
# geometry in an inventory slot. Each one is drawn from an explicit row table so
# the silhouette is designed rather than emergent - a spark plug that is not
# instantly a spark plug has failed at the only job an icon has.
def _row(px, y, x0, x1, color):
    """One inclusive run of pixels. The unit every icon below is drawn in."""
    for x in range(max(0, x0), min(len(px[0]), x1 + 1)):
        px[y][x] = color


def _shaded_row(px, y, x0, x1, base, lit=26, dark=-30):
    """A run with a lit left edge and a shadowed right one, i.e. a round body."""
    for x in range(max(0, x0), min(len(px[0]), x1 + 1)):
        if x == x0:
            px[y][x] = shade(base, lit)
        elif x == x1:
            px[y][x] = shade(base, dark)
        elif x == x0 + 1:
            px[y][x] = shade(base, lit // 2)
        else:
            px[y][x] = base


def t_item_spark_plug():
    """The Spark Plug, standing upright.

    Five parts from the top down - brass terminal, ribbed porcelain insulator,
    the spanner hex, the threaded shell, and the electrode with its ground strap
    - because that stack of widths *is* the silhouette. The hex is the widest
    thing in the sprite on purpose: a plug narrowing, flaring and narrowing
    again is recognisable at 16 pixels in a way a plain rod never is.
    """
    px = blank(size=ITEM)
    steel = (150, 156, 165, 255)

    _shaded_row(px, 1, 6, 9, shade(BRASS, 18))          # terminal nut
    _shaded_row(px, 2, 6, 9, BRASS)
    _shaded_row(px, 3, 6, 9, shade(BRASS, -30))         # collar under it

    # Insulator: three ribs, each one texel wider than the body between them.
    for y in range(4, 10):
        rib = y % 2 == 0
        _shaded_row(px, y, 4 if rib else 5, 11 if rib else 10,
                    shade(CERAMIC, 10) if rib else CERAMIC)

    _shaded_row(px, 10, 3, 12, shade(steel, 14))        # spanner hex
    _shaded_row(px, 11, 3, 12, shade(steel, -18))

    for y in (12, 13):                                   # threaded shell
        _shaded_row(px, y, 5, 10, steel)
        for x in range(5, 11, 2):                        # the thread itself
            px[y][x] = shade(steel, -34)

    _row(px, 14, 5, 10, shade(steel, -40))               # shell mouth
    _row(px, 15, 7, 8, shade(steel, 30))                 # centre electrode
    px[15][5] = px[15][10] = shade(steel, -20)           # ground strap
    px[14][7] = px[14][8] = shade(CERAMIC, -6)           # insulator nose
    return px


def t_item_crushed_oil_shale():
    """A heap of crushed rock, wet with the petroleum that is the point of it."""
    px = blank(size=ITEM)
    rng = Rng(881)
    # A heap silhouette: narrow at the top, spreading to the base.
    heap = [(4, 7, 9), (5, 6, 10), (6, 5, 11), (7, 4, 11), (8, 3, 12),
            (9, 3, 12), (10, 2, 13), (11, 2, 13), (12, 3, 12), (13, 4, 11)]
    for (y, x0, x1) in heap:
        _shaded_row(px, y, x0, x1, SHALE_MID, 22, -26)
    # Individual chips, so it reads as crushed rather than as a smooth pile.
    for _ in range(26):
        y = rng.rint(4, 13)
        row = [h for h in heap if h[0] == y][0]
        x = rng.rint(row[1], row[2])
        px[y][x] = shade(SHALE_DARK if rng.rangef(0, 1) < 0.6 else SHALE_STONE,
                         int(rng.rangef(-10, 10)))
    # Two oily glints. Without them this is a pile of gravel.
    px[6][7] = shade(SHALE_STONE, 34)
    px[10][5] = shade(SHALE_STONE, 26)
    return px


def t_item_petroleum_residue():
    """The heavy bottom fraction: a lump of cold tar, glossy and near-black."""
    px = blank(size=ITEM)
    lump = [(3, 6, 9), (4, 4, 11), (5, 3, 12), (6, 2, 13), (7, 2, 13),
            (8, 2, 13), (9, 2, 13), (10, 3, 12), (11, 3, 12), (12, 5, 10)]
    for (y, x0, x1) in lump:
        _shaded_row(px, y, x0, x1, TAR, 30, -18)
    # The gloss is the whole difference between tar and a lump of coal: one
    # small hard highlight high on the left, and a broad dull sheen below it.
    px[4][6] = px[4][7] = (128, 106, 80, 255)
    px[5][5] = px[5][6] = (98, 80, 60, 255)
    px[8][4] = px[9][4] = shade(TAR, 30)
    for x in range(8, 12):
        px[10][x] = shade(TAR, -12)
    return px


def _incomplete(base, marks):
    """A part-built item: the material, roughly formed, with work marks on it.

    Create's own transitional items are recognisably the thing they will become
    but visibly unfinished, and these follow that: a blank of the right metal
    with the tool marks of the step that just happened, never a finished part in
    a different colour.
    """
    px = blank(size=ITEM)
    rng = Rng(1471)
    for (y, x0, x1) in [(y, 3, 12) for y in range(4, 12)]:
        _shaded_row(px, y, x0, x1, base, 24, -28)
        # Jittered here rather than with `noise`, which walks the whole sprite
        # and - because `shade` always returns alpha 255 - would turn the
        # transparent margin around the icon into opaque black.
        for x in range(x0, x1 + 1):
            px[y][x] = shade(px[y][x], int(rng.rangef(-6, 6)))
    for (y, x0, x1) in marks:
        _row(px, y, x0, x1, shade(base, -44))
    # The unfinished corner: a bite out of the blank, so the outline itself says
    # the part is not done.
    for y, x in ((4, 12), (4, 11), (5, 12), (11, 3), (11, 4), (10, 3)):
        px[y][x] = (0, 0, 0, 0)
    return px


def t_item_incomplete_piston_assembly():
    return _incomplete(ALU, [(6, 5, 10), (8, 5, 10), (9, 7, 8), (10, 7, 8)])


def t_item_incomplete_carburetor():
    return _incomplete(BRASS, [(5, 6, 9), (7, 4, 11), (9, 6, 9)])


# --------------------------------------------------------------------------
# fluids
# --------------------------------------------------------------------------
# Both fluids used to be a single flat colour repeated 256 times, with the
# flowing sprite byte-identical to the still one. That is what made a full
# float bowl look like a sticker: real fluid sprites move.
#
# The surface is a sum of sine waves whose wavelengths divide the sprite and
# whose periods divide the frame count, so every frame tiles seamlessly with
# its neighbours in space AND the last frame runs back into the first with no
# jump. Nothing here is random, so the loop is exact rather than nearly exact.
FLUID_FRAMES = 16

# Three petroleum products that have to be told apart in a tank, in a pipe, in a
# float bowl and in a bucket - often two of them side by side. They are
# separated by *value* first and hue second, because value survives being four
# pixels wide and hue does not: pale straw, mid amber-brown, near-black.
#
# Gasoline was a saturated yellow, which read as lemonade rather than as fuel.
# It is now pulled towards straw: less green, less saturation, a paler top end.
GASOLINE_FLUID = ((146, 118, 62), (206, 180, 116), (240, 224, 178))
# Engine oil keeps its darkness but gains warmth, so it is amber-brown rather
# than the olive it was drifting towards - and stays clearly lighter than crude.
ENGINE_OIL_FLUID = ((48, 30, 12), (99, 65, 26), (152, 106, 46))
# Crude is the darkest thing in the mod. Its highlight is barely a highlight;
# what makes it legible at all is the small amount of brown left in it, which is
# also what keeps it from reading as a hole in the world.
CRUDE_OIL_FLUID = ((12, 10, 8), (36, 28, 21), (74, 58, 42))

# (waves along x, waves along y, periods per loop, amplitude, phase)
STILL_WAVES = ((1, 0, 1, 0.50, 0.00), (0, 1, -1, 0.42, 0.31),
               (1, 1, 1, 0.30, 0.61), (2, -1, -1, 0.22, 0.13),
               (-1, 2, 1, 0.17, 0.77))
# Every term drifts the pattern down the sprite (y grows as t grows), which is
# the direction a flowing sprite is expected to travel, plus one standing wave
# across x for the vertical streaking a running film of fuel has.
FLOW_WAVES = ((0, 1, -1, 0.55, 0.00), (0, 2, -2, 0.30, 0.37),
              (1, 1, -1, 0.28, 0.12), (2, 1, -1, 0.20, 0.68),
              (1, 3, -3, 0.14, 0.44), (2, 0, 0, 0.22, 0.05))


FLUID_BANDS = 4   # shades either side of the base tone


def fluid_sprite(palette, waves, frames=FLUID_FRAMES, contrast=1.0):
    """A looping, seamlessly tiling fluid sprite, frames stacked vertically."""
    deep, base, high = (c + (255,) for c in palette)
    px = blank(size=ITEM, height=ITEM * frames)
    peak = sum(w[3] for w in waves) or 1.0
    for f in range(frames):
        for y in range(ITEM):
            for x in range(ITEM):
                v = 0.0
                for (kx, ky, kt, amp, phase) in waves:
                    v += amp * math.sin(2 * math.pi * (
                        kx * x / ITEM + ky * y / ITEM + kt * f / frames + phase))
                v = max(-1.0, min(1.0, v / peak * contrast))
                # Quantised to a handful of shades. A continuous ramp at 16x16
                # reads as a blurry photo next to vanilla water, which is banded
                # - and banding is also what makes the motion legible at all,
                # because the eye follows the edges between bands.
                v = round(v * FLUID_BANDS) / FLUID_BANDS
                px[f * ITEM + y][x] = (mix(base, high, v) if v >= 0
                                       else mix(base, deep, -v))
    return px


def animation_meta(frametime):
    return {"animation": {"frametime": frametime}}


# --------------------------------------------------------------------------
# bucket items
# --------------------------------------------------------------------------
# Vanilla's bucket, redrawn rather than copied: the same pressed pail and wire
# bail every vanilla fluid bucket uses, so a Gasoline Bucket in the hotbar next
# to a Water Bucket reads as the same object holding something else. Only the
# contents differ between the two, which is the whole idea of a fluid bucket.
#
# Rows are (y, x0, x1), both x bounds inclusive, top to bottom.
PAIL = ([(4, 2, 13)] + [(y, 3, 12) for y in range(5, 10)]
        + [(y, 4, 11) for y in range(10, 13)]
        + [(y, 5, 10) for y in range(13, 15)])
# The bail is one texel thick: a rounded shoulder, then a vertical run down the
# flanks to the rim. Sloping it all the way to the rim instead turns the gap
# under it into a clean triangle and the whole item reads as a lantern.
BAIL = ((0, 6, 9), (1, 4, 5), (1, 10, 11), (2, 3, 3), (2, 12, 12),
        (3, 3, 3), (3, 12, 12))
MOUTH = 4                         # the rim row, where the surface is visible
FLUID_TOP, FLUID_BOTTOM = 5, 12   # rows holding contents; below that is the base

STEEL_HI = (208, 212, 218, 255)
STEEL_LI = (176, 181, 189, 255)
STEEL_MI = (143, 148, 156, 255)
STEEL_SH = (105, 110, 118, 255)
STEEL_DK = (68, 72, 79, 255)


def bucket(palette, body=1.0):
    """One filled bucket.

    `palette` is the fluid's (deep, base, high) triple. `body` is how thick the
    contents look, from 0 (thin and clear, like gasoline) to 1 (heavy and opaque,
    like crude), and it is the whole reason three buckets of three different
    dark liquids do not look like three recolours of one sprite:

    * a *thin* fluid is lit right through, so its surface is bright, the sheen
      down the lit wall is strong, and the depth gradient from top to bottom is
      long and smooth - you can see into it;
    * a *thick* fluid stops light at the surface, so the surface is barely
      brighter than the body, the sheen collapses to a single specular pixel on
      the meniscus, and the fluid goes dark immediately below the rim.

    That is a real difference in how the two are drawn rather than a difference
    in tint, which is what makes Engine Oil and Crude Oil - two dark browns -
    distinguishable in a hotbar at all.
    """
    deep, base, high = (c + (255,) for c in palette)
    clarity = 1.0 - body
    px = blank(size=ITEM)

    # --- wire bail. Lit on the left limb, shadowed on the right, so the loop
    # reads as round rather than as two posts.
    for (y, x0, x1) in BAIL:
        for x in range(x0, x1 + 1):
            px[y][x] = STEEL_MI if x < 8 else STEEL_SH
    for x in range(6, 10):
        px[0][x] = STEEL_LI                    # the light catches the top bend

    # --- pail. A left-to-right ramp across every row turns a flat trapezoid
    # into a cylinder: dark rolled edge, highlight, body, shadow, dark edge.
    for (y, x0, x1) in PAIL:
        for x in range(x0, x1 + 1):
            if x in (x0, x1):
                c = STEEL_DK
            elif x == x0 + 1:
                c = STEEL_HI
            elif x >= x1 - 1:
                c = STEEL_SH
            else:
                c = STEEL_LI if x <= x0 + 3 else STEEL_MI
            px[y][x] = c

    # --- rolled rim, and the shadowed underside of the base
    for x in range(3, 13):
        px[4][x] = STEEL_HI if x < 9 else STEEL_LI
    px[4][2] = px[4][13] = STEEL_MI
    for x in range(6, 10):
        px[13][x] = STEEL_MI
        px[14][x] = STEEL_SH
    px[14][5] = px[14][10] = STEEL_DK

    # --- contents.
    #
    # The item is seen from slightly above, so the rim row is the mouth: it
    # shows the surface as a wide band with only the rolled edge either side of
    # it. Everything below is behind the front wall, which is why the window
    # there is two texels narrower on each side. Filling the mouth with metal
    # instead - a bright bar across the full width - is what makes a bucket
    # sprite read as a lantern.
    # How bright the open surface is depends on how far light gets into the
    # fluid: gasoline's mouth is nearly its highlight colour, crude's is barely
    # above its body.
    surface = mix(base, high, 0.35 + 0.55 * clarity)
    for x in range(4, 12):
        px[MOUTH][x] = mix(surface, base, 0.45) if x in (4, 11) else surface
    # One specular pixel on the meniscus. On a thick fluid this is the only
    # thing that says there is a surface at all; on a thin one it is a glint on
    # top of an already-lit mouth.
    px[MOUTH][6] = mix(surface, high, 0.55 + 0.35 * body)

    # The window narrows with the taper on its own, because it is derived from
    # the silhouette rather than hard-coded.
    #
    # Depth: a thin fluid darkens gradually over the whole window, a thick one
    # is already at its deep tone one row under the rim. `t` is how far down the
    # window a row is; raising it to a power greater than one for a thick fluid
    # is what front-loads the darkening.
    spans = {y: (x0, x1) for (y, x0, x1) in PAIL}
    rows = FLUID_BOTTOM - FLUID_TOP
    falloff = 1.0 - 0.6 * body
    for y in range(FLUID_TOP, FLUID_BOTTOM + 1):
        x0, x1 = spans[y]
        t = ((y - FLUID_TOP) / rows) ** falloff
        for x in range(x0 + 2, x1 - 1):
            if y == FLUID_TOP:
                c = mix(base, high, 0.15 + 0.45 * clarity)   # lit by the mouth
            else:
                c = mix(base, deep, 0.15 + 0.6 * t)
            if x == x0 + 2:
                c = mix(c, high, 0.10 + 0.26 * clarity)      # sheen, lit wall
            elif x == x1 - 2:
                c = mix(c, deep, 0.42)
            px[y][x] = c
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
    "block/control_module": t_control_module,
    "block/combustion_flash": t_combustion_flash,
    "block/oil_shale": t_oil_shale,
    "item/spark_plug": t_item_spark_plug,
    "item/crushed_oil_shale": t_item_crushed_oil_shale,
    "item/petroleum_residue": t_item_petroleum_residue,
    "item/incomplete_piston_assembly": t_item_incomplete_piston_assembly,
    "item/incomplete_carburetor": t_item_incomplete_carburetor,
}

# Sprite name -> (pixels, animation frametime or None). Still fluids idle;
# the flowing ones run at vanilla water's pace.
FLUIDS = {
    "block/gasoline_still": (GASOLINE_FLUID, STILL_WAVES, 3, 0.85),
    "block/gasoline_flow": (GASOLINE_FLUID, FLOW_WAVES, 2, 1.0),
    "block/engine_oil_still": (ENGINE_OIL_FLUID, STILL_WAVES, 4, 0.7),
    "block/engine_oil_flow": (ENGINE_OIL_FLUID, FLOW_WAVES, 3, 0.85),
    # Crude is the slowest and flattest of the three - 6000 viscosity against
    # gasoline's 600, and the sprite says so. The higher contrast is not a
    # brighter fluid, it is the only way a near-black sprite shows any motion.
    "block/crude_oil_still": (CRUDE_OIL_FLUID, STILL_WAVES, 6, 1.0),
    "block/crude_oil_flow": (CRUDE_OIL_FLUID, FLOW_WAVES, 4, 1.15),
}

# name -> (palette, body). See `bucket`: body is what makes three dark liquids
# read as three different substances rather than three tints.
BUCKETS = {
    "item/gasoline_bucket": (GASOLINE_FLUID, 0.1),
    "item/engine_oil_bucket": (ENGINE_OIL_FLUID, 0.6),
    "item/crude_oil_bucket": (CRUDE_OIL_FLUID, 1.0),
}


def write_meta(path, meta):
    with open(path, "w") as f:
        json.dump(meta, f, indent=2)
        f.write("\n")


if __name__ == "__main__":
    for name, fn in TEXTURES.items():
        write_png(os.path.join(OUT, name + ".png"), fn())
        print("wrote", name + ".png")

    for name, (palette, waves, frametime, contrast) in FLUIDS.items():
        png = os.path.join(OUT, name + ".png")
        write_png(png, fluid_sprite(palette, waves, contrast=contrast))
        write_meta(png + ".mcmeta", animation_meta(frametime))
        print("wrote", name + ".png", f"({FLUID_FRAMES} frames)")

    for name, (palette, body) in BUCKETS.items():
        write_png(os.path.join(OUT, name + ".png"), bucket(palette, body))
        print("wrote", name + ".png")
