package com.rkuo.Executables;

import com.rkuo.handbrake.*;
import com.rkuo.logging.RKLog;
import com.rkuo.mkvtoolnix.MKVExeHelper;
import com.rkuo.mkvtoolnix.MKVTrack;
import com.rkuo.shared.HBXEncodingStats;
import com.rkuo.subtitles.SrtEntry;
import com.rkuo.subtitles.SrtParser;
import com.rkuo.threading.RKEvent;
import com.rkuo.util.FileUtils;
import com.rkuo.util.Misc;
import com.rkuo.util.OperatingSystem;

import java.io.*;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

public abstract class HBXBaseWrapperLogic implements IHBXExecutor {

    private final static Integer MAX_XRES = 1920;
    private final static Integer MAX_YRES = 1080;
//    private static long SCAN_DELAY_MS = 60000;
//    private static long EVENT_LOOP_DELAY_MS = 1000;
//    private static long TIMEOUT_COPY = 15 * 60 * 1000; // 15 min (in ms)
//    private static String PREFERRED_WORKING_DIR = "/private/tmp/hbxwrapper";

    /*
    Method stub for pulling common resources to the local node that will be used to process the work.
     */
    protected abstract boolean PullResources( HBXWrapperParams hbxwp, HBXWrapperLogicState state );

    /*
    Method stub for pulling the main file or file(s) to work on to the local node.
     */
    protected abstract boolean PullOriginal( HBXWrapperLogicState state );

    /*
    Method stub for pushing the final output back to a specific remote location
     */
    protected abstract boolean PushEncoded( HBXWrapperLogicState state );

    /*
    Method stub for pushing encoding stats to a specific remote location
     */
    protected abstract boolean PushOutputStats( HBXEncodingStats hbxes, HBXWrapperLogicState state );

    protected IHandBrakeExeCallback GetHandBrakeCallback() {
        return null;
    }

    protected boolean PreExecute( HBXWrapperParams hbxwp ) {
        return true;
    }

    protected void HBXCleanup(String directoryName) {
        return;
    }

    public void ReportError(String failedFilename) {
        return;
    }

