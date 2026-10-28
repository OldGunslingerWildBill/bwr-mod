# Fabrication desk

The control-room parts are made at a bench, not in the crafting grid. You open the **Fabrication Desk**, pick a part from a list,
see what it is made of and how much of it you carry, and press **Craft**. The desk takes the materials out of your inventory and
gives you the part. There is no pattern to remember and no grid to lay out: a part costs a number of ingredients, and that is all.

This covers the 42 parts that used to be grid recipes in the panel and furniture group and in the panel component group: the panel
bodies and trim, the desks, the relay cabinets, the control stand, the wall displays, the gauges, switches, indicator lamp,
pushbutton, RPS trip, annunciator window, bar graph and segment displays. Everything else in the plant is crafted as before.

## The block

`bwr:fabrication_desk`, a workshop bench: a steel drawer cabinet, a knee space with a shelf, a slate work top with a parts tray and
a tilted drawing board, and a pegboard at the back with a few tools and an amber lamp. It has a facing and no other state. It
is crafted in the grid, with a vanilla recipe and a Mekanism recipe like any other tiered item (MEKANISM-RECIPES.md):

| Install | Recipe |
| --- | --- |
| Without Mekanism | 3 smooth stone slabs across the top, redstone, a crafting table and redstone across the middle, an iron ingot at each foot (`fabrication_desk.json`) |
| With Mekanism | 3 steel ingots across the top, a basic circuit, a crafting table and a basic circuit across the middle, a steel ingot at each foot (`fabrication_desk_mekanism.json`) |

It has **no block entity and no ticker**: nothing is stored in the block, so a desk costs the server nothing while nobody uses it,
and it has no computer face (the CC:Tweaked rule that every block entity needs one does not apply). Right-click opens the screen
(any player but a spectator). It is mined with a pickaxe and drops itself.

The model is built in headless Blender from box geometry, the way the control-room furniture is
(`blender --background --factory-startup --python art/models/fabrication_desk/build_fabrication_desk.py`, which writes the
`.blend` beside it and the OBJ under `assets/bwr/models/block/fabrication_desk/`; `python tools-export-fabrication-desk.py` writes
the model, blockstate, item model and loot table around it). It has no maker's name or mark on it.

## The screen

Laid out like the gun smith table of TaCZ, in the mod's own screen style (GUI-AUDIT.md):

- **Tabs** across the top: Panels and trim, Desks and furniture, Switches, Gauges and meters, Lamps and annunciators, Displays.
- A **list** of the parts in the tab, with their icons, scrollable (wheel, Page Up and Down, or Up and Down on the focused list). A
  green circle marks a part you can make right now.
- A **preview** of the selected part, drawn large, and how many one craft makes.
- Its **materials**: an icon, a name and *have / need* for each, in green with a circle when you have enough and in red with a
  diamond when you do not (shape and colour, so it reads without telling the colours apart). A tag ingredient shows one of its items
  and says in its tooltip that any item of the tag works; its count adds up every item the tag names, in every stack.
- A **Craft** button, off while any material is short. A click makes one craft. **Shift-click makes as many crafts as your materials
  cover, up to 8 and never more than one stack of the result** (a part that stacks to 64 and is made 4 at a time is capped at 8
  crafts, 32 parts). The line above the button says what the last request came to.

What you hold comes from the server (the paying stacks, sent when the inventory changes and after every request), not from the
client's own inventory, which is only brought up to date when no other screen is open. So the numbers are the ones the server will
check. The screen has no slots.

`GuiScreenCheck` (`:mod:runTurbineModelCheck -PbwrGuiScreenCheck`) has three pictures of it (a gauge with some of its materials, a
switch with all of them and the answer to a craft, the longest tab scrolled with nothing held), at GUI scales 2, 3 and 4 and at the
320 x 240 and 427 x 240 minimum sizes. It checks that nothing leaves the window or the panel, that the body does not scroll, that
every control has a tooltip and a narration title and can be reached with Tab.

## What the server decides

The screen decides nothing. Craft sends one command (`FabricationDeskMenu.CMD_CRAFT`) with a recipe key (the hash of the recipe id)
and a number of crafts (1, or 8 for a shift-click), and the server does all of this, every time, in this order
(`FabricationCrafting.craft`):

