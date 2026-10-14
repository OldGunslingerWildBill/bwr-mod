package dev.bwr.core.grid;

/** Immutable settings; the device freezes one settings snapshot for each tick. */
public record GridDemand(int mode,double fixedMW,double remoteMW,double minimumMW,double maximumMW,long periodTicks) {
    public GridDemand {
        if(mode<0||mode>2||periodTicks<20||periodTicks>1_728_000)throw new IllegalArgumentException("Invalid demand mode or period");
        for(double value:new double[]{fixedMW,remoteMW,minimumMW,maximumMW})
            if(!Double.isFinite(value)||value<0||value>48000)throw new IllegalArgumentException("Invalid MW");
        if(minimumMW>maximumMW)throw new IllegalArgumentException("Reversed curve");
    }
    public double mw(long phase,double serverMaximumMW) {
        if(!Double.isFinite(serverMaximumMW)||serverMaximumMW<0||serverMaximumMW>48000)throw new IllegalArgumentException("Invalid maximum");
        double value=switch(mode) {
            case 0->fixedMW;
            case 2->remoteMW;
            default->minimumMW+(maximumMW-minimumMW)*(1-Math.cos(2*Math.PI*Math.floorMod(phase,periodTicks)/periodTicks))/2;
        };
        return Math.min(serverMaximumMW,value);
    }
}
