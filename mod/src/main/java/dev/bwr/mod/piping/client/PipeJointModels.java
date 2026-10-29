package dev.bwr.mod.piping.client;

import dev.bwr.mod.BwrMod;
import dev.bwr.mod.registry.BwrBlocks;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.BlockModelShaper;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.resources.model.ModelResourceLocation;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.PipeBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ModelEvent;
import net.neoforged.neoforge.client.model.BakedModelWrapper;
import net.neoforged.neoforge.client.model.data.ModelData;
import net.neoforged.neoforge.client.model.data.ModelProperty;
import org.jetbrains.annotations.Nullable;

/**
 * The flange where a pipe meets a machine (PIPES.md). A pipe's blockstate only says which faces are connected, not to
 * what, and that stays so: the save format and the connection rules are untouched. When a section is meshed, each pipe
 * looks at its connected neighbours once ({@link BakedModel#getModelData}); a connected face whose neighbour is not the
 * same pipe gets the bolted flange model {@code block/pipes/<set>/port_<direction>} added to the pipe's own quads. Pipe to
 * pipe stays a plain, seamless run. Rendering only: nothing on the server reads this.
 */
@EventBusSubscriber(modid = BwrMod.MOD_ID, value = Dist.CLIENT)
public final class PipeJointModels {
    /** Bit {@code Direction.ordinal()} set: that connected face meets something other than this pipe. */
    public static final ModelProperty<Integer> PORTS = new ModelProperty<>();

    private PipeJointModels() {}

    private static Map<Block, String> sets() {
        return Map.of(BwrBlocks.HIGH_PRESSURE_WATER_PIPE.get(), "water", BwrBlocks.PRESSURISED_TUBE.get(), "steam", BwrBlocks.LUBE_OIL_PIPE.get(), "oil");
    }

    public static ModelResourceLocation port(String set, Direction d) {
        return ModelResourceLocation.standalone(ResourceLocation.fromNamespaceAndPath(BwrMod.MOD_ID, "block/pipes/" + set + "/port_" + d.getName()));
    }

    @SubscribeEvent
    public static void register(ModelEvent.RegisterAdditional event) {
        for (String set : sets().values()) for (Direction d : Direction.values()) event.register(port(set, d));
    }

    @SubscribeEvent
    public static void bake(ModelEvent.ModifyBakingResult event) {
        for (var entry : sets().entrySet()) {
            BakedModel[] ports = new BakedModel[6];
            for (Direction d : Direction.values()) {
                ports[d.ordinal()] = event.getModels().get(port(entry.getValue(), d));
                if (ports[d.ordinal()] == null || ports[d.ordinal()] == event.getModels().get(net.minecraft.client.resources.model.ModelBakery.MISSING_MODEL_VARIANT))
                    throw new IllegalStateException("Unbaked pipe port flange " + port(entry.getValue(), d));
            }
            // Paint and waterlogging share one mesh per connection pattern; wrap each mesh once.
            Map<BakedModel, BakedModel> wrapped = new IdentityHashMap<>();
            for (BlockState state : entry.getKey().getStateDefinition().getPossibleStates()) {
                var key = BlockModelShaper.stateToModelLocation(state);
                BakedModel model = event.getModels().get(key);
                if (model == null) throw new IllegalStateException("Unbaked pipe model " + key);
                event.getModels().put(key, wrapped.computeIfAbsent(model, m -> new Joints(m, ports)));
            }
        }
    }

    /** The ports bit mask of a placed pipe: connected faces whose neighbour is not the same pipe block. */
    public static int ports(BlockAndTintGetter level, BlockPos pos, BlockState state) {
        int mask = 0;
        for (Direction d : Direction.values())
            if (state.getValue(PipeBlock.PROPERTY_BY_DIRECTION.get(d)) && !level.getBlockState(pos.relative(d)).is(state.getBlock()))
                mask |= 1 << d.ordinal();
        return mask;
    }

    /** A connection-pattern mesh plus the port flanges its model data asks for. */
    public static final class Joints extends BakedModelWrapper<BakedModel> {
        private final BakedModel[] ports;

        Joints(BakedModel original, BakedModel[] ports) {
            super(original);
            this.ports = ports;
        }

        @Override
        public ModelData getModelData(BlockAndTintGetter level, BlockPos pos, BlockState state, ModelData data) {
            int mask = ports(level, pos, state);
            return mask == 0 ? data : data.derive().with(PORTS, mask).build();
        }

        @Override
        public List<BakedQuad> getQuads(@Nullable BlockState state, @Nullable Direction side, RandomSource rand, ModelData data,
                                        @Nullable RenderType renderType) {
            List<BakedQuad> base = originalModel.getQuads(state, side, rand, data, renderType);
            Integer mask = data.get(PORTS);
            if (mask == null || mask == 0) return base;
            List<BakedQuad> out = new ArrayList<>(base);
            for (Direction d : Direction.values())
                if ((mask >> d.ordinal() & 1) != 0) out.addAll(ports[d.ordinal()].getQuads(state, side, rand, ModelData.EMPTY, renderType));
            return out;
        }
    }
}