1. **A request rate limit**: a bucket of 10 requests, refilled at 10 a second per player (`PanelRateLimit.Kind.FABRICATE`, dropped
   when the player leaves). Over it the request is answered *too fast* and nothing happens.
2. **May operate**: not a spectator and not dead (`AssemblyAccess.mayOperate`, the test every panel control uses). Adventure mode
   may craft, as it may press a panel button on an adventure map.
3. **A desk**: the position is a loaded fabrication desk in the player's level.
4. **Reach**: the player is within the block interaction range plus 4 blocks of it, which is what vanilla gives an open container
   screen (`stillValid`), so an open screen is never refused for distance and a client that stays "open" far away is.
5. **The recipe**: the key names a recipe this server has loaded (so a part the install does not make cannot be asked for).
6. **The materials**: the 36 main slots and the off hand, never armour, counted from the inventory itself (the client's counts are
   never read). A craft count is capped at 8 and at one stack of the result, and reduced to what the inventory pays for; none means
   *not enough materials*.
7. **Pay, then give**, in one go on the server thread. The payment is planned before anything is touched (an item is spent by the
   first ingredient that matches it, a stack is never spent twice), so it cannot fail halfway. The part goes onto a stack that has
   room, then into an empty slot; **what does not fit is dropped at the player's feet, never deleted** (also for a creative player,
   for whom `Inventory#add` would delete it).

**Creative players pay like everyone else.** The mod gives nothing free anywhere else, and a creative player can take the part from
the creative tab anyway. There is no ticker and no chunk is loaded or kept loaded by any of this.

## The recipe type

`bwr:fabrication`: a result with its count, a category (the tab), and a list of ingredients, each an item or a tag with a count.

```json
{
  "type": "bwr:fabrication",
  "category": "gauges",
  "ingredients": [
    { "item": "minecraft:iron_ingot", "count": 2 },
    { "item": "minecraft:glass_pane", "count": 1 },
    { "tag": "c:circuits/basic", "count": 1 }
  ],
  "result": { "id": "bwr:dial_gauge", "count": 1 }
}
```

The categories are `panels`, `furniture`, `switches`, `gauges`, `lamps` and `displays` (`FabricationCategory`); an unknown one
fails the recipe at load. An ingredient is NeoForge's flat sized ingredient. A recipe has one to five ingredients (the screen lists
five), counts from 1 to 64, nothing named twice, and **no item that two of its ingredients both match** (the desk pays them in
order, so an overlap would make the result depend on the order). A recipe with no ingredients is rejected by the codec, and `tools-audit-recipes.py` and `tools-audit-assets.py` check all of the above, including that every `mekanism:` id and tag
exists in the pinned Mekanism jar.

Every desk part has the same two files as a tiered grid item, with the ids it had as a grid recipe (so nothing that knows a recipe
id breaks):

- `<name>.json`, loaded only **without** Mekanism (`neoforge:not` of `neoforge:mod_loaded mekanism`): the vanilla recipe;
- `<name>_mekanism.json`, loaded only **with** it: the Mekanism recipe.

So exactly one recipe makes each part in each install, and no grid recipe makes it any more. Recipes of other items that use these
parts as ingredients (the benchboard, the header strip, the board blueprint, the compact panels and so on) are unchanged: they
name the part as an item, and the part is now made at the desk.

### What the parts cost

Without Mekanism each part costs what its grid recipe cost (the same items in the same counts, the same result count). With
Mekanism the design is the one in MEKANISM-RECIPES.md (steel for iron, a basic circuit for a redstone dust that carries a signal,
an advanced circuit for a comparator, HDPE sheets in the vertical panel) with **every steel count doubled**: the owner wants steel to
cost more, and it is one constant (`STEEL_FACTOR` in `tools-generate-mekanism-recipes.py`). The vanilla files are written by hand
and the Mekanism ones by that tool, and `--check` fails if this table is stale.

<!-- BEGIN DESK TABLE (tools-generate-mekanism-recipes.py --desk-table writes this) -->

**Panels and trim**

