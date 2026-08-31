# Create-Engineered-Combustion

**Create: Engineered Combustion** is a [Create](https://github.com/Creators-of-Create/Create)
addon about building internal combustion engines out of individual mechanical
parts instead of placing a single "engine" block.

The long-term goal is that players assemble engines from crankshafts, cylinders,
pistons, connecting rods, flywheels, intake and exhaust components, ignition,
lubrication and cooling, eventually in single-cylinder, inline, V and boxer
layouts. Development proceeds in small, individually buildable milestones.

## Target platform

| | |
| --- | --- |
| Minecraft | 1.21.1 |
| Mod loader | NeoForge 21.1.238 |
| Java | 21 |
| Create | 6.0.10 (**required** dependency) |
| Mod ID | `engineered_combustion` |
| Base package | `dev.engineeredcombustion` |

The NeoForge, Parchment, Registrate, Ponder and Flywheel versions in
`gradle.properties` are taken verbatim from the `mc1.21.1-6.0.10` tag of Create
itself, so the development environment matches what Create 6.0.10 actually ships
with.

## Building

```
./gradlew build
```

The first run downloads and decompiles Minecraft, which takes a while.

Development runs:

```
./gradlew runClient
./gradlew runServer
```

### A note on Create's Maven version

Create is **not** published under its plain release version. Its `build.gradle`
appends the Jenkins build number:

```groovy
version = mod_version + (dev && buildNumber != null ? "-${buildNumber}" : "")
```

So Create 6.0.10 lives at `com.simibubi.create:create-1.21.1:6.0.10-280`, and
asking for `6.0.10` fails with:

```
Could not find com.simibubi.create:create-1.21.1:6.0.10
```

`create_version` in `gradle.properties` therefore carries the build number, while
`create_version_range` (used for the mod-loader dependency in
`neoforge.mods.toml`) does not - Create reports plain `6.0.10` to the mod loader.
To bump Create, list the available versions at
<https://maven.createmod.net/com/simibubi/create/create-1.21.1/> and copy the
full `x.y.z-buildnumber` string.

### Required Maven repositories

Dependency resolution needs all of these to be reachable:

| Host | Provides |
| --- | --- |
| `maven.neoforged.net` | NeoForge, ModDevGradle's NeoForm runtime |
| `piston-meta.mojang.com`, `piston-data.mojang.com`, `libraries.minecraft.net` | Minecraft itself |
| `maven.createmod.net` | Create, Ponder (which contains Catnip), Flywheel |
| `maven.ithundxr.dev` | Registrate |
| `maven.parchmentmc.org` | Parchment mappings |

If your network blocks any of them, the build fails during dependency
resolution before it ever reaches compilation.

## Testing

```
./gradlew check
```

runs everything: the pure engine simulation suites and the generated-model
validation, alongside the normal build.

### Without Minecraft

The engine simulation is deliberately free of Minecraft, NeoForge and Create
types, so most of this mod's behaviour can be tested on a bare JDK — no
dependency downloads, no decompilation, no game launch:

```
./gradlew simulationTest checkModels
```

That is worth knowing about when the Maven hosts above are unreachable, because
it is the part of `check` that still works.

| Task | What it runs |
| --- | --- |
| `simulationTest` | every suite in `src/simulationTest/java` |
| `simulationTest<Name>` | one suite, e.g. `simulationTestEngineLayoutTests` |
| `checkModels` | `tools/check_models.py` |

The `simulationTest` source set compiles the simulation and its tests with an
**empty compile classpath**. That is load-bearing rather than an optimisation: it
is what mechanically enforces the boundary, because the moment a Minecraft type is
imported into `EngineState`, `EngineTuning`, `EngineLayout` or their neighbours,
that compile fails. The two classes in the package that legitimately touch
Minecraft — `EngineComponents` and `CombustionAudio` — are excluded by name in
`build.gradle`, and that list is the exhaustive statement of where the boundary
runs.

The suites are plain classes with a `main` method rather than JUnit ones, so that
running them needs nothing but a JDK. To add one, drop it in
`src/simulationTest/java` and add its class name to `simulationTestClasses` in
`build.gradle`.

`checkModels` needs `python3` on `PATH`, and says so by name if it is missing.

Which tests cover what — and which checks are simulation, which are Gradle, and
which still have to be done by hand in game — is documented in
[`docs/milestone-12-hardening.md`](docs/milestone-12-hardening.md) and, for engine
wear, in
[`docs/milestone-13-wear-maintenance.md`](docs/milestone-13-wear-maintenance.md).
The advancement, Ponder, translation and structure checks - and what was
deliberately *not* tested - are in
[`docs/milestone-14-player-experience.md`](docs/milestone-14-player-experience.md).

## Continuous integration

[`.github/workflows/build.yml`](.github/workflows/build.yml) runs on pushes to
`main` and on pull requests. It builds with Java 21 through the Gradle wrapper,
runs `./gradlew build check`, and uploads the built JAR as a workflow artifact. It
publishes no releases.

A separate job runs the pure simulation tests, the model check, a
generated-assets-are-current check and the licence-file check **without** the mod
dependencies, so those still report even when an upstream Maven host is
unavailable.

## Assets

Every texture and model is generated, not hand-edited, and the generators are
deterministic. So are the recipes, tags, loot tables and worldgen, so that the
balance numbers exist in exactly one place. See [`docs/assets.md`](docs/assets.md)
for what writes what, why the resolutions differ, and the two model invariants
`tools/check_models.py` enforces.

## Milestones

* **Milestone 0** - clean NeoForge 1.21.1 + Create 6.0.10 workspace, mod entry
  point, registration structure.
* **Milestone 1** - single-cylinder mechanical prototype: player-built structure
  detection, an authoritative crank angle, a piston animated from that angle, and
  rotational output into Create's kinetic network. See
  [`docs/milestone-1.md`](docs/milestone-1.md).
* **Milestone 2** - real mechanical rotation and hand-crank starting: the engine
  can be motored by any Create source, has angular velocity, flywheel inertia and
  friction, fires once per revolution, and must be cranked above a starting speed
  before it will run. Redstone is now only a combustion enable. See
  [`docs/milestone-2.md`](docs/milestone-2.md).
* **Milestone 3** - gasoline: a real fuel fluid, a Carburetor with a fluid tank,
  fuel burned per combustion event, multi-cycle starting instead of an instant
  threshold, and Create-style goggle overlays. See
  [`docs/milestone-3.md`](docs/milestone-3.md).
* **Milestone 4** - visual rebuild: a cut-away crankcase with a real offset
  crankshaft, a working connecting rod on exact slider-crank geometry, a finned
  cutaway cylinder with an integrated head, and models that cross block
  boundaries so the assembly reads as one machine. No gameplay change. See
  [`docs/milestone-4.md`](docs/milestone-4.md).
* **Milestone 5** - a working shaft output on *both* ends of the crankshaft
  (one source, one stress budget), a spark plug that sparks on real ignition
  events and a brief flash on real combustion, a float bowl showing the actual
  gasoline level, an Air Filter item fitted to the Carburetor, and a 0-100 %
  throttle that changes combustion torque rather than dialling in a speed. See
  [`docs/milestone-5.md`](docs/milestone-5.md).
* **Milestone 6** - control architecture: the Flywheel may sit at *either* end of
  the crankshaft (and two of them are an explicit, inert error rather than an
  arbitrary choice), and redstone is no longer the ignition. The engine runs on a
  mechanical ignition switch on the crankcase with no redstone at all; an optional
  Redstone Control Module item adds ignition, throttle or both under signal
  control, resolved centrally so nothing else in the mod reads redstone. See
  [`docs/milestone-6.md`](docs/milestone-6.md).
* **Milestone 7** - ignition presentation: the Spark Plug is screwed down through
  the head and stands beside the Carburetor, with only its electrode and ground
  strap in the combustion chamber and nothing hanging outside the engine's own
  silhouette; the combustion flash is
  a fireball that the descending piston uncovers rather than a dim disc, and both
  the flash and the firing sound are driven by server-authoritative spark and
  combustion counters, so what the player sees and hears is the charge that
  actually burned. See [`docs/milestone-7.md`](docs/milestone-7.md).
* **Milestone 8** - Survival foundation: the Spark Plug becomes a real installable
  component without which the engine sparks and burns nothing, every part of a
  working engine gains a Create-tier recipe, and a small standalone petroleum
  chain - Oil Shale, Crude Oil, and refining into Gasoline and Engine Oil - makes
  the mod playable without Creative and without any other petroleum mod. Fuel and
  lubricant acceptance is tag-driven, so another mod's fluids can join with a
  datapack. See [`docs/milestone-8.md`](docs/milestone-8.md).
* **Milestone 9** - Engine stability: a game-breaking exploit is closed - an
  engine that is not burning fuel now contributes zero Stress Capacity however
  fast a neighbour spins it, gated behind one authoritative
  `isActivelyGenerating()` predicate and charged a small parasitic load instead;
  the crankshaft has one momentum rather than two, so an engine driven to 200 RPM
  by an external network coasts down from 200 when that network is removed instead
  of snapping back to idle; new engines are placed with the ignition already on;
  and the audio is rebuilt into a mechanical rotation layer plus one combustion
  pulse per charge that actually burned, so the engine's rhythm is its real firing
  rhythm and a fuel-starved engine audibly stops combusting while it is still
  spinning. See [`docs/milestone-9.md`](docs/milestone-9.md).
* **Milestone 10** - save/reload RPM reconciliation: an engine that survives a
  world save no longer comes back running at a speed Create simply happened to be
  holding. The engine's signed angular velocity is the one persisted rotational
  state and the generated speed is reconstructed from it; an explicit post-load
  reconciliation step re-derives generation from the world on the first tick the
  engine's blocks are actually loaded and force-publishes the result - including
  zero, and including Create's cached Stress Capacity, so a save cannot resurrect
  a dead engine's power. The generated-speed update rule is now a low-pass filter
  plus a rate limit rather than a deadband, so combustion ripple still never
  reaches the kinetic network but a small error can no longer persist for ever.
  See [`docs/milestone-10.md`](docs/milestone-10.md).
* **Milestone 11** - modular inline engines: extending the crankshaft along its
  own axis builds an Inline-1, -2, -3 or -4, and it is **one** engine - one
  simulation, one master crank angle, one throttle, one Flywheel, one kinetic
  source - with each cylinder taking its turn at `i * 360 / n` degrees. Every
  cylinder gets its own combustion, its own charge of gasoline, its own spark and
  flash and bang at its own bore, and its own phase-shifted compression, so an
  inline-4 burns four times the fuel, supplies four times the Stress Capacity and
  runs visibly and audibly smoother than a single - and an inline-4 with one dead
  Spark Plug runs on three cylinders, down on power. Capacity is scaled by the
  cylinders that are actually burning fuel, never by how many exist, so a motored
  dry inline-4 still supplies exactly nothing. See
  [`docs/milestone-11.md`](docs/milestone-11.md).
* **Milestone 12** - hardening pass: Stress Capacity is refreshed when a cylinder
  stops firing even if the published speed does not move, so an inline-4 that
  loses a Spark Plug while another source holds the shaft drops to three
  cylinders' worth of capacity immediately; crankshaft layout is resolved by one
  scan shared by both resolvers, so runs longer than four sections are
  consistently invalid from every section instead of splitting into partial
  engines; an unloaded chunk is no longer mistaken for the end of a run, so an
  engine across a chunk border suspends rather than re-deriving itself into a
  smaller one; extending a run at its negative end hands the ignition switch, the
  Control Module and the selected mode to the new controller; combustion events
  travel as one compact payload per engine per tick instead of a full block
  entity sync per event; the last charge an engine paid for finishes its power
  stroke instead of being cut off when the tank reads empty; and the coast-down
  is shortened through separate non-firing drag rather than by weakening the
  flywheel inertia. See
  [`docs/milestone-12-hardening.md`](docs/milestone-12-hardening.md).
* **Milestone 13** - engine wear and maintenance: engines wear out, and the wear
  belongs to the parts rather than to the engine - each Crankshaft section keeps
  its own bearing wear and each installed Piston Assembly its own compression
  wear, so an inline-4 can have one tired bore and three good ones. Wear survives
  the part becoming an item, so pulling a worn assembly out and pushing it back in
  repairs nothing and only a freshly crafted part is new. A worn cylinder produces
  less torque and contributes proportionally less Stress Capacity - an inline-4
  with one dead-compression bore supplies 3.65 cylinders' worth, not four - while
  still counting as an active cylinder, and worn bearings are felt as extra
  friction rather than as subtracted RPM. Rates are per revolution, so wear
  follows the work the machine actually did. The goggles name the worn part and
  the reason it is wearing, and servicing needs a stopped engine. See
  [`docs/milestone-13-wear-maintenance.md`](docs/milestone-13-wear-maintenance.md).
* **Milestone 13.1** - realistic wear rebalance: a properly lubricated, filtered
  and normally operated engine now experiences near-negligible major-component
  wear. The healthy baseline drops by a factor of 114, so an engine at full
  throttle under half load accumulates about 0.035 bearing wear in 100 hours and
  is some 2,800 hours from its service limit - crankshafts and Piston Assemblies
  are not consumables, and replacing one is what happens to an engine that was
  abused rather than to one that was used. Everything harmful is restated as a
  much larger multiple of that much smaller number: low oil 18x, running dry
  1000x, an open intake 8x, sustained overspeed up to 10x, so a dry engine still
  destroys itself in hours and a dry, oversped, fully loaded one in about twelve
  minutes, while thirty dry seconds are forgiven. Full load moves the other way,
  1.9x to 1.6x - an engine hauling a network is doing its job, not being
  mistreated. Both consequence curves are reshaped to be mostly quadratic, so a
  Good engine keeps 99.2% of its compression and healthy engines feel healthy.
  See
  [`docs/milestone-13-wear-maintenance.md`](docs/milestone-13-wear-maintenance.md).
* **Milestone 14** - player experience, Ponder and progression: everything a
  newcomer needs to build, start, expand, diagnose and maintain an engine, put
  where they already know to look. Eight Ponder scenes registered through Create's
  own plugin interface, staged on generated structure files and technically true
  to the game in every frame. Twenty-two advancements in one tab, all driven by a
  single configurable criterion reading real engine events rather than by scanning
  - so a chunk load cannot re-award a first start, rotation is never mistaken for
  generation, and a condition only counts when the engine wore its way there.
  Item tooltips, action-bar feedback that names the one thing actually blocking a
  start, and goggle warnings that show the root cause instead of the cascade below
  it. Everything is in English and German, and a validator checks that every id,
  key, structure and translation resolves. See
  [`docs/milestone-14-player-experience.md`](docs/milestone-14-player-experience.md).

## License

Create: Engineered Combustion is source-available, but it is not open source.

The repository may be viewed and forked through GitHub, but no general permission
is granted to reuse, modify, redistribute, rehost, or publish the code or original
assets.

Official unmodified releases may be used for private, non-commercial gameplay.
Public modpack distribution, mirrors, derivative builds, and reuse of code or
assets require prior written permission.

See [LICENSE.md](LICENSE.md), [ASSET_LICENSE.md](ASSET_LICENSE.md), and
[CONTRIBUTING.md](CONTRIBUTING.md). Third-party rights are set out in
[NOTICE.md](NOTICE.md).
