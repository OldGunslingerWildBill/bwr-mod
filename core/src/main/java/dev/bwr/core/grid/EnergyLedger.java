package dev.bwr.core.grid;

import java.util.Arrays;

/** Completed-tick measurements. Queries never advance the clock. */
public final class EnergyLedger {
    private final long[] buckets=new long[1200];
    private long tick=Long.MIN_VALUE,current,total,last;
    private int cursor,samples;
    private boolean saturated;
    public void beginTick(long now) {
        if(tick==now)return;
        if(tick!=Long.MIN_VALUE) {
            publish(current);
            // Only the loaded caller advances time. Rate windows retain idle zeros.
            long gap=now>tick?now-tick:1;
            for(long n=1;n<Math.min(gap,1201);n++)publish(0);
        }
        tick=now;current=0;
    }
    private void publish(long amount) {
        buckets[cursor]=amount;cursor=(cursor+1)%buckets.length;
        samples=Math.min(buckets.length,samples+1);last=amount;
    }
    public void record(long fe) {
        if(fe<0)throw new IllegalArgumentException("Negative transfer");
        if(tick==Long.MIN_VALUE)throw new IllegalStateException("Tick not prepared");
        if(Long.MAX_VALUE-total<fe)saturated=true;
        total=EnergyUnits.add(total,fe);current=EnergyUnits.add(current,fe);
    }
    public long totalFE(){return total;}
    public boolean saturated(){return saturated;}
    public long lastTickFE(){return last;}
    public int sampleTicks(){return samples;}
    public double averageFE(int ticks) {
        if(ticks<1||ticks>buckets.length)throw new IllegalArgumentException("Invalid window");
        double sum=0;
        for(int n=0;n<ticks;n++)sum+=buckets[Math.floorMod(cursor-1-n,buckets.length)];
        return sum/ticks;
    }
    public void restore(long lifetimeFE,boolean overflow) {
        total=Math.max(0,lifetimeFE);saturated=overflow;
        resetRates();
    }
    public void resetRates(){Arrays.fill(buckets,0);tick=Long.MIN_VALUE;current=last=0;cursor=samples=0;}
}
