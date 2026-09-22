# Realistic BWR — Phase One Verification Audit

**Date:** 21 September 2026 (local time)
**Snapshot:** local working tree after the repairs in `PHASE-ONE-FIXES.md`,
including uncommitted condenser, cooling and tank work. Git base:
`b77cf001ce07a61342e4c11022e926aed80bd693`.

## Result

**The standard build passes, but the code is not yet clear of known defects.**
This audit found **five remaining P2 correctness/reliability issues**. Four
were reproduced in a real NeoForge GameTest server; the fifth was reproduced
using the compiled recirculation sizing code and the controller's command
conversion. These are additional cases missed by the existing regression tests.

No production code was changed during this verification. Temporary probes live
under ignored `tmp/`; the normal build configuration and release JAR exclude
them. No commits, pushes or GitHub issue changes were made during this audit.

| ID | Priority | Remaining issue | Measured result |
| --- | --- | --- | --- |
| R01 | P2 | Shared condenser ports overstate available water capacity | Simulated acceptance 200; actual acceptance 100; standard transfer lost 100 fluid units |
| R02 | P2 | Basin revalidation discards newly condensed water | Unchanged 64-block basin fell from 64,100 kg to 64,000 kg |
| R03 | P2 | Remote computer peripherals retain discarded controllers | A 37% command changed the removed controller; the replacement remained at 100% |
| R04 | P2 | An unused closed MSIV branch can stop the open header | Nozzle flow fell from 485.09 kg/s to zero |
| R05 | P2 | Recirculation flow commands do not invert shared jet limits | A 50% flow command produces 83.33% delivery with 12 jets and two pumps at commanded speed |

P2 here means a reproducible functional defect under the conditions described
below. Resolve these before treating phase one as fully verified. Existing tests
passing is evidence for the cases they cover, not for every possible layout.

## R01 — Count a shared fluid inventory once during simulation

**Locations:**
[water network sink discovery](C:/Users/14238/Downloads/bwr-mod-handoff/bwr-mod/mod/src/main/java/dev/bwr/mod/water/WaterLineNetwork.java:70),
[fill accumulation](C:/Users/14238/Downloads/bwr-mod-handoff/bwr-mod/mod/src/main/java/dev/bwr/mod/water/WaterLineNetwork.java:94),
[condenser capability wrappers](C:/Users/14238/Downloads/bwr-mod-handoff/bwr-mod/mod/src/main/java/dev/bwr/mod/condenser/CondenserCapabilities.java:12).

**Trigger:** connect two cold-water flanges on the same condenser to one
high-pressure water pipe network, with the condenser nearly full.

`WaterLineNetwork.inlet().fill` deduplicates destinations by Java handler
identity. Each flange now supplies a separate live wrapper, although the
wrappers reach the same condenser inventory. A simulated fill does not mutate
that inventory, so each wrapper reports the same free space. Execution fills
the shared inventory through the first wrapper and has less room through the
second.

**Executed evidence:** a server fixture with exactly 100 units free returned
200 for `fill(200, SIMULATE)` and 100 for `fill(200, EXECUTE)`. Calling NeoForge's
actual `FluidUtil.tryFluidTransfer` with a 200-unit source emptied that source
but added only 100 to the condenser. The other 100 units were lost. This is a
reproduced standard fluid-handler transfer, not a claim that every third-party
pipe uses that exact transfer implementation.

**Repair direction:** identify destinations by their logical owner and tank
role, or reserve shared capacity while simulating the network. Preserve live
owner resolution from the earlier capability fix. Add a two-flange,
nearly-full regression that checks source plus destination inventory before
and after a normal transfer. Check the equivalent shared tank/pump layouts.

## R02 — Preserve condensed inventory during an unchanged basin survey

**Locations:**
[claim on every survey](C:/Users/14238/Downloads/bwr-mod-handoff/bwr-mod/mod/src/main/java/dev/bwr/mod/suppression/SuppressionPoolBlockEntity.java:625),
[capacity refresh](C:/Users/14238/Downloads/bwr-mod-handoff/bwr-mod/mod/src/main/java/dev/bwr/mod/suppression/SuppressionBasinData.java:41),
[inventory clamp](C:/Users/14238/Downloads/bwr-mod-handoff/bwr-mod/core/src/main/java/dev/bwr/core/pool/SuppressionPool.java:283).

**Trigger:** a suppression basin receives steam, increasing its modeled water
mass above its original source-block mass, then undergoes routine revalidation.

