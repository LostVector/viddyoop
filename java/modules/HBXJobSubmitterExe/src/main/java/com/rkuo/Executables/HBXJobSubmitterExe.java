package com.rkuo.Executables;

import java.io.*;
import java.io.File;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.*;

import com.rkuo.hadoop.HBXMapReduceBase;
import com.rkuo.logging.RKLog;
import com.rkuo.shared.HBXJobPreprocessorBase;
import com.rkuo.shared.HBXJobSubmitterParams;
import com.rkuo.shared.HBXOriginalStats;
import com.rkuo.util.*;

// Watches a input directory
// Moves the files to a processing directory
// Submits an xgrid job for each file
public class HBXJobSubmitterExe {

//    private static long     RESULTS_SCAN_DELAY_MS = 60 * 60 * 1000;  // 60 min (in ms)
    private static long     SCAN_DELAY_MS = 5 * 1000;          // 5 sec (in ms)
    private static long     SIZE_DELAY_MS = 60 * 1000;          // 60 sec (in ms)
    private static long     EVENT_LOOP_DELAY_MS = 1000;     // 1 sec (in ms)
    private static long     WARNING_DELAY = 60L * 60L * 1000L;

    public static void main( String[] args ) {
        CommandLineParser   clp;
        HBXJobSubmitterParams hbxjsp;
        Map<String,String> mapSettings;
        Map<String,RkTracker> mapTrackers;
        ShutdownThread      shutdownThread;
        long                lastRun;
        long                lastWarning;
        long                elapsedJobCreation;
        long                elapsedSize;
        HBXJobPreprocessorBase preprocessor;
        HBXMapReduceBase    mr;
        boolean             br;

        lastRun = Long.MIN_VALUE;
        lastWarning = Long.MAX_VALUE;
        elapsedJobCreation = SCAN_DELAY_MS;
        elapsedSize = SIZE_DELAY_MS;

        try {
            shutdownThread = new ShutdownThread();
            Runtime.getRuntime().addShutdownHook( shutdownThread );
        }
        catch( Exception ex ) {
            RKLog.Log( "Could not add shutdown hook." );
            return;
        }

        clp = new CommandLineParser();

        clp.Parse(args);

        hbxjsp = GetHBXJobSubmitterParams( clp );
        if( hbxjsp == null ) {
            RKLog.Log( "Missing required parameters." );
            return;
        }
/*
        RKLog.Log( "Source (New files): %s", hbxjsp.SourceNew );
        RKLog.Log( "Source (Reprocess files): %s", hbxjsp.SourceReprocess );
        RKLog.Log( "Remuxed files: %s", hbxjsp.Remux );
        RKLog.Log( "Source Archive 720 (original movie sources are placed here for archival): %s", hbxjsp.SourceArchive720 );
        RKLog.Log( "Source Archive 1080 (original movie sources are placed here for archival): %s", hbxjsp.SourceArchive1080 );
        RKLog.Log( "Failed Source (sources moved here if preprocessing failed): %s", hbxjsp.SourceFailed );
        RKLog.Log( "Scratch: %s", hbxjsp.Scratch );

        RKLog.Log( "Hadoop input (sources are moved to be processed from here): %s", hbxjsp.SourceIntermediate );
        RKLog.Log( "Hadoop output: %s", hbxjsp.Target );
        RKLog.Log( "Hadoop logs: %s", hbxjsp.Logs );

        RKLog.Log( "Local HandBrake x64: %s", hbxjsp.LocalHandBrake);
        RKLog.Log( "Local mkvinfo: %s", hbxjsp.LocalMkvInfo );
        RKLog.Log( "Local mkvextract: %s", hbxjsp.LocalMkvExtract );
        RKLog.Log( "Local mkvmerge: %s", hbxjsp.LocalMkvMerge );
        RKLog.Log( "Local aften: %s", hbxjsp.LocalAften );
        RKLog.Log( "Local dcadec: %s", hbxjsp.LocalDcaDec );
 */
        mapTrackers = ClusterManager.GetTrackers(hbxjsp.TaskTrackers);
        RKLog.Log("GetTrackers loaded %d tracker(s).", mapTrackers.size());

        preprocessor = LoadJobPreprocessor(hbxjsp.PreprocessorFilename,hbxjsp.PreprocessorClass);
        if( preprocessor == null ) {
            RKLog.Log("LoadJobPreprocessor for %s failed.",hbxjsp.PreprocessorFilename);
            return;
        }

        mapSettings = preprocessor.LoadSettings(hbxjsp.PreprocessorConfiguration);
        if( mapSettings != null ) {
            br = preprocessor.Configure(mapSettings);
            if( br == false ) {
                RKLog.Log("preprocessor.Configure failed.");
            }
        }

        mr = LoadJobProcessor(hbxjsp.MapReduceFilename,hbxjsp.MapReduceClass);
        if( mr == null ) {
            RKLog.Log("LoadJobProcessor for %s failed.",hbxjsp.MapReduceFilename);
            return;
        }

        while( true ) {
            long now;
            Date nowDate;

            now = System.currentTimeMillis();
            nowDate = new Date();

            synchronized( shutdownThread ) {
                if( shutdownThread.isShutdown() == true ) {
                    break;
                }
            }

            try {
                Thread.sleep(EVENT_LOOP_DELAY_MS);
            }
            catch( InterruptedException iex ) {
                // do nothing?
                break;
            }

            elapsedJobCreation += EVENT_LOOP_DELAY_MS;
            elapsedSize += EVENT_LOOP_DELAY_MS;

            // we want to scan the directory but only process one file per scan
            // this lets us process any high priority job first relatively quickly
            // We do not have a FIFO model, so there is a presumption that the job submitter
            // can process files faster than it receives them
            if( elapsedJobCreation >= SCAN_DELAY_MS ) {
                do {
                    String selectedFile;
                    Map<String,String> mapProcessed;
                    File fSelected;

                    elapsedJobCreation = 0;

                    selectedFile = preprocessor.Select(); // chooses a file to preprocess
                    if( selectedFile == null ) {
                        break;
                    }

                    fSelected = new File(selectedFile);

                    mapProcessed = preprocessor.Execute(selectedFile);
                    if( mapProcessed != null ) {
                        String processedFile = mapProcessed.get("output");
                        File fProcessed = new File(processedFile);

                        Long duration;
                        Integer xRes, yRes;

                        duration = Long.parseLong(mapProcessed.get("duration"));
                        xRes = Integer.parseInt(mapProcessed.get("x"));
                        yRes = Integer.parseInt(mapProcessed.get("y"));

                        Map<String,String> mapMRSettings = mr.LoadSettings(hbxjsp.MapReduceConfiguration);
                        mapMRSettings.put("source",fProcessed.getAbsolutePath());
                        mapMRSettings.put("target",FileUtils.PathCombine(new File(hbxjsp.HadoopOutput).getAbsolutePath(), FileUtils.getNameWithoutExtension(fProcessed.getName()) + ".m4v"));
                        mapMRSettings.put("stats",FileUtils.PathCombine(new File(hbxjsp.HadoopLogs).getAbsolutePath(), fProcessed.getName() + ".encodingstats.xml"));
                        mapMRSettings.put("hdfs",hbxjsp.Hdfs);
                        mapMRSettings.put("jobtracker_hostname",hbxjsp.JobTracker);
                        mapMRSettings.put("jobtracker_port",hbxjsp.JobTrackerPort.toString());
                        mr.Submit(mapMRSettings);

                        String originalStatsFilename = FileUtils.PathCombine(new File(hbxjsp.HadoopLogs).getAbsolutePath(), fProcessed.getName() + ".originalstats.xml");
                        if( fSelected.getAbsolutePath().compareToIgnoreCase(fProcessed.getAbsolutePath()) == 0 ) {
                            WriteOriginalStats(nowDate, fProcessed, duration, xRes, yRes, false, originalStatsFilename);
                        } else {
                            WriteOriginalStats(nowDate, fProcessed, duration, xRes, yRes, true, originalStatsFilename);
                        }

                        fSelected.delete();
                        elapsedJobCreation = SCAN_DELAY_MS;
                    }
                }
                while( false );
                /*
                HBXJobSubmitterState    state;
                boolean                 br;
                boolean                 bLogWarnings;

                elapsedJobCreation = 0;
                bLogWarnings = false;

                if( lastWarning - now > WARNING_DELAY ) {
                    bLogWarnings = true;
                }

                state = AreDirectoriesValid( hbxjsp, true );
                if( state == null ) {
                    if( bLogWarnings == true ) {
                        lastWarning = now;
                    }
                }
                else {
                    // process the new directory
                    br = TrySubmittingSingleJob( mr, hbxjsp, state );
                    if( br == true ) {
                        // the following will cause the next loop iteration to scan immediately
                        // if we processed one job, there are likely to be more waiting in the queue and we want to
                        // blow through them as quickly as possible
                        elapsedJobCreation = SCAN_DELAY_MS;
                    }
                }
                */
            }

            if( elapsedSize >= SIZE_DELAY_MS ) {
                // wake or shutdown extra clients depending on the size of the job queue
                elapsedSize = 0;
                ClusterManager.SizeCluster(hbxjsp.JobTracker, hbxjsp.JobTrackerPort, ClusterSizingStrategy.MANUALON, hbxjsp.WakeOnLan, mapTrackers);
            }

            lastRun = now;
        }

        RKLog.Log( "Exiting..." );
        return;
    }

