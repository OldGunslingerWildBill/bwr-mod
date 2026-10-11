# GPU visual effects — implementation spec (planned, after alpha.31)

Status: **design, not yet built.** Three client-side effects move onto GPU
shaders, each with the current renderer kept as the fallback. Build this after
the containment structure (alpha.31) has shipped.

## 0. Scope and rules

- **Client only.** Nothing here touches the physics core, the server, saves or
  gameplay. A dedicated server never loads any of it.
- **Every player has a GPU.** Minecraft can't run without OpenGL 3.2, so "no GPU"
  isn't a real case. The fallback exists for situations where a custom shader
  can't or shouldn't run:
  1. The shader fails to compile or link on that driver.
  2. A shader pack is active (Iris or Oculus), which replaces the rendering
     pipeline and can break custom shaders.
  3. The player chooses the classic look in the config.
  4. A special render path is in use (outline, destruction overlay, GUI
     preview), where `MachineMeshCache` already falls back today.
- **The fallback is the renderer that already exists.** Don't delete or rewrite
  `CoolingVaporParticle`, `VesselCherenkovGlow` or `VesselWaterSurface`. Each
  new GPU path is added beside its classic renderer, and the classic one keeps
  working unchanged.
- **Same physics, same look.** The GPU path reads the same synced values as the
  classic path: power fraction, water level, wind sample, plume strength. It
  must not need new network fields. If one turns out to be essential, bump the
  menu protocol (currently 12) and document it.
- Follow the existing pattern in `MachineMeshCache`: register the shader in
  `RegisterShadersEvent`, keep a null-checked `ShaderInstance`, and take the
  classic path whenever it is null.

## 1. Choosing the path

New client config option `visualEffects`: `AUTO` (default), `SHADER`, `CLASSIC`.

A small `VisualEffectsMode` class decides once per resource reload, and again
when the config changes:

- `CLASSIC` → classic.
- A shader pack is in use → classic, even when `SHADER` is set (log once that
  the shader pack takes priority). Detect it through the Iris API
  (`IrisApi.getInstance().isShaderPackInUse()`) as a soft dependency, checked
  with `ModList.isLoaded("iris")` or `("oculus")` first. With neither mod
  loaded, assume no shader pack.
- Any of this effect's shaders failed to compile → classic, log the GL error
  once, and don't retry until the next resource reload.
- Otherwise → shader.

Each effect decides on its own, so one failing shader doesn't turn off the
other two. Show the active mode for each effect on the F3 debug screen, e.g.
`BWR visuals: vapor=GPU cherenkov=GPU water=classic (iris)`.

## 2. Cooling-tower vapor: instanced plume renderer

Today each puff is a vanilla `TextureSheetParticle` in a 1,200-particle group,
so every puff goes through the particle engine's per-particle tick, sort and
quad building.

GPU path:

- One `VaporPlumeRenderer` per client level, holding puffs in plain arrays
  (struct of arrays: position, velocity, size, alpha, age, phase). Keep the
  same motion rules as `CoolingVaporParticle.tick()`, including the wind from
  `CoolingVaporWind`, lift, eddies, growth and fade. Write it so both paths use
  one shared motion function, which keeps them looking the same.
- Per frame: upload one small per-instance buffer (position, size, alpha,
  sprite frame) and issue **one instanced draw per tower**
  (`glDrawArraysInstanced`, available in GL 3.1, so it's always present). The
  vertex shader builds the camera-facing quad.
- Keep the current blending: translucent with depth test on and depth write off
  (see the comment in `CoolingVaporParticle` about layers cutting into slices).
  Draw after solid geometry.
- Keep the same total cap (1,200) and the same reaction to the particle
  setting: minimal = none, decreased = half.
- Spawning stays where it is. The spawner sends puffs to whichever path is
  active.

**Ship it only if it's faster.** Use the existing disposable client benchmark
from alpha.13. Measure frame time with two working towers in view, classic
against GPU, on the same machine. Report both numbers. If the GPU path isn't
clearly faster, keep it behind `SHADER` and leave `AUTO` on classic for vapor.

## 3. Cherenkov glow: shader volume

Today: additive layered disks and a side band (`RenderType.eyes`) with
per-vertex alpha.

GPU path: a custom shader `bwr:cherenkov` drawn on the same simple geometry: a
cylinder around the fuel envelope, plus the surface disk.

- Uniforms: `Strength` (from `CherenkovAppearance.strength`, unchanged),
  `CoreTop`, `CoreBottom`, `Surface`, `Radius`, `GameTime`.
- The fragment shader computes intensity from:
  - radial distance from the core axis (bright at the fuel, fading toward the
    shroud);
  - water depth above the point (light fades toward the surface, matching the
    current `.85/(1+depth*.12)` falloff);
  - a very slow, faint shimmer from `GameTime`, scaled by strength. No strobing:
    decay heat glow stays steady.
- Color stays Cherenkov blue (the classic path's ratio, 0.04 : 0.6 : 1), with
  premultiplied additive blending, so zero strength gives exactly zero light.
- The same rules apply: no glow without water, without fuel, or above the
  water surface.

## 4. Vessel water surface: animated shader

Today: a flat translucent disk, `block/water_still` texture, 64 segments.

GPU path: a custom shader `bwr:vessel_water` on the same disk (raise it to
96–128 segments if needed for smooth edges).

- Ripples: two or three crossing sine waves, plus scrolling noise in the
  fragment normal. Light it with the world light value already passed in.
- **Tie it to the plant state** using values the client already has:
  - Ripple amplitude grows with power fraction (boiling). A cold, shut-down
    vessel is nearly still.
  - A fast-falling level (blowdown, LOCA) adds stronger, choppier motion. Use
    the rate of change of the already-interpolated client level; it doesn't
    need syncing.
- Keep the same height (`VesselWaterGeometry.height`), radius and inner-shell
  clipping, so the existing water-surface GameTest still applies to both paths.
- Alpha and color match the classic disk at rest, so switching modes doesn't
  jump.

## 5. Tests and checks

Add to the client checks (`RvuClientCheck`, `CoolingClientCheck` or a new
`GpuVisualsClientCheck`):

- With each mode forced (`SHADER`, `CLASSIC`), every effect renders with no GL
  errors (`glGetError` after the draw).
- A deliberately broken shader source (test resource) falls back to classic
  without crashing and logs once.
- Simulating "shader pack in use" selects classic.
- The vapor cap and particle-setting scaling hold on both paths.
- Cherenkov strength 0 draws nothing on both paths. Glow never appears above
  the water surface.
- The water height at BAF and TAF matches between paths (reuse the
  `waterSurfaceFitsAllVesselSizes` geometry).
- The resource reload (F3+T) recreates the shaders and keeps working.
- World unload and shutdown free every GL buffer, the same as
  `MachineMeshCache.clear()`.

The required GameTests are server-side and are unaffected.

## 6. Build order

1. `VisualEffectsMode`, the config option and the F3 line, with all three
   effects still classic.
2. Cherenkov shader (the smallest and lowest risk).
3. Water surface shader.
4. Vapor instancing, then the benchmark. Make the `AUTO` decision from the
   measured result.
5. Documentation: a `GPU-VISUALS-UPDATE.md`, a changelog entry and the version
   bump.
