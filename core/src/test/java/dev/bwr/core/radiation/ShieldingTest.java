package dev.bwr.core.radiation;

import dev.bwr.core.Check;

import java.util.ArrayList;
import java.util.List;

/**
 * Block shielding arithmetic (RADIATION.md, "Shielding"): half-value layers, the path through the block grid, and water
 * counted once.
 */
public final class ShieldingTest {

    private ShieldingTest() {
    }

    private record Cell(int x, int y, int z, double length) {
    }

    private static List<Cell> cells(double ax, double ay, double az, double bx, double by, double bz) {
        List<Cell> out = new ArrayList<>();
        Shielding.march(ax, ay, az, bx, by, bz, (x, y, z, l) -> {
            out.add(new Cell(x, y, z, l));
            return true;
        });
        return out;
    }

    private static double total(List<Cell> cells) {
        return cells.stream().mapToDouble(Cell::length).sum();
    }

    public static void test01_halfValueLayersMatchTheirDefinitionAndTheDoseModel() {
        Check.relative(0.5, Shielding.slabTransmission(Shielding.LEAD_HVL_CM, Shielding.LEAD_HVL_CM), 1e-12, "one HVL of lead");
        Check.relative(0.25, Shielding.slabTransmission(2 * Shielding.CONCRETE_HVL_CM, Shielding.CONCRETE_HVL_CM), 1e-12, "two HVLs of concrete");
        Check.relative(0.125, Shielding.slabTransmission(3 * 2.0, 2.0), 1e-12, "three HVLs of anything");
        // A block of water is the pool's water: the same coefficient as the dose model
        Check.relative(DoseModel.waterTransmission(1.0), Shielding.slabTransmission(100.0, Shielding.WATER_HVL_CM), 1e-12,
                "a metre of water through HVLs equals the dose model's water");
        // the table (RADIATION.md, "Shielding"): broad beam at about 1 MeV, and the narrow-beam NIST figures beside it
        Check.exactly(1.0, Shielding.LEAD_HVL_CM, "lead HVL cm");
        Check.exactly(2.5, Shielding.STEEL_HVL_CM, "steel HVL cm");
        Check.exactly(6.0, Shielding.CONCRETE_HVL_CM, "concrete HVL cm");
        Check.inRange(9.7, 9.9, Shielding.WATER_HVL_CM, "water HVL cm");
        Check.inRange(0.85, 0.87, Shielding.NIST_LEAD_HVL_CM, "NIST lead HVL cm");
        Check.inRange(1.46, 1.48, Shielding.NIST_IRON_HVL_CM, "NIST iron HVL cm");
        Check.inRange(4.6, 4.7, Shielding.NIST_CONCRETE_HVL_CM, "NIST concrete HVL cm");
        Check.isTrue(Shielding.LEAD_HVL_CM >= Shielding.NIST_LEAD_HVL_CM && Shielding.STEEL_HVL_CM >= Shielding.NIST_IRON_HVL_CM
                && Shielding.CONCRETE_HVL_CM >= Shielding.NIST_CONCRETE_HVL_CM, "broad beam is never more shielding than narrow beam");
        Check.isTrue(Shielding.SHIELD_PLUG_HVL_CM < Shielding.CONCRETE_HVL_CM && Shielding.HEAVY_SHIELD_PLUG_HVL_CM < Shielding.SHIELD_PLUG_HVL_CM
                && Shielding.ULTRA_SHIELD_PLUG_HVL_CM < Shielding.HEAVY_SHIELD_PLUG_HVL_CM, "the shield plug tiers shield progressively more");
        // a glass block barely matters: about an eighth off per block
        Check.inRange(0.85, 0.92, Shielding.slabTransmission(100.0 * Shielding.GLASS_FILL, Shielding.GLASS_HVL_CM), "one glass block");
        Check.relative(Shielding.LEAD_HVL_CM, Shielding.hvlCm(Shielding.attenuationPerM(Shielding.LEAD_HVL_CM)), 1e-12, "round trip");
        Check.exactly(1.0, Shielding.transmission(0.0), "nothing in the way");
        Check.exactly(1.0, Shielding.transmission(Double.NaN), "NaN is nothing");
        Check.exactly(0.0, Shielding.transmission(Shielding.MAX_OPTICAL_DEPTH), "past the limit, exactly zero");
        Check.exactly(0.0, Shielding.attenuationPerM(0.0), "no HVL, no shielding");
        Check.exactly(0.0, Shielding.attenuationPerM(-1.0), "negative HVL, no shielding");
        Check.relative(10.0, Shielding.leadEquivalentCm(Shielding.attenuationPerM(Shielding.LEAD_HVL_CM) * 0.10), 1e-12, "10 cm of lead is 10 cm lead-equivalent");
    }

    public static void test02_oneTwoAndThreeHalvingsGiveAHalfAQuarterAndAnEighth() {
        double mu = Shielding.attenuationPerM(25.0); // a 25 cm HVL: four per block
        for (int n = 1; n <= 3; n++) {
            double depth = 0.0;
            for (Cell c : cells(0.5, 0.5, 0.5, 0.5, 0.5, 0.5 + 0.25 * n)) {
                depth += mu * c.length();
            }
            Check.relative(Math.pow(0.5, n), Shielding.transmission(depth), 1e-12, n + " HVL");
        }
    }

