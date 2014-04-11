package com.rkuo.handbrake;

import com.rkuo.logging.RKLog;
import com.rkuo.threading.RKEvent;
import com.rkuo.util.ExecuteProcessCallback;
import com.rkuo.util.FileUtils;
import com.rkuo.util.Misc;
import com.rkuo.util.OperatingSystem;
import com.sun.tools.corba.se.idl.StringGen;

import javax.sound.midi.Track;
import java.awt.geom.Path2D;
import java.io.*;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Created by IntelliJ IDEA.
 * User: rkuo
 * Date: Aug 18, 2010
 * Time: 12:08:59 AM
 * To change this template use File | Settings | File Templates.
 */
public class HBXExeHelper {

    private static long TIMEOUT_ENCODING = 15 * 60 * 1000; // 15 min (in ms)
    private static long TIMEOUT_AC3_CONVERSION = 60 * 60 * 1000; // 90 min (in ms)
    private static Integer ATV2010_MAX_HRES = 1280;
    private static Integer ATV2010_MAX_VRES = 720;
    private static Integer ATV2012_MAX_HRES = 1920;
    private static Integer ATV2012_MAX_VRES = 1080;
    private static Integer HANDBRAKE_095 = 0;
    private static Integer HANDBRAKE_096 = 1;

    // in progress
    // Encoding: task 1 of 1, 47.42 % (6.96 fps, avg 8.80 fps, ETA 01h24m15s))
    public static double ScanHandbrakeProgress( String sInputProgress ) {
        double d;
        int commaPos, parenPos;

        String  sPercentage;
        String  sTemp, sFinal;

        commaPos = sInputProgress.indexOf(',');
        parenPos = sInputProgress.indexOf('(');

        if( parenPos == -1 ) {
            sTemp = sInputProgress.substring( commaPos + 1 );
        }
        else {
            sTemp = sInputProgress.substring( commaPos + 1, parenPos );
        }

        sPercentage = sTemp.trim();
        sPercentage = sPercentage.replace('%',' ');
        sFinal = sPercentage.trim();
        d = Double.parseDouble(sFinal);
        return d;
    }

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

    public static HBXScanParams ExecuteHandbrake095ForAppleTV2012(
            String handbrakePath,
            String sourceFilename,
            String targetFilename,
            int xRes,
            int yRes,
            HBXAudioTrack[] audioTracks,
            HBXSubtitleTrack[] subtitleTracks,
            IHandBrakeExeCallback callback,
            RKEvent evAbort ) {

        return ExecuteHandbrake(
                HANDBRAKE_095, ATV2012_MAX_HRES, ATV2012_MAX_VRES,
                handbrakePath, sourceFilename, targetFilename, xRes, yRes,
                audioTracks, subtitleTracks,
                callback, evAbort );
    }

    public static HBXScanParams ExecuteHandbrake098ForAppleTV2012(
            String handbrakePath,
            String sourceFilename,
            String targetFilename,
            int xRes,
            int yRes,
            HBXAudioTrack[] audioTracks,
            HBXSubtitleTrack[] subtitleTracks,
            IHandBrakeExeCallback callback,
            RKEvent evAbort ) {

        return ExecuteHandbrake(
                HANDBRAKE_096, ATV2012_MAX_HRES, ATV2012_MAX_VRES,
                handbrakePath, sourceFilename, targetFilename, xRes, yRes,
                audioTracks, subtitleTracks,
                callback, evAbort );
    }

