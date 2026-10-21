package dev.bwr.mod.devtest;

import com.mojang.logging.LogUtils;
import dev.bwr.mod.BwrMod;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.NumericTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;

/**
 * Old-save check ({@code :mod:runSaveCompatCheck -PbwrSaveWorld=<world folder>}, see build.gradle). Inert without
 * {@code -Dbwr.saveCompat.source=<pristine world folder>}.
 *
 * <p>Reads the PRISTINE world's region files itself (raw chunk NBT, no game code, no data fixing), then lets the dedicated
 * server load a copy of that world and compares, chunk by chunk:
 * <ul>
 *   <li>block states: every block id must still exist (a missing id silently becomes air), every saved property must still
 *       exist on the block with a value it accepts (otherwise the default silently replaces it), and the loaded chunk must
 *       hold exactly the counts the raw data says;</li>
 *   <li>block entities: every saved block entity must exist after loading and re-saving it must still contain every value
 *       the old save held (panel components and bindings, wrench bindings and machine names, reactor and pump state all
 *       live in block entity NBT). A key that is absent now but held an empty/zero value before is not a loss;</li>
 *   <li>the player files: every bwr and vanilla item stack must still decode and keep its data components.</li>
 * </ul>
 * After 100 more ticks the bwr block counts and the existence and type of every block entity are compared again, so anything
 * the mod removes on its first ticks (consistency passes, orphan cleanup) shows up as a loss too (values are not compared
 * then: the physics has moved on). The verdict line is
 * {@code BWR SAVE COMPAT CHECK: PASS|FAIL}; the full detail goes to {@code save-compat-report.txt} in the run directory.
 * Only the bwr and minecraft namespaces decide the verdict (a world from a profile with other mods has their blocks too).
 */
@EventBusSubscriber(modid = BwrMod.MOD_ID, value = Dist.DEDICATED_SERVER)
public final class SaveCompatCheck {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String SOURCE = "bwr.saveCompat.source";
    private static int ticks;
    private static Raw raw;
    private static final List<String> failures = new ArrayList<>();
    private static final List<String> notes = new ArrayList<>();
    private static final StringBuilder report = new StringBuilder();

    private SaveCompatCheck() {}

    /** Before the first tick, so a running reactor has not advanced yet and its saved values can be compared exactly. */
    @SubscribeEvent
    public static void started(ServerStartedEvent event) {
        String source = System.getProperty(SOURCE);
        if (source == null) return;
        MinecraftServer server = event.getServer();
        try {
            raw = readRaw(server, Path.of(source));
            compareBlocks(server, raw, true);
            compareBlockEntities(server, raw, "load", true);
            checkPlayers(server, Path.of(source));
        } catch (Throwable t) {
            failures.add("check crashed: " + t);
            LOGGER.error("BWR SAVE COMPAT CHECK: crashed", t);
            finish(server);
        }
    }

    @SubscribeEvent
    public static void tick(ServerTickEvent.Post event) {
        if (System.getProperty(SOURCE) == null || raw == null) return;
        MinecraftServer server = event.getServer();
        ticks++;
        try {
            if (ticks == 100) {
                compareBlocks(server, raw, false);
                compareBlockEntities(server, raw, "after 100 ticks", false);
                checkSavedData(server, Path.of(System.getProperty(SOURCE)));
            } else if (ticks == 105) {
                finish(server);
            }
        } catch (Throwable t) {
            failures.add("check crashed: " + t);
            LOGGER.error("BWR SAVE COMPAT CHECK: crashed", t);
            finish(server);
        }
    }

    // ---- raw reading -------------------------------------------------------------------------------------------

    private record RawState(String name, Map<String, String> props) {
        String key() {
            StringBuilder b = new StringBuilder(name);
            if (!props.isEmpty()) b.append(props);
            return b.toString();
        }
    }

    private static final class RawChunk {
        final int x, z;
        final Map<RawState, Integer> states = new LinkedHashMap<>();
        final List<CompoundTag> blockEntities = new ArrayList<>();

        RawChunk(int x, int z) {
            this.x = x;
            this.z = z;
        }
    }

    private static final class Raw {
        /** dimension folder name ("" for the overworld) to chunks. */
        final Map<String, List<RawChunk>> byDim = new TreeMap<>();
        int skippedNotFull;
        int skippedNoBwr;
        int unreadable;
        final Set<Integer> dataVersions = new TreeSet<>();
        int totalChunks() {
            return byDim.values().stream().mapToInt(List::size).sum();
        }
    }

