package com.rkuo.Executables;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import com.rkuo.shared.HBXFileCollectorParams;
import com.rkuo.logging.RKLog;
import com.rkuo.util.*;

/**
 * Created by IntelliJ IDEA.
 * User: rkuo
 * Date: Aug 14, 2010
 * Time: 5:17:04 PM
 * To change this template use File | Settings | File Templates.
 */
public class HBXFileCollectorExe {

    private static long SCAN_DELAY_MS = 5000;
    private static long EVENT_LOOP_DELAY_MS = 1000;
//    private static long ONE_DAY_MS = 86400000;
//    private static long YOUNGEST_DELETE_TIME = 4 * 60 * 60 * 1000; /// four hours (in ms)
    private static long WARNING_DELAY = 60 * 60 * 1000; // one hour

    public static void main( String[] args ) {
        CommandLineParser       clp;
        ShutdownThread          shutdownThread;
        Map<String,Long>        mapLastMightMove;
        HBXFileCollectorParams  hbxfcp;
        Map<String,Long>        mapFirstSeen;
        long                    lastRunTick;
        long                    lastWarningTick;
        long                    elapsed;

        lastRunTick = Long.MIN_VALUE;
        lastWarningTick = Long.MAX_VALUE;
        mapFirstSeen = new TreeMap<String, Long>();
        mapLastMightMove = new TreeMap<String, Long>();

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

        hbxfcp = GetHBXFileCollectorParams( clp );
        if( hbxfcp == null ) {
            RKLog.Log( "Missing required parameters." );
            return;
        }

        RKLog.Log( "Source: %s", hbxfcp.Source );
        RKLog.Log( "Target: %s", hbxfcp.Target );

        String  logLine = "File Extensions: ";
        for( String s : hbxfcp.FileExtensions ) {
            logLine += s + ",";
        }

        RKLog.Log( "Delay: %d", hbxfcp.Delay );
        RKLog.Log( "Cleanup: %s", hbxfcp.Cleanup.toString() );

        // remove the trailing comma
        logLine = logLine.substring(0, logLine.length() - 1 );

        RKLog.Log( logLine );

        elapsed = SCAN_DELAY_MS;
        while( true ) {
            File            fSource, fTarget;
            Map<String,Long>        mapAllMoved;
            Map<String,Long>        mapAllMightMove;
            long                    now;
            boolean         br;

            now = System.currentTimeMillis();

            synchronized( shutdownThread ) {
                if( shutdownThread.isShutdown() == true ) {
                    break;
                }
            }

            try {
                Thread.currentThread().sleep( EVENT_LOOP_DELAY_MS );
            }
            catch( InterruptedException iex ) {
                // do nothing?
                break;
            }

            elapsed += EVENT_LOOP_DELAY_MS;
            if( elapsed < SCAN_DELAY_MS ) {
                lastRunTick = now;
                continue;
            }

            elapsed = 0;


            fSource = new File( hbxfcp.Source );
            if( fSource.isDirectory() == false ) {
                if( lastWarningTick - lastRunTick > WARNING_DELAY ) {
                    RKLog.Log( "%s is not a directory.", hbxfcp.Source );
                    lastWarningTick = now;
                }
                lastRunTick = now;
                continue;
            }

            fTarget = new File( hbxfcp.Target );
            if( fTarget.isDirectory() == false ) {
                lastRunTick = now;
                continue;
            }

            mapAllMoved = new TreeMap<String,Long>();
            mapAllMightMove = new TreeMap<String,Long>();
            for( String sExtension : hbxfcp.FileExtensions ) {
                Map<String,Long>        mapMoved;
                Map<String,Long>        mapMightMove;
                String[]                fileNames;

                mapMightMove = new TreeMap<String,Long>();
                // We build a list of files that might get moved so that we don't delete them prematurely
                // Collect all filenames in the source directory with the specified file extension
                fileNames = com.rkuo.io.File.GetFilesWithExtension(fSource, "." + sExtension);
                for( String sourceName : fileNames ) {
                    mapMightMove.put(sourceName, new File(sourceName).length());
                    mapAllMightMove.put(sourceName, new File(sourceName).length());
                }

                // now actually move files
                mapMoved = TryMovingFiles( now, hbxfcp.Delay, hbxfcp.CollectExcluded, hbxfcp.CleanupExcluded, mapMightMove, fTarget, mapLastMightMove, mapFirstSeen );
                mapAllMoved.putAll( mapMoved );
            }

            mapLastMightMove = mapAllMightMove;

            // remove old files (but not files that might get moved at some point!)
            if( hbxfcp.Cleanup == true ) {
                PurgeOldFiles( now, hbxfcp, fSource, mapAllMoved, mapFirstSeen );
            }

            UpdateFirstSeenMap( now, fSource, mapFirstSeen );
            lastRunTick = now;
        }

        System.out.println( "Exiting..." );
        return;
    }

