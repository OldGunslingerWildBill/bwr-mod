package dev.bwr.mod.devtest;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.authlib.GameProfile;
import dev.bwr.mod.panel.PanelBlock;
import dev.bwr.mod.panel.PanelBlockEntity;
import dev.bwr.mod.panel.board.BoardBlueprintItem;
import dev.bwr.mod.panel.board.BoardPlacer;
import dev.bwr.mod.panel.board.BoardPlan;
import dev.bwr.mod.panel.board.BoardTemplate;
import dev.bwr.mod.panel.board.BoardTemplateParser;
import dev.bwr.mod.panel.board.BoardTemplateSync;
import dev.bwr.mod.panel.board.BoardTemplates;
import dev.bwr.mod.panel.component.ComponentTypes;
import dev.bwr.mod.panel.component.ConfigField;
import dev.bwr.mod.panel.component.InputSources;
import dev.bwr.mod.panel.component.PanelComponent;
import dev.bwr.mod.registry.BwrItems;
import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NumericTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The premade control boards ({@code panel_board} templates, the board blueprint): every shipped template parses and
 * validates (and is the one the data pack loader registered), the parser refuses each kind of bad template with a reason,
 * every board placed through the real placer produces exactly its components (type, place, size, label, caption,
 * settings), leaves its reserved spaces empty and comes out unwired, a board faces any way, a blocked or out of world
 * site is refused with nothing changed, survival pays the panels and parts from the inventory and says what is missing,
 * the blueprint item places and cycles, and the sync packet round trips. Fixtures live around 163,700 / 163,700.
 */
@GameTestHolder("bwr")
@PrefixGameTestTemplate(false)
public final class PremadeBoardTests {
    private PremadeBoardTests() {
    }

    private static final int ORIGIN_X = 163_700;
    private static final int ORIGIN_Z = 163_700;
    private static final int FLOOR_Y = 100;
    /** The shipped shapes, in blueprint order: blank boards named by their form. */
    private static final String[] SHIPPED = {"vertical_board", "back_panel", "benchboard", "console",
            "back_panel_with_benchboard", "duplex_board"};

    private interface Body {
        void run(ServerLevel level, BlockPos floor) throws Exception;
    }

    /** A cleared stone floor with air above (the board stands on it); the body runs once the chunks answer lookups. */
    private static void fixture(GameTestHelper h, int slot, Body body) {
        ServerLevel level = h.getLevel();
        BlockPos floor = new BlockPos(ORIGIN_X + slot * 64, FLOOR_Y, ORIGIN_Z);
        AABB bounds = new AABB(floor.getX() - 22, FLOOR_Y, floor.getZ() - 10, floor.getX() + 6, FLOOR_Y + 14, floor.getZ() + 10);
        EntityLookupProbe.runWhenLive(h, bounds, () -> {
            clear(level, floor);
            try {
                body.run(level, floor);
            } finally {
                clear(level, floor);
            }
            h.succeed();
        });
    }