| Part | Makes | Without Mekanism | With Mekanism |
| --- | --- | --- | --- |
| `control_panel_console` | 1 | 2 iron ingot, 2 control panel vertical | 4 steel ingot, 2 control panel vertical |
| `control_panel_corner_bench_chamfer` | 2 | 1 iron nugget, 2 control panel benchboard | 2 steel nugget, 2 control panel benchboard |
| `control_panel_corner_bench_outside` | 2 | 1 iron ingot, 1 control panel benchboard | 2 steel ingot, 1 control panel benchboard |
| `control_panel_corner_vertical_chamfer` | 2 | 1 iron nugget, 2 control panel vertical | 2 steel nugget, 2 control panel vertical |
| `control_panel_corner_vertical_outside` | 2 | 1 iron ingot, 1 control panel vertical | 2 steel ingot, 1 control panel vertical |
| `control_panel_duplex` | 1 | 1 iron ingot, 1 control panel header strip, 2 control panel vertical, 1 control panel benchboard | 2 steel ingot, 1 control panel header strip, 2 control panel vertical, 1 control panel benchboard |
| `control_panel_end_cap_round` | 4 | 1 iron nugget, 1 control panel vertical | 2 steel nugget, 1 control panel vertical |
| `control_panel_header_canopy` | 1 | 1 iron ingot, 1 control panel header strip | 2 steel ingot, 1 control panel header strip |
| `control_panel_header_vent` | 2 | 1 iron ingot, 1 control panel vertical, 2 iron bars | 2 steel ingot, 1 control panel vertical, 2 iron bars |
| `control_panel_plinth_apron` | 2 | 2 iron ingot, 1 control panel vertical | 4 steel ingot, 1 control panel vertical |
| `control_panel_plinth_base` | 2 | 2 iron ingot, 1 control panel vertical | 4 steel ingot, 1 control panel vertical |
| `control_panel_plinth_kick` | 6 | 3 iron ingot | 6 steel ingot |
| `control_panel_vertical` | 4 | 8 iron ingot, 1 lime dye | 10 steel ingot, 1 lime dye, 2 HDPE sheet, 1 basic circuit |

**Desks and furniture**

| Part | Makes | Without Mekanism | With Mekanism |
| --- | --- | --- | --- |
| `control_panel_desk` | 1 | 1 iron ingot, 1 control panel console, 1 control panel vertical | 2 steel ingot, 1 control panel console, 1 control panel vertical |
| `control_panel_desk_wide` | 1 | 1 iron ingot, 2 control panel desk | 2 steel ingot, 2 control panel desk |
| `control_stand` | 1 | 1 iron ingot, 1 glass pane, 1 control panel vertical | 2 steel ingot, 1 glass pane, 1 control panel vertical |
| `operator_desk` | 2 | 5 iron ingot, 1 stick | 10 steel ingot, 1 stick |
| `operator_desk_corner` | 1 | 1 iron ingot, 1 operator desk | 2 steel ingot, 1 operator desk |
| `operator_desk_round` | 1 | 1 iron nugget, 1 operator desk | 2 steel nugget, 1 operator desk |
| `relay_cabinet` | 1 | 7 iron ingot, 1 iron door, 1 redstone | 14 steel ingot, 1 iron door, 1 basic circuit |
| `relay_cabinet_plain` | 1 | 8 iron ingot, 1 redstone | 16 steel ingot, 1 basic circuit |
| `supervisor_desk` | 1 | 2 iron ingot, 2 operator desk | 4 steel ingot, 2 operator desk |

**Switches**

| Part | Makes | Without Mekanism | With Mekanism |
| --- | --- | --- | --- |
| `control_switch` | 1 | 5 iron ingot, 1 lever, 1 redstone | 10 steel ingot, 1 lever, 1 basic circuit |
| `key_switch` | 1 | 1 iron ingot, 1 lever, 1 tripwire hook | 2 steel ingot, 1 lever, 1 tripwire hook |
| `push_turn_switch` | 1 | 1 iron ingot, 1 red dye, 1 stone button, 1 redstone | 2 steel ingot, 1 red dye, 1 stone button, 1 basic circuit |
| `pushbutton` | 2 | 1 iron ingot, 1 stone button | 2 steel ingot, 1 stone button |
| `rotary_selector` | 1 | 4 iron ingot, 1 redstone | 8 steel ingot, 1 basic circuit |
| `rps_trip` | 1 | 3 iron ingot, 2 stone button, 1 red dye | 6 steel ingot, 2 stone button, 1 red dye |
| `setpoint_knob` | 1 | 3 iron ingot, 1 redstone, 1 black dye | 6 steel ingot, 1 basic circuit, 1 black dye |
| `thumbwheel` | 2 | 2 iron ingot, 2 paper, 1 redstone | 4 steel ingot, 2 paper, 1 basic circuit |
| `toggle_switch` | 2 | 2 iron ingot, 1 stick, 1 redstone | 4 steel ingot, 1 stick, 1 basic circuit |

