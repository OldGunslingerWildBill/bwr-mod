# Phase L1: gameplay config

The server config `serverconfig/bwr-gameplay.toml` exposes the following
calibration. Every default matches the pre-L1 value.

| Setting | Default | Effect |
| --- | ---: | --- |
| `failureHazardPerSecond` | 0.02 | Chance of a pressure-boundary rupture after accumulated stress; zero leaves wear but prevents the random rupture draw. |
| `accidentProgressionMultiplier` | 1.0 | Relocation, lower-head creep, basemat ablation and gas yield, ex-vessel release time. It does not change fuel melting energy or decay heat. |
| `contaminationHalfLifeSeconds` | 86,400 | Decay of plant and site hazard indices. |
| `contaminationDoseMultiplier` | 1.0 | Nearby player status-effect hazard; zero suppresses the effects while retaining contamination records. |
| `antimonyExposureHours` | 48 | Antimony-beryllium source activation at rated local flux. |
| `cobaltExposureHours` | 72 | Cobalt target irradiation at rated local flux. |
| `tritiumExposureHours` | 168 | Tritium target irradiation at rated local flux. |
| `siliconExposureHours` | 24 | Silicon target irradiation at rated local flux. |
| `wallStrengthK` | 5.8 | Default block pressure rating = `K * sqrt(blast resistance)`. |
| `wallStrengthCapPsig` | 250 | Maximum contribution of one wall block. Explicit datapack strength overrides still take precedence. |
| `tritiumSamplesPerRod` | 1,250 | Items harvested from a completed tritium target; each sample retains its existing 10,000 mB Mekanism recipe. |

The config is applied to loaded reactor physics each server tick and refreshed
when the server config loads or reloads. Irradiation progress is stored as
equivalent rated-flux seconds; changing a target time changes the completion
fraction of an existing cassette without deleting its accumulated exposure.

The new `GameplayTuningTest` first failed to compile before the tunable core
API existed. After implementation its four physics tests passed. The new
`PhaseLGameplayConfigTests` changes settings during its own isolated GameTest
batch, checks they reach the core and affect a wall rating and tritium harvest,
then restores all original settings. The known unchanged
`ContainmentStructurePhysicsTest.test03` failure from J-fix4 remains; see
`PHASE-J4-REVIEW.md`.

Verification:

- `:core:acceptance --args='GameplayTuningTest BoundaryStressTest SevereAccidentEscalationTest CoreMeltProgressionTest ContainmentHydrogenTest FuelVarietyTest'`: **48/48** passed.
- `:mod:runTurbineGameTest :mod:checkNoProtectionLogic`: **196/196** GameTests passed in the repeat persistent-world run; protection scan passed, 531 mod files.
- `:mod:compileJava`: passed, with one pre-existing Mekanism deprecation warning.
- The full core suite was last run for J-fix4 and failed only at the unchanged containment structure test. It was not rerun after L1; the affected core classes were covered by the targeted run above.