    public boolean Execute(HBXWrapperParams hbxwp) {

        HBXWrapperLogicState    state;
        HBXEncodingStats hbxes;
        HBXScanParams hbxsp;
        Date dateStarted;

        boolean                 br;

        state = new HBXWrapperLogicState();

        dateStarted = new Date();

        br = PreExecute( hbxwp );
        if( br == false ) {
            RKLog.Log( "PreExecute failed." );
            return false;
        }

        RKLog.Log( "Remote Sources Username: %s", hbxwp.Username );
        RKLog.Log( "Remote Sources Hostname: %s", hbxwp.Hostname );
        RKLog.Log( "Remote Resources Username: %s", hbxwp.ResourcesUsername );
        RKLog.Log( "Remote Resources Hostname: %s", hbxwp.ResourcesHostname );
        RKLog.Log( "Remote Resources Location: %s", hbxwp.ResourcesLocation );
        RKLog.Log( "Remote Resources HandBrake: %s", hbxwp.Handbrake_x64 );
        RKLog.Log( "Remote Resources MkvInfo: %s", hbxwp.MKVInfo );
        RKLog.Log( "Remote Resources MkvExtract: %s", hbxwp.MKVExtract );
        RKLog.Log( "Remote Source: %s", hbxwp.Source);
        RKLog.Log( "Remote Target: %s", hbxwp.Target );
        RKLog.Log( "Stats: %s", hbxwp.Stats );
//        RKLog.Log( "Handbrake_x86: %s", hbxwp.Handbrake_x86 );
        RKLog.Log( "Handbrake_x64: %s", hbxwp.Handbrake_x64 );
        RKLog.Log( "TempDirectory: %s", hbxwp.TempDirectory );

        br = InitializeParams( hbxwp, state );
        if( br == false ) {
            ReportError(state.fFailed.getAbsolutePath());
            return false;
        }

        // pull handbrake executables for encoding
        br = PullResources( hbxwp, state );
        if( br == false ) {
            ReportError(state.fFailed.getAbsolutePath());
            return false;
        }

        RKLog.Log( "Testing whether to use x86 or x64 executable." );
        state.handbrakeExe = GetHandbrakeExe( state.fLocalHandBrake32, state.fLocalHandBrake64 );
        if( state.handbrakeExe == null ) {
            RKLog.Log( "Couldn't find or run Handbrake executables." );
            ReportError(state.fFailed.getAbsolutePath());
            return false;
        }

        RKLog.Log( "Using exe: %s", state.handbrakeExe );

        new File(hbxwp.TempDirectory).mkdir();

        // remove leftover files from previous runs (this assumes only one instance per agent)
        RKLog.Log("Cleaning up %s.", hbxwp.TempDirectory);
        HBXCleanup( hbxwp.TempDirectory );

        // pull the original from the network share to the local drive for encoding
        br = PullOriginal( state );
        if( br == false ) {
            ReportError(state.fFailed.getAbsolutePath());
            return false;
        }
/*
        MKVTrack[]  tracks;

        tracks = HBXExeHelper.ExecuteMKVInfo( "/private/tmp/.xsfs/bin/Mkvtoolnix.app/Contents/MacOS/mkvinfo", hbxwp.Source );
        if( tracks == null ) {
            RKLog.Log( "MKVInfo on %s failed.", fLocalSource.getAbsolutePath() );
            return;
        }

        int nTrackNumber = MKVTrack.FindMKVAudioTrack( tracks, "A_DTS", "eng", 6 );
 */

        // scan the original
        hbxsp = VerifyOriginal( state );
        if( hbxsp == null ) {
            ReportError(state.fFailed.getAbsolutePath());
            return false;
        }

        // Go!
        hbxes = EncodeOriginal( hbxsp, state );
        if( hbxes == null ) {
            ReportError(state.fFailed.getAbsolutePath());
            return false;
        }

        RKLog.Log( "Encoded size is %d.", state.fLocalTarget.length() );

        // push back the encoded file to the network share
        br = PushEncoded( state );
        if( br == false ) {
            ReportError(state.fFailed.getAbsolutePath());
            return false;
        }

        hbxes.DateStarted = dateStarted;
        hbxes.DateFinished = new Date();

        // all done ... write some stats and quit out
        RKLog.Log( "Writing stats to %s...", state.fStats.getAbsolutePath() );
        PushOutputStats( hbxes, state );
        return true;
    }

    private static boolean InitializeParams( HBXWrapperParams hbxwp, HBXWrapperLogicState state ) {

        state.fSource = new File( hbxwp.Source );
        state.fTarget = new File( hbxwp.Target );
        state.fStats = new File( hbxwp.Stats );

        state.fTempTarget = new File( hbxwp.Target + ".tmp" );

        state.fLocalSource = new File( FileUtils.PathCombine(hbxwp.TempDirectory,state.fSource.getName()) );
        state.fLocalTarget = new File( FileUtils.PathCombine(hbxwp.TempDirectory,state.fTarget.getName()) );
        state.fLocalStats = new File( FileUtils.PathCombine(hbxwp.TempDirectory,state.fStats.getName()) );

        state.fFailed = new File( hbxwp.Source + ".failed.txt" );

        state.Username = hbxwp.Username;
        state.Password = hbxwp.Password;
        state.Hostname = hbxwp.Hostname;

        state.ResourcesUsername = hbxwp.ResourcesUsername;
        state.ResourcesPassword = hbxwp.ResourcesPassword;
        state.ResourcesHostname = hbxwp.ResourcesHostname;

        state.PrimaryLanguage = hbxwp.PrimaryLanguage;
        state.SecondaryLanguage = hbxwp.SecondaryLanguage;

        state.Abort = hbxwp.Abort;

        return true;
    }

