package dev.bwr.mod.devtest;

import com.mojang.logging.LogUtils;
import dev.bwr.mod.eccs.AssemblyPort;
import dev.bwr.mod.eccs.PumpAssemblyBlock;
import dev.bwr.mod.piping.LubeOilPipeBlock;
import dev.bwr.mod.piping.PaintedPipeBlock;
import dev.bwr.mod.power.LubeOilValveBlock;
import dev.bwr.mod.power.TurbineAuxBlock;
import dev.bwr.mod.piping.PipePaint;
import dev.bwr.mod.power.PowerModuleBlock;
import dev.bwr.mod.power.PowerModuleBlockEntity;
import dev.bwr.mod.registry.BwrBlocks;
import dev.bwr.mod.steam.MainSteamIsolationValveBlock;
import net.minecraft.client.CloudStatus;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Difficulty;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.PipeBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/**
 * Pictures of the high-pressure water pipe and the steam pipe in a real world, for the before/after comparison of the pipe
 * remodel (PIPES.md), and of the lube oil piping (TURBINE-AUX.md): a basement lube oil room piped up through the hall floor to the
 * turbine train, the Lube Oil Valve open and shut with its limit switch box close up, the oil filter housing in-line, and the oil
 * pipe beside the water and steam pipes. The pipe pictures: straight runs side by side, corners, tees and six-way junctions of each, pipes meeting machine ports (an
 * LPCS pump and an MSIV), a turbine hall with the main steam line and the crossaround, and dyed pipes. Every pipe is placed
 * the way a player's would be (its arms resolved against what is around it). Test harness only:
 * {@code :mod:runTurbineModelCheck -PbwrPipeShots=<label>} saves run/turbineModelCheck/pipe-shots/<label>/. The check fails
 * if a fixture pipe does not join what it was laid against.
 */
@EventBusSubscriber(modid = "bwr", value = Dist.CLIENT)
public final class PipeShots {
    /** The turbine train, as in MainTurbineShots: LP root here, HP 9 west, generator 8 east, shaft along x. */
    private static final BlockPos LP = new BlockPos(200, 120, 200);
    /** The pipe fixtures stand on a slab of smooth stone at y 119, x 236..284, z 190..238. */
    private static final int FY = 121, WATER_ROW = 204, STEAM_ROW = 213;
    private static final BlockPos PUMP = new BlockPos(270, 120, 230), MSIV = new BlockPos(250, 120, 231);
    /** The demonstration valves on the hall floor: one open, one shut, on a short oil line. */
    private static final BlockPos VALVE_OPEN = new BlockPos(212, 120, 210), VALVE_SHUT = new BlockPos(218, 120, 210);
    /** The lube oil room's tank, in the basement under the hall floor (the floor is y 119, the room y 113 to 118). */
    private static final BlockPos OIL_TANK = new BlockPos(186, 115, 200), OIL_FILTER = new BlockPos(184, 115, 200), HEADER_VALVE = new BlockPos(187, 117, 197);

    private record View(String name, Vec3 centre, Vec3 dir, double distance) {}

