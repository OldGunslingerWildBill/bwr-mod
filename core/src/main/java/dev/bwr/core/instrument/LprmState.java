package dev.bwr.core.instrument;

import java.util.List;

/** Immutable rack wiring, per-chamber hardware and fixed channel calibration; empty is a legacy save. */
public record LprmState(List<LprmLayout.StringLocation> strings, List<Integer> detectorFlags,
                        List<Double> referenceMeans, List<Double> gains, List<Double> gamma) {
    public static final LprmState EMPTY=new LprmState(List.of(),List.of(),List.of(),List.of(),List.of());
    public LprmState {
        strings=List.copyOf(strings); detectorFlags=List.copyOf(detectorFlags);
        referenceMeans=List.copyOf(referenceMeans); gains=List.copyOf(gains); gamma=List.copyOf(gamma);
        if(!strings.isEmpty()&&(detectorFlags.size()!=strings.size()*4||referenceMeans.size()!=6||gains.size()!=6||gamma.size()!=6))
            throw new IllegalArgumentException("incomplete LPRM rack state");
    }
    /** Version 1 numeric snapshot fits ReactorState's deliberately primitive persistence boundary. */
    public double[] encode() {
        if(strings.isEmpty())return new double[0];
        double[] out=new double[2+strings.size()*7+18];out[0]=1;out[1]=strings.size();int at=2;
        for(int i=0;i<strings.size();i++) {
            var p=strings.get(i);out[at++]=p.x();out[at++]=p.y();out[at++]=p.latticePosition();
            for(int l=0;l<4;l++)out[at++]=detectorFlags.get(i*4+l);
        }
        for(var list:List.of(referenceMeans,gains,gamma))for(double v:list)out[at++]=v;
        return out;
    }
    public static LprmState decode(double[] data) {
        if(data==null||data.length==0)return EMPTY;
        if(data.length<2||data[0]!=1||data[1]!=(int)data[1]||data[1]<0||data[1]>4096||data.length!=2+7*(int)data[1]+18)
            throw new IllegalArgumentException("invalid LPRM snapshot");
        var strings=new java.util.ArrayList<LprmLayout.StringLocation>();var flags=new java.util.ArrayList<Integer>();int at=2;
        for(int i=0;i<(int)data[1];i++) {
            strings.add(new LprmLayout.StringLocation((int)data[at++],(int)data[at++],(int)data[at++]));
            for(int l=0;l<4;l++)flags.add((int)data[at++]);
        }
        var refs=new java.util.ArrayList<Double>();var gains=new java.util.ArrayList<Double>();var gamma=new java.util.ArrayList<Double>();
        for(var list:List.of(refs,gains,gamma))for(int c=0;c<6;c++)list.add(data[at++]);
        return new LprmState(strings,flags,refs,gains,gamma);
    }
}
