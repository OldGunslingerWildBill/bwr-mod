package dev.bwr.mod.floodlight;

import dev.bwr.mod.registry.BwrBlockEntities;
import dev.bwr.mod.world.AssemblyAccess;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LightBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.Vec3;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * The block entity of a beam lamp: the floodlight's aim, and the exact list of light blocks this lamp has placed.
 *
 * <p>The list is the lamp's own record of what it changed: for each cell, whether it was air, cave air or still water.
 * {@link #reconcile()} brings the world to the plan: lights in the cells the planner wants while the lamp is lit, and every
 * listed light the plan no longer wants put back. A light goes only into a cell that is air or a still water source now, and
 * a cell is put back only while it still holds a light, so a block (or a light) a player put there since is never touched.
 * A put-back due in an unloaded chunk is handed to {@link PendingLights}, which makes it when that chunk next loads.
 *
 * <p>All world writes use {@code UPDATE_CLIENTS | UPDATE_KNOWN_SHAPE}: no neighbour shape updates, which would read (and so
 * load) the neighbours of a cell at the edge of the loaded area. Nothing here runs on a chunk unload, a save or a shutdown.
 *
 * <p>The floodlight's aim is kept in whole degrees: a yaw offset of 0 to 359 from the lamp's facing, a pitch of -75 to +30 and
 * a reach of 8 to 128 blocks (32 unless set). It is saved as {@code YawDeg}, {@code PitchDeg} and {@code Reach} when they are
 * not the defaults; a yaw on the old 45 degree grid and a pitch on the old 15 degree grid are also written as the old step keys
 * {@code Yaw} and {@code Pitch}, so a save opened by an older build keeps its aim, and a save from an older build (step keys
 * only) loads to the same aim. {@code Lights} (positions) is saved with {@code LightKinds} when the list is not empty.
 *
 * <p>A change of aim from a screen or a computer ({@link #setAim}, {@link #setReach}) moves the head at once and asks for a
 * re-plan through {@link AimReplans}, which makes one re-plan for a burst of changes.
 */
public final class BeamLampBlockEntity extends BlockEntity {
    /** Yaw steps of 45 degrees, relative to the lamp's facing (the quick turn and the old save format). */
    public static final int YAW_STEPS = 8;
    /** Pitch steps from -75 to +30 degrees, in 15 degree steps (the quick tilt and the old save format). */
    public static final int PITCH_STEPS = 8;
    /** The pitch step that is level (0 degrees). */
    public static final int PITCH_LEVEL = 5;
    /** The floodlight's largest yaw offset from its facing, degrees (0 to 359). */
    public static final int YAW_MAX = 359;
    /** The floodlight's pitch: lowest (down) and highest (up), degrees. */
    public static final int PITCH_MIN = -75, PITCH_MAX = 30;
    /** The floodlight's reach: shortest, longest and default, blocks. */
    public static final int REACH_MIN = 8, REACH_MAX = 128, REACH_DEFAULT = 32;
    /** Flags of every write: tell the clients, but make no neighbour shape updates (they can load chunks at an edge). */
    public static final int WRITE_FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;

    private static final int YAW_DEGREES = 45;
    private static final int PITCH_BASE = -75;
    private static final int PITCH_DEGREES = 15;

    private int yawDeg;
    private int pitchDeg;
    private int reach = REACH_DEFAULT;
    private final Map<Long, Byte> lights = new HashMap<>();
    /** Plans cast since the server started, by all lamps (tests, diagnostics). */
    private static final java.util.concurrent.atomic.AtomicLong PLANS = new java.util.concurrent.atomic.AtomicLong();

    public static long plansTotal() {
        return PLANS.get();
    }

    /** Plans this lamp has cast since it was loaded (tests, diagnostics). */
    private int plans;

    public int plans() {
        return plans;
    }

    public BeamLampBlockEntity(BlockPos pos, BlockState state) {
        super(BwrBlockEntities.BEAM_LAMP.get(), pos, state);
    }

    // ---- the aim and the beam

    private BeamLampBlock lampBlock() {
        return getBlockState().getBlock() instanceof BeamLampBlock b ? b : null;
    }

    public boolean floodlight() {
        return getBlockState().getBlock() instanceof XenonFloodlightBlock;
    }

    public Direction facing() {
        BeamLampBlock b = lampBlock();
        return b == null ? Direction.NORTH : b.facing(getBlockState());
    }

    /** Whether the lamp is lit (a redstone signal reaches it, as of its last re-plan). */
    public boolean lit() {
        return getBlockState().hasProperty(BeamLampBlock.LIT) && getBlockState().getValue(BeamLampBlock.LIT);
    }

    /** The yaw as a step of the 45 degree grid (rounded down when the yaw is not on it). */
    public int yawStep() {
        return Math.floorDiv(yawDeg, YAW_DEGREES);
    }

    /** The pitch as a step of the 15 degree grid (rounded down when the pitch is not on it). */
    public int pitchStep() {
        return Math.floorDiv(pitchDeg - PITCH_BASE, PITCH_DEGREES);
    }

    /** The yaw offset from the facing, degrees, 0 to 359. */
    public int yawDegrees() {
        return yawDeg;
    }

    /** The pitch, degrees; negative points down. */
    public int pitchDegrees() {
        return pitchDeg;
    }

    /** How far the beam reaches, blocks: the floodlight's own setting (8 to 128), the fixed range of any other lamp. */
    public int reach() {
        BeamLampBlock b = lampBlock();
        return b instanceof XenonFloodlightBlock ? reach : b == null ? REACH_DEFAULT : (int) Math.round(b.range());
    }

    public Vec3 beamDirection() {
        BeamLampBlock b = lampBlock();
        return b == null ? new Vec3(0, -1, 0) : b.beamDirection(getBlockState(), this);
    }

    public Vec3 beamOrigin() {
        BeamLampBlock b = lampBlock();
        return b == null ? Vec3.atCenterOf(worldPosition) : b.beamOrigin(getBlockState(), this);
    }

    /** The blockstate y rotation that turns a north-facing model to face {@code facing}. */
    public static int yawOf(Direction facing) {
        return switch (facing) {
            case EAST -> 90;
            case SOUTH -> 180;
            case WEST -> 270;
            default -> 0;
        };
    }

    /** The lamp's aim as a message: yaw offset and pitch, degrees. */
    public Component aimMessage() {
        return Component.translatable("message.bwr.xenon_floodlight.aim", yawDegrees(), pitchDegrees());
    }

    /**
     * Turns the aim to the next step of the old grid (one yaw step of 45 degrees, or, for the sneak-right-click, one pitch step
     * of 15 degrees, wrapping from +30 round to -75), then re-plans the light at once.
     */
    public void stepAim(boolean pitchStep) {
        if (pitchStep) {
            pitchDeg = PITCH_BASE + ((pitchStep() + 1) % PITCH_STEPS) * PITCH_DEGREES;
        } else {
            yawDeg = ((yawStep() + 1) % YAW_STEPS) * YAW_DEGREES;
        }
        aimChanged();
        reconcile();
    }

    public static boolean validYaw(int degrees) {
        return degrees >= 0 && degrees <= YAW_MAX;
    }

    public static boolean validPitch(int degrees) {
        return degrees >= PITCH_MIN && degrees <= PITCH_MAX;
    }

    public static boolean validReach(int blocks) {
        return blocks >= REACH_MIN && blocks <= REACH_MAX;
    }

    /**
     * Aims the floodlight: yaw offset (0 to 359) and pitch (-75 to +30), degrees. The head turns at once; the light follows at
     * the next re-plan, one for a burst of calls ({@link AimReplans}). Returns false, and changes nothing, for another lamp or a
     * value out of range.
     */
    public boolean setAim(int yaw, int pitch) {
        return setAim(yaw, pitch, reach);
    }

    /** As {@link #setAim(int, int)}, with the reach too (8 to 128 blocks). */
    public boolean setAim(int yaw, int pitch, int reachBlocks) {
        if (!floodlight() || !validYaw(yaw) || !validPitch(pitch) || !validReach(reachBlocks)) {
            return false;
        }
        if (yaw != yawDeg || pitch != pitchDeg || reachBlocks != reach) {
            yawDeg = yaw;
            pitchDeg = pitch;
            reach = reachBlocks;
            aimChanged();
            if (level instanceof ServerLevel server) {
                AimReplans.request(server, worldPosition);
            }
        }
        return true;
    }

    /** Sets the floodlight's reach, blocks (8 to 128). False, and no change, for another lamp or a value out of range. */
    public boolean setReach(int blocks) {
        return setAim(yawDeg, pitchDeg, blocks);
    }

    /** Saves the new aim and tells the clients (the head turns to it). */
    private void aimChanged() {
        setChanged();
        if (level != null) {
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), Block.UPDATE_CLIENTS);
        }
    }

    /** Who may aim the lamp: the same rule as the monitor's setpoint. */
    public static boolean mayAim(Level level, Player player, BlockPos pos) {
        return player.getAbilities().mayBuild && AssemblyAccess.permitted(level, player, pos);
    }

    /**
     * Whether a redstone signal reaches {@code pos}, or null when that cannot be read without loading a chunk (reading a
     * signal can look two blocks out: a wire's connections, a powered conductor's neighbours).
     */
    public static Boolean powered(ServerLevel level, BlockPos pos) {
        if (!level.hasChunksAt(pos.getX() - 2, pos.getZ() - 2, pos.getX() + 2, pos.getZ() + 2)) {
            return null;
        }
        return level.hasNeighborSignal(pos);
    }

    // ---- the lights

    /** The lights this lamp has placed: packed position to what the cell held before. */
    public Map<Long, Byte> placed() {
        return Collections.unmodifiableMap(lights);
    }

    /**
     * Brings the world to the plan. Server only. Does nothing on a client, for a removed lamp, when the lamp's own chunk is
     * not loaded, or when its power cannot be read without loading a neighbour chunk.
     */
    public void reconcile() {
        if (!(level instanceof ServerLevel server) || isRemoved() || !server.isLoaded(worldPosition)) {
            return;
        }
        BlockState state = getBlockState();
        BeamLampBlock block = lampBlock();
        if (block == null) {
            clearAll();
            return;
        }
        Boolean powered = powered(server, worldPosition);
        if (powered == null) {
            return;
        }
        if (state.getValue(BeamLampBlock.LIT) != powered) {
            state = block.switched(state, powered);
            server.setBlock(worldPosition, state, Block.UPDATE_CLIENTS);
        }
        int lightLevel = block.projectedLevel(state);
        XenonBeam.Plan plan = powered
                ? XenonBeam.plan(world(server), worldPosition, block.beamOrigin(state, this), block.beamDirection(state, this),
                        reach(), block.halfAngleDegrees(), block.spillAngleDegrees(), block.lightStep(), lights)
                : XenonBeam.Plan.NONE;
        if (powered) {
            PLANS.incrementAndGet();
            plans++;
        }
        Map<Long, Byte> want = plan.cells();
        int spillLevel = block.spillLevel(lightLevel);

        boolean changed = false;
        for (var it = lights.entrySet().iterator(); it.hasNext(); ) {
            var entry = it.next();
            long key = entry.getKey();
            BlockPos p = BlockPos.of(key);
            if (!server.isLoaded(p)) {
                if (powered) {
                    // Still lit, but the cell cannot be seen: keep it on the list until its chunk is back.
                    continue;
                }
                PendingLights.get(server).add(p, entry.getValue());
                it.remove();
                changed = true;
                continue;
            }
            BlockState now = server.getBlockState(p);
            if (!now.is(Blocks.LIGHT)) {
                // Someone replaced the light (a block placed, a piston, a bucket): it is not ours any more.
                it.remove();
                changed = true;
                continue;
            }
            if (want.containsKey(key)) {
                int level = plan.isSpill(key) ? spillLevel : lightLevel;
                if (now.getValue(LightBlock.LEVEL) != level) {
                    server.setBlock(p, now.setValue(LightBlock.LEVEL, level), WRITE_FLAGS);
                }
                continue;
            }
            restoreLoaded(server, p, now, entry.getValue());
            it.remove();
            changed = true;
        }
        for (var entry : want.entrySet()) {
            if (lights.containsKey(entry.getKey())) {
                continue;
            }
            int kind = placeCell(server, entry.getKey(), plan.isSpill(entry.getKey()) ? spillLevel : lightLevel);
            if (kind >= 0) {
                lights.put(entry.getKey(), (byte) kind);
                changed = true;
            }
        }
        if (changed) {
            setChanged();
        }
        block.afterReconcile(server, worldPosition, state);
    }

    /** Takes back every light this lamp placed (the lamp is broken or replaced). Loads nothing: unloaded cells are queued. */
    public void clearAll() {
        if (level instanceof ServerLevel server) {
            for (var entry : lights.entrySet()) {
                BlockPos p = BlockPos.of(entry.getKey());
                if (!server.isLoaded(p)) {
                    PendingLights.get(server).add(p, entry.getValue());
                    continue;
                }
                BlockState now = server.getBlockState(p);
                if (now.is(Blocks.LIGHT)) {
                    restoreLoaded(server, p, now, entry.getValue());
                }
            }
        }
        if (!lights.isEmpty()) {
            lights.clear();
            setChanged();
        }
    }

    /** Puts a loaded cell that holds a light back to what it was: water if the light is waterlogged now, else its air. */
    static void restoreLoaded(ServerLevel server, BlockPos p, BlockState light, byte kind) {
        // A waterlogged light is a water source to the fluid code, so the water it goes back to behaves exactly as before.
        server.setBlock(p, XenonBeam.restoredState(light, kind), WRITE_FLAGS);
    }

    /**
     * Places a light where the cell is still air, cave air or a still water source, in a loaded chunk. Returns what the cell
     * held ({@link XenonBeam#kindOf}), or -1 when nothing was placed.
     */
    private static int placeCell(ServerLevel server, long key, int lightLevel) {
        BlockPos p = BlockPos.of(key);
        if (!server.isLoaded(p)) {
            return -1;
        }
        int kind = XenonBeam.kindOf(server.getBlockState(p));
        if (kind < 0) {
            return -1;
        }
        PendingLights.get(server).forget(p);
        server.setBlock(p, XenonBeam.lightState((byte) kind, lightLevel), WRITE_FLAGS);
        return kind;
    }

    /**
     * What a plan reads. A ray reads cell after cell of one chunk, so the answer to "is this chunk loaded" and the chunk
     * itself are remembered for the last chunk read (for the length of one plan): the same answers, one chunk lookup per
     * chunk crossed instead of three per cell. A chunk that is not loaded is never remembered and never loaded.
     */
    private static XenonBeam.World world(ServerLevel server) {
        return new XenonBeam.World() {
            private long chunkKey = Long.MIN_VALUE;
            private LevelChunk chunk;

            @Override
            public boolean loaded(BlockPos pos) {
                if (server.isOutsideBuildHeight(pos)) {
                    return server.isLoaded(pos);
                }
                long key = ChunkPos.asLong(pos.getX() >> 4, pos.getZ() >> 4);
                if (key == chunkKey) {
                    return true;
                }
                if (!server.isLoaded(pos)) {
                    return false;
                }
                chunkKey = key;
                chunk = server.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4);
                if (chunk == null) {
                    // Full, but not yet visible to the chunk source (it became full this tick): reading it would make the
                    // game finish its load on our stack. It counts as not loaded; the ray stops here.
                    chunkKey = Long.MIN_VALUE;
                    return false;
                }
                return true;
            }

            @Override
            public BlockState state(BlockPos pos) {
                if (chunk != null && chunkKey == ChunkPos.asLong(pos.getX() >> 4, pos.getZ() >> 4) && !server.isOutsideBuildHeight(pos)) {
                    return chunk.getBlockState(pos);
                }
                return server.getBlockState(pos);
            }

            @Override
            public boolean opaque(BlockState state, BlockPos pos) {
                return state.isSolidRender(server, pos);
            }
        };
    }

    // ---- saving (nothing here ticks, and nothing here runs on load or unload)

    /**
     * The aim keys: the degrees and the reach when they are not the defaults, and the old step keys while the aim is on the
     * old grid (so an older build reads the same aim).
     */
    private void putAim(CompoundTag tag) {
        if (yawDeg != 0) {
            tag.putInt("YawDeg", yawDeg);
            if (yawDeg % YAW_DEGREES == 0) {
                tag.putInt("Yaw", yawDeg / YAW_DEGREES);
            }
        }
        if (pitchDeg != 0) {
            tag.putInt("PitchDeg", pitchDeg);
            if ((pitchDeg - PITCH_BASE) % PITCH_DEGREES == 0) {
                tag.putInt("Pitch", (pitchDeg - PITCH_BASE) / PITCH_DEGREES);
            }
        }
        if (reach != REACH_DEFAULT) {
            tag.putInt("Reach", reach);
        }
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        putAim(tag);
        if (!lights.isEmpty()) {
            long[] keys = new long[lights.size()];
            byte[] kinds = new byte[lights.size()];
            int i = 0;
            for (var entry : lights.entrySet()) {
                keys[i] = entry.getKey();
                kinds[i++] = entry.getValue();
            }
            tag.putLongArray("Lights", keys);
            tag.putByteArray("LightKinds", kinds);
        }
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        // The degrees when they were saved; else the old step keys, to the same aim; else the defaults.
        if (tag.contains("YawDeg", Tag.TAG_INT)) {
            yawDeg = Math.floorMod(tag.getInt("YawDeg"), YAW_MAX + 1);
        } else {
            yawDeg = Math.floorMod(tag.getInt("Yaw"), YAW_STEPS) * YAW_DEGREES;
        }
        if (tag.contains("PitchDeg", Tag.TAG_INT)) {
            pitchDeg = Mth.clamp(tag.getInt("PitchDeg"), PITCH_MIN, PITCH_MAX);
        } else {
            int step = Math.floorMod(tag.contains("Pitch", Tag.TAG_INT) ? tag.getInt("Pitch") : PITCH_LEVEL, PITCH_STEPS);
            pitchDeg = PITCH_BASE + step * PITCH_DEGREES;
        }
        reach = tag.contains("Reach", Tag.TAG_INT) ? Mth.clamp(tag.getInt("Reach"), REACH_MIN, REACH_MAX) : REACH_DEFAULT;
        lights.clear();
        long[] keys = tag.getLongArray("Lights");
        byte[] kinds = tag.getByteArray("LightKinds");
        for (int i = 0; i < keys.length; i++) {
            byte kind = i < kinds.length ? kinds[i] : XenonBeam.AIR;
            lights.put(keys[i], kind == XenonBeam.CAVE_AIR || kind == XenonBeam.WATER ? kind : XenonBeam.AIR);
        }
    }

    // The aim reaches the client with the chunk and with each change, for the renderer.
    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        CompoundTag tag = new CompoundTag();
        putAim(tag);
        return tag;
    }

    @Override
    public net.minecraft.network.protocol.Packet<net.minecraft.network.protocol.game.ClientGamePacketListener> getUpdatePacket() {
        return net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket.create(this);
    }
}