    // When this function and HandBrakeExeParams are fully defined,
    // we should be able to fully control the handbrake exe from java with this function
    public static HBXScanParams ExecuteHandbrake( HandBrakeExeParams params ) {

        HBXScanParams       hbxsp;
        Process             process;
        String              line;
        InputStream         is;
        InputStreamReader   isr;
        BufferedReader      bufr;
        ArrayList<String>   cmdArgs;
        int                 nLastProgress;
        double              dLastProgress;
        long                lastProgressChange;

        hbxsp = new HBXScanParams();
        nLastProgress = -1;
        dLastProgress = -1;
        lastProgressChange = System.currentTimeMillis();

        cmdArgs = new ArrayList<String>();
        cmdArgs.add( params.Executable );

        if( params.Verbose > 0 ) {
            cmdArgs.add( "--verbose" );
            cmdArgs.add( params.Verbose.toString() );
        }

        if( params.Input.length() > 0 ) {
            cmdArgs.add( "--input" );
            cmdArgs.add( params.Input );
        }

        if( params.Output.length() > 0 ) {
            cmdArgs.add( "--output" );
            cmdArgs.add( params.Output );
        }

        if( params.Format != HandBrakeExeParams.DestinationFormat.AUTODETECT ) {
            cmdArgs.add( "--format" );
            cmdArgs.add( params.Format.toString() );
        }

        if( params.Crop == HandBrakeExeParams.PictureCropOption.Strict ) {
            cmdArgs.add( "--crop" );
            cmdArgs.add( String.format("%d:%d:%d:%d",params.CropTop,params.CropBottom,params.CropLeft,params.CropRight) );
        }
        else if( params.Crop == HandBrakeExeParams.PictureCropOption.Loose ) {
            cmdArgs.add( "--loose-crop" );
            if( params.LooseCropMaximum > 0 ) {
                cmdArgs.add( params.LooseCropMaximum.toString() );
            }
        }
        else {
            // autocrop
        }

        cmdArgs.add( "--quality" );
        cmdArgs.add( String.format("%.1f",params.Quality) );

        if( params.RateControl != HandBrakeExeParams.VideoFrameRateControlOption.DEFAULT ) {
            cmdArgs.add( "--" + params.RateControl.toString() );
        }

        cmdArgs.add( "--rate" );
        if( params.Rate != HandBrakeExeParams.VideoRateOption.FRAMERATE_VARIABLE ) {
            cmdArgs.add( String.format(params.Rate.toString()) );
        }

        if( params.Encoder != HandBrakeExeParams.VideoEncoderOption.DEFAULT ) {
            cmdArgs.add( "--encoder" );
            cmdArgs.add( params.Encoder.toString() );
        }

        if( params.Decomb == true ) {
            cmdArgs.add( "--decomb" );
        }

        if( params.Detelecine == true ) {
            cmdArgs.add( "--detelecine" );
        }

        if( params.x264opts.length() > 0 ) {
            cmdArgs.add( "--x264opts" );
            cmdArgs.add( params.x264opts );
        }

        if( params.AudioTracks.size() > 0 ) {
            String args = "";

            cmdArgs.add( "--audio" );
            for( Integer i : params.AudioTracks ) {
                args += i.toString() + ",";
            }
            args = args.substring(0, args.length()-1);
            cmdArgs.add( args );
        }

        if( params.AudioEncoders.size() > 0 ) {
            String args = "";

            cmdArgs.add( "--aencoder" );
            for( HandBrakeExeParams.AudioEncoderOption aeo : params.AudioEncoders ) {
                args += aeo.toString() + ",";
            }
            args = args.substring(0, args.length()-1);
            cmdArgs.add( args );
        }

        if( params.AudioMixdowns.size() > 0 ) {
            String args = "";

            cmdArgs.add( "--mixdown" );
            for( HandBrakeExeParams.AudioMixdownOption aeo : params.AudioMixdowns ) {
                args += aeo.toString() + ",";
            }
            args = args.substring(0, args.length()-1);
            cmdArgs.add( args );
        }

        if( params.AudioSampleRates.size() > 0 ) {
            String args = "";

            cmdArgs.add( "--arate" );
            for( Integer i : params.AudioSampleRates ) {
                if( i == 0 ) {
                    args += "auto,";
                }
                else {
                    args += i.toString() + ",";
                }
            }
            args = args.substring(0, args.length()-1);
            cmdArgs.add( args );
        }

        if( params.AudioBitrates.size() > 0 ) {
            String args = "";

            cmdArgs.add( "--ab" );
            for( Integer i : params.AudioBitrates ) {
                if( i == 0 ) {
                    args += "auto,";
                }
                else {
                    args += i.toString() + ",";
                }
            }
            args = args.substring(0, args.length()-1);
            cmdArgs.add( args );
        }

        if( params.AudioDynamicRangeCompressions.size() > 0 ) {
            String args = "";

            cmdArgs.add( "--drc" );
            for( Double d : params.AudioDynamicRangeCompressions ) {
                args += String.format("%.1f,",d);
            }
            args = args.substring(0, args.length()-1);
            cmdArgs.add( args );
        }

        if( params.SrtFiles.size() > 0 ) {
            String args = "";

            cmdArgs.add( "--srt-file" );
            for( String s : params.SrtFiles ) {
                args += s + ",";
            }
            args = args.substring(0, args.length()-1);
            cmdArgs.add( args );
        }

        if( params.SrtCodesets.size() > 0 ) {
            String args = "";

            cmdArgs.add( "--srt-codeset" );
            for( String s : params.SrtCodesets ) {
                args += s + ",";
            }
            args = args.substring(0, args.length()-1);
            cmdArgs.add( args );
        }

        if( params.SrtLanguages.size() > 0 ) {
            String args = "";

            cmdArgs.add( "--srt-lang" );
            for( String s : params.SrtLanguages ) {
                args += s + ",";
            }
            args = args.substring(0, args.length()-1);
            cmdArgs.add( args );
        }

        if( params.SrtDefault > 0 ) {
            cmdArgs.add( "--srt-default" );
            cmdArgs.add( params.SrtDefault.toString() );
        }

        if( params.Anamorphic == HandBrakeExeParams.PictureAnamorphicOption.STRICT ) {
            cmdArgs.add( "--strict-anamorphic" );
        }
        else if( params.Anamorphic == HandBrakeExeParams.PictureAnamorphicOption.LOOSE ) {
            cmdArgs.add( "--loose-anamorphic" );
            if( params.Modulus > 0 ) {
                cmdArgs.add( "--modulus" );
                cmdArgs.add( params.Modulus.toString() );
            }

            cmdArgs.add( "--width" );
            cmdArgs.add( params.Width.toString() );
            cmdArgs.add( "--maxWidth" );
            cmdArgs.add( params.MaxWidth.toString() );
            cmdArgs.add( "--maxHeight" );
            cmdArgs.add( params.MaxHeight.toString() );
        }
        else {
            // auto
        }

        if( params.Markers == true ) {
            cmdArgs.add( "--markers" );
        }

        if( params.LargeFile == true ) {
            cmdArgs.add( "--large-file" );
        }

        // let's print the constructed command line for reference
        String command;

        command = "";
        for( String c : cmdArgs ) {
            command += c + " ";
        }

        RKLog.Log( "ExecuteHandbrake attempting to run HandBrake. Command line follows." );
        RKLog.Log( command );

        // OK, start executing
        try {
            ProcessBuilder      pb;

            pb = new ProcessBuilder(cmdArgs);
            pb.redirectErrorStream( true );

            process = pb.start();
        }
        catch( IOException ioex ) {
            RKLog.Log( "ExecuteHandbrake failed. %s", ioex.getMessage() );
            return null;
        }

        is = process.getInputStream();
        isr = new InputStreamReader(is);

        bufr = new BufferedReader(isr);

        while (true) {
            int     exitCode;
            boolean br;
            boolean bAbort;

            bAbort = false;

            exitCode = Integer.MIN_VALUE;
            try {
                exitCode = process.exitValue();
            }
            catch( IllegalThreadStateException itsex ) {
                // process has not exited yet ... this is ok
            }

            // We do this work before checking the exit code so that any buffered data can be spooled out
            // after reading the exit code but prior to exiting
            while( true ) {

                long    now;

                now = System.currentTimeMillis();

                if( params.Abort != null ) {
                    br = params.Abort.Wait(0);
                    if( br == true ) {
                        RKLog.Log( "ExecuteHandbrake aborted." );
                        process.destroy();
                        bAbort = true;
                        break;
                    }
                }

                if( now > (lastProgressChange + TIMEOUT_ENCODING) ) {
                    RKLog.Log( "Progress has not changed for 15 minutes. Failing out..." );
                    process.destroy();
                    bAbort = true;
                    break;
                }

                try {
                    line = bufr.readLine();
                }
                catch (IOException ioex) {
                    break;
                }

                if( line == null ) {
                    break;
                }

                if( line.startsWith("Encoding:") == true ) {
                    try {
                        double  dProgress;
                        int     nProgress;

                        dProgress = HBXExeHelper.ScanHandbrakeProgress( line );
                        nProgress = (int)dProgress;

                        // this is for printing ... spamming the log with progress updates is not helpful
                        // we only output to the log when progress advances by 1%
                        // temporarily obsoleting this ... we assume the callback will handle when to print.
                        if( nProgress > nLastProgress ) {
                            nLastProgress = nProgress;
//                            HBXExeHelper.PrintProgress( line, nProgress );
                        }

                        if( params.Callback != null ) {
                            params.Callback.Process( line, dProgress );
                        }

                        // this is a more granular tracking of progress in hundredths of a percent
                        // we check this because the finer granularity lets us reliably track whether we are hung
                        if( dProgress > dLastProgress ) {
                            dLastProgress = dProgress;
                            lastProgressChange = now;
                        }
                    }
                    catch( Exception ex ) {
                        // just swallow any poorly formatted lines
                    }
                }
                else {
                    RKLog.println(line);
                }
            }

            if( exitCode != Integer.MIN_VALUE ) {
                RKLog.Log( "Handbrake exited with error code %d.", exitCode );
                hbxsp.ExitCode = exitCode;
                break;
            }

            if( bAbort == true ) {
                hbxsp.ExitCode = -1;
                break;
            }
        }

        return hbxsp;
    }