    private static Raw readRaw(MinecraftServer server, Path sourceRoot) throws IOException {
        Raw raw = new Raw();
        for (ServerLevel level : server.getAllLevels()) {
            Path folder = DimensionType.getStorageFolder(level.dimension(), sourceRoot);
            Path regions = folder.resolve("region");
            String dim = sourceRoot.relativize(folder).toString();
            List<RawChunk> chunks = new ArrayList<>();
            if (Files.isDirectory(regions)) {
                try (Stream<Path> files = Files.list(regions)) {
                    for (Path f : files.filter(p -> p.getFileName().toString().endsWith(".mca")).sorted().toList()) {
                        readRegion(f, chunks, raw);
                    }
                }
            }
            raw.byDim.put(dim, chunks);
        }
        int bes = raw.byDim.values().stream().flatMap(List::stream).mapToInt(c -> c.blockEntities.size()).sum();
        note("raw world " + sourceRoot.getFileName() + ": " + raw.totalChunks() + " full chunks in "
                + raw.byDim.entrySet().stream().map(e -> "'" + e.getKey() + "'=" + e.getValue().size()).toList()
                + ", " + bes + " block entities, " + raw.skippedNotFull + " chunks not full (ignored), " + raw.skippedNoBwr + " full chunks without any bwr block or block entity (ignored), "
                + raw.unreadable + " unreadable, data versions " + raw.dataVersions);
        return raw;
    }

    private static void readRegion(Path file, List<RawChunk> out, Raw raw) throws IOException {
        try (RandomAccessFile f = new RandomAccessFile(file.toFile(), "r")) {
            byte[] header = new byte[4096];
            if (f.length() < 8192) return;
            f.readFully(header);
            for (int i = 0; i < 1024; i++) {
                int entry = ((header[i * 4] & 0xFF) << 24) | ((header[i * 4 + 1] & 0xFF) << 16)
                        | ((header[i * 4 + 2] & 0xFF) << 8) | (header[i * 4 + 3] & 0xFF);
                if (entry == 0) continue;
                long offset = (entry >>> 8) * 4096L;
                try {
                    f.seek(offset);
                    int length = f.readInt();
                    int compression = f.readByte();
                    byte[] data = new byte[length - 1];
                    f.readFully(data);
                    InputStream in = switch (compression) {
                        case 1 -> new GZIPInputStream(new java.io.ByteArrayInputStream(data));
                        case 2 -> new InflaterInputStream(new java.io.ByteArrayInputStream(data));
                        case 3 -> new java.io.ByteArrayInputStream(data);
                        default -> throw new IOException("unsupported compression " + compression);
                    };
                    CompoundTag tag;
                    try (DataInputStream din = new DataInputStream(new java.io.BufferedInputStream(in))) {
                        tag = NbtIo.read(din);
                    }
                    RawChunk chunk = parseChunk(tag, raw);
                    if (chunk != null) out.add(chunk);
                } catch (IOException | RuntimeException e) {
                    raw.unreadable++;
                    failures.add("unreadable raw chunk " + file.getFileName() + "#" + i + ": " + e);
                }
            }
        }
    }

