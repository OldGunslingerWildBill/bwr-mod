package dev.bwr.mod.reactor.client;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.bwr.mod.BwrMod;
import dev.bwr.mod.client.MachineMeshCache;
import dev.bwr.mod.reactor.*;
import dev.bwr.mod.registry.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.*;
import net.minecraft.client.renderer.block.BlockModelShaper;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.blockentity.*;
import net.minecraft.client.resources.model.*;
import net.minecraft.core.*;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.*;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.*;
import net.neoforged.neoforge.client.model.BakedModelWrapper;
import net.neoforged.neoforge.client.model.data.*;
import java.util.*;

/** One renderer per reactor. Blender pieces scale to the existing construction envelope. */
@EventBusSubscriber(modid=BwrMod.MOD_ID,value=Dist.CLIENT)
public final class ReactorVesselRenderer implements BlockEntityRenderer<ReactorControllerBlockEntity> {
    /** Every vessel part: the RVU port ends and panel, and the v2 vessel's unit parts. */
    public static final String[] PARTS=java.util.stream.Stream.concat(java.util.stream.Stream.of("spool","steam_nozzle","water_nozzle","controller_panel"),
            java.util.Arrays.stream(VesselModel.PARTS)).toArray(String[]::new);
    private static final ModelProperty<Boolean> HIDDEN=new ModelProperty<>();
    private record Shape(VesselAppearance.Envelope envelope,boolean closed,boolean failed){}
    private record CoreShape(int width,int depth,int height,VesselCoreAppearance core){}
    private record JetShape(VesselAppearance.Envelope envelope,boolean internalPumps){}
    /** The v2 internals' three meshes: the fixed ones, the separator assembly and the dryer (size and variant only). */
    public record InternalsShape(int width,int depth,int height,boolean internalPumps){}
    public record SeparatorShape(int width,int depth,int height,boolean internalPumps){}
    public record DryerShape(int width,int depth,int height,boolean internalPumps){}
    private record DriveShape(int width,int depth,int height,int version,int latticeWidth,
                              List<VesselCoreAppearance.Drive> drives,boolean internalPumps){}
    public static ModelResourceLocation model(String part) {
        return ModelResourceLocation.standalone(BwrMod.id("block/reactor_vessel/"+part+"/body"));
    }
    public ReactorVesselRenderer(BlockEntityRendererProvider.Context context) {}
    @SubscribeEvent public static void models(ModelEvent.RegisterAdditional e) {for(var p:PARTS)e.register(model(p));for(var p:VesselJetGeometry.PARTS)e.register(VesselJetGeometry.model(p));e.register(VesselDriveGeometry.MODEL);}
    @SubscribeEvent public static void renderers(EntityRenderersEvent.RegisterRenderers e) {
        e.registerBlockEntityRenderer(BwrBlockEntities.REACTOR_CONTROLLER.get(),ReactorVesselRenderer::new);
    }
    @SubscribeEvent public static void baked(ModelEvent.ModifyBakingResult e) {
        for(var block:List.of(BwrBlocks.REACTOR_VESSEL.get(),BwrBlocks.CORE_SPRAY_SPARGER.get(),BwrBlocks.JET_PUMP.get(),
                BwrBlocks.REACTOR_CONTROLLER.get(),BwrBlocks.RPV_WATER_INJECTION_PORT.get(),BwrBlocks.RPV_STEAM_OUTLET.get(),
                BwrBlocks.RECIRCULATION_INLET.get(),BwrBlocks.RECIRCULATION_OUTLET.get(),BwrBlocks.CONTROL_ROD_DRIVE.get()))
        for(var state:block.getStateDefinition().getPossibleStates()) {
            var key=BlockModelShaper.stateToModelLocation(state);var original=e.getModels().get(key);
            if(original!=null)e.getModels().put(key,new ShellModel(original,block instanceof CoreSpraySpargerBlock,
                    block!=BwrBlocks.REACTOR_VESSEL.get()&&!(block instanceof CoreSpraySpargerBlock),block==BwrBlocks.JET_PUMP.get(),block==BwrBlocks.CONTROL_ROD_DRIVE.get()));
        }
    }
    private static final class ShellModel extends BakedModelWrapper<BakedModel> {
        private final boolean sparger;
        private final boolean interfaceBlock;
        private final boolean jet;
        private final boolean drive;
        ShellModel(BakedModel model,boolean sparger,boolean interfaceBlock,boolean jet,boolean drive) {super(model);this.sparger=sparger;this.interfaceBlock=interfaceBlock;this.jet=jet;this.drive=drive;}
        @Override public ModelData getModelData(BlockAndTintGetter world,BlockPos pos,BlockState state,ModelData data) {
            var level=Minecraft.getInstance().level;
            return data.derive().with(HIDDEN,drive?VesselAppearance.hidesDrive(level,pos):jet?VesselAppearance.hidesJet(level,pos):sparger?VesselAppearance.hidesSparger(level,pos)
                    :interfaceBlock?VesselAppearance.hidesInterface(level,pos):VesselAppearance.hides(level,pos)).build();
        }
        @Override public List<BakedQuad> getQuads(BlockState state,Direction side,RandomSource random,ModelData data,RenderType type) {
            return Boolean.TRUE.equals(data.get(HIDDEN))?List.of():super.getQuads(state,side,random,data,type);
        }
    }
    @Override public void render(ReactorControllerBlockEntity be,float partial,PoseStack pose,MultiBufferSource buffers,int light,int overlay) {
        var e=be.clientVesselEnvelope();if(e==null || !be.clientFormed())return;
        double cx=e.min().getX()+e.width()/2.0,cz=e.min().getZ()+e.depth()/2.0;
        if(be.getLevel()!=null)light=LevelRenderer.getLightColor(be.getLevel(),BlockPos.containing(cx,e.max().getY()+1,cz));
        boolean failed=be.clientHeadFailed();
        boolean closed=be.vesselState().canHoldPressure()&&!failed;
        VesselAccidentEffects.emit(be);
        pose.pushPose();pose.translate(cx-be.getBlockPos().getX(),e.min().getY()-be.getBlockPos().getY(),cz-be.getBlockPos().getZ());
        MachineMeshCache.draw(new Shape(e,closed,failed),b->build(b,e,closed,failed),pose,buffers,light,overlay);
        var core=be.clientCoreAppearance();
        if(core!=null)MachineMeshCache.draw(new DriveShape(e.width(),e.depth(),e.height(),core.layoutVersion(),core.latticeWidth(),core.drives(),core.internalPumps()),
                b->VesselDriveGeometry.build(b,e,core),pose,buffers,light,overlay);
        var camera=Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
        boolean interiorVisible=!closed||getRenderBoundingBox(be).contains(camera);
        if(core!=null&&interiorVisible) {
            MachineMeshCache.draw(new CoreShape(e.width(),e.depth(),e.height(),core),b->ReactorCoreGeometry.build(b,e,core),pose,buffers,light,overlay);
            var profile=VesselProfile.of(e,core.internalPumps());
            MachineMeshCache.draw(new InternalsShape(e.width(),e.depth(),e.height(),core.internalPumps()),b->VesselInternalsModel.buildFixed(b,profile),pose,buffers,light,overlay);
            if(be.clientSeparatorInstalled())drawSeparators(profile,pose,buffers,light,overlay);
            if(be.clientDryerInstalled())drawDryer(profile,pose,buffers,light,overlay);
            MachineMeshCache.draw(new JetShape(e,core.internalPumps()),b->VesselJetGeometry.build(b,e,core),pose,buffers,light,overlay);
            var layout=ReactorCoreGeometry.layout(e,core);
            for(int i=0;i<core.drives().size();i++) {
                float height=(float)((layout.top()-layout.bottom())*be.clientBladeInsertion(i,partial));if(height<.001)continue;
                var drive=core.drives().get(i);
                pose.pushPose();pose.translate(ReactorCoreGeometry.driveX(e,core,layout,drive),layout.bottom(),ReactorCoreGeometry.driveZ(e,core,layout,drive));
                pose.scale(layout.pitchX(),height,layout.pitchZ());
                MachineMeshCache.model(ReactorCoreGeometry.model("blade"),pose,buffers,light,overlay);pose.popPose();
            }
        }
        if(interiorVisible&&be.clientWaterPresent()) {
            if(!VesselGpuVisuals.water(be,e,partial,pose,buffers,light))
                VesselWaterSurface.render(e,be.clientWaterLevelIn(partial),pose,buffers,light,overlay);
            if(core!=null&&!VesselGpuVisuals.cherenkov(be,e,core,partial,pose,buffers))
                VesselCherenkovGlow.render(be,e,core,partial,pose,buffers);
        }
        // Water over the flange, in the well and the annulus down to the cavity seal: outside the vessel, so seen with the head on too.
        if(be.clientWellSurface()>0)VesselWellWater.render(e,be.clientWellSurface(),pose,buffers,light);
        pose.popPose();
        var player=Minecraft.getInstance().player;
        if(core!=null && core.layoutVersion()!=1 && player!=null && (player.getMainHandItem().is(BwrBlocks.RIP_PUMP.get().asItem())
                || player.getOffhandItem().is(BwrBlocks.RIP_PUMP.get().asItem()))) {
            for(var mount:dev.bwr.core.fuel.InternalPumpLayout.mounts(e.width()-2,e.depth()-2)) {
                var p=e.min().offset(1+mount.x(),0,1+mount.z());
                var box=new AABB(p.getX(),p.getY()-3,p.getZ(),p.getX()+2,p.getY()+2,p.getZ()+2)
                        .move(-be.getBlockPos().getX(),-be.getBlockPos().getY(),-be.getBlockPos().getZ());
                LevelRenderer.renderLineBox(pose,buffers.getBuffer(RenderType.lines()),box,.15f,.9f,1f,.85f);
            }
        }
        if(player!=null && (player.getMainHandItem().is(BwrBlocks.REACTOR_VESSEL.get().asItem())
                || player.getOffhandItem().is(BwrBlocks.REACTOR_VESSEL.get().asItem()))) {
            var box=getRenderBoundingBox(be).move(-be.getBlockPos().getX(),-be.getBlockPos().getY(),-be.getBlockPos().getZ());
            LevelRenderer.renderLineBox(pose,buffers.getBuffer(RenderType.lines()),box,.3f,.75f,1f,.65f);
        }
    }
    private static void build(MachineMeshCache.Builder b,VesselAppearance.Envelope e,boolean closed,boolean failed){
        CoreSpargerGeometry.build(b,e);
        VesselModel.buildShell(b,e,closed,failed);
        CavitySealGeometry.build(b,e);
        // Adapt the curved shell to the player's own wall blocks. Interfaces stay put.
        VesselModel.buildPorts(b,e);
    }
    /** The separator assembly (shroud head, standpipes, separators) at its place in a vessel of this profile. Also drawn on the hoist and on a pad. */
    public static void drawSeparators(VesselProfile p,PoseStack pose,MultiBufferSource buffers,int light,int overlay){
        MachineMeshCache.draw(new SeparatorShape(p.width,p.depth,p.height,p.internalPumps),b->VesselInternalsModel.buildSeparators(b,p),pose,buffers,light,overlay);
    }
    /** The steam dryer at its place in a vessel of this profile. Also drawn on the hoist and on a pad. */
    public static void drawDryer(VesselProfile p,PoseStack pose,MultiBufferSource buffers,int light,int overlay){
        MachineMeshCache.draw(new DryerShape(p.width,p.depth,p.height,p.internalPumps),b->VesselInternalsModel.buildDryer(b,p),pose,buffers,light,overlay);
    }
    @Override public AABB getRenderBoundingBox(ReactorControllerBlockEntity be) {
        var e=be.clientVesselEnvelope();return e==null?new AABB(be.getBlockPos()):new AABB(Vec3.atLowerCornerOf(e.min().below()),Vec3.atLowerCornerOf(e.max().offset(1,1,1)));
    }
    @Override public int getViewDistance(){return 256;}
    // NeoForge frustum-tests this full finite envelope even on the global BER path.
    @Override public boolean shouldRenderOffScreen(ReactorControllerBlockEntity be){return true;}
    @Override public boolean shouldRender(ReactorControllerBlockEntity be,Vec3 camera) {
        return be.clientFormed() && be.clientVesselEnvelope()!=null && MachineMeshCache.withinDistance(getRenderBoundingBox(be),camera,getViewDistance());
    }
}
