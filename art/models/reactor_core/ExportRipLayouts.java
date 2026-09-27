import dev.bwr.core.fuel.*;
import java.nio.file.*;
import java.util.*;

/** Run after :core:compileJava: java -cp core/build/classes/java/main
 * art/models/reactor_core/ExportRipLayouts.java art/models/reactor_core/rip_layouts.json
 * Blender consumes the same authoritative masks and mounting sites as the game. */
class ExportRipLayouts {
    public static void main(String[] args)throws Exception {
        var entries=new ArrayList<String>();
        for(int[] size:new int[][]{{5,5},{15,15},{21,21},{7,11}}) {
            int w=size[0],d=size[1];var design=InternalPumpLayout.design(w,d);
            var core=new CompactCoreLayout(w,d,true);
            var packed=RipCorePacking.layout(w,d);
            String mounts=design.mounts().stream().map(m->"["+m.x()+","+m.z()+"]").reduce((a,b)->a+","+b).orElse("");
            String drives=core.drives().stream().map(m->"["+m.x()+","+m.z()+"]").reduce((a,b)->a+","+b).orElse("");
            entries.add("{\"width\":"+w+",\"depth\":"+d+",\"scale\":"+design.coreScale()
                    +",\"pitch_x\":"+packed.pitchX()+",\"pitch_z\":"+packed.pitchZ()
                    +",\"mounts\":["+mounts+"],\"drives\":["+drives+"],\"fuel\":"+Arrays.toString(core.fuelPositions())+"}");
            System.out.println((w+2)+"x"+(d+2)+": "+core.assemblyCount()+" assemblies, "+core.drives().size()+" drives, "+design.mounts().size()+" pumps");
        }
        Files.writeString(Path.of(args[0]),"[\n"+String.join(",\n",entries)+"\n]\n");
        Files.writeString(Path.of(args[0]).resolveSibling("abwr_dimensions.json"),
                "{\n  \"source\": \"GE ABWR DCD Tier 2, Table 5.3-2\",\n"
                +"  \"vessel_id_mm\": "+AbwrShroudGeometry.VESSEL_ID_MM+",\n"
                +"  \"shroud_od_mm\": "+AbwrShroudGeometry.SHROUD_OD_MM+",\n"
                +"  \"shroud_wall_mm\": "+AbwrShroudGeometry.SHROUD_WALL_MM+",\n"
                +"  \"outer_radius\": "+AbwrShroudGeometry.OUTER_RADIUS+",\n"
                +"  \"inner_radius\": "+AbwrShroudGeometry.INNER_RADIUS+",\n"
                +"  \"rim_radius\": "+AbwrShroudGeometry.RIM_RADIUS+",\n"
                +"  \"fuel_radius\": "+AbwrShroudGeometry.FUEL_RADIUS+"\n}\n");
    }
}
