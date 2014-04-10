package com.rkuo.Executables;

import com.rkuo.handbrake.IHandBrakeExeCallback;
import com.rkuo.logging.RKLog;

/**
 * Created by IntelliJ IDEA.
 * User: rkuo
 * Date: 8/17/12
 * Time: 2:00 AM
 * To change this template use File | Settings | File Templates.
 */
public class HBXXgridHandBrakeExeCallback implements IHandBrakeExeCallback {

    protected int nLastProgress;
    public HBXXgridHandBrakeExeCallback() {
        nLastProgress = -1;
        return;
    }

    public void KeepAlive(String line) {
        return;
    }

    public void Process(String line, double n) {
        int nProgress = (int)n;
        if( nProgress > nLastProgress  ) {
            nLastProgress = nProgress;
            RKLog.println(String.format("<xgrid>{control = statusUpdate; percentDone = %d.0; }</xgrid>%s", n, line));
        }

        return;
    }
}
