package dev.bwr.core.nodal;

import dev.bwr.core.fuel.CoreLoading;
import dev.bwr.core.thermal.PressureVessel;

/**
 * The coarse mesh the diffusion solve runs on: one cell per fuel assembly per
 * axial node, with the topology flattened into plain int arrays.
 *
 * <h2>Why coarse mesh</h2>
 * {@code SPEC.md} section 1.3 rejects ray tracing and Monte Carlo outright —
 * too slow, and statistically noisy in a way that would make power output
 * visibly jitter. A nodal diffusion mesh has neither problem: it is a fixed
 * sparse linear system, it is deterministic, and at
 * {@value #DEFAULT_AXIAL_NODES} axial nodes over a real BWR/6 lattice it is
 * about nineteen thousand cells, which is nothing once per second.
 *
 * <h2>Geometry</h2>
 * A node is a cube. Assembly pitch is {@value #DEFAULT_ASSEMBLY_PITCH_CM} cm
 * (six inches, the BWR bundle pitch) and the active fuel height of
 * {@link PressureVessel#ACTIVE_FUEL_HEIGHT_IN} inches over
 * {@value #DEFAULT_AXIAL_NODES} axial nodes is 15.24 cm as well, so the default
 * mesh happens to be cubic. Nothing depends on that; the radial and axial
 * spacings are carried separately throughout.
 *
 * <h2>Empty lattice positions are not cells</h2>
 * A real core is a circle inscribed in the square lattice, and the corners hold
 * nothing. Unoccupied positions get no cell at all: they appear to the solve as
 * a missing neighbour, which is to say a boundary face, which is to say water.
 * That is the physically right answer — the reflector is exactly what is out
 * there — and it means the mesh cost tracks the fuel actually loaded rather
 * than the bounding square.
 *
 * <p>Immutable once built, and rebuilt only when the occupancy pattern changes.
 * {@link #occupancySignature()} is what a solver compares to decide that.
 */
public final class NodalMesh {

    /** Face index: the neighbour one lattice column toward lower column numbers. */
    public static final int FACE_COLUMN_MINUS = 0;
    /** Face index: the neighbour one lattice column toward higher column numbers. */
    public static final int FACE_COLUMN_PLUS = 1;
    /** Face index: the neighbour one lattice row toward lower row numbers. */
    public static final int FACE_ROW_MINUS = 2;
    /** Face index: the neighbour one lattice row toward higher row numbers. */
    public static final int FACE_ROW_PLUS = 3;
    /** Face index: the axial node below, toward the bottom of active fuel. */
    public static final int FACE_BELOW = 4;
    /** Face index: the axial node above, toward the top of active fuel. */
    public static final int FACE_ABOVE = 5;

    /** Faces per node. Six, because a node is a box. */
    public static final int FACES = 6;

    /** BWR fuel bundle pitch, centimetres — six inches, centre to centre. */
    public static final double DEFAULT_ASSEMBLY_PITCH_CM = 15.24;

    /** Axial nodes per assembly. Twenty-five, as {@code SPEC.md} section 1.3 sizes it. */
    public static final int DEFAULT_AXIAL_NODES = 25;

    /** Centimetres per inch, for converting the plant's inch-denominated heights. */
    private static final double CM_PER_INCH = 2.54;

    private final int latticeWidth;
    private final int positionCount;
    private final int axialNodes;
    private final int nodeCount;

    private final double pitchCm;
    private final double nodeHeightCm;

    /** Node index for each (position, axial) pair, or -1 where no fuel is loaded. */
    private final int[] nodeIndexOf;
    /** Lattice position of each node. */
    private final int[] positionOfNode;
    /** Axial index of each node, 0 at the bottom of active fuel. */
    private final int[] axialOfNode;
    /** Six neighbour node indices per node, -1 for a face on the core boundary. */
    private final int[] neighbours;
    /** Hash of the occupancy pattern and geometry, so a rebuild can be detected without one. */
    private final long occupancySignature;

