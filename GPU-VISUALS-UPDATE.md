# GPU visuals (alpha.32)

This update adds client-only GPU paths beside the original renderers. There are
no new server rules, save fields, or network fields. The three effects read the
values already synchronized for the classic path.

## Choose a mode

Set `visualEffects` in the client file `config/bwr-visuals.toml`:

| Value | Behavior |
|---|---|
| `AUTO` (default) | GPU Cherenkov and vessel water; classic cooling vapor. The visible two-tower benchmark did not justify enabling GPU vapor by default. |
| `SHADER` | Request GPU Cherenkov, vessel water, and instanced tower vapor. |
| `CLASSIC` | Use the original three renderers. |

A running Iris/Oculus shader pack takes priority and selects classic even under
`SHADER`. The mod checks for the optional shader mod before asking its API if a
pack is actually active. The F3 screen reports the active mode of each effect.
If a shader fails to compile, link, or draw, only that effect falls back. It
logs once and retries after a resource reload (F3+T). Preview and special
buffer paths use the classic renderer.

## Effects

* Cherenkov is a shader drawn on the fuel-envelope cylinder and submerged
  disks. It uses the existing power, fuel, and water conditions. With no fuel,
  no water, or zero strength, it draws nothing. Its additive blue light is
  clipped at the water surface.
* Vessel water uses the same height and inner radius as the classic disk. A
  112-segment disk carries ripples driven by power and by falling level inferred
  from consecutive client samples. It uses the world light supplied to the
  vessel renderer.
* Cooling vapor uses one `glDrawArraysInstanced` call per tower. Its 1,200-puff
  shared cap and minimal/decreased particle settings match the classic path.
  Both paths call the same wind, lift, eddy, growth, and fade calculation.
  Puffs draw after particles with depth testing on and depth writing off.

GL buffers are released on client world unload, game shutdown, and resource
reload. Shader registration uses NeoForge's `RegisterShadersEvent`; a failed
effect can recover after F3+T. The intentionally broken shader used by the
disposable client check is excluded from the published mod jar.

## Verification

The disposable RVU client check forces `SHADER` and `CLASSIC`, compiles a
deliberately broken shader, simulates an active shader pack, reloads resources,
and checks GPU draws, dry-core clipping, and GL-error fallback. The cooling
client check measures the same two-tower view in classic and instanced modes.
The existing server GameTests remain unchanged by these visual paths.

The valid two-tower run with visible puffs measured **1.376 ms mean / 1.922 ms
p95** for classic (1,029 puffs) and **1.452 ms mean / 2.215 ms p95** for GPU
(1,031 puffs). Earlier instanced timings were invalid because a quad-winding
mistake made the plume invisible; they are excluded from the AUTO choice. The
visible GPU result is 5.5% slower on mean frame time and 15.2% slower at p95.
`SHADER` keeps it available for players to try on their own hardware. The
disposable client check saves `vapor-classic.png` and `vapor-gpu.png` beside
the other cooling screenshots.

A repeat visible run measured **1.440 ms mean / 2.122 ms p95** for classic
(1,024 puffs) and **1.520 ms mean / 2.443 ms p95** for GPU (1,016 puffs).
The GPU path remained slower, so the default choice is unchanged. The final
dedicated-server run passed **193/193 required GameTests** and
`checkNoProtectionLogic`; the packaged jar includes the three production
shaders and excludes the deliberately broken test shader.
