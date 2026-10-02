package com.rkuo.shared;

import java.util.ArrayList;

/**
 * Created by IntelliJ IDEA.
 * User: rkuo
 * Date: Aug 14, 2010
 * Time: 6:43:44 PM
 * To change this template use File | Settings | File Templates.
 */
public class HBXImporterParams {

    public String   Username;
    public String   Password;
    public String   Hostname;
    public String   SSHEndpoint;

    public String   Source;
    public String   Target;
    public String   FinalTarget;
    public ArrayList<String>   FileExtensions;
    public long     MinimumTargetDiskSpace;
    public Boolean  RenameForIFlicks;

    public HBXImporterParams() {
        Username = "";
        Password = "";
        Hostname = "";
        SSHEndpoint = "target";
        Source = "";
        Target = "";
        FinalTarget = "";
        FileExtensions = new ArrayList<String>();
        MinimumTargetDiskSpace = -1;
        RenameForIFlicks = false;
        return;
    }
}
