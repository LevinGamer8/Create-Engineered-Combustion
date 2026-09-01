# Milestone 15A.2 — Valvetrain and the visible four-stroke

**Status: design and prototype only. `src/main/` is untouched.** Milestones 15A
and 15A.1 froze what the engine *does* every 720 degrees. This one answers what
the player **builds and sees**.

Companion document: `milestone-15-four-stroke-design.md`. Everything frozen there
— the cycle representation, the stroke boundaries, R1–R4 firing — is taken as
given here and is not re-argued.

---

## 1. What the engine is today, measured

Every spatial claim below was read out of the shipped models and code, not
assumed. These are the constraints the valvetrain has to live inside.

### The parts a player actually installs

| Part | Kind | Per | Mandatory? |
| --- | --- | --- | --- |
| Crankshaft | block | section | yes |
| Cylinder | block | section | yes |
| **Piston Assembly** | **item into Cylinder** | cylinder | yes (structural) |
| **Spark Plug** | **item into Cylinder** | cylinder | yes to fire, not structural |
| Flywheel | block | engine | yes (structural) |
| Carburetor | block | engine | yes to fire |
| Oil Sump | block | engine | no — runs dry, more friction |
| Air Filter | item into Carburetor | engine | no |
| Redstone Control Module | item into Crankshaft | engine | no |

> **The mod has already decided this question once, and wrote down why.**
> `ECItems`, on the Air Filter: *"The engine already stands five blocks tall, and
> a full block for every bolt-on part would make it unbuildable in a normal room.
> An air cleaner is also genuinely a part of the carburetor rather than a machine
> beside it, so an item that installs into one is both the smaller and the more
> honest model."* Four parts follow that rule already. A valvetrain is the same
> kind of thing, and the burden of proof is on any design that breaks the pattern.

`EngineComponents.isMechanicallyValid()` requires only: layout COMPLETE, every
section carrying a Cylinder with a Piston Assembly, exactly one Flywheel. The
Carburetor, Oil Sump and Spark Plugs are deliberately outside it.

### The three volumes, and what is in them

```
   y+2   [ Carburetor ]   ── above ONE cylinder only
   y+1   ( contested air )
   y     [ Cylinder  ]    ── head protrudes UP to y≈17.8; plug to y=21
   y-1   [ Crankshaft ]   ── full 16³ block, interior swept by the crank
   y-2   [ Oil Sump   ]   ── below ONE section only
```

| Volume | Occupied by | Free? |
| --- | --- | --- |
| **Inside the crankcase** | crank assembly sweeps **y 3.4–12.6**, window is **y 2.04–12.8** | **no** |
| **Above the cylinder head** | head to y≈17.8, spark plug to **y=21**, carburetor y 1–9.8 of that block | **no** |
| Cylinder outer flank | cooling fins, ±Z port bosses | partly |
| Crankcase outer flank | side walls z 0.52–2.48, cutaway window between them | **yes, above deck** |

Two findings do most of the work in this document:

1. **The crankcase interior cannot show a camshaft.** The crank assembly's swept
   volume is y 3.4–12.6 and the cutaway window is y 2.04–12.8. There is 0.2 of a
   pixel of clearance above the crank and nothing below. An in-case camshaft
   would be invisible or interpenetrating.
2. **The block above the head is already contested.** The spark plug reaches
   y=21 — five units *into* the block above the cylinder — and the Carburetor
   occupies y 1–9.8 of that same block, so the two already overlap slightly on
   whichever cylinder carries the carburetor. There is no clean volume up there
   for a camshaft.

### The head, and the two ports it already has

`cylinder.json` carries two bosses on the head, and they are not symmetric:

| Boss | Extent | Size | Reading |
| --- | --- | --- | --- |
| **−Z** | x 5.4–10.6, z 0–4.6, y 13.8–17.6 | larger, with a deck cap | **intake port** — it faces the Carburetor |
| **+Z** | x 6–10, z 11.6–15.98, y 13.8–16.4 | smaller | **unused — the natural exhaust port** |
| Spark plug | x 11.15–12.65, z 7.25–8.75, y 13.6–21 | tall, +X side | must stay clear |

**The head is already a crossflow head.** Intake one side, exhaust the other,
plug between them. Nothing needs inventing; sections 20 and 21 are answered by
what is already modelled.

### One existing wrinkle, flagged and not fixed

