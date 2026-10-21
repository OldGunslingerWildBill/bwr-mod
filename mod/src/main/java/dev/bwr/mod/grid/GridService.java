package dev.bwr.mod.grid;

import java.util.*;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;

/** Shared loaded-device tick; no block ticker, chunk tickets or world scans. */
public final class GridService {
    private static final Set<GridDevice> LOADED=Collections.newSetFromMap(new IdentityHashMap<>());
    private GridService() {}
    public static void register(){
        NeoForge.EVENT_BUS.addListener(GridService::tick);
        NeoForge.EVENT_BUS.addListener(GridService::unloadLevel);
        NeoForge.EVENT_BUS.addListener(GridService::stopped);
    }
    public static void loaded(GridDevice device){LOADED.add(device);}
    public static void unloaded(GridDevice device){LOADED.remove(device);}
    private static void tick(ServerTickEvent.Post event){
        long probe=dev.bwr.mod.TickProbe.start();
        try{for(var device:List.copyOf(LOADED))if(device.live())device.tickDevice();else LOADED.remove(device);}
        finally{dev.bwr.mod.TickProbe.stop("GridService",probe);}
    }
    private static void unloadLevel(LevelEvent.Unload event){LOADED.removeIf(d->d.getLevel()==event.getLevel());}
    private static void stopped(ServerStoppedEvent event){LOADED.clear();}
}
