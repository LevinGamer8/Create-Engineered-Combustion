# Assets

Every texture and every model in this mod is generated. Nothing under
`src/main/resources/assets/` is hand-edited: the engine's parts interlock across
block boundaries, so a coordinate that moves in one model has to move in three
others, and hand-editing 44-element JSON drifts. Edit the generator, re-run it,
commit what it wrote.

| | |
| --- | --- |
| Textures, fluid sprites, bucket and item icons | `tools/generate_engine_textures.py` |
| Every block and item model | `tools/generate_engine_models.py` |
| Recipes, tags, loot tables, worldgen | `tools/generate_survival_data.py` |
| Static checks over what those wrote | `tools/check_models.py` |
| Offline rasteriser for looking at it | `tools/preview_engine.py` |
| Engine audio (unrelated, untouched) | `tools/generate_sounds.py` |

```
python3 tools/generate_engine_textures.py
python3 tools/generate_engine_models.py
python3 tools/generate_survival_data.py
python3 tools/check_models.py          # exits non-zero on any finding
```

`generate_survival_data.py` is under `data/` rather than `assets/` and is a
generator for a different reason from the other two: not because coordinates
interlock, but because **the balance numbers must only exist once.** A yield
that appears in two hand-written recipe files drifts. Every number a balance
pass would touch is a named constant at the top of that file.

Both generators are deterministic - the noise runs off a fixed-seed LCG - so
re-running them reproduces the committed assets byte for byte, and a diff that
is not empty means something really changed.

## Resolutions

Four, each because of what the sprite sits next to.

**Blocks are 32x32**, two texels per model unit. The model generator emits
world-aligned UVs, so that ratio is what puts element boundaries on texel
boundaries no matter how a part is cut up.

**Fluid sprites are 16 wide**, because the fluid renderer, Create's tanks and
the carburetor's sight window all draw them at block scale next to vanilla
water. They are animated: 16 frames stacked vertically with a `.mcmeta`
alongside. The frames are built from sine waves whose wavelengths divide the
sprite and whose periods divide the frame count, so a sprite tiles seamlessly
with itself in space *and* runs from its last frame back into its first with no
jump.

**Items are 16x16**, vanilla's own item resolution. The buckets are drawn on
vanilla's bucket silhouette - pressed pail, wire bail, and the mouth showing
the surface of whatever is inside - so a Gasoline Bucket in the hotbar next to
a Water Bucket reads as the same object holding something else. What separates
the three fluid buckets from each other is the `body` parameter rather than a
tint: see `bucket()`, and [milestone 8](milestone-8.md).

**Oil Shale is 16x16**, unlike every other block texture here. Every neighbour
it ever has is a vanilla stone texture, and an ore at twice the texel density of
the rock around it reads as a sticker on the wall. The world-aligned-UV argument
that puts the castings at 32 does not apply either - it is a plain `cube_all`.

## The two model invariants

`tools/check_models.py` re-derives both of these from the written JSON rather
than trusting the generator, and fails if either is broken. They exist because
neither is visible in a JSON diff and both are very visible in game.

**No co-planar overlap.** Two quads that face the same way, sit in exactly the
same plane and overlap in area give the depth buffer nothing to order them
with, so the pair shimmers as the camera moves. On the parts a block entity
renderer turns - crankshaft, connecting rod, flywheel - the geometry itself
moves, so the shimmer becomes a flicker that follows the engine's speed. The
generator's `separate` pass colours a graph of contested quads and steps the
losers 0.02 units out of the plane: a fortieth of a texel, and enough for the
depth buffer.

**No buried quads.** A face that another element of the same model completely
covers can never be seen and is still stitched into the chunk mesh. The
generator's `cull` pass drops them, conservatively - only when one single
element both stands on the face's outward side and spans it - because a wrong
cull punches a hole in a model.

The checker applies the first rule twice: once inside each model, and once over
the *assembled* engine, because half of this mod's geometry deliberately crosses
block boundaries and a contested plane does not have to live inside a single
model to flicker. Three of them did: the crankshaft's main journals against the
crankcase's outer walls, and the cylinder's intake flange and stud bosses
against the carburetor sitting on top of them.

It also checks that every texture a model declares exists on disk, that every
`#ref` a face uses is declared, that no UV runs off its sprite, and that every
blockstate variant points at a model that is there.

## Looking at it

`tools/preview_engine.py <dir>` loads the real models and the real textures,
applies the same transforms the block entity renderers apply, and writes images
of the assembled engine from three angles at four crank angles. It is not a
Minecraft client and it does not prove anything about how the game will stitch
the atlas - but it is how the geometry gets looked at at all in an environment
that cannot resolve NeoForge, and a broken model is obvious in it.