`blockstates/cylinder.json` has a **single variant with no axis property**, while
`crankshaft.json` rotates `y: 90` for `axis=z`. So the cylinder's ±Z port bosses
and its +X spark plug are **fixed in world space regardless of which way the
engine is built**. On a Z-axis engine the ports already point at the neighbouring
cylinders.

This is a pre-existing issue, not one this milestone introduces, and fixing it is
a production model change that is out of scope here. It matters because it sets a
rule for the valvetrain: **anything axis-dependent must be drawn by the block
entity renderer, not by the static model.** The renderer can already do this — it
picks `CONNECTING_ROD_X` or `CONNECTING_ROD_Z` today.

---

## 2. How much valvetrain should the player build?

### The three candidates, costed

Interaction counts are placements plus item installs for a complete *running*
engine (Crankshaft, Cylinder, Piston, Plug, Flywheel, Carburetor, Oil Sump).

| | R1 today | R4 today | R1 after | R4 after | R4 growth | **new per-cylinder interactions on an R4** |
| --- | --- | --- | --- | --- | --- | --- |
| **A — Full modular** | 7 | 19 | **11** | **32** | **+68 %** | **+12** (8 valves, 4 cam sections) |
| **B — Semi-modular** | 7 | 19 | **8** | **20** | **+5 %** | **0** |
| **C — Integrated** | 7 | 19 | 7 | 19 | +0 % | 0 |

### A — Full modular: rejected

Installing eight valves into an inline-4 is the exact failure section 17 of the
brief names: *"I right-clicked sixteen tiny mandatory service items into
identical sockets."* Worse, it is **regressive** — it grows the R4 by 68 % and
the R1 by only 57 %, taxing hardest the layout the player worked hardest for, at
precisely the moment the game should be rewarding them. And it buys nothing the
other options do not: a valve the player installs behaves identically to a valve
that is simply there.

Individual valves also fail the mod's own test. A valve is not "a part that plugs
into a machine" in the sense the Air Filter is; it is a feature of a cylinder
head, inseparable from it, and no engine has ever been sold without them.

### C — Integrated: rejected

It costs nothing and it teaches nothing. The **2:1 crank-to-cam ratio is the
single defining fact of a four-stroke engine** — it is *why* a cylinder fires
every other revolution — and Create's philosophy is that the important
relationships in a machine should be things you can see and build, not things
that happen. An integrated valvetrain would animate perfectly and mean nothing,
and it forecloses the timing failure mode in section 15.

### B — Semi-modular: **chosen**

> ### The player installs **one Camshaft** into any one Crankshaft section. That is the whole addition.
>
> Valves, pushrods and rocker arms are part of the Cylinder, appearing with the
> Piston Assembly. The timing gears are part of the Camshaft.

| Question | Answer |
| --- | --- |
| Gameplay | +1 interaction, whatever the engine's size |
| Create philosophy | one visible, installable component carries the one idea that matters |
| Realism | a camshaft *is* one shaft serving the whole engine; valves *are* part of a head |
| Visual satisfaction | a shaft, gears, pushrods and rockers all begin moving from one install |
| Assembly complexity | R1 7→8, R4 19→20 |
| Ponder discoverability | one part to introduce, one relationship to teach |
| Maintenance potential | the drive gears can carry wear later, like crankcase bearings do |
| R1 vs R4 build time | **identical cost** — the R4 penalty is zero |
| Future upgrades | a hotter cam is a variant of one item, not eight |
| Implementation | one installable flag on the controller, one renderer path |

**Why one Camshaft for the whole engine rather than one per section.** The mod
already has parts that serve an entire engine from one position: the Carburetor
sits *above any one cylinder*, the Oil Sump *below any one section*, and there is
exactly one Flywheel. A camshaft is that kind of part — a single shaft spanning
the run — and making it per-section would both contradict the existing pattern
and reintroduce the repetition this option exists to avoid. Material cost still
scales with engine size through the parts that genuinely scale: crankcases,
cylinders, pistons and plugs.

**Why the timing drive is not a second item.** Two mandatory one-per-engine parts
that are *always installed together* is a socket, not a system — the same
busywork in miniature. A camshaft that is not driven is not a thing that exists.
The gears are rendered, and the 2:1 is taught by watching them and by Ponder,
which is where teaching belongs.

---

## 3. Where the camshaft goes: side cam, pushrods, rockers

> ### Side camshaft in a gallery along the crankcase flank, pushrods up the cylinder, rocker arms on the head. **OHV, not OHC.**

