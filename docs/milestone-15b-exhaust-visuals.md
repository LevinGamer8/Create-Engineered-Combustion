# Milestone 15B follow-up — the exhaust side, where the gas goes, and what it is made of

The in-game test said the four-stroke runs and the R4 recording works, and that
three things were still missing: there was **no exhaust**, the intake and exhaust
flow could not be read, and the engine still looked like placeholder blocks.

**No four-stroke timing, physics, fuel, wear or audio was changed.** Every number
the simulation runs on is where it was. What follows is geometry, materials, one
client-side effects class and the checks that hold them.

---

## 1. The exhaust side

The +Z flank was kept clear for exactly this. It now carries, on every Cylinder:

| Part | What it is | Why it looks like that |
| --- | --- | --- |
| Header flange | a dark iron plate over the head's machined exhaust face, slightly smaller | the machined face shows round it as a gasket line, so it reads as bolted on |
| Header | one heavy cast elbow per cylinder, out of the port and down | two crossed boxes, so it reads as a round pipe rather than a slab |
| Log collector | a fat pipe low along the fin band, the length of the engine | lower and heavier than the intake rail, which sits clean and high over the rockers |
| Seam joints | half a temper-coloured collar at each seam | two sections meet as one bolted joint, like the intake rail and the cam bearings already do |
| End cap | on the negative end of the run | a log that ends, not one that was cut off |
| Stack | one short upright pipe at the **positive end** - the flywheel end | away from the Carburetor, which is where hot pipe goes on any engine carrying fuel on the other side |

**An inline-1 gets all of it.** It is an engine whose positive end is its only
cylinder, so it has the same header, a stub of the same log and the same stack as
the end of an inline-4. Every layout therefore has one outlet, always in the same
place relative to the flywheel - and the question "where do the gases go" has the
same answer on all four.

The stack is **short on purpose**: its mouth is level with the rocker shaft, which
is enough to say "the gas leaves here" and not enough to turn a small engine into
a chimney. The mouth is a lip round an open recess with a soot floor - a pipe you
look down into, not a capped post. It is clamped to the head by a strap, so it is
carried by the engine rather than standing on the log alone.

### The bound

The exhaust stands up to **2.75** units proud of the +Z face. That is the same kind
of exception the valve gear has on the other flank, granted for the same reason -
one run from first port to outlet, carried on every head - and bounded the same
way: `check_models.py` lets only the four Cylinder variants use it. A Carburetor,
sump or plug reaching over the exhaust side still fails.

### Clearances it threads

None of this touches the oil sump, the carburetor or the spark plug: the
carburetor's furthest +Z part is at 10.3 and the plug's at 8.75, both inside the
block, and the sump hangs a whole block below the lowest exhaust part. The
flywheel is beyond the block in X and the stack is inside it.

---

## 2. Telling the valves apart

Both valves have to stand on the intake half of the head - both are worked by
pushrods on the intake flank - so position alone could never say which was which.
Four things now do:

1. **The guides are different materials.** The intake guide is the clean alloy of
   the intake manifold and stands on the intake port it opens. The exhaust guide is
   the dark iron of the exhaust side, with a temper-coloured cap.
2. **The exhaust valve has a visible way out.** A raised cast passage runs from
   beside its guide straight across the head to the exhaust boss. It threads a gap:
   the rocker swings no wider than x 11.9, the plug's hex runs 11.15..12.65, the
   carburetor's bowl comes down to 17.0 over x 5.2..10.8 - so the passage leaves on
   the guide's +X side and stays beyond 12.75 past the plug.
3. **The valves are two models.** `intake_valve_*` in bright steel, and
   `exhaust_valve_*` with its spring and retainer in the straw-to-blue temper
   colours a real exhaust valve wears within hours of running. Same geometry, so
   the rocker lands on both identically.
4. **The motion was already right**, and is unchanged: lift is still
   `ValveTiming` straight off the authoritative cycle angle.

---

## 3. Flow, in two layers

`EngineFlowEffects`, held by the controller like `CombustionAudio` and driven from
the same combustion bits.

### Normal play

**One small grey puff from the stack per charge that really burned**, on that
cylinder's exhaust stroke. A puff needs both halves:

- a combustion event from the server for that cylinder - the same bit that
  consumed the fuel, delivered the torque and lit the chamber flash; and then
- that cylinder's own exhaust valve coming off its seat, read from `ValveTiming`
  at its own place in the cycle.

