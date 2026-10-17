# Milestone 15B follow-up — ports you can see into, one oil pan, and a Z engine that is not a mirror

The in-game look at the exhaust pass said the engine still read as symbols: an
intake with no opening, an exhaust bolted to a blank face, valves standing beside
the pipes they were meant to open, and an inline-4 with one deep sump under one
bay and a strip under the other three.

**No physics, timing, firing order, fuel, wear, audio or advancement changed.**
Everything below is geometry, two textures, one renderer offset, and the checks
that hold it. The one renderer change fixes an existing bug (section 6).

---

## 1. The head, cut open

The path of the charge is now built as **cavities subtracted from the castings
round them**: a new `subtract()` in the generator cuts named boxes out of the
head, the port casting, the flanges and the exhaust boss, and what is left is the
casting with real holes in it. Each cavity gets a thin lining on its solid walls -
clean cool shadow (`intake_port`) on the intake side, `exhaust_soot` on the
exhaust side - standing 0.1 inside the cavity so it covers the casting's face
rather than fighting it. **The depth is geometry; the colour only confirms it.**

### Intake: runner → inlet → plenum → throat → seat

| Part | Where it is | What it is for |
| --- | --- | --- |
| Inlet | a recess in the head top under x 6.6–9.6, z 1.2–3.8 | exactly where a multi-cylinder runner lands and where an inline-1's Carburetor sits, so the air visibly goes **into** the head |
| Plenum | under the inlet | the passage turns |
| Throat | from the plenum across to the intake valve at x 5, z 5.6 | the passage ends **at the valve** |
| Seat | a two-unit hole in the chamber roof, with a steel ring on the passage floor | the valve's head sits in it |
| Window | a framed opening in the intake port's outer wall, with a machined flange standing proud of it | where the player looks in |

Through the window you see the dark passage, the intake valve's stem standing in
it, and the seat ring on its floor.

### Exhaust: seat → pocket → trough → mouth → header

| Part | Where it is | What it is for |
| --- | --- | --- |
| Seat and pocket | round the exhaust valve at x 11, z 5.6, roofed over by its guide | the valve sits at the start of the passage |
| Trough | an **open-topped**, sooted channel across the head at x 9–11, from the pocket to the exhaust face | the one valve whose gas has to cross the whole head now visibly does |
| Mouth | a recessed tunnel through the boss and the machined face | the port opening |
| Header | its nose pushed **into** the mouth with a dark gap round it; the flange is a frame round the opening | the header is connected to a passage, not touching a wall |

The exhaust header, studs and boss moved to centre on the port (x 10 instead of
8), and the log's back end moved with them.

**Clearances these thread**, all measured: the plug's hex starts at x 11.15
between z 7.25 and 8.75, so the trough stays at x ≤ 11.0 and the pocket stops at
z 7.0. The pushrods are at z −1.35…−0.45, so the window's flange stands no further
out than z −0.15. An inline-1's Carburetor sits on the same inlet a runner would.

### Valves

Each valve now has its **head**: a 1.8-unit disc that sits in its seat flush with
the chamber roof and drops out of it as the valve opens. Seen from the port it
closes a hole; seen through the barrel's window it comes down into the chamber
over the piston. The exhaust valve's head is in the heat-tinted steel, like its
spring. `check_models.py` swings both against the piston through the whole cycle
at the simulation's own lift. Measured at a tenth of a degree, the gap between a
valve head and the crown **never drops below 0.5** - the clearance the chamber has
at top dead centre with both valves shut - because the raised-cosine profile
opens a valve no faster than the piston falls away from it.

### The chamber

The chamber roof is lined in soot with both seats cut through it. Between the
crown, the two seats, and the plug's electrode and strap coming through the same
roof, the chamber is the one place where piston, valves and plug visibly meet.

---

## 2. One oil pan

The pan is **part of the crankcase now**. Every section carries its own length at
full depth (down to `PAN_BOTTOM = −6.5`): a flange a little wider than the block
rail with a bolt line, a tapered body, cast ribs on the crank-web pitch, and a
stiffening rib the length of the engine. It runs across every seam exactly as the
deck, the rail and the gallery already did: from −0.5 on a joined section, from
0.5 at the free end, always to 15.5. So:

- R1: a pan under one section;
- R4: **one pan four bays long, the same depth everywhere, with no gap.**

The **Oil Sump block is the deep bowl** bolted under one bay of that pan. It holds
the lip, the bowl tapering to a drain plug, its ribs, the dipstick climbing the
flank outside the pan's flange to a handle at the rim, and an oil feed up to the
crankcase's gallery, which runs past every bearing of every bay. One installed
tank, one service point, and visibly one pan that every crank bay drains into.

**No block state was needed.** The pan stays wholly above `PAN_BOTTOM` and the bowl
wholly below it apart from its lip, so where a sump is fitted the two meet at one
plane and nothing is drawn twice. The gameplay is unchanged: still one Oil Sump
per engine, still at the accessory section.

The new `oil_pan` texture is dark cast steel, grimed at the flange line and along
the bottom edge. The Oil Sump's item shows the bowl under a length of pan, which
is what it is. The Crankshaft's item leaves its pan length off, or the icon would
be pushed out of the top of the slot.

---

## 3. Block coherence