    private static void UpdateFirstSeenMap( long now, File fSource, Map<String,Long> mapFirstSeen ) {

        String[]    aOldSourceFiles;

        aOldSourceFiles = FileUtils.GetFiles(fSource);

        // add new files to the first seen map
        for (String oldSourceFile : aOldSourceFiles) {
            if (mapFirstSeen.containsKey(oldSourceFile) == false) {
                mapFirstSeen.put(oldSourceFile, now);
            }
        }

        // remove nonexistent files from the first seen map so that we don't accummulate dead weight over time
        ArrayList<String> mapKeys;

        mapKeys = new ArrayList<String>();
        for (String s : mapFirstSeen.keySet()) {
            mapKeys.add(s);
        }

        for (String mapKey : mapKeys) {
            File f;

            f = new File(mapKey);
            if (f.exists() == false) {
                mapFirstSeen.remove(mapKey);
            }
        }

        return;
    }

    private static HBXFileCollectorParams GetHBXFileCollectorParams(CommandLineParser clp) {

        HBXFileCollectorParams  hbxfcp;
        String                  value;
        String[]                extensions;

        hbxfcp = new HBXFileCollectorParams();

        if( clp.Contains("source") == false ) {
            return null;
        }

        hbxfcp.Source = clp.GetString("source");

        if( clp.Contains("target") == false ) {
            return null;
        }

        hbxfcp.Target = clp.GetString("target");

        if( clp.Contains("fileextension") == false ) {
            return null;
        }

        value = clp.GetString("fileextension");
        extensions = value.split(",");
        for( String s : extensions ) {
            hbxfcp.FileExtensions.add( s );
        }

        if( clp.Contains("cleanup") == false ) {
            return null;
        }

        hbxfcp.Cleanup = clp.GetBoolean("cleanup");

        if( clp.Contains("delay") == true ) {
            hbxfcp.Delay = clp.GetLong("delay");
        }

        // cleanup_exclude
        if( clp.Contains("cleanup_exclude") == false ) {
            return null;
        }

        value = clp.GetString("cleanup_exclude");
        extensions = value.split(",");
        for( String s : extensions ) {
            hbxfcp.CleanupExcluded.add( s );
        }

        // collect_exclude
        if( clp.Contains("collect_exclude") == false ) {
            return null;
        }

        value = clp.GetString("collect_exclude");
        extensions = value.split(",");
        for( String s : extensions ) {
            hbxfcp.CollectExcluded.add( s );
        }

        // maximum_cleanup_size
        if( clp.Contains("maximum_cleanup_size") == false ) {
            return null;
        }

        hbxfcp.MaximumCleanupSize = clp.GetLong("maximum_cleanup_size");
        hbxfcp.MaximumCleanupSize *= 1024 * 1024;

        // cleanup_change_delay
        if( clp.Contains("cleanup_change_delay") == false ) {
            return null;
        }

        hbxfcp.CleanupChangeDelay = clp.GetLong("cleanup_change_delay");
        hbxfcp.CleanupChangeDelay *= 1000L;

        // cleanup_delay
        if( clp.Contains("cleanup_delay") == false ) {
            return null;
        }

        hbxfcp.CleanupDelay = clp.GetLong("cleanup_delay");
        hbxfcp.CleanupDelay *= 1000L;

        return hbxfcp;
    }