    protected static HBXScanParams VerifyOriginal( HBXWrapperLogicState state ) {

        HBXScanParams   hbxsp;

        // Verify source
        RKLog.Log( "Scanning source %s.",  state.fLocalSource.getAbsolutePath() );
        hbxsp = HBXExeHelper.ExecuteHandbrakeScan(state.handbrakeExe, state.fLocalSource.getAbsolutePath(), true);
        if( hbxsp == null ) {
            RKLog.Log( "HandbrakeScan on %s failed.", state.fLocalSource.getAbsolutePath() );
            return null;
        }

        if( hbxsp.Valid == false ) {
            RKLog.Log( "HandbrakeScan: Could not parse %s.", state.fLocalSource.getAbsolutePath() );
            return null;
        }

        if( hbxsp.XRes > 0 ) {
            RKLog.Log( "OriginalXRes: %d", hbxsp.XRes );
        }

        if( hbxsp.YRes > 0 ) {
            RKLog.Log( "OriginalYRes: %d", hbxsp.YRes );
        }

        if( hbxsp.Duration > 0 ) {
            RKLog.Log( "Duration: %s (%d ms)", Misc.GetTimeString(hbxsp.Duration), hbxsp.Duration );
        }

        return hbxsp;
    }

    protected HBXEncodingStats EncodeOriginal( HBXScanParams hbxspIn, HBXWrapperLogicState state ) {

        HBXEncodingStats            hbxes;
        HBXScanParams               hbxsp;
        HBXSubtitleTrack[] subtitleTracks;
        HBXAudioTrack               trackPrimary;
        HBXAudioTrack               trackJapanese;

        IHandBrakeExeCallback callback = GetHandBrakeCallback();            // we have to ping this every 10 minutes or the job will die!
        boolean isMkv = false;

        ArrayList<HBXAudioTrack> aAudioTracks = new ArrayList<HBXAudioTrack>();
//        ArrayList<String> aSubtitleTracks = new ArrayList<String>();

        hbxes = new HBXEncodingStats();
        hbxes.Name = state.fLocalSource.getName();
        hbxes.OriginalLength = state.fLocalSource.length();
        hbxes.OriginalXRes = hbxspIn.XRes;
        hbxes.OriginalYRes = hbxspIn.YRes;

        if( com.rkuo.io.File.GetExtension(state.fLocalSource.getName()).compareToIgnoreCase("mkv") == 0 ) {
            isMkv = true;
        }

        if( hbxspIn.AudioTracks.size() == 0 ) {
            RKLog.Log( "EncodeOriginal: At least one audio track is required." );
            return null;
        }

        trackPrimary = GetPrimaryTrack( state.PrimaryLanguage, hbxspIn );
        if( trackPrimary != null ) {
            aAudioTracks.add( trackPrimary );
        }

        // for anime, we want to add the original japanese track on top of the english one
        trackJapanese = GetBestAudioTrack( state.SecondaryLanguage, hbxspIn );
        if( trackJapanese != null ) {
            if( trackPrimary == null ) {
                aAudioTracks.add( trackJapanese );
            }
            else {
                // If the japanese track = primary track, don't add it again.
                if( trackJapanese.TrackNumber.equals(trackPrimary.TrackNumber) == false ) {
                    aAudioTracks.add( trackJapanese );
                }
            }
        }

        // if we didn't find any preferred audio tracks to encode, just use the first one
        if( aAudioTracks.size() == 0 ) {
            aAudioTracks.add( hbxspIn.AudioTracks.get(0) );
        }

        // If this is a MKV, extract the subtitles and clean them up
        if( isMkv == true ) {
            MKVTrack[] tracks;

            tracks = MKVExeHelper.ExecuteMKVInfo(state.mkvInfoExe, state.fLocalSource.getAbsolutePath());
            if( tracks == null ) {
                RKLog.Log( "EncodeOriginal: ExecuteMKVInfo failed." );
                return null;
            }

            subtitleTracks = ExtractMKVSubtitles( state.mkvExtractExe, state.ssaConverterExe, state.fLocalSource.getAbsolutePath(), tracks, callback );
            if( subtitleTracks == null ) {
                RKLog.Log( "EncodeOriginal: ExtractMKVSubtitles failed." );
                return null;
            }
        }
        else {
            subtitleTracks = new HBXSubtitleTrack[0];
        }

        // Encode source
        RKLog.Log( "Encoding source %s to target %s.", state.fLocalSource.getAbsolutePath(), state.fLocalTarget.getAbsolutePath() );
        hbxes.EncodingStarted = new Date();
        HandBrakeExeParams params = GenerateHandbrakeParams(
                state.handbrakeExe,
                state.fLocalSource.getAbsolutePath(),
                state.fLocalTarget.getAbsolutePath(),
                hbxspIn.XRes,
                hbxspIn.YRes,
                aAudioTracks.toArray(new HBXAudioTrack[aAudioTracks.size()]),
                subtitleTracks,
                OperatingSystem.isMac(),
                callback,
                state.Abort
        );

        hbxsp = HBXExeHelper.ExecuteHandbrake(params);
//        hbxsp = HBXExeHelper.ExecuteHandbrake098ForAppleTV2012(
//                state.handbrakeExe,
//                state.fLocalSource.getAbsolutePath(),
//                state.fLocalTarget.getAbsolutePath(),
//                hbxspIn.XRes,
//                hbxspIn.YRes,
//                aAudioTracks.toArray( new HBXAudioTrack[aAudioTracks.size()] ),
//                subtitleTracks,
//                callback,
//                state.Abort );
        if( hbxsp == null ) {
            RKLog.Log( "Handbrake: Encoding %s failed.", state.fLocalTarget.getAbsolutePath() );
            return null;
        }

        if( hbxsp.ExitCode != 0 ) {
            RKLog.Log( "Handbrake: Error code - %d", hbxsp.ExitCode );
            return null;
        }

        hbxes.EncodingFinished = new Date();
        RKLog.Log( "Handbrake: Encoding finished." );

        // Verify output
        RKLog.Log( "Scanning target %s.", state.fLocalTarget.getAbsolutePath() );
        hbxsp = HBXExeHelper.ExecuteHandbrakeScan( state.handbrakeExe, state.fLocalTarget.getAbsolutePath(), true );
        if( hbxsp == null ) {
            RKLog.Log( "HandbrakeScan on %s failed.", state.fLocalTarget.getAbsolutePath() );
            return null;
        }

        if( hbxsp.Valid == false ) {
            RKLog.Log( "HandbrakeScan: Could not parse %s.", state.fLocalTarget.getAbsolutePath() );
            return null;
        }

        if( (hbxspIn.Duration > 0) && (hbxsp.Duration > 0) ) {
            double  percentageDeviation;

            percentageDeviation = (double)Math.abs(hbxsp.Duration - hbxspIn.Duration) / (double)hbxspIn.Duration;
            if( percentageDeviation > 0.05 ) {
                RKLog.Log( "HandbrakeScan: Original duration is %s. Encoded duration is %s. (Deviation of more than 5 pct is an error.)", Misc.GetTimeString(hbxspIn.Duration), Misc.GetTimeString(hbxsp.Duration) );
                return null;
            }

            RKLog.Log( "HandbrakeScan: %s looks valid.", state.fLocalTarget.getAbsolutePath() );
        }

        hbxes.EncodedLength = new File(state.fLocalTarget.getAbsolutePath()).length();
        hbxes.EncodedXRes = hbxsp.XRes;
        hbxes.EncodedYRes = hbxsp.YRes;
        hbxes.Duration = hbxsp.Duration;

        try {
            hbxes.MachineName = InetAddress.getLocalHost().getHostName();
        }
        catch( UnknownHostException uhex ) {
            // non-fatal
        }

        return hbxes;
    }