    private static RawChunk parseChunk(CompoundTag tag, Raw raw) {
        raw.dataVersions.add(tag.getInt("DataVersion"));
        if (!"minecraft:full".equals(tag.getString("Status"))) {
            raw.skippedNotFull++;
            return null;
        }
        RawChunk chunk = new RawChunk(tag.getInt("xPos"), tag.getInt("zPos"));
        for (Tag st : tag.getList("sections", Tag.TAG_COMPOUND)) {
            CompoundTag section = (CompoundTag) st;
            CompoundTag bs = section.getCompound("block_states");
            ListTag palette = bs.getList("palette", Tag.TAG_COMPOUND);
            if (palette.isEmpty()) continue;
            RawState[] states = new RawState[palette.size()];
            for (int i = 0; i < states.length; i++) {
                CompoundTag p = palette.getCompound(i);
                Map<String, String> props = new TreeMap<>();
                CompoundTag pp = p.getCompound("Properties");
                for (String k : pp.getAllKeys()) props.put(k, pp.getString(k));
                states[i] = new RawState(p.getString("Name"), props);
            }
            int[] counts = new int[states.length];
            if (states.length == 1) {
                counts[0] = 4096;
            } else {
                int bits = Math.max(4, 32 - Integer.numberOfLeadingZeros(states.length - 1));
                long[] data = bs.getLongArray("data");
                int perLong = 64 / bits;
                long mask = (1L << bits) - 1;
                for (int i = 0; i < 4096; i++) {
                    int idx = (int) ((data[i / perLong] >>> ((i % perLong) * bits)) & mask);
                    counts[idx]++;
                }
            }
            for (int i = 0; i < states.length; i++) {
                if (counts[i] > 0) chunk.states.merge(states[i], counts[i], Integer::sum);
            }
        }
        for (Tag bt : tag.getList("block_entities", Tag.TAG_COMPOUND)) chunk.blockEntities.add((CompoundTag) bt);
        // A world made by running the test fixtures holds thousands of plain terrain chunks; only chunks with mod content matter.
        if (!Boolean.getBoolean("bwr.saveCompat.allChunks")
                && chunk.states.keySet().stream().noneMatch(st -> st.name().startsWith("bwr:"))
                && chunk.blockEntities.stream().noneMatch(be -> be.getString("id").startsWith("bwr:"))) {
            raw.skippedNoBwr++;
            return null;
        }
        return chunk;
    }

    // ---- blocks ------------------------------------------------------------------------------------------------

    private static boolean decides(String ns) {
        return ns.equals("bwr") || ns.equals("minecraft");
    }

    private static String ns(String name) {
        int c = name.indexOf(':');
        return c < 0 ? "minecraft" : name.substring(0, c);
    }

    private static String canon(BlockState s) {
        Map<String, String> props = new TreeMap<>();
        for (Property<?> p : s.getProperties()) props.put(p.getName(), valueName(p, s));
        String name = BuiltInRegistries.BLOCK.getKey(s.getBlock()).toString();
        return props.isEmpty() ? name : name + props;
    }

    private static <T extends Comparable<T>> String valueName(Property<T> p, BlockState s) {
        return p.getName(s.getValue(p));
    }

    private static <T extends Comparable<T>> BlockState with(BlockState s, Property<T> p, String value) {
        Optional<T> v = p.getValue(value);
        return v.isPresent() ? s.setValue(p, v.get()) : null;
    }