```
SIDE VIEW — R1, engine built along X, viewed from −Z

                    ╭──────────╮
                    │ CARB     │            y+2
                    ╰────┬─────╯
        ╔═══════════╗    │                  y+1  (spark plug reaches up here)
        ║  ROCKERS  ║◄───┼── rocker arms pivot on a shaft parallel to the crank
     ┌──╨───────────╨──┐ │
     │ ▓ INTAKE  EXH ▓ │ ●  ← spark plug (+X column, kept clear)
     │ ▓   valves    ▓ │ │
     │  ┌───────────┐  │ │                  y    CYLINDER
     │  │  piston   │  │ ║  ← pushrod, outside the fins, full stroke visible
     │  │▬▬▬▬▬▬▬▬▬▬▬│  │ ║
     │  │    rod    │  │ ║
     └──┴───────────┴──┘ ║
     ┌─────────────────┐ ║
     │  ╭───────────╮  │ ║                  y-1  CRANKCASE
     │  │ crank ◯   │  │ ╠══ CAMSHAFT — gallery on the outer flank, deck height
     │  ╰───────────╯  │ ║   (turning at half crank speed)
     └─────────────────┘
     ┌─────────────────┐
     │    OIL SUMP     │                    y-2
     └─────────────────┘
```

```
TOP VIEW — R4 along X.  ONE camshaft, ONE gallery, four cylinders.

        −Z  ← intake side (Carburetor, shared intake manifold)
     ╔══════════════════════════════════════════════════════╗
     ║  ▄▄▄▄▄▄▄     ▄▄▄▄▄▄▄     ▄▄▄▄▄▄▄     ▄▄▄▄▄▄▄         ║  intake ports
     ║ ┌───────┐   ┌───────┐   ┌───────┐   ┌───────┐        ║
     ║ │ CYL 1 │●  │ CYL 2 │●  │ CYL 3 │●  │ CYL 4 │●       ║  ● = spark plug (+X)
     ║ └───────┘   └───────┘   └───────┘   └───────┘        ║
     ║  ▀▀▀▀▀       ▀▀▀▀▀       ▀▀▀▀▀       ▀▀▀▀▀           ║  exhaust ports
     ╚══════════════════════════════════════════════════════╝
        +Z  → exhaust side (RESERVED — no block here yet)

     ╞═════╤═══════════╤═══════════╤═══════════╤═════╡
     │GEARS│  ▲▲       │    ▲▲     │      ▲▲   │     │   ← CAMSHAFT, one shaft,
     │ 2:1 │  lobes    │   lobes   │   lobes   │     │     8 lobes, continuous
     ╘═════╧═══════════╧═══════════╧═══════════╧═════╛     across all sections
       ↑
    driven off the crank at one end of the run
```

### Why not OHC

| | OHC | **Side cam + pushrods** |
| --- | --- | --- |
| Space needed | the block above every head | the crankcase flank |
| Is that space free? | **no** — head to y≈17.8, plug to y=21, carburetor overlaps | yes, above deck height |
| Aesthetic | modern automotive | **early industrial — the correct idiom** |
| Visible motion | a shaft turning, mostly hidden under a cover | **tall vertical pushrod travel, plus rockers** |
| Readable at player height | poor — you look at it from below | **excellent — pushrods run the full cylinder height** |
| R1→R4 scaling | fine | fine |
| Model complexity | a cover per cylinder | one gallery, two rods and two rockers per cylinder |

Section 4 of the brief asks explicitly for something that is not *"a modern
plastic-covered automotive engine"*. An overhead cam under a cam cover is exactly
that. A side camshaft driving exposed pushrods and rockers is what an engine of
this era and this cutaway aesthetic actually looks like — and it is also the only
one of the two that has anywhere to live.

The pushrods are the real prize. A camshaft turning is a subtle motion seen
end-on; **a pushrod rising and falling the height of a cylinder is legible from
across a room**, and there are two per cylinder moving at different times. That
is a large gain in "this machine is doing something" for very little geometry.

---

## 4. The camshaft, exactly

```java
camAngle(cycleAngle) = cycleAngle / 2        // [0, 720) -> [0, 360)
```

**Zero-point convention:** cam 0 is engine cycle 0, which by the frozen convention
is cylinder 1 at the **start of its compression stroke** — bottom dead centre,
both valves just seated. A deliberately observable zero: pause an engine at cam 0
and every valve is shut with cylinder 1 at the bottom of a fresh charge.