    protected static String GetHandbrakeExe( File f32, File f64 ) {

        int     exitCode;

        // try 64 bit version first
        if( f64 != null ) {
            exitCode = Misc.ExecuteProcess( f64.getAbsolutePath() );
            if( exitCode == 1 ) {
                return f64.getAbsolutePath();
            }
        }

        if( f32 != null ) {
            exitCode = Misc.ExecuteProcess( f32.getAbsolutePath() );
            if( exitCode == 1 ) {
                return f32.getAbsolutePath();
            }
        }

        return null;
    }

    protected static HBXAudioTrack GetPrimaryTrack( String language, HBXScanParams hbxsp ) {

        // look for first english ac3 5.1 track
        RKLog.Log( "Looking for a %s AC3 5.x track.", language );
        for( HBXAudioTrack at : hbxsp.AudioTracks ) {
            if( at.Language.compareToIgnoreCase(language) == 0 ) {
                if( at.Codec.compareToIgnoreCase("ac3") == 0 ) {
                    if( at.SurroundNotation.toLowerCase().contains("5.1") == true ) {
                        RKLog.Log( "Using audio track %d. (%s, %s, %s).", at.TrackNumber, at.Language, at.Codec, at.SurroundNotation );
                        return at;
                    }

                    if( at.SurroundNotation.toLowerCase().contains("5.0") == true ) {
                        RKLog.Log( "Using audio track %d. (%s, %s, %s).", at.TrackNumber, at.Language, at.Codec, at.SurroundNotation );
                        return at;
                    }
                }
            }
        }

        // otherwise, look for the first 5.1 track
        RKLog.Log( "Looking for any AC3 5.x track." );
        for( HBXAudioTrack at : hbxsp.AudioTracks ) {
            if( at.Codec.compareToIgnoreCase("ac3") == 0 ) {
                if( at.SurroundNotation.toLowerCase().contains("5.1") == true ) {
                    RKLog.Log( "Using audio track %d. (%s, %s, %s).", at.TrackNumber, at.Language, at.Codec, at.SurroundNotation );
                    return at;
                }

                if( at.SurroundNotation.toLowerCase().contains("5.0") == true ) {
                    RKLog.Log( "Using audio track %d. (%s, %s, %s).", at.TrackNumber, at.Language, at.Codec, at.SurroundNotation );
                    return at;
                }
            }
        }

        // otherwise, just look any english track
        RKLog.Log( "Looking for any %s track.", language );
        for( HBXAudioTrack at : hbxsp.AudioTracks ) {
            if( at.Language.compareToIgnoreCase(language) == 0 ) {
                RKLog.Log( "Using audio track %d. (%s, %s, %s).", at.TrackNumber, at.Language, at.Codec, at.SurroundNotation );
                return at;
            }
        }

        return null;
    }

