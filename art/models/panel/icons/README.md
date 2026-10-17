# Panel item icons

`render_icons.py` renders the inventory icons of the panel component items from their exported panel models, so an
item looks like the component it places. Each icon is a flat 32 x 32 texture, the cheapest thing an inventory can
draw. The 3D model is only drawn on the panel itself.

    blender --background --factory-startup --python art/models/panel/icons/render_icons.py
    blender ... --python art/models/panel/icons/render_icons.py -- dial_gauge lamp    (only these items)

The script works as follows:
- It reads `mod/src/main/resources/assets/bwr/models/panel/<model>.json`, the files each family's `build.py`
  exports.
- It rebuilds each model with its own face colours, leaving out the glass windows and drawing lit lamp lenses in
  their colour.
- It renders the model head-on with a small tilt (orthographic, transparent background, 128 px).
- It averages the render down to `textures/item/panel/<item>.png`.

The 128 px renders stay in `tmp/panel-icons/`, which git ignores.

The item-to-model table (`ICONS`) follows each type's default style in `PanelStyles`. The two recorder items show
their own make. Rerun the script after changing a model.

The wrench icon (`panel_wrench.png`) is hand-drawn pixel art, not a render.

The other panel items still use vanilla placeholder icons because they have no Blender model: they are drawn in code
(bar graph, segment displays, LCD, graphic display, annunciator, strip chart, thumbwheel, toggle switch, nameplate
and the mimic pieces).