    protected static HBXJobPreprocessorBase LoadJobPreprocessor(String jarFilename, String className) {
        File fJar;
        URLClassLoader loader;
        Class classToLoad;
        Object o;
        HBXJobPreprocessorBase preprocessor;

        fJar = new File(jarFilename);

        try {
            URL[] aURL;

            aURL = new URL[1];
            aURL[0] = fJar.toURI().toURL();
            loader = new URLClassLoader(aURL, HBXJobSubmitterExe.class.getClassLoader());
        }
        catch( MalformedURLException muex ) {
            RKLog.Log("MalformedURLException - %s", muex.getMessage());
            return null;
        }

        try {
            classToLoad = loader.loadClass(className);
        }
        catch( ClassNotFoundException cnfex ) {
            RKLog.Log("ClassNotFoundException - %s", cnfex.getMessage());
            return null;
        }

        try {
            o = classToLoad.newInstance();
        }
        catch( InstantiationException iex ) {
            RKLog.Log("InstantiationException - %s", iex.getMessage());
            return null;
        }
        catch( IllegalAccessException iaex ) {
            RKLog.Log("IllegalAccessException - %s", iaex.getMessage());
            return null;
        }

        if( o instanceof HBXJobPreprocessorBase == false ) {
            return null;
        }

        preprocessor = (HBXJobPreprocessorBase) o;

        return preprocessor;
    }

