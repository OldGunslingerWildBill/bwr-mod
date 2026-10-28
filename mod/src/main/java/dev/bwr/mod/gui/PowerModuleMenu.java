package dev.bwr.mod.gui;

import dev.bwr.mod.power.*;
import dev.bwr.mod.registry.BwrMenus;
import net.minecraft.core.BlockPos;
import net.minecraft.network.*;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.*;
import net.minecraft.world.level.block.Block;

/** Readouts for any part of a large shaft module. */
public class PowerModuleMenu extends BwrMenu {
    public static final int CMD_TURNING_GEAR=1;
    private final BlockPos anchor;private final Block expected;
    public boolean present,running,generator,hp;
    public double rpm,flow,shaftMW,electricMW,trainMW,inletP,outletP,inletC,outletC,water,energy;
    public int hpCount,lpCount,generatorCount;
    /** The turbine auxiliaries (TURBINE-AUX.md): which systems this section's train is governed by, and their readings. */
    public boolean lubeGov,bowGov,genGov,oilTank,shaftPump,gearOn,gearRolling,gearFed;
    public double oilPsig,oilC,oilLevel,oilFilm,bearingC,vibration,bow,rotorC,windingC,statorFlow,h2Psig,coolantC;
    public int alarms;
    /** The oil filter housing on the train's oil tank (TURBINE-AUX.md): fitted, its bypass open, the cartridge life left and the pressure drop across it. */
    public boolean filterHousing,filterBypass;
    public double filterLife=Double.NaN,filterDp=Double.NaN;
    /** The blade sections and the casing (TURBINE-MAINTENANCE.md). Section masks: bit 0 flow A, bit 1 flow B. */
    public boolean lp,coverOff,motoring,hasClose;
    public int sectionsIn,thrown;
    public double bladeA,bladeB,hoodC,reverseMW,closeAngle,closePu,shaftDamage,damage;
    /** A wrecked casing and a fire (TURBINE-MAINTENANCE.md): the wreck level (PowerModuleBlockEntity.WRECK_*), the fire's kind (0 none, 1 oil, 2 hydrogen) and fuel. */
    public int wreck,fire;
    public double fireFuel;
    public String status="Waiting for server";
    public PowerModuleMenu(int id,Inventory inv,RegistryFriendlyByteBuf b){this(id,inv,b.readBlockPos(),b.readBlockPos());}
    public PowerModuleMenu(int id,Inventory inv,BlockPos root,BlockPos anchor){super(BwrMenus.POWER_MODULE.get(),id,inv,root,inv.player.level().getBlockState(root).getBlock());this.anchor=anchor.immutable();expected=inv.player.level().getBlockState(root).getBlock();}
    public static void open(ServerPlayer player,BlockPos root,BlockPos anchor){
        if(!(player.level().getBlockEntity(root) instanceof PowerModuleBlockEntity m))return;
        player.openMenu(new SimpleMenuProvider((id,inv,who)->new PowerModuleMenu(id,inv,root,anchor),m.getBlockState().getBlock().getName()),b->{b.writeBlockPos(root);b.writeBlockPos(anchor);});
    }
    @Override public boolean stillValid(Player who){
        if(!level().isLoaded(pos)||!level().isLoaded(anchor)||who.distanceToSqr(anchor.getX()+.5,anchor.getY()+.5,anchor.getZ()+.5)>64)return false;
        var s=level().getBlockState(anchor);
        return s.is(expected)&&s.getBlock() instanceof PowerModuleBlock b&&b.origin(anchor,s).equals(pos)&&b.complete(level(),pos,s);
    }
    public void sample(){
        present=level().getBlockEntity(pos) instanceof PowerModuleBlockEntity;if(!present)return;
        var m=(PowerModuleBlockEntity)level().getBlockEntity(pos);
        running=m.running;generator=m.block().generator();hp=m.block().highPressure();rpm=m.rpm;
        flow=m.flowKgPerS;shaftMW=m.shaftMW;electricMW=m.electricMW;trainMW=m.trainMW;inletP=m.inletPsia;outletP=m.outletPsia;
        inletC=m.inletC;outletC=m.outletC;water=m.waterStored();energy=m.energyStored();hpCount=m.hpCount;lpCount=m.lpCount;generatorCount=m.generatorCount;status=m.status;
        var a=m.aux();
        lubeGov=a.lubeGoverned;bowGov=a.bowGoverned;genGov=a.genGoverned;oilTank=a.oilTank;shaftPump=a.shaftPump;gearOn=m.turningGear();gearRolling=a.gearRolling;
        gearFed=a.gearFed;
        oilPsig=a.oilPsig;oilC=a.oilC;oilLevel=a.oilLevel;oilFilm=a.oilFilm;bearingC=m.bearingC;vibration=m.vibration;bow=m.bow();rotorC=m.rotorC;
        windingC=m.windingC;statorFlow=a.statorFlow;h2Psig=a.h2Psig;coolantC=a.coolantC;
        alarms=(a.oilPressureAlarm?1:0)|(a.oilLevelAlarm?2:0)|(a.oilTempAlarm?4:0)|(a.bearingAlarm?8:0)|(a.vibrationAlarm?16:0)|(a.windingAlarm?32:0)|(a.h2Alarm?64:0)|(a.filterAlarm?128:0);
        filterHousing=a.filterHousing;filterBypass=a.filterBypass;filterLife=a.filterLife;filterDp=a.filterDpPsi;
        lp=m.lp();coverOff=m.coverOff();motoring=m.motoring;hasClose=m.hasCloseShock();
        sectionsIn=(m.bladeIn(0)?1:0)|(m.bladeIn(1)?2:0);thrown=(m.bladeThrown(0)?1:0)|(m.bladeThrown(1)?2:0);
        bladeA=m.blade(0);bladeB=m.blade(1);hoodC=m.hoodC;reverseMW=m.reverseMW;closeAngle=m.closeAngle();closePu=m.closeTorquePu();
        shaftDamage=m.shaftDamage();damage=m.damage();
        wreck=m.wreck();fire=m.fire().ordinal();fireFuel=m.fireFuel();
    }
    @Override protected void writeSnapshot(FriendlyByteBuf b){
        sample();b.writeBoolean(present);if(!present)return;
        b.writeBoolean(running);b.writeBoolean(generator);b.writeBoolean(hp);
        for(double v:new double[]{rpm,flow,shaftMW,electricMW,trainMW,inletP,outletP,inletC,outletC,water,energy})b.writeDouble(v);
        b.writeInt(hpCount);b.writeInt(lpCount);b.writeInt(generatorCount);b.writeUtf(status);
        b.writeInt(alarms);
        for(boolean v:new boolean[]{lubeGov,bowGov,genGov,oilTank,shaftPump,gearOn,gearRolling,gearFed})b.writeBoolean(v);
        for(double v:new double[]{oilPsig,oilC,oilLevel,oilFilm,bearingC,vibration,bow,rotorC,windingC,statorFlow,h2Psig,coolantC})b.writeDouble(v);
        for(boolean v:new boolean[]{lp,coverOff,motoring,hasClose})b.writeBoolean(v);
        b.writeByte(sectionsIn);b.writeByte(thrown);
        for(double v:new double[]{bladeA,bladeB,hoodC,reverseMW,closeAngle,closePu,shaftDamage,damage})b.writeDouble(v);
        b.writeByte(wreck);b.writeByte(fire);b.writeDouble(fireFuel);
        b.writeBoolean(filterHousing);b.writeBoolean(filterBypass);b.writeDouble(filterLife);b.writeDouble(filterDp);
    }
    @Override protected void readSnapshot(FriendlyByteBuf b){
        present=b.readBoolean();if(!present)return;running=b.readBoolean();generator=b.readBoolean();hp=b.readBoolean();
        rpm=b.readDouble();flow=b.readDouble();shaftMW=b.readDouble();electricMW=b.readDouble();trainMW=b.readDouble();inletP=b.readDouble();outletP=b.readDouble();inletC=b.readDouble();outletC=b.readDouble();water=b.readDouble();energy=b.readDouble();
        hpCount=b.readInt();lpCount=b.readInt();generatorCount=b.readInt();status=b.readUtf();
        alarms=b.readInt();
        lubeGov=b.readBoolean();bowGov=b.readBoolean();genGov=b.readBoolean();oilTank=b.readBoolean();shaftPump=b.readBoolean();gearOn=b.readBoolean();gearRolling=b.readBoolean();gearFed=b.readBoolean();
        oilPsig=b.readDouble();oilC=b.readDouble();oilLevel=b.readDouble();oilFilm=b.readDouble();bearingC=b.readDouble();vibration=b.readDouble();bow=b.readDouble();rotorC=b.readDouble();
        windingC=b.readDouble();statorFlow=b.readDouble();h2Psig=b.readDouble();coolantC=b.readDouble();
        lp=b.readBoolean();coverOff=b.readBoolean();motoring=b.readBoolean();hasClose=b.readBoolean();
        sectionsIn=b.readByte();thrown=b.readByte();
        bladeA=b.readDouble();bladeB=b.readDouble();hoodC=b.readDouble();reverseMW=b.readDouble();closeAngle=b.readDouble();closePu=b.readDouble();
        shaftDamage=b.readDouble();damage=b.readDouble();
        wreck=b.readByte();fire=b.readByte();fireFuel=b.readDouble();
        filterHousing=b.readBoolean();filterBypass=b.readBoolean();filterLife=b.readDouble();filterDp=b.readDouble();
    }
    @Override public void handleCommand(ServerPlayer sender,int command,int a,int unused){
        // Turbine sections have no local steam actuator; use the upstream steam valve. The generator's turning gear is the one switch here.
        if(command==CMD_TURNING_GEAR&&stillValid(sender)&&level().getBlockEntity(pos) instanceof PowerModuleBlockEntity m){
            if(!m.setTurningGear(a!=0))refuse(sender,net.minecraft.network.chat.Component.literal(m.block().generator()
                    ?"The turning gear cannot be engaged above "+(int)dev.bwr.core.turbine.TurbineAux.GEAR_MAX_RPM+" rpm."
                    :"Only the generator has a turning gear."));
            markSnapshotDirty();
        }
    }
}
