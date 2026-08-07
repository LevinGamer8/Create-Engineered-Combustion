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
| Mod loader | NeoForge 21.1.219 |
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

## Milestones

* **Milestone 0** - clean NeoForge 1.21.1 + Create 6.0.10 workspace, mod entry
  point, registration structure.
* **Milestone 1** - single-cylinder mechanical prototype: player-built structure
  detection, an authoritative crank angle, a piston animated from that angle, and
  rotational output into Create's kinetic network. See
  [`docs/milestone-1.md`](docs/milestone-1.md).