    private static final View[] VIEWS = {
            new View("side-by-side-straights", new Vec3(244.5, 121.5, 198.5), new Vec3(-.55, .42, 1), 6.5),
            new View("side-by-side-play-distance", new Vec3(244.5, 121.5, 198.5), new Vec3(-.8, .3, 1), 14),
            new View("water-fittings", new Vec3(250.5, 121.8, 205.5), new Vec3(-.25, .6, 1), 12),
            new View("steam-fittings", new Vec3(250.5, 121.8, 214.5), new Vec3(-.25, .6, 1), 12),
            new View("water-corners", new Vec3(242.5, 121.8, 205.0), new Vec3(-.6, .55, 1), 6),
            new View("steam-corners", new Vec3(242.5, 121.8, 214.0), new Vec3(-.6, .55, 1), 6),
            new View("water-tee", new Vec3(252.5, 121.5, 204.8), new Vec3(-.5, .65, 1), 4.2),
            new View("steam-tee", new Vec3(252.5, 121.5, 213.8), new Vec3(-.5, .65, 1), 4.2),
            new View("water-six-way", new Vec3(258.5, 122.5, 205.5), new Vec3(-.7, .45, 1), 4.6),
            new View("steam-six-way", new Vec3(258.5, 122.5, 214.5), new Vec3(-.7, .45, 1), 4.6),
            new View("pump-ports", new Vec3(268.5, 121.5, 231.5), new Vec3(-.9, .5, -1), 10),
            new View("pump-port-close", new Vec3(266.6, 120.6, 231.5), new Vec3(-1, .45, -.7), 3.6),
            new View("msiv-ports", new Vec3(250.5, 121.6, 231.5), new Vec3(.9, .4, -.6), 6.5),
            new View("turbine-hall", new Vec3(195.5, 123, 201.5), new Vec3(-.6, .55, 1), 26),
            new View("turbine-hall-steam-line", new Vec3(190.5, 124, 210.5), new Vec3(-1, .25, .25), 18),
            new View("dyed", new Vec3(250.5, 121.5, 221.5), new Vec3(-.2, .7, 1), 9),
            // The two pipe items in the hotbar and the water pipe in hand (the only view with the GUI shown).
            new View("items", new Vec3(244.5, 121.0, 198.5), new Vec3(-.3, .5, 1), 4.5),
            // Lube oil (TURBINE-AUX.md): the basement room under the hall floor, the risers on the train's two flanks, the valve and the filter.
            new View("oil-room", new Vec3(196.0, 115.4, 200.5), new Vec3(0, .1, 1), 15),
            new View("oil-room-tank-and-filter", new Vec3(186.5, 115.8, 200.0), new Vec3(.3, .1, 1), 7),
            new View("oil-filter-in-line", new Vec3(184.5, 115.5, 200.5), new Vec3(.2, .45, 1), 4.4),
            new View("oil-risers-south-flank", new Vec3(199.5, 121.0, 203.5), new Vec3(-.3, .45, 1), 17),
            new View("oil-risers-close", new Vec3(190.5, 120.4, 202.5), new Vec3(-.7, .5, 1), 6),
            new View("oil-valves-open-and-shut", new Vec3(215.0, 120.6, 210.5), new Vec3(0, .5, 1), 6.5),
            new View("oil-valve-open-close-up", new Vec3(212.5, 120.9, 210.5), new Vec3(.25, .75, .7), 2.6),
            new View("oil-valve-shut-close-up", new Vec3(218.5, 120.9, 210.5), new Vec3(.25, .75, .7), 2.6),
            new View("oil-water-steam-straights", new Vec3(244.5, 121.5, 200.0), new Vec3(-.55, .42, 1), 6.5),
    };
    private static boolean started, built, requested;
    private static int age, joined, stage, shown;

    private PipeShots() {}

    private static String label() { return System.getProperty("bwr.pipeShots", ""); }

