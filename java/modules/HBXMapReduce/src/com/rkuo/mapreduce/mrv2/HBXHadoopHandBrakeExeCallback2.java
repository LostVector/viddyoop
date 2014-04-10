package com.rkuo.mapreduce.mrv2;

import com.rkuo.handbrake.IHandBrakeExeCallback;
import org.apache.hadoop.mapreduce.*;

public class HBXHadoopHandBrakeExeCallback2 implements IHandBrakeExeCallback {

    protected Reducer.Context c;
    protected int nLastProgress;

    public HBXHadoopHandBrakeExeCallback2(Reducer.Context c) {
        this.c = c;
        nLastProgress = -1;
        return;
    }

    public void KeepAlive(String line) {
        c.progress();
        return;
    }

    public void Process(String line, double n) {
        c.progress();

        int nProgress = (int)n;
        if( nProgress > nLastProgress ) {
            nLastProgress = nProgress;
            System.out.format("%s\n", line);
            c.setStatus(line);
        }

        return;
    }
}