The head's lower slab now runs to the block boundary on a linked side, like the
fins, so the heads of an R4 meet as one long casting. With the continuous pan,
rail, deck and gallery underneath, the shared intake rail, rocker shaft and
exhaust log above, and the seams still visible, an R4 reads as one cast engine
assembled from sections.

---

## 4. Without the goggles

Every element of the brief's list is now geometry:

| | How you tell |
| --- | --- |
| intake side / exhaust side | light alloy rail, framed window, valve gear / dark iron, sooted trough, log, stack |
| intake path | inlet under the runner or Carburetor → window shows the passage → valve stem in it |
| exhaust path | sooted trough from the exhaust guide across the head → mouth → header → log → stack |
| oil pan | one dark pan under every section |
| valves | each stands in its own passage, with a head in a seat |

The goggles' traces moved with the geometry. The intake trace now starts in the
intake window and drifts inward. The exhaust trace starts at the header, now
x 10. `check_models.py` pins the Java's `EXHAUST_PORT_X` to the generator's
header line.

---

## 5. Ponder

- **Four-stroke scene:** the intake trace starts at the new `intakePort` (the
  window), replacing the old point beside the valve guide.
- **Assembly scene:** "The Oil Sump bolts under the oil pan as its deep bowl, and
  supplies lubrication."
- **Inline scene:** one new step after "one crankshaft, one Carburetor, one Oil
  Sump…". It outlines the whole crank run and the one Oil Sump: "The oil pan runs
  under every section. The one Oil Sump is its deep bowl." It points at the
  dipstick.

German for all three.

---

## 6. The X/Z bug, found by building the previewer a Z engine

A Z engine's **baked** blocks are turned by the blockstate's `"y": 90`, which carries
(x, z) to (16 − z, x): the intake flank goes from −Z to **+X**. The **partials** a block
entity draws are not turned by anything, so each has a `_z` model. For the whole
valve gear that model was built by `transpose`, which is a **mirror**: it carries the
same flank to **−X**. And the renderers translated the parts onto the mirrored side.

So since the valve gear went in, a Z engine had its camshaft, pushrods, rockers,
valves and timing gears on the **opposite flank** from the cradle, tunnels, rocker
shaft and guides they belong to. The crank, rod, piston and flywheel never showed
it: each is symmetric about its swing plane, where a mirror and a quarter turn
agree.

Fixed in three places that have to agree:

- the generator builds every asymmetric `_z` part with `rotate_y90`;
- `EngineValvetrain.flankOffset` negates the across-engine offset onto X for Z,
  which is that same quarter turn applied to a translation;
- `CrankshaftRenderer` (camshaft, timing gear) and `CylinderRenderer` (rockers)
  use it. Pushrods and valves only translate along the run, so they were already
  right once their models were.

**How it was proved.** `preview_engine.py` can now assemble an engine along Z the
way the game does: baked models turned by the blockstate rule, `_z` partials with
the renderer's offsets. A Z engine seen from a camera turned back a quarter must
give the X engine's picture. Comparing them with flat, per-material colour (so the
world-fixed light does not count):

| | pixels that differ (520×420) |
| --- | --- |
| old partials and old offsets, R1 | **6,788** - the whole valve gear on the wrong side |
| now, R1 and R4 at five angles and crank positions | **2 – 47** - edge rasterisation |

`check_models.py` now asserts the model half for every `_x`/`_z` pair: the `_z`
boxes must be the `_x` boxes turned. Putting the old mirrored `pushrod_z.json`
back fails it.

---

## 7. Validators

Added to `check_models.py`. They check topology and placement against the
generator's own named cavities and constants, never against coordinates written
out in the checker:

| Check | Fails when |
| --- | --- |
| `check_ports` | either passage is blocked anywhere from its opening to its valve's seat; a seat has no floor round it or a port no roof; a cavity has no lining; no header reaches into the exhaust mouth; a multi-cylinder runner does not land on the inlet |
| `check_oil_pan` | any bay's pan is a different depth; an R4's pan has a gap anywhere along it; the crankcase's pan reaches below `PAN_BOTTOM`; the bowl is not deep; nothing crosses the pan floor (a bowl hanging with a gap) |
| `check_valves_clear_piston` | a valve head meets the piston at any point of the cycle |
| `check_z_is_turned` | any `_z` model is not its `_x` model turned a quarter |
| `check_exhaust_outlet` (extended) | the Java's warm-trace X is not the generator's header line |

Each was proved by breaking: a block in the intake throat, a missing length of
pan, a shallower pan bay, the old mirrored pushrod. Each failed with a message
saying what and where.

---

## 8. What only Minecraft can answer

No Minecraft was launched. Everything above was measured from the model JSON and
from the offline rasteriser, which does not model Minecraft's lighting or ambient
occlusion. In particular:

- whether the intake window and the exhaust trough are **visible enough** at the
  head's height, which is usually above a standing player's eye (V31, V33);
- whether the dark linings read as depth or as flat patches under real lighting
  (V31–V33);
- whether a valve head is visible dropping into the chamber through the barrel's
  window, at Minecraft's light inside a cutaway (V34);
- whether the continuous pan and the bowl read as one pan with a deep part
  (V35–V36);
- **a Z-axis engine with a Camshaft**, which has never been looked at in game and is
  the one place this pass changed what a renderer does (V37).