    public static void test03_aStraightRayGivesEachBlockItsLength() {
        List<Cell> c = cells(0.5, 0.5, 0.5, 3.5, 0.5, 0.5);
        Check.exactly(4, c.size(), "cells along x");
        Check.relative(0.5, c.get(0).length(), 1e-12, "half of the first");
        Check.relative(1.0, c.get(1).length(), 1e-12, "the second whole");
        Check.relative(1.0, c.get(2).length(), 1e-12, "the third whole");
        Check.relative(0.5, c.get(3).length(), 1e-12, "half of the last");
        Check.relative(3.0, total(c), 1e-12, "lengths add up");
        Check.exactly(0, c.get(0).x(), "starts in its own cell");
        Check.exactly(3, c.get(3).x(), "ends in the dose point's cell");
        // backwards and negative coordinates
        List<Cell> back = cells(-0.5, 2.5, -7.25, -3.5, 2.5, -7.25);
        Check.exactly(4, back.size(), "cells backwards");
        Check.exactly(-1, back.get(0).x(), "first cell");
        Check.exactly(-4, back.get(3).x(), "last cell");
        Check.relative(3.0, total(back), 1e-12, "lengths add up backwards");
    }

    public static void test04_partialAndDiagonalBlocksAreMeasuredNotCounted() {
        // 45 degrees in the xz plane through a 1 m thick wall at x = 2: the path in the wall is sqrt(2)
        double inWall = 0.0;
        for (Cell c : cells(0.5, 0.5, 0.5, 4.5, 0.5, 4.5)) {
            if (c.x() == 2) {
                inWall += c.length();
            }
        }
        Check.relative(Math.sqrt(2.0), inWall, 1e-9, "diagonal through one block thickness");
        // a ray that only clips a corner of a block
        double clip = 0.0;
        for (Cell c : cells(0.5, 0.5, 0.5, 1.5, 0.5, 1.7)) {
            if (c.x() == 0 && c.z() == 1) {
                clip += c.length();
            }
        }
        // the ray enters z = 1 at t = 0.5/1.2 (x = 0.917) and leaves through x = 1 at t = 0.5 (z = 1.1)
        Check.relative((0.5 - 0.5 / 1.2) * Math.sqrt(1.0 + 1.44), clip, 1e-9, "a corner clipped: 8 cm of the block, not a whole one");
        // a full 3D diagonal: the lengths add up to the distance, and every step is to a face neighbour
        List<Cell> d = cells(0.1, 0.2, 0.3, 7.7, -5.4, 11.9);
        Check.relative(Math.sqrt(7.6 * 7.6 + 5.6 * 5.6 + 11.6 * 11.6), total(d), 1e-9, "3D lengths add up");
        for (int i = 1; i < d.size(); i++) {
            int step = Math.abs(d.get(i).x() - d.get(i - 1).x()) + Math.abs(d.get(i).y() - d.get(i - 1).y())
                    + Math.abs(d.get(i).z() - d.get(i - 1).z());
            Check.exactly(1, step, "face neighbour at " + i);
        }
        Check.exactly(7 + 6 + 11 + 1, d.size(), "cell count of a 3D diagonal");
        // exactly through an edge: no cell gets a share it does not have, and the total holds
        List<Cell> edge = cells(0.5, 0.5, 0.5, 2.5, 2.5, 0.5);
        Check.relative(Math.sqrt(8.0), total(edge), 1e-12, "through an edge");
        for (Cell c : edge) {
            Check.isTrue(c.length() > 0.0, "no empty cell is reported along the edge path: %s", c);
        }
        // along a face: the ray belongs to one side
        List<Cell> face = cells(0.5, 1.0, 0.5, 3.5, 1.0, 0.5);
        Check.relative(3.0, total(face), 1e-12, "along a face");
        Check.isTrue(face.stream().allMatch(c -> c.y() == 1), "a ray on a face is counted once, on one side: %s", face);
        // a point source in the middle of its own cell to a point in the same cell
        Check.exactly(1, cells(0.2, 0.2, 0.2, 0.8, 0.8, 0.8).size(), "one cell");
        Check.exactly(1, cells(3.3, 3.3, 3.3, 3.3, 3.3, 3.3).size(), "zero length");
        // stopping early
        int[] seen = {0};
        int visited = Shielding.march(0.5, 0.5, 0.5, 50.5, 0.5, 0.5, (x, y, z, l) -> ++seen[0] < 5);
        Check.exactly(5, visited, "a visitor can stop the march");
    }

    public static void test05_waterIsCountedOnceAndAsTheDoseModelsWater() {
        Check.relative(DoseModel.WATER_ATTENUATION_PER_M, Shielding.cellAttenuationPerM(0.0, 1.0, true), 1e-12, "a water block is the dose model's water");
        Check.exactly(0.0, Shielding.cellAttenuationPerM(0.0, 1.0, false), "a source that has its water already gets none from the blocks");
        double lead = Shielding.attenuationPerM(Shielding.LEAD_HVL_CM);
        Check.relative(lead, Shielding.cellAttenuationPerM(lead, 0.5, false), 1e-12, "a waterlogged lead slab's water is not counted for such a source");
        Check.relative(lead + 0.5 * DoseModel.WATER_ATTENUATION_PER_M, Shielding.cellAttenuationPerM(lead, 0.5, true), 1e-12,
                "and is counted for any other");
        Check.exactly(0.0, Shielding.cellAttenuationPerM(Double.NaN, 0.0, true), "NaN solid counts nothing");
        Check.exactly(0.0, Shielding.cellAttenuationPerM(-3.0, -1.0, true), "negative counts nothing");
        // the pool's own rule and the block march agree for a column of water straight over a source
        double depth = 0.0;
        for (Cell c : cells(4.5, 10.0, 4.5, 4.5, 13.0, 4.5)) {
            depth += Shielding.cellAttenuationPerM(0.0, 1.0, true) * c.length();
        }
        Check.relative(DoseModel.waterTransmission(3.0), Shielding.transmission(depth), 1e-12, "3 m of water blocks = 3 m of pool water");
    }
}