    /*
    This will find the best specified language track.  Right now, used for anime.
     */
    protected static HBXAudioTrack GetBestAudioTrack( String language, HBXScanParams hbxsp ) {

        // look for the first ac3 5.1 track in the specified language
        for( HBXAudioTrack at : hbxsp.AudioTracks ) {
            if( at.Language.compareToIgnoreCase(language) == 0 ) {
                if( at.Codec.compareToIgnoreCase("ac3") == 0 ) {
                    if( at.SurroundNotation.toLowerCase().contains("5.1") == true ) {
                        RKLog.Log( "Using audio track %d. (%s, %s, %s).", at.TrackNumber, at.Language, at.Codec, at.SurroundNotation );
                        return at;
                    }

                    if( at.SurroundNotation.toLowerCase().contains("5.0") == true ) {
                        RKLog.Log( "Using audio track %d. (%s, %s, %s).", at.TrackNumber, at.Language, at.Codec, at.SurroundNotation );
                        return at;
                    }
                }
            }
        }

        // otherwise, just look for the first track in the specified language
        for( HBXAudioTrack at : hbxsp.AudioTracks ) {
            if( at.Language.compareToIgnoreCase(language) == 0 ) {
                RKLog.Log( "Using audio track %d. (%s, %s, %s).", at.TrackNumber, at.Language, at.Codec, at.SurroundNotation );
                return at;
            }
        }

        return null;
    }

