# Milestone 15B follow-up — the Camshaft, the timing drive, and being able to see them

The first real R1 test says the four-stroke works and the Camshaft does not read.
This is the pass that fixes the presentation, and the two clashes it turned up
on the way.

**No four-stroke timing was changed.** Every number the simulation runs on is
where it was. Everything below is geometry, materials, and four new checks.

---

## 1. The item

It was a journal with **eight lobe plates** on a 3.2 pitch and a flat box on the
end. At inventory scale that is a comb next to a brick: each plate is about one
pixel, the box has no teeth, and nothing about it says camshaft.

What it is now, left to right: a **timing gear** with eight teeth in bright
steel on a cast web, a steel retaining nut on the shaft nose, a machined shaft,
**three** widely spaced lobes with noses pointing three different ways, and the
rear journal.

Three lobes rather than eight, because the item is an **icon** before it is a
drawing. The installed shaft still grows the two lobes each cylinder actually
has; the item is the part, not the parts list.

### Transforms, audited

| Context | Before | Now | Why |
| --- | --- | --- | --- |
| `gui` | 20/200, scale 1.05 | **30/225**, scale 1.05 | The old comb had to be turned nearly side-on to show any stagger. A shaft with a wheel on the end reads best three-quarters on, which is where every other part in this mod reads best. |
| `ground` | inherited 0.25 | **0.4**, lifted | `block/block` drops items at quarter scale. Right for a cube; a shaft becomes a four-unit sliver in the grass. |
| `fixed` | inherited 0.5 | **0.62, turned 235** | An item frame shows the model face-on at the default rotation, which for a part authored along X is dead end-on: a frame would show a gear and nothing else. |
| held (first/third person) | inherited | **inherited** | Every other engine part inherits them, a shaft carried like a rod reads fine at 75/45, and one part posing differently in the hand would be the odd thing rather than the fixed one. |

**1.05 is measured, not chosen.** A full block item at the standard pose covers
about 26 units of screen; the Camshaft at 1.05 and 30/225 covers 26.0. Bigger
and the gear's teeth are cropped by the slot.

### Materials

Cast iron for the gear web and the lobe bodies, machined steel for the teeth,
the shaft and the lobe tips. **No brass**: a brass collar was tried and it lands
square on the gear's face at every pose the icon is drawn at, so it reads as a
gold boss rather than as a fitting, and a camshaft has no brass on it anywhere.
The three materials are the ones the Crankshaft is drawn in.

---

## 2. Item and installed are one part

They are built by the **same two functions** - `cam_gear_elements` and
`cam_lobe_elements` - at the same proportions, in the same three materials. That
is the whole mechanism against drift: there is no second description of what a
camshaft looks like to get out of step.

What the installed shaft adds is what a real one has: a lobe pair per cylinder,
journals, bearing caps, and the timing gear on the end of the engine's first
section only.

### Where the noses point is not decoration

Each lobe is authored so that when the shaft has turned to that valve's
peak-lift cam angle, its nose is pointing straight up at the follower. Those
angles come from `CamshaftTiming.lobeAngleDegrees`, not from taste: the intake
window is centred on cycle 630 and the exhaust on 450, which halve to cam 315
and 225 — a quarter turn apart, always, because the two windows are always 180
cycle degrees apart.

Verified by probing the rotated geometry against the lift function:

```
cycle  intake lift   lobe@x5 top    exhaust lift  lobe@x11 top
 450      0.00          1.45           1.00          2.31     <- exhaust nose up
 630      1.00          2.31           0.00          1.45     <- intake nose up
```

`check_models.py` now asserts it (§5 below).

---

## 3. The timing drive, which did not exist

The 2:1 between crank and camshaft is the defining visible fact of a four-stroke
and there was **nothing in the world showing it**.

It cannot be a gear on the crankshaft meshing with a gear on the camshaft, and
the arithmetic is worth writing down so nobody tries again:

- the crankshaft runs down the middle of its block, at (8, 8);
- the camshaft has to be out on the intake flank, at (4.5, −0.9), or it cannot
  be seen at all, which was the whole complaint;
- that is **9.56 units apart**, and a meshing pair spanning 9.56 at 2:1 needs a
  camshaft gear **12.7 units across** — most of a block, hanging seven units out
  into whatever the player built beside the engine.

So it is what a real engine has. The crankshaft turns a **drive gear** inside
the case; that gear meshes with the camshaft's own, which is twice its diameter
and therefore turns at half.

| | radius | axis | turns at |
| --- | --- | --- | --- |
| drive gear | 1.5 | (9.0, −0.9) | crank angle, **negated** |
| cam gear | 3.0 | (4.5, −0.9) | cam angle, which is half the cycle |

