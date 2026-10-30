package dev.bwr.mod.piping.client;

import dev.bwr.mod.BwrMod;
import dev.bwr.mod.piping.OilLineNetwork;
import dev.bwr.mod.power.PowerModuleBlock;
import dev.bwr.mod.registry.BwrBlocks;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.BlockModelShaper;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.resources.model.ModelResourceLocation;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ModelEvent;
import net.neoforged.neoforge.client.model.BakedModelWrapper;
import net.neoforged.neoforge.client.model.data.ModelData;
import org.jetbrains.annotations.Nullable;

/**
 * The bearing oil ports of the turbine train, drawn (TURBINE-AUX.md, Piping; PIPES.md). Every cell state of the HP, LP and
 * generator for which {@link PowerModuleBlock#oilPortAt} names a port on one of its faces gets the port model
 * {@code block/pipes/turbine_ports/<section>_<supply|drain>_<face>} added to its own mesh: a short stub and a blanked flange,
 * cobalt blue for the supply, brown for the drain. The cells and faces come from the same method the oil network uses, so the
 * picture cannot drift from the plumbing; the port turns with the section because the face it is asked about does. A Lube Oil
 * Pipe connected there draws its own steel machine flange against it (PipeJointModels): one flange pair. Rendering only.
 */
@EventBusSubscriber(modid = BwrMod.MOD_ID, value = Dist.CLIENT)
public final class TurbineOilPortModels {
    private TurbineOilPortModels() {}

    private static final Direction[] HORIZONTAL = {Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST};

    private static PowerModuleBlock[] sections() {
        return new PowerModuleBlock[]{BwrBlocks.HP_TURBINE.get(), BwrBlocks.LP_TURBINE.get(), BwrBlocks.NUCLEAR_GENERATOR.get()};
    }

    public static ModelResourceLocation port(PowerModuleBlock section, boolean supply, Direction face) {
        String id = BuiltInRegistries.BLOCK.getKey(section).getPath();
        return ModelResourceLocation.standalone(ResourceLocation.fromNamespaceAndPath(BwrMod.MOD_ID,
                "block/pipes/turbine_ports/" + id + "_" + (supply ? "supply" : "drain") + "_" + face.getName()));
    }

    @SubscribeEvent
    public static void register(ModelEvent.RegisterAdditional event) {
        for (PowerModuleBlock section : sections())
            for (boolean supply : new boolean[]{true, false})
                for (Direction face : HORIZONTAL) event.register(port(section, supply, face));
    }

    @SubscribeEvent
    public static void bake(ModelEvent.ModifyBakingResult event) {
        BakedModel missing = event.getModels().get(net.minecraft.client.resources.model.ModelBakery.MISSING_MODEL_VARIANT);
        for (PowerModuleBlock section : sections()) {
            Map<BakedModel, Map<List<BakedModel>, BakedModel>> wrapped = new IdentityHashMap<>();
            for (BlockState state : section.getStateDefinition().getPossibleStates()) {
                List<BakedModel> ports = new ArrayList<>();
                for (Direction face : HORIZONTAL) {
                    OilLineNetwork.Role role = section.oilPortAt(state, face);
                    if (role == null) continue;
                    BakedModel port = event.getModels().get(port(section, role == OilLineNetwork.Role.SECTION_SUPPLY, face));
                    if (port == null || port == missing)
                        throw new IllegalStateException("Unbaked turbine oil port " + port(section, role == OilLineNetwork.Role.SECTION_SUPPLY, face));
                    ports.add(port);
                }
                if (ports.isEmpty()) continue;
                var key = BlockModelShaper.stateToModelLocation(state);
                BakedModel model = event.getModels().get(key);
                if (model == null) throw new IllegalStateException("Unbaked turbine cell " + key);
                event.getModels().put(key, wrapped.computeIfAbsent(model, m -> new HashMap<>()).computeIfAbsent(ports, p -> new WithPorts(model, p)));
            }
        }
    }

    /** A section cell's mesh with its oil port meshes added. */
    public static final class WithPorts extends BakedModelWrapper<BakedModel> {
        private final List<BakedModel> ports;

        WithPorts(BakedModel original, List<BakedModel> ports) {
            super(original);
            this.ports = List.copyOf(ports);
        }

        public List<BakedModel> ports() { return ports; }

        @Override
        public List<BakedQuad> getQuads(@Nullable BlockState state, @Nullable Direction side, RandomSource rand, ModelData data,
                                        @Nullable RenderType renderType) {
            List<BakedQuad> base = originalModel.getQuads(state, side, rand, data, renderType);
            List<BakedQuad> out = new ArrayList<>(base);
            for (BakedModel port : ports) out.addAll(port.getQuads(state, side, rand, ModelData.EMPTY, renderType));
            return out;
        }

        @Override
        public List<BakedQuad> getQuads(@Nullable BlockState state, @Nullable Direction side, RandomSource rand) {
            return getQuads(state, side, rand, ModelData.EMPTY, null);
        }
    }
}
