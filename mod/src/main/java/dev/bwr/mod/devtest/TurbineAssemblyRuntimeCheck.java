package dev.bwr.mod.devtest;

import com.mojang.authlib.GameProfile;
import com.mojang.logging.LogUtils;
import dev.bwr.mod.eccs.AssemblyPort;
import dev.bwr.mod.eccs.EccsPumpBlockEntity;
import dev.bwr.mod.eccs.SuctionSource;
import dev.bwr.mod.eccs.TurbineAssemblyBlock;
import dev.bwr.mod.registry.BwrBlockEntities;
import dev.bwr.mod.registry.BwrBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.DirectionalPlaceContext;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.PipeBlock;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static dev.bwr.mod.eccs.TurbineAssemblyBlock.CELL;
import static dev.bwr.mod.eccs.TurbineAssemblyBlock.FACING;

@GameTestHolder("bwr")
@PrefixGameTestTemplate(false)
public final class TurbineAssemblyRuntimeCheck {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Direction[] FACINGS = {
            Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST
    };

    private TurbineAssemblyRuntimeCheck() {}

    @GameTest(template = "empty", timeoutTicks = 200)
    public static void assemblies(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        List<ChunkPos> chunks = new ArrayList<>();
        List<ChunkPos> owned = new ArrayList<>();
        try {
            // Covers all current fixtures, including rotated cells and cleanup bounds.
            for (int x = 7; x <= 12; x++) {
                for (int z = 7; z <= 9; z++) {
                    ChunkPos chunk = new ChunkPos(x, z);
                    chunks.add(chunk);
                    if (!level.getForcedChunks().contains(chunk.toLong())) {
                        owned.add(chunk);
                        level.setChunkForced(x, z, true);
                    }
                    level.getChunk(x, z);
                }
            }
        } catch (RuntimeException | Error e) {
            owned.forEach(c -> level.setChunkForced(c.x, c.z, false));
            throw e;
        }
        awaitFixtures(helper, chunks, owned);
    }

    private static void awaitFixtures(GameTestHelper helper,
                                      List<ChunkPos> chunks,
                                      List<ChunkPos> owned) {
        ServerLevel level = helper.getLevel();
        boolean ready = chunks.stream().allMatch(c ->
                level.areEntitiesLoaded(c.toLong())
                        && level.isPositionEntityTicking(
                                new BlockPos((c.x << 4) + 8, 200, (c.z << 4) + 8)));

        if (!ready && helper.getTick() < 180) {
            helper.runAfterDelay(1, () -> awaitFixtures(helper, chunks, owned));
            return;
        }

        try {
            helper.assertTrue(ready, "Fixture entity chunks never became ready");
            int failures = run(level) + TurbinePlumbingRuntimeCheck.run(level) + PumpAssemblyRuntimeCheck.run(level) + PowerModuleRuntimeCheck.run(level) + SteamValveRuntimeCheck.run(level) + MsivRuntimeCheck.run(level) + CondenserRuntimeCheck.run(level) + CoolingRuntimeCheck.run(level) + CondensateTankRuntimeCheck.run(level);
            helper.assertTrue(failures == 0,
                    "Turbine assembly checks: " + failures + " failure(s); see server log");
            helper.succeed();
        } finally {
            owned.forEach(c -> level.setChunkForced(c.x, c.z, false));
        }
    }