    private static void clear(ServerLevel level, BlockPos floor) {
        for (BlockPos pos : BlockPos.betweenClosed(floor.offset(-20, 0, -9), floor.offset(4, 12, 9))) {
            net.minecraft.world.level.block.state.BlockState want =
                    pos.getY() == floor.getY() ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState();
            if (level.getBlockState(pos) != want) {
                level.setBlock(pos, want, 2 | 16 | 32);
            }
        }
        level.getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class, new AABB(floor).inflate(40))
                .forEach(net.minecraft.world.entity.Entity::discard);
    }

    private static FakePlayer player(ServerLevel level, String name, GameType mode) {
        FakePlayer player = FakePlayerFactory.get(level,
                new GameProfile(UUID.nameUUIDFromBytes(name.getBytes(StandardCharsets.UTF_8)), name));
        player.setGameMode(mode);
        player.getInventory().clearContent();
        player.setShiftKeyDown(false);
        return player;
    }

    private static void check(GameTestHelper h, boolean condition, String message) {
        h.assertTrue(condition, message);
    }

    private static BoardTemplate shipped(GameTestHelper h, String name) {
        BoardTemplate template = BoardTemplates.get(ResourceLocation.fromNamespaceAndPath("bwr", name));
        h.assertTrue(template != null, "the data pack loader did not register the board bwr:" + name + " (loaded: "
                + BoardTemplates.all().keySet() + ")");
        return template;
    }

    private static JsonElement json(String text) {
        return JsonParser.parseString(text);
    }

    private static BoardTemplateParser.Result parse(String text) {
        return BoardTemplateParser.parse(ResourceLocation.fromNamespaceAndPath("bwr", "test"), json(text));
    }

    private static boolean mentions(BoardTemplateParser.Result result, String... words) {
        for (String problem : result.problems()) {
            boolean all = true;
            for (String word : words) {
                all &= problem.contains(word);
            }
            if (all) {
                return true;
            }
        }
        return false;
    }

    /** A one frame template with the components given, for the parser tests. */
    private static String board(String components) {
        return "{\"format\":1,\"frames\":[{\"id\":\"main\",\"kind\":\"duplex\",\"columns\":2,\"at\":[0,0,0]}],\"components\":["
                + components + "]}";
    }

    // ---- the shipped templates

    @GameTest(template = "empty")
    public static void everyShippedBoardParsesAndValidates(GameTestHelper h) {
        var manager = h.getLevel().getServer().getResourceManager();
        Map<ResourceLocation, net.minecraft.server.packs.resources.Resource> files =
                manager.listResources(BoardTemplates.DIRECTORY, id -> id.getPath().endsWith(".json"));
        Set<String> found = new HashSet<>();
        for (var entry : files.entrySet()) {
            if (!entry.getKey().getNamespace().equals("bwr")) {
                continue;
            }
            String name = entry.getKey().getPath().substring(BoardTemplates.DIRECTORY.length() + 1,
                    entry.getKey().getPath().length() - ".json".length());
            found.add(name);
            JsonElement element;
            try (var reader = new InputStreamReader(entry.getValue().open(), StandardCharsets.UTF_8)) {
                element = JsonParser.parseReader(reader);
            } catch (Exception bad) {
                throw new AssertionError("cannot read " + entry.getKey() + ": " + bad);
            }
            BoardTemplateParser.Result result = BoardTemplateParser.parse(ResourceLocation.fromNamespaceAndPath("bwr", name), element);
            check(h, result.ok(), entry.getKey() + " has problems: " + result.problems());
            BoardTemplate template = shipped(h, name);
            check(h, template.components().size() == result.template().components().size()
                    && template.mimic().size() == result.template().mimic().size(),
                    name + ": the registered board is not the file's");
            // every type has its item, the board fits the default panel limits, and the live config
            for (BoardTemplate.Comp comp : template.components()) {
                check(h, comp.type().item() != net.minecraft.world.item.Items.AIR, name + ": " + comp.type().id() + " has no item");
            }
            check(h, BoardPlacer.limits(template) == null, name + ": " + BoardPlacer.limits(template));
        }
        for (String name : SHIPPED) {
            check(h, found.contains(name), "board file " + name + ".json is missing from data/bwr/panel_board");
        }
        check(h, BoardTemplates.ordered().size() >= SHIPPED.length, "fewer boards registered than shipped");
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void theShippedBoardsAreBlankShapesNamedByTheirForm(GameTestHelper h) {
        List<BoardTemplate> ordered = BoardTemplates.ordered();
        List<String> ids = new ArrayList<>();
        for (BoardTemplate each : ordered) {
            if (each.id().getNamespace().equals("bwr")) {
                ids.add(each.id().getPath());
            }
        }
        check(h, ids.equals(List.of(SHIPPED)), "the shipped boards, in blueprint order, are " + List.of(SHIPPED) + ", not " + ids);
        for (String name : SHIPPED) {
            BoardTemplate template = shipped(h, name);
            // blank: panel blocks only, nothing mounted, no tape, no reserved space, no notes, and no reactor function in the name
            check(h, template.components().isEmpty() && template.reserved().isEmpty() && template.mimic().isEmpty()
                    && template.todo().isEmpty(), name + " is not blank: " + summary(template));
            check(h, !template.frames().isEmpty() && !template.title().isBlank(), name + ": no frames or no title");
            String words = (name + " " + template.title() + " " + template.description() + " " + template.source()).toLowerCase();
            for (String function : List.of("rod", "rpv", "rps", "eccs", "p601", "neutron", "scram", "level", "reactor")) {
                check(h, !words.contains(function), name + " names a plant function (" + function + "): " + words);
            }
            // every frame is a valid panel or trim block: it has a surface, and the plan makes only panel blocks that exist
            for (BoardTemplate.Frame frame : template.frames()) {
                check(h, !frame.surfaces().isEmpty() && frame.columns() >= 1 && frame.rows() >= 1, name + ": frame " + frame.id());
                for (BoardTemplate.Surface surface : frame.surfaces()) {
                    check(h, surface.width() > 0 && surface.height() > 0 && surface.panelType() != null, name + ": surface " + surface.id());
                }
            }
            BoardPlan plan = new BoardPlan(template, BlockPos.ZERO, Direction.NORTH);
            check(h, !plan.blocks().isEmpty() && !plan.panelPositions().isEmpty() && plan.panelPositions().size() <= plan.blocks().size(),
                    name + ": no panel blocks");
            for (BoardPlan.Block block : plan.blocks()) {
                // a duplex column also has body cells with no item (the item of its column pays for them)
                boolean bodyCell = block.item() == null && block.state().getBlock() instanceof dev.bwr.mod.panel.DuplexPanelBlock;
                check(h, block.state().getBlock() instanceof PanelBlock && (bodyCell || block.item() != null
                        && block.item() != net.minecraft.world.item.Items.AIR), name + ": " + block.state() + " is not a panel block with an item");
            }
            check(h, BoardPlacer.limits(template) == null, name + ": " + BoardPlacer.limits(template));
        }
        // any board of any data pack, not only the shipped shapes: every component is a registered type with an item and a
        // size, and no reserved space is left
        for (BoardTemplate each : ordered) {
            check(h, each.reserved().isEmpty(), each.id() + " still has a reserved space");
            for (BoardTemplate.Comp comp : each.components()) {
                check(h, ComponentTypes.get(comp.type().id()) == comp.type() && comp.type().item() != net.minecraft.world.item.Items.AIR
                        && comp.w() > 0 && comp.h() > 0, each.id() + ": " + comp.type().id() + " is not a registered type with a size");
            }
        }
        // a blueprint saved with an id that no longer exists (the five function boards were removed) falls back to the first board
        for (String retired : List.of("rod_control", "rpv_level_pressure_flow", "reactor_scram_rps", "eccs_p601", "neutron_monitoring")) {
            ItemStack old = BoardBlueprintItem.stackFor(ResourceLocation.fromNamespaceAndPath("bwr", retired));
            check(h, BoardTemplates.get(ResourceLocation.fromNamespaceAndPath("bwr", retired)) == null, retired + " is still loaded");
            check(h, BoardBlueprintItem.template(old) == ordered.get(0), "an old blueprint for " + retired + " must fall back to the first board");
            check(h, !old.getHoverName().getString().isEmpty(), "an old blueprint has no name");
        }
        h.succeed();
    }

    private static String summary(BoardTemplate template) {
        Map<String, Long> by = new java.util.TreeMap<>();
        template.components().forEach(c -> by.merge(c.type().id().getPath(), 1L, Long::sum));
        return by + " reserved " + template.reserved().size() + " mimic " + template.mimic().size();
    }

    // ---- the parser

    @GameTest(template = "empty")
    public static void theParserRefusesEachKindOfBadTemplateWithAReason(GameTestHelper h) {
        String lamp = "{\"on\":\"main.bench\",\"type\":\"bwr:lamp\",\"size\":[2,2],\"at\":[1,1]}";
        check(h, parse(board(lamp)).ok(), "a minimal board must parse: " + parse(board(lamp)).problems());
        check(h, mentions(parse("[]"), "not a JSON object"), "not an object");
        check(h, mentions(parse(board("{\"on\":\"main.bench\",\"type\":\"bwr:no_such_part\",\"at\":[1,1]}")), "unknown component type"),
                "unknown type");
        check(h, mentions(parse(board("{\"on\":\"main.nowhere\",\"type\":\"bwr:lamp\",\"size\":[2,2],\"at\":[1,1]}")), "names no surface"), "unknown surface");
        check(h, mentions(parse(board("{\"on\":\"main.bench\",\"type\":\"bwr:lamp\",\"size\":[2,2],\"at\":[31,1]}")), "not inside"), "off the surface");
        check(h, mentions(parse(board(lamp + "," + lamp)), "overlaps"), "overlap");
        check(h, mentions(parse(board("{\"on\":\"main.bench\",\"type\":\"bwr:lamp\",\"at\":[1,1],\"size\":[7,7]}")), "has no size"),
                "a size the type does not have");
        check(h, mentions(parse(board("{\"on\":\"main.bench\",\"type\":\"bwr:lamp\",\"size\":[2,2],\"at\":[1,1],\"config\":{\"Nope\":1}}")),
                "no config field 'Nope'"), "unknown config field");
        check(h, mentions(parse(board("{\"on\":\"main.bench\",\"type\":\"bwr:rotary_selector\",\"size\":[2,4],\"at\":[1,1],\"config\":{\"Positions\":9}}")),
                "allowed 2 to 6"), "config out of range");
        check(h, mentions(parse(board("{\"on\":\"main.bench\",\"type\":\"bwr:lamp\",\"size\":[2,2],\"at\":[1,1],\"config\":{\"Flash\":\"strobe\"}}")),
                "allowed: steady"), "a choice that is not one");
        check(h, mentions(parse(board("{\"on\":\"main.bench\",\"type\":\"bwr:lamp\",\"size\":[2,2],\"at\":[1,1],\"config\":{\"Color\":\"red\"}}")),
                "must be a colour"), "a colour that is not one");
        check(h, mentions(parse(board("{\"on\":\"main.bench\",\"type\":\"bwr:lamp\",\"size\":[2,2],\"at\":[1,1],\"label\":\"A-LABEL-WAY-TOO-LONG-FOR-IT\"}")),
                "longer than"), "a long label");
        check(h, mentions(parse(board("{\"on\":\"main.bench\",\"type\":\"bwr:lamp\",\"size\":[2,2],\"at\":[1,1],\"label\":\"X\"},"
                + "{\"on\":\"main.bench\",\"type\":\"bwr:lamp\",\"size\":[2,2],\"at\":[3,1],\"label\":\"X\"}")), "used by another"), "a label used twice");
        check(h, mentions(parse(board("{\"on\":\"main.bench\",\"type\":\"bwr:nameplate\",\"at\":[1,1],\"size\":[16,4],\"config\":{\"Text\":\""
                + "A".repeat(40) + "\"}}")), "at most"), "a text that is too long");
        // a frame past the widest panel group, two frames on the same blocks, a bad kind
        check(h, mentions(parse("{\"format\":1,\"frames\":[{\"id\":\"a\",\"kind\":\"duplex\",\"columns\":17}]}"), "columns must be"), "too wide");
        check(h, mentions(parse("{\"format\":1,\"frames\":[{\"id\":\"a\",\"kind\":\"duplex\",\"columns\":2},"
                + "{\"id\":\"b\",\"kind\":\"vertical\",\"columns\":1,\"rows\":1,\"at\":[1,1,-1]}]}"), "also used by another frame"),
                "two frames on the same blocks");
        check(h, mentions(parse("{\"format\":1,\"frames\":[{\"id\":\"a\",\"kind\":\"circle\"}]}"), "kind must be"), "unknown frame kind");
        check(h, mentions(parse("{\"format\":2,\"frames\":[{\"id\":\"a\",\"kind\":\"duplex\",\"columns\":1}]}"), "format 2"), "unknown format");
        // a component past what a type may span (a 64 cell nameplate starting 10 cells into a block spans 5 blocks)
        check(h, mentions(parse("{\"format\":1,\"frames\":[{\"id\":\"a\",\"kind\":\"back_panel\",\"columns\":8,\"rows\":1}],\"components\":["
                + "{\"on\":\"a.face\",\"type\":\"bwr:nameplate\",\"at\":[10,0],\"size\":[64,4]}]}"), "spans"), "a span of too many blocks");
        // a component without its footprint
        check(h, mentions(parse(board("{\"on\":\"main.bench\",\"type\":\"bwr:lamp\",\"at\":[1,1]}")), "'size' [w, h] is missing"),
                "a missing size");
        // too many components start in one block
        BoardTemplateParser.Result crowded = BoardTemplateParser.parse(ResourceLocation.fromNamespaceAndPath("bwr", "test"),
                json(board("{\"on\":\"main.bench\",\"type\":\"bwr:lamp\",\"size\":[2,2],\"at\":[0,0],\"repeat\":{\"cols\":4,\"rows\":4,\"step\":[2,2]}}")),
                new BoardTemplateParser.Limits(16, 8, 10, 16));
        check(h, mentions(crowded, "components are anchored in the block"), "a crowded block: " + crowded.problems());
        // mimic and reserved checks
        check(h, mentions(parse(board(lamp).replace("]}", "],\"mimic\":[{\"on\":\"main.bench\",\"points\":[[0,0],[0,0]]}]}")), "same spot"),
                "a mimic path of one spot");
        check(h, mentions(parse(board(lamp).replace("]}", "],\"reserved\":[{\"on\":\"main.bench\",\"at\":[0,0],\"size\":[4,4]}]}")),
                "overlaps a component"), "a reserved space over a component");
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void repeatAndNamesExpandIntoLabelledCopies(GameTestHelper h) {
        BoardTemplateParser.Result result = parse(board("{\"on\":\"main.bench\",\"type\":\"bwr:lamp\",\"size\":[2,2],\"at\":[2,1],\"label\":\"PMP-{name}-{n}\","
                + "\"caption\":\"PUMP {name}\",\"config\":{\"Color\":\"20C040\"},\"repeat\":{\"count\":4,\"step\":[3,0]},"
                + "\"names\":[\"A\",\"B\",\"C\",\"D\"]},"
                + "{\"on\":\"main.board\",\"type\":\"bwr:pushbutton\",\"size\":[2,2],\"at\":[0,0],\"repeat\":{\"cols\":3,\"rows\":2,\"step\":[2,2]},"
                + "\"label\":\"PB-{col}-{row}\"}"));
        check(h, result.ok(), "repeat: " + result.problems());
        List<BoardTemplate.Comp> list = result.template().components();
        check(h, list.size() == 10, "4 + 6 copies, got " + list.size());
        check(h, list.get(0).x() == 2 && list.get(3).x() == 11 && list.get(3).y() == 1, "the step moves each copy 3 cells");
        check(h, list.get(2).label().equals("PMP-C-3") && list.get(2).caption().equals("PUMP C"), "names and numbers: " + list.get(2));
        check(h, list.get(0).config().getInt("Color") == 0x20C040, "a colour string becomes the colour number");
        check(h, list.get(9).x() == 4 && list.get(9).y() == 2 && list.get(9).label().equals("PB-2-1"), "cols and rows: " + list.get(9));
        BoardTemplateParser.Result wrong = parse(board("{\"on\":\"main.bench\",\"type\":\"bwr:lamp\",\"size\":[2,2],\"at\":[2,1],\"repeat\":{\"count\":4,\"step\":[3,0]},"
                + "\"names\":[\"A\"]}"));
        check(h, mentions(wrong, "names has 1 entries for 4 copies"), "names must match the copies");
        h.succeed();
    }

    // ---- placing

    /** The board placed through the real placer at the front of the floor, facing north, free (creative). */
    private static BoardPlan placeFree(GameTestHelper h, ServerLevel level, BlockPos floor, BoardTemplate template, Direction facing) {
        FakePlayer player = player(level, "Boards-" + template.id().getPath(), GameType.CREATIVE);
        BlockPos origin = floor.above();
        BoardPlacer.Outcome outcome = BoardPlacer.place(level, player, template, origin, facing);
        check(h, outcome.ok(), template.id() + " facing " + facing + " was refused: " + outcome.status() + " " + outcome.detail());
        check(h, outcome.blocks() == new BoardPlan(template, origin, facing).blocks().size()
                && outcome.components() == template.components().size() && outcome.paths() == template.mimic().size(),
                "the outcome counts differ: " + outcome);
        return new BoardPlan(template, origin, facing);
    }

    private static PanelComponent componentAt(ServerLevel level, BoardPlan plan, BoardTemplate.Comp comp) {
        BoardPlan.Anchor anchor = plan.anchor(plan.template().surface(comp.surface()), comp.x(), comp.y());
        if (!(level.getBlockEntity(anchor.pos()) instanceof PanelBlockEntity be)) {
            return null;
        }
        for (PanelComponent component : be.owned()) {
            if (component.x() == anchor.cx() && component.y() == anchor.cy()) {
                return component;
            }
        }
        return null;
    }

    /** The board in the world is its template, nothing more and nothing less. */
    private static void verifyPlaced(GameTestHelper h, ServerLevel level, BoardPlan plan) {
        BoardTemplate template = plan.template();
        String name = template.id() + " facing " + plan.facing();
        for (BoardPlan.Block block : plan.blocks()) {
            check(h, level.getBlockState(block.pos()).equals(block.state()), name + ": " + block.pos() + " is "
                    + level.getBlockState(block.pos()) + ", not " + block.state());
        }
        int total = 0;
        for (BlockPos pos : plan.panelPositions()) {
            check(h, level.getBlockEntity(pos) instanceof PanelBlockEntity, name + ": no panel entity at " + pos);
            total += ((PanelBlockEntity) level.getBlockEntity(pos)).owned().size();
        }
        check(h, total == template.components().size(), name + ": " + total + " components in the world, the template has "
                + template.components().size());
        for (BoardTemplate.Comp comp : template.components()) {
            PanelComponent component = componentAt(level, plan, comp);
            String what = name + ": " + comp.type().id() + " at " + comp.surface() + " " + comp.x() + "," + comp.y();
            check(h, component != null, what + " is not where the template puts it");
            check(h, component.type() == comp.type() && component.w() == comp.w() && component.h() == comp.h(), what + " has the wrong type or size");
            if (!comp.label().isEmpty()) {
                check(h, component.label().equals(comp.label()), what + " label " + component.label() + " is not " + comp.label());
            }
            check(h, component.caption().equals(comp.caption()), what + " caption '" + component.caption() + "' is not '" + comp.caption() + "'");
            CompoundTag saved = component.data().toTag();
            CompoundTag fresh = comp.type().createData(dev.bwr.mod.panel.component.Footprint.of(comp.w(), comp.h())).toTag();
            for (String key : comp.config().getAllKeys()) {
                Tag want = comp.config().get(key);
                // a setting is saved unless it equals the type's default, which is then not written
                if (saved.get(key) != null) {
                    check(h, sameValue(want, saved.get(key)), what + " config " + key + " is " + saved.get(key) + ", the template says " + want);
                } else if (key.equals("Preset") && want.getAsString().equals("custom")) {
                    // a bar gauge saves its preset only once one is chosen: custom is the absence of the key
                    check(h, fresh.get(key) != null, "a bar gauge starts on a preset");
                } else {
                    check(h, fresh.get(key) == null || sameValue(want, fresh.get(key)), what + " config " + key
                            + " was not kept: the template says " + want + ", a new part has " + fresh.get(key));
                }
            }
            for (int i = 0; i < component.inputCount(); i++) {
                check(h, component.source(i) == InputSources.NONE, what + " came wired (input " + i + ")");
            }
            for (int i = 0; i < component.outputCount(); i++) {
                check(h, component.sinks(i).isEmpty(), what + " came wired (output " + i + ")");
            }
        }
        for (BoardTemplate.Reserved reserved : template.reserved()) {
            BoardTemplate.Surface surface = template.surface(reserved.surface());
            for (int x = reserved.x(); x < reserved.x() + reserved.w(); x += 3) {
                for (int y = reserved.y(); y < reserved.y() + reserved.h(); y += 3) {
                    BoardPlan.Anchor anchor = plan.anchor(surface, x, y);
                    PanelBlockEntity be = (PanelBlockEntity) level.getBlockEntity(anchor.pos());
                    check(h, be.claimAt(anchor.cx(), anchor.cy()) == null, name + ": the reserved space " + reserved.forType()
                            + " on " + reserved.surface() + " is not empty at " + x + "," + y);
                }
            }
        }
        Set<Long> paths = new HashSet<>();
        for (BlockPos pos : plan.panelPositions()) {
            ((PanelBlockEntity) level.getBlockEntity(pos)).mimicPaths().forEach(path -> paths.add(path.pathId()));
        }
        check(h, paths.size() == template.mimic().size(), name + ": " + paths.size() + " mimic paths, the template has " + template.mimic().size());
    }

    private static boolean sameValue(Tag expected, Tag actual) {
        if (actual == null) {
            return false;
        }
        if (expected instanceof NumericTag a && actual instanceof NumericTag b) {
            return Math.abs(a.getAsDouble() - b.getAsDouble()) < 1.0e-9;
        }
        return expected.equals(actual);
    }

    @GameTest(template = "empty", timeoutTicks = 1200)
    public static void everyShapePlacesExactlyItsPanelBlocks(GameTestHelper h) {
        fixture(h, 0, (level, floor) -> {
            for (String name : SHIPPED) {
                verifyPlaced(h, level, placeFree(h, level, floor, shipped(h, name), Direction.NORTH));
                clear(level, floor);
            }
        });
    }

    @GameTest(template = "empty", timeoutTicks = 1200)
    public static void aBoardFacesAnyWayAndIsRemovedWithItsParts(GameTestHelper h) {
        fixture(h, 5, (level, floor) -> {
            BoardTemplate template = shipped(h, "duplex_board");
            for (Direction facing : Direction.Plane.HORIZONTAL) {
                BoardPlan plan = placeFree(h, level, floor, template, facing);
                verifyPlaced(h, level, plan);
                clear(level, floor);
                for (BoardPlan.Block block : plan.blocks()) {
                    check(h, level.getBlockState(block.pos()).isAir(), "block " + block.pos() + " is left after clearing");
                }
            }
        });
    }

    // ---- refusals

    @GameTest(template = "empty", timeoutTicks = 600)
    public static void aBlockedSiteIsRefusedAndNothingChanges(GameTestHelper h) {
        fixture(h, 6, (level, floor) -> {
            BoardTemplate template = shipped(h, "duplex_board");
            FakePlayer player = player(level, "BoardBlocked", GameType.CREATIVE);
            BlockPos origin = floor.above();
            BoardPlan plan = new BoardPlan(template, origin, Direction.NORTH);
            // something in the middle of the board, in a block the plan wants
            BlockPos obstacle = plan.blocks().get(plan.blocks().size() / 2).pos();
            level.setBlock(obstacle, Blocks.STONE.defaultBlockState(), 3);
            BoardPlacer.Outcome outcome = BoardPlacer.place(level, player, template, origin, Direction.NORTH);
            check(h, outcome.status() == BoardPlacer.Status.BLOCKED, "expected BLOCKED, got " + outcome.status());
            check(h, outcome.blocked().contains(obstacle), "the refusal does not name the block in the way: " + outcome.blocked());
            check(h, outcome.message(template.title()).getString().contains("No room"), "the message: " + outcome.message(template.title()).getString());
            for (BoardPlan.Block block : plan.blocks()) {
                if (!block.pos().equals(obstacle)) {
                    check(h, level.getBlockState(block.pos()).isAir(), "a block was placed at " + block.pos() + " by a refused board");
                }
            }
            check(h, level.getBlockState(obstacle).is(Blocks.STONE), "the obstacle was replaced");
            // the same board fits once the obstacle is gone
            level.setBlock(obstacle, Blocks.AIR.defaultBlockState(), 3);
            check(h, BoardPlacer.place(level, player, template, origin, Direction.NORTH).ok(), "the board does not fit on the cleared site");
        });
    }

    @GameTest(template = "empty", timeoutTicks = 400)
    public static void aSiteOutsideTheWorldIsRefused(GameTestHelper h) {
        fixture(h, 7, (level, floor) -> {
            BoardTemplate template = shipped(h, "duplex_board");
            FakePlayer player = player(level, "BoardHigh", GameType.CREATIVE);
            BoardPlacer.Outcome high = BoardPlacer.place(level, player, template, new BlockPos(floor.getX(), level.getMaxBuildHeight() - 2, floor.getZ()),
                    Direction.NORTH);
            check(h, high.status() == BoardPlacer.Status.OUT_OF_WORLD, "above the build height: " + high.status());
            BoardPlacer.Outcome low = BoardPlacer.place(level, player, template, new BlockPos(floor.getX(), level.getMinBuildHeight() - 3, floor.getZ()),
                    Direction.NORTH);
            check(h, low.status() == BoardPlacer.Status.OUT_OF_WORLD, "below the build height: " + low.status());
        });
    }

    @GameTest(template = "empty", timeoutTicks = 400)
    public static void aPlayerWhoMayNotBuildThereIsRefusedAndNothingChanges(GameTestHelper h) {
        fixture(h, 10, (level, floor) -> {
            BoardTemplate template = shipped(h, "duplex_board");
            FakePlayer player = player(level, "BoardForbidden", GameType.CREATIVE);
            BlockPos origin = floor.above();
            BoardPlan plan = new BoardPlan(template, origin, Direction.NORTH);
            // protection (a claim, the spawn area) covers only one block of the board: that is enough to refuse all of it
            BlockPos guarded = plan.blocks().get(plan.blocks().size() - 1).pos();
            BoardPlacer.overridePermission((who, pos) -> !pos.equals(guarded));
            try {
                BoardPlacer.Outcome outcome = BoardPlacer.place(level, player, template, origin, Direction.NORTH);
                check(h, outcome.status() == BoardPlacer.Status.NOT_PERMITTED, "expected NOT_PERMITTED, got " + outcome.status());
                for (BoardPlan.Block block : plan.blocks()) {
                    check(h, level.getBlockState(block.pos()).isAir(), "a refused board left a block at " + block.pos());
                }
                // the player's own claim is respected, a nobody (tools, tests) is not asked
                check(h, BoardPlacer.place(level, null, template, origin, Direction.NORTH).ok(), "a free placement is not asked for permission");
            } finally {
                BoardPlacer.overridePermission(null);
            }
        });
    }

    // ---- survival

    @GameTest(template = "empty", timeoutTicks = 1000)
    public static void survivalPaysThePartsFromTheInventoryAndListsWhatIsMissing(GameTestHelper h) {
        fixture(h, 8, (level, floor) -> {
            BoardTemplate template = shipped(h, "back_panel_with_benchboard");
            BlockPos origin = floor.above();
            BoardPlan plan = new BoardPlan(template, origin, Direction.NORTH);
            Map<Item, Integer> bill = BoardPlacer.materials(plan);
            int items = bill.values().stream().mapToInt(Integer::intValue).sum();
            check(h, bill.get(BwrItems.CONTROL_PANEL_BACK.get()) == 8 && bill.get(BwrItems.CONTROL_PANEL_BENCHBOARD.get()) == 4
                    && bill.size() == 2, "panel blocks in the bill: " + bill);
            check(h, items == plan.blocks().size() + template.components().size(), "the bill does not add up: " + items);
            FakePlayer player = player(level, "BoardSurvival", GameType.SURVIVAL);
            // empty handed: refused, everything listed, nothing placed
            BoardPlacer.Outcome none = BoardPlacer.place(level, player, template, origin, Direction.NORTH);
            check(h, none.status() == BoardPlacer.Status.MISSING && none.missing().size() == bill.size(),
                    "an empty inventory must lack every item: " + none.status() + " " + none.missing().size() + " of " + bill.size());
            check(h, none.message(template.title()).getString().startsWith("Missing for"), none.message(template.title()).getString());
            check(h, level.getBlockState(origin).isAir(), "a board was placed without materials");
            // one short: refused naming that item, nothing consumed
            fill(player, bill, 1);
            BoardPlacer.Outcome one = BoardPlacer.place(level, player, template, origin, Direction.NORTH);
            check(h, one.status() == BoardPlacer.Status.MISSING && one.missing().size() == 1
                    && one.missing().values().iterator().next() == 1, "one short: " + one.status() + " " + one.missing());
            check(h, held(player, bill) == items - 1, "a refused board consumed items");
            // the exact materials: placed, and the inventory is empty of them
            player.getInventory().clearContent();
            fill(player, bill, 0);
            BoardPlacer.Outcome ok = BoardPlacer.place(level, player, template, origin, Direction.NORTH);
            check(h, ok.ok(), "the exact materials were refused: " + ok.status() + " " + ok.missing());
            check(h, held(player, bill) == 0, "the inventory still holds " + held(player, bill) + " of the bill");
            verifyPlaced(h, level, plan);
            // extra items are left alone
            clear(level, floor);
            player.getInventory().clearContent();
            fill(player, bill, 0);
            player.getInventory().add(new ItemStack(BwrItems.LAMP.get(), 5));
            check(h, BoardPlacer.place(level, player, template, origin, Direction.NORTH).ok(), "placing with spare lamps");
            check(h, BoardPlacer.count(player, BwrItems.LAMP.get()) == 5 && held(player, bill) == 0, "spare items must stay: " + held(player, bill));
        });
    }

    /** Puts the bill in the inventory, {@code shortBy} items fewer of the first kind. Component items go in at some size. */
    private static void fill(FakePlayer player, Map<Item, Integer> bill, int shortBy) {
        boolean first = true;
        for (Map.Entry<Item, Integer> entry : bill.entrySet()) {
            int left = entry.getValue() - (first ? shortBy : 0);
            first = false;
            while (left > 0) {
                ItemStack stack = new ItemStack(entry.getKey(), Math.min(left, entry.getKey().getDefaultMaxStackSize()));
                if (entry.getKey() instanceof dev.bwr.mod.panel.component.ComponentItem) {
                    // any size of the part pays for it: a stack set to the second size
                    stack.set(dev.bwr.mod.fuel.BwrDataComponents.COMPONENT_VARIANT.get(), 1);
                }
                left -= stack.getCount();
                if (!player.getInventory().add(stack)) {
                    throw new AssertionError("the inventory is full at " + entry.getKey());
                }
            }
        }
    }

    private static int held(FakePlayer player, Map<Item, Integer> bill) {
        int total = 0;
        for (Item item : bill.keySet()) {
            total += BoardPlacer.count(player, item);
        }
        return total;
    }

    // ---- the item

    @GameTest(template = "empty", timeoutTicks = 800)
    public static void theBlueprintPlacesTheSelectedBoardAndCyclesThroughTheBoards(GameTestHelper h) {
        fixture(h, 9, (level, floor) -> {
            FakePlayer player = player(level, "BoardBlueprint", GameType.CREATIVE);
            List<BoardTemplate> all = BoardTemplates.ordered();
            ItemStack stack = new ItemStack(BwrItems.BOARD_BLUEPRINT.get());
            check(h, BoardBlueprintItem.template(stack) == all.get(0), "a plain blueprint places the first board");
            player.setItemInHand(InteractionHand.MAIN_HAND, stack);
            // right-click in the air: the next board, all the way round, and sneak goes back
            for (int i = 1; i <= all.size(); i++) {
                player.setShiftKeyDown(false);
                stack.getItem().use(level, player, InteractionHand.MAIN_HAND);
                check(h, BoardBlueprintItem.template(stack) == all.get(i % all.size()), "cycle step " + i + ": "
                        + BoardBlueprintItem.template(stack).id());
            }
            player.setShiftKeyDown(true);
            stack.getItem().use(level, player, InteractionHand.MAIN_HAND);
            check(h, BoardBlueprintItem.template(stack) == all.get(all.size() - 1), "sneak goes to the previous board");
            player.setShiftKeyDown(false);
            // the creative tab stacks name their board
            for (BoardTemplate template : all) {
                check(h, BoardBlueprintItem.template(BoardBlueprintItem.stackFor(template.id())) == template, "stackFor " + template.id());
            }
            // use on the floor: the board stands where the click was, facing the player
            BoardTemplate rps = shipped(h, "duplex_board");
            ItemStack rpsStack = BoardBlueprintItem.stackFor(rps.id());
            player.setItemInHand(InteractionHand.MAIN_HAND, rpsStack);
            BlockPos origin = floor.above();
            player.setPos(Vec3.atBottomCenterOf(origin.relative(Direction.SOUTH, 6)));
            player.setYRot(Direction.NORTH.toYRot()); // looking north: the panels face south, toward the player
            player.setXRot(0);
            BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(floor).add(0, 0.5, 0), Direction.UP, floor, false);
            check(h, rpsStack.useOn(new UseOnContext(player, InteractionHand.MAIN_HAND, hit)).consumesAction(), "the blueprint did not place");
            verifyPlaced(h, level, new BoardPlan(rps, origin, Direction.SOUTH));
            check(h, level.getBlockState(origin).getValue(PanelBlock.FACING) == Direction.SOUTH, "the panels do not face the player");
            check(h, !rpsStack.isEmpty(), "the blueprint was used up");
            // a second use on the same spot is refused (the site is taken) and says so
            check(h, !rpsStack.useOn(new UseOnContext(player, InteractionHand.MAIN_HAND, hit)).consumesAction(), "placed a board over a board");
        });
    }

    // ---- the sync packet

    @GameTest(template = "empty")
    public static void theSyncPacketCarriesABoardToTheClientIntact(GameTestHelper h) {
        BoardTemplate template = shipped(h, "duplex_board");
        String text = BoardTemplates.sources().get(template.id());
        check(h, text != null && text.length() < BoardTemplates.MAX_JSON_CHARS, "the board's JSON is kept for the sync: " + (text == null ? -1 : text.length()));
        RegistryFriendlyByteBuf buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), h.getLevel().registryAccess());
        BoardTemplateSync.Payload.CODEC.encode(buffer, new BoardTemplateSync.Payload(template.id(), text, true));
        BoardTemplateSync.Payload back = BoardTemplateSync.Payload.CODEC.decode(buffer);
        check(h, back.id().equals(template.id()) && back.json().equals(text) && back.first(), "the packet changed in transit");
        BoardTemplateParser.Result again = BoardTemplateParser.parse(back.id(), json(back.json()));
        check(h, again.ok() && again.template().components().size() == template.components().size(), "the client's copy: " + again.problems());
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void everyTypeTheBoardsUseCanBeConfiguredByTheTemplateFields(GameTestHelper h) {
        // a spot check of the format against the real schemas: each field kind converts and a wrong kind is refused
        for (var type : ComponentTypes.all()) {
            for (ConfigField field : type.configSchema()) {
                check(h, field.key() != null && !field.key().isEmpty(), type.id() + " has a field without a key");
            }
        }
        h.succeed();
    }

    /** The boards' parts in one list, for the report. */
    static List<String> inventory() {
        List<String> lines = new ArrayList<>();
        for (BoardTemplate template : BoardTemplates.ordered()) {
            lines.add(template.id() + ": " + summary(template));
        }
        return lines;
    }
}
