# Contributing to Create: Engineered Combustion

Contributions are welcome. Please read this document before opening a pull
request — this project is **source-available, not open source**, and that changes
what submitting a contribution means.

## Licensing of contributions

**1. Only your own work, or work you may lawfully license.**
Pull requests are accepted only for contributions you created yourself, or which
you hold sufficient rights to license under the terms below.

**2. No copied code or assets.**
Do not submit source code, models, textures, sounds, or any other material taken
from another mod, project, or third party unless you can document permission that
is compatible with these terms. "It was on the internet" is not permission, and
neither is an open-source licence whose conditions this project cannot satisfy —
copyleft licences in particular are incompatible with a proprietary project. If
you are unsure, ask in an issue before writing the code.

**3. What you grant by submitting.**
By submitting a contribution you represent and agree that:

- you are entitled to submit it;
- it does not infringe the rights of any third party;
- you grant LevinGamer8 a perpetual, worldwide, irrevocable, non-exclusive,
  royalty-free licence to use, reproduce, modify, adapt, publish, distribute,
  sublicense, and relicense the contribution as part of this project.

**4. Accepted contributions ship as part of a proprietary project.**
Once merged, your contribution is distributed under LICENSE.md and
ASSET_LICENSE.md along with the rest of the project.

**5. Contributing does not make the project open source.**
Submitting a contribution grants you no rights over the project as a whole, and
does not place the project, or your contribution as distributed within it, under
any open-source licence. You retain your own copyright in what you wrote; what
you give is the licence described in point 3.

**6. Larger contributions may require a signed CLA.**
For substantial contributions, a separate Contributor License Agreement may be
requested before the work can be merged.

## Technical requirements

**7. Generated assets are changed through their generators.**
Models, textures, sounds and survival data under `src/generated` and
`src/main/resources` are produced by the scripts in `tools/`. Change the
generator and re-run it; do not hand-edit its output. A hand-edited file is
silently reverted the next time anyone runs the generator, so an edit made that
way is a bug waiting to happen rather than a fix.

Run the generators from the repository root, then validate:

```
python3 tools/generate_engine_models.py
python3 tools/generate_engine_textures.py
python3 tools/generate_survival_data.py
python3 tools/check_models.py
```

Those three use only the standard library and are byte-reproducible, so CI
re-runs them and fails if the committed output has drifted. `generate_sounds.py`
is separate: it needs `numpy` and `soundfile`, and Ogg Vorbis output is not
stable across libsndfile versions, so it is not run in CI and sound changes are
reviewed by listening to them.

```
pip install numpy soundfile
python3 tools/generate_sounds.py
```

**8. Behaviour changes need tests and documentation.**
Any change to how the engine behaves must come with:

- a regression test that fails before the change and passes after it, and
- an update to the affected documentation under `docs/` and, where the change is
  user-visible, to `README.md`.

The engine simulation is deliberately free of Minecraft, NeoForge and Create
types, so most behaviour can be tested on a bare JDK. Add such tests to
`src/simulationTest/java` and register the class in `simulationTestClasses` in
`build.gradle`. See [docs/milestone-12-hardening.md](docs/milestone-12-hardening.md)
for what each kind of test covers.

Before opening a pull request:

```
./gradlew simulationTest checkModels    # no Minecraft needed
./gradlew build check                   # needs the mod dependencies
```

**9. Keep the architecture invariants.**
These are not style preferences; breaking one is a bug even if everything still
compiles:

- one inline engine is **one** engine — one simulation, one crank angle, one
  momentum, one set of controls, one kinetic source, one stress budget;
- only `EngineFlywheelBlockEntity` may be a `GeneratingKineticBlockEntity`;
- an engine that is not actually burning fuel contributes **no** Stress Capacity,
  however fast something else spins it;
- cylinder count alone never produces power — capacity follows cylinders that
  genuinely fired;
- combustion is server-authoritative; the client is told, it never predicts;
- no block entity references are held across ticks or reloads;
- no unbounded world scans and no chunk force-loading;
- the mod must run on a dedicated server without loading client classes.

**10. Match the surrounding code.**
Comments in this project explain *why* a thing is the way it is, and are expected
to describe what the code actually does. A comment that has stopped being true is
treated as a defect.

## Reporting bugs

Open an issue with the mod version, the Minecraft, NeoForge and Create versions,
whether it happened in single player or on a dedicated server, and the steps that
reproduce it. For engine behaviour, the sneak-goggle diagnostics on the Crankshaft
are the most useful thing you can include.

## Permission requests

Requests to use the code or assets outside what LICENSE.md and ASSET_LICENSE.md
permit may be submitted through the issue tracker. Permission is not granted
unless it is given explicitly in writing by the copyright holder.