    public static int run(ServerLevel level) {
        int failures = 0;
        TurbineAssemblyBlock[] blocks = {
                BwrBlocks.RCIC_TWL.get(), BwrBlocks.HPCI_TURBINE.get()
        };
        for (boolean modern : new boolean[]{false,true}) for (int kind = 0; kind < blocks.length; kind++) {
            for (int rotation = 0; rotation < FACINGS.length; rotation++) {
                Fixture f = new Fixture(level, blocks[kind], kind == 1, modern,
                        FACINGS[rotation], new BlockPos(128 + rotation * 24, 200, 128 + kind * 24));
                List<ChunkPos> forced = new ArrayList<>();
                try {
                    for (int x = (f.root.getX() - 5) >> 4;
                         x <= (f.root.getX() + 5) >> 4; x++) {
                        for (int z = (f.root.getZ() - 5) >> 4;
                             z <= (f.root.getZ() + 5) >> 4; z++) {
                            if (!level.getForcedChunks().contains(ChunkPos.asLong(x, z))) {
                                forced.add(new ChunkPos(x, z));
                                level.setChunkForced(x, z, true);
                            }
                            level.getChunk(x, z);
                        }
                    }
                    failures += f.test("geometry", f::geometry);
                    failures += f.test("obstruction", f::obstruction);
                    failures += f.test("physical ports", f::ports);
                    failures += f.test("NBT persistence", f::persistence);
                    if(modern)failures += f.test("Terry panel, CC and water capabilities",f::interfaces);
                    for (int cell : new int[]{0, f.count() - 1}) {
                        failures += f.test("survival pickaxe cell " + cell,
                                () -> f.harvest(cell, false, true));
                        failures += f.test("survival empty hand cell " + cell,
                                () -> f.harvest(cell, false, false));
                        failures += f.test("creative cell " + cell,
                                () -> f.harvest(cell, true, true));
                    }
                    failures += f.test("destroy root with drops", () -> {
                        f.place();
                        check(level.destroyBlock(f.root, true), "root destruction rejected");
                        f.gone();
                        f.drops(1);
                    });
                    failures += f.test("remove child without drops", () -> {
                        f.place();
                        check(level.removeBlock(f.pos(f.count() - 1), false),
                                "child removal rejected");
                        f.gone();
                        f.drops(0);
                    });
                } catch (RuntimeException | AssertionError e) {
                    failures++;
                    LOGGER.error("Turbine fixture failed: {} {}", blocks[kind].design().id(),
                            FACINGS[rotation], e);
                } finally {
                    for (ChunkPos chunk : forced) {
                        level.setChunkForced(chunk.x, chunk.z, false);
                    }
                }
            }
        }
        LOGGER.info("Turbine assembly runtime checks: {} failure(s)", failures);
        return failures;
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private record Port(AssemblyPort role, BlockPos offset, Direction face) {}

    private record Fixture(ServerLevel level, TurbineAssemblyBlock block, boolean hpci, boolean modern,
                           Direction facing, BlockPos root) {
        int width() { return modern ? hpci?7:5 : hpci?4:3; }
        int height() { return modern ? hpci?5:4 : hpci?5:3; }
        int depth() { return modern ? 5 : hpci?3:2; }
        int count() { return width() * height() * depth(); }

        // Independent coordinate oracle, deliberately not TurbineAssemblyBlock.turn().
        BlockPos rotated(BlockPos p) {
            int x = p.getX(), y = p.getY(), z = p.getZ();
            return switch (facing) {
                case NORTH -> new BlockPos(x, y, z);
                case EAST -> new BlockPos(-z, y, x);
                case SOUTH -> new BlockPos(-x, y, -z);
                case WEST -> new BlockPos(z, y, -x);
                default -> throw new AssertionError("Non-horizontal facing");
            };
        }

        Rotation rotation() {
            return switch (facing) {
                case NORTH -> Rotation.NONE;
                case EAST -> Rotation.CLOCKWISE_90;
                case SOUTH -> Rotation.CLOCKWISE_180;
                case WEST -> Rotation.COUNTERCLOCKWISE_90;
                default -> throw new AssertionError("Non-horizontal facing");
            };
        }

        BlockPos offset(int cell) {
            return new BlockPos(cell % width(), cell / (width() * depth()),
                    (cell / width()) % depth());
        }

        BlockPos pos(int cell) { return root.offset(rotated(offset(cell))); }

        BlockState candidate() {
            if(!modern) {
                // Saved legacy skids are not newly placeable; emulate their persisted root.
                for(int i=0;i<count();i++)if(!level.getBlockState(pos(i)).canBeReplaced())return null;
                return block.defaultBlockState().setValue(FACING,facing);
            }
            return block.getStateForPlacement(new DirectionalPlaceContext(
                    level, root, facing.getOpposite(), new ItemStack(block), Direction.UP));
        }

        BlockState place() {
            BlockState state = candidate();
            check(state != null, "empty footprint rejected");
            check(state.getValue(FACING) == facing && state.getValue(CELL) == 0,
                    "placement orientation/root cell");
            check(level.setBlock(root, state, 3), "root placement rejected");
            block.setPlacedBy(level, root, state, null, new ItemStack(block));
            check(block.complete(level, root, state), "setPlacedBy left incomplete assembly");
            return state;
        }

        int test(String name, Runnable body) {
            try {
                clear();
                body.run();
                LOGGER.info("Turbine PASS: {} {} / {}", block.design().id(), facing, name);
                return 0;
            } catch (RuntimeException | AssertionError e) {
                LOGGER.error("Turbine FAIL: {} {} / {}", block.design().id(), facing, name, e);
                return 1;
            } finally {
                clear();
            }
        }

        void clear() {
            for (BlockPos p : BlockPos.betweenClosed(root.offset(-7, -1, -7),
                    root.offset(7, 6, 7))) {
                if (!level.getBlockState(p).isAir()) level.removeBlock(p, false);
            }
            for (ItemEntity item : items()) item.discard();
        }

        List<ItemEntity> items() {
            return level.getEntitiesOfClass(ItemEntity.class, new AABB(root).inflate(7));
        }

        void drops(int expected) {
            List<ItemEntity> items = items();
            check(items.stream().allMatch(e -> e.getItem().is(block.asItem())),
                    "unexpected dropped item");
            int actual = items.stream().mapToInt(e -> e.getItem().getCount()).sum();
            check(actual == expected, "expected " + expected + " assembly item(s), got " + actual);
        }

        void gone() {
            for (int i = 0; i < count(); i++) {
                check(!level.getBlockState(pos(i)).is(block), "orphan cell " + i);
                check(level.getBlockEntity(pos(i)) == null, "orphan block entity at cell " + i);
            }
        }

        void verify(BlockState rootState) {
            check(block.complete(level, root, rootState), "assembly incomplete");
            for (int i = 0; i < count(); i++) {
                BlockPos p = pos(i);
                BlockState actual = level.getBlockState(p);
                check(actual.is(block) && actual.getValue(CELL) == i
                        && actual.getValue(FACING) == facing, "incorrect cell " + i + " at " + p);
                check(block.cellOffset(rootState,i).equals(offset(i)), "cellOffset " + i);
                check(actual.getValue(TurbineAssemblyBlock.MODERN)==modern,"saved model version");
                check(block.origin(p, actual).equals(root), "origin from cell " + i);
                BlockEntity entity = level.getBlockEntity(p);
                check(i == 0 ? entity instanceof EccsPumpBlockEntity : entity == null,
                        "block entity ownership at cell " + i);
                check((block.getTicker(level, actual, BwrBlockEntities.ECCS_PUMP.get()) != null)
                        == (i == 0), "ticker ownership at cell " + i);
                BlockState turned = actual.rotate(Rotation.CLOCKWISE_90);
                check(turned.getValue(CELL) == i
                        && turned.getValue(FACING) == facing.getClockWise(),
                        "state rotation at cell " + i);
            }
        }

        void geometry() {
            check(block.isHpci() == hpci && block.cellCount(block.defaultBlockState().setValue(TurbineAssemblyBlock.MODERN,modern)) == count(), "assembly dimensions");
            BlockState state = place();
            verify(state);
            BlockPos tail = pos(count() - 1);
            BlockState saved = level.getBlockState(tail);
            level.setBlock(tail, saved.setValue(CELL, 1), 3);
            check(!block.complete(level, root, state), "wrong cell index accepted");
            level.setBlock(tail, saved.setValue(FACING, facing.getOpposite()), 3);
            check(!block.complete(level, root, state), "wrong cell orientation accepted");
            level.setBlock(tail, saved, 3);
            verify(state);
        }

        void obstruction() {
            for (int i = 0; i < count(); i++) {
                BlockPos blocked = pos(i);
                level.setBlock(blocked, Blocks.STONE.defaultBlockState(), 3);
                check(candidate() == null, "obstruction accepted at cell " + i);
                gone();
                check(level.getBlockState(blocked).is(Blocks.STONE), "obstruction overwritten");
                level.removeBlock(blocked, false);
            }
            BlockState state = candidate();
            check(state != null, "clear footprint rejected after obstruction removal");
            BlockPos blocked = pos(count() - 1);
            level.setBlock(blocked, Blocks.STONE.defaultBlockState(), 3);
            level.setBlock(root, state, 3);
            block.setPlacedBy(level, root, state, null, new ItemStack(block));
            gone();
            check(level.getBlockState(blocked).is(Blocks.STONE), "late obstruction overwritten");
            drops(0);
        }

        Port[] specifications() {
            if(modern)return new Port[]{
                    new Port(AssemblyPort.STEAM_INLET,new BlockPos(0,hpci?3:2,2),Direction.WEST),
                    new Port(AssemblyPort.STEAM_EXHAUST,new BlockPos(hpci?2:1,1,0),Direction.NORTH),
                    new Port(AssemblyPort.WATER_SUCTION,new BlockPos(hpci?6:4,1,2),Direction.EAST),
                    new Port(AssemblyPort.WATER_DISCHARGE,new BlockPos(hpci?5:3,1,4),Direction.SOUTH)};
            return hpci ? new Port[]{
                    new Port(AssemblyPort.STEAM_INLET, new BlockPos(2, 4, 1), Direction.UP),
                    new Port(AssemblyPort.STEAM_EXHAUST, new BlockPos(0, 1, 1), Direction.WEST),
                    new Port(AssemblyPort.WATER_SUCTION, new BlockPos(3, 0, 0), Direction.EAST),
                    new Port(AssemblyPort.WATER_DISCHARGE, new BlockPos(3, 0, 2), Direction.EAST)
            } : new Port[]{
                    new Port(AssemblyPort.STEAM_INLET, new BlockPos(0, 2, 1), Direction.UP),
                    new Port(AssemblyPort.STEAM_EXHAUST, new BlockPos(1, 1, 1), Direction.SOUTH),
                    new Port(AssemblyPort.WATER_SUCTION, new BlockPos(2, 1, 1), Direction.EAST),
                    new Port(AssemblyPort.WATER_DISCHARGE, new BlockPos(2, 2, 1), Direction.UP)
            };
        }

        void ports() {
            BlockState state = place();
            Port[] specs = specifications();
            for (AssemblyPort role : AssemblyPort.values()) {
                boolean expected = true;
                check(block.hasPort(role) == expected, "hasPort " + role);
            }
            for (Port port : specs) {
                check(block.portPosition(root, state, port.role)
                        .equals(root.offset(rotated(port.offset))), "port position " + port.role);
                check(block.portFace(state, port.role) == rotation().rotate(port.face),
                        "port direction " + port.role);
            }
            var tube = BwrBlocks.PRESSURISED_TUBE.get();
            for (int i = 0; i < count(); i++) {
                BlockPos cellPos = pos(i);
                BlockState cellState = level.getBlockState(cellPos);
                for (Direction face : Direction.values()) {
                    AssemblyPort expected = null;
                    for (Port port : specs) {
                        if (port.offset.equals(offset(i)) && rotation().rotate(port.face) == face) {
                            expected = port.role;
                        }
                    }
                    check(block.portAt(cellState, face) == expected,
                            "portAt cell " + i + " face " + face);
                    check(block.acceptsSteamLineOn(cellState, face) == (expected != null && expected.isSteam()),
                            "physical port acceptance cell " + i + " face " + face);
                    BlockPos tubePos = cellPos.relative(face);
                    if (!level.getBlockState(tubePos).isAir()) continue;
                    level.setBlock(tubePos, tube.stateWithConnections(level, tubePos), 3);
                    check(level.getBlockState(tubePos)
                                    .getValue(PipeBlock.PROPERTY_BY_DIRECTION.get(face.getOpposite()))
                                    == (expected != null && expected.isSteam()),
                            "tube arm cell " + i + " face " + face);
                    level.removeBlock(tubePos, false);
                    level.setBlock(tubePos, BwrBlocks.HIGH_PRESSURE_WATER_PIPE.get().stateWithConnections(level,tubePos),3);
                    check(level.getBlockState(tubePos).getValue(PipeBlock.PROPERTY_BY_DIRECTION.get(face.getOpposite()))
                            == (expected != null && !expected.isSteam()), "water arm cell " + i + " face " + face);
                    level.removeBlock(tubePos,false);
                }
            }
            Port inlet = specs[0];
            BlockPos tubePos = root.offset(rotated(inlet.offset))
                    .relative(rotation().rotate(inlet.face));
            Direction arm = rotation().rotate(inlet.face).getOpposite();
            level.setBlock(tubePos, tube.stateWithConnections(level, tubePos), 3);
            level.removeBlock(root, false);
            gone();
            check(!level.getBlockState(tubePos).getValue(PipeBlock.PROPERTY_BY_DIRECTION.get(arm)),
                    "tube arm remained after assembly removal");
        }

        void persistence() {
            BlockState state = place();
            EccsPumpBlockEntity original = (EccsPumpBlockEntity) level.getBlockEntity(root);
            original.setComputerControlled(true);
            original.setRunning(true);
            original.setFlowDemandFraction(0.375);
            original.setSuctionSource(SuctionSource.CONDENSATE_TANK);
            CompoundTag entityTag = original.saveWithFullMetadata(level.registryAccess());
            List<CompoundTag> states = new ArrayList<>();
            for (int i = 0; i < count(); i++) {
                states.add(NbtUtils.writeBlockState(level.getBlockState(pos(i))));
                if(!modern)states.get(i).getCompound("Properties").remove("modern");
            }
            level.removeBlock(root, false);
            gone();
            for (int i = 0; i < count(); i++) {
                BlockState restored = NbtUtils.readBlockState(
                        level.registryAccess().lookupOrThrow(Registries.BLOCK), states.get(i));
                level.setBlock(pos(i), restored, 3);
            }
            level.removeBlockEntity(root);
            BlockEntity decoded = BlockEntity.loadStatic(root, level.getBlockState(root),
                    entityTag, level.registryAccess());
            check(decoded instanceof EccsPumpBlockEntity, "root block entity NBT load failed");
            level.setBlockEntity(decoded);
            EccsPumpBlockEntity restored = (EccsPumpBlockEntity) decoded;
            check(restored != original && restored.design().id().equals(block.design().id()),
                    "restored block entity identity/design");
            check(restored.isComputerControlled() && restored.isRunning()
                    && Math.abs(restored.getFlowDemandFraction() - 0.375) < 1e-9
                    && restored.getSuctionSource() == SuctionSource.CONDENSATE_TANK,
                    "persisted pump controls changed");
            verify(state);
            drops(0);
        }

        void interfaces() {
            var state=place();
            var be=(EccsPumpBlockEntity)level.getBlockEntity(root);
            var suction=block.portPosition(root,state,AssemblyPort.WATER_SUCTION);
            var face=block.portFace(state,AssemblyPort.WATER_SUCTION);
            var capability=net.neoforged.neoforge.capabilities.Capabilities.FluidHandler.BLOCK;
            var water=level.getCapability(capability,suction,face);
            check(water!=null,"Mekanism-compatible suction capability missing");
            check(water.fill(new net.neoforged.neoforge.fluids.FluidStack(net.minecraft.world.level.material.Fluids.WATER,10000),
                    net.neoforged.neoforge.fluids.capability.IFluidHandler.FluidAction.EXECUTE)>0,"suction rejected water");
            check(water.fill(new net.neoforged.neoforge.fluids.FluidStack(net.minecraft.world.level.material.Fluids.LAVA,1000),
                    net.neoforged.neoforge.fluids.capability.IFluidHandler.FluidAction.EXECUTE)==0,"suction accepted lava");
            for(var port:specifications())for(Direction side:Direction.values()) {
                var p=root.offset(rotated(port.offset));
                check((level.getCapability(capability,p,side)!=null)==(port.role==AssemblyPort.WATER_SUCTION&&side==face),"fluid leaked onto another flange/face");
            }
            var panel=root.offset(rotated(new BlockPos(hpci?4:3,2,0)));
            try {
                if(net.neoforged.fml.ModList.get().isLoaded("computercraft")) {
                    @SuppressWarnings("unchecked") var cc=(net.neoforged.neoforge.capabilities.BlockCapability<Object,Direction>)
                            Class.forName("dan200.computercraft.api.peripheral.PeripheralCapability").getMethod("get").invoke(null);
                    check(level.getCapability(cc,panel,rotation().rotate(Direction.NORTH))!=null,"visible computer panel has no peripheral");
                }
                var player=new FakePlayer(level,new GameProfile(UUID.randomUUID(),"TerryPanelTest"));
                player.setPos(panel.getX()+.5,panel.getY()+.5,panel.getZ()-.5);
                var menu=new dev.bwr.mod.gui.TerryTurbineMenu(2,player.getInventory(),root,panel);
                check(menu.stillValid(player),"child panel could not reach controller");
                be.setComputerControlled(true);be.setSpeedDemandFraction(.5);
                menu.handleCommand(player,dev.bwr.mod.gui.PumpControlMenu.SPEED,900,0);
                check(be.pump().getSpeedDemandFraction()==.5,"panel stole computer-owned speed");
                menu.handleCommand(player,dev.bwr.mod.gui.PumpControlMenu.CONTROL,0,0);
                menu.handleCommand(player,dev.bwr.mod.gui.PumpControlMenu.SPEED,650,0);
                check(be.pump().getSpeedDemandFraction()==.65,"manual speed command failed");
                var bytes=new net.minecraft.network.FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
                try {
                    var write=dev.bwr.mod.gui.TerryTurbineMenu.class.getDeclaredMethod("writeSnapshot",net.minecraft.network.FriendlyByteBuf.class);write.setAccessible(true);write.invoke(menu,bytes);
                    byte[] data=new byte[bytes.readableBytes()];bytes.readBytes(data);
                    var client=new dev.bwr.mod.gui.TerryTurbineMenu(2,player.getInventory(),root,panel);client.acceptSnapshot(data);
                    check(client.present&&client.target==.65&&!client.portLocations[3].isEmpty(),"Terry snapshot round trip lost data");
                } finally {bytes.release();}
                level.removeBlock(root,false);
                check(!menu.stillValid(player),"destroyed skid kept an active panel");
                check(water.fill(new net.neoforged.neoforge.fluids.FluidStack(net.minecraft.world.level.material.Fluids.WATER,1000),
                        net.neoforged.neoforge.fluids.capability.IFluidHandler.FluidAction.EXECUTE)==0,"cached suction survived teardown");
            } catch(ReflectiveOperationException e) {throw new IllegalStateException("Terry interface test",e);}
        }

        void harvest(int cell, boolean creative, boolean correctTool) {
            check(level.getGameRules().getBoolean(GameRules.RULE_DOBLOCKDROPS),
                    "doTileDrops must be enabled");
            place();
            FakePlayer player = new FakePlayer(level,
                    new GameProfile(UUID.randomUUID(), "TurbineCheck"));
            player.setPos(root.getX(), root.getY() + 10, root.getZ());
            player.gameMode.changeGameModeForPlayer(creative ? GameType.CREATIVE : GameType.SURVIVAL);
            player.setItemInHand(InteractionHand.MAIN_HAND,
                    correctTool ? new ItemStack(Items.DIAMOND_PICKAXE) : ItemStack.EMPTY);
            if (!creative) {
                check(player.hasCorrectToolForDrops(level.getBlockState(pos(cell))) == correctTool,
                        "tool/drop tags do not match intended harvest case");
            }
            check(player.gameMode.destroyBlock(pos(cell)), "player destruction rejected");
            gone();
            int expected = !creative && correctTool ? 1 : 0;
            drops(expected);
            check(!level.destroyBlock(pos(cell), true), "removed cell could be destroyed again");
            drops(expected);
        }
    }
}
