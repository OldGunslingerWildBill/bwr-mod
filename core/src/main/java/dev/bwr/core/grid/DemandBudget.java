package dev.bwr.core.grid;

/** One shared multi-call FE allowance per prepared tick; no saved reservations. */
public final class DemandBudget {
    private long tick=Long.MIN_VALUE,remaining;
    private double carry;
    public void beginTick(long now,double demandMW,boolean eligible) {
        if(now==tick)return;
        validate(demandMW);
        double amount=eligible?EnergyUnits.fePerTick(demandMW)+carry:0;
        remaining=(long)Math.floor(amount);carry=eligible?amount-remaining:0;tick=now;
    }
    /** A query at a not-yet-prepared tick computes acceptance without changing carry. */
    public int accept(long now,double demandMW,boolean eligible,int offered,boolean simulate) {
        validate(demandMW);
        if(offered<=0||!eligible)return 0;
        long available=now==tick?remaining:(long)Math.floor(EnergyUnits.fePerTick(demandMW)+carry);
        int accepted=(int)Math.min(offered,available);
        if(!simulate){beginTick(now,demandMW,true);remaining-=accepted;}
        return accepted;
    }
    private static void validate(double mw){if(!Double.isFinite(mw)||mw<0||mw>48000)throw new IllegalArgumentException("Invalid MW");}
    public double carry(){return carry;}
    public long remaining(){return remaining;}
    public void restore(double fraction){carry=Double.isFinite(fraction)&&fraction>=0&&fraction<1?fraction:0;tick=Long.MIN_VALUE;remaining=0;}
}