    public static HBXScanParams ExecuteHandbrake(
            Integer handbrakeVersion,
            Integer maxXRes,
            Integer maxYRes,
            String handbrakePath,
            String sourceFilename,
            String targetFilename,
            int xRes,
            int yRes,
            HBXAudioTrack[] audioTracks,
            HBXSubtitleTrack[] subtitleTracks,
            IHandBrakeExeCallback callback,
            RKEvent evAbort ) {

        HBXScanParams       hbxsp;
        Process             process;
        String              line;
        InputStream         is;
        InputStreamReader   isr;
        BufferedReader      bufr;
        ArrayList<String>   cmdArgs;
        int                 nLastProgress;
        double              dLastProgress;
        long                lastProgressChange;
        boolean             bStrictAnamorphic;
        String              sourceExtension;

        hbxsp = new HBXScanParams();
        nLastProgress = -1;
        dLastProgress = -1;
        lastProgressChange = System.currentTimeMillis();

        bStrictAnamorphic = true;
        if( xRes > maxXRes || yRes > maxYRes ) {
            bStrictAnamorphic = false;
        }

        sourceExtension = com.rkuo.io.File.GetExtension(sourceFilename);

        cmdArgs = new ArrayList<String>();
        cmdArgs.add( handbrakePath );

        cmdArgs.add( "--verbose" );
        cmdArgs.add( "1" );

        cmdArgs.add( "--input" );
        cmdArgs.add( sourceFilename );

        cmdArgs.add( "--output" );
        cmdArgs.add( targetFilename );

        cmdArgs.add( "--format" );
        cmdArgs.add( "mp4" );

        cmdArgs.add( "--crop" );
        cmdArgs.add( "0:0:0:0" );

        cmdArgs.add( "--quality" );

        // 1080p encodes can go a little higher on the rate factor (aka lower file size/quality)
        if( yRes <= 720 ) {
            cmdArgs.add( "20" );
        }
        else {
            cmdArgs.add( "23" );
        }

        cmdArgs.add( "--pfr" );
        cmdArgs.add( "--rate" );
        cmdArgs.add( "29.97" );

        cmdArgs.add( "--encoder" );
        cmdArgs.add( "x264" );

        if( sourceExtension.compareToIgnoreCase("ts") == 0 ) {
            cmdArgs.add( "--decomb" );
            cmdArgs.add( "--detelecine" );
        }

        if( handbrakeVersion.compareTo(HANDBRAKE_095) == 0 ) {
            cmdArgs.add( "--x264opts" );
        }
        else {
            cmdArgs.add( "--encopts" );
        }

        if( yRes <= 720 ) {
            // high profile 3.1 settings
            cmdArgs.add( "b-adapt=2:rc-lookahead=50:vbv-maxrate=14000:vbv-bufsize=14000" );
        }
        else {
            // high profile 4.0 settings
            cmdArgs.add( "b-adapt=2:rc-lookahead=50:vbv-maxrate=25000:vbv-bufsize=20000" );
        }

        // eng aac 2.0, jpn aac 2.0, eng ac3 5.1, jpn ac3 5.1
        String  argTrackNumbers, argAEncoder, argMixdown, argARate, argABitrate, argDRC;

        argTrackNumbers = "";
        argAEncoder = "";
        argMixdown = "";
        argARate = "";
        argABitrate = "";
        argDRC = "";

        // encode everything to aac.  aac tracks must come first
        for( Integer x=0; x < audioTracks.length; x++ ) {
            argTrackNumbers += audioTracks[x].TrackNumber.toString() + ",";
            if( OperatingSystem.isMac() == true ) {
                argAEncoder += "ca_aac,";
            }
            else {
                argAEncoder += "faac,";
            }
            argMixdown += "dpl2,";
            argARate += "48,";
            argABitrate += "224,";
            argDRC += "2.0,";
        }

        // ac3 surround tracks come next ... we try to passthru all ac3, but encode to ac3 otherwise
        // try to pass through ac3 (or encode to it if passthru is not possible)
        for( Integer x=0; x < audioTracks.length; x++ ) {
            if( audioTracks[x].Codec.compareToIgnoreCase("ac3") == 0 ) {
                // we don't need any ac3 that isn't surround
                if( audioTracks[x].SurroundNotation.contains("1.0") == true ) {
                    continue;
                }

                if( audioTracks[x].SurroundNotation.contains("2.0") == true ) {
                    continue;
                }

                if( audioTracks[x].SurroundNotation.contains("Dolby Surround") == true ) {
                    continue;
                }

                argTrackNumbers += audioTracks[x].TrackNumber.toString() + ",";
                argAEncoder += "copy:ac3,";
                argMixdown += "auto,";
                argARate += "auto,";
                argABitrate += "auto,";
                argDRC += "0.0,";
                continue;
            }

            if( audioTracks[x].Codec.compareToIgnoreCase("flac") == 0 ) {
                if( audioTracks[x].SurroundNotation.contains("1.0") == true ) {
                    continue;
                }

                if( audioTracks[x].SurroundNotation.contains("2.0") == true ) {
                    continue;
                }

                argTrackNumbers += audioTracks[x].TrackNumber.toString() + ",";
                argAEncoder += "ffac3,";
                argMixdown += "auto,";
                argARate += "auto,";
                argABitrate += "448,";
                argDRC += "0.0,";
                continue;
            }
        }

        argTrackNumbers = argTrackNumbers.substring(0, argTrackNumbers.length()-1);
        argAEncoder = argAEncoder.substring(0, argAEncoder.length()-1);
        argMixdown = argMixdown.substring(0, argMixdown.length()-1);
        argARate = argARate.substring(0, argARate.length()-1);
        argABitrate = argABitrate.substring(0, argABitrate.length()-1);
        argDRC = argDRC.substring(0, argDRC.length()-1);

        cmdArgs.add( "--audio" );
        cmdArgs.add( argTrackNumbers );

        cmdArgs.add( "--aencoder" );
        cmdArgs.add( argAEncoder );

        cmdArgs.add( "--mixdown" );
        cmdArgs.add( argMixdown );

        cmdArgs.add( "--arate" );
        cmdArgs.add( argARate );

        cmdArgs.add( "--ab" );
        cmdArgs.add( argABitrate );

        cmdArgs.add( "--drc" );
        cmdArgs.add( argDRC );

//        cmdArgs.add( "--subtitle" );
//        cmdArgs.add( "1,2,3,4,5,6,7,8,9,10" );

        if( subtitleTracks.length > 0 ) {
            String argSrtFiles, argSrtCodeset, argSrtLanguage, argSrtDefault;

            argSrtFiles = "";
            argSrtCodeset = "";
            argSrtLanguage = "";
            argSrtDefault = "";

            for( int x=0; x < subtitleTracks.length; x++ ) {
                 HBXSubtitleTrack st = subtitleTracks[x];

                argSrtFiles += st.Filename + ",";
                argSrtCodeset += "UTF-8,";
                if( st.Language.length() == 0 ) {
                    argSrtLanguage += "und,";
                }
                else {
                    argSrtLanguage += st.Language + ",";
                }
                if( st.Default == true ) {
                    argSrtDefault = Integer.toString(x+1);
                }
            }

            argSrtFiles = argSrtFiles.substring(0, argSrtFiles.length()-1);
            argSrtCodeset = argSrtCodeset.substring(0, argSrtCodeset.length()-1);
            argSrtLanguage = argSrtLanguage.substring(0, argSrtLanguage.length()-1);

            cmdArgs.add( "--srt-file" );
            cmdArgs.add( argSrtFiles );

            cmdArgs.add( "--srt-codeset" );
            cmdArgs.add( argSrtCodeset );

            cmdArgs.add( "--srt-lang" );
            cmdArgs.add( argSrtLanguage );

            if( argSrtDefault.length() > 0 ) {
                cmdArgs.add( "--srt-default" );
                cmdArgs.add( argSrtDefault );
            }
        }
        
        if( bStrictAnamorphic == true ) {
            cmdArgs.add( "--strict-anamorphic" );
        }
        else {
            cmdArgs.add( "--loose-anamorphic" );
            cmdArgs.add( "--modulus" );
            cmdArgs.add( "4" );

            cmdArgs.add( "--width" );
            cmdArgs.add( maxXRes.toString() );
            cmdArgs.add( "--maxWidth" );
            cmdArgs.add( maxXRes.toString() );
            cmdArgs.add( "--maxHeight" );
            cmdArgs.add( maxYRes.toString() );
        }

        cmdArgs.add( "--markers" );
        cmdArgs.add( "--large-file" );

        // let's print the constructed command line for reference
        String command;

        command = "";
        for( String c : cmdArgs ) {
            command += c + " ";
        }

        RKLog.Log( "ExecuteHandbrake attempting to run HandBrake. Command line follows." );
        RKLog.Log( command );

        // OK, start executing
        try {
            ProcessBuilder      pb;

            pb = new ProcessBuilder(cmdArgs);
            pb.redirectErrorStream( true );

            process = pb.start();
        }
        catch( IOException ioex ) {
            RKLog.Log( "ExecuteHandbrake failed. %s", ioex.getMessage() );
            return null;
        }

        is = process.getInputStream();
        isr = new InputStreamReader(is);

        bufr = new BufferedReader(isr);

        while (true) {
            int     exitCode;
            boolean br;
            boolean bAbort;

            bAbort = false;

            exitCode = Integer.MIN_VALUE;
            try {
                exitCode = process.exitValue();
            }
            catch( IllegalThreadStateException itsex ) {
                // process has not exited yet ... this is ok
            }

            // We do this work before checking the exit code so that any buffered data can be spooled out
            // after reading the exit code but prior to exiting
            while( true ) {

                long    now;

                now = System.currentTimeMillis();

                if( evAbort != null ) {
                    br = evAbort.Wait(0);
                    if( br == true ) {
                        RKLog.Log( "ExecuteHandbrake aborted." );
                        process.destroy();
                        bAbort = true;
                        break;
                    }
                }

                if( now > (lastProgressChange + TIMEOUT_ENCODING) ) {
                    RKLog.Log( "Progress has not changed for 15 minutes. Failing out..." );
                    process.destroy();
                    bAbort = true;
                    break;
                }

                try {
                    line = bufr.readLine();
                }
                catch (IOException ioex) {
                    break;
                }

                if( line == null ) {
                    break;
                }

                if( line.startsWith("Encoding:") == true ) {
                    try {
                        double  dProgress;
                        int     nProgress;

                        dProgress = HBXExeHelper.ScanHandbrakeProgress( line );
                        nProgress = (int)dProgress;

                        // this is for printing ... spamming the log with progress updates is not helpful
                        // we only output to the log when progress advances by 1%
                        // temporarily obsoleting this ... we assume the callback will handle when to print.
                        if( nProgress > nLastProgress ) {
                            nLastProgress = nProgress;
//                            HBXExeHelper.PrintProgress( line, nProgress );
                        }

                        if( callback != null ) {
                            callback.Process( line, dProgress );
                        }

                        // this is a more granular tracking of progress in hundredths of a percent
                        // we check this because the finer granularity lets us reliably track whether we are hung
                        if( dProgress > dLastProgress ) {
                            dLastProgress = dProgress;
                            lastProgressChange = now;
                        }
                    }
                    catch( Exception ex ) {
                        // just swallow any poorly formatted lines
                    }
                }
                else {
                    RKLog.println(line);
                }
            }

            if( exitCode != Integer.MIN_VALUE ) {
                RKLog.Log( "Handbrake exited with error code %d.", exitCode );
                hbxsp.ExitCode = exitCode;
                break;
            }

            if( bAbort == true ) {
                hbxsp.ExitCode = -1;
                break;
            }
        }

        return hbxsp;
    }

/*
    public static HBXScanParams ExecuteHandbrakeForAppleTV2007( String handbrakePath, String sourceFilename, String targetFilename, Integer audioTrack ) {

        HBXScanParams       hbxsp;
        Process             process;
        String              line;
        InputStream is;
        InputStreamReader isr;
        BufferedReader bufr;
        ArrayList<String> cmdArgs;
        double              lastProgress;
        long                lastProgressChange;

        hbxsp = new HBXScanParams();
        lastProgress = -1;
        lastProgressChange = System.currentTimeMillis();

        cmdArgs = new ArrayList<String>();
        cmdArgs.add( handbrakePath );

        cmdArgs.add( "--verbose" );
        cmdArgs.add( "1" );

        cmdArgs.add( "--input" );
        cmdArgs.add( sourceFilename );
        cmdArgs.add( "--output" );
        cmdArgs.add( targetFilename );
        cmdArgs.add( "--format" );
        cmdArgs.add( "mp4" );
        cmdArgs.add( "--width" );
        cmdArgs.add( "1280" );
        cmdArgs.add( "--maxWidth" );
        cmdArgs.add( "1280" );
        cmdArgs.add( "--maxHeight" );
        cmdArgs.add( "720" );
        cmdArgs.add( "--crop" );
        cmdArgs.add( "0:0:0:0" );
        cmdArgs.add( "--encoder" );
        cmdArgs.add( "x264" );
        cmdArgs.add( "--quality" );
        cmdArgs.add( "20" );
        cmdArgs.add( "--rate" );
        cmdArgs.add( "25" );
        cmdArgs.add( "--x264opts" );
        cmdArgs.add( "cabac=1:mixed-refs=1:b-adapt=2:b-pyramid=none:trellis=0:weightp=0:vbv-maxrate=5500:vbv-bufsize=5500" );

        cmdArgs.add( "--audio" );
        if( audioTrack != -1 ) {
            cmdArgs.add( String.format("%d,%d", audioTrack, audioTrack) );
        }
        else {
            cmdArgs.add( "1,1" );
        }

        cmdArgs.add( "--aencoder" );
        if( Misc.isWindows() == true ) {
            cmdArgs.add( "faac,ac3" );
        }
        else {
            cmdArgs.add( "ca_aac,ac3" );
        }
        cmdArgs.add( "--mixdown" );
        cmdArgs.add( "dpl2,auto" );
        cmdArgs.add( "--arate" );
        cmdArgs.add( "48,Auto" );
        cmdArgs.add( "--ab" );
        cmdArgs.add( "160,auto" );
        cmdArgs.add( "--drc" );
        cmdArgs.add( "2.5,0.0" );
        cmdArgs.add( "--subtitle" );
        cmdArgs.add( "1,2,3,4,5,6,7,8,9" );

        cmdArgs.add( "--pfr" );
        cmdArgs.add( "--large-file" );
        cmdArgs.add( "--loose-anamorphic" );
        cmdArgs.add( "--markers" );

        try {
            ProcessBuilder      pb;

            pb = new ProcessBuilder(cmdArgs);
            pb.redirectErrorStream( true );

            process = pb.start();
        }
        catch( IOException ioex ) {
            RKLog.Log( "ExecuteHandbrakeForAppleTV2007 failed. %s", ioex.getMessage() );
            return null;
        }

        is = process.getInputStream();
        isr = new InputStreamReader(is);

        bufr = new BufferedReader(isr);

        while (true) {

            int     exitCode;
            long    now;

            now = System.currentTimeMillis();

            try {
                Thread.sleep( 1000 );
            }
            catch( InterruptedException iex ) {
                break;
            }

            exitCode = Integer.MIN_VALUE;
            try {
                exitCode = process.exitValue();
            }
            catch( IllegalThreadStateException itsex ) {
                // process has not exited yet ... this is ok
            }

            // We put this here so that any buffered data can be spooled out
            // after reading the exit code but prior to exiting
            while( true ) {
                try {
                    line = bufr.readLine();
                }
                catch (IOException ioex) {
                    break;
                }

                if( line == null ) {
                    break;
                }

                if( line.startsWith("Encoding:") == true ) {
                    try {
                        double  progress;
                        progress = HBXExeHelper.ScanHandbrakeProgress( line );

                        if( progress > lastProgress ) {
                            lastProgress = progress;
                            lastProgressChange = now;
                            HBXExeHelper.PrintProgress( line, (int)progress );
                        }
                    }
                    catch( Exception ex ) {
                        // just swallow any poorly formatted lines
                    }
                }
                else {
                    RKLog.println(line);
                }

                if( now > (lastProgressChange + TIMEOUT_ENCODING) ) {
                    RKLog.Log( "Progress has not changed for 30 minutes. Failing out..." );
                    process.destroy();
                    break;
                }
            }

            if( exitCode != Integer.MIN_VALUE ) {
                RKLog.Log( "Handbrake exited with error code %d.", exitCode );
                hbxsp.ExitCode = exitCode;
                break;
            }
        }

        return hbxsp;
    }
 */

