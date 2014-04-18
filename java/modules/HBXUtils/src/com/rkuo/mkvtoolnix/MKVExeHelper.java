package com.rkuo.mkvtoolnix;

import com.rkuo.handbrake.HandbrakeExecuteProcessCallback;
import com.rkuo.handbrake.IHandBrakeExeCallback;
import com.rkuo.logging.RKLog;
import com.rkuo.util.FileUtils;
import com.rkuo.util.Misc;

import java.io.*;
import java.util.ArrayList;
import java.util.Map;

/**
 * Created with IntelliJ IDEA.
 * User: rkuo
 * Date: 4/12/14
 * Time: 4:24 PM
 * To change this template use File | Settings | File Templates.
 */
public class MKVExeHelper {

    private static long TIMEOUT_AC3_CONVERSION = 60 * 60 * 1000; // 90 min (in ms)

    // in progress
    // Encoding: task 1 of 1, 47.42 % (6.96 fps, avg 8.80 fps, ETA 01h24m15s))
    public static int ScanMKVDTS2AC3Progress( String sInputProgress ) {

        int     nProgress;
        int     nColonIndex, nPercentIndex;
        String  sProgress;

        String  sPercentage;
        String  sTemp, sFinal;

        nColonIndex = sInputProgress.indexOf(':');
        nPercentIndex = sInputProgress.indexOf('%');

        sProgress = sInputProgress.substring(nColonIndex+1, nPercentIndex);
        sProgress = sProgress.trim();

        return Integer.parseInt(sProgress);
    }

    public static int ExecuteMkvMergeRemux( String mkvMergeExe, String sourceFilename, String targetFilename ) {

        ArrayList<String> cmdArgs;
        int exitCode;

        // remux the file to avoid bugs
        RKLog.Log("Remuxing file to avoid bugs.");
        cmdArgs = new ArrayList<String>();
        cmdArgs.add( mkvMergeExe );
        cmdArgs.add( "-q" );

        cmdArgs.add( "-o" );
        cmdArgs.add( targetFilename );
        cmdArgs.add( sourceFilename );

        Misc.printArgs(cmdArgs.toArray(new String[cmdArgs.size()]));
        exitCode = Misc.ExecuteProcess( cmdArgs.toArray( new String[cmdArgs.size()]), null, false, null, null );
        if( exitCode != 0 ) {
            RKLog.Log("mkvmerge remux failed.");
            return exitCode;
        }

        RKLog.Log("Remuxing complete.");
        return exitCode;
    }

    public static int ExecuteMkvMergeSplit( String mkvMergeExe, String sourceFilename, String targetFilename ) {

        ArrayList<String> cmdArgs;
        int exitCode;

        // remux the file to avoid bugs
        RKLog.Log("Remuxing file to avoid bugs.");
        cmdArgs = new ArrayList<String>();
        cmdArgs.add( mkvMergeExe );
        cmdArgs.add( "-q" );

        cmdArgs.add( "-o" );
        cmdArgs.add( targetFilename );
        cmdArgs.add( sourceFilename );

        Misc.printArgs(cmdArgs.toArray(new String[cmdArgs.size()]));
        exitCode = Misc.ExecuteProcess( cmdArgs.toArray( new String[cmdArgs.size()]), null, false, null, null );
        if( exitCode != 0 ) {
            RKLog.Log("mkvmerge remux failed.");
            return exitCode;
        }

        RKLog.Log("Remuxing complete.");
        return exitCode;
    }

