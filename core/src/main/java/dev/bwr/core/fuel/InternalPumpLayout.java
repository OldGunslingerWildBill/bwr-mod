package dev.bwr.core.fuel;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Construction mounts and the conservative seed mask retained for saved slots.
 * RipCorePacking fills usable perimeter space; AbwrShroudGeometry sets the shell. */
public final class InternalPumpLayout {
    public record Mount(int x,int z) {}
    public record Design(List<Mount> mounts,double coreScale) {
        public Design { mounts=List.copyOf(mounts); }
        /** Circular/elliptical capacity-mask area scales with the diameter ratio squared. */
        public double areaFraction(){return coreScale*coreScale;}
    }
    private record Candidate(Mount mount,Set<Mount> orbit,Set<CompactCoreLayout.Drive> columns,double angle,double radius,double coreScale) {}
    private static final Map<Integer,Design> CACHE=new ConcurrentHashMap<>();
    private static final double PUMP_RADIUS=.55;
    private InternalPumpLayout() {}

    public static Design design(int width,int depth) {
        if(width<5||width>21||depth<5||depth>21)throw new IllegalArgumentException("Interior must be 5..21");
        return CACHE.computeIfAbsent(width*32+depth,k->build(width,depth));
    }
    public static List<Mount> mounts(int width,int depth){return design(width,depth).mounts();}

    private static Design build(int width,int depth) {
        // Match the Blender barrel's inner radii. Pumps keep their round section;
        // their 2x2 construction cells are allowed to contain empty corner space.
        double vx=.448*(width+2),vz=.448*(depth+2);
        var candidates=new ArrayList<Candidate>();
        for(int x=0;x<width-1;x++)for(int z=0;z<depth-1;z++) {
            double dx=x+1-width/2.0,dz=z+1-depth/2.0;
            if(dx<=0||dz<=0)continue;
            var orbit=Set.of(new Mount(x,z),new Mount(width-2-x,z),
                    new Mount(x,depth-2-z),new Mount(width-2-x,depth-2-z));
            var columns=occupiedColumns(orbit);
            double radius=Math.hypot(dx/vx,dz/vz);
            if(columns.size()!=16||radius+(PUMP_RADIUS+.05)/Math.min(vx,vz)>1)continue;
            candidates.add(new Candidate(new Mount(x,z),orbit,columns,Math.atan2(dz/vz,dx/vx),radius,coreScale(orbit,width,depth)));
        }
        List<Mount> mounts=List.of();
        // Four reflected quadrants, with enough spacing for complete 2x2 assemblies.
        // Fewer sites are used when a narrow rectangle cannot fit the desired ring.
        for(int n=Math.max(1,(width+depth)/10);n>=1;n--) {
            var chosen=new HashSet<Mount>();var occupied=new HashSet<CompactCoreLayout.Drive>();
            for(int i=0;i<n;i++) {
                double angle=(i+.5)*Math.PI/(2*n);
                var best=candidates.stream().filter(c->Collections.disjoint(c.columns(),occupied))
                        .min(Comparator.comparingDouble((Candidate c)->4*Math.pow(c.angle()-angle,2)+Math.pow(1-c.radius(),2))
                                .thenComparingInt(c->c.mount().x()).thenComparingInt(c->c.mount().z())).orElse(null);
                if(best==null)break;
                chosen.addAll(best.orbit());occupied.addAll(best.columns());
            }
            if(chosen.size()==4*n) {
                mounts=chosen.stream().sorted(Comparator.comparingInt(Mount::x).thenComparingInt(Mount::z)).toList();break;
            }
        }
        if(mounts.isEmpty())throw new IllegalStateException("No internal pump ring fits "+width+"x"+depth);
        mounts=expandLimitingSites(mounts,candidates,width,depth);
        return new Design(mounts,coreScale(mounts,width,depth));
    }

    /** A one-cell adjustment of the tightest sites recovers core space without
     * changing pump count, pump size or the clearances used by the model. */
    private static List<Mount> expandLimitingSites(List<Mount> mounts,List<Candidate> candidates,int width,int depth) {
        double originalScale=coreScale(mounts,width,depth);
        var selected=new ArrayList<>(candidates.stream().filter(c->mounts.contains(c.mount()))
                .sorted(Comparator.comparingDouble(Candidate::coreScale)
                        .thenComparingInt(c->c.mount().x()).thenComparingInt(c->c.mount().z())).toList());
        var occupied=new HashSet<>(occupiedColumns(mounts));
        for(int i=0;i<selected.size();i++) {
            var current=selected.get(i);
            // Only move bottlenecks. Leave already roomy sites at their saved coordinates.
            if(current.coreScale()>originalScale+1e-9)break;
            var others=new HashSet<>(occupied);others.removeAll(current.columns());
            var best=candidates.stream().filter(c->c.coreScale()>current.coreScale()+1e-9
                            && Math.abs(c.mount().x()-current.mount().x())<=1
                            && Math.abs(c.mount().z()-current.mount().z())<=1
                            && Collections.disjoint(c.columns(),others))
                    .min(Comparator.comparingDouble(Candidate::coreScale).reversed()
                            .thenComparingDouble(c->Math.abs(c.angle()-current.angle()))
                            .thenComparingInt(c->c.mount().x()).thenComparingInt(c->c.mount().z())).orElse(null);
            if(best!=null) {selected.set(i,best);occupied=others;occupied.addAll(best.columns());}
        }
        var expanded=selected.stream().flatMap(c->c.orbit().stream())
                .sorted(Comparator.comparingInt(Mount::x).thenComparingInt(Mount::z)).toList();
        return coreScale(expanded,width,depth)>originalScale+1e-9?expanded:mounts;
    }

    private static double coreScale(Collection<Mount> mounts,int width,int depth) {
        // Reserve the whole annulus, even with only one installed pump. The nearest
        // mounting column limits the central drive ellipse; no individual holes.
        double scale=.95;
        for(var c:occupiedColumns(mounts)) {
            double r=Math.hypot((2*c.x()+1-width)/(double)width,(2*c.z()+1-depth)/(double)depth);
            scale=Math.min(scale,r/Math.sqrt(236.0/225)-1e-6);
        }
        // Retain the conservative construction reservation for saved layout stability.
        // This cylinder no longer dictates the visible shroud's diameter: pump wet
        // ends and the shroud occupy different vertical intervals in the model.
        double sx=.385*(width+2),sz=.385*(depth+2);
        for(var m:mounts)scale=Math.min(scale,
                Math.hypot((m.x()+1-width/2.0)/sx,(m.z()+1-depth/2.0)/sz)
                        -(PUMP_RADIUS+.10)/Math.min(sx,sz));
        if(scale<=0)throw new IllegalStateException("No central core fits inside pump ring");
        return scale;
    }
    public static Set<CompactCoreLayout.Drive> occupiedColumns(Collection<Mount> mounts) {
        var result=new HashSet<CompactCoreLayout.Drive>();
        for(var m:mounts)for(int x=0;x<2;x++)for(int z=0;z<2;z++)result.add(new CompactCoreLayout.Drive(m.x()+x,m.z()+z));
        return Set.copyOf(result);
    }
}