    public static void ScanHandbrakeOutput( String line, HBXScanState hbxss, HBXScanParams hbxsp ) {

        String[]  parts;

        if( hbxss.bAudioTrackScanMode == true ) {
            if( line.startsWith("    +") == false ) {
                hbxss.bAudioTrackScanMode = false;
            }
            else {
                HBXAudioTrack   hbxat;
                String  sTrackNumber, sTrackDescription, sLanguage, sCodec, sSurroundNotation;
                Integer nOpenIndex, nCloseIndex;

                hbxat = new HBXAudioTrack();

                parts = line.split(",");

                // get the track number
                sTrackNumber = parts[0];
                sTrackNumber = sTrackNumber.substring(6);
                hbxat.TrackNumber = Integer.parseInt(sTrackNumber);

                // get the track description
                do {
                    sTrackDescription = parts[1];
                    sTrackDescription = sTrackDescription.trim();

                    // get the language
                    nOpenIndex = sTrackDescription.indexOf('(');
                    if( nOpenIndex == -1 ) {
                        break;
                    }

                    sLanguage = sTrackDescription.substring(0, nOpenIndex);
                    sLanguage = sLanguage.trim();
                    hbxat.Language = sLanguage;

                    // get the codec
                    nCloseIndex = sTrackDescription.indexOf(')');
                    if( nCloseIndex == -1 ) {
                        break;
                    }

                    sCodec = sTrackDescription.substring(nOpenIndex+1,nCloseIndex);
                    hbxat.Codec = sCodec;

                    // get the surround notation
                    sTrackDescription = sTrackDescription.substring(nCloseIndex+1);

                    nOpenIndex = sTrackDescription.indexOf('(');
                    if( nOpenIndex == -1 ) {
                        break;
                    }

                    nCloseIndex = sTrackDescription.indexOf(')');
                    if( nCloseIndex == -1 ) {
                        break;
                    }

                    sSurroundNotation = sTrackDescription.substring(nOpenIndex+1,nCloseIndex);
                    hbxat.SurroundNotation = sSurroundNotation;
                } while (false);

                hbxsp.AudioTracks.add( hbxat );
            }
        }

        if( hbxss.bSubtitleTrackScanMode == true ) {
            if( line.startsWith("    +") == false ) {
                hbxss.bSubtitleTrackScanMode = false;
            }
            else {
                HBXSubtitleTrack   hbxst;
                boolean br;

                hbxst = new HBXSubtitleTrack();

                String pattern = "    \\+ (\\d+), (\\w*) \\(iso639-2: (\\w*)\\) \\(\\w*\\)\\(\\w*\\)";
              	Pattern p = Pattern.compile(pattern);
              	Matcher m = p.matcher(line);

                br = m.find();
                if( br == true ) {
                    hbxst.TrackNumber = Integer.parseInt(m.group(1));
                    hbxst.Description = m.group(2);
                    hbxst.Language = m.group(3);
                    hbxsp.SubtitleTracks.add( hbxst );
                }
            }
        }

        if( hbxss.bChapterTrackScanMode == true ) {
            if( line.startsWith("    +") == false ) {
                hbxss.bChapterTrackScanMode = false;
            }
            else {
                boolean br;

                String pattern = "    \\+ (\\d+): cells .*";
              	Pattern p = Pattern.compile(pattern);
              	Matcher m = p.matcher(line);

                br = m.find();
                if( br == true ) {
                    hbxsp.Chapters++;
                }
            }
        }

        if( line.startsWith("No title found.") == true ) {
            hbxsp.Valid = false;
            return;
        }

        if( line.contains("valid title") == true ) {
            hbxsp.Valid = true;
        }

        if( line.startsWith("  + audio tracks:") == true ) {
            // example
//                    + audio tracks:
//                      + 1, English (aac) (2.0 ch) (iso639-2: eng)
//                      + 2, English (AC3) (5.1 ch) (iso639-2: eng), 48000Hz, 640000bps
            hbxss.bAudioTrackScanMode = true;
            return;
        }

        if( line.startsWith("  + subtitle tracks:") == true ) {
            hbxss.bSubtitleTrackScanMode = true;
            return;
        }

        if( line.startsWith("  + chapters:") == true ) {
            hbxss.bChapterTrackScanMode = true;
            return;
        }

        String      tempLine;
        String      sDuration, sSize, sHdVideo;

        sDuration = "+ duration: ";
        sSize = "+ size: ";
        sHdVideo = "hd_video        : ";

        tempLine = line.trim();
        if( tempLine.startsWith(sDuration) == true ) {

            long hours, minutes, seconds;

            tempLine = tempLine.substring( sDuration.length() );

            parts = tempLine.split(":");
            if( parts.length == 3 ) {
                hours = Long.parseLong(parts[0]);
                minutes = Long.parseLong(parts[1]);
                seconds = Long.parseLong(parts[2]);
                hbxsp.Duration = (hours * 60 * 60 * 1000) + (minutes * 60 * 1000) + (seconds * 1000);
            }
        }
        else if( tempLine.startsWith(sSize) == true ) {
            tempLine = tempLine.substring( sSize.length() );

            parts = tempLine.split(",");
            if( parts.length > 0 ) {
                parts = parts[0].split("x");
                if( parts.length == 2 ) {
                    hbxsp.XRes = Integer.parseInt(parts[0]);
                    hbxsp.YRes = Integer.parseInt(parts[1]);
                }
            }
        }
        else if( tempLine.startsWith(sHdVideo) == true ) {
            tempLine = tempLine.substring( sHdVideo.length() );
            hbxsp.HdVideo = Integer.parseInt(tempLine);
        }

        return;
    }

