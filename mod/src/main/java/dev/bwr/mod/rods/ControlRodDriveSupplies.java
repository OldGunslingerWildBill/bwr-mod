package dev.bwr.mod.rods;

import java.lang.ref.WeakReference;
import java.util.*;
import net.minecraft.core.*;
import net.minecraft.server.level.*;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.capabilities.ICapabilityInvalidationListener;
import net.neoforged.neoforge.event.level.*;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;

/** A connected drive bank shares supplies, but each drive retains its own saved inventory and mechanics. */
@EventBusSubscriber(modid="bwr")
public final class ControlRodDriveSupplies {
    public static final int MAX_DRIVES=4096;
    private static final int MAX_CACHED_POSITIONS=8192;
    private static final Map<Level,LinkedHashMap<BlockPos,Bank>> CACHE=new WeakHashMap<>();
    private static long builds;
    public static long buildCount(){return builds;}
    private ControlRodDriveSupplies(){}

    public static Bank at(Level level,BlockPos pos){
        if(level==null||level.isClientSide()||!level.isLoaded(pos)
                ||!(level.getBlockEntity(pos) instanceof ControlRodDriveBlockEntity))return null;
        var cache=CACHE.computeIfAbsent(level,l->new LinkedHashMap<>(64,.75f,true));
        var old=cache.get(pos);if(old!=null&&!old.dirty)return old;
        if(old!=null)cache.values().removeIf(b->b==old);
        var bank=new Bank(level);
        var found=new LinkedHashSet<BlockPos>();var pending=new ArrayDeque<BlockPos>();
        pending.add(pos.immutable());found.add(pos.immutable());
        while(!pending.isEmpty()&&!bank.truncated){
            var p=pending.remove();bank.watch(level,p);
            for(var side:Direction.values()){
                var next=p.relative(side);bank.watch(level,next);
                if(level.isLoaded(next)&&level.getBlockEntity(next) instanceof ControlRodDriveBlockEntity&&found.add(next)){
                    if(found.size()>MAX_DRIVES){bank.truncated=true;break;}
                    pending.add(next);
                }
            }
        }
        bank.positions=found.stream().sorted().toList();builds++;
        for(var p:bank.positions)cache.put(p,bank);
        while(cache.size()>MAX_CACHED_POSITIONS){var evicted=cache.values().iterator().next();cache.values().removeIf(b->b==evicted);}
        return bank;
    }

    public static void changed(Level level,BlockPos pos){if(!level.isClientSide())level.invalidateCapabilities(pos);}
    @SubscribeEvent public static void unload(LevelEvent.Unload event){if(event.getLevel() instanceof Level l&&!l.isClientSide())CACHE.remove(l);}
    private static void chunkChanged(Level level,long chunk){
        var cache=CACHE.get(level);if(cache==null)return;
        for(var b:new HashSet<>(cache.values()))if(b.chunks.contains(chunk))b.dirty=true;
    }
    @SubscribeEvent public static void availability(ChunkTicketLevelUpdatedEvent event){
        boolean before=ChunkLevel.fullStatus(event.getOldTicketLevel()).isOrAfter(FullChunkStatus.FULL);
        boolean after=ChunkLevel.fullStatus(event.getNewTicketLevel()).isOrAfter(FullChunkStatus.FULL);
        if(before==after)return;
        var level=event.getLevel();long chunk=event.getChunkPos();chunkChanged(level,chunk);
        if(after&&event.getChunkHolder()!=null){
            var server=level.getServer();var holder=event.getChunkHolder();
            server.tell(new net.minecraft.server.TickTask(server.getTickCount(),()->
                    holder.getFullChunkFuture().thenRun(()->server.execute(()->chunkChanged(level,chunk)))));
        }
    }