> **There is no camshaft clock.** Every value in `CamshaftTiming` is a pure
> function of the engine's authoritative `CyclePosition`. Nothing is integrated,
> stored or ticked. A camshaft that advanced its own angle would be a second
> clock, and two clocks in one engine drift — across a reload, a chunk unload, a
> controller change, a skipped tick. A camshaft that is *division* cannot.

Tested: 7200 crank degrees produces exactly 10 camshaft revolutions; a violently
shaken engine's cam matches a fresh engine placed at the same angle to four
decimals; 500 forward-and-back rocks leave the cam exactly where it started.

### Lobes

**Two per cylinder — eight on an inline-4 — and their positions are derived, not
authored:**

```java
lobeAngle(cyl, valve) = camAngle( normalize720( windowCentre(valve) - cyclePhaseOffset(cyl) ) )
```

| Cylinder | Intake lobe | Exhaust lobe |
| --- | --- | --- |
| 1 | 315° | 225° |
| 2 | 225° | 135° |
| 3 | 45° | 315° |
| 4 | 135° | 45° |

The four intake lobes sit at 45 / 135 / 225 / 315 — **90 cam degrees apart, which
is the 180 crank degrees of the firing interval.** A player looking along the
camshaft is looking at the firing order. Tested: every lobe peaks exactly when its
own valve peaks, for every cylinder of every layout.

---

## 5. Valve timing and lift

### Windows — simple, no overlap

| Stroke | Cycle angle | Intake | Exhaust |
| --- | --- | --- | --- |
| COMPRESSION | 0–180 | shut | shut |
| POWER | 180–360 | shut | shut |
| EXHAUST | 360–540 | shut | **open** |
| INTAKE | 540–720 | **open** | shut |

Overlap is deliberately omitted. Real engines open before the dead centre and
close after it, and that is worth modelling *only* once there is gas flow to
model through it — which there is not, and which sections 11 and 30 of the
four-stroke brief put out of scope. The window is stored as an angle plus a
duration rather than as a stroke, so adding overlap later is a change to two
numbers and not to the shape of anything.

> **One invariant worth naming.** The intake opens at exactly
> `FourStrokeCycle.ARMING_ANGLE_DEGREES` — the angle at which a cylinder becomes
> able to fire. That is not a coincidence to be maintained by hand; it is the
> same fact stated twice. **A cylinder can fire because it has just inhaled.**
> The test asserts the two constants are equal.

### The lift curve

```java
lift(t) = (1 - cos(2*pi*t)) / 2          // t = progress through the open window
```

A raised cosine, chosen for one property above all: **both its value and its slope
are zero at each end.** A valve that snapped open would read as a flickering block
rather than a mechanism; one that reached zero with a non-zero slope — a triangle,
or a half-sine — would visibly kink as it met the seat. This meets the seat the
way a cam does.

| Property | Measured |
| --- | --- |
| Range | never leaves [0, 1] over 7200 samples |
| Peak | exactly 1.0, at mid-stroke (intake 630°, exhaust 450°) |
| Continuity | largest change over 0.1° is **0.0017** of full lift |
| Seating | exactly 0.0 at both boundaries |
| Departure from the seat | 0.000076 at 0.5° in — a gentle roll-off, not a corner |
| Overlap | **zero** — 14 400 samples, never both open |

Rocker ratio is modelled as **1:1**, so pushrod travel equals valve lift. Real
engines use about 1.4:1; here the two ends of the rocker move together, which is
simpler to model and, more importantly, simpler to *read* — a pushrod rising one
unit drops a valve one unit, and the linkage explains itself. A separate
`pushrodLift` method exists so a ratio can be introduced later without touching a
renderer.

---

## 6. The layouts, validated

Generated from the prototype. `I`/`E` are valve lifts, `p` is piston position
(0 = BDC, 1 = TDC).

### R4 — firing order 1‑3‑4‑2

