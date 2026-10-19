# Milestone 15B — the last broad visual pass: primary form, silhouette, proportion

The geometry-realism pass made the engine *correct*: real ports, valves in their
passages, one pan, a Z engine that is not a mirror. The in-game screenshots said it
still did not look like an engine. It read as a tall cage of bars and repeated
modules. That is a problem of large shapes, not of detail, so this pass changes
large shapes and adds no new small ones.

**Nothing in gameplay changed:** no physics, timing, wear, fuel, audio, ownership,
block layout or recipe. No Java changed at all. Every change is in the model
generator and the checks.

Before/after renders, current model on the left, this pass on the right, are in
[`docs/renders/milestone-15b-silhouette/`](renders/milestone-15b-silhouette/):
R1 front, side, three-quarter from both flanks; R4 side, three-quarter from both
flanks, and from below. They were used to judge each change before keeping it. One
change, windows on all four faces of the barrel, was reverted because the
render showed it rebuilding the cage at an R1's corners.

---

## 1. Why R1 read as a tall, narrow tower

Three things, in order of how much they contributed:

1. **The cylinder had no body.** Four corner columns and four full rings of fins,
   with open windows between all of them on all four faces. The piston was very
   visible and the *cylinder* was not: the eye read a stack of horizontal bars a
   block tall, which is the "tower" impression. The pushrod gallery's ribs,
   tunnels and collars added a second set of bars in front of it.
2. **The bottom was a separate tank.** A flat-sided pan with a 14-unit-deep bowl
   under it, in a different material, carried the silhouette almost a full block
   further down than the working parts needed.
3. **The head was narrower than the barrel**, so the top end read as parts stacked
   on a post rather than as a casting that caps the cylinder.

The stack itself, Oil Sump / Crankshaft / Cylinder / Carburetor, is the block
layout, and this pass must not change it. So the height has to be won from what
each block *draws*, and the width from how the masses step outwards towards the
bottom.

---

## 2. Cylinder

`barrel_elements`: **one cast body round the bore**, walls two units thick, with one
large window in each **flank** that the piston and rod are seen through. The cut
edges of the windows show the wall's thickness, which is what makes a window read
as a section rather than as a gap between bars.

- Fins, corner columns, and the pushrod gallery's ribs, tunnels and collars are
  gone. The pushrods stand free in front of the barrel's wall, guided by one cast
  foot rail along the engine and by the rockers.
- The ends are **solid**. Windows on the ends as well left an R1's barrel as four
  corner posts. That was tried, rendered, and reverted.
- Linked sides run to the block boundary, so an R4's barrels are **one long
  cylinder block** with a window over each bore. The seams remain where the faces
  meet.
- A base flange a little wider than the barrel, bored through, stands on the
  crankcase deck.

## 3. Head

Heavier and wider: the lower slab now overhangs the barrel on every side (0.6 to
15.4, running to the boundary on a linked side), and the upper deck is nearly as
wide (1.6 to 14.4). It still contains the chamber roof, both ports, the plug's
seat, the guides and the rocker pedestals, and is still cut by the same named
cavities. The exhaust boss and its machined face grew upwards to take the larger
port mouth.

## 4. Crankcase

The **cam side is closed behind the crank.** Both flanks used to be open, so the
crank was seen *through* the engine and out the other side, which is what a cage
looks like. A wall now stands just inboard of the cam's sweep on the intake flank.
The crank is seen through the exhaust-side window against the inside of its own
case. The intake side shows what it is for: the camshaft, the timing gears and the
pushrods' feet. On the controller section the wall is cut round the timing pocket.
`check_cam_sweep` still passes, so no lobe or gear reaches it.

## 5. Cutaway windows

Every window is now a hole in a casting with visible wall thickness:

- the barrel's flank windows (§2);
- the crankcase's exhaust-flank window, backed by the closed cam side (§4);
- the intake port's window (§6).

Nothing is kept visible by a frame of bars any more.

## 6. Intake visibility

- The window is now the full width of the passage (x 3.6–9.6) and its full height
  (y 14.5–16.9).
- Its flange stands 0.4 proud, which is as far as the pushrods passing in front
  allow, and is wider all round.
- The runner is now a round-section pipe in two crossed boxes. It lands on the
  inlet, and `check_ports` still asserts that.

## 7. Exhaust visibility

