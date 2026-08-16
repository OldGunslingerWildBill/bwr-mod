# Agent Handoff

**You are picking up a Minecraft 1.21.1 / NeoForge mod that simulates a boiling water reactor.**
This file is the entry point for an agent starting cold. Read it before touching anything; it
exists to stop you rediscovering things that already cost hours.

`README.md` is the *design* handoff written before any code existed. This file is the *codebase*
handoff. Both are current — they answer different questions.

---

## 1. Prove the project works before you change it

Everything below is verified. If any of it fails on your machine, fix that before writing code,
because you cannot tell your breakage from pre-existing breakage otherwise.

```bash
cd <repo root>
./gradlew clean build          # expect BUILD SUCCESSFUL, 117 tests passed
python tools-audit-assets.py   # expect PROBLEMS: 0
./gradlew :mod:runData         # expect BUILD SUCCESSFUL, mod loads in a real MC runtime
```

There is a Gradle wrapper, so you need nothing installed but a JDK 21.

**Finding a JDK is the first trap.** On the machine this was built on there is no `java` or
`javac` on `PATH` at all. A full JDK 21.0.7 *including `javac`* ships inside PrismLauncher:

```bash
export JAVA_HOME="/c/Users/14238/AppData/Roaming/PrismLauncher/java/java-runtime-delta"
export PATH="$JAVA_HOME/bin:$PATH"
```

If that path is gone, any JDK 21 works. NeoForge 1.21.1 requires exactly Java 21.

The physics module needs **no Gradle at all** — useful when you want a fast loop:

```bash
javac -d out $(find core/src/main/java -name '*.java')
javac -cp out -d testout $(find core/src/test/java -name '*.java')
java -cp "out;testout" dev.bwr.core.AcceptanceTests     # ';' on Windows, ':' on POSIX
```

---

## 2. The rule that will fail your build

> **The mod provides hardware and physics. The player provides control logic.**

There is deliberately **no scram logic, no trip setpoints, no automatic ECCS actuation and no
protection system anywhere in this codebase.** Players write all of it themselves in CC:Tweaked
Lua, from scratch. The mod exposes **measurements and actuators, never judgements**.

This is not a style preference. It is enforced by two build checks, both of which have been
negative-tested to confirm they actually fail:

| Check | Scope | Run it |
|---|---|---|
| `ReactorCoreTickTest.test07` | 976 methods across all 37 `core/` classes, discovered by reflection | `./gradlew :core:acceptance` |
| `checkNoProtectionLogic` | 90 `mod/` source files, comments stripped first | `./gradlew :mod:checkNoProtectionLogic` |

Banned name fragments: `shouldScram`, `autoScram`, `checkTrip(s)`, `isSafe`, `isHighPressure`,
`isLowLevel`, `autoStart`, `mustScram`, `needsScram`, `scramIfNeeded`, `tripOnHighPressure`,
`protectionSystem`.

Javadoc that *prohibits* such a name is fine and should stay — comments are stripped before
scanning, deliberately.

**`scram()` exists and is correct.** It is a bare, unconditional actuator with no checks. The
rule is not "no scram", it is "nothing in the mod decides *when* to scram."

Corollaries that are easy to violate by accident:

- **SRVs are player-actuated** via CC or redstone. They do not self-open on pressure. This means
  the plant has **no automatic overpressure relief at all**, which is why the stress model in
  `core/boundary/` is the only automatic consequence in the entire mod.
- **IRM ranging is manual.** Never auto-range an instrument — mis-ranging is a real startup
  hazard and auto-ranging deletes the skill.
- **Pump shutoff head is physics; an injection permissive is not.** Model the head so flow goes
  to zero. Do not implement the real plant's 500 psig interlock — that is a chosen setpoint and
  belongs in the player's Lua.

---

## 3. Architecture

Two Gradle modules, and the split is enforced by the compiler rather than by discipline.

```
core/   pure Java physics. ZERO Minecraft on the classpath. MIT licensed.
        37 main + 19 test files.
        boundary  eccs  fuel  harness  instrument  kinetics  nodal  poison  pool  thermal

mod/    NeoForge integration. MPL-2.0 licensed. 90 files.
        damage  devtest  eccs  flow  fuel  gui  mekanism  peripheral  reactor
        registry  rods  steam  suppression
```

`core/` cannot import Minecraft because Minecraft is not on its classpath. Verify with:

```bash
grep -rn "^import net\.minecraft\|^import net\.neoforged" core/src   # must return nothing
```