    private NodalMesh(int latticeWidth, int axialNodes, double pitchCm, double activeHeightCm,
                      boolean[] occupied, long occupancySignature) {
        this.latticeWidth = latticeWidth;
        this.positionCount = latticeWidth * latticeWidth;
        this.axialNodes = axialNodes;
        this.pitchCm = pitchCm;
        this.nodeHeightCm = activeHeightCm / axialNodes;
        this.occupancySignature = occupancySignature;

        this.nodeIndexOf = new int[positionCount * axialNodes];
        int count = 0;
        for (int position = 0; position < positionCount; position++) {
            for (int axial = 0; axial < axialNodes; axial++) {
                nodeIndexOf[position * axialNodes + axial] = occupied[position] ? count++ : -1;
            }
        }
        this.nodeCount = count;

        this.positionOfNode = new int[count];
        this.axialOfNode = new int[count];
        for (int position = 0; position < positionCount; position++) {
            for (int axial = 0; axial < axialNodes; axial++) {
                int node = nodeIndexOf[position * axialNodes + axial];
                if (node >= 0) {
                    positionOfNode[node] = position;
                    axialOfNode[node] = axial;
                }
            }
        }

        this.neighbours = new int[count * FACES];
        for (int node = 0; node < count; node++) {
            int position = positionOfNode[node];
            int axial = axialOfNode[node];
            int column = position % latticeWidth;
            int row = position / latticeWidth;
            neighbours[node * FACES + FACE_COLUMN_MINUS] = radialNeighbour(column - 1, row, axial);
            neighbours[node * FACES + FACE_COLUMN_PLUS] = radialNeighbour(column + 1, row, axial);
            neighbours[node * FACES + FACE_ROW_MINUS] = radialNeighbour(column, row - 1, axial);
            neighbours[node * FACES + FACE_ROW_PLUS] = radialNeighbour(column, row + 1, axial);
            neighbours[node * FACES + FACE_BELOW] =
                    axial > 0 ? nodeIndexOf[position * axialNodes + axial - 1] : -1;
            neighbours[node * FACES + FACE_ABOVE] =
                    axial < axialNodes - 1 ? nodeIndexOf[position * axialNodes + axial + 1] : -1;
        }
    }

    private int radialNeighbour(int column, int row, int axial) {
        if (column < 0 || column >= latticeWidth || row < 0 || row >= latticeWidth) {
            return -1;
        }
        return nodeIndexOf[(row * latticeWidth + column) * axialNodes + axial];
    }

    /**
     * Builds the mesh for whatever is currently loaded, at the default pitch and
     * the plant's real active fuel height.
     *
     * @param loading    the core; only its occupancy pattern is read
     * @param axialNodes axial cells per assembly, at least 1
     */
    public static NodalMesh forLoading(CoreLoading loading, int axialNodes) {
        return forLoading(loading, axialNodes, DEFAULT_ASSEMBLY_PITCH_CM,
                PressureVessel.ACTIVE_FUEL_HEIGHT_IN * CM_PER_INCH);
    }

    /** As {@link #forLoading(CoreLoading, int)}, with the geometry stated explicitly. */
    public static NodalMesh forLoading(CoreLoading loading, int axialNodes,
                                       double pitchCm, double activeHeightCm) {
        if (loading == null) {
            throw new IllegalArgumentException("loading must not be null");
        }
        if (axialNodes < 1) {
            throw new IllegalArgumentException("axialNodes must be at least 1, was " + axialNodes);
        }
        if (!(pitchCm > 0.0) || !(activeHeightCm > 0.0)) {
            throw new IllegalArgumentException(
                    "pitch and active height must be positive, were " + pitchCm + " and " + activeHeightCm);
        }
        int positions = loading.positionCount();
        boolean[] occupied = new boolean[positions];
        for (int position = 0; position < positions; position++) {
            occupied[position] = loading.isOccupied(position);
        }
        return new NodalMesh(loading.latticeWidth(), axialNodes, pitchCm, activeHeightCm,
                occupied, signatureOf(loading, axialNodes, pitchCm, activeHeightCm));
    }