    public static int ExecuteMKVDTS2AC3( String scriptPath, String workingDir, String sourceFilename, Integer audioTrack ) {

        Process             process;
        String              processWorkingDir;
        String              line;
        InputStream is;
        InputStreamReader isr;
        BufferedReader bufr;
        ArrayList<String>   cmdArgs;
        int                 returnCode;
        int                 lastProgress;
        int                 lastDTSDecodeProgress;
        long                lastUpdate;

        returnCode = 1;
        lastProgress = -1;
        lastDTSDecodeProgress = -1;
        lastUpdate = System.currentTimeMillis();

        processWorkingDir = new File(scriptPath).getParent();

        cmdArgs = new ArrayList<String>();
        cmdArgs.add( scriptPath );

        cmdArgs.add( "-f" ); // go even if there is already an AC3 track
        cmdArgs.add( "--new" ); // output to a new file of the form basename-AC3.mkv
        cmdArgs.add( "-n" ); // remove the original DTS track
        cmdArgs.add( "-i" ); // make the new AC3 track first
        cmdArgs.add( "-t" ); // specify the track id to use in the source
        cmdArgs.add( audioTrack.toString() );
        cmdArgs.add( "-w" );
        cmdArgs.add( workingDir );
        cmdArgs.add( sourceFilename );

        // let's print the constructed command line for reference
        RKLog.Log( "ExecuteMKVDTS2AC3 attempting to run script. Command line follows." );
        Misc.printArgs( cmdArgs.toArray(new String[cmdArgs.size()]) );

        try {
            ProcessBuilder      pb;

            pb = new ProcessBuilder(cmdArgs);
            pb.redirectErrorStream( true );
            pb.directory( new File(processWorkingDir) );

            Map<String,String> env = pb.environment();

//            String path = env.get("path");
//            env.put("path", path + File.pathSeparator + processWorkingDir);

            process = pb.start();
        }
        catch( IOException ioex ) {
            RKLog.Log( "ExecuteMKVDTS2AC3 failed. %s", ioex.getMessage() );
            return 1;
        }

        is = process.getInputStream();
        isr = new InputStreamReader(is);

        bufr = new BufferedReader(isr);

        while (true) {

            int     exitCode;

            try {
                Thread.sleep( 1000 );
            }
            catch( InterruptedException iex ) {
                break;
            }

            exitCode = Integer.MAX_VALUE;
            try {
                exitCode = process.exitValue();
            }
            catch( IllegalThreadStateException itsex ) {
                // process has not exited yet ... this is ok
            }

            String lineBuilder;
            boolean skipCarriageReturn;

            lineBuilder = "";
            skipCarriageReturn = false;

            // We put this here so that any buffered data can be spooled out
            // after reading the exit code but prior to exiting
            while( true ) {

                long now;

                now = System.currentTimeMillis();
                if( now > (lastUpdate + MKVExeHelper.TIMEOUT_AC3_CONVERSION) ) {
                    RKLog.Log( "Conversion did not complete in 30 minutes. Failing out..." );
                    process.destroy();
                    exitCode = Integer.MIN_VALUE;
                    break;
                }

                try {
                    line = null;

                    while( bufr.ready() == true ) {
                        char c;

                        c = (char)bufr.read();

                        if( skipCarriageReturn == true ) {
                            skipCarriageReturn = false;
                            if( c == '\n' ) {
                                continue;
                            }
                        }

                        if( c == '\r' ) {
                            line = lineBuilder;
                            lineBuilder = "";
                            skipCarriageReturn = true;
                            break;
                        }
                        else if( c == '\n' ) {
                            line = lineBuilder;
                            lineBuilder = "";
                            break;
                        }
                        else {
                            lineBuilder += c;
                        }
                    }

//                    line = bufr.readLine();
                }
                catch (IOException ioex) {
                    break;
                }

                if( line == null ) {
                    break;
                }

                lastUpdate = System.currentTimeMillis();
                if( line.startsWith("Progress:") == true ) {
                    try {
                        int  progress;
                        progress = ScanMKVDTS2AC3Progress(line);

                        if( progress < lastProgress || progress >= lastProgress + 10 ) {
                            lastProgress = progress;
                            RKLog.println(line);
                        }
                    }
                    catch( Exception ex ) {
                        // just swallow any poorly formatted lines
                    }
                }
                else if( line.compareTo("skip") == 0 ) {
                    // swallow it
                }
                else if( line.contains("frames in") == true ) {
                    int pos;
                    int progress;
                    String  sProgress;

                    pos = line.indexOf("frames in");
                    sProgress = line.substring(0, pos).trim();
                    progress = Integer.parseInt(sProgress);
                    if( progress < lastDTSDecodeProgress || progress >= lastDTSDecodeProgress + 100000 ) {
                        lastDTSDecodeProgress = progress;
                        RKLog.println(line);
                    }
                }
                else {
                    RKLog.println(line);
                }
            }

            if( exitCode != Integer.MAX_VALUE ) {
                RKLog.Log( "MKVDTS2AC3 exited with error code %d.", exitCode );
                returnCode = exitCode;
                break;
            }
        }

        return returnCode;
    }

