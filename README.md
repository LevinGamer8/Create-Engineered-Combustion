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

## Assets

Every texture and model is generated, not hand-edited, and the generators are
deterministic. See [`docs/assets.md`](docs/assets.md) for what writes what, why
the three resolutions differ, and the two model invariants `tools/check_models.py`
enforces.

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