| Cycle | Cam | Cylinder 1 | Cylinder 2 | Cylinder 3 | Cylinder 4 |
| --- | --- | --- | --- | --- | --- |
| 0 | 0° | COMPRESSION I0 E0 p0.00 | POWER I0 E0 p1.00 **FIRE** | INTAKE I0 E0 p1.00 | EXHAUST I0 E0 p0.00 |
| 90 | 45° | COMPRESSION I0 E0 p0.50 | POWER I0 E0 p0.50 | INTAKE **I1.00** E0 p0.50 | EXHAUST I0 **E1.00** p0.50 |
| 180 | 90° | POWER I0 E0 p1.00 **FIRE** | EXHAUST I0 E0 p0.00 | COMPRESSION I0 E0 p0.00 | INTAKE I0 E0 p1.00 |
| 270 | 135° | POWER I0 E0 p0.50 | EXHAUST I0 **E1.00** p0.50 | COMPRESSION I0 E0 p0.50 | INTAKE **I1.00** E0 p0.50 |
| 360 | 180° | EXHAUST I0 E0 p0.00 | INTAKE I0 E0 p1.00 | POWER I0 E0 p1.00 **FIRE** | COMPRESSION I0 E0 p0.00 |
| 450 | 225° | EXHAUST I0 **E1.00** p0.50 | INTAKE **I1.00** E0 p0.50 | POWER I0 E0 p0.50 | COMPRESSION I0 E0 p0.50 |
| 540 | 270° | INTAKE I0 E0 p1.00 | COMPRESSION I0 E0 p0.00 | EXHAUST I0 E0 p0.00 | POWER I0 E0 p1.00 **FIRE** |
| 630 | 315° | INTAKE **I1.00** E0 p0.50 | COMPRESSION I0 E0 p0.50 | EXHAUST I0 **E1.00** p0.50 | POWER I0 E0 p0.50 |

Every row has **exactly one cylinder on POWER**, exactly one on each other stroke,
and pistons paired 1+4 against 2+3. Tested continuously, not just at these eight
angles.

### R2 — 180° opposed, uneven fire

**Section 12 asks why opposed pistons do not imply even firing. Here it is:**

| Cycle | Cam | Cylinder 1 | Cylinder 2 |
| --- | --- | --- | --- |
| 0 | 0° | COMPRESSION p**0.00** | INTAKE p**1.00** |
| 90 | 45° | COMPRESSION p0.50 | INTAKE I1.00 p0.50 |
| 180 | 90° | POWER p**1.00** **FIRE** | COMPRESSION p**0.00** |
| 270 | 135° | POWER p0.50 | COMPRESSION p0.50 |
| 360 | 180° | EXHAUST p**0.00** | POWER p**1.00** **FIRE** |
| 450 | 225° | EXHAUST E1.00 p0.50 | POWER p0.50 |
| 540 | 270° | INTAKE p**1.00** | EXHAUST p**0.00** |
| 630 | 315° | INTAKE I1.00 p0.50 | EXHAUST E1.00 p0.50 |

The pistons are **exactly opposed at every single row** — one at 0.00 whenever the
other is at 1.00, both crossing 0.50 together. Perfectly symmetric motion.

And yet: cylinder 1 fires at cycle 180, cylinder 2 at cycle 360, and cylinder 1
again at 900. **Intervals 180 / 540.**

The reason is the whole point of the 720° representation. The pistons repeat
every **360°**; the cycle repeats every **720°**. Two cylinders whose pistons are
180° apart can be either 180° or 540° apart *in the cycle*, and the crank
geometry alone cannot tell you which. Symmetric metal, asymmetric rhythm.

### R3 — throws 0/120/240, even fire

| Cycle | Cam | Cylinder 1 | Cylinder 2 | Cylinder 3 |
| --- | --- | --- | --- | --- |
| 0 | 0° | COMPRESSION p0.00 | EXHAUST E0.75 p0.75 | POWER p0.75 |
| 90 | 45° | COMPRESSION p0.50 | INTAKE I0.25 p0.93 | POWER p0.07 |
| 180 | 90° | POWER p1.00 **FIRE** | INTAKE I0.75 p0.25 | EXHAUST E0.75 p0.25 |
| 270 | 135° | POWER p0.50 | COMPRESSION p0.07 | EXHAUST E0.25 p0.93 |
| 360 | 180° | EXHAUST p0.00 | COMPRESSION p0.75 | INTAKE I0.75 p0.75 |
| 450 | 225° | EXHAUST E1.00 p0.50 | POWER p0.93 | INTAKE I0.25 p0.07 |
| 540 | 270° | INTAKE p1.00 | POWER p0.25 | COMPRESSION p0.25 |
| 630 | 315° | INTAKE I1.00 p0.50 | EXHAUST E0.25 p0.07 | COMPRESSION p0.93 |

Firing at cycle 180 / 420 / 660 — **240° apart, even fire**, on the crank the mod
already ships. The partial lifts (0.25, 0.75) are correct: the sampling grid is
90° and R3's cylinders are 240° apart, so no sample lands on a peak for cylinders
2 and 3. The lobes still peak exactly on time; the test checks that directly.

### R1

