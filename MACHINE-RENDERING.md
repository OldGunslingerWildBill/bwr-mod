# Machine rendering — alpha.13

Large static exteriors reuse GPU vertex buffers. Models are still the original
Blender OBJ assets, baked by NeoForge. The cache removes repeated CPU vertex
submission; it does not reduce model detail or change plant simulation.

## What is cached

| Object | Cached geometry | Updates each frame |
| --- | --- | --- |
| Cooling towers and water machines | One shared buffer per model/version | Placement, light, separate fan rotation |
| Condensers | One shared buffer per model/version | Placement and light |
| Reactor exterior | Complete assembled shell, heads, studs, welds and port spools | Placement and light |
| Condensate tanks | Complete tank per diameter/height | Placement and light |
| Suppression pool | Water-surface and header component meshes | Water height and component placement |
| HP/LP turbines, generators and other block-model pumps | Existing Minecraft chunk meshes | Existing chunk-rendering path |

Each shape is uploaded on first use. Reactor envelope/port/head changes and
tank size changes select another cached shape. Changes in time of day, camera
position, machine measurements or fan angle do not rebuild its mesh. Fan
instances share their mesh but have independent rotation transforms.

Material vertex colors, textures, fog, lightmaps and overlays are retained.
Special destruction/outline vertex consumers use the normal CPU submission
path so their effects are not silently lost. The cache targets 96 MiB of vertex
data and at most 128 entries, evicting least-recently-used shapes; a single shape
larger than the target can occupy the cache alone. Minecraft manages the shared
quad index buffer separately. Reloading resource packs, leaving the client world,
and shutting down close the GPU buffers. Cache keys retain no world or entity.

## Visibility

Rendering distance is measured to the nearest point of a machine's full bounds.
The reactor, towers, condensers and tanks participate independently of the
controller block's visible chunk section. This prevents an otherwise visible
tower top disappearing when the camera cannot see its base.

In NeoForge 21.1.248, `shouldRenderOffScreen` selects the global block-entity
list, but **does not bypass the full bounding-box frustum check**. The flag's
name is misleading here. We retain finite machine bounds and verify that looking
away produces zero renderer calls. Frustum rejection checks the camera's view;
this change does not add a separate system for detecting buildings hidden behind walls.

## Verification and limits

Run the disposable client scene with:

```powershell
.\gradlew.bat :mod:runTurbineModelCheck -PbwrMachineRenderCheck
```

It renders the same plant through cached and uncached vertex submission paths,
reports renderer CPU time and total frame rate, and repeats the cached sample.
The plant includes both towers, a reactor, an LP/condenser pair, a tank and a
circulating-water pump. Particles are disabled to isolate model submission.
Checks also cover zero steady-state uploads, off-screen rejection, a visible
tower top with its controller off-screen, night lighting, a real resource reload
and buffer cleanup. The fixture is excluded from the distributed JAR.

The separate cooling check exercises animated fans and working vapor; the vessel
check exercises multiple sizes, the open head, shell breach/repair and removal.
Executed results and the artifact hash are recorded in [BUILD-STATUS.md](BUILD-STATUS.md).

### Local comparison

The September 25 test used Minecraft 1.21.1 / NeoForge 21.1.248, 1280 × 720,
12-chunk view distance, VSync off and an RTX 5080. The six custom-rendered
machines were visible throughout each sample. Each sample excludes two seconds
of warm-up and measures approximately six seconds of steady rendering.

| Path in the same test build | Renderer CPU time per frame | Observed whole-scene FPS |
| --- | ---: | ---: |
| Uncached vertex submission | 31.88 ms | 27 |
| Cached GPU meshes, first sample | 0.161 ms | 713 |
| Cached GPU meshes, repeated sample | 0.148 ms | 750 |

The uncached path is the normal vertex-consumer fallback in the same build,
not a separate historical-release benchmark. CPU timings measure the renderer
calls and do not measure GPU execution time. Both paths submit the same model
detail. Seven reusable meshes occupied 25,211,232 vertex bytes, with no uploads
during the steady samples. Looking away produced zero machine renderer calls.
A real resource reload rebuilt seven meshes once and then resumed stable reuse.

This pass preserves full detail and the existing vapor. GPU geometry cost and
transparent-cloud overdraw still exist. No distance-based simplified models or
new vapor budget are introduced. Measurements are controlled local comparisons,
not FPS guarantees for a player's modpack. Third-party shader packs and render
replacements require their own compatibility testing.
