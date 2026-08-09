# Milestone 4 - the engine looks like an engine

A pure model/visual-architecture pass. No gameplay system was added, removed or
retuned: gasoline, oil, starting, RPM, stress, sound, HUD, component detection
positions, save data and the Create kinetic integration are all untouched.

The previous pass improved textures. The remaining problem was not texture
detail - it was silhouette, proportion and mechanical continuity. Four blocks
placed near one another still read as four blocks.

## The one idea: ignore block boundaries

The engine is separate blocks for gameplay reasons. The models are no longer
allowed to stop at those seams.

```
 world Y   (measured from the bottom of the Crankshaft block)

  48 ┬ ── carburetor block top
     │      carburetor body, hung low and to the intake side
  34 ┤      cylinder head intake flange  ──┐  the carb bolts onto a flange
  32 ┼ ── carburetor / cylinder boundary ──┘  that belongs to the head
     │      head casting, rocker step reaching up past the seam
  30 ┤      combustion chamber face
     │      finned barrel + cutaway windows, piston travelling inside
  17 ┤      cylinder mounting flange
  16 ┼ ── cylinder / crankshaft boundary  ──┐  the flange lands *inside* the
     │      machined crankcase deck (to 16.5) ┘  deck, not on top of a seam
  13 ┤      crankcase cavity: crank, webs, counterweights, rod big end
   3 ┤      oil pan / lower crankcase, tapered, drain plug
   0 ┴ ── ground
```

Every joint between blocks interlocks by at least half a unit rather than
butting. A scan of all 17 models reports **zero** pairs of exactly coplanar
touching faces, so nothing z-fights - inside a model or across a block seam.

## Crankcase and crankshaft

The Crankshaft block is now two things:

* **Baked (static)** - the *crankcase*: tapered oil pan with a drain plug, two
  cut-away side walls, two end walls carrying the main bearing housings, and a
  machined top deck with a slot for the connecting rod.
* **Partial model (rotating)** - the *crankshaft*: two main journals, two crank
  webs, two counterweights per web and one **offset crank pin**, drawn by the
  new `CrankshaftRenderer` and rotated about the block centre.

The crank pin sits 3 units off the main journal axis, so the throw visibly
orbits rather than a straight shaft spinning inside a box. The whole rotating
assembly is dimensioned against the cavity: its worst-case swept radius is
**4.87** units against a 5.0 limit, so no part of it ever pokes through a wall.

Both crank webs use a darker forged texture than the connecting rod on purpose -
they overlap constantly in the crankcase window, and matching greys would read
as one lump.

## Connecting rod

New, and the single biggest legibility win. It is part of the Piston Assembly,
so the cylinder draws it - it only exists when a piston is installed.

The rod model hangs straight down with its small end at the centre of its block.
The renderer translates by the wrist pin's travel, which puts the small end on
the wrist pin, and then `rotateCentered` pivots about exactly that point. No
translate-rotate-translate dance and nothing to drift.

### Slider-crank

Gameplay keeps the sinusoidal `CrankMath.pistonPosition` it has always had, so
combustion timing and the goggle readout are bit-identical. The *renderers* use
exact geometry:

```
r = 3.0    crank radius        (1/16 blocks)
l = 14.5   connecting rod length

wrist pin y   = axis - r*cos(theta) + sqrt(l^2 - r^2*sin^2(theta))
rod swing     = asin(r*sin(theta) / l)
```

`sin(swing) = r*sin(theta)/l` is precisely the condition that the big end lands
on the crank pin, so the rod stays attached at both ends by construction.
Checked numerically every 15 degrees, for both engine axes: the rod's big end
and the crank pin coincide to **0.0 units**.

Because the rod is finite, the piston now dwells slightly longer near bottom
dead centre than near top - the asymmetry a real engine has and a sine wave
does not.

## Piston

Twelve boxes forming an octagonal section: crown with a turned face, a modelled
ring groove, a ring land and a tapered skirt, in machined aluminium rather than
the old gold plate. It clears the bore by 0.35 units and the corner columns by
0.35, so it never clips. Bottom dead centre leaves it 0.5 clear of the crankcase
deck; top dead centre leaves 0.5 of squish under the head.

## Cylinder

Rebuilt as a cutaway air-cooled barrel: mounting flange with bolts, four heavy
corner columns, **three cooling fin rings** with their corners cut off so the
silhouette is octagonal rather than a stack of square slabs, and open windows
between the columns on all four sides.

Windows on all four faces is a deliberate compromise: the Cylinder has no axis
blockstate property (adding one would touch block state and save data, which
this pass is not allowed to do), so it cannot know which way the crankshaft
below runs. Four windows means the mechanism is visible whichever way the engine
was placed.

The fins' side faces take the whole height of the fin texture, which is what
gives each one a lit top edge and a shadowed root.

## Cylinder head

Visual only - no valves, no spark plug, no exhaust gameplay. The top of the
Cylinder model is a heavier casting with a stepped upper section, four studs, a
combustion-chamber face, an **intake port** turning up on the north side into a
machined flange, and an **exhaust boss and flange** on the south side ready for
a future exhaust.

## Carburetor