`ReactorCore` exposes `toState()` / `fromState()` returning a plain `ReactorState` record. That
single snapshot serves NBT persistence, client sync and the CC peripheral readout. The core never
touches NBT — `mod/reactor/ReactorStateNbt.java` does the conversion.

Tick order inside `ReactorControllerBlockEntity` matters and is load-bearing:

```java
rodNetwork.preStep(dt);   // rods move, accumulators discharge
core.step();              // physics, pressure held CONSTANT within the sub-stepped kinetics
rodNetwork.postStep();    // hardware condition written back
```

Pressure is updated once per tick and frozen inside the kinetics sub-steps. That timescale
separation is what keeps the pressure → void → reactivity positive feedback loop stable. **Do not
"fix" it by updating pressure mid-tick.**

---

## 4. The integrations, and the licensing constraint on them

Both are **soft**: the mod must load and run correctly with either absent. Both are verified by
running, not asserted.

```bash
./gradlew :mod:runData                    # both present
./gradlew :mod:runData -PbwrNoCC          # no CC:Tweaked
./gradlew :mod:runData -PbwrNoMekanism    # no Mekanism
```

Every foreign type is reached only through a `ModList.get().isLoaded(...)` branch in `BwrMod`'s
constructor, and because the check and the reference live in *different classes*, a JVM without
the mod never resolves the type.

**CC:Tweaked must never be bundled.** Parts of its API — `IPeripheral` among them — are still
`LicenseRef-CCPL`, which permits redistribution only "unmodified and in full". It is `compileOnly`
for publishing, `runtimeOnly` for the dev runtime only, and never in `jarJar`. Verify with
`python tools-check-jar.py`; the answer must be zero CC classes.

Mekanism is MIT, so bundling would be *legal*, but it is still not bundled — players install it.
Note `metadataSources { artifact() }` on the modmaven repository: modmaven publishes bare jars
with **no POM**, and without that line Gradle 404s looking for module metadata.

Pinned versions live in `gradle.properties`: NeoForge 21.1.248, ModDevGradle 2.0.143,
CC:Tweaked 1.120.2, Mekanism 1.21.1-10.7.19.85.

---

## 5. Where the project actually stands

`BUILD-STATUS.md` is the authoritative, freshly-verified status with real command output. Summary:

- **`gradle clean build` green**, 117 acceptance tests passing
- **Mod loads** in a real MC 1.21.1 runtime, four dependency combinations
- **21 blocks** with complete assets, audit reports 0 problems
- **10 of 12** SPEC §14 build-order steps done

The two incomplete steps are both blocked on the same fact: **nothing has ever run in a world or
a client.** No model, texture or GUI has been rendered, and **no `@LuaFunction` has ever
executed** — CC:Tweaked now loads, but nothing has called into the peripheral.

That makes the highest-value next action clear and cheap: **launch a client, build a small core,
and take it critical from a Lua console.** It exercises the peripheral, structure validation, CRD
binding, ticking and NBT together, and will expose more real defects than any further building.

Other genuine gaps, all recorded in `BUILD-STATUS.md`: no `BlockEntityRenderer` exists so head-off
core rendering is absent; partial core uncovery is not representable because the thermal model
uses two lumped nodes; SPEC §8.1 stages 4, 6 and 7 have no implementation; containment does not
exist, so the hydrogen the model generates has nowhere to go. Textures are flat-colour
placeholders — the project owner is doing real art separately.

---

## 6. Traps this project already fell into

Each of these cost real time. They will bite you the same way.

**Never trust a piped exit code.** `gradle ... | tail -20` gives you `tail`'s exit code. This
produced a confident report of "BUILD SUCCESSFUL" on a build that had failed with 40 errors.
Always redirect to a file, check `$?`, *then* grep the file.

**A test that measures a defect is not a test that catches it.** A round-trip test quantified that
41% of an in-progress quench spike was lost across a save, and filed it as a "finding" rather than
failing. The bug survived. If you measure something wrong, fail on it.

**Hand-written field lists rot silently.** `assertStatesIdentical` enumerated 26 record components
by hand, so when `hydrogenKg` was added nothing compared it. There is now a reflective test that
perturbs every component and requires the comparator to notice. Prefer reflection over hand lists
for anything that must stay exhaustive.

**Hardcoded scan lists rot the same way.** The `:core` design-rule guard scanned a hardcoded 23
classes while `core/` had grown to 37 — four whole packages were invisible to the project's most
important rule. It now discovers classes at runtime.

