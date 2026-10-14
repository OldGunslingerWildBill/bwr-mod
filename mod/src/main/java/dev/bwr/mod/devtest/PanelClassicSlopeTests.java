package dev.bwr.mod.devtest;

import dev.bwr.mod.panel.*;
import dev.bwr.mod.panel.client.QuadSink;
import dev.bwr.mod.panel.model.*;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.*;
import net.neoforged.neoforge.gametest.*;

/** Procedural quads through the actual fit sink and panel world frame. No fixture chunks. */
@GameTestHolder("bwr") @PrefixGameTestTemplate(false)
public final class PanelClassicSlopeTests {
    @GameTest(template="empty")
    public static void classicShapesKeepWorldProportions(GameTestHelper h) {
        for(var type:PanelType.values()) for(var facing:Direction.Plane.HORIZONTAL) {
            var frame=PanelGeometry.frame(type,facing);
            var fit=Placement.fit(8,12,8,12,(float)frame.vLength);
            QuadSink capture=new QuadSink() {
                public void quad(float x0,float y0,float z0,float x1,float y1,float z1,
                        float x2,float y2,float z2,float x3,float y3,float z3,int rgb,int n,boolean e) {
                    double width=distance(frame,x0,y0,z0,x1,y1,z1);
                    double height=distance(frame,x1,y1,z1,x2,y2,z2);
                    h.assertTrue(Math.abs(width-height)<1e-6,"classic square stretched on "+type+"/"+facing);
                    h.assertTrue(Math.abs(width-6*fit.scale/16)<1e-6,"classic scale changed");
                    h.assertTrue(Math.abs((x0+x2)/2-4)<1e-6&&Math.abs((y0+y2)/2-6)<1e-6,"fit not centered");
                }
            };
            new PlacedQuadSink(capture,fit).rect(1,3,6,6,2,0xffffff,true);
            if(frame.vLength>1.01) {
                h.assertTrue(Math.abs(6*frame.vLength/16-6.0/16)>.001,"negative control did not expose old stretch");
            }
        }
        h.succeed();
    }
    private static double distance(PanelGeometry.Frame f,float x,float y,float z,float a,float b,float c) {
        return Math.sqrt(Math.pow(f.x(a,b,c)-f.x(x,y,z),2)+Math.pow(f.y(a,b,c)-f.y(x,y,z),2)+Math.pow(f.z(a,b,c)-f.z(x,y,z),2));
    }
}