    @SubscribeEvent
    public static void tick(ClientTickEvent.Post event) throws Exception {
        if (label().isEmpty()) return;
        var mc = Minecraft.getInstance();
        if (++age > 12000) throw new IllegalStateException("Pipe shots timed out at stage " + stage);
        if (!started && mc.screen != null && mc.getOverlay() == null) {
            started = true;
            mc.options.pauseOnLostFocus = false;
            mc.options.cloudStatus().set(CloudStatus.OFF);
            mc.options.fov().set(60);
            mc.options.renderDistance().set(8);
            mc.createWorldOpenFlows().createFreshLevel("bwr-pipe-shots-" + System.currentTimeMillis(),
                    new LevelSettings("Pipe shots", GameType.CREATIVE, false, Difficulty.PEACEFUL, true, new GameRules(), WorldDataConfiguration.DEFAULT),
                    new WorldOptions(0, false, false), r -> r.registryOrThrow(Registries.WORLD_PRESET).getOrThrow(WorldPresets.FLAT).createWorldDimensions(), mc.screen);
            return;
        }
        if (mc.player == null || mc.level == null || mc.getSingleplayerServer() == null || ++joined < 40) return;
        var view = VIEWS[stage];
        Vec3 camera = view.centre().add(view.dir().normalize().scale(view.distance()));
        var delta = view.centre().subtract(camera);
        float yaw = (float) (Math.toDegrees(Math.atan2(delta.z, delta.x)) - 90);
        float pitch = (float) -Math.toDegrees(Math.atan2(delta.y, Math.hypot(delta.x, delta.z)));
        if (!requested) {
            requested = true;
            shown = 0;
            boolean build = !built;
            int selected = stage;
            built = true;
            var server = mc.getSingleplayerServer();
            var uuid = mc.player.getUUID();
            server.execute(() -> {
                var player = server.getPlayerList().getPlayer(uuid);
                var l = player.serverLevel();
                player.setNoGravity(true);
                player.getAbilities().flying = true;
                player.onUpdateAbilities();
                if (build) build(l);
                if (VIEWS[selected].name().equals("items")) {
                    player.getInventory().setItem(0, new ItemStack(water()));
                    player.getInventory().setItem(1, new ItemStack(steam()));
                    player.getInventory().selected = 0;
                }
                l.setDayTime(6000);
                l.setWeatherParameters(6000, 0, false, false);
                l.getGameRules().getRule(GameRules.RULE_DAYLIGHT).set(false, server);
                player.connection.teleport(camera.x, camera.y - player.getEyeHeight(), camera.z, yaw, pitch);
            });
        }
        mc.player.getAbilities().flying = true;
        mc.player.setPos(camera.x, camera.y - mc.player.getEyeHeight(), camera.z);
        mc.player.setYRot(yaw);
        mc.player.setXRot(pitch);
        mc.player.yRotO = yaw;
        mc.player.xRotO = pitch;
        mc.player.setDeltaMovement(Vec3.ZERO);
        mc.options.hideGui = !view.name().equals("items");
        if (!mc.options.hideGui) mc.player.getInventory().selected = 0;
        mc.getToasts().clear();
        if (stage == 0 && shown == 80) {
            // Newly built sections far from spawn can reach the client before their sky light does (SteamValveShots,
            // HxMultiblockClientCheck): send every fixture chunk again, with its light, once the light engine is done.
            var server = mc.getSingleplayerServer();
            var uuid = mc.player.getUUID();
            server.execute(() -> {
                var player = server.getPlayerList().getPlayer(uuid);
                var l = player.serverLevel();
                for (int x = 10; x <= 17; x++)
                    for (int z = 10; z <= 14; z++)
                        player.connection.send(new net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket(
                                l.getChunk(x, z), l.getChunkSource().getLightEngine(), null, null));
            });
        }
        if (stage == 0 && shown == 120) {
            // The shut valve is opened on the server; the client must see it open (the limit switch box shows it, verify below).
            var server = mc.getSingleplayerServer();
            server.execute(() -> server.overworld().setBlock(VALVE_SHUT, server.overworld().getBlockState(VALVE_SHUT).setValue(LubeOilValveBlock.OPEN, true), 3));
        }
        if (++shown < (stage == 0 ? 300 : 110)) return;
        if (stage == 0) {
            verify(mc);
            // Shut again for the pictures.
            var server = mc.getSingleplayerServer();
            server.execute(() -> server.overworld().setBlock(VALVE_SHUT, server.overworld().getBlockState(VALVE_SHUT).setValue(LubeOilValveBlock.OPEN, false), 3));
        }
        if (view.name().equals("oil-valve-shut-close-up") && mc.level.getBlockState(VALVE_SHUT).getValue(LubeOilValveBlock.OPEN))
            throw new IllegalStateException("The shut valve was shut again on the server but the client still draws it open");
        var dir = mc.gameDirectory.toPath().resolve("pipe-shots").resolve(label());
        java.nio.file.Files.createDirectories(dir);
        var file = dir.resolve(view.name() + ".png");
        CheckShots.save(mc, file);
        LogUtils.getLogger().info("PIPE SHOT: {}", file.toAbsolutePath());
        requested = false;
        if (++stage == VIEWS.length) {
            LogUtils.getLogger().info("PIPE SHOTS PASS: {} pictures, every fixture pipe joined to what it was laid against, the oil valve's indicator reached the client", stage);
            mc.options.hideGui = false;
            mc.stop();
        }
    }

    /** Checks on the placed fixtures, run once before the first picture. */
    private static void verify(Minecraft mc) {
        var c = mc.level.getBlockState(new BlockPos(258, FY + 1, WATER_ROW + 1));
        for (var d : Direction.values())
            if (!c.getValue(PipeBlock.PROPERTY_BY_DIRECTION.get(d))) throw new IllegalStateException("Six-way junction is missing its " + d + " arm: " + c);
        // The client draws a flange where a pipe meets a machine and none where it meets another pipe (PipeJointModels).
        var pump = BwrBlocks.LPCS_PUMP.get();
        var pumpState = mc.level.getBlockState(PUMP);
        for (var port : pump.ports(pumpState)) {
            var face = pump.portFace(pumpState, port.role());
            var first = pump.portPosition(PUMP, pumpState, port.role()).relative(face);
            int mask = dev.bwr.mod.piping.client.PipeJointModels.ports(mc.level, first, mc.level.getBlockState(first));
            if (mask != 1 << face.getOpposite().ordinal())
                throw new IllegalStateException("Pipe at the pump's " + port.role() + " port should have one flange, towards the pump: " + mask);
            var second = first.relative(face);
            int none = dev.bwr.mod.piping.client.PipeJointModels.ports(mc.level, second, mc.level.getBlockState(second));
            if (none != 0) throw new IllegalStateException("Pipe-to-pipe joint drew a machine flange at " + second + ": " + none);
        }
        // The steam pipe's outline follows its 12 px cladding; its collision stays the 8 px pipe (PIPES.md).
        var steamAt = new BlockPos(244, FY, 199);
        var steamState = mc.level.getBlockState(steamAt);
        var outline = steamState.getShape(mc.level, steamAt).bounds();
        var collision = steamState.getCollisionShape(mc.level, steamAt).bounds();
        if (Math.abs(outline.minY - 2 / 16.0) > 1e-6 || Math.abs(collision.minY - 4 / 16.0) > 1e-6 || outline.minX != 0 || collision.minX != 0)
            throw new IllegalStateException("Steam pipe shapes: outline " + outline + ", collision " + collision);
        var model = mc.getBlockRenderer().getBlockModel(steamState);
        if (!(model instanceof dev.bwr.mod.piping.client.PipeJointModels.Joints))
            throw new IllegalStateException("Steam pipe model is not wrapped for port flanges: " + model);
        verifyOil(mc);
    }

