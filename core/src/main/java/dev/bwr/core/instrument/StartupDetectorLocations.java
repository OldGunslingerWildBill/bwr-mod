package dev.bwr.core.instrument;

import java.util.*;

/** Typical symmetric 4-SRM/8-IRM positions; not a plant-specific placement drawing.
 * Tube positions are chosen beside actual LPRM strings, so core and FCD share the same coordinates. */
public final class StartupDetectorLocations {
    private StartupDetectorLocations() {}
    public record Detector(String range,int channel,int x,int y,int latticePosition,int stringIndex) {
        public String label() { return range+"-"+(char)('A'+channel); }
    }
    public static List<Detector> typical(LprmLayout layout) {
        var strings=layout.strings();if(strings.isEmpty())return List.of();
        int minX=Integer.MAX_VALUE,maxX=Integer.MIN_VALUE,minY=minX,maxY=maxX;
        for(var s:strings) {minX=Math.min(minX,s.x());maxX=Math.max(maxX,s.x());minY=Math.min(minY,s.y());maxY=Math.max(maxY,s.y());}
        double cx=(minX+maxX)/2.0,cy=(minY+maxY)/2.0,rx=(maxX-minX)/2.0,ry=(maxY-minY)/2.0;
        var out=new ArrayList<Detector>();var used=new HashSet<Integer>();
        for(int range=0;range<2;range++)for(int channel=0;channel<(range==0?4:8);channel++) {
            double angle=Math.toRadians(range==0?45+90*channel:45*channel),radius=range==0?.85:.55;
            double x=cx+radius*rx*Math.cos(angle),y=cy+radius*ry*Math.sin(angle);
            int best=-1;double distance=Double.POSITIVE_INFINITY;
            for(int i=0;i<strings.size();i++) {
                if(used.size()<strings.size()&&used.contains(i))continue;
                var s=strings.get(i);double d=Math.pow(s.x()-x,2)+Math.pow(s.y()-y,2);
                if(d<distance) {distance=d;best=i;}
            }
            var s=strings.get(best);used.add(best);
            out.add(new Detector(range==0?"SRM":"IRM",channel,s.x(),s.y(),s.latticePosition(),best));
        }
        return List.copyOf(out);
    }
}