    protected static HBXMapReduceBase LoadJobProcessor(String jarFilename, String className) {
        File fJar;
        URLClassLoader loader;
        Class classToLoad;
        Object o;
        HBXMapReduceBase mr;

        fJar = new File(jarFilename);

        try {
            URL[] aURL;

            aURL = new URL[1];
            aURL[0] = fJar.toURI().toURL();
            loader = new URLClassLoader(aURL, HBXJobSubmitterExe.class.getClassLoader());
        }
        catch( MalformedURLException muex ) {
            RKLog.Log("MalformedURLException - %s", muex.getMessage());
            return null;
        }

        try {
            classToLoad = loader.loadClass(className);
        }
        catch( ClassNotFoundException cnfex ) {
            RKLog.Log("ClassNotFoundException - %s", cnfex.getMessage());
            return null;
        }

        try {
            o = classToLoad.newInstance();
        }
        catch( InstantiationException iex ) {
            RKLog.Log("InstantiationException - %s", iex.getMessage());
            return null;
        }
        catch( IllegalAccessException iaex ) {
            RKLog.Log("IllegalAccessException - %s", iaex.getMessage());
            return null;
        }

        if( o instanceof HBXMapReduceBase == false ) {
            return null;
        }

        mr = (HBXMapReduceBase) o;

        return mr;
    }

/*
    private static void RetryBenignJobFailures( HBXJobSubmitterParams hbxjsp ) {

        Long[] xgJobs;

        xgJobs = XgridHelper.XgridGetJobs();
        if( xgJobs == null ) {
            return;
        }

        for (Long jobId : xgJobs) {
            XgridJobAttributes xga;

            xga = XgridHelper.XgridGetJobAttributes(jobId);
            if( xga == null ) {
                continue;
            }
            
            if (xga.jobStatus.compareToIgnoreCase("Failed") == 0) {
                String results;

                results = XgridHelper.XgridGetJobResults(jobId);
                if (results.contains("Unable to access jarfile") == true) {
                    RKLog.Log("%d: Resubmitting %s because original job failed with unable to access jarfile error.", jobId, xga.name);
                    XgridHelper.XgridDeleteJob(jobId);
                    XgridHelper.SubmitXgridJob(FileUtils.PathCombine(hbxjsp.SourceIntermediate, xga.name) + ".xgrid.xml");
                }
            }
        }

        return;
    }
*/

