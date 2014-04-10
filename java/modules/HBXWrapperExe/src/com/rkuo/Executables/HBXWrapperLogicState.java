package com.rkuo.Executables;

import com.rkuo.threading.RKEvent;

import java.io.File;

/**
 * Created by IntelliJ IDEA.
 * User: rkuo
 * Date: Oct 6, 2010
 * Time: 11:18:55 PM
 * To change this template use File | Settings | File Templates.
 */
public class HBXWrapperLogicState {
        public String                  handbrakeExe, mkvInfoExe, mkvExtractExe, ssaConverterExe;
        public File                    fLocalHandBrake32, fLocalHandBrake64;
        public String                  Username, Password, Hostname;
        public String                  ResourcesUsername, ResourcesPassword, ResourcesHostname;
        public File                    fSource, fTarget, fStats;
        public File                    fTempTarget;
        public File                    fLocalSource, fLocalTarget, fLocalStats;
        public File                    fFailed;
        public RKEvent Abort;

    public HBXWrapperLogicState() {
        Abort = null;
        return;
    }
}