    // attempts to move files from the specified directories
    // returns a list of files that were actually moved
    private static Map<String,Long> TryMovingFiles(
            long now,
            long delay,
            List<String> aCollectExcluded,
            List<String> aCleanupExcluded,
            Map<String,Long> mapMightMove,
            File fTarget,
            Map<String,Long> mapLastSizes,
            Map<String,Long> mapFirstSeen ) {

        TreeMap<String,Long>    mapMove;
        boolean                 br;

        mapMove = new TreeMap<String, Long>();

        // If we find the filename in the previous map
        // and the size has remained constant
        // and any specified delay has been exceeded
        // we add it to the list of files to move
        for (Map.Entry<String, Long> e : mapMightMove.entrySet()) {
            String filename;
            long lastFileSize;

            filename = e.getKey();
            if (mapLastSizes.containsKey(filename) == false) {
                continue;
            }

            lastFileSize = mapLastSizes.get(filename);
            if (lastFileSize != e.getValue()) {
                continue;
            }

            boolean bExcluded = false;
            for( String pattern : aCollectExcluded ) {
                if( filename.toLowerCase().contains(pattern) == true ) {
                    bExcluded = true;
                }
            }

            for( String pattern : aCleanupExcluded ) {
                if( filename.toLowerCase().contains(pattern) == true ) {
                    bExcluded = true;
                }
            }

            if( bExcluded == true ) {
                continue;
            }

            if( delay > 0 ) {
                long    firstSeen;
                if( mapFirstSeen.containsKey(filename) == false ) {
                    continue;
                }

                firstSeen = mapFirstSeen.get(filename);
                if( now - firstSeen < delay ) {
                    continue;
                }
            }

            mapMove.put(e.getKey(), e.getValue());
        }

        for (Map.Entry<String, Long> e : mapMove.entrySet()) {
            File f;
            String targetDirectory;
            String targetName;
            String sourceName;

            sourceName = e.getKey();

            f = new File(sourceName);

            targetDirectory = fTarget.getAbsolutePath();
            targetName = FileUtils.PathCombine(targetDirectory, f.getName());
            if( f.getAbsolutePath().contains("high_priority") == true ) {
                RKLog.Log( "%s is a high priority file. Flagging filename with high priority file extension.", f.getName() );
                targetName = FileUtils.getAbsolutePathWithoutExtension(targetName) + "-p1." + com.rkuo.io.File.GetExtension(targetName);
            }

            RKLog.Log("Moving %s to %s.", f.getName(), targetName);
            br = FileUtils.MoveFile(f.getAbsolutePath(), targetName);
            if (br == false) {
                RKLog.Log("Rename from %s to %s failed.", sourceName, targetName);
            }

            RKLog.Log("Move succeeded: %s to %s.", sourceName, targetName);
        }

        return mapMove;
    }

    // source directory to purge files from (recursively)
    // list of files we intend to move ... don't ever purge these
    // list of files that have appeared recently.  Don't purge these either.
    private static void PurgeOldFiles( long now, HBXFileCollectorParams fcp, File fSource, Map<String,Long> mapLastSizes, Map<String,Long> mapFirstSeen ) {
        // Try to purge files older than a day ... but don't worry about failure
        String[]    aOldSourceFiles, aOldSourceDirectories;
        boolean     br;

        aOldSourceFiles = FileUtils.GetFiles(fSource);
        for (String oldSourceFile : aOldSourceFiles) {
            long firstSeen;
            File f;

            f = new File(oldSourceFile);

            // Don't delete files that we want to move
            if (mapLastSizes.containsKey(oldSourceFile) == true) {
                continue;
            }

            // Don't delete any files in excluded directories
            boolean bExcluded = false;
            for( String pattern : fcp.CleanupExcluded ) {
                if( oldSourceFile.toLowerCase().contains(pattern) == true ) {
                    bExcluded = true;
                }
            }

            if( bExcluded == true ) {
                continue;
            }

            // Don't delete any file bigger than 128MB ... it's almost certainly primary content
            if (f.length() > fcp.MaximumCleanupSize) {
                continue;
            }

            // Don't delete files we have never seen before
            if (mapFirstSeen.containsKey(oldSourceFile) == false) {
                continue;
            }

            // Don't delete files we have seen exist for less than four hours
            // this allows time for deep extraction in JDownloader
            firstSeen = mapFirstSeen.get(oldSourceFile);
            if (now - firstSeen < fcp.CleanupChangeDelay) {
                continue;
            }

            // Delete files older than a day
            // sometimes files get extracted and written with an old timestamp
            // the above four hour wait protects us against this scenario
            if (now - f.lastModified() < fcp.CleanupDelay) {
                continue;
            }

            br = f.delete();
            if (br == false) {
                // do nothing for now ... something has it locked
                continue;
            }

            RKLog.Log("Deleted %s", oldSourceFile);
        }

        // Try to purge directories older than one day ... but don't worry about failure
        // note that deleting files inside a directory changes the modification date of the directory
        // so it takes up to a day after the last file was deleted for the directory to be removed
        aOldSourceDirectories = FileUtils.GetDirectories(fSource);
        for (String oldSourceDirectory : aOldSourceDirectories) {
            File f;

            f = new File(oldSourceDirectory);

            // Don't delete any files in excluded directories
            boolean bExcluded = false;
            for( String pattern : fcp.CleanupExcluded ) {
                if( oldSourceDirectory.toLowerCase().contains(pattern) == true ) {
                    bExcluded = true;
                }
            }

            if( bExcluded == true ) {
                continue;
            }

            if( now - f.lastModified() < fcp.CleanupDelay ) {
                continue;
            }

            br = f.delete();
            if (br == false) {
                continue;
            }

            RKLog.Log("Deleted %s", oldSourceDirectory);
        }

        return;
    }
}
