# The in-game guide

The **Operator's Guide** (`bwr:guide_book`) is a short book for new players and outside testers: what to build first, how to wire a
panel, how not to melt the core. The docs in this repository are written for development and run to hundreds of pages; the guide is
the part a tester needs, in the game, one screen at a time. Each chapter ends by naming the repository document to read next
("More: PRESSURE-VESSEL.md").

It adds no dependency (no Patchouli or similar): the screen draws its own open book (a PNG sheet and `Ui` text) and reads plain JSON.

## Getting it

- **Craft it:** a book and a nameplate, shapeless (`data/bwr/recipe/guide_book.json`). A nameplate is an iron ingot and a black dye
  (it makes four), so a book costs a quarter of an iron ingot and a quarter of a dye more than a bare book. With Mekanism installed the
  nameplate is a steel ingot and a black dye instead ([MEKANISM-RECIPES.md](MEKANISM-RECIPES.md)).
- **First join:** with the server option `giveGuideOnFirstJoin` (`bwr-gameplay.toml`, default **true**) every player is given one book the
  first time they join a world, **once only**. The mark is a player attachment (`bwr:guide_given`), written only when set and kept
  through death, so a relog, a death or a lost book never gives a second one. With the option off nothing is given and nothing is
  marked, so a player who joined while it was off gets the book on the first join after it is turned on.
  (`GuideGift`, `GuideBookTests`.)
- **Read it:** right-click. It opens as an open book on parchment, in dark ink, with the vanilla page-turn sound. The book opens
  where you stopped last time, or at the contents the first time. The place is kept on your own computer
  (`config/bwr-guide-progress.json`, one chapter id and one page; a missing or stale file just means "start at the contents").
  The arrows in the bottom corners, the Left and Right keys (and Page Up and Down) turn pages; Next on the last page of a chapter
  goes on to the next chapter; the red ribbon at the top of the right page (and Home) goes back to the contents; End goes to the
  last page.

## How the book looks

- **A spread is a guide page.** The first spread is the title page with the contents: on the left the title, a few words of
  introduction and, once there is a bookmark, a **Continue** link; on the right every chapter as a link with its page number, and the
  keys at the foot. After it each guide page is one spread: the page's title is the heading of the left page (with the chapter's
  title above it as a running head), the text flows from the left page to the right one, and the icons and image sit at the top of
  the right page. The text is split so that both columns end at about the same height. The last page of a chapter ends with the
  "More: ..." line. Both pages carry a page number at the foot (the contents spread is pages 1 and 2, each guide page two more; the
  contents list shows the number of each chapter's first page). The arrows turn by spread.
- **Fixed design size.** The book is 316 x 232 pixels (the first part of `textures/gui/guide_book.png`, which also holds the arrows
  and the ribbon; `tools-make-guide-assets.py` draws it) and is drawn a whole number of times larger when the window has room: the
  largest whole zoom that leaves 2 px at each side, so at the smallest window the game gives a screen (320 x 240) it is drawn at
  1x, and at GUI scale 2 in a 1080p window at 2x. Every page therefore holds exactly the same text at every size. A text column is
  132 px wide and 165 px tall (18 lines of text; a blank line between paragraphs is 5 px).

## Where the text is

Everything the guide says is data under `mod/src/main/resources/assets/bwr/guide/<language>/`. A resource pack can replace a file or add a
language the same way. Nothing about the content is in Java.

```
guide/en_us/index.json              the chapters, in order
guide/en_us/<chapter id>.json       one file per chapter
```

`index.json`:

```json
{ "chapters": ["what-this-is", "first-plant", "control-room"] }
```

A chapter file:

```json
{
  "title": "A first working plant",
  "docs": ["README.md", "PRESSURE-VESSEL.md"],
  "pages": [
    {
      "title": "1. Vessel and core",
      "text": [
        "A paragraph. Lines of the array are joined with newlines; an empty string is a blank line.",
        "",
        "Another paragraph."
      ],
      "icons": ["bwr:reactor_vessel", "bwr:reactor_controller"],
      "image": { "texture": "bwr:textures/item/survey_meter.png", "textureSize": 16, "size": 32 }
    }
  ]
}
```

| Field | Rules |
| --- | --- |
| `title` (chapter) | Shown in the contents and as the running head of its pages; keep it to about 24 characters so it fits the contents column (a longer one is cut with an ellipsis and a tooltip) |
| `docs` | One or more repository documents (a path from the repository root, ending in `.md`). The last page of the chapter ends with "More: ..." naming them. Every one must exist |
| `title` (page) | The page's heading (the left page's, one line of the column) |
| `text` | A string, or an array of lines. At most **600 characters** (counting newlines), which fits the two columns of a spread (with the icons and the "More:" line) at every size. Plain text only; the screen word-wraps it |
| `icons` | Optional. Up to 12 item ids of this mod (`bwr:name`), drawn 16 px each at the top of the right page in rows of six, each with the item's name as a tooltip. An id of an item that is not installed is left out |
| `image` | Optional. A small picture from a texture of the mod: `textureSize` is the texture's side (square), `size` is how large it is drawn (at most 32 px); it follows the icons |