    public static Integer ConvertDTSToAC3(
            String sourceFilename,
            String targetFilename,
            String mkvExtractFilename,
            String mkvMergeFilename,
            String aftenFilename,
            String dcaDecFilename,
            String workingDir,
            MKVTrack tPreferred ) {

        ArrayList<String> cmdArgs;
        String[] leftoverFiles;
        int exitCode;

        Integer delay = 0;
        File fWorkingDir = new File(workingDir);

        File fMKVExtract = new File( mkvExtractFilename );
        File fMKVMerge = new File( mkvMergeFilename );

        File fAften = new File( aftenFilename );
        File fDcaDec = new File( dcaDecFilename );

        File fMKV = new File( sourceFilename );
        File fTC = new File( FileUtils.PathCombine(fWorkingDir.getAbsolutePath(), FileUtils.getNameWithoutExtension(fMKV.getName()) + ".tc") );
        File fDTS = new File( FileUtils.PathCombine(fWorkingDir.getAbsolutePath(), FileUtils.getNameWithoutExtension(fMKV.getName()) + ".dts") );
        File fAC3 = new File( FileUtils.PathCombine(fWorkingDir.getAbsolutePath(), FileUtils.getNameWithoutExtension(fMKV.getName()) + ".ac3") );

        // cleanup
        fWorkingDir.mkdir();

        leftoverFiles = FileUtils.GetFiles(fWorkingDir);
        for (String leftoverFile : leftoverFiles) {
            File fLeftover;

            fLeftover = new File(leftoverFile);
            fLeftover.delete();
        }

        // extract timecodes
        RKLog.Log("Extracting timecodes.");
        cmdArgs = new ArrayList<String>();
        cmdArgs.add( fMKVExtract.getAbsolutePath() );
        cmdArgs.add( "timecodes_v2" );
        cmdArgs.add( fMKV.getAbsolutePath() );
        cmdArgs.add(String.format("%d:%s", tPreferred.TrackId, fTC.getAbsolutePath()));

        Misc.printArgs( cmdArgs.toArray(new String[cmdArgs.size()]) );
        exitCode = Misc.ExecuteProcess( cmdArgs.toArray( new String[cmdArgs.size()]), null, false, null, null );
        if( exitCode != 0 ) {
            RKLog.Log("mkvextract failed while extracting timecodes.");
            return exitCode;
        }

        // parse timecode file for delay
        try {
            InputStream fis;
            BufferedReader br;
            Integer x = 0;

            fis = new FileInputStream(fTC);
            br = new BufferedReader(new InputStreamReader(fis, "UTF-8"));
            while (true) {
                String line = br.readLine();
                if (line == null) {
                    break;
                }

                if (x == 1) {
                    delay = Integer.parseInt(line);
                    break;
                }

                x++;
            }
        }
        catch (Exception ex) {
            RKLog.Log("Exception while parsing timecodes.");
            return 1;
        }
        RKLog.Log("Timecode extraction completed. Delay = %d.", delay);

        // extract dts
        RKLog.Log("Extracting DTS track.");
        cmdArgs = new ArrayList<String>();
        cmdArgs.add( fMKVExtract.getAbsolutePath() );
        cmdArgs.add( "tracks" );
        cmdArgs.add( fMKV.getAbsolutePath() );
        cmdArgs.add(String.format("%d:%s", tPreferred.TrackId, fDTS.getAbsolutePath()));

        Misc.printArgs( cmdArgs.toArray(new String[cmdArgs.size()]) );
        exitCode = Misc.ExecuteProcess( cmdArgs.toArray( new String[cmdArgs.size()]), null, false, null, null );
        if( exitCode != 0 ) {
            RKLog.Log("mkvextract failed while extracting dts.");
            return exitCode;
        }
        RKLog.Log("Extracting DTS track completed.");

        // chain dcadec with aften to convert to dts to ac3
        RKLog.Log("Converting DTS track to AC3.");
        cmdArgs = new ArrayList<String>();
        cmdArgs.add( "bash" );
        cmdArgs.add( "-c" );
        cmdArgs.add( String.format("\"%s\" -o wavall \"%s\" | \"%s\" -v 0 - \"%s\"",
                fDcaDec.getAbsolutePath(), fDTS.getAbsolutePath(),
                fAften.getAbsolutePath(), fAC3.getAbsolutePath()));

        Misc.printArgs( cmdArgs.toArray(new String[cmdArgs.size()]) );
        exitCode = Misc.ExecuteProcess( cmdArgs.toArray( new String[cmdArgs.size()]), null, false, null, null );
        if( exitCode != 0 ) {
            RKLog.Log("mkvextract failed while extracting dts.");
            return exitCode;
        }
        RKLog.Log("Converting DTS track to AC3 completed.");

        // merge to new file
        RKLog.Log("Remuxing to final AC3 MKV.");
        cmdArgs = new ArrayList<String>();
        cmdArgs.add( fMKVMerge.getAbsolutePath() );
        cmdArgs.add( "-q" );

        cmdArgs.add( "--track-order" );
        cmdArgs.add( "0:1,1:0" );

        cmdArgs.add( "-o" );
        cmdArgs.add( targetFilename );

        cmdArgs.add( "-a" );
        cmdArgs.add( String.format("!%d",tPreferred.TrackId) );

        cmdArgs.add( sourceFilename );

        cmdArgs.add( "--language" );
        cmdArgs.add( String.format("0:%s",tPreferred.Language) );
        if( delay > 0 ) {
            cmdArgs.add( "--sync" );
            cmdArgs.add( String.format("0:%d",delay) );
        }

        cmdArgs.add( fAC3.getAbsolutePath() );

        Misc.printArgs( cmdArgs.toArray(new String[cmdArgs.size()]) );
        exitCode = Misc.ExecuteProcess( cmdArgs.toArray( new String[cmdArgs.size()]), null, false, null, null );
        if( exitCode != 0 ) {
            RKLog.Log("mkvmerge to %s failed.",targetFilename);
            return exitCode;
        }
        RKLog.Log("Remuxing to final AC3 MKV completed.");


        leftoverFiles = FileUtils.GetFiles(fWorkingDir);
        for (String leftoverFile : leftoverFiles) {
            File fLeftover;

            fLeftover = new File(leftoverFile);
            fLeftover.delete();
        }

        return 0;
    }

