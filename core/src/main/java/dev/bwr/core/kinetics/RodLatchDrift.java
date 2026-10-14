package dev.bwr.core.kinetics;

/** Passive outward motion after a collet fails to catch. No commands, trips or interlocks. */
public final class RodLatchDrift {
    private final boolean[] failed, unlatched;
    private long randomState=0x61c8864680b583ebL;
    public RodLatchDrift(int rods) { failed=new boolean[rods];unlatched=new boolean[rods]; }
    public boolean failed(int rod) { return failed[rod]; }
    public void setFailed(int rod,boolean value) { failed[rod]=value;if(value)unlatched[rod]=true; }
    public void seed(long seed) { randomState=seed; }
    public double advance(int rod,double position,boolean driven,boolean scram,double hazard,double rate,double dt) {
        if(scram) return position; // hydraulic insertion force still works with a defective collet
        if(!failed[rod]&&!driven&&position<24&&hazard>0&&uniform() < -Math.expm1(-hazard*dt))setFailed(rod,true);
        if(driven) { if(!failed[rod]&&Math.abs(position-Math.rint(position))<1e-9)unlatched[rod]=false;return position; }
        if(!unlatched[rod])return position;
        // A repaired collet catches the next outward notch, not an imaginary latch between notches.
        double stop=failed[rod]?24:Math.ceil(position-1e-9);
        double next=Math.min(stop,position+Math.max(0,rate)*dt);
        if(!failed[rod]&&next==stop)unlatched[rod]=false;
        return next;
    }
    private double uniform() {
        randomState+=0x9e3779b97f4a7c15L;long z=randomState;
        z=(z^(z>>>30))*0xbf58476d1ce4e5b9L;z=(z^(z>>>27))*0x94d049bb133111ebL;
        return ((z^(z>>>31))>>>11)*0x1.0p-53;
    }
    public int[] snapshot() {
        int[] out=new int[3+failed.length];out[0]=1;out[1]=(int)randomState;out[2]=(int)(randomState>>>32);
        for(int r=0;r<failed.length;r++)out[3+r]=(failed[r]?1:0)|(unlatched[r]?2:0);return out;
    }
    public void restore(int[] saved) {
        if(saved.length==0) {java.util.Arrays.fill(failed,false);java.util.Arrays.fill(unlatched,false);return;}
        if(saved.length!=3+failed.length||saved[0]!=1)throw new IllegalArgumentException("invalid rod collet state");
        randomState=((long)saved[2]<<32)|Integer.toUnsignedLong(saved[1]);
        for(int r=0;r<failed.length;r++) {failed[r]=(saved[3+r]&1)!=0;unlatched[r]=(saved[3+r]&2)!=0;}
    }
}
