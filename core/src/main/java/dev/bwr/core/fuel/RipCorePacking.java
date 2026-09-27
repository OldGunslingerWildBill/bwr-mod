package dev.bwr.core.fuel;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Packs real fuel slots inside the ABWR shroud. Shared by simulation and artwork.
 * Construction mount coordinates stay fixed; every complete four-bundle group
 * must have an unobstructed drive. Partial peripheral groups get a blade where
 * its wings and lower guide tube also fit. No per-frame/world work is involved. */
public final class RipCorePacking {
    public record Packing(double pitchX,double pitchZ,List<Integer> fuel,List<CompactCoreLayout.Drive> drives) {
        public Packing {fuel=List.copyOf(fuel);drives=List.copyOf(drives);}
    }
    private static final Map<Integer,Packing> CACHE=new ConcurrentHashMap<>();
    public static Packing layout(int width,int depth) {
        if(width<5||width>21||depth<5||depth>21)throw new IllegalArgumentException("Interior must be 5..21");
        return CACHE.computeIfAbsent(width*32+depth,k->build(width,depth));
    }
    private static Packing build(int w,int d) {
        var base=new CompactCoreLayout(w,d);
        var blocked=InternalPumpLayout.occupiedColumns(InternalPumpLayout.mounts(w,d));
        var seed=reservedFuel(w,d);
        double px=.35*(w+2)/w,pz=.35*(d+2)/d,radial=0;
        for(int slot:base.fuelPositions())radial=Math.max(radial,corner(slot,px,pz,w,d));
        // Start at the ordinary core pitch, never compress fuel below that pitch.
        double fit=Math.min(1,.362/radial);
        for(var drive:blocked) {
            double outer=0;
            for(int a=0;a<2;a++)for(int b=0;b<2;b++)
                outer=Math.max(outer,corner(slot(drive.x(),drive.z(),a,b,w,d),px,pz,w,d));
            // A pump footprint must not acquire a complete unrodded fuel cell.
            // Keep a small numeric margin across double/float render conversions.
            fit=Math.max(fit,AbwrShroudGeometry.FUEL_RADIUS/outer*(1+1e-6));
        }
        double seedEdge=0;for(int slot:seed)seedEdge=Math.max(seedEdge,corner(slot,px,pz,w,d));
        fit=Math.min(fit,AbwrShroudGeometry.FUEL_RADIUS/seedEdge);
        px*=fit;pz*=fit;
        var allowed=new HashSet<>(seed);var candidates=new HashSet<Integer>();
        for(int slot:base.fuelPositions())if(corner(slot,px,pz,w,d)<=AbwrShroudGeometry.FUEL_RADIUS+1e-12)candidates.add(slot);
        // Rectangular lattice steps can put an existing cell corner farther out
        // than a neighbouring pump's partial group. Keep those saved slots, then
        // fill symmetric perimeter orbits without completing a blocked cell or
        // leaving a hole. The common square layouts accept the whole circle.
        for(int slot:base.fuelPositions()) {
            if(!candidates.contains(slot)||allowed.contains(slot))continue;
            var orbit=orbit(slot);
            if(!candidates.containsAll(orbit))continue;
            var proposed=new HashSet<>(allowed);proposed.addAll(orbit);
            if(validOutline(proposed,blocked,w,d))allowed=proposed;
        }
        var fuel=new ArrayList<Integer>();for(int slot:base.fuelPositions())if(allowed.contains(slot))fuel.add(slot);
        var drives=new ArrayList<CompactCoreLayout.Drive>();
        for(int x=0;x<w;x++)for(int z=0;z<d;z++) {
            var drive=new CompactCoreLayout.Drive(x,z);
            if(blocked.contains(drive)||!bladeFits(x,z,px,pz,w,d))continue;
            boolean present=false;
            for(int a=0;a<2;a++)for(int b=0;b<2;b++)present|=allowed.contains(slot(x,z,a,b,w,d));
            if(present)drives.add(drive);
        }
        return new Packing(px,pz,fuel,drives);
    }
    private static Set<Integer> reservedFuel(int w,int d) {
        double q=InternalPumpLayout.design(w,d).coreScale();var seed=new HashSet<Integer>();
        for(int x=0;x<w;x++)for(int z=0;z<d;z++)if(inside(2*x+1-w,2*z+1-d,w,d,236,q))
            for(int a=0;a<2;a++)for(int b=0;b<2;b++)seed.add(slot(x,z,a,b,w,d));
        for(int x=0;x<2*w;x++)for(int z=0;z<2*d;z++)if(inside(2*x+1-2*w,2*z+1-2*d,w,d,970,q))
            seed.add((21-d+z)*42+21-w+x);
        return seed;
    }
    private static boolean inside(int x,int z,int w,int d,int limit,double q) {
        return 225L*((long)x*x*d*d+(long)z*z*w*w)<=(long)limit*w*w*d*d*q*q;
    }
    public static Set<Integer> orbit(int slot) {
        int x=slot%42,z=slot/42;
        return Set.of(z*42+x,z*42+41-x,(41-z)*42+x,(41-z)*42+41-x);
    }
    public static boolean validOutline(Set<Integer> fuel,Set<CompactCoreLayout.Drive> blocked,int w,int d) {
        for(var drive:blocked) {
            boolean full=true;
            for(int a=0;a<2;a++)for(int b=0;b<2;b++)full&=fuel.contains(slot(drive.x(),drive.z(),a,b,w,d));
            if(full)return false;
        }
        for(int axis=0;axis<2;axis++)for(int row=0;row<42;row++) {
            int first=42,last=-1;
            for(int c=0;c<42;c++)if(fuel.contains(axis==0?row*42+c:c*42+row)){first=Math.min(first,c);last=c;}
            for(int c=first;c<=last;c++)if(!fuel.contains(axis==0?row*42+c:c*42+row))return false;
        }
        return true;
    }
    static int slot(int x,int z,int a,int b,int w,int d) {
        return (21-d+2*z+b)*42+21-w+2*x+a;
    }
    public static double corner(int slot,double px,double pz,int w,int d) {
        return Math.hypot((Math.abs(slot%42+.5-21)+.5)*px/(w+2),
                (Math.abs(slot/42+.5-21)+.5)*pz/(d+2));
    }
    public static boolean bladeFits(int x,int z,double px,double pz,int w,int d) {
        double cx=Math.abs(2*x+1-w),cz=Math.abs(2*z+1-d);
        // Blender blade: +/- .86 pitch, half-thickness .025. Guide radius .30.
        double outer=Math.max(Math.hypot((cx+.86)*px/(w+2),(cz+.025)*pz/(d+2)),
                Math.hypot((cx+.025)*px/(w+2),(cz+.86)*pz/(d+2)));
        outer=Math.max(outer,Math.hypot((cx+.30)*px/(w+2),(cz+.30)*pz/(d+2)));
        return outer<AbwrShroudGeometry.INNER_RADIUS-.001;
    }
    private RipCorePacking() {}
}
