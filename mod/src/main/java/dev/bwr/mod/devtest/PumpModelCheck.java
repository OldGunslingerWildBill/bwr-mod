package dev.bwr.mod.devtest;

import com.mojang.logging.LogUtils;
import dev.bwr.mod.eccs.PumpAssemblyBlock;
import dev.bwr.mod.registry.BwrBlocks;
import net.minecraft.client.renderer.block.BlockModelShaper;
import net.minecraft.client.resources.model.ModelResourceLocation;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.neoforged.neoforge.client.event.ModelEvent;
import net.neoforged.neoforge.client.model.data.ModelData;

public final class PumpModelCheck {
    private PumpModelCheck() {}
    public static PumpAssemblyBlock[] blocks() { return new PumpAssemblyBlock[]{BwrBlocks.LPCS_PUMP.get(),BwrBlocks.RHR_PUMP.get(),BwrBlocks.HPCS_PUMP.get(),
            BwrBlocks.MOTOR_FEED_PUMP.get(),BwrBlocks.TURBINE_FEED_PUMP.get(),BwrBlocks.JET_PUMP.get(),BwrBlocks.RIP_PUMP.get(),BwrBlocks.RECIRCULATION_PUMP.get(),
            BwrBlocks.HP_TURBINE.get(),BwrBlocks.LP_TURBINE.get(),BwrBlocks.NUCLEAR_GENERATOR.get()}; }
    public static void run(ModelEvent.BakingCompleted event) {
        waterModels(event);
        CondenserModelCheck.run(event);
        CoolingModelCheck.run(event);
        CondensateTankModelCheck.run(event);
        int checked=0;
        for(var block:blocks()) {
            int faces=0;
            var layouts=new java.util.ArrayList<net.minecraft.world.level.block.state.BlockState>();
            layouts.add(block.placementState());
            if(block.kind()==PumpAssemblyBlock.Kind.RCP || block.kind()==PumpAssemblyBlock.Kind.JET
                    || block instanceof dev.bwr.mod.eccs.ModernPumpAssemblyBlock)
                layouts.add(block.defaultBlockState().setValue(PumpAssemblyBlock.ASSEMBLED,true));
            for(var layout:layouts) for(Direction facing:Direction.Plane.HORIZONTAL) for(int cell=0;cell<block.cellCount(layout);cell++) {
                var state=layout.setValue(PumpAssemblyBlock.FACING,facing).setValue(PumpAssemblyBlock.CELL,cell);
                var model=event.getModels().get(BlockModelShaper.stateToModelLocation(state));
                if(model==null || model==event.getModelManager().getMissingModel()) throw new IllegalStateException("Missing pump model "+state);
                var quads=model.getQuads(state,null,RandomSource.create(0),ModelData.EMPTY,null);
                for(var quad:quads) {
                    if(quad.getSprite().contents().name().getPath().equals("missingno")) throw new IllegalStateException("Missing pump texture "+state);
                    int[] data=quad.getVertices(); int stride=data.length/4;
                    for(int vertex=0;vertex<4;vertex++) for(int axis=0;axis<3;axis++) {
                        float p=Float.intBitsToFloat(data[vertex*stride+axis]);
                        if(!Float.isFinite(p) || p<-.001F || p>1.001F) throw new IllegalStateException("Pump cell outside block "+state+": "+p);
                    }
                }
                faces+=quads.size(); checked++;
            }
            var id=net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(block);
            var item=event.getModels().get(ModelResourceLocation.inventory(id));
            if(faces<100 || item==null || item==event.getModelManager().getMissingModel()
                    || item.getQuads(null,null,RandomSource.create(0),ModelData.EMPTY,null).isEmpty()) throw new IllegalStateException("Empty pump or inventory mesh "+id);
            var compact=event.getModels().get(BlockModelShaper.stateToModelLocation(block.defaultBlockState()));
            if(compact==null || compact==event.getModelManager().getMissingModel() || compact.getQuads(null,null,RandomSource.create(0),ModelData.EMPTY,null).isEmpty()) throw new IllegalStateException("Missing compact migration model "+id);
            LogUtils.getLogger().info("PUMP MODEL CHECK: {} has {} quads across four rotations",id,faces);
        }
        LogUtils.getLogger().info("PUMP MODEL CHECK PASS: {} occupied cell states, {} inventory and compact models",checked,blocks().length);
    }
    private static void waterModels(ModelEvent.BakingCompleted event) {
        int states=0;
        for (var block:new net.minecraft.world.level.block.Block[]{BwrBlocks.HIGH_PRESSURE_WATER_PIPE.get(), BwrBlocks.PRESSURISED_TUBE.get(), BwrBlocks.RPV_WATER_INJECTION_PORT.get(), BwrBlocks.RECIRCULATION_OUTLET.get(), BwrBlocks.RECIRCULATION_INLET.get(), BwrBlocks.CONDENSATE_STORAGE_TANK.get(),BwrBlocks.STEAM_STOP_VALVE.get(),BwrBlocks.TURBINE_CONTROL_VALVE.get(),BwrBlocks.MSIV.get(),BwrBlocks.BYPASS_STEAM_VALVE.get()}) {
            for (var state:block.getStateDefinition().getPossibleStates()) {
                var model=event.getModels().get(BlockModelShaper.stateToModelLocation(state));
                if(model==null || model==event.getModelManager().getMissingModel()) throw new IllegalStateException("Missing water model "+state);
                // OBJ parts use unculled quads; legacy cube valves use six culled faces.
                var quads=new java.util.ArrayList<>(model.getQuads(state,null,RandomSource.create(0),ModelData.EMPTY,null));
                for(Direction side:Direction.values())quads.addAll(model.getQuads(state,side,RandomSource.create(0),ModelData.EMPTY,null));
                boolean tankRenderer=block instanceof dev.bwr.mod.eccs.CondensateStorageTankBlock&&state.getValue(dev.bwr.mod.eccs.CondensateStorageTankBlock.ASSEMBLED);
                if(quads.isEmpty()&&!tankRenderer) throw new IllegalStateException("Empty water model "+state);
                if(tankRenderer&&!quads.isEmpty())throw new IllegalStateException("Assembled tank still renders cubes");
                if (block instanceof dev.bwr.mod.piping.PaintedPipeBlock && (quads.stream().noneMatch(q -> q.getTintIndex()==0)
                        || quads.stream().noneMatch(q -> q.getTintIndex()==-1)))
                    throw new IllegalStateException("Pipe needs both paint and unpainted metal: "+state);
                for(var quad:quads) {
                    if(quad.getSprite().contents().name().getPath().equals("missingno")) throw new IllegalStateException("Missing water material "+state);
                    int[] data=quad.getVertices(); int stride=data.length/4;
                    for(int v=0;v<4;v++) for(int axis=0;axis<3;axis++) {
                        float p=Float.intBitsToFloat(data[v*stride+axis]);
                        if(!Float.isFinite(p) || p<-.001F || p>1.001F) throw new IllegalStateException("Water model outside block "+state);
                    }
                }
                states++;
            }
            var id=net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(block);
            var item=event.getModels().get(ModelResourceLocation.inventory(id));
            int itemQuads=item==null?0:item.getQuads(null,null,RandomSource.create(0),ModelData.EMPTY,null).size();
            if(item!=null)for(var side:Direction.values())itemQuads+=item.getQuads(null,side,RandomSource.create(0),ModelData.EMPTY,null).size();
            if(item==null || item==event.getModelManager().getMissingModel() || itemQuads==0)
                throw new IllegalStateException("Missing water inventory model "+id);
        }
        LogUtils.getLogger().info("WATER/STEAM MODEL CHECK PASS: {} block states and ten inventory models",states);
    }
}
