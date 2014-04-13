package com.rkuo.shared;

/**
 * Created by IntelliJ IDEA.
 * User: rkuo
 * Date: Aug 15, 2010
 * Time: 12:15:53 AM
 * To change this template use File | Settings | File Templates.
 */
public class HBXJobSubmitterParams {

    public String Hdfs;
    public String JobTracker;
    public Integer JobTrackerPort;

    public String HadoopInput;
    public String HadoopOutput;
    public String HadoopLogs;

    public String PreprocessorFilename;
    public String PreprocessorClass;
    public String PreprocessorConfiguration;

    public String MapReduceFilename;
    public String MapReduceClass;
    public String MapReduceConfiguration;

    public String WakeOnLan;
    public String TaskTrackers;

    public HBXJobSubmitterParams() {

        // hadoop processor settings ... need to be moved
        HadoopInput = "";
        HadoopOutput = "";
        HadoopLogs = "";

        // main settings
        Hdfs = "";
        JobTracker = "";
        JobTrackerPort = 8021;

        PreprocessorFilename = "";
        PreprocessorClass = "";
        PreprocessorConfiguration = "";

        MapReduceFilename = "";
        MapReduceClass = "";
        MapReduceConfiguration = "";

        WakeOnLan = "";
        TaskTrackers = "";
        return;
    }
}
