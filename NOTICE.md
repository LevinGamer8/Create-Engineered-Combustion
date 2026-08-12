# Notices

Create: Engineered Combustion is an unofficial add-on. It is not affiliated with,
endorsed by, or sponsored by any of the parties named below.

## Third-party rights

| Project | Rights holder |
| --- | --- |
| Minecraft | Mojang Studios / Microsoft |
| Create | the Create developers and contributors |
| NeoForge | NeoForged and contributors |
| Registrate | its respective authors |
| Ponder | its respective authors |
| Catnip | its respective authors (distributed within the Ponder artifact) |
| Flywheel | its respective authors |
| Gradle, ModDevGradle, Parchment | their respective authors |

These names, trademarks, APIs, source code, and game content are the property of
their respective owners and remain subject to their own licences and terms.

**The project licence claims nothing over any of them.** LICENSE.md and
ASSET_LICENSE.md apply only to the original content of this repository. Nothing
in either document asserts ownership of, or grants any right to, third-party
software, trademarks, names, APIs, or game content.

## Dependencies

This mod is built against, and requires at runtime, the following. None of it is
bundled in this repository; all of it is resolved from its publishers' own
distribution channels at build time.

- Minecraft 1.21.1
- NeoForge 21.1.238
- Create 6.0.10 (`com.simibubi.create:create-1.21.1`)
- Registrate (`com.tterrag.registrate:Registrate`)
- Ponder, which contains Catnip (`net.createmod.ponder:ponder-neoforge`)
- Flywheel (`dev.engine-room.flywheel`)

Exact versions are declared in `gradle.properties` and `build.gradle`.

## Bundled third-party material

At the time of writing this repository contains **no** vendored third-party source
code or assets. Every model, texture, sound and data file under `src/` is either
authored for this project or produced by the generators under `tools/`.

If third-party material is added in future, it must be listed here with its origin
and licence, and its licence text must be included in the repository. See
CONTRIBUTING.md, which requires documented, compatible permission before any such
material may be merged.

## Trademarks

"Minecraft" is a trademark of Mojang Synergies AB. "Create" and the names of the
other projects above are used descriptively, to say what this mod is built on and
what it requires. No claim of ownership or endorsement is made or implied.
