package com.rkuo.mapreduce.mrv1;

import com.rkuo.handbrake.IHandBrakeExeCallback;
import com.rkuo.logging.RKLog;
import org.apache.hadoop.mapred.Reporter;

/**
 * Created by IntelliJ IDEA.
 * User: rkuo
 * Date: 8/17/12
 * Time: 2:00 AM
 * To change this template use File | Settings | File Templates.
 */
public class HBXHadoopHandBrakeExeCallback implements IHandBrakeExeCallback {

    protected Reporter reporter;
    protected int nLastProgress;

    public HBXHadoopHandBrakeExeCallback(Reporter r) {
        reporter = r;
        nLastProgress = -1;
        return;
    }

    public void KeepAlive(String line) {
        reporter.progress();
        return;
    }

    public void Process(String line, double n) {
        reporter.progress();

        int nProgress = (int)n;
        if( nProgress > nLastProgress ) {
            nLastProgress = nProgress;
            System.out.format("%s\n", line);
            reporter.setStatus(line);
        }

        return;
    }
}
