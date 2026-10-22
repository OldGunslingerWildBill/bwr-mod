# Plant computer (safety parameter display)

`bwr:plant_computer` is a control panel component: a lit screen up to 4 x 4 blocks across, in a dark bezel, that shows text,
numbers, bars, trend plots and green / amber / red tiles. It is the screen of a plant computer or a safety parameter display
(SPDS) in the control room. Its pages come from one of two places:

- a **CC:Tweaked program** that you write, through the `bwr_plant_computer` calls (below); or
- a **built-in page** (reactor overview, trends, flows and heat) that reads the reactor you bind it to with the Networking
  Wrench, for a control room with no code at all.

It is **read-only towards the plant**: no outputs, no sinks, no redstone, no commands. Touching one of its soft keys changes the
built-in page shown, or raises a CC event for a program that chose to listen. Nothing it shows is a judgement of the mod:
the built-in pages have no alarm limits and no safe / unsafe colours, and the limits a program colours with are the program's.

## Placing and setting it up

1. Build a panel group at least 2 x 2 blocks (a 4 x 3 block display is the default size, 64 x 48 cells; sizes from 32 x 24 to
   64 x 64 are listed on the item, like every component, Alt+click to pick one). It needs the ordinary panel blocks behind it; the
   screen spans the blocks it covers.
2. Mount **Plant Computer (Safety Parameter Display)** from the Control Panels creative tab with the panel tools. A new display
   has no program and shows **NO PROGRAM**.
3. With the Panel Wrench open its settings:

   | Setting | Values | Default |
   | --- | --- | --- |
   | Display | `program` (a CC program draws it), `overview`, `trends`, `flows` (built-in pages) | `program` |
   | Text size | 50 to 200 percent, the same slider as the gauges | 100 |
   | Built-in trend step | 1 to 60 seconds a point | 5 |

4. For a built-in page, bind the display's one input, **Reactor**, with the Networking Wrench to the reactor controller (any of
   its signals; `formed` is the natural one). Only a reactor can be bound. The pages read the reactor's own signals
   (SIGNALS.md): power, level, pressure, core flow, containment pressure, scram, steam, feedwater and injection flow, thermal
   power, recirculation demand, void fraction, boron.
5. Soft keys along the bottom row switch the built-in page (HOME, PLOT, FLOW) by touch (right-click the key).

An unbound display says **NO REACTOR BOUND**, an unformed reactor **REACTOR NOT FORMED**; the numbers are dashes then.

## The text grid

The screen is a grid of characters. At 100 percent a character is 2 x 3 cells with a one cell margin round the screen, so a
64 x 48 display is **31 columns x 15 rows**, and at 50 percent 62 x 30. `plantInfo(label)` gives the grid of a display. Text
is printable ASCII (anything else is shown as `?`). Positions are 1-based `(col, row)`, as in Lua. Row 1 is the title band when a
page has a title; the **last row** is the soft keys' row (key `n` is the n-th sixth of its width).