    private static void compareBlocks(MinecraftServer server, Raw raw, boolean full) {
        String phase = full ? "load" : "after 100 ticks";
        Map<String, Integer> missingIds = new TreeMap<>();
        Map<String, Integer> droppedProps = new TreeMap<>();
        Map<String, Integer> badValues = new TreeMap<>();
        Map<String, Integer> expectedTotal = new TreeMap<>();
        Map<String, Integer> gameTotal = new TreeMap<>();
        Map<String, Integer> bwrIdsRaw = new TreeMap<>();
        Map<String, Integer> bwrIdsGame = new TreeMap<>();
        int chunks = 0;
        for (ServerLevel level : server.getAllLevels()) {
            Path folder = DimensionType.getStorageFolder(level.dimension(), server.getWorldPath(LevelResource.ROOT));
            String dim = server.getWorldPath(LevelResource.ROOT).relativize(folder).toString();
            for (RawChunk rc : raw.byDim.getOrDefault(dim, List.of())) {
                chunks++;
                Map<String, Integer> expected = new TreeMap<>();
                for (Map.Entry<RawState, Integer> e : rc.states.entrySet()) {
                    RawState rs = e.getKey();
                    int n = e.getValue();
                    ResourceLocation id = ResourceLocation.tryParse(rs.name());
                    Optional<Block> block = id == null ? Optional.empty() : BuiltInRegistries.BLOCK.getOptional(id);
                    if (block.isEmpty()) {
                        missingIds.merge(rs.name(), n, Integer::sum);
                        expected.merge("minecraft:air", n, Integer::sum);
                        continue;
                    }
                    BlockState state = block.get().defaultBlockState();
                    for (Map.Entry<String, String> p : rs.props().entrySet()) {
                        Property<?> prop = block.get().getStateDefinition().getProperty(p.getKey());
                        if (prop == null) {
                            droppedProps.merge(rs.name() + "." + p.getKey() + "=" + p.getValue(), n, Integer::sum);
                        } else {
                            BlockState next = with(state, prop, p.getValue());
                            if (next == null) badValues.merge(rs.name() + "." + p.getKey() + "=" + p.getValue(), n, Integer::sum);
                            else state = next;
                        }
                    }
                    expected.merge(canon(state), n, Integer::sum);
                    if (rs.name().startsWith("bwr:")) bwrIdsRaw.merge(rs.name(), n, Integer::sum);
                }
                level.setChunkForced(rc.x, rc.z, true);
                LevelChunk chunk = level.getChunk(rc.x, rc.z);
                Map<String, Integer> actual = new TreeMap<>();
                for (LevelChunkSection section : chunk.getSections()) {
                    section.getStates().count((state, count) -> {
                        actual.merge(canon(state), count, Integer::sum);
                        String n = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
                        if (n.startsWith("bwr:")) bwrIdsGame.merge(n, count, Integer::sum);
                    });
                }
                if (full) {
                    expected.forEach((k, v) -> expectedTotal.merge(k, v, Integer::sum));
                    actual.forEach((k, v) -> gameTotal.merge(k, v, Integer::sum));
                    for (String k : union(expected, actual)) {
                        int a = expected.getOrDefault(k, 0), b = actual.getOrDefault(k, 0);
                        if (a != b && decides(ns(k))) {
                            failures.add("[" + phase + "] chunk " + new ChunkPos(rc.x, rc.z) + " dim '" + dim + "': " + k
                                    + " raw-expected " + a + " but loaded " + b);
                        }
                    }
                }
            }
        }
        if (full) {
            for (var e : missingIds.entrySet()) {
                String msg = "block id no longer exists: " + e.getKey() + " x" + e.getValue();
                if (decides(ns(e.getKey()))) failures.add(msg); else note("(not decisive) " + msg);
            }
            for (var e : droppedProps.entrySet()) {
                String msg = "saved property no longer exists: " + e.getKey() + " x" + e.getValue();
                if (decides(ns(e.getKey()))) failures.add(msg); else note("(not decisive) " + msg);
            }
            for (var e : badValues.entrySet()) {
                String msg = "saved property value no longer accepted: " + e.getKey() + " x" + e.getValue();
                if (decides(ns(e.getKey()))) failures.add(msg); else note("(not decisive) " + msg);
            }
            int bwrBlocks = bwrIdsRaw.values().stream().mapToInt(Integer::intValue).sum();
            note("blocks [load]: " + chunks + " chunks compared, " + expectedTotal.size() + " distinct states in the raw data, "
                    + bwrIdsRaw.size() + " distinct bwr block ids / " + bwrBlocks + " bwr blocks");
            report.append("\nbwr block ids in the old save (raw count -> loaded count):\n");
            bwrIdsRaw.forEach((k, v) -> report.append("  ").append(k).append(' ').append(v).append(" -> ")
                    .append(bwrIdsGame.getOrDefault(k, 0)).append('\n'));
        } else {
            for (String k : union(bwrIdsRaw, bwrIdsGame)) {
                int a = bwrIdsRaw.getOrDefault(k, 0), b = bwrIdsGame.getOrDefault(k, 0);
                if (a != b) failures.add("[" + phase + "] bwr block " + k + " count changed " + a + " -> " + b
                        + " (removed or added by the mod's first ticks)");
            }
            note("blocks [" + phase + "]: bwr block counts compared for " + bwrIdsRaw.size() + " ids");
        }
    }

    private static Set<String> union(Map<String, Integer> a, Map<String, Integer> b) {
        Set<String> s = new TreeSet<>(a.keySet());
        s.addAll(b.keySet());
        return s;
    }

    // ---- block entities ----------------------------------------------------------------------------------------

