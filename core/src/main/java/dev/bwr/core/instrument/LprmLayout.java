package dev.bwr.core.instrument;

import dev.bwr.core.fuel.CoreLoading;
import dev.bwr.core.nodal.RodLatticeMap;
import java.util.*;

/** Four axial chambers per water-gap string. Coordinates use the GE display grid.
 * The spatially interleaved six-channel assignment is a typical game layout, not plant wiring.
 * See REFERENCE-DATA.md, Part B neutron instrumentation. */
public final class LprmLayout {
    public record StringLocation(int x, int y, int latticePosition) {}
    private final List<StringLocation> strings;
    private final int[] inputIndex, channelIndex, counts = new int[6];
    public LprmLayout(List<StringLocation> strings) {
        this.strings=List.copyOf(strings);inputIndex=new int[strings.size()*4];channelIndex=new int[inputIndex.length];
        // Stratify by radius, then snake across six channels. Simple row-order modulo
        // correlated a channel with particular radial/axial regions as rods moved.
        double cx=strings.stream().mapToInt(StringLocation::x).average().orElse(0);
        double cy=strings.stream().mapToInt(StringLocation::y).average().orElse(0);
        var order=new ArrayList<Integer>();for(int i=0;i<strings.size();i++)order.add(i);
        order.sort(Comparator.<Integer>comparingDouble(i -> {
            var p=strings.get(i);return Math.pow(p.x()-cx,2)+Math.pow(p.y()-cy,2);
        }).thenComparingDouble(i -> Math.atan2(strings.get(i).y()-cy,strings.get(i).x()-cx)));
        for(int rank=0;rank<order.size();rank++) {
            int s=order.get(rank), radialChannel=(rank/6)%2==0?rank%6:5-rank%6;
            for(int l=0;l<4;l++)channelIndex[s*4+l]=(radialChannel+l)%6;
        }
        // Keep the established first-string rack addresses: A-D are channels 1-4,
        // input 1. This permutes channel names without changing any sampling group.
        int origin=channelIndex.length==0?0:channelIndex[0];
        for(int i=0;i<channelIndex.length;i++)channelIndex[i]=(channelIndex[i]-origin+6)%6;
        for(int s=0;s<strings.size();s++)for(int l=0;l<4;l++)inputIndex[s*4+l]=counts[channelIndex[s*4+l]]++;
    }
    public List<StringLocation> strings() { return strings; }
    public int count() { return strings.size(); }
    public int channel(int string, int level) { check(string,level); return channelIndex[string*4+level]; }
    public int input(int string, int level) { check(string,level); return inputIndex[string*4+level]; }
    public int inputs(int channel) { return counts[channel]; }
    public static double heightFraction(int level) {
        if(level<0||level>3) throw new IllegalArgumentException("LPRM level must be A-D");
        return (level+.5)/4;
    }
    private void check(int s,int l) {
        if(s<0||s>=count()||l<0||l>=4) throw new IllegalArgumentException("unknown LPRM string/level "+s+"/"+l);
    }
    public static LprmLayout defaultLayout(CoreLoading loading, RodLatticeMap rods) {
        int[] cols=new int[rods.rodCount()], rows=cols.clone();
        Arrays.fill(cols,-1); Arrays.fill(rows,-1);
        for(int r=0;r<cols.length;r++) if(rods.positionCountOfRod(r)>0) {
            int p=rods.positionOfRod(r,0); cols[r]=(p%loading.latticeWidth())/2; rows[r]=(p/loading.latticeWidth())/2;
        }
        return fromRodGrid(loading,rods,cols,rows);
    }
    public static LprmLayout fromRodGrid(CoreLoading loading,RodLatticeMap rods,int[] cols,int[] rows) {
        if(cols.length!=rods.rodCount()||rows.length!=cols.length) throw new IllegalArgumentException("one cell per rod");
        int minC=Integer.MAX_VALUE,minR=minC,maxC=-1,maxR=-1;
        for(int r=0;r<cols.length;r++) if(cols[r]>=0&&rows[r]>=0) {
            minC=Math.min(minC,cols[r]);minR=Math.min(minR,rows[r]);maxC=Math.max(maxC,cols[r]);maxR=Math.max(maxR,rows[r]);
        }
        if(maxC<0) return new LprmLayout(List.of());
        int w=maxC-minC+1,h=maxR-minR+1,sx=Math.max(0,7-(w-1)/2),sy=Math.max(0,7-(h-1)/2);
        int[] cells=new int[w*h];Arrays.fill(cells,-1);
        for(int r=0;r<cols.length;r++) if(cols[r]>=0&&rows[r]>=0) cells[(rows[r]-minR)*w+cols[r]-minC]=r;
        List<StringLocation> out=new ArrayList<>();
        for(int ly=3+4*(h-1+sy)+4;ly>=1;ly--) if(ly%8==1) {
            for(int lx=8;lx<=2+4*(w-1+sx)+4;lx+=8) {
                int left=lx/4-1-sx,top=h-1-((ly-5)/4-sy+1);
                if(left<0||top<0||left+1>=w||top+1>=h) continue;
                int[] near={cells[top*w+left],cells[top*w+left+1],cells[(top+1)*w+left],cells[(top+1)*w+left+1]};
                if(Arrays.stream(near).anyMatch(r->r<0)) continue;
                // Sample the fuel node nearest the water gap between these four control cells.
                double x=0,y=0;int n=0;
                for(int r:near) for(int p:rods.positionsOfRod(r)) { x+=p%loading.latticeWidth();y+=p/loading.latticeWidth();n++; }
                if(n==0) continue; x/=n;y/=n;
                int nearest=-1;double distance=Double.POSITIVE_INFINITY;
                for(int r:near) for(int p:rods.positionsOfRod(r)) {
                    double dx=p%loading.latticeWidth()-x,dy=p/loading.latticeWidth()-y,d=dx*dx+dy*dy;
                    if(d<distance) { distance=d;nearest=p; }
                }
                if(nearest>=0) out.add(new StringLocation(lx,ly,nearest));
            }
        }
        // Tiny teaching cores still have one physical string, not 144 fictitious chambers.
        if(out.isEmpty()) for(int p=0;p<loading.positionCount();p++) if(loading.isOccupied(p)) {
            out.add(new StringLocation(32,33,p));break;
        }
        return new LprmLayout(out);
    }
}