One firing per 720°, one cylinder cycling through all four strokes in order —
the reference case, and the one where a player can watch a whole cycle on a single
piston.

---

## 7. Timing drive

> ### A pair of exposed spur gears, 2:1, at one end of the crankcase run. Part of the Camshaft component, not a separate install.

| Option | Verdict |
| --- | --- |
| **Exposed gears** | **chosen** — early-industrial, the ratio is countable by eye, no cover to hide it |
| Chain | needs a case to look right, and a case hides the thing worth showing |
| Belt | wrong era entirely |
| Hidden / internal | forecloses the teaching and the future failure mode |

Placed on the **outer flank** of an end crankcase section, not on its axis end
face — both ends of the run are already spoken for (the Flywheel at one, a Create
shaft output at the other), and a gear case there would collide with whatever the
player attaches.

### Timing failure — designed for, not implemented

> **A missing or broken timing drive should make the engine turn over and never
> fire. Nothing more. No damage, no valve-piston collision.**

The architecture already expresses this exactly, with **no new machinery at all**.
`FourStrokeCylinderTiming` gates ignition on the arming latch, and the latch is
set by forward-crossing the intake opening. No camshaft means no valve actuation
means no induction means the latch never sets means the cylinder never fires —
while the crank turns, the pistons move, and compression is still felt.

That is a genuinely good failure: the player gets a machine that spins, sounds
mechanical, and refuses to catch, which is exactly what a real engine with a
snapped timing chain does. It reuses the existing `EnginePhase` CRANKING state
and needs not one new field.

Valve-piston collision is explicitly out of scope and should stay there. It would
demand real valve/piston position intersection, a damage model, and a repair
path, for a failure the player cannot see coming.

---

## 8. Maintenance

> ### Recommendation: **no new consumables.** Two wear items is the right number.

The mod has Piston Assembly compression wear and Crankshaft bearing wear. Both
are diagnosable ("cylinder 3 has lost its compression") and both map to a part
you replace. That chain works because it is short.