    /*
        This will extract all subtitles as srt's for repair and reinsertion.
     */
    public static HBXSubtitleTrack[] ExtractMKVSubtitles( String exePath, String ssaExePath, String mkvPath, MKVTrack[] tracks, IHandBrakeExeCallback callback ) {

        ArrayList<HBXSubtitleTrack> aSubtitleTracks;

        aSubtitleTracks = new ArrayList<HBXSubtitleTrack>();

        for (MKVTrack t : tracks ) {
            if( t.Type.compareToIgnoreCase("subtitles") == 0 ) {
                String subBase, subExt;
                String mappedLanguageCode;
                File fMKV, fSub, fFinalSub;
                File fDir;
                int nr;

                HBXSubtitleTrack subtitleTrack = new HBXSubtitleTrack();

                mappedLanguageCode = ISO6392CodeBibliographicToTerminology(t.Language);

                if( t.CodecID.compareToIgnoreCase("S_TEXT/UTF8") == 0 ) {
                    subExt = "srt";
                }
                else if( t.CodecID.compareToIgnoreCase("S_TEXT/ASS") == 0 ) {
                    subExt = "ass";
                }
                else if( t.CodecID.compareToIgnoreCase("S_TEXT/SSA") == 0 ) {
                    subExt = "ssa";
                }
                else {
                    System.out.format("Unrecognized subtitle format. (%s)\n",t.CodecID);
                    continue;
                }

                fMKV = new File(mkvPath);
                fDir = fMKV.getParentFile();

                subBase = String.format("%d_%s",t.TrackId,mappedLanguageCode);
                fSub = new File(FileUtils.PathCombine(fDir.getAbsolutePath(),subBase + "." + subExt));
                fFinalSub = new File(FileUtils.PathCombine(fDir.getAbsolutePath(),subBase + "." + subExt));

                RKLog.Log("Extracting subtitle to %s.\n",fSub.getName());
                nr = MKVExeHelper.ExtractMKVSubtitle(exePath, mkvPath, fSub.getAbsolutePath(), t.TrackId, callback);
                if( nr != 0 ) {
                    RKLog.Log("ExtractMKVSubtitle to %s failed.\n", fSub.getName());
                    continue;
                }

                RKLog.Log("Extracted %s.",fSub.getName());

                if( subExt.compareToIgnoreCase("ass") == 0 || subExt.compareToIgnoreCase("ssa") == 0 ) {
                    ArrayList<String> args;

                    fFinalSub = new File(FileUtils.PathCombine(fDir.getAbsolutePath(),subBase + ".srt"));

                    args = new ArrayList<String>();
                    args.add("python");
                    args.add(ssaExePath);
                    args.add(fSub.getAbsolutePath());
                    args.add(fFinalSub.getAbsolutePath());

                    RKLog.Log("Converting %s to %s.",fSub.getName(),fFinalSub.getName());
                    nr = Misc.ExecuteProcess( args.toArray(new String[args.size()]) );
                    if( nr != 0 ) {
                        RKLog.Log("ssa2srt.py failed.\n");
                        continue;
                    }

                    RKLog.Log("Converted %s to %s.",fSub.getName(),fFinalSub.getName());
                }

                subtitleTrack.TrackNumber = t.TrackNumber;
                subtitleTrack.TrackId = t.TrackId;
                subtitleTrack.Filename = fFinalSub.getAbsolutePath();
                subtitleTrack.Language = mappedLanguageCode;
                subtitleTrack.Default = t.Default;

                aSubtitleTracks.add( subtitleTrack );

                if( callback != null ) {
                    callback.KeepAlive( String.format("Ready to process %s\n", fFinalSub.getName()) );
                }
            }
        }

        // repair srt's, if needed
        for( HBXSubtitleTrack s : aSubtitleTracks ) {
            List<SrtEntry> entries = SrtParser.parseFileStrict(s.Filename);
            if( entries == null ) {
                entries = SrtParser.parseFileLoose(s.Filename);
                if( entries != null ) {
                    String tempSub = s.Filename + ".tmp";
                    boolean br = SrtParser.writeFile(tempSub,entries);
                    if( br == true ) {
                        File fOriginalSrt = new File(s.Filename);
                        fOriginalSrt.delete();
                        new File(tempSub).renameTo(fOriginalSrt);
                    }
                }
            }
        }

        return aSubtitleTracks.toArray(new HBXSubtitleTrack[aSubtitleTracks.size()]);
    }

