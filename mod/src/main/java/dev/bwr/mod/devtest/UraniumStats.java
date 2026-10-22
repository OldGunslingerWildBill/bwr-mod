package dev.bwr.mod.devtest;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Connected-component measurements of uranium blocks, shared by the vein GameTests and the worldgen check.
 *
 * <p>Blocks are grouped into cubes of {@code cell} blocks; two occupied cubes are connected when they touch (faces, edges or
 * corners). With {@code cell == 1} that is exact 26-neighbour connectivity between blocks; with a larger cell, blocks in
 * touching cubes (up to {@code 2 * cell - 1} apart in each axis) count as one body, which is how a vein whose ore sits in
 * pockets with filler between them is still one vein.
 */
final class UraniumStats {

    private UraniumStats() {
    }

    /** One connected body: its block count and its bounding box (inclusive block coordinates). */
    record Component(int blocks, int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        /** The longest horizontal extent: the diagonal of the bounding box seen from above. */
        double horizontalSpan() {
            double dx = maxX - minX + 1, dz = maxZ - minZ + 1;
            return Math.sqrt(dx * dx + dz * dz);
        }

        int height() {
            return maxY - minY + 1;
        }

        @Override
        public String toString() {
            return blocks + " blocks, " + (maxX - minX + 1) + " x " + height() + " x " + (maxZ - minZ + 1)
                    + " (horizontal span " + Math.round(horizontalSpan()) + ")";
        }
    }

    static long pack(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (y & 0xFFF);
    }

    static int unpackX(long p) {
        return (int) (p >> 38);
    }

    static int unpackY(long p) {
        return (int) (p << 52 >> 52);
    }

    static int unpackZ(long p) {
        return (int) (p << 26 >> 38);
    }

    /** The connected bodies of the blocks, largest first. */
    static List<Component> components(Collection<Long> blocks, int cell) {
        Map<Long, List<Long>> cubes = new HashMap<>();
        for (long b : blocks) {
            cubes.computeIfAbsent(pack(Math.floorDiv(unpackX(b), cell), Math.floorDiv(unpackY(b), cell), Math.floorDiv(unpackZ(b), cell)),
                    k -> new ArrayList<>()).add(b);
        }
        java.util.Set<Long> seen = new java.util.HashSet<>();
        List<Component> out = new ArrayList<>();
        for (long start : cubes.keySet()) {
            if (!seen.add(start)) {
                continue;
            }
            int count = 0;
            int x0 = Integer.MAX_VALUE, y0 = Integer.MAX_VALUE, z0 = Integer.MAX_VALUE;
            int x1 = Integer.MIN_VALUE, y1 = Integer.MIN_VALUE, z1 = Integer.MIN_VALUE;
            ArrayDeque<Long> queue = new ArrayDeque<>();
            queue.add(start);
            while (!queue.isEmpty()) {
                long cube = queue.poll();
                for (long b : cubes.get(cube)) {
                    count++;
                    int x = unpackX(b), y = unpackY(b), z = unpackZ(b);
                    x0 = Math.min(x0, x);
                    y0 = Math.min(y0, y);
                    z0 = Math.min(z0, z);
                    x1 = Math.max(x1, x);
                    y1 = Math.max(y1, y);
                    z1 = Math.max(z1, z);
                }
                int cx = unpackX(cube), cy = unpackY(cube), cz = unpackZ(cube);
                for (int dx = -1; dx <= 1; dx++) {
                    for (int dy = -1; dy <= 1; dy++) {
                        for (int dz = -1; dz <= 1; dz++) {
                            long n = pack(cx + dx, cy + dy, cz + dz);
                            if (cubes.containsKey(n) && seen.add(n)) {
                                queue.add(n);
                            }
                        }
                    }
                }
            }
            out.add(new Component(count, x0, y0, z0, x1, y1, z1));
        }
        out.sort(Comparator.comparingInt(Component::blocks).reversed());
        return out;
    }
}