    /** The lube oil fixtures: the flange at a turbine port, none between pipes, brown paint, and the valve's two states on the client. */
    private static void verifyOil(Minecraft mc) {
        var hpSupply = new BlockPos(191, 120, 197);
        var state = mc.level.getBlockState(hpSupply);
        if (!state.is(BwrBlocks.LUBE_OIL_PIPE.get())) throw new IllegalStateException("No oil pipe at the HP section's supply port: " + state);
        int mask = dev.bwr.mod.piping.client.PipeJointModels.ports(mc.level, hpSupply, state);
        if (mask != 1 << Direction.SOUTH.ordinal())
            throw new IllegalStateException("The oil pipe at the HP supply port should have one flange, towards the turbine: " + mask);
        var below = hpSupply.below();
        if (dev.bwr.mod.piping.client.PipeJointModels.ports(mc.level, below, mc.level.getBlockState(below)) != 0)
            throw new IllegalStateException("Oil pipe to oil pipe drew a machine flange");
        if (!(mc.getBlockRenderer().getBlockModel(state) instanceof dev.bwr.mod.piping.client.PipeJointModels.Joints))
            throw new IllegalStateException("The oil pipe model is not wrapped for port flanges");
        int colour = mc.getBlockColors().getColor(state, mc.level, hpSupply, 0) & 0xFFFFFF;
        if (colour != 0x6F4A2E) throw new IllegalStateException("The oil pipe's paint is not lube oil brown: " + Integer.toHexString(colour));
        int steelTint = mc.getBlockColors().getColor(state, mc.level, hpSupply, -1);
        if (steelTint != -1) throw new IllegalStateException("The oil pipe's flanges take the paint colour: " + Integer.toHexString(steelTint));
        // The indicator state reached the client: the first valve is open, the second was opened by the server during the check.
        var open = mc.level.getBlockState(VALVE_OPEN);
        var wasShut = mc.level.getBlockState(VALVE_SHUT);
        if (!open.is(BwrBlocks.LUBE_OIL_VALVE.get()) || !open.getValue(LubeOilValveBlock.OPEN))
            throw new IllegalStateException("The open valve is not open on the client: " + open);
        if (!wasShut.getValue(LubeOilValveBlock.OPEN))
            throw new IllegalStateException("The server opened the second valve but the client still draws it shut: " + wasShut);
        var randomSource = net.minecraft.util.RandomSource.create(0);
        var openQuads = mc.getBlockRenderer().getBlockModel(open).getQuads(open, null, randomSource, net.neoforged.neoforge.client.model.data.ModelData.EMPTY, null);
        var shutState = open.setValue(LubeOilValveBlock.OPEN, false);
        var shutQuads = mc.getBlockRenderer().getBlockModel(shutState).getQuads(shutState, null, randomSource, net.neoforged.neoforge.client.model.data.ModelData.EMPTY, null);
        if (openQuads.isEmpty() || shutQuads.isEmpty()) throw new IllegalStateException("A valve state has no model");
        if (java.util.Objects.hash(quadHash(openQuads)) == java.util.Objects.hash(quadHash(shutQuads)) && quadSprites(openQuads).equals(quadSprites(shutQuads)))
            throw new IllegalStateException("The open and shut valve models are the same: the indicator would not change");
    }