    private static void compareBlockEntities(MinecraftServer server, Raw raw, String phase, boolean values) {
        int total = 0, found = 0, bwrTotal = 0, bwrFound = 0;
        Map<String, Integer> lost = new TreeMap<>();
        Map<String, Integer> diffs = new TreeMap<>();
        Map<String, Integer> perType = new TreeMap<>();
        Map<String, Integer> extra = new TreeMap<>();
        Map<String, Integer> drift = new TreeMap<>();
        Map<String, Integer> initialised = new TreeMap<>();
        Map<String, Integer> panelKeys = new TreeMap<>();
        String[] panelSample = new String[1];
        for (ServerLevel level : server.getAllLevels()) {
            Path folder = DimensionType.getStorageFolder(level.dimension(), server.getWorldPath(LevelResource.ROOT));
            String dim = server.getWorldPath(LevelResource.ROOT).relativize(folder).toString();
            for (RawChunk rc : raw.byDim.getOrDefault(dim, List.of())) {
                LevelChunk chunk = level.getChunk(rc.x, rc.z);
                Set<BlockPos> rawPositions = new TreeSet<>(Comparator.comparingLong(BlockPos::asLong));
                for (CompoundTag old : rc.blockEntities) {
                    BlockPos pos = new BlockPos(old.getInt("x"), old.getInt("y"), old.getInt("z"));
                    rawPositions.add(pos);
                    String id = old.getString("id");
                    boolean bwr = id.startsWith("bwr:");
                    total++;
                    if (bwr) {
                        bwrTotal++;
                        perType.merge(id, 1, Integer::sum);
                    }
                    if (values && id.equals("bwr:panel")) {
                        for (String k : old.getAllKeys()) panelKeys.merge(k, 1, Integer::sum);
                        if (panelSample[0] == null && old.toString().contains("plant")) panelSample[0] = old.toString();
                    }
                    BlockEntity be = chunk.getBlockEntity(pos);
                    if (be == null) {
                        lost.merge(id, 1, Integer::sum);
                        continue;
                    }
                    found++;
                    if (bwr) bwrFound++;
                    String nowId = String.valueOf(BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(be.getType()));
                    if (!nowId.equals(id)) {
                        diffs.merge(id + ": block entity type is now " + nowId, 1, Integer::sum);
                        continue;
                    }
                    CompoundTag now = be.saveWithFullMetadata(server.registryAccess());
                    List<String> out = new ArrayList<>();
                    subset("", old, now, out, true);
                    for (String d : out) {
                        if (values) {
                            // A zero the old save held that the mod now fills with a default is not lost data.
                            (d.matches(".* changed 0(\\.0)?[bsdfL]? -> .*") ? initialised : diffs).merge(id + ": " + d, 1, Integer::sum);
                        } else {
                            // After ticks the physics has moved on; which keys moved is only reported, so two builds can be compared.
                            int q = d.indexOf('\'', 1);
                            drift.merge(id + ": " + d.substring(0, Math.max(0, q + 1)), 1, Integer::sum);
                        }
                    }
                }
                for (BlockPos p : chunk.getBlockEntities().keySet()) {
                    if (!rawPositions.contains(p)) {
                        BlockEntity be = chunk.getBlockEntities().get(p);
                        extra.merge(String.valueOf(BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(be.getType())) + " on "
                                + BuiltInRegistries.BLOCK.getKey(be.getBlockState().getBlock()), 1, Integer::sum);
                    }
                }
            }
        }
        note("block entities [" + phase + "]: " + total + " in the raw data (" + bwrTotal + " bwr), " + found + " present ("
                + bwrFound + " bwr)");
        report.append("\nbwr block entity types in the old save [").append(phase).append("]: ").append(perType).append('\n');
        if (values) {
            report.append("\nbwr:panel block entity keys in the old save: ").append(panelKeys).append('\n');
            if (panelSample[0] != null) {
                report.append("sample panel with a plant binding: ")
                        .append(panelSample[0], 0, Math.min(2500, panelSample[0].length())).append('\n');
            }
            note("panel block entities in the old save: " + panelKeys.getOrDefault("id", 0) + ", keys " + panelKeys.keySet());
        }
        initialised.forEach((k, v) -> note("[" + phase + "] zero in the old save, initialised by the mod (not a loss): " + k + " x" + v));
        if (!values) {
            report.append("\nvalues that differ from the old save after the first ticks (informational: the physics runs; compare two builds' lists):\n");
            drift.forEach((k, v) -> report.append("  DRIFT ").append(k).append(" x").append(v).append('\n'));
        }
        if (!extra.isEmpty()) {
            note("block entities created without saved data [" + phase + "]: " + extra);
        }
        for (var e : lost.entrySet()) {
            String msg = "[" + phase + "] block entity lost: " + e.getKey() + " x" + e.getValue();
            if (decides(ns(e.getKey()))) failures.add(msg); else note("(not decisive) " + msg);
        }
        for (var e : diffs.entrySet()) {
            String msg = "[" + phase + "] " + e.getKey() + " x" + e.getValue();
            if (decides(ns(e.getKey()))) failures.add(msg); else note("(not decisive) " + msg);
        }
    }