Its gameplay position is unchanged - still `cylinder.above()`. Its *model* no
longer fills that block: the body is pulled down and toward the head's intake
side, so it reads as a carburetor bolted to the engine rather than a box parked
on the cylinder. Mounting flange, throat with a venturi waist, air horn, float
bowl with a brass clamp ring, brass fuel inlet, throttle arm and rod, and an
idle screw. Dark cast body; brass is an accent only.

## Oil sump

The redesign was originally authored against a branch with no Oil Sump block, so
it carried a pan inside the crankcase. Merging the oil and lubrication work
brought a real `oil_sump` block at `crankshaft.below()`, and the pan now lives
where it belongs.

* The **Crankshaft** block ends in a machined, bolted joint face - no pan of its
  own, so the engine can never show two.
* The **Oil Sump** is the lower half of the crankcase: a wide bolted top flange,
  a pan tapering down in three steps, flange bolts and a drain plug.

The two interlock rather than butting. The sump's top flange runs 0.8 units *up*
into the crankshaft block and is wider than the crankcase's joint face, so the
crankcase's underside disappears inside it. Assembled, they read as one
crankcase with a removable pan; separately, each still looks finished.

## Running indicator

The lubrication work added a `lit` blockstate on the Crankshaft. The rebuilt
crankcase carries it as a tell-tale lamp on the lower rail of *both* side walls,
so it reads from either flank, with a cast bezel around an amber lens.
`crankshaft.json` and `crankshaft_lit.json` are generated from one element list
with only the lens texture swapped, so the two can never drift apart.

The lamp's outward faces take the whole lamp texture instead of the
world-aligned slice every other face uses - a 3x2.4 boss would otherwise sample
a corner of the sprite and never show the lens at all.

## Flywheel

The block's baked model is now empty - every part of a flywheel turns, so
leaving the shaft in the chunk mesh would have left one visibly stationary piece
of the output side. Rim, four spokes, hub **and** the through shaft are all in
the partial model and all driven by the crank angle.

15.5 units across in a 16 unit block, 3 units thick: large diameter, narrow
axially, with a heavy octagonal rim, a bolt circle on the face and a machined
hub. Still exactly centred on the crankshaft axis, still a
`HorizontalAxisKineticBlock`, so Create connectivity is unchanged.

## Materials

| role | used for |
| --- | --- |
| dark cast iron | crankcase, cylinder barrel, fins, head, oil sump |
| darkest cast iron | flywheel |
| machined grey | crankcase deck, head flanges |
| forged steel | crank webs and counterweights (darker), connecting rod (lighter) |
| bright machined steel | journals, crank pin, bolts, output shaft |
| aluminium | piston |
| brass | carburetor fittings only - never a whole component |

Eighteen textures at **32x32**, two texels per model unit, consistent across
every component. UVs are written explicitly and world-aligned, so the concentric rings
on the flywheel face and the piston crown line up across the elements they span,
and parts that stick out of their block cannot index off the edge of their
sprite in the atlas.

## Animation

Everything that moves reads the same authoritative crank angle:
`EngineState.getRenderCrankAngleDegrees(partialTicks)`.

| part | drawn by | transform |
| --- | --- | --- |
| crankshaft, webs, pin, journals | `CrankshaftRenderer` | rotate about block centre |
| piston | `CylinderRenderer` | translate by wrist pin travel |
| connecting rod | `CylinderRenderer` | same translate, then pivot about the wrist pin |
| flywheel + output shaft | `EngineFlywheelRenderer` | rotate about block centre |

No animation timer exists anywhere. Reverse rotation works because every formula
is a pure function of the angle, and the angle itself goes backwards when the
engine is turned backwards. A stopped engine keeps whatever angle it stopped at -
the value is persisted - so nothing snaps to a default pose.

## Model budget

| model | elements |
| --- | --- |
| cylinder | 34 |
| crankcase (static, x2 for lit) | 20 |
| flywheel wheel | 14 |
| crank assembly (rotating) | 13 |
| piston | 12 |
| carburetor | 11 |
| oil sump | 9 |
| connecting rod | 6 |

The rotating parts are cached `SuperByteBuffer`s, so per-frame cost is a buffer
copy and a transform, not a re-bake. Detail comes from silhouette and texture
rather than from cube count.

## Tooling

The assets are generated, because the parts interlock across block boundaries
and hand-editing 34-element JSON drifts:

* `tools/generate_engine_textures.py` - the 32x texture set
* `tools/generate_engine_models.py` - every block and item model
* `tools/preview_engine.py` - an offline rasteriser that loads the real models
  and textures, applies the same transforms the renderers apply, and writes
  images of the assembled engine from any angle at any crank angle

Both generators are deterministic; re-running them reproduces the committed
assets byte for byte.

`tools/generate_sounds.py`, from the audio work, is untouched and unrelated - it
writes the `.ogg` files, not models. The earlier `tools/generate_models.py` was
removed in the merge: two generators writing the same JSON files means whichever
ran last silently won, which is exactly how model sets drift apart.

## Merge note

This pass and the oil/lubrication/audio work happened on two branches at once
and both touched the visual layer. They were reconciled by keeping **all** of
the gameplay from the oil/audio side and **all** of the visual layer from this
side, then extending this side to cover the two things it had never seen: the
Oil Sump block and the `lit` indicator. The superseded visual assets from the
other branch - `crank_throw_x/z`, `crankcase.png`, `crank_steel.png`,
`cylinder_bore.png`, the flat `item/piston_assembly.png` - were dropped rather
than left orphaned.