    private static int quadHash(java.util.List<net.minecraft.client.renderer.block.model.BakedQuad> quads) {
        int h = quads.size();
        for (var q : quads) h = 31 * h + java.util.Arrays.hashCode(q.getVertices());
        return h;
    }

    private static java.util.Set<String> quadSprites(java.util.List<net.minecraft.client.renderer.block.model.BakedQuad> quads) {
        var out = new java.util.TreeSet<String>();
        for (var q : quads) out.add(q.getSprite().contents().name().toString() + "/" + q.getTintIndex());
        return out;
    }

    private static Block water() { return BwrBlocks.HIGH_PRESSURE_WATER_PIPE.get(); }

    private static Block steam() { return BwrBlocks.PRESSURISED_TUBE.get(); }

    private static void build(ServerLevel l) {
        for (int x = 10; x <= 17; x++) for (int z = 10; z <= 14; z++) l.setChunkForced(x, z, true);
        // The turbine hall: a floor, a back wall and the train with its steam lines.
        for (var p : BlockPos.betweenClosed(LP.offset(-30, -1, -16), LP.offset(22, -1, 22)))
            l.setBlock(p, ((p.getX() >> 2) + (p.getZ() >> 2)) % 2 == 0 ? Blocks.POLISHED_ANDESITE.defaultBlockState() : Blocks.SMOOTH_STONE.defaultBlockState(), 2);
        for (var p : BlockPos.betweenClosed(LP.offset(-30, 0, -16), LP.offset(22, 13, -16)))
            l.setBlock(p, p.getX() % 8 == 0 ? Blocks.GRAY_CONCRETE.defaultBlockState() : Blocks.LIGHT_GRAY_CONCRETE.defaultBlockState(), 2);
        var hp = PowerModuleRuntimeCheck.place(l, BwrBlocks.HP_TURBINE.get(), LP.west(9), Direction.EAST);
        var lp = PowerModuleRuntimeCheck.place(l, BwrBlocks.LP_TURBINE.get(), LP, Direction.EAST);
        PowerModuleRuntimeCheck.place(l, BwrBlocks.NUCLEAR_GENERATOR.get(), LP.east(8), Direction.EAST);
        turbineLines(l, hp, lp);

        // The fixture slab.
        for (var p : BlockPos.betweenClosed(236, 119, 190, 284, 119, 238))
            l.setBlock(p, ((p.getX() >> 2) + (p.getZ() >> 2)) % 2 == 0 ? Blocks.POLISHED_ANDESITE.defaultBlockState() : Blocks.SMOOTH_STONE.defaultBlockState(), 2);
        // Straight runs side by side.
        line(l, water(), new BlockPos(240, FY, 196), new BlockPos(249, FY, 196));
        line(l, steam(), new BlockPos(240, FY, 199), new BlockPos(249, FY, 199));
        fittings(l, water(), WATER_ROW);
        fittings(l, steam(), STEAM_ROW);
        // Dyed: short runs of each pipe in eight dyes.
        DyeColor[] dyes = {DyeColor.RED, DyeColor.ORANGE, DyeColor.YELLOW, DyeColor.LIME, DyeColor.GREEN, DyeColor.CYAN, DyeColor.PURPLE, DyeColor.WHITE};
        for (int i = 0; i < dyes.length; i++) {
            int x = 241 + i * 2;
            line(l, water(), new BlockPos(x, FY, 219), new BlockPos(x, FY, 221));
            line(l, steam(), new BlockPos(x, FY, 223), new BlockPos(x, FY, 225));
            for (int z : new int[]{219, 220, 221, 223, 224, 225}) {
                var p = new BlockPos(x, FY, z);
                l.setBlock(p, l.getBlockState(p).setValue(PaintedPipeBlock.PAINT, PipePaint.of(dyes[i])), Block.UPDATE_CLIENTS);
            }
        }
        // Lube oil beside the water and steam runs, for the colour and the size of the pipe.
        line(l, oil(), new BlockPos(240, FY, 202), new BlockPos(249, FY, 202));
        oilFixtures(l);
        // An LPCS pump with its suction and discharge piped.
        var pump = BwrBlocks.LPCS_PUMP.get();
        var state = pump.placementState();
        l.setBlock(PUMP, state, 3);
        pump.setPlacedBy(l, PUMP, state, null, new ItemStack(pump));
        for (var port : pump.ports(state)) portLine(l, pump, PUMP, port.role(), 4);
        // An MSIV with its steam line piped on both sides.
        var msiv = BwrBlocks.MSIV.get();
        var valve = msiv.placementState().setValue(MainSteamIsolationValveBlock.FACING, Direction.NORTH);
        l.setBlock(MSIV, valve, 3);
        msiv.setPlacedBy(l, MSIV, valve, null, new ItemStack(msiv));
        for (var face : new Direction[]{Direction.NORTH, Direction.SOUTH}) {
            line(l, steam(), MSIV.relative(face), MSIV.relative(face, 4));
            requireJoined(l, MSIV.relative(face), face.getOpposite());
        }
    }