    public static final class Bank {
        private final WeakReference<Level> world;
        private final Set<Long> chunks=new HashSet<>();
        private final Set<BlockPos> watched=new HashSet<>();
        private final ICapabilityInvalidationListener listener=()->{dirty=true;return false;};
        private List<BlockPos> positions=List.of();
        private boolean dirty,truncated;
        private long balancedAt=Long.MIN_VALUE;
        private Bank(Level level){world=new WeakReference<>(level);}
        private void watch(Level l,BlockPos p){
            chunks.add(net.minecraft.world.level.ChunkPos.asLong(p));
            if(watched.add(p)&&l instanceof ServerLevel server)server.registerCapabilityListener(p,listener);
        }
        private List<ControlRodDriveBlockEntity> members(){
            var l=world.get();if(l==null||dirty||truncated)return List.of();
            var result=new ArrayList<ControlRodDriveBlockEntity>(positions.size());
            for(var p:positions){
                if(!l.isLoaded(p)||!(l.getBlockEntity(p) instanceof ControlRodDriveBlockEntity d)||d.isRemoved()){
                    dirty=true;return List.of();
                }
                result.add(d);
            }
            return result;
        }
        public int size(){return truncated?0:positions.size();}
        private static double stored(ControlRodDriveBlockEntity d,boolean water){return water?d.hardware().getWaterStoredMb():d.hardware().getEnergyStoredFe();}
        private static double capacity(ControlRodDriveBlockEntity d,boolean water){return water?d.hardware().getWaterCapacityMb():d.hardware().getEnergyCapacityFe();}
        private static double sum(List<ControlRodDriveBlockEntity> list,boolean water,boolean maximum){
            double total=0;for(var d:list)total+=maximum?capacity(d,water):stored(d,water);return total;
        }
        private static int whole(double n){return (int)Math.min(Integer.MAX_VALUE,Math.max(0,Math.floor(n)));}
        public int stored(boolean water){return whole(sum(members(),water,false));}
        public int capacity(boolean water){return whole(sum(members(),water,true));}
        /** Whole-unit acceptance, with fractional inventory retained in the actual drives. */
        public int receive(int offered,boolean water,boolean simulate){
            if(offered<=0)return 0;
            var list=members();double stored=sum(list,water,false),capacity=sum(list,water,true);
            int accepted=Math.min(offered,whole(capacity-stored));
            if(!simulate&&accepted>0)distribute(list,water,stored+accepted,capacity);
            return accepted;
        }
        private static void distribute(List<ControlRodDriveBlockEntity> list,boolean water,double total,double capacity){
            if(capacity<=0)return;
            double left=total,space=capacity;
            for(var d:list){
                double cap=capacity(d,water),amount=Math.min(cap,Math.max(0,left*cap/space));
                if(water)d.hardware().setWaterStoredMb(amount);else d.hardware().setEnergyStoredFe(amount);
                left-=amount;space-=cap;d.setChanged();
            }
        }
        /** Once per connected bank per tick; no neighbour search and no diffusion delay across a large grid. */
        public void balance(long tick){
            if(balancedAt==tick)return;
            balancedAt=tick;var list=members();
            if(list.size()<2)return;
            distribute(list,false,sum(list,false,false),sum(list,false,true));
            distribute(list,true,sum(list,true,false),sum(list,true,true));
        }
        public final IFluidHandler water=new IFluidHandler(){
            public int getTanks(){return 1;}
            public FluidStack getFluidInTank(int i){int n=i==0?stored(true):0;return n>0?new FluidStack(Fluids.WATER,n):FluidStack.EMPTY;}
            public int getTankCapacity(int i){return i==0?capacity(true):0;}
            public boolean isFluidValid(int i,FluidStack f){return i==0&&f.is(Fluids.WATER);}
            public int fill(FluidStack f,FluidAction a){return isFluidValid(0,f)?receive(f.getAmount(),true,a.simulate()):0;}
            public FluidStack drain(int n,FluidAction a){return FluidStack.EMPTY;}
            public FluidStack drain(FluidStack f,FluidAction a){return FluidStack.EMPTY;}
        };
    }
}