    /** Appends one line to {@code out} for every value of {@code old} that {@code now} lacks or holds differently. */
    private static void subset(String path, Tag old, Tag now, List<String> out, boolean top) {
        if (old instanceof CompoundTag oc) {
            if (!(now instanceof CompoundTag nc)) {
                out.add("'" + path + "' was a compound, is now " + now.getType().getName());
                return;
            }
            for (String k : oc.getAllKeys()) {
                if (top && (k.equals("x") || k.equals("y") || k.equals("z") || k.equals("id") || k.equals("keepPacked"))) continue;
                String p = path.isEmpty() ? k : path + "." + k;
                Tag ov = oc.get(k);
                if (!nc.contains(k)) {
                    if (!emptyValue(ov)) out.add("'" + p + "' is no longer saved (was " + brief(ov) + ")");
                    continue;
                }
                subset(p, ov, nc.get(k), out, false);
            }
        } else if (old instanceof ListTag ol) {
            if (!(now instanceof ListTag nl)) {
                out.add("'" + path + "' was a list, is now " + now.getType().getName());
                return;
            }
            if (ol.size() != nl.size()) {
                out.add("'" + path + "[]' length " + ol.size() + " -> " + nl.size());
                return;
            }
            for (int i = 0; i < ol.size(); i++) subset(path + "[]", ol.get(i), nl.get(i), out, false);
        } else if (old instanceof net.minecraft.nbt.LongArrayTag ol) {
            if (!(now instanceof net.minecraft.nbt.LongArrayTag nl)) {
                out.add("'" + path + "' was a long array, is now " + now.getType().getName());
                return;
            }
            // Position sets are saved in hash order; only the members count.
            java.util.Set<Long> before = new TreeSet<>();
            java.util.Set<Long> after = new TreeSet<>();
            for (long v : ol.getAsLongArray()) before.add(v);
            for (long v : nl.getAsLongArray()) after.add(v);
            if (!before.equals(after)) {
                java.util.Set<Long> gone = new TreeSet<>(before);
                gone.removeAll(after);
                java.util.Set<Long> added = new TreeSet<>(after);
                added.removeAll(before);
                out.add("'" + path + "' members changed: " + before.size() + " -> " + after.size() + " (" + gone.size()
                        + " gone, " + added.size() + " new)");
            }
        } else if (old instanceof NumericTag on) {
            if (!(now instanceof NumericTag nn) || Double.compare(on.getAsDouble(), nn.getAsDouble()) != 0) {
                out.add("'" + path + "' changed " + brief(old) + " -> " + brief(now));
            }
        } else if (!old.equals(now)) {
            out.add("'" + path + "' changed " + brief(old) + " -> " + brief(now));
        }
    }

    private static boolean emptyValue(Tag t) {
        if (t instanceof NumericTag n) return n.getAsDouble() == 0;
        if (t instanceof StringTag s) return s.getAsString().isEmpty();
        if (t instanceof CompoundTag c) return c.isEmpty();
        if (t instanceof ListTag l) return l.isEmpty();
        if (t instanceof net.minecraft.nbt.CollectionTag<?> c) return c.isEmpty();
        return false;
    }

    private static String brief(Tag t) {
        String s = t.toString();
        return s.length() > 60 ? s.substring(0, 57) + "..." : s;
    }

    // ---- world-level saved data ----------------------------------------------------------------------------------

