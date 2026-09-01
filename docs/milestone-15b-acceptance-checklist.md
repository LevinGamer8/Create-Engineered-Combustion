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