So it happens on the exhaust stroke after a real power stroke and on no other. A
motored engine burns nothing and never puffs; an engine that runs dry stops one
exhaust stroke after its last bang; an engine fast enough to cross the whole
exhaust window inside a tick still puffs, a tick late, never invented. Above two
thirds throttle a puff carries a second particle - the engine is working.

**Nothing on the intake.** A real intake is not visible from outside.

### Teaching layer — Engineer's Goggles

The general-purpose debug toggle the brief asked about turned out to exist
already: the Goggles are this mod's "tell me more" switch everywhere else. With
them on:

- a **cool blue** trace beside each intake valve while it is open. This one needs
  no combustion: an engine being turned over really does draw air, and "air moving,
  no exhaust heat" is exactly the diagnosis of an engine turning and not firing;
- a **warm orange** trace at each cylinder's header while it vents a charge it
  really burned.

### Cost

At most one outlet emission per engine per tick - cylinders venting on the same
tick are one puff - and one or two particles in it. The teaching traces are
strided so each cylinder emits on one tick in three. Vanilla already drops
particles more than 32 blocks from the camera and honours the particle setting.

### One constant, checked

The stack's mouth is authored in the generator (`EXHAUST_OUTLET`) and the puff is
emitted from `EngineFlowEffects.OUTLET_*`. `check_models.py` now checks both that
they agree and that the written models really have a soot-floored recess under
that point. Proved by breaking: moving `OUTLET_Y` to 23 in the Java alone reports
the generator's value against it.

---

## 4. Materials

| Texture | Change |
| --- | --- |
| `intake_manifold` | **new.** Clean, light, cool cast alloy with a mould parting line. The intake rail, runner, collars and the intake valve guide. |
| `exhaust_manifold` | **new.** Darker and warmer than the block, patched with rust scale, sooted toward the bottom of every face. The roughest casting on the machine. |
| `exhaust_steel` | **new.** Temper colours - grey-straw to bronze to blue - for every fitting on the hot side and the exhaust valve's spring and retainer. Desaturated on purpose: a first pass read as brass. |
| `exhaust_soot` | **new.** The stack's mouth. |
| `cylinder_head` | **lighter and finer-grained**, because a head is the part machined all over, and drawing it in the block's dark iron was what merged head, barrel and crankcase into one mass. |
| `crankshaft` (crankcase) | oil weeping from the deck joint, and darker patches where it has pooled. |
| `oil_sump` | the dirtiest part, as it should be: weeping streaks from the pan gasket, patches, and a sheen at the top of the wet band. |

Brass is unchanged and still only where it already earned its place. Nothing was
made shinier.

---

## 5. Coherence

The engine now has three horizontal lines on one flank - cam, pushrod gallery,
rocker shaft - and on the other one heavy one: the exhaust log, with a header
dropping into it from every cylinder. That is what stops a multi-cylinder engine
looking visually incomplete from the side that used to be bare, and the two sides
now read as two halves of one machine: **cool and clean where the air goes in, hot
and dirty where it comes out.**

The previewer renders a fourth view per layout, `exhaust_3q`, from the exhaust side
three-quarters on.

---

## 6. Ponder

The four-stroke scene changed in one step and gained two effects:

- the intake step shows the cool trace at the intake valve;
- the exhaust step's text now says the burnt gas **leaves by the exhaust**, points
  at the stack, and shows the warm trace at the header and smoke from the stack.

These are timed by the narration, which is what a lesson is. In the world they are
only ever driven by the engine's own cycle and real combustion. `PonderEngine`
gained `intakeValve`, `exhaustPort` and `exhaustOutlet`, and `validate_ux.py`
knows all three are on a Cylinder. Every other scene shows the exhaust by
construction - the geometry is the Cylinder's own block model.

---

## 7. Also fixed

`check_models.py`'s asset-reference check built model names with the platform's
path separator, so on Windows every blockstate reported a missing model. It now
uses POSIX paths and passes on both.

---

## 8. What is still unverified

No Minecraft was launched. Everything above is measured from the model JSON, the
render maths, and the offline rasteriser. In particular nobody has yet seen:

- whether the puff reads as exhaust and not as a stray smoke particle, and whether
  it is visible enough in daylight (V23-V24);
- whether the goggles' traces are visible at all, and not too strong (V27-V28);
- how the new textures sit under Minecraft's real lighting and ambient occlusion,
  which the rasteriser does not model (V22, V30);
- the Z-axis engine, which is covered by the same transform the spark particle has
  always used, but has not been looked at (V21);
- the Ponder particles (P2).

Those are V19-V30 and P2 on the acceptance checklist.