Every successful basin claim calls `resizeCapacityKeepingInventory`, even when
geometry did not change. That method clamps current mass to the source-block
design mass. `condenseSteam` legitimately added mass above that baseline, so
the next survey removes it without a drain or accounted overflow. The normal
formed-basin revalidation interval is 600 ticks; topology changes can bring
the next survey forward.

**Executed evidence:** form a 64-source-block basin, condense 100 kg of steam,
then invoke its normal validation method without changing any blocks:

```text
design=64000 before=64100.0 after=64000.0
```

**Repair direction:** an unchanged survey must preserve the complete current
inventory. If capacity or overflow limits are desired, make their mass and
energy accounting explicit during physical updates, rather than silently
discarding water in structure validation. Retain the one-owner/no-refill
protection from the previous repair.

## R03 — Rebind remote computer controls when a controller is replaced

**Locations:**
[remote peripheral registration](C:/Users/14238/Downloads/bwr-mod-handoff/bwr-mod/mod/src/main/java/dev/bwr/mod/peripheral/BwrPeripheralSupport.java:35),
[captured controller and command](C:/Users/14238/Downloads/bwr-mod-handoff/bwr-mod/mod/src/main/java/dev/bwr/mod/peripheral/EccsPumpPeripheral.java:50).

**Trigger:** a computer connects at a remote assembly part that stays loaded
while the controller instance is discarded and restored, such as an assembly
crossing a chunk boundary.

The earlier repair makes fluid and FE proxies resolve the current controller.
The computer registration still constructs a peripheral containing a final
reference to the controller found at capability lookup. Changing the root
does not invalidate the cached capability at the remote child, and its methods
continue to operate on the removed instance. Instrumentation can be stale and
control commands do not reach the live machine. The same registration pattern
exists for other modeled pump and power-module peripherals.

**Executed evidence:** a real NeoForge `BlockCapabilityCache` at a remote HPCS
part retained the same peripheral after its controller was removed and restored
from NBT. Calling `setSpeed(.37)` produced:

```text
cachedSame=true removed=true oldTarget=0.37 liveTarget=1.0
```

This probe exercises engine block-entity removal/NBT replacement and an actual
capability cache. It does not simulate a complete long-running wired-modem
network or every third-party chunk loader.

**Repair direction:** resolve the live owner for each computer operation and
handle temporary absence, or reliably invalidate/rebind all affected remote
peripherals through controller lifecycle changes. Include reconnection when a
lookup initially occurs while the controller is unavailable.

## R04 — Apply MSIV isolation to the route actually used

**Locations:**
[route control detection](C:/Users/14238/Downloads/bwr-mod-handoff/bwr-mod/mod/src/main/java/dev/bwr/mod/steam/SteamValveRouting.java:63),
[legacy network-wide minimum](C:/Users/14238/Downloads/bwr-mod-handoff/bwr-mod/mod/src/main/java/dev/bwr/mod/reactor/RpvSteamOutletBlockEntity.java:583).

**Trigger:** a steam header has an open route to a turbine outlet and a closed
MSIV on an unused side branch, with no turbine control/stop/bypass valve in
that network.

`SteamValveRouting.nozzleOpening` considers MSIV positions but only marks the
network controlled when it finds a `TurbineValveBlockEntity`. In an MSIV-only
layout it returns the fallback signal. The nozzle then takes the minimum
opening of every surveyed MSIV, including unused branches. That global limit
stops flow before the newer consumer-specific route logic can use the open
route.

**Executed evidence:** RPV nozzle, three pipe cells and a turbine steam outlet;
add a closed MSIV as a side branch from the middle pipe. At the same 1,025 psig
input, reported nozzle flow changed from **485.0918401388889 kg/s to zero**.

**Repair direction:** make the nozzle's MSIV-only policy path-aware too. Test
unused branches, parallel open/closed routes, and an actually inline closed
MSIV so correcting the branch case cannot bypass intended isolation.

## R05 — Convert requested core flow using the actual delivery function

**Locations:**
[command conversion](C:/Users/14238/Downloads/bwr-mod-handoff/bwr-mod/mod/src/main/java/dev/bwr/mod/reactor/ReactorControllerBlockEntity.java:229),
[shared delivery limit](C:/Users/14238/Downloads/bwr-mod-handoff/bwr-mod/core/src/main/java/dev/bwr/core/flow/RecirculationSizing.java:41),
[measured flow](C:/Users/14238/Downloads/bwr-mod-handoff/bwr-mod/mod/src/main/java/dev/bwr/mod/flow/RecirculationNetwork.java:135).

**Trigger:** use reactor computer `setRecirculationFlow` with multiple external
pumps whose combined drive capacity exceeds the shared installed jet capacity.