    /**
     * Saves the world and compares each {@code data/bwr_*.dat} (condensate dismantling ledger, suppression basins, ...) with the
     * old save's: whatever the mod loaded and then rewrote must still hold every value the old file held.
     */
    private static void checkSavedData(MinecraftServer server, Path sourceRoot) throws IOException {
        Path oldDir = sourceRoot.resolve("data");
        if (!Files.isDirectory(oldDir)) {
            note("no data folder in the old save");
            return;
        }
        server.saveEverything(true, true, true);
        Path newDir = server.getWorldPath(LevelResource.ROOT).resolve("data");
        int compared = 0;
        int drifted = 0;
        try (Stream<Path> files = Files.list(oldDir)) {
            for (Path f : files.filter(p -> p.getFileName().toString().startsWith("bwr_")
                    && p.getFileName().toString().endsWith(".dat")).toList()) {
                Path now = newDir.resolve(f.getFileName());
                if (!Files.isRegularFile(now)) {
                    failures.add("saved data file is gone: " + f.getFileName());
                    continue;
                }
                CompoundTag before = NbtIo.readCompressed(f, NbtAccounter.unlimitedHeap());
                CompoundTag after = NbtIo.readCompressed(now, NbtAccounter.unlimitedHeap());
                List<String> out = new ArrayList<>();
                subset("", before.getCompound("data"), after.getCompound("data"), out, false);
                for (String d : out) {
                    // The physics keeps running for 100 ticks, so a number that moved is not a loss; a missing key or a changed
                    // shape is.
                    if (d.matches(".* changed -?[0-9.eE+-]+[bsdfL]? -> -?[0-9.eE+-]+[bsdfL]?")) drifted++;
                    else failures.add("saved data " + f.getFileName() + ": " + d);
                }
                compared++;
            }
        }
        note("saved data files compared: " + compared + " (" + drifted + " numbers moved with the running physics)");
    }

    // ---- players -----------------------------------------------------------------------------------------------

    private static void checkPlayers(MinecraftServer server, Path sourceRoot) throws IOException {
        Path dir = sourceRoot.resolve("playerdata");
        if (!Files.isDirectory(dir)) {
            note("no playerdata folder");
            return;
        }
        HolderLookup.Provider access = server.registryAccess();
        int stacks = 0, bwrStacks = 0;
        try (Stream<Path> files = Files.list(dir)) {
            for (Path f : files.filter(p -> p.toString().endsWith(".dat")).toList()) {
                CompoundTag player = NbtIo.readCompressed(f, NbtAccounter.unlimitedHeap());
                for (String list : new String[] {"Inventory", "EnderItems"}) {
                    for (Tag t : player.getList(list, Tag.TAG_COMPOUND)) {
                        CompoundTag old = ((CompoundTag) t).copy();
                        old.remove("Slot"); // the inventory slot belongs to the player file, not to the stack
                        String id = old.getString("id");
                        if (id.isEmpty() || id.equals("minecraft:air")) continue;
                        stacks++;
                        boolean bwr = id.startsWith("bwr:");
                        if (bwr) bwrStacks++;
                        if (!decides(ns(id))) continue;
                        var parsed = ItemStack.CODEC.parse(access.createSerializationContext(NbtOps.INSTANCE), old);
                        if (parsed.error().isPresent() || parsed.result().isEmpty() || parsed.result().get().isEmpty()) {
                            failures.add("player item no longer decodes: " + f.getFileName() + " " + list + " " + id + " "
                                    + parsed.error().map(Object::toString).orElse("(empty)"));
                            continue;
                        }
                        Tag back = ItemStack.CODEC.encodeStart(access.createSerializationContext(NbtOps.INSTANCE),
                                parsed.result().get()).result().orElse(null);
                        List<String> out = new ArrayList<>();
                        if (back != null) subset("", old, back, out, true);
                        for (String d : out) failures.add("player item " + id + " (" + list + "): " + d);
                    }
                }
            }
        }
        note("player files: " + stacks + " item stacks checked (" + bwrStacks + " bwr)");
    }

    // ---- verdict -----------------------------------------------------------------------------------------------

    private static void note(String s) {
        notes.add(s);
        LOGGER.info("BWR SAVE COMPAT CHECK: {}", s);
    }

    private static void finish(MinecraftServer server) {
        if (ticks < 0) return;
        ticks = Integer.MIN_VALUE / 2;
        for (String s : failures.stream().limit(200).toList()) LOGGER.error("BWR SAVE COMPAT CHECK: PROBLEM {}", s);
        try {
            StringBuilder b = new StringBuilder();
            b.append("notes:\n");
            notes.forEach(n -> b.append("  ").append(n).append('\n'));
            b.append("\nproblems (").append(failures.size()).append("):\n");
            failures.forEach(n -> b.append("  ").append(n).append('\n'));
            b.append(report);
            Files.writeString(Path.of("save-compat-report.txt"), b.toString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            LOGGER.error("BWR SAVE COMPAT CHECK: cannot write the report: {}", e.toString());
        }
        if (failures.isEmpty()) {
            LOGGER.info("BWR SAVE COMPAT CHECK: PASS");
        } else {
            LOGGER.error("BWR SAVE COMPAT CHECK: FAIL {} problems", failures.size());
        }
        server.halt(false);
    }
}