    // Converts three letter bibliographic code to terminology code (which is the data we get from mkvinfo)
    protected static String ISO6392CodeBibliographicToTerminology(String code) {

        String outCode;

        if( code.compareToIgnoreCase("alb") == 0 ) {
            outCode = "sqi";
        }
        else if( code.compareToIgnoreCase("arm") == 0 ) {
            outCode = "hye";
        }
        else if( code.compareToIgnoreCase("baq") == 0 ) {
            outCode = "eus";
        }
        else if( code.compareToIgnoreCase("bur") == 0 ) {
            outCode = "mya";
        }
        else if( code.compareToIgnoreCase("chi") == 0 ) {
            outCode = "zho";
        }
        else if( code.compareToIgnoreCase("cze") == 0 ) {
            outCode = "ces";
        }
        else if( code.compareToIgnoreCase("dut") == 0 ) {
            outCode = "nld";
        }
        else if( code.compareToIgnoreCase("fre") == 0 ) {
            outCode = "fra";
        }
        else if( code.compareToIgnoreCase("geo") == 0 ) {
            outCode = "kat";
        }
        else if( code.compareToIgnoreCase("ger") == 0 ) {
            outCode = "deu";
        }
        else if( code.compareToIgnoreCase("gre") == 0 ) {
            outCode = "ell";
        }
        else if( code.compareToIgnoreCase("ice") == 0 ) {
            outCode = "isl";
        }
        else if( code.compareToIgnoreCase("mac") == 0 ) {
            outCode = "mkd";
        }
        else if( code.compareToIgnoreCase("may") == 0 ) {
            outCode = "msa";
        }
        else if( code.compareToIgnoreCase("per") == 0 ) {
            outCode = "fas";
        }
        else if( code.compareToIgnoreCase("rum") == 0 ) {
            outCode = "ron";
        }
        else if( code.compareToIgnoreCase("slo") == 0 ) {
            outCode = "slk";
        }
        else if( code.compareToIgnoreCase("tib") == 0 ) {
            outCode = "bod";
        }
        else if( code.compareToIgnoreCase("wel") == 0 ) {
            outCode = "cym";
        }
        else {
            outCode = code;
        }

        return outCode;
    }