The command divides requested flow by the capped maximum-flow fraction and
sends that speed to every motor. Actual delivery is the sum of individual pump
contributions, capped by shared jet capacity. Those functions are not inverses:

```text
required jets = 12; installed jets = 12; external pumps = 2
maximum flow fraction = 1
requested flow fraction = 0.5
commanded speed per pump = 0.5
delivery = min(12, (0.5 + 0.5) * 10) / 12 = 0.833333...
```

**Executed evidence:** a Java probe used the actual compiled
`RecirculationSizing` functions and the controller's conversion, reproducing
**50% requested versus 83.33% delivery at the commanded speeds**. This is a
deterministic arithmetic reproduction, not a full in-world motor spin-up run.
Insufficient power and motor acceleration still limit actual pump speed.

**Repair direction:** solve for motor demand using uncapped contribution rates
and the shared delivery ceiling. Preserve the ten-jets-per-pump rating and
physical motor/energy limits. Cover multiple external pumps, mixed internal and
external pumps, partial demand, and excess installed capacity.

## Verification performed in this audit

| Check | Result |
| --- | --- |
| `:core:check` | **182 passed, 0 failed**, 880.1 seconds |
| Existing `:mod:runTurbineGameTest` suite | **12 passed**, CC:Tweaked and Mekanism present |
| Full `build` with the above checks | **Passed**, 15 minutes 21 seconds |
| Four additional scratch GameTests | **All four reproduced their intended failure**; existing 12 still passed in the combined run |
| Standalone recirculation calculation | Reproduced the request/delivery mismatch |
| Asset audit | **2,170 JSON files, zero problems** |
| Release JAR audit | **303 classes**; no bundled CC/Mekanism classes or development tests; notices present |
| Normal classes/JAR tasks after scratch probes | Passed; scratch probe class absent from normal class output |
| Whitespace validation | `git diff --check` passed |

The scratch suite is intentionally red because it asserts the desired behavior
for the newly found defects. Its failure is separate from the successful normal
build. No scratch tests were added to the production source tree.

**Unchanged release artifact:** `mod/build/libs/mod-0.1.0-SNAPSHOT.jar`
**SHA-256:** `61EBC479DBC3052D4C6DEBE6DFBA5649EEBC18228B5DD6CF32603C8D100AE5D2`

### Local evidence

- [Full verification log](C:/Users/14238/Downloads/bwr-mod-handoff/bwr-mod/tmp/phase-one-reaudit-build.log)
- [Runtime probe results](C:/Users/14238/Downloads/bwr-mod-handoff/bwr-mod/tmp/phase-one-reaudit-probes.log)
- [Runtime probe source](C:/Users/14238/Downloads/bwr-mod-handoff/bwr-mod/tmp/reaudit-src/dev/bwr/mod/devtest/ReauditProbes.java)
- [Recirculation calculation](C:/Users/14238/Downloads/bwr-mod-handoff/bwr-mod/tmp/ReauditSizing.java)
- [Recirculation result](C:/Users/14238/Downloads/bwr-mod-handoff/bwr-mod/tmp/reaudit-sizing.log)
- [Asset audit](C:/Users/14238/Downloads/bwr-mod-handoff/bwr-mod/tmp/phase-one-reaudit-assets.log)
- [JAR audit](C:/Users/14238/Downloads/bwr-mod-handoff/bwr-mod/tmp/phase-one-reaudit-jar.log)

The ignored probe sources can be rerun with
`gradlew.bat --init-script tmp/reaudit.init.gradle :mod:runTurbineGameTest`.
They are local audit evidence and are not included in Git or the release JAR.

## Scope and limits

The review covered core physics/control calculations and the mod's reactor,
fuel, fluid/steam networks, multiblock lifecycle, persistence, capabilities,
computer/GUI command paths, registrations, assets and packaging. The source
inventory is 45 core and 172 mod production Java files, plus 24 existing mod
development-test Java files. This is a broad audit, not proof that every
possible defect has been found.

The earlier dedicated-server optional-mod absence and six spawn-protection
checks remain recorded in `BUILD-STATUS.md`; they were not rerun in this audit.
There was no new interactive client/Blender visual inspection, large multiplayer
load test, or long-duration chunk-loader stress test.

Previously documented phase-two gaps remain separate: LP turbine exhaust is not
yet fully coupled to the external condenser, and arbitrary plant pipes do not
yet transport temperature/enthalpy. Automatic protection remains an intentional
player-controlled design choice. They were not counted again as new defects.