    /** The lube oil room under the hall floor, its piping up through the floor to the train, and the two demonstration valves. */
    private static void oilFixtures(ServerLevel l) {
        var concrete = Blocks.LIGHT_GRAY_CONCRETE.defaultBlockState();
        var dark = Blocks.GRAY_CONCRETE.defaultBlockState();
        // The room: floor at y 112, back and side walls, open to the south, the hall floor for a ceiling. A cutaway, lit by invisible light blocks.
        for (var p : BlockPos.betweenClosed(178, 112, 190, 216, 112, 240)) l.setBlock(p, dark, 2);
        for (var p : BlockPos.betweenClosed(178, 113, 190, 216, 118, 190)) l.setBlock(p, concrete, 2);
        for (var p : BlockPos.betweenClosed(178, 113, 190, 178, 118, 212)) l.setBlock(p, concrete, 2);
        for (var p : BlockPos.betweenClosed(216, 113, 190, 216, 118, 212)) l.setBlock(p, concrete, 2);
        for (int x = 180; x <= 214; x += 3)
            for (int z = 192; z <= 214; z += 3)
                for (int y : new int[] {114, 118})
                    l.setBlock(new BlockPos(x, y, z), Blocks.LIGHT.defaultBlockState(), 2);
        // The holes in the hall floor where the risers go through.
        int[][] holes = {{191, 197}, {200, 196}, {208, 197}, {191, 203}, {200, 204}, {208, 203}};
        for (var h : holes) l.setBlock(new BlockPos(h[0], 119, h[1]), Blocks.AIR.defaultBlockState(), 2);
        // The tank: discharge on its west face (front), return on its east face (back); the filter housing in-line on the discharge.
        l.setBlock(OIL_TANK, BwrBlocks.MAIN_OIL_TANK.get().defaultBlockState().setValue(TurbineAuxBlock.FACING, Direction.WEST), 3);
        l.setBlock(OIL_FILTER, BwrBlocks.OIL_FILTER_HOUSING.get().defaultBlockState().setValue(TurbineAuxBlock.FACING, Direction.WEST), 3);
        // Supply: the tank's discharge through the housing and up, then along the header under the train; risers to each section's north flank.
        line(l, oil(), new BlockPos(185, 115, 200), new BlockPos(185, 115, 200));
        path(l, oil(), new BlockPos(183, 115, 200), new BlockPos(183, 117, 200), new BlockPos(183, 117, 197), new BlockPos(208, 117, 197));
        path(l, oil(), new BlockPos(191, 120, 197), new BlockPos(191, 117, 197));
        path(l, oil(), new BlockPos(208, 120, 197), new BlockPos(208, 117, 197));
        path(l, oil(), new BlockPos(200, 120, 196), new BlockPos(200, 117, 196), new BlockPos(200, 117, 197));
        // Drain: each section's south flank down to the header, back along it to the tank's return.
        path(l, oil(), new BlockPos(187, 115, 200), new BlockPos(187, 115, 203), new BlockPos(208, 115, 203));
        path(l, oil(), new BlockPos(191, 120, 203), new BlockPos(191, 115, 203));
        path(l, oil(), new BlockPos(208, 120, 203), new BlockPos(208, 115, 203));
        path(l, oil(), new BlockPos(200, 120, 204), new BlockPos(200, 115, 204), new BlockPos(200, 115, 203));
        // A valve in the supply header, open.
        l.setBlock(HEADER_VALVE, BwrBlocks.LUBE_OIL_VALVE.get().defaultBlockState().setValue(LubeOilValveBlock.FACING, Direction.EAST), 3);
        for (var p : new BlockPos[] {HEADER_VALVE.west(), HEADER_VALVE.east()}) l.setBlock(p, state(oil(), l, p), 3);
        // The pipes that were laid before their neighbours were there get their arms resolved again.
        for (var p : BlockPos.betweenClosed(180, 113, 192, 214, 121, 206))
            if (l.getBlockState(p).is(oil())) l.setBlock(p, state(oil(), l, p), 3);
        // The demonstration valves on the hall floor: open and shut, with short runs of pipe either side.
        for (int x = 209; x <= 221; x++) {
            var p = new BlockPos(x, 120, 210);
            if (x != VALVE_OPEN.getX() && x != VALVE_SHUT.getX() && !(x >= 214 && x <= 216)) l.setBlock(p, oil().defaultBlockState(), 3);
        }
        l.setBlock(VALVE_OPEN, BwrBlocks.LUBE_OIL_VALVE.get().defaultBlockState().setValue(LubeOilValveBlock.FACING, Direction.EAST).setValue(LubeOilValveBlock.OPEN, true), 3);
        l.setBlock(VALVE_SHUT, BwrBlocks.LUBE_OIL_VALVE.get().defaultBlockState().setValue(LubeOilValveBlock.FACING, Direction.EAST).setValue(LubeOilValveBlock.OPEN, false), 3);
        for (int x = 213; x <= 217; x++) l.setBlock(new BlockPos(x, 120, 210), oil().defaultBlockState(), 3);
        for (var p : BlockPos.betweenClosed(209, 120, 210, 221, 120, 210))
            if (l.getBlockState(p).is(oil())) l.setBlock(p, state(oil(), l, p), 3);
        // Every port pipe must join its machine.
        requireJoined(l, new BlockPos(191, 120, 197), Direction.SOUTH);
        requireJoined(l, new BlockPos(200, 120, 196), Direction.SOUTH);
        requireJoined(l, new BlockPos(208, 120, 197), Direction.SOUTH);
        requireJoined(l, new BlockPos(191, 120, 203), Direction.NORTH);
        requireJoined(l, new BlockPos(200, 120, 204), Direction.NORTH);
        requireJoined(l, new BlockPos(208, 120, 203), Direction.NORTH);
        requireJoined(l, new BlockPos(185, 115, 200), Direction.EAST);
        requireJoined(l, new BlockPos(185, 115, 200), Direction.WEST);
        requireJoined(l, new BlockPos(183, 115, 200), Direction.EAST);
        requireJoined(l, new BlockPos(187, 115, 200), Direction.WEST);
        requireJoined(l, HEADER_VALVE.west(), Direction.EAST);
        requireJoined(l, HEADER_VALVE.east(), Direction.WEST);
        requireJoined(l, new BlockPos(211, 120, 210), Direction.EAST);
        requireJoined(l, new BlockPos(213, 120, 210), Direction.WEST);
    }