Text is sized as a share of the character cell (so it scales with the Text size and with nothing else), and a line that is too
long for its room is made smaller to fit, down to 40 percent of its size (GaugeScale's rule), never off the screen.

## The CC peripheral

Every `bwr_panel` peripheral (a wired modem on any block of the panel group, or the computer touching a panel block) also answers
to the type **`bwr_plant_computer`**, so `peripheral.find("bwr_plant_computer")` finds a panel. `plantList()` tells which of its
components are plant computers; every other call takes the component's **label** (the label from the wrench, `PC-1` ...) first.
Pages are numbered 1 to 8. Every call below runs on the server thread like the rest of `bwr_panel`, and raises a Lua error with a
reason when it is refused; nothing refused is kept.

```lua
local panel = peripheral.find("bwr_plant_computer")
print(textutils.serialize(panel.plantList()))     -- { ["PC-1"] = { cols = 31, rows = 15, mode = "program" } }
local info = panel.plantInfo("PC-1")
panel.plantClear("PC-1")
panel.plantTitle("PC-1", 1, "REACTOR")
panel.plantSet("PC-1", 1, "pw", { type = "field", col = 2, row = 3, w = 24, text = "POWER", unit = "%", alarmHigh = 110 })
panel.plantValue("PC-1", 1, "pw", 87.4)
```

### Calls

| Call | What it does |
| --- | --- |
| `plantList()` | `{ [label] = { cols, rows, mode } }` for the plant computers of the panel group |
| `plantInfo(label)` | `{ cols, rows, mode, page, pages, elements, trends, limits = { ... } }`: the grid, the mode, the page shown, what is on the display and its limits |
| `plantClear(label)` | clears every page and shows page 1; returns whether anything changed |
| `plantClearPage(label, page)` | clears one page |
| `plantSetPage(label, page)` | shows page 1 to 8 |
| `plantTitle(label, page, title)` | the page's title band (up to 24 characters; none: no band) |
| `plantSet(label, page, id, spec)` | defines or redefines the element `id` (below); returns whether it changed |
| `plantValue(label, page, id, value)` | changes a field or bar number (`nil`: no data, shown `---`), a tile's state (`"ok"`, `"warn"`, `"alarm"`, `"off"`), or the text of a text line or soft key |
| `plantPush(label, page, id, value)` | adds a point to a trend |
| `plantRemove(label, page, id)` | removes an element |
| `plantFlush(label)` | have what changed sent with the next tick instead of the usual interval; returns whether it was asked for |

An `id` is 1 to 10 letters, digits, `_` or `-`, unique on its page. Redefining an id replaces the element (a trend loses its
history). The calls that change the display return whether what is **shown** changed: a field that would show the same text, or
a bar that moves by less than half a percent of its length, is not a change and sends nothing.

### Elements (`plantSet` specs)

`type` is required. Unknown settings are errors (a typo is caught, not ignored). Text (`text`, with `caption` and `label` as
aliases) is cut to its limit. Colours are `white`, `grey`, `green`, `amber`, `red`, `cyan`, `blue`, `magenta`.

| `type` | Settings | Shows |
| --- | --- | --- |
| `text` | `col`, `row`, `text` (32), `color` | a line of text |
| `field` | `col`, `row`, `w` (22), `text` (16 caption), `unit` (6), `decimals` (0 to 4, 1), `value`, `color`, `warnLow`, `warnHigh`, `alarmLow`, `alarmHigh` | the caption on the left and the number with its unit on the right. The number is coloured by the limits: amber at a warn limit, red at an alarm limit (`value <= low` or `>= high`), `color` otherwise, grey without data |
| `bar` | as `field`, and `min`, `max` (0, 100), `showValue` (true) | a caption, a bar from `min` to `max`, the number; limits are drawn as amber and red ticks on the bar and colour it like a field |
| `trend` | `col`, `row`, `w` (20), `h` (6, at least 3), `text`, `unit`, `decimals`, `min`, `max`, `history` (8 to 120 points, 60), `color` | a plot of the points pushed, newest at the right, with `max` and `min` on its axis, a line half way, and the last value in its header |
| `tile` | `col`, `row`, `w` (10), `h` (3), `text` (16), `state` | a labelled tile, green (`ok`), amber (`warn`), red (`alarm`) or dark (`off`); a label with a space wraps to two lines. A grid of these is the critical safety function display |
| `key` | `key` (1 to 6), `text` (16) | soft key `key` on the bottom row; touching it raises `plant_computer_touch` |

An element must fit the grid (`col + w - 1 <= cols`, `row + h - 1 <= rows`); a text line is cut to the room left on its row.

### The touch event

A player right-clicking a soft key of a program page raises, on the computers attached to the panel group:

```lua
local event, name, label, page, key = os.pullEvent("plant_computer_touch")
```

`name` is the peripheral name the computer sees (like `bwr_panel_input`'s), `label` the display, `page` the page shown (1 to 8), `key`
the soft key (1 to 6). It is only an event: nothing changes unless the program changes it. At most one touch a display is handled
every 4 ticks. A touch on a built-in page only changes the page.

## Limits

| Limit | Value |
| --- | --- |
| Pages | 8 |
| Elements on a page | 48 |
| Elements on a display | 160, trends included |
| Trends on a display | 12 |
| Points a trend keeps | 8 to 120 (60 by default); the oldest goes when it is full |
| Soft keys on a page | 6 (one per number) |
| Text line / caption, label, tile text, soft key text / unit / id / title | 32 / 16 / 6 / 10 / 24 characters |
| Grid | at most 80 columns x 48 rows (4 blocks at 50 percent text is 62 x 41) |
| Calls that change the display | a burst of 512, refilled at 32 a tick (640 a second); a call with no token left is an error, not a queue |
| Network | at most one patch every 4 ticks per display (5 a second), however often a program draws; `plantFlush` may ask for the next one early but not twice within 2 ticks |
| Patch | at most 16,384 bytes. The whole display, with every limit at its maximum at once, is 14,4xx bytes and always fits (`PlantComputerTests.theLimitsKeepTheWholeStateInOnePatch`) |
| Touches | one handled every 4 ticks per display |

Numbers must be finite and no bigger than 1e9; a spec number that is not a whole number where a whole number is needed is an error.

## How it works

- **State.** The server keeps the pages as a compact binary state (`PlantState`): elements of a few bytes, trend points as
  16 bit values across their `min` to `max`, texts as ASCII. The same bytes are the save, the full state a client gets when it
  starts watching the panel, and the base of every patch.
- **Deltas.** What is sent is what changed since the last patch, written from the current state: an element redefined or removed, a
  value, the new points of a trend (only those), a page cleared or retitled, the page shown. A thousand writes to one field
  between two patches are one value; a value that shows the same is nothing. If the changes do not fit a patch, or the display was
  cleared, the patch is the full state. A client applies a patch with the same code and ignores a malformed one.
- **Sampling.** The panel sampler (every `sampleTicks`, 2 by default) updates the built-in pages from the bound reactor and sends
  what waits; there is no ticker of the display's own. A built-in trend takes a point every `Built-in trend step` seconds.
- **Save.** `Mode`, `TextSize` and `TrendSeconds` only when they are not the default; `Program` (the pages) when there are any
  or page 1 is not shown; `Builtin` (the built-in pages, trend history included) in a built-in mode. A save from before the
  plant computer existed has none of these; damaged bytes load as an empty program, never as half a page.
- **Drawing.** The client lays a page out once per change (`PlantScreen`, the same code the tests check), records its shapes into
  the panel block's cached vertex buffer and its text into the block's cached text buffer, and draws both with one call each. A
  display whose program is not changing costs the renderer nothing but those two replays. The text follows the panel text
  rules (flush on the face, one height, the legend basis audit).

## Writing a program

`docs/lua/spds.lua` is a complete example: the six critical safety functions (reactivity control, core cooling, heat sink, RPV
integrity, containment, inventory) as tiles with the reason for the first one that is not green, three trend plots and a values
page, with soft keys for the pages. Its limits are its own choices, written at the top of the file. See `docs/lua/README.md` for
how to run it and for the developer check (`:mod:runLuaExamplesCheck`) that runs it on a real CC computer.

A program that draws a whole page every second is well inside the limits (about 40 calls); update with `plantValue` and
`plantPush` after the first frame, rather than `plantSet` again.

**Every call takes a server tick**, as every peripheral call does (it runs on the server thread), so a frame of twenty calls
takes about a second. An event that arrives while a program is inside a call is lost to a program that only looks for it between
calls: listen for `plant_computer_touch` in a coroutine of its own (`parallel.waitForAny(updateLoop, touchLoop)`), as
`spds.lua` does.