- The port mouth is larger (3.2 × 2.5 against 2.4 × 2.1).
- The header's flange is twice as thick, so it has a silhouette of its own.
- The header's nose is larger and still enters the mouth with a dark sooted gap
  round it.
- The studs moved onto the thicker flange.

## 8. Manifolds

- **Intake:** the rail is a round-section pipe, slightly larger, light alloy, high
  over the rockers. Each runner is a round-section branch down to its inlet.
- **Exhaust:** the log moved **up to head height** (y 11.0–13.2, from 8.4–10.6).
  The headers are short and heavy, as on a real engine. The log no longer crosses
  the middle of the barrel's window, and on an R4 it draws one heavy dark line
  along the top of the block.

## 9. Oil pan

A real taper. The flange runs the **full width of the block**. Below it, four steps
each come a unit in for 1.4 down, roughly the angle of a pressed pan's side, to a
floor less than half the flange's width. Every bay has the same profile, so on an
R4 the steps draw unbroken horizontal lines the length of the engine. The vertical
ribs and the separate stiffening rib were removed: the taper is the pan's detail.
The bolt line moved to the flange's outer edge.

## 10. Deep sump

The bowl **is the pan's taper carried on down** under one bay:

- its top step is exactly the pan's floor, the same width;
- every step below comes in by the same amount, in X as well as Z;
- it is in the **pan's own material**.

So the pan's walls simply keep falling under that bay. It stops at y 4.3 in its
block, where it used to reach 1.2, which takes about three units off the R1's
silhouette. The dipstick and the oil feed leave the bowl's side and climb the
flank through the edge of the pan's flange, as a dipstick tube does.

---

## 11. R1, before and after

| | Before | After |
| --- | --- | --- |
| first read | a tower of horizontal bars | a cylinder: one barrel, one window |
| top | parts stacked on a post | a head that caps the barrel |
| middle | the crank seen through the engine | the crank seen in its case |
| bottom | flat dark slab, separate tank | a pan whose walls fall into one deeper bowl |
| height | to y −15 under the crankcase | to about −12 |

## 12. R4, before and after

Before: four finned towers side by side over a crankcase. After: **one long
cylinder block** with four windows, one head line, one heavy exhaust log along the
top, one light intake rail above the rockers, and one tapered pan the length of the
engine flowing into a single bowl. The section seams are still visible. The R4 side
and three-quarter renders are where the difference is largest.

## 13. Movement and clearance

Every moving part is unchanged. The new castings are checked against all of them:

- **Piston:** `check_chamber` sweeps the piston against every cylinder variant.
  The bore is cut through the whole barrel and its base flange.
- **Connecting rod (new check):** `check_rod_clear` swings the rod exactly as
  `CylinderRenderer` does, sampled at every box's corners, edge midpoints and face
  centres, against the cylinder and crankcase castings. It caught a real fault
  during this pass: the base flange had first been bored only from y 1.0, which
  would have put the rod through solid metal at the bottom of every stroke.
- **Camshaft and timing gear:** `check_cam_sweep` sweeps them against the new
  closed cam-side wall.
- **Valves and ports:** the valve-head sweep and the port topology checks are
  unchanged and pass at the new dimensions.

## 14. X and Z

All the changed geometry is in baked block models, which the blockstate turns, so
it rotates with the engine by construction. No partial changed. `check_z_is_turned`
still passes. The previewer's Z engine, seen from a camera turned back a quarter,
still matches the X engine: **157 differing pixels over five views** at 520×420,
all edge rasterisation, against 6,788 for the old mirrored valve gear.

## 15. Validators

Kept all the geometry-realism checks unchanged in strength:

- intake and exhaust connectivity;
- valve clearance;
- oil-pan continuity, same depth in every bay, one bowl;
- X/Z rotational equivalence.

Two adjustments:

- the bowl may now meet the pan's floor exactly rather than overlap it, because
  it continues the pan's taper;
- `check_rod_clear` was added.

## 16. What only Minecraft can answer

No Minecraft was launched. The rasteriser does not do Minecraft's lighting, ambient
occlusion or distance fog, so:

- whether the solid barrel reads as cast metal or as a flat grey box at
  Minecraft's light. Its texture is the same draft-marked casting as before, and
  now covers much larger faces (V38);
- whether the closed cam-side wall makes the crankcase interior too dark to see
  the crank through the exhaust-side window (V40);
- whether the pan's steps read as a taper or as stripes (V41);
- the overall first impression from several blocks away, which is the actual
  acceptance test (V38, V42).