| Candidate | Recommendation |
| --- | --- |
| **Valve wear** | **Never a separate consumable.** A worn valve's symptom is lost compression, which is *already* what Piston Assembly wear means. Two parts producing one indistinguishable symptom makes diagnosis worse, not deeper. |
| **Timing gear wear** | **Possible later, as a property of the Camshaft part** — the pattern the crankcase already uses for bearings. It has a distinct, diagnosable symptom (won't fire despite spark and fuel) and a clear repair. Not now. |
| Rocker / pushrod wear | **Never.** No distinct symptom, no separate part. |

The test to apply to any future wear item: *does it fail in a way the player can
tell apart from everything else, and is there exactly one part that fixes it?*
Valve wear fails both. Timing gear wear passes both.

---

## 9. Keeping the head readable

Section 19's concern is real and specific: the head is small and already carries
the spark plug, the two ports and the head studs.

| Feature | Reserved volume | Rule |
| --- | --- | --- |
| **Spark plug** | x 11.15–12.65, z 7.25–8.75, y 13.6–21 | **untouched.** Nothing may enter this column |
| Intake port | −Z boss, x 5.4–10.6, z 0–4.6 | valve sits under it |
| Exhaust port | +Z boss, x 6–10, z 11.6–15.98 | valve sits under it |
| Head studs | four corners, y 14.8–17.8 | untouched |
| **Rockers** | pivot on a shaft **parallel to the crank axis**, leaning ±Z | between the studs, clear of the plug column |
| **Pushrods** | vertical, at the ±(cross-axis) flanks | outside the fins, clear of the plug |

The rockers lean across the engine (toward the two ports), and the plug stands on
the along-engine side. **The two never share a face.** A player looking at the head
sees: plug on one side, two rockers nodding at the ports on the other.

---

## 10. Intake, exhaust, and the space to leave alone

### Intake — coexists, nothing to change

The shared intake manifold work happening on another branch runs along the **−Z**
side (for an X-axis engine), feeding the larger head boss from the Carburetor.
The valvetrain is on the crankcase flank and the head top; **the two do not share
a volume.** No production model needs to change for them to coexist.

Spatial reservation for the manifold branch: **z 0–4.6 at head height is intake.**
The valvetrain claims only the rocker volume above the head cap and the pushrod
columns at the flanks.

### Exhaust — reserved, not built

> **The +Z head boss is the exhaust port.** It already exists in `cylinder.json`,
> is smaller than the intake boss, and is currently unused.

A future exhaust manifold runs along **+Z**, mirroring the intake, at head height.
The head is therefore **crossflow** — intake one side, exhaust the other — which is
both the correct arrangement and the one the existing model already implies.

The only thing this milestone must not do is put the valvetrain where the exhaust
will need to go. It does not: the pushrods run at the flanks and the camshaft is
on the crankcase, a full block below.

---

## 11. Rendering

> ### Block entity renderer partials, driven from the one master `CyclePosition`. No new pattern.

| Drawn by | What | Transform source |
| --- | --- | --- |
| `CrankshaftRenderer` | camshaft segment for this section, lobes | `camAngle(position)` |
| `CrankshaftRenderer` (end section) | timing gears | `camAngle` and physical crank angle |
| `CylinderRenderer` | 2 pushrods, 2 rockers, 2 valves | `CamshaftTiming.valveLift(...)` |

This is exactly the pattern already in use: `CylinderRenderer` draws the piston
and connecting rod as `CachedBuffers.partial(...)` transformed by a value derived
from the controller's angle, and picks an axis-appropriate rod model. The
valvetrain adds more of the same and no new mechanism.

**Axis handling.** The camshaft gallery and pushrods must be drawn on the face
*parallel to the crank axis*, so they are open to air on both X-axis and Z-axis
engines. The renderer already selects by axis; the static model cannot, which is
why these parts belong in the BER.

**One source, always.** Every moving part reads `CamshaftTiming`, which reads the
engine's `CyclePosition`. Piston, rod, camshaft, lobes, pushrods, rockers and
valves therefore cannot disagree about where the engine is — the same guarantee
the piston and rod already have.

### Performance

The engine is capped at **four cylinders**, and that cap is what makes this cheap.

| | Worst case (R4) |
| --- | --- |
| Camshaft segments | 4 |
| Pushrods | 8 |
| Rockers | 8 |
| Valves | 8 |
| Pistons + rods | 8 |
| **Total partial draws** | **≤ 36** |
| Trig per frame | 8 cosines (one per valve) |
| Allocations per frame | **zero** — `CachedBuffers.partial` and primitive maths |
| World lookups | **zero** — components are already resolved and cached per tick |

No world scans, no per-frame searching, no per-valve state. A valve's position is
one cosine of one float. This is not a performance question at four cylinders,
and the cap is what guarantees that.

---

## 12. Sound

> ### Recommendation: **no separate valvetrain sound layer.**

The engine's audio is already two layers — a mechanical loop following crank
speed, and discrete combustion pulses from the authoritative event counter. A
valvetrain sits squarely inside what the mechanical loop already represents:
*"crankshaft, bearings, flywheel, the piston pumping against compression"*.

The arithmetic settles it. An inline-4 at full throttle runs 1.6 cycles a second,
so its **8 valves open 12.8 times a second — 25.6 discrete events counting each
closing.** Rendering those as ticks would be a buzz, not a texture, and it would
compete with the combustion pulses that carry the engine's actual rhythm — the one
thing the sound design exists to protect.

If valvetrain character is wanted later, the right move is a **richer mechanical
loop asset**, not a new layer. Same clock, same volume envelope, no new code.

---

## 13. Future Ponder concept

Not implemented. One scene, five beats, an R1 at a crawl.

| Beat | Shown | Text |
| --- | --- | --- |
| 1 | Cam turns, crank turns twice. Highlight the gears. | "The camshaft turns once for every **two** turns of the crankshaft." |
| 2 | Intake valve opens, piston descends, blue arrow in through the intake port | "**Intake.** The piston draws in fuel and air." |
| 3 | Both valves shut, piston rises, cylinder highlighted as sealed | "**Compression.** Both valves close. The charge is squeezed." |
| 4 | Spark at the plug, piston driven down, flywheel visibly speeds up | "**Power.** The spark lights it. This is the only stroke that pushes." |
| 5 | Exhaust valve opens, piston rises, grey arrow out through the exhaust port | "**Exhaust.** The burnt charge is pushed out. Then it begins again." |

Then one closing beat on an inline-4 at speed: four pistons, the firing order
1‑3‑4‑2 called out as each fires, and the line *"Four cylinders, four strokes —
one of them is always pushing."*

Teaching order matters: **start with the 2:1 gears**, because everything else
follows from it, and a player who understands that one relationship can work out
the rest by watching. The pushrods are the visual thread — they are the only part
that moves differently for each cylinder and is visible from a distance.

---

## 14. Prototype and tests

`src/prototype/java/dev/engineeredcombustion/prototype/fourstroke/`

| Class | Responsibility |
| --- | --- |
| **`ValveTiming`** | the two windows and the lift curve — `lift`, `isOpen`, `liftCurve` |
| **`CamshaftTiming`** | `camAngle`, `lobeAngleDegrees`, `valveLift`, `pushrodLift`, `rockerAngleDegrees`, `isSealed` |

Two classes, no state, no per-valve objects. A `ValveState` type was considered
and rejected: a valve's entire state is one float, and wrapping it would be the
"second permanent engine framework" the milestone warns against.

**`ValvetrainTests`** — 10 sections, all passing:

| Requirement | Check |
| --- | --- |
| cam turns once per 720° | driven for 7200°, exactly 10 revolutions counted |
| no independent cam clock | shaken engine matches fresh engine; 500 rocks leave it unmoved |
| intake opens only on intake | 2880 samples |
| exhaust opens only on exhaust | 2880 samples |
| both shut on compression | 179 samples |
| both shut on power | 179 samples |
| lift continuous | largest step over 0.1° is 0.0017 of full lift |
| lift zero at boundaries | exactly 0.0 at both |
| no overlap | 14 400 samples, never both open |
| lobe layout | every lobe peaks when its valve peaks, all 5 layouts |
| R1 / R2 / R3 / R4 timing | no valve ever opens on the wrong stroke, any layout |
| per-cylinder independence | 4 different strokes at once; exactly one on POWER always |
| save / reload | cam and all 8 valves identical across 7 boundary angles |

Plus one invariant that crosses milestones: **the intake opening angle equals
`FourStrokeCycle.ARMING_ANGLE_DEGREES`** — asserted, so the valvetrain and the
combustion gate can never drift apart.

---

## 15. FROZEN / OPEN

### FROZEN

| # | Decision | Value |
| --- | --- | --- |
| 1 | Player-facing complexity | **Semi-modular** |
| 2 | What the player installs | **one Camshaft**, into any one Crankshaft section, serving the whole engine |
| 3 | Valves, pushrods, rockers | part of the Cylinder; appear with the Piston Assembly |
| 4 | Timing drive | part of the Camshaft component, **not** a second install |
| 5 | Camshaft location | gallery on the crankcase outer flank, deck height |
| 6 | Valvetrain type | **OHV — side cam, pushrods, rocker arms.** Not OHC |
| 7 | Camshaft continuity | one shaft across all sections; module seams acceptable |
| 8 | Cam angle | `cycleAngle / 2`, zero at cylinder 1's compression BDC |
| 9 | Lobes | 2 per cylinder, positions derived from cycle phase offset |
| 10 | Valve windows | intake `[540,720)`, exhaust `[360,540)`, **no overlap** |
| 11 | Lift curve | raised cosine, `(1 - cos 2πt)/2` |
| 12 | Rocker ratio | 1:1 |
| 13 | Timing drive style | exposed spur gears, outer flank of an end section |
| 14 | Timing failure | **turns over, never fires.** No damage, no collision |
| 15 | Intake port | −Z head boss (existing) |
| 16 | Exhaust port | **+Z head boss (existing, reserved)**, crossflow head |
| 17 | Spark plug column | x 11.15–12.65, z 7.25–8.75 — **inviolate** |
| 18 | Rendering | BER partials from one `CyclePosition`; axis-aware parts in the BER |
| 19 | New consumables | **none** |
| 20 | Sound | no separate layer |

### OPEN

1. **Exact model geometry** — lobe profile shape, pushrod diameter, rocker
   proportions, gallery depth. Model work, not architecture.
2. **Does the Camshaft need a distinct crafting chain**, or is it a Sequenced
   Assembly like the Piston Assembly? Recipe design.
3. **Should an engine without a Camshaft be `isMechanicallyValid()`?**
   Recommendation: **yes, keep it valid** — it turns, it just never fires, exactly
   like an engine with no Spark Plug. But it is a production semantics decision.
4. **Timing gear wear**, later, as a Camshaft property. Deliberately deferred.
5. **Valve overlap**, later, if gas flow ever gets modelled. The window is stored
   as angle + duration so this is two numbers.
6. **The axis-fixed cylinder model.** Ports point the wrong way on Z-axis engines
   *today*; a future axis-aware variant is a prerequisite for the ports to read
   correctly, and is a production change outside this milestone.
7. **Whether the exhaust manifold is a block or a Cylinder-installed item** — the
   same question this document answered for the valvetrain, and it should probably
   get the same answer.
