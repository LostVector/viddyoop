package com.rkuo.shared;

import java.util.ArrayList;
import java.util.List;

/**
 * Created by IntelliJ IDEA.
 * User: rkuo
 * Date: Aug 17, 2010
 * Time: 11:36:59 PM
 * To change this template use File | Settings | File Templates.
 */
public class HBXFileCollectorParams {

    public String               Source;
    public String               Target;
    public List<String>         FileExtensions;
    public List<String>         CollectExcluded; // list of directory patterns to exclude during cleanup
    public List<String>         CleanupExcluded; // list of directory patterns to exclude during cleanup
    public Long                 MaximumCleanupSize; // Files will not be cleaned up if above this size (in bytes)
    public Long                 CleanupChangeDelay; // Files will not be cleaned up if they have changed within this amount of time (in ms)
    public Long                 CleanupDelay; // Files will be cleaned up if last modified is older than this delay (in ms)
    public Long                 Delay; // default to two hours (in ms)

    public Boolean              Cleanup;

    public HBXFileCollectorParams() {
        Source = "";
        Target = "";
        FileExtensions = new ArrayList<String>();
        CollectExcluded = new ArrayList<String>();
        CleanupExcluded = new ArrayList<String>();
        CleanupChangeDelay = 4L * 60L * 60L * 1000L;
        CleanupDelay = 86400000L;

        MaximumCleanupSize = 0L;
        Delay = 0L;
        Cleanup = false;
        return;
    }
}
