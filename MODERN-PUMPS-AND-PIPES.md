# Modern pump flanges and dyeable pipes

## Pump changes

HPCS, LPCS, RHR/LPCI, electric feedwater and turbine feedwater now use
Blender-authored round pipe necks and bolted flanges. Each port ends at the center
of one Minecraft block face. Connect the pipe directly outside that face.

| Machine | Width × height × depth | Body change |
| --- | --- | --- |
| LPCS | 5 × 5 × 3 | Original scale; centered ports |
| HPCS | 9 × 4 × 3 | Original scale; centered ports |
| RHR/LPCI | 7 × 3 × 3 | 15% larger body |
| Electric feedwater | 7 × 3 × 3 | 25% larger body |
| Turbine feedwater | 9 × 3 × 3 | 25% larger body |

Dimensions describe the reserved placement volume. One unit is one block. All
five use a controller at the center of the base; the machine extends to either
side of the clicked block. Their original textured casings, motors, guards and
nameplates are retained. The DVSS recirculation pump is unchanged.

See [the port table](PUMP-MODELS.md#ports) for offsets relative to the controller.
Suction accepts ordinary water pipes; pressure-side discharge uses BWR water
pipe. Only the turbine feedwater machine has steam admission/exhaust ports among
these five pumps. Existing power, flow curves and controls are unchanged.

### Upgrading placed pumps

Saved assemblies keep their previous geometry, size, ports and controller data.
**Pick up and place each pump again** to use the modern model, then reconnect its
pipes at the new flanges. Ensure the larger footprint is clear before placement.
Loading an existing world never expands a machine through adjacent equipment.
As with other pump replacement, check its controls and supplies after placing it.

## Round steam and water piping

Both existing pipe items now use Blender-built steel fittings: round barrels,
swept elbows, cast junctions and bolted coupling rings. The model follows all 64
possible directional connection patterns. Existing pipe networks update their
appearance without replacement.

The default identification bands are **blue for water** and **amber for steam**.
Right-click a placed pipe with any of the **16 vanilla dyes** to recolor its bands.

- Recoloring one segment uses one dye in survival. Creative uses none.
- Applying its existing color uses no dye.
- Colors persist across saves, neighbor changes and structure rotation.
- Dye changes appearance only. Water and steam remain separate systems, and
  differently colored segments of the same pipe type still connect.
- Breaking and replacing a segment restores its default service color.

The steel remains unpainted, so colored bands stay easy to identify. There is no
automatic repainting of connected networks.

## Authoring and export

Editable source: [`modern_pumps_and_pipes.blend`](art/models/modern/modern_pumps_and_pipes.blend).
The file contains individual pump scenes, all pipe connection patterns, packed
textures and studio previews. No Blender installation is needed to play.

`art/models/modern/build_models.py` runs inside Blender with the original
`BWR | <pump_id>` gallery scenes loaded. It copies their parts into isolated
scenes and builds the new flanges and pipe meshes. `preview_models.py` renders
the preview gallery and saves a normal editable Blender project.

From the repository root:

```powershell
python tools-export-modern.py
python tools-export-modern.py --check
python tools-audit-assets.py
```

The exporter reads Blender mesh/UV snapshots, clips pumps into block cells and
checks surface-area conservation. It retains the old layouts for saved worlds.
Pipe material tint index 0 marks the dye bands; the remaining metal is untinted.