    /**
     * Hash of everything the mesh depends on. Cheap enough to recompute before
     * every solve, which is the point: it decides whether the topology arrays
     * can be reused, and rebuilding them to find that out would defeat the
     * purpose. Allocates nothing.
     */
    public static long signatureOf(CoreLoading loading, int axialNodes,
                                   double pitchCm, double activeHeightCm) {
        long signature = 1125899906842597L;
        signature = signature * 31 + loading.latticeWidth();
        signature = signature * 31 + axialNodes;
        signature = signature * 31 + Double.doubleToLongBits(pitchCm);
        signature = signature * 31 + Double.doubleToLongBits(activeHeightCm);
        int positions = loading.positionCount();
        for (int position = 0; position < positions; position++) {
            signature = signature * 31 + (loading.isOccupied(position) ? 1 : 0);
        }
        return signature;
    }

    /**
     * True when this mesh still describes the given core. Compares occupancy and
     * geometry, not fuel state — burning a bundle does not change the mesh, and
     * moving one from a loaded position to an empty one does.
     */
    public boolean matches(CoreLoading loading, int axialNodes) {
        return matches(loading, axialNodes, pitchCm,
                PressureVessel.ACTIVE_FUEL_HEIGHT_IN * CM_PER_INCH);
    }

    /** @see #matches(CoreLoading, int) */
    public boolean matches(CoreLoading loading, int axialNodes, double pitchCm, double activeHeightCm) {
        return signatureOf(loading, axialNodes, pitchCm, activeHeightCm) == occupancySignature;
    }

    // ---------------------------------------------------------------
    // Geometry
    // ---------------------------------------------------------------

    public int latticeWidth() {
        return latticeWidth;
    }

    /** Lattice positions in the square, occupied or not. */
    public int positionCount() {
        return positionCount;
    }

    public int axialNodes() {
        return axialNodes;
    }

    /** Cells in the mesh: occupied positions times axial nodes. */
    public int nodeCount() {
        return nodeCount;
    }

    public double pitchCm() {
        return pitchCm;
    }

    public double nodeHeightCm() {
        return nodeHeightCm;
    }

    /** Volume of one cell, cubic centimetres. */
    public double nodeVolumeCm3() {
        return pitchCm * pitchCm * nodeHeightCm;
    }

    /** Area of one face, square centimetres. Radial and axial faces differ. */
    public double faceAreaCm2(int face) {
        return (face == FACE_BELOW || face == FACE_ABOVE) ? pitchCm * pitchCm : pitchCm * nodeHeightCm;
    }

    /** Centre-to-centre spacing across one face, centimetres. */
    public double faceSpacingCm(int face) {
        return (face == FACE_BELOW || face == FACE_ABOVE) ? nodeHeightCm : pitchCm;
    }

    /** Height above the bottom of active fuel of an axial node's centre, as a fraction of core height. */
    public double axialCentreHeightFraction(int axial) {
        return (axial + 0.5) / axialNodes;
    }

    // ---------------------------------------------------------------
    // Topology
    // ---------------------------------------------------------------

    /** Node index for a lattice position and axial node, or -1 if that position holds no fuel. */
    public int nodeAt(int position, int axial) {
        if (position < 0 || position >= positionCount || axial < 0 || axial >= axialNodes) {
            return -1;
        }
        return nodeIndexOf[position * axialNodes + axial];
    }

    public int positionOfNode(int node) {
        return positionOfNode[node];
    }

    public int axialOfNode(int node) {
        return axialOfNode[node];
    }

    /**
     * Neighbour across one face, or -1 when there is none — the lattice edge, or
     * an unloaded position, which for the solve are the same thing: water.
     */
    public int neighbour(int node, int face) {
        return neighbours[node * FACES + face];
    }

    /** Signature of the occupancy pattern and geometry this mesh was built for. */
    public long occupancySignature() {
        return occupancySignature;
    }

    @Override
    public String toString() {
        return String.format("NodalMesh[%dx%d lattice x %d axial, %d cells, %.2f x %.2f cm]",
                latticeWidth, latticeWidth, axialNodes, nodeCount, pitchCm, nodeHeightCm);
    }
}