Negated because meshing wheels turn in opposite senses, and the one place a
player looks is where the teeth meet. Its *speed* is still exactly the crank's,
which is what a gear geared 1:1 to the crankshaft through the case turns at.

Both live in a **timing pocket** — a machined opening at the free end of the
controller's crankcase, which the casting and the cam cradle are cut back for,
so no turning gear passes through anything. One per engine, at the end opposite
the flywheel, which is where a timing case goes.

---

## 4. Being able to see the camshaft at all

It was in a housing with a **cap running the length of the section**, so from
anywhere above the horizontal a player saw the lid and never the shaft. Three
changes:

- the cap is cut back to the **bearings that need one** — the two section ends
  and one between the lobes — which is what an overhead-valve engine with its
  cam cover off looks like, and which leaves the lobes open to the sky;
- the **gallery is machined out** where the lobes swing and left at full depth
  between, so the flank still reads as a casting rather than as a channel;
- the lobes are **eccentric enough to read**. The tip radius is now derived,
  `CAM_LOBE_R = CAM_BASE_R + VALVE_LIFT`, because a follower rises by exactly
  the difference between base circle and nose.

That last one was also a bug: at the old numbers the lobe grew 0.25 while the
pushrod moved 1.1, so **the follower floated clear of the nose at full lift**.

---

## 5. Two clashes, and the checks that would have caught them

Both were invisible in every existing check, because each model is fine on its
own and the overlap only exists for part of a revolution.

**The lobes swept through the crankcase.** A lobe turns a circle of `CAM_LOBE_R`
about an axis 0.9 units outside the block; the crankcase's own lower rail is
inside that circle. At every angle a nose pointed inboard it was inside the
casting. Fixed by the machined gallery above.

**And through the ignition switch.** Which sat on that rail, in the band the
exhaust lobe swings in. It moves to the widest piece of solid casting outside
both lobe bands.

Four checks added, in `check_models.py`:

| Check | What it catches |
| --- | --- |
| `check_moving_reach` | how far a part reaches **after the renderer has translated it onto its real axis**. The static rule cannot see this at all: a model authored about the block centre is inside its block by construction, wherever it actually ends up. |
| `check_lobe_phase` | each lobe's nose against the cam angle its valve peaks at. Turns the geometry 360 times and finds where it stands highest. |
| `check_cam_sweep` | the camshaft's swept circle against the static casting, for the eccentric parts only — a journal inside its bearing is round about the axis and belongs there. |
| `check_shared_constants` | the numbers that live in the generator, `EngineValvetrain.java` and `preview_engine.py` at once. The generator's header has always said to change them together; this makes it a build failure. |

Each was proved by breaking the thing it checks: swapping the two nose
directions produces four explicit failures naming both lobes and by how many
degrees, and changing `VALVE_LIFT` in the Java alone reports the generator's
value against it.

---

## 6. The top end

Three fixes, all about legibility.

**One brass point on the valve gear**, and it is the pad the pushrod lifts.
Valve springs, both rocker pads, the pushrod cup and the plug's terminal used to
put half a dozen gold points on one head with no hierarchy between them. The pad
over the valve is the contact that matters mechanically and is also the one
nobody sees — it sits over the bore, on the far side of the head from the flank
the valve gear runs down. The pad on the pushrod end is on the side the player
is standing, and watching it rise while its valve falls is the linkage
explaining itself. Valve springs are steel, which is what a valve spring is.

**The rocker now reaches its valve.** The swing was 10 degrees, which over the
5.4 units from pivot to valve lifts the pad 0.95 while the valve falls 1.1 — a
sixth of the travel short of the thing it is pressing. It is 11.5 now, which is
`atan(lift / arm)`.

**Per-section cam phase.** Each section draws its slice of the shaft at its own
cam angle, exactly as the crankshaft already draws its throw at its own crank
angle. Drawing every section at the master angle put an inline-4's four lobe
pairs at one clock position while their four pushrods moved a quarter cycle
apart — a mechanism that cannot exist. It was invisible on an R1, which is why
it lasted.

---

## 7. Ponder

**Nothing needed changing.** The Camshaft step shows
`new ItemStack(ECItems.CAMSHAFT.get())` being right-clicked onto the Crankshaft,
so it renders the live item model and the live block models. The redesigned part
appears in it by construction, and no structure or scene references geometry by
anything but identity.

---

## 8. What is still unverified

No Minecraft was launched. Everything above is measured from the model JSON, the
render maths, and an offline rasteriser that applies the same transforms the
renderers do — which is enough to catch a lobe pointing the wrong way and not
enough to say how it looks. Whether the gears read as gears, whether the pocket
reads as a timing case, and whether the whole thing still merges into a dark
mass at Minecraft's own lighting are V-items on the acceptance checklist.
