package dev.bwr.mod.devtest;

import dev.bwr.mod.panel.client.QuadSink;
import dev.bwr.mod.panel.model.*;
import net.minecraft.gametest.framework.*;
import net.neoforged.neoforge.gametest.*;
import java.nio.charset.StandardCharsets;

/** Pure exported-asset checks, with no world fixture positions. */
@GameTestHolder("bwr") @PrefixGameTestTemplate(false)
public final class PanelLampAppearanceTests {
    private static BakedPanelModel model(String name) {
        try (var in = PanelLampAppearanceTests.class.getResourceAsStream("/assets/bwr/models/panel/"+name+".json")) {
            return PanelModelParser.parse(name,new String(in.readAllBytes(),StandardCharsets.UTF_8));
        } catch (Exception e) { throw new AssertionError(name,e); }
    }
    /**
     * The project owner's call (2026-10-15): a lit lens is the player's chosen colour across the whole lens, with no
     * hot spot and no shading, so a red lamp always reads red. (This replaced Part B's white crown, which left a red dome
     * mostly white.) The unlit lens is the same colour dimmed, and never whitened.
     */
    @GameTest(template="empty")
    public static void litDomeIsTheChosenColourEdgeToEdge(GameTestHelper h) {
        var m=model("light_dome_smooth"); var lens=m.part("lens");
        int[] lit=new int[lens.faceCount], off=new int[lens.faceCount]; int[] i={0};
        QuadSink sink=(a,b,c,d,e,f,g,j,k,l,n,o,rgb,normal,emissive)->lit[i[0]++]=rgb;
        PartDraw.emit(sink,lens,Placement.fit(m,4,4),0,0,0x00CC66,1,true,new float[PartDraw.SCRATCH]);
        i[0]=0;
        QuadSink dark=(a,b,c,d,e,f,g,j,k,l,n,o,rgb,normal,emissive)->off[i[0]++]=rgb;
        PartDraw.emit(dark,lens,Placement.fit(m,4,4),0,0,0x00CC66,0.22f,false,new float[PartDraw.SCRATCH]);
        int crown=0,shoulder=0;
        for(int f=0;f<lens.faceCount;f++) {
            if(lens.normal(f,2)>.97) crown++;
            if(lens.normal(f,2)<.5) shoulder++;
            h.assertTrue((lit[f]&0xFFFFFF)==0x00CC66,"lit lens face "+f+" is "+Integer.toHexString(lit[f]&0xFFFFFF)+", not the chosen colour");
            h.assertTrue(((off[f]>>16)&255)==0,"unlit lens retains a hot spot");
        }
        h.assertTrue(crown>0&&shoulder>0,"asset must exercise crown and shoulder"); h.succeed();
    }
    @GameTest(template="empty")
    public static void travellingAmberCapOwnsItsLegend(GameTestHelper h) {
        var legend=model("pb_guarded_yellow").label("legend");
        h.assertTrue(legend.onLamp,"travelling cap was excluded from legend surface lookup");
        h.assertTrue(Legends.lampInk(legend.surfaceRgb,true)==PanelModelParser.INK_DARK,"amber lit cap needs dark ink");
        h.assertTrue(Legends.lampInk(legend.surfaceRgb,false)==PanelModelParser.INK_LIGHT,"dim cap needs light ink");
        h.assertTrue(Legends.lampInk(0x000033,true)==PanelModelParser.INK_LIGHT,"dark configured cap needs light ink even when lit");
        h.succeed();
    }
}
