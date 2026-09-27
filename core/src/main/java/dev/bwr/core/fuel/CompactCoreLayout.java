package dev.bwr.core.fuel;

import dev.bwr.core.nodal.RodLatticeMap;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Version 2 game geometry: two logical bundles per block along each axis,
 * with one physical drive per cruciform blade. This is a rounded, symmetric
 * game layout calibrated to 764 assemblies / 185 blades at a 15x15 interior,
 * not a reproduction of a particular plant's loading diagram.
 *
 * The ordinary blade ellipse includes its complete four-bundle groups; a slightly
 * larger fuel ellipse supplies peripheral bundles. Those peripheral bundles
 * have no dedicated blade but still participate in the diffusion solve.
 * Integer comparisons keep the ordinary mask and drive IDs deterministic.
 * RIP configurations use RipCorePacking to fill the available shroud perimeter,
 * including partial outer control cells with one to three occupied positions.
 */
public final class CompactCoreLayout {
    public static final int VERSION = 2;
    public static final int LATTICE_WIDTH = 42;
    public record Drive(int x, int z) {}
    private final List<Drive> drives;
    private final int[] fuelPositions;
    private final RodLatticeMap rodMap;

    public CompactCoreLayout(int width, int depth) {
        this(width,depth,false);
    }

    public CompactCoreLayout(int width,int depth,boolean internalPumps) {
        if (width < 5 || width > 21 || depth < 5 || depth > 21)
            throw new IllegalArgumentException("Interior dimensions must be 5..21");
        if(internalPumps) {
            var packed=RipCorePacking.layout(width,depth);
            drives=packed.drives();
            fuelPositions=packed.fuel().stream().mapToInt(Integer::intValue).toArray();
            int[] rodAt=new int[LATTICE_WIDTH*LATTICE_WIDTH];Arrays.fill(rodAt,-1);
            boolean[] fuel=new boolean[rodAt.length];for(int slot:fuelPositions)fuel[slot]=true;
            for(int rod=0;rod<drives.size();rod++) {
                var drive=drives.get(rod);
                for(int a=0;a<2;a++)for(int b=0;b<2;b++) {
                    int slot=RipCorePacking.slot(drive.x(),drive.z(),a,b,width,depth);
                    if(fuel[slot])rodAt[slot]=rod;
                }
            }
            rodMap=new RodLatticeMap(drives.size(),rodAt.length,rodAt);
            return;
        }
        double scale=1;
        List<Drive> driveList = new ArrayList<>();
        boolean[] fuel = new boolean[LATTICE_WIDTH * LATTICE_WIDTH];
        int[] rodAt = new int[fuel.length];
        Arrays.fill(rodAt, -1);
        int offsetX = LATTICE_WIDTH / 2 - width;
        int offsetZ = LATTICE_WIDTH / 2 - depth;
        for (int x = 0; x < width; x++) for (int z = 0; z < depth; z++) {
            if (!inside(2*x+1-width, 2*z+1-depth, width, depth, 236,scale)) continue;
            int rod = driveList.size();
            driveList.add(new Drive(x, z));
            for (int dx = 0; dx < 2; dx++) for (int dz = 0; dz < 2; dz++) {
                int slot = (offsetZ + 2*z + dz)*LATTICE_WIDTH + offsetX + 2*x + dx;
                fuel[slot] = true;
                rodAt[slot] = rod;
            }
        }
        for (int x = 0; x < 2*width; x++) for (int z = 0; z < 2*depth; z++) {
            if (inside(2*x+1-2*width, 2*z+1-2*depth, width, depth, 970,scale))
                fuel[(offsetZ+z)*LATTICE_WIDTH + offsetX+x] = true;
        }
        drives = List.copyOf(driveList); // x-major, then z: stable physical IDs.
        List<Integer> positions = new ArrayList<>();
        for (int i = 0; i < fuel.length; i++) if (fuel[i]) positions.add(i);
        // Stable centre-outward GUI order; the packet carries explicit indices.
        positions.sort(java.util.Comparator.<Integer>comparingInt(CompactCoreLayout::radiusSquared)
                .thenComparingInt(Integer::intValue));
        fuelPositions = positions.stream().mapToInt(Integer::intValue).toArray();
        rodMap = new RodLatticeMap(drives.size(), fuel.length, rodAt);
    }

    private static boolean inside(int x, int z, int width, int depth, int limit,double scale) {
        return 225L * ((long)x*x*depth*depth + (long)z*z*width*width)
                <= (long)limit*width*width*depth*depth*scale*scale;
    }

    private static int radiusSquared(int slot) {
        int x = 2*(slot % LATTICE_WIDTH)+1-LATTICE_WIDTH;
        int z = 2*(slot / LATTICE_WIDTH)+1-LATTICE_WIDTH;
        return x*x+z*z;
    }

    public List<Drive> drives() { return drives; }
    public int assemblyCount() { return fuelPositions.length; }
    public int[] fuelPositions() { return fuelPositions.clone(); }
    public RodLatticeMap rodMap() { return rodMap; }
}