    /** Corner (flat and rising), tee and six-way junction of one pipe type, along a row at z. */
    private static void fittings(ServerLevel l, Block pipe, int z) {
        path(l, pipe, new BlockPos(238, FY, z), new BlockPos(241, FY, z), new BlockPos(241, FY, z + 3));
        path(l, pipe, new BlockPos(243, FY, z), new BlockPos(246, FY, z), new BlockPos(246, FY + 3, z));
        line(l, pipe, new BlockPos(250, FY, z), new BlockPos(255, FY, z));
        line(l, pipe, new BlockPos(252, FY, z + 1), new BlockPos(252, FY, z + 3));
        var c = new BlockPos(258, FY + 1, z + 1);
        for (var d : Direction.values()) line(l, pipe, c.relative(d), c.relative(d, d == Direction.DOWN ? 1 : 2));
        line(l, pipe, c, c);
    }

    private static BlockState state(Block pipe, ServerLevel l, BlockPos p) {
        return pipe instanceof dev.bwr.mod.water.HighPressureWaterPipeBlock w ? w.stateWithConnections(l, p)
                : pipe instanceof LubeOilPipeBlock o ? o.stateWithConnections(l, p)
                : ((dev.bwr.mod.steam.PressurisedTubeBlock) pipe).stateWithConnections(l, p);
    }

    private static Block oil() { return BwrBlocks.LUBE_OIL_PIPE.get(); }

    /** One axis-aligned run of pipe, laid block by block the way a player places it. */
    private static void line(ServerLevel l, Block pipe, BlockPos a, BlockPos b) {
        if ((a.getX() != b.getX() ? 1 : 0) + (a.getY() != b.getY() ? 1 : 0) + (a.getZ() != b.getZ() ? 1 : 0) > 1)
            throw new IllegalArgumentException("Not an axis run: " + a + " " + b);
        int n = a.distManhattan(b);
        for (int i = 0; i <= n; i++) {
            var p = n == 0 ? a : new BlockPos(a.getX() + (b.getX() - a.getX()) * i / n, a.getY() + (b.getY() - a.getY()) * i / n, a.getZ() + (b.getZ() - a.getZ()) * i / n);
            var here = l.getBlockState(p);
            if (here.is(pipe)) continue;
            if (!here.isAir()) throw new IllegalStateException("Pipe route blocked at " + p + " by " + here);
            l.setBlock(p, state(pipe, l, p), 3);
        }
    }