A chapter has at most 12 pages. A chapter's `id` is its file name without `.json`, and it must be listed in `index.json`. The contents
list holds about ten chapters at the design size (rows shrink from 14 to 10 px for more); beyond that the GUI check fails.

### Adding or editing a page

Edit the JSON, then run `python tools-audit-guide.py` (below). No Java changes, no rebuild of anything but the resources. A new chapter
is a new file plus one line in `index.json`. Keep the style of the others: short, plain, concrete, and every fact checked against the code
or the feature document it comes from.

### Translating

Copy the folder: `guide/de_de/` holds the same file names with translated `title` and `text`. The language the game is set to is tried
first and `en_us` fills in any file the translation lacks, so a partial translation works. Keep the same number of pages in a
translated chapter (the audit requires it), the same `icons` and the same `docs`. The buttons, tooltips and the "More:" line are
ordinary lang keys (`gui.bwr.guide.*` in `lang/en_us.json`), so they translate with the rest of the mod.

## Checks

- **`python tools-audit-guide.py`** reads the files from the source tree and checks: the index and the chapter files agree (none missing,
  none unlisted); every file parses with the types the loader needs; every page is within the text, icon and image limits; every icon id
  is an item of this mod; every image exists and is square at its declared size; every document in `docs` exists in the repository; a
  translation has the same page counts as `en_us`; the **Server options** chapter names every option of every config class with its
  default (a new `define("name", ...)` that the chapter does not list fails the audit); every `gui.bwr.guide.*` key the screen uses is in
  the lang file. It runs twelve negative controls (it breaks a copy in memory and requires the audit to notice) and prints the count.
- **GameTest `GuideBookTests`** loads the same files from the class path through the real loader (`GuideBook`), checks every icon against the
  item registry and every image against the resources, crafts the book through the recipe manager, and runs the first-join gift: once, not
  again after a relog (the player is saved and read back), not after the book is lost, not with the option off.
- **The GUI check** (`:mod:runTurbineModelCheck -PbwrGuiScreenCheck`) opens the contents (with and without a bookmark) and the first page,
  the longest page, an icon page, the last page of a chapter (with an image and the "More:" line) and the last page of the book,
  at GUI scales 2, 3 and 4 in a 1920 x 1080 window and at the 320 x 240 and 427 x 240 floors, and requires 0 violations: nothing
  outside the window or the book, no text outside the book, every control reachable with Tab with a tooltip. It also opens **every page of
  the real book** and requires that its text fits the two columns (the layout is the same at every size), and drives the book:
  the arrow keys, the arrows, the ribbon and the contents links turn pages, Next goes on across a chapter's end, Back from the first
  page goes to the contents, End and Home go to the ends, and nothing goes past either end.

## Code

| | |
| --- | --- |
| `dev.bwr.mod.guide.GuideBook` | The data model and loader: pure, no Minecraft screen or resource manager; takes a `Source` |
| `dev.bwr.mod.guide.GuideBookItem` | The item: opens the screen on the client; a tooltip |
| `dev.bwr.mod.guide.GuideGift` | The first-join gift |
| `dev.bwr.mod.guide.client.GuideScreen` | The screen: the open book (an `AbstractContainerScreen` with no slots), its contents and pages |
| `dev.bwr.mod.guide.client.GuideMenu` | The slot-less client-only menu the screen stands on |
| `dev.bwr.mod.guide.client.GuideMedia` | The wrapped rows of icons and the image at the top of the right page |
| `dev.bwr.mod.guide.client.GuideProgress` | The bookmark |
| `dev.bwr.mod.guide.client.GuideClient` | Reads the files through the resource manager and opens the screen |