    public static int ExtractMKVSubtitle( String exePath, String mkvPath, String subPath, int trackId, IHandBrakeExeCallback callback ) {

        ArrayList<String>   cmdArgs;
        int                 exitCode;
        HandbrakeExecuteProcessCallback hCallback;

        hCallback = new HandbrakeExecuteProcessCallback(callback);

        cmdArgs = new ArrayList<String>();
        cmdArgs.add( exePath );
        cmdArgs.add( "tracks" );
        cmdArgs.add( mkvPath );
        cmdArgs.add(String.format("%d:%s", trackId, subPath));

        Misc.printArgs( cmdArgs.toArray(new String[cmdArgs.size()]) );
        exitCode = Misc.ExecuteProcess( cmdArgs.toArray( new String[cmdArgs.size()]), null, false, null, hCallback );

        return exitCode;
    }

    public static MKVTrack[] ExecuteMKVInfo( String mkvinfoPath, String sourceFilename ) {

        Process             process;
        String              line;
        InputStream         is;
        InputStreamReader   isr;
        BufferedReader      bufr;
        ArrayList<String>   cmdArgs;
        boolean             bSegmentTracks;
        int                 exitCode;
        ArrayList<MKVTrack> tracks;

        bSegmentTracks = false;
        exitCode = Integer.MIN_VALUE;
        tracks = new ArrayList<MKVTrack>();

        cmdArgs = new ArrayList<String>();
        cmdArgs.add( mkvinfoPath );
        cmdArgs.add( sourceFilename );

        try {
            ProcessBuilder      pb;

            pb = new ProcessBuilder(cmdArgs);
            pb.redirectErrorStream( true );

            process = pb.start();
        }
        catch( IOException ioex ) {
            RKLog.Log( "ExecuteMKVInfo failed. %s", ioex.getMessage() );
            return null;
        }

        is = process.getInputStream();
        isr = new InputStreamReader(is);
        bufr = new BufferedReader(isr);

        while (true) {

            int tempExitCode;

            tempExitCode = Integer.MIN_VALUE;
            try {
                tempExitCode = process.exitValue();
            }
            catch( IllegalThreadStateException itsex ) {
                // process has not exited yet ... this is ok
            }

            MKVTrack    track;

            track = null;

            // We put this here so that any buffered data can be spooled out
            // after reading the exit code but prior to exiting
            while( true ) {

                String[]  parts;

                try {
                    line = bufr.readLine();
                }
                catch (IOException ioex) {
                    break;
                }

                if( line == null ) {
                    break;
                }

                RKLog.println(line);

                if( line.startsWith("|+ Segment tracks") == true ) {
                    bSegmentTracks = true;
                }
                else if( bSegmentTracks == true ) {
                    String  trackAttributePrefix, trackAttributePrefix2;

                    trackAttributePrefix = "|  + ";
                    trackAttributePrefix2 = "|   + ";

                    if( line.startsWith("| + A track") == true ) {
                        track = new MKVTrack();
                        tracks.add( track );
                        continue;
                    }

                    if( track == null ) {
                        continue;
                    }

                    if( line.startsWith(trackAttributePrefix) == true ) {
                        String tempLine, name, value;
                        int     nColonIndex;

                        tempLine = line.substring(trackAttributePrefix.length());

                        nColonIndex = tempLine.indexOf(':');
                        if( nColonIndex == -1 ) {
                            continue;
                        }

                        name = tempLine.substring(0, nColonIndex);
                        value = tempLine.substring(nColonIndex+1, tempLine.length());
                        value = value.trim();

                        if( name.compareToIgnoreCase("track number") == 0 ) {
                            String tempValue;

                            int idxSpace = value.indexOf(' ');
                            if( idxSpace != -1 ) {
                                tempValue = value.substring(0,idxSpace);
                            }
                            else {
                                tempValue = value;
                            }

                            track.TrackNumber = Integer.parseInt(tempValue);

                            nColonIndex = value.indexOf(':');
                            if( nColonIndex >= 0 ) {
                                int nParenIndex = value.indexOf(')');
                                tempValue = value.substring(nColonIndex+1,nParenIndex);
                                tempValue = tempValue.trim();
                                track.TrackId = Integer.parseInt(tempValue);
                            }
                        }
                        else if( name.compareToIgnoreCase("track type") == 0 ) {
                            track.Type = value;
                        }
                        else if( name.compareToIgnoreCase("codec id") == 0 ) {
                            track.CodecID = value;
                        }
                        else if( name.compareToIgnoreCase("language") == 0 ) {
                            track.Language = value;
                        }
                        else if( name.compareToIgnoreCase("default flag") == 0 ) {
                            if( Integer.parseInt(value) == 0 ) {
                                track.Default = false;
                            }
                            else {
                                track.Default = true;
                            }
                        }
                        else if( name.compareToIgnoreCase("forced flag") == 0 ) {
                            if( Integer.parseInt(value) == 0 ) {
                                track.Forced = false;
                            }
                            else {
                                track.Forced = true;
                            }
                        }
                        else if( name.compareToIgnoreCase("name") == 0 ) {
                            track.Name = value;
                        }

                    }
                    else if( line.startsWith(trackAttributePrefix2) == true ) {
                        String tempLine, name, value;
                        int     nColonIndex;

                        tempLine = line.substring(trackAttributePrefix2.length());

                        nColonIndex = tempLine.indexOf(':');
                        if( nColonIndex == -1 ) {
                            continue;
                        }

                        name = tempLine.substring(0, nColonIndex);
                        value = tempLine.substring(nColonIndex+1, tempLine.length());
                        value = value.trim();

                        if( name.compareToIgnoreCase("channels") == 0 ) {
                            track.Channels = Integer.parseInt(value);
                        }
                    }
                    else if( line.indexOf('+') == 1 ) {
                        bSegmentTracks = false;
                        track = null;
                    }
                    else {
                        // unexpected?
                    }
                }
            }

            if( tempExitCode != Integer.MIN_VALUE ) {
                RKLog.Log( "mkvinfo exited with error code %d.", tempExitCode );
                exitCode = tempExitCode;
                break;
            }
        }

        if( exitCode != 0 ) {
            return null;
        }

        return tracks.toArray( new MKVTrack[tracks.size()] );
    }
}