    public static HBXScanParams ExecuteHandbrakeScanFromFile( String sourceFilename ) {

        HBXScanState hbxss = new HBXScanState();
        HBXScanParams hbxsp = new HBXScanParams();

        BufferedReader input;

        try {
            input = new BufferedReader(new FileReader(sourceFilename));
        }
        catch( Exception ex ) {
            return null;
        }

        try {
            while (true) {
                String              line;

                try {
                    line = input.readLine();
                }
                catch (IOException ioex) {
                    break;
                }

                if( line == null ) {
                    break;
                }

                ScanHandbrakeOutput( line, hbxss, hbxsp );
            }
        }
        catch( Exception ex ) {
            RKLog.Log("ExecuteHandbrakeScanFromFile exceptioned");
        }
        finally {
            Misc.close(input);
        }

        return hbxsp;
    }

    public static HBXScanParams ExecuteHandbrakeScan( String handbrakePath, String sourceFilename, boolean bEcho ) {

        Process             process;
        String              line;
        InputStream         is;
        InputStreamReader   isr;
        BufferedReader      bufr;
        ArrayList<String>   cmdArgs;

        HBXScanState hbxss = new HBXScanState();
        HBXScanParams hbxsp = new HBXScanParams();

        cmdArgs = new ArrayList<String>();
        cmdArgs.add( handbrakePath );

        cmdArgs.add( "--input" );
        cmdArgs.add( sourceFilename );

        cmdArgs.add( "--scan" );

        try {
            ProcessBuilder      pb;

            pb = new ProcessBuilder(cmdArgs);
            pb.redirectErrorStream( true );

            process = pb.start();
        }
        catch( IOException ioex ) {
            RKLog.Log( "ExecuteHandbrakeScan failed. %s", ioex.getMessage() );
            return null;
        }

        is = process.getInputStream();
        isr = new InputStreamReader(is);
        bufr = new BufferedReader(isr);

        while (true) {

            int exitCode;

            exitCode = Integer.MIN_VALUE;
            try {
                exitCode = process.exitValue();
            }
            catch( IllegalThreadStateException itsex ) {
                // process has not exited yet ... this is ok
            }

            // We put this here so that any buffered data can be spooled out
            // after reading the exit code but prior to exiting
            while( true ) {

                try {
                    line = bufr.readLine();
                }
                catch (IOException ioex) {
                    break;
                }

                if( line == null ) {
                    break;
                }

                if( bEcho == true ) {
                    RKLog.println(line);
                }

                ScanHandbrakeOutput( line, hbxss, hbxsp );
            }

            if( exitCode != Integer.MIN_VALUE ) {
                RKLog.Log( "Handbrake exited with error code %d.", exitCode );
                hbxsp.ExitCode = exitCode;
                break;
            }
        }

        return hbxsp;
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

    public static int ExecuteMKVDTS2AC3( String scriptPath, String workingDir, String sourceFilename, Integer audioTrack ) {

        Process             process;
        String              processWorkingDir;
        String              line;
        InputStream         is;
        InputStreamReader   isr;
        BufferedReader      bufr;
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
                if( now > (lastUpdate + TIMEOUT_AC3_CONVERSION) ) {
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
                        progress = HBXExeHelper.ScanMKVDTS2AC3Progress( line );

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

        Misc.printArgs( cmdArgs.toArray(new String[cmdArgs.size()]) );
        exitCode = Misc.ExecuteProcess( cmdArgs.toArray( new String[cmdArgs.size()]), null, false, null, null );
        if( exitCode != 0 ) {
            RKLog.Log("mkvmerge remux failed.");
            return exitCode;
        }

        RKLog.Log("Remuxing complete.");
        return exitCode;
    }
}