    public static HandBrakeExeParams GenerateHandbrakeParams(
            String handbrakeExe,
            String sourceFilename,
            String targetFilename,
            int xRes,
            int yRes,
            HBXAudioTrack[] audioTracks,
            HBXSubtitleTrack[] subtitleTracks,
            boolean isMac,
            IHandBrakeExeCallback callback,
            RKEvent abort) {

        HandBrakeExeParams params;
        String sourceExtension;
        boolean             bStrictAnamorphic;
        boolean             bFullHD;

        params = new HandBrakeExeParams();
        sourceExtension = com.rkuo.io.File.GetExtension(sourceFilename);

        bStrictAnamorphic = true;
        if( xRes > MAX_XRES || yRes > MAX_YRES ) {
            bStrictAnamorphic = false;
        }

        bFullHD = false;
        if( (xRes > 1280) || (yRes > 720) ) {
            bFullHD = true;
        }

        params.Verbose = 1;
        params.Input = sourceFilename;
        params.Output = targetFilename;
        params.Format = HandBrakeExeParams.DestinationFormat.MP4;

        params.Crop = HandBrakeExeParams.PictureCropOption.Strict;
        params.CropTop = 0;
        params.CropBottom = 0;
        params.CropLeft = 0;
        params.CropRight = 0;

        // fix
        params.Quality = 20.0;
        if( bFullHD == true ) {
            params.Quality = 23.0;
        }

        params.RateControl = HandBrakeExeParams.VideoFrameRateControlOption.PEAK_LIMITED;
        params.Rate = HandBrakeExeParams.VideoRateOption.FRAMERATE_29_97;

        params.Encoder = HandBrakeExeParams.VideoEncoderOption.X264;

        // high profile 3.1 settings
        params.EncodingOptions = "b-adapt=2:rc-lookahead=50:vbv-maxrate=14000:vbv-bufsize=14000";
        if( bFullHD == true ) {
            // high profile 4.0 settings
            params.EncodingOptions = "b-adapt=2:rc-lookahead=50:vbv-maxrate=25000:vbv-bufsize=20000";
        }

        // encode everything to mono or (usually) stereo aac.  aac tracks must come first
        for( Integer x=0; x < audioTracks.length; x++ ) {
            params.AudioTracks.add(audioTracks[x].TrackNumber);
            if( isMac == true ) {
                params.AudioEncoders.add(HandBrakeExeParams.AudioEncoderOption.CA_AAC);
            }
            else {
                params.AudioEncoders.add(HandBrakeExeParams.AudioEncoderOption.FAAC);
            }

            params.AudioMixdowns.add(HandBrakeExeParams.AudioMixdownOption.DPL2);
            params.AudioSampleRates.add(48);
            params.AudioBitrates.add(224);
            params.AudioDynamicRangeCompressions.add(2.0);
        }

        // ac3 surround tracks come next ... we try to passthru all ac3, but encode to ac3 otherwise
        // we ignore stereo tracks because the aac encoding above already takes care of that
        for( Integer x=0; x < audioTracks.length; x++ ) {
            if( audioTracks[x].SurroundNotation.contains("1.0") == true ) {
                continue;
            }

            if( audioTracks[x].SurroundNotation.contains("2.0") == true ) {
                continue;
            }

            if( audioTracks[x].SurroundNotation.contains("Dolby Surround") == true ) {
                continue;
            }

            if( audioTracks[x].Codec.compareToIgnoreCase("ac3") == 0 ) {
                params.AudioTracks.add(audioTracks[x].TrackNumber);
                params.AudioEncoders.add(HandBrakeExeParams.AudioEncoderOption.COPY_AC3);
                params.AudioMixdowns.add(HandBrakeExeParams.AudioMixdownOption.AUTO);
                params.AudioSampleRates.add(0);
                params.AudioBitrates.add(0);
                params.AudioDynamicRangeCompressions.add(0.0);
                continue;
            }

            if( audioTracks[x].Codec.compareToIgnoreCase("flac") == 0 ) {
                params.AudioTracks.add(audioTracks[x].TrackNumber);
                params.AudioEncoders.add(HandBrakeExeParams.AudioEncoderOption.FFAC3);
                params.AudioMixdowns.add(HandBrakeExeParams.AudioMixdownOption.AUTO);
                params.AudioSampleRates.add(0);
                params.AudioBitrates.add(448);
                params.AudioDynamicRangeCompressions.add(0.0);
                continue;
            }
        }

        // subtitles
        if( subtitleTracks.length > 0 ) {
            for( int x=0; x < subtitleTracks.length; x++ ) {
                HBXSubtitleTrack st = subtitleTracks[x];

                params.SrtFiles.add(st.Filename);
                params.SrtCodesets.add("UTF-8");
                if( st.Language.length() == 0 ) {
                    params.SrtLanguages.add("und");
                }
                else {
                    params.SrtLanguages.add(st.Language);
                }
                if( st.Default == true ) {
                    params.SrtDefault = x+1;
                }
            }
        }

        if( bStrictAnamorphic == true ) {
            params.Anamorphic = HandBrakeExeParams.PictureAnamorphicOption.STRICT;
        }
        else {
            params.Anamorphic = HandBrakeExeParams.PictureAnamorphicOption.LOOSE;
            params.Modulus = 4;

            params.Width = MAX_XRES;
            params.MaxWidth = MAX_XRES;
            params.MaxHeight = MAX_YRES;
        }

        params.Markers = true;
        params.LargeFile = true;

        if( sourceExtension.compareToIgnoreCase("ts") == 0 ) {
            params.Decomb = true;
            params.Detelecine = true;
        }

        params.Executable = handbrakeExe;
        params.Callback = callback;
        params.Abort = abort;
        return params;
    }
}