    private static void path(ServerLevel l, Block pipe, BlockPos... points) {
        for (int i = 1; i < points.length; i++) line(l, pipe, points[i - 1], points[i]);
    }

    /** A straight run of {@code length} out of a machine port; the first pipe must join the port. */
    private static BlockPos portLine(ServerLevel l, PumpAssemblyBlock block, BlockPos root, AssemblyPort role, int length) {
        var s = l.getBlockState(root);
        var port = block.portPosition(root, s, role);
        var face = block.portFace(s, role);
        line(l, role.isSteam() ? steam() : water(), port.relative(face), port.relative(face, length));
        requireJoined(l, port.relative(face), face.getOpposite());
        return port.relative(face, length);
    }

    private static void requireJoined(ServerLevel l, BlockPos pipe, Direction towardsMachine) {
        var state = l.getBlockState(pipe);
        var property = PipeBlock.PROPERTY_BY_DIRECTION.get(towardsMachine);
        if (!state.hasProperty(property) || !state.getValue(property))
            throw new IllegalStateException("Pipe at " + pipe + " does not join the machine on its " + towardsMachine + " face: " + state);
    }

    /** The HP's main steam line from the south and its crossaround over to the LP's top inlet. */
    private static void turbineLines(ServerLevel l, PowerModuleBlockEntity hp, PowerModuleBlockEntity lp) {
        var hpBlock = (PowerModuleBlock) hp.getBlockState().getBlock();
        var lpBlock = (PowerModuleBlock) lp.getBlockState().getBlock();
        var hpState = hp.getBlockState();
        var lpState = lp.getBlockState();
        // Main steam: up from the HP's top inlet, south past the casing, down to a rack at FY + 2 and away west along the hall.
        var inlet = hpBlock.portPosition(hp.getBlockPos(), hpState, AssemblyPort.STEAM_INLET).relative(hpBlock.portFace(hpState, AssemblyPort.STEAM_INLET));
        var top = inlet.above(1);
        var south = new BlockPos(top.getX(), top.getY(), LP.getZ() + 9);
        var rack = new BlockPos(south.getX(), LP.getY() + 3, south.getZ());
        var away = new BlockPos(LP.getX() - 28, rack.getY(), rack.getZ());
        path(l, steam(), inlet, top, south, rack, away);
        requireJoined(l, inlet, hpBlock.portFace(hpState, AssemblyPort.STEAM_INLET).getOpposite());
        // A second, parallel main steam line beside it, ending in the hall (two of the four lines a plant has).
        path(l, steam(), new BlockPos(rack.getX() - 2, rack.getY(), rack.getZ() + 3), new BlockPos(away.getX(), rack.getY(), rack.getZ() + 3));
        // Pipe supports under both lines every four blocks.
        for (int x = away.getX(); x <= rack.getX(); x += 4)
            for (int dz : new int[]{0, 3})
                for (int y = LP.getY(); y < rack.getY(); y++) {
                    var p = new BlockPos(x, y, rack.getZ() + dz);
                    if (l.getBlockState(p).isAir()) l.setBlock(p, Blocks.POLISHED_DEEPSLATE_WALL.defaultBlockState(), 2);
                }
        // Crossaround: out of the HP's side exhaust, up over the casings, east and down into the LP's top inlet.
        var exFace = hpBlock.portFace(hpState, AssemblyPort.STEAM_EXHAUST);
        var exhaust = hpBlock.portPosition(hp.getBlockPos(), hpState, AssemblyPort.STEAM_EXHAUST).relative(exFace);
        var lpFace = lpBlock.portFace(lpState, AssemblyPort.STEAM_INLET);
        var lpIn = lpBlock.portPosition(lp.getBlockPos(), lpState, AssemblyPort.STEAM_INLET).relative(lpFace);
        var out = exhaust.relative(exFace, 1);
        int high = Math.max(lpIn.getY(), out.getY()) + 2;
        path(l, steam(), exhaust, out, new BlockPos(out.getX(), high, out.getZ()), new BlockPos(lpIn.getX(), high, out.getZ()),
                new BlockPos(lpIn.getX(), high, lpIn.getZ()), lpIn);
        requireJoined(l, exhaust, exFace.getOpposite());
        requireJoined(l, lpIn, lpFace.getOpposite());
    }
}