    private static HBXJobSubmitterParams GetHBXJobSubmitterParams(CommandLineParser clp) {

        HBXJobSubmitterParams hbxjsp;

        hbxjsp = new HBXJobSubmitterParams();

        // hadoop_hdfs
        if( clp.Contains("hadoop_hdfs") == false ) {
            return null;
        }

        hbxjsp.Hdfs = clp.GetString("hadoop_hdfs");

        // hadoop_jobtracker
        if( clp.Contains("hadoop_jobtracker") == false ) {
            return null;
        }

        hbxjsp.JobTracker = clp.GetString("hadoop_jobtracker");

        // hadoop_jobtracker_port ... not required ... defaults exist
        if( clp.Contains("hadoop_jobtracker_port") == true ) {
            hbxjsp.JobTrackerPort = clp.GetInteger("hadoop_jobtracker_port");
        }

        // wake on lan
        if (clp.Contains("local_wakeonlan") == false) {
            return null;
        }

        hbxjsp.WakeOnLan = clp.GetString("local_wakeonlan");

        // preprocessor_jar
        if (clp.Contains("preprocessor_jar") == false) {
            return null;
        }

        hbxjsp.PreprocessorFilename = clp.GetString("preprocessor_jar");

        // preprocessor_class
        if (clp.Contains("preprocessor_class") == false) {
            return null;
        }

        hbxjsp.PreprocessorClass = clp.GetString("preprocessor_class");

        // preprocessor_configuration
        if (clp.Contains("preprocessor_configuration") == false) {
            return null;
        }

        hbxjsp.PreprocessorConfiguration = clp.GetString("preprocessor_configuration");

        // mapreduce_jar
        if (clp.Contains("mapreduce_jar") == false) {
            return null;
        }

        hbxjsp.MapReduceFilename = clp.GetString("mapreduce_jar");

        // mapreduce_class
        if (clp.Contains("mapreduce_class") == false) {
            return null;
        }

        hbxjsp.MapReduceClass = clp.GetString("mapreduce_class");

        // mapreduce_configuration
        if (clp.Contains("mapreduce_configuration") == false) {
            return null;
        }

        hbxjsp.MapReduceConfiguration = clp.GetString("mapreduce_configuration");

        // hadoop_tasktrackers
        if (clp.Contains("hadoop_tasktrackers") == false) {
            return null;
        }

        hbxjsp.TaskTrackers = clp.GetString("hadoop_tasktrackers");

        // hadoop_input
        if( clp.Contains("hadoop_input") == false ) {
            return null;
        }

        hbxjsp.HadoopInput = clp.GetString("hadoop_input");

        // hadoop_output
        if (clp.Contains("hadoop_output") == false) {
            return null;
        }

        hbxjsp.HadoopOutput = clp.GetString("hadoop_output");

        // hadoop_logs
        if (clp.Contains("hadoop_logs") == false) {
            return null;
        }

        hbxjsp.HadoopLogs = clp.GetString("hadoop_logs");

        return hbxjsp;
    }

    private static boolean WriteOriginalStats( Date now, File fSource, Long duration, Integer xRes, Integer yRes, boolean bNormalized, String statsFilename ) {

        HBXOriginalStats hbxos;

        hbxos = new HBXOriginalStats();

        // file properties
        hbxos.Name = fSource.getName();
        hbxos.Length = fSource.length();

        // format properties
        hbxos.Duration = duration;
        hbxos.XRes = xRes;
        hbxos.YRes = yRes;

        if( bNormalized == false ) {
            hbxos.Normalized = 0;
        }
        else {
            hbxos.Normalized = 1;
        }

        // system properties
        hbxos.DateAdded = now;

        try {
            OutputStreamWriter osw;

            osw = Misc.GetUTF8FileWriter(statsFilename);
            if( osw == null ) {
                return false;
            }

            osw.write( hbxos.Serialize() );
            osw.close();
        }
        catch( IOException ioex ) {
            return false;
        }

        return true;
    }
}
