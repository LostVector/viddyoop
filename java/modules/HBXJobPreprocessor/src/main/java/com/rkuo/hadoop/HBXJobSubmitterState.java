package com.rkuo.hadoop;

import java.io.File;

/**
 * Created by IntelliJ IDEA.
 * User: rkuo
 * Date: Nov 23, 2010
 * Time: 6:38:49 PM
 * To change this template use File | Settings | File Templates.
 */
public class HBXJobSubmitterState {
    public File   fSourceNewDir;
    public File   fSourceReprocessDir;
    public File   fRemuxDir;

    public File   fArchive720Dir;
    public File   fArchive1080Dir;

    public File   fSourceIntermediateDir;
    public File   fSourceFailedDir;
    public File   fTargetDir;
    public File   fLogsDir;

    public HBXJobSubmitterState() {
        return;
    }
}
