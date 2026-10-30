package dev.bwr.mod.gui;

import dev.bwr.mod.floodlight.BeamLampBlockEntity;
import dev.bwr.mod.floodlight.XenonBeam;
import dev.bwr.mod.registry.BwrMenus;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.phys.Vec3;

/**
 * The xenon floodlight's aiming screen (LIGHTING.md): the heading (yaw offset from the way the lamp faces, 0 to 359 degrees),
 * the tilt (pitch, -75 to +30 degrees) and the reach (8 to 128 blocks), each in single steps.
 *
 * <p>The screen sends absolute values, so a command that arrives twice or late does no harm, and shows what it has just asked
 * for without waiting for the next snapshot ({@link #aimTo}, {@link #reachTo}; the snapshot is ignored for a moment so it
 * does not wind the numbers back). The server checks every command: the player has this menu open and is in reach of the
 * lamp, is not a spectator, may build there ({@link BeamLampBlockEntity#mayAim}), sends no more than
 * {@link #MAX_COMMANDS} commands in {@link #WINDOW_TICKS} ticks, and the values are in range. The lamp's head follows at
 * once and its light follows one re-plan later, one re-plan for a burst of commands ({@link dev.bwr.mod.floodlight.AimReplans}).
 */
public class FloodlightAimMenu extends BwrMenu {
    /** Command 0: heading {@code a} (0 to 359) and tilt {@code b} (-75 to +30), degrees. */
    public static final int SET_AIM = 0;
    /** Command 1: reach {@code a}, blocks (8 to 128). */
    public static final int SET_REACH = 1;
    /** Most commands a menu takes in {@link #WINDOW_TICKS} ticks; the rest are dropped. */
    public static final int MAX_COMMANDS = 30;
    public static final int WINDOW_TICKS = 20;
    /** How far (blocks) a player may be from the lamp, squared. */
    public static final double MAX_DISTANCE_SQ = 8.0 * 8.0;
    /** Ticks the screen keeps its own numbers after a click before it takes the server's again. */
    private static final int HOLD_TICKS = 12;

    public boolean present;
    public boolean lit;
    public int yaw, pitch, reach = BeamLampBlockEntity.REACH_DEFAULT, lights;
    /** Client: ticks left during which the snapshot does not overwrite the numbers just clicked. */
    private int hold;
    private long windowStart = Long.MIN_VALUE;
    private int windowCount;

    public FloodlightAimMenu(int id, Inventory inv, RegistryFriendlyByteBuf b) {
        this(id, inv, b.readBlockPos());
    }

    public FloodlightAimMenu(int id, Inventory inv, BlockPos pos) {
        super(BwrMenus.FLOODLIGHT_AIM.get(), id, inv, pos, inv.player.level().getBlockState(pos).getBlock());
    }

    public static void open(ServerPlayer player, BlockPos pos) {
        if (!(player.level().getBlockEntity(pos) instanceof BeamLampBlockEntity lamp) || !lamp.floodlight()) {
            return;
        }
        player.openMenu(new SimpleMenuProvider((id, inv, who) -> new FloodlightAimMenu(id, inv, pos), lamp.getBlockState().getBlock().getName()),
                b -> b.writeBlockPos(pos));
    }

    @Override
    protected void writeSnapshot(FriendlyByteBuf b) {
        var lamp = blockEntity(BeamLampBlockEntity.class);
        b.writeBoolean(lamp != null && lamp.floodlight());
        if (lamp == null || !lamp.floodlight()) {
            return;
        }
        b.writeBoolean(lamp.lit());
        b.writeVarInt(lamp.yawDegrees());
        b.writeVarInt(lamp.pitchDegrees());
        b.writeVarInt(lamp.reach());
        b.writeVarInt(lamp.placed().size());
    }

    @Override
    protected void readSnapshot(FriendlyByteBuf b) {
        present = b.readBoolean();
        if (!present) {
            return;
        }
        lit = b.readBoolean();
        int y = b.readVarInt(), p = b.readVarInt(), r = b.readVarInt();
        lights = b.readVarInt();
        if (hold <= 0) {
            yaw = y;
            pitch = p;
            reach = r;
        }
    }

    /** Client: counts the hold down (called every screen tick). */
    public void clientTick() {
        if (hold > 0) {
            hold--;
        }
    }

    // ---- client side: what a click asks for

    /** Client: aims to {@code yawDegrees} (wrapped into 0 to 359) and {@code pitchDegrees} (kept in -75 to +30), and tells the server. */
    public void aimTo(int yawDegrees, int pitchDegrees) {
        yaw = Math.floorMod(yawDegrees, BeamLampBlockEntity.YAW_MAX + 1);
        pitch = Mth.clamp(pitchDegrees, BeamLampBlockEntity.PITCH_MIN, BeamLampBlockEntity.PITCH_MAX);
        hold = HOLD_TICKS;
        sendCommand(SET_AIM, yaw, pitch);
    }

    /** Client: sets the reach to {@code blocks} (kept in 8 to 128) and tells the server. */
    public void reachTo(int blocks) {
        reach = Mth.clamp(blocks, BeamLampBlockEntity.REACH_MIN, BeamLampBlockEntity.REACH_MAX);
        hold = HOLD_TICKS;
        sendCommand(SET_REACH, reach);
    }

    // ---- server side

    @Override
    public void handleCommand(ServerPlayer who, int command, int a, int b) {
        var lamp = blockEntity(BeamLampBlockEntity.class);
        if (lamp == null || !lamp.floodlight() || !stillValid(who)) {
            return;
        }
        if (who.isSpectator() || who.gameMode.getGameModeForPlayer() == net.minecraft.world.level.GameType.SPECTATOR || who.distanceToSqr(Vec3.atCenterOf(pos)) > MAX_DISTANCE_SQ
                || !BeamLampBlockEntity.mayAim(who.level(), who, pos)) {
            return;
        }
        long now = who.level().getGameTime();
        if (windowStart == Long.MIN_VALUE || now - windowStart >= WINDOW_TICKS || now < windowStart) {
            windowStart = now;
            windowCount = 0;
        }
        if (++windowCount > MAX_COMMANDS) {
            markSnapshotDirty();
            return;
        }
        switch (command) {
            case SET_AIM -> lamp.setAim(a, b);
            case SET_REACH -> lamp.setReach(a);
            default -> {
            }
        }
        markSnapshotDirty();
    }

    /** The most lights a plan may hold, for the screen's "n of 256". */
    public static int maxLights() {
        return XenonBeam.MAX_LIGHTS;
    }
}
