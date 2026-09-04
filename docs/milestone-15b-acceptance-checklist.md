# Milestone 15B — manual acceptance checklist

Everything below needs a running Minecraft. None of it was verified in the
environment that produced the branch. Test on
`claude/milestone-15b-four-stroke-integration-kh9pcr` only.

Two behaviour changes to expect before you start, so they do not read as bugs:

- a single-cylinder engine now needs roughly **16 seconds** of hand cranking to
  catch, about double what it was. The firing opportunities are unchanged, they
  are simply 720 degrees apart now.
- a **single** worn to 1.0 on both bearings and bore will no longer idle. It
  still runs on throttle. Every multi-cylinder engine still idles at the
  service limit.

---

## F — Four-stroke function

| | Do this | Expect |
| --- | --- | --- |
| **F1** | Build an inline-1, fit fuel and oil, do **not** fit a Camshaft. Look at the Crankshaft with goggles. | A red **"Camshaft: Missing"** line, above the spark plug line. Structure lines all normal, no INVALID state. |
| **F2** | Crank it anyway, for a minute. | It turns, the plugs spark, the compression resists, and it never fires and never draws fuel. |
| **F3** | Fit the Camshaft, crank by hand. | Catches in roughly 16 s. Sneak-look at the Crankshaft: cycle angle and per-cylinder stroke lines appear. |
| **F4** | Idle, then half throttle, then full. | Settles at **64 / 128 / 192 RPM**. |
| **F5** | Repeat F3–F4 on an inline-2, inline-3 and inline-4. | Same three equilibria on all of them. Inline-4 should catch almost immediately. |
| **F6** | Watch the flywheel at idle on each layout. | R1 visibly lumpy, R2 lumpy and **uneven** (bang-bang, pause), R3 smooth, R4 nearly rigid. |
| **F7** | Let each layout run one in-game hour at full throttle and compare fuel used against your old worlds. | Unchanged: 1 / 2 / 3 / 4 mB per revolution for R1 / R2 / R3 / R4. |
| **F8** | Rock the crank back and forth across the ignition point by hand, for a minute. | Zero combustions, zero fuel. This was the exploit. |
| **F9** | Drive an engine with a Create motor, no fuel, no Camshaft, or both. | Stress Capacity is **0**, not a fraction. Nothing pulses. |
| **F10** | On a running inline-4, pull one spark plug. Then pull the Camshaft. | The plug's cylinder goes hollow on the overlay immediately and capacity drops by a quarter; the engine keeps running. Pulling the Camshaft stops all combustion within one cycle and capacity goes to 0. |

---

## F11-F15 — Sound

The reason for these is in `milestone-15b-audio.md`. In one line: the mechanical
loop used to carry a compression knock once per **revolution**, which on a
four-stroke put a percussive event between two real bangs and was heard as a
second firing. Listen for that specifically.

| | Do this | Expect |
| --- | --- | --- |
| **F11** | Stand next to a running inline-1 at idle. Watch the piston and listen at the same time. | **One bang per visible combustion, and nothing at the non-firing TDC.** There is a mechanical bed all the way through - swell, breathing, whirr - and exactly one percussive event per 720°. |
| **F12** | Compare 64, then 128, then 192 RPM on the inline-1. | 0.53, 1.07 and 1.60 bangs per second. It should sound **intentionally lumpy** at idle, like a big slow single, rather than broken or arrhythmic. If any speed sounds like it is firing twice as often as the piston shows, that is the phantom and it is a regression. |
| **F13** | Listen to R1, R2, R3, R4 at idle in turn. | Four clearly different rhythms: R1 one bang every 1.9 s; **R2 uneven, a bang 0.47 s after the previous one and then 1.41 s of gap**; R3 even at 0.62 s; R4 even at 0.47 s. R2's limp is the one to check - it is a deliberate design decision, not a fault. |
| **F14** | Run an engine out of fuel while it is still being turned, or pull the Camshaft on a spinning one. | The mechanical layer carries on unchanged and there is **no combustion sound at all**. A turning engine that is not burning must sound like dead weight. |
| **F15** | Hand-crank an engine from cold. | "rrrr, PUT, rrrr, PUT, PUT-BRUM": the coil ticks are quiet and clearly not bangs, and each pre-catch firing is duller and quieter than a running one. |

---

## V — Visual

| | Do this | Expect |
| --- | --- | --- |
| **V1** | Stand back from an inline-4 and look at it side-on. | It reads as **one machine**, not four in a row: an unbroken crankcase, and the cam cradle and rocker shaft running the full length. |
| **V2** | Look at the inline-4 from underneath. | One oil pan four bays long, with the Oil Sump as a deep service bowl bolted into one bay. Not a small tank hanging under cylinder 1. |
| **V3** | Look at the top of the engine, above the rockers. | An intake manifold rail with a runner down into each head, starting at the Carburetor. |
| **V4** | Look at the flank **opposite** the valve gear. | Completely clear. Nothing should be there; it is reserved for a future exhaust manifold. |
| **V5** | Build the same engine along **Z** instead of X. | Spark plugs, connecting rods and valve gear all correctly oriented. This is the bug that was fixed; a quarter-turn error here is a regression. |
| **V6** | Watch the valve gear on a running engine. | Cam turns at **half** crank speed. Pushrod rises as its valve is pushed down, because the rocker is a lever. Nothing jitters. |
| **V7** | Remove the Camshaft. | The cradle and pushrod tunnels are visibly **empty**. No valve gear drawn at all. |
| **V8** | Watch a running engine's valves, then sneak-look at the Crankshaft and read the stroke lines. | The valves match the strokes named. A cylinder shown on POWER must have both valves shut. |
| **V9** | Reconnect to the world while an engine is running and watch immediately. | Valves settle onto the right stroke within a second or two, without a visible jump through half a cycle. |
| **V10** | Listen to R1, then R2, then R4 at idle. | R1 distinctly lumpy, R2 **uneven** (two bangs then a gap), R4 smooth. |