API details that are easy to get wrong on this stack:

- `CompoundTag` has int, long and byte arrays but **no double array**. Use `ListTag` of `DoubleTag`
  (`ReactorStateNbt.putDoubles` / `getDoubles`).
- `registerSimpleBlockItem` returns `DeferredItem<BlockItem>`, not `DeferredItem<Item>`.
- ModDevGradle run types are exactly `[client, data, gameTestServer, server, junit]`. There is no
  `clientData()`.
- Minecraft 1.21 renamed the data folders to **singular**: `data/<ns>/recipe/`, `loot_table/`,
  `advancement/`. Getting it wrong silently loads nothing.
- 1.21 recipe results use `"id"`, not `"item"`.
- A block registered `requiresCorrectToolForDrops()` with **no** `mineable/pickaxe` tag can never
  be harvested by anything. Both are required.
- Mekanism 10.7 (1.21) **unified** gas/infusion/pigment/slurry into one `Chemical` type. There is
  no `GasStack` any more.
- `PipeBlock` declares an abstract `codec()` you must override.

---

## 7. Domain facts worth not re-deriving

- **Rod position is 25 discrete notches labelled 00–48 in steps of two**, as the Full Core Display
  reads them. Not a 0–100% float.
- **Point kinetics needs a source term.** Without `+ S`, `n = 0` is an equilibrium, a shutdown core
  decays to exactly zero and source range monitors read nothing. With it, the subcritical
  equilibrium `n = Λ·S/|ρ|` gives both the count-rate floor and the hyperbolic approach to
  criticality that makes 1/M plots work.
- **The integrator is implicit, not explicit.** The Jacobian is an arrowhead matrix, so backward
  Euler collapses to one scalar equation — no linear algebra library. Guard the denominator: it
  can cross zero when `ρ > β`, exactly during a super-prompt excursion.
- **Detectors are paralyzable pulse counters**: `indicated = true · exp(−true · deadTime)`. That
  rises, peaks, then **rolls to zero** — which is why a saturated SRM reads `00.00` rather than
  pegging high, and why that is the cue to switch to IRMs. No special-case branch.
- **Void coefficient steepens with void.** A 1% void step removes ~1.1% of the water at 10% void
  but ~3.45% at 70%. A flat coefficient understates feedback where BWR behaviour actually lives.
- **The Pu/MOX danger is emergent from β**, never a hardcoded penalty. Keepin totals: U-235
  0.006502, Pu-239 0.002099, U-233 0.00266.
- `T_sat(°F) = 115.1 · P(psia)^0.225` — under 0.4 °F error across 800–1400 psia.
- Rated dome pressure is **1025 psig**, not 1150. Design 1250, ASME limit 1375.

`REFERENCE-DATA.md` has all of these with citations to the NRC BWR/6 Systems Manual
(ADAMS **ML20090J537**). That document is deliberately **not** committed — facts are not
copyrightable, but the PDF is not ours to redistribute.

---

## 8. Document map

| File | What it is |
|---|---|
| `HANDOFF.md` | This file. Codebase and environment orientation. |
| `README.md` | The original design handoff. Why the mod exists, principles, scope boundaries. |
| `SPEC.md` | **Authoritative specification.** Read in full before substantial work. |
| `REFERENCE-DATA.md` | Real BWR/6 constants with NRC citations, and the corrections applied to SPEC. |
| `BUILD-STATUS.md` | Verified current state, SPEC §14 progress table, known defects. |
| `spec-v1-draft.md` | Superseded earlier draft. **Do not implement from it.** |
| `tools-audit-assets.py` | Asset integrity check. Exits non-zero on any gap. |
| `tools-check-jar.py` | Confirms no soft-dependency classes are bundled. |

## 9. Before you claim anything is done

Run these, and quote the real output rather than describing it:

```bash
./gradlew clean build                  # 117 tests, 0 failed
./gradlew :mod:runData                 # loads in a real runtime
./gradlew :mod:runData -PbwrNoCC       # still loads without CC:Tweaked
./gradlew :mod:runData -PbwrNoMekanism # still loads without Mekanism
python tools-audit-assets.py           # PROBLEMS: 0
python tools-check-jar.py              # no bundled soft dependencies
```

If you add a guard, **negative-test it** — inject a violation, confirm the build fails, remove it,
confirm it passes. A guard that cannot fail is decoration, and this project has already shipped
one of those.