**Gauges and meters**

| Part | Makes | Without Mekanism | With Mekanism |
| --- | --- | --- | --- |
| `bar_gauge` | 1 | 6 iron ingot, 1 glass pane, 1 black dye, 1 redstone | 12 steel ingot, 1 glass pane, 1 black dye, 1 basic circuit |
| `dial_gauge` | 1 | 2 iron ingot, 1 glass pane, 1 clock, 1 redstone | 4 steel ingot, 1 glass pane, 1 clock, 1 basic circuit |
| `edgewise_gauge_horizontal` | 1 | 3 iron ingot, 2 glass pane, 1 redstone | 6 steel ingot, 2 glass pane, 1 basic circuit |
| `edgewise_gauge_vertical` | 1 | 6 iron ingot, 2 glass pane, 1 redstone | 12 steel ingot, 2 glass pane, 1 basic circuit |

**Lamps and annunciators**

| Part | Makes | Without Mekanism | With Mekanism |
| --- | --- | --- | --- |
| `annunciator` | 2 | 3 iron ingot, 5 glass pane, 1 redstone | 6 steel ingot, 5 glass pane, 1 basic circuit |
| `lamp` | 2 | 1 iron ingot, 1 glass pane, 1 redstone | 2 steel nugget, 1 glass pane, 1 redstone |

**Displays**

| Part | Makes | Without Mekanism | With Mekanism |
| --- | --- | --- | --- |
| `bar_graph` | 2 | 2 iron ingot, 3 glowstone dust, 1 redstone | 4 steel ingot, 3 glowstone dust, 1 basic circuit |
| `seven_segment_display` | 1 | 3 iron ingot, 5 redstone, 1 glass pane | 6 steel ingot, 4 redstone, 1 basic circuit, 1 glass pane |
| `sixteen_segment_display` | 1 | 3 iron ingot, 4 redstone, 1 comparator, 1 glass pane | 6 steel ingot, 4 redstone, 1 advanced circuit, 1 glass pane |
| `wall_display` | 1 | 8 iron ingot, 1 black stained glass pane | 16 steel ingot, 1 black stained glass pane |
| `wall_display_wide` | 1 | 3 iron ingot, 2 black stained glass pane, 1 wall display | 6 steel ingot, 2 black stained glass pane, 1 wall display |
<!-- END DESK TABLE -->

## Recipe viewers

JEI and EMI are not dependencies of this repository, so there is no viewer category and nothing was added or downloaded for one. The
desk's own screen is the recipe list: every part, its materials and the counts you hold.

## Tests

`FabricationDeskTests` (GameTests, namespace `bwrfab`, run with and without Mekanism; `-PbwrGameTestNamespaces=bwrfab`) check, in
both installs: the right recipe set is live and the other is not; every one of the 42 has exactly one live desk recipe, in the
right tab, and no grid recipe makes it; the data of every recipe (the limits above, tags that name items, no overlap, distinct wire
keys); a craft takes exactly the materials and gives exactly the part; a refusal when short, from too far away, for a spectator and
at no desk, each changing nothing; a tag counted across stacks, kinds and the off hand, and armour not counted; a full inventory
(survival and creative) dropping the part; a burst of requests making only what was paid for and the rate limit stopping a flood;
the menu snapshot; and the block's registration, assets and shape.

The tests that pinned the grid recipes of these parts now check the same thing through the desk recipe: `RealScaleTests`
(`craftedStacksStartAtRealSize`), `ControlRoomRecipeTests`, `ControlDeskTests`, `RoomFurnitureTests`, `PanelTrimTests`, and the route test
`everyCraftableItemHasARecipeAndTheRestDoNot` in `FuelFabricatorItemRouteTests` counts a desk recipe as an item's route.
`MekanismRecipeTierTests` leaves the desk recipes to `FabricationDeskTests` (they come in the same pairs but are not grid recipes).