## V11-V18 — the Camshaft and the timing drive

Why these exist is in `milestone-15b-camshaft-visuals.md`. In one line: the item
was an unreadable comb, the installed camshaft was under a cover, and the 2:1
that defines a four-stroke was drawn nowhere at all.

| | Do this | Expect |
| --- | --- | --- |
| **V11** | Look at the Camshaft in your inventory. | A toothed timing wheel at one end, a shaft, three lobes with visible noses. It should be identifiable **without reading the name**. |
| **V12** | Drop one on the ground, and put one in an item frame. | Still recognisable in both. Not a sliver in the grass, not a gear seen dead end-on. |
| **V13** | Install it and look at the engine's flank. | The **same part**: same wheel, same lobes, same materials. Not one mechanism in the hand and an unrelated machine in the engine. |
| **V14** | Look along the intake flank of a running R1 at the camshaft. | The shaft is **visible**, not under a cover, carried by bearing caps, with two lobes whose noses you can watch come round. |
| **V15** | Look at the front of the engine — the end **opposite** the Flywheel. | An open timing case with two gears in it: a small one above, and one visibly **twice its diameter** below on the camshaft. Watch them turn: the big one goes round once for every two of the small one. |
| **V16** | Watch the gears where their teeth meet. | They turn in **opposite** directions, and nothing passes through anything. |
| **V17** | Watch one cylinder's pushrod and rocker through a full cycle. | The pushrod's foot stays **on** its lobe at every angle, the rocker visibly **pivots** rather than sliding, and its brass pad rises as the valve on the far side goes down. |
| **V18** | Find the Spark Plug and the ignition switch. | The plug is still on the head and reachable; the switch has moved along the crankcase, out of the camshaft's way, and is still on the intake flank at eye level. |

---

## P — Ponder

| | Do this | Expect |
| --- | --- | --- |
| **P1** | Open Ponder on every engine block and page through all **nine** scenes to the end. | No crash, no missing scene, no scene that ends early. |
| **P2** | Read the new **"The Four-Stroke Cycle"** scene. | Understandable with no engine knowledge. Intake, compression, power, exhaust, then why the flywheel matters. |
| **P3** | Read the assembly scene. | Camshaft installation is shown, and shown as **required** before the engine will start. |
| **P4** | On every text step in every scene, check what is highlighted. | The highlight points at the object the sentence is teaching. No broad box covering half the machine; no highlight left over from the previous step. |
| **P5** | Switch the game to German and repeat P1–P4. | Every line translated. No raw `engineered_combustion.…` keys anywhere, in Ponder, goggles or tooltips. |

---

## D — Dismantling

| | Do this | Expect |
| --- | --- | --- |
| **D1** | Mine each of Crankshaft, Cylinder, Flywheel, Carburetor, Oil Sump with a **stone or better** pickaxe. | Each drops itself. This is the bug that was reported: before the fix, all five dropped nothing. |
| **D2** | Try the same with a wooden pickaxe or bare hand. | No drop, correct tier behaviour. |
| **D3** | Run an engine until the goggles show visible wear, then mine the Cylinder and re-place it. | The wear is still there. It must not reset, halve or double. |
| **D4** | Remove each block in Creative. | No crash, no leftover block entity, no ghost engine. |
| **D5** | **Sneak** + Create Wrench each of the five blocks. | Exactly one of each drops. Never two. |
| **D6** | Wrench a Cylinder, Carburetor or Oil Sump **without** sneaking. | Nothing happens. They have no rotation to cycle. |
| **D7** | Fit a Camshaft, then mine the Crankshaft. | Exactly one Camshaft drops, plus the Crankshaft. |
| **D8** | Fit a Camshaft and a Redstone Control Module, then extend the engine so the controller moves to a different block. | Both parts follow the new controller. None duplicated, none lost. Check the new controller's overlay. |
| **D9** | Dismantle an engine **while it is running and generating**. | The Stress Capacity it was contributing disappears from the network. No ghost capacity, no orphaned controller, no cylinders still claiming to fire. |
| **D10** | Mine a Cylinder that has a spark plug and a piston in it. | The installed parts come back, exactly once each. |

---

## M — Multiplayer

Dedicated server, at least two clients.

| | Do this | Expect |
| --- | --- | --- |
| **M1** | Both players watch the same running engine. | Same crank position, same valve positions, same stroke. |
| **M2** | Second player joins while the engine is already running. | Valves land on the correct stroke within a second or two. **Not** one stroke out permanently, which looks identical on the piston and wrong on the valves. |
| **M3** | One player fits the Camshaft while the other watches. | The valve gear appears for both. |
| **M4** | Run several engines near each other and watch the server tick time and network graph (F3). | No per-tick packet storm. Engine updates are event-driven. |
| **M5** | One player dismantles a running engine while the other watches. | Both see it stop. No phantom cylinders, no capacity left behind on either side. |
