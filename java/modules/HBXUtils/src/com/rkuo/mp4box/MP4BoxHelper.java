package com.rkuo.mp4box;

import com.rkuo.subtitles.SrtEntry;
import com.rkuo.subtitles.SrtParser;
import com.rkuo.logging.RKLog;
import com.rkuo.mkvtoolnix.MKVTrack;
import com.rkuo.util.FileUtils;
import com.rkuo.util.Misc;

import java.io.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Scanner;
import java.util.regex.Matcher;

public class MP4BoxHelper {

    public static List<MKVTrack> ExecuteMP4BoxScan( String mp4boxPath, String sourceFilename, boolean bEcho ) {

        Process process;
        String line;
        InputStream is;
        InputStreamReader isr;
        BufferedReader bufr;
        StringWriter writer;

//        MP4ScanState mp4ss = new MP4ScanState();

        ArrayList<String> cmdArgs = new ArrayList<String>();
        cmdArgs.add( mp4boxPath );

        cmdArgs.add( "-info" );
        cmdArgs.add( sourceFilename );

        try {
            ProcessBuilder      pb;

            pb = new ProcessBuilder(cmdArgs);
            pb.redirectErrorStream( true );

            process = pb.start();
        }
        catch( IOException ioex ) {
            RKLog.Log( "ExecuteMP4BoxScan failed. %s", ioex.getMessage() );
            return null;
        }

        is = process.getInputStream();
        isr = new InputStreamReader(is);
        bufr = new BufferedReader(isr);

        writer = new StringWriter();
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

//                ScanMP4BoxOutput(line, mp4ss);
                writer.write(line + "\n");
            }

            if( exitCode != Integer.MIN_VALUE ) {
                RKLog.Log( "MP4Box exited with error code %d.", exitCode );
                break;
            }
        }

        MP4ScanState mp4ss = ParseMP4BoxScan(writer.toString());
        return mp4ss.tracks;
    }

    public static MP4ScanState ParseMP4BoxScan(String text) {
        MP4ScanState mp4ss = new MP4ScanState();

        BufferedReader bufr = new BufferedReader(new StringReader(text));
        while (true) {
            String line;

            try {
                line = bufr.readLine();
            }
            catch (IOException ioex) {
                break;
            }

            if(line == null) {
                break;
            }

            ScanMP4BoxOutput(line, mp4ss);
        }

        return mp4ss;
    }

    public static List<MKVTrack> ExecuteMP4BoxInfoFromFile( String sourceFilename ) {

        MP4ScanState mp4ss = new MP4ScanState();

        BufferedReader input;

        try {
            input = new BufferedReader(new FileReader(sourceFilename));
        }
        catch( Exception ex ) {
            return null;
        }

        try {
            while (true) {
                String line;

                try {
                    line = input.readLine();
                }
                catch (IOException ioex) {
                    break;
                }

                if( line == null ) {
                    break;
                }

                ScanMP4BoxOutput(line, mp4ss);
            }
        }
        catch( Exception ex ) {
            RKLog.Log("ExecuteMP4BoxInfoFromFile exceptioned.");
        }
        finally {
            Misc.close(input);
        }

        return mp4ss.tracks;
    }

    public static void ScanMP4BoxOutput( String line, MP4ScanState mp4ss ) {

        if( line.startsWith("Track # ") == true ) {
            boolean br;

          	Matcher m = mp4ss.rpTrack.matcher(line);

            br = m.find();
            if( br == true ) {
                mp4ss.currentTrack = new MKVTrack();
                mp4ss.currentTrack.TrackNumber = Integer.parseInt(m.group(1));
                mp4ss.currentTrack.TrackId = Integer.parseInt(m.group(2));
            }
            else {
                RKLog.Log("Couldn't parse line: %s", line);
            }
        }
        else if( mp4ss.currentTrack != null ) {
            if( line.startsWith("Media Info: ") == true ) {
                boolean br;

              	Matcher m = mp4ss.rpMediaInfo.matcher(line);

                br = m.find();
                if( br == true ) {
                    mp4ss.currentTrack.Language = m.group(1);
                    mp4ss.currentTrack.CodecID = m.group(2);
                    mp4ss.currentTrack.Samples = Integer.parseInt(m.group(3));
                }
                else {
                    RKLog.Log("Couldn't parse line: %s", line);
                }
            }
            else if( line.startsWith("Alternate Group ID ") == true ) {
                boolean br;

              	Matcher m = mp4ss.rpAlternateGroupId.matcher(line);

                br = m.find();
                if( br == true ) {
                    mp4ss.currentTrack.GroupId = Integer.parseInt(m.group(1));
                }
                else {
                    RKLog.Log("Couldn't parse line: %s", line);
                }
            }
            else if( line.startsWith("Track is disabled") == true ) {
                mp4ss.currentTrack.Disabled = true;
            }
            else if( line.length() == 0 ) {
                mp4ss.tracks.add(mp4ss.currentTrack);
                mp4ss.currentTrack = null;
            }
            else {
                // unexpected?
            }
        }

        return;
    }

    // with the help of a temp directory, this wraps the repair function so that it is non destructive
    public static boolean CleanRepairM4VSubtitlesWithBlankLines( String filename, String mp4box, String tempDirectory ) {

        boolean br;

        try {
            String tempFilename, tempMove;
            int nr;

            new File(tempDirectory).mkdirs();
            FileUtils.CleanDirectory(new File(tempDirectory));

            tempFilename = FileUtils.PathCombine( tempDirectory, new File(filename).getName() );
            tempMove = filename + ".tmp";

            RKLog.Log("Copying %s to repair directory.",filename);
            br = FileUtils.Copy(filename,tempFilename);
            if( br == false ) {
                return false;
            }

            nr = MP4BoxHelper.RepairM4VSubtitlesWithBlankLines(tempFilename,mp4box);
            if( nr < 0 ) {
                return false;
            }

            if( nr > 0 ) {
                RKLog.Log("Copying repaired file back to source directory.",filename);
                br = FileUtils.Copy(tempFilename,tempMove);
                if( br == false ) {
                    return false;
                }

                br = new File(filename).delete();
                if( br == false ) {
                    return false;
                }

                br = new File(tempMove).renameTo( new File(filename) );
                if( br == false ) {
                    return false;
                }
            }
        }
        catch( Exception ex ) {
            RKLog.println(ex.getMessage());
        }
        finally {
            FileUtils.CleanDirectory(new File(tempDirectory));
        }

        return true;
    }

    // this is destructive during the remove/add process.  It will also leave junk in the directory
    public static int RepairM4VSubtitlesWithBlankLines( String filename, String mp4box ) {

        List<MKVTrack> tracks;
        boolean br;

        List<Map.Entry<MKVTrack,String>> aSubs;
        List<Map.Entry<MKVTrack,String>> aSubsRepaired = new java.util.ArrayList<Map.Entry<MKVTrack,String>>();
        File f = new File(filename);

        // scan the file
        RKLog.Log("MP4Box (direct): (%s)", f.getAbsolutePath());

        tracks = MP4BoxHelper.ExecuteMP4BoxScan(mp4box, filename, true);
        if( tracks == null ) {
            RKLog.Log("MP4Box scan of %s failed.", f.getAbsolutePath());
            return -1;
        }

        // extract each subtitle as srt
        aSubs = MP4BoxHelper.ExtractSrts(f, tracks, mp4box);
        if( aSubs == null ) {
            RKLog.Log("ExtractSrts failed.");
            return -1;
        }

        // find any subtitles that don't pass strict validation, but that the loose parser can read
        // repair files read by the loose parser
        for( Map.Entry<MKVTrack,String> sub : aSubs ) {
            List<SrtEntry> srtEntries;
            File fOriginalSrt = new File(sub.getValue());
            File fRepairedSrt = new File(
                    FileUtils.PathCombine(fOriginalSrt.getParent(),String.format("%d.repaired.srt",sub.getKey().TrackId))
                    );

            srtEntries = SrtParser.parseFileStrict(sub.getValue());
            if( srtEntries != null ) {
                RKLog.Log("Track %d - SrtParser::parseFileStrict succeeded.", sub.getKey().TrackId);
                continue;
            }

            RKLog.Log("Track %d - SrtParser::parseFileStrict failed. Trying parseFileLoose.", sub.getKey().TrackId);

            srtEntries = SrtParser.parseFileLoose(sub.getValue());
            if( srtEntries == null ) {
                RKLog.Log("Track %d - SrtParser::parseFileLoose returned null.", sub.getKey().TrackId);
                continue;
            }

            RKLog.Log("Track %d - SrtParser::parseFileLoose succeeded. Writing repaired file.", sub.getKey().TrackId);

            br = SrtParser.writeFile( fRepairedSrt.getAbsolutePath(), srtEntries );
            if( br == false ) {
                RKLog.Log("Track %d - SrtParser::writeFile failed.", sub.getKey().TrackId);
                continue;
            }

            Map.Entry<MKVTrack,String> e = new java.util.AbstractMap.SimpleEntry<MKVTrack,String>(sub.getKey(),fRepairedSrt.getAbsolutePath());
            aSubsRepaired.add(e);

            RKLog.Log("Track %d - SrtParser::writeFile succeeded.", sub.getKey().TrackId);
        }

        if( aSubsRepaired.size() == 0 ) {
            RKLog.Log("No subtitles repaired.");
            return 0;
        }

        // Remove all subs that need repair
        ArrayList<MKVTrack> aSubsRemove = new ArrayList<MKVTrack>();

        for( Map.Entry<MKVTrack,String> sub : aSubsRepaired ) {
            aSubsRemove.add(sub.getKey());
        }

        br = MP4BoxHelper.RemoveSubtitles(f, aSubsRemove, mp4box );
        if( br == false ) {
            RKLog.Log("RemoveSubtitles failed.");
            return -1;
        }

        // Add back all repaired subs
        br = MP4BoxHelper.AddSubtitles(f, aSubsRepaired, mp4box );
        if( br == false ) {
            RKLog.Log("AddSubtitles failed.");
            return -1;
        }

        return aSubsRepaired.size();
    }

    // with the help of a temp directory, this wraps the repair function so that it is non destructive
    public static boolean CleanRepairM4VSubtitlesWithLinefeeds( String filename, String mp4box, String tempDirectory ) {

        boolean br;

        try {
            String tempFilename, tempMove;
            int nr;

            new File(tempDirectory).mkdirs();
            FileUtils.CleanDirectory(new File(tempDirectory));

            tempFilename = FileUtils.PathCombine( tempDirectory, new File(filename).getName() );
            tempMove = filename + ".tmp";

            RKLog.Log("Copying %s to repair directory.",filename);
            br = FileUtils.Copy(filename,tempFilename);
            if( br == false ) {
                return false;
            }

            nr = MP4BoxHelper.RepairM4VSubtitlesWithLinefeeds(tempFilename,mp4box);
            if( nr < 0 ) {
                return false;
            }

            if( nr > 0 ) {
                RKLog.Log("Copying repaired file back to source directory.",filename);
                br = FileUtils.Copy(tempFilename,tempMove);
                if( br == false ) {
                    return false;
                }

                br = new File(filename).delete();
                if( br == false ) {
                    return false;
                }

                br = new File(tempMove).renameTo( new File(filename) );
                if( br == false ) {
                    return false;
                }
            }
        }
        catch( Exception ex ) {
            RKLog.println(ex.getMessage());
        }
        finally {
            FileUtils.CleanDirectory(new File(tempDirectory));
        }

        return true;
    }

    // this locates and repairs subtitles with the windows linefeed + formatting crash issue
    // this is destructive during the remove/add process.  It will also leave junk in the directory of the file being repaired
    public static int RepairM4VSubtitlesWithLinefeeds( String filename, String mp4box ) {

        List<MKVTrack> tracks;
        boolean br;

        List<Map.Entry<MKVTrack,String>> aSubs;
        List<Map.Entry<MKVTrack,String>> aSubsRepaired = new java.util.ArrayList<Map.Entry<MKVTrack,String>>();
        File f = new File(filename);

        // scan the file
        RKLog.Log("MP4Box (direct): (%s)", f.getAbsolutePath());

        tracks = MP4BoxHelper.ExecuteMP4BoxScan(mp4box, filename, true);
        if( tracks == null ) {
            RKLog.Log("MP4Box scan of %s failed.", f.getAbsolutePath());
            return -1;
        }

        // extract each subtitle as srt
        aSubs = MP4BoxHelper.ExtractSrts(f, tracks, mp4box);
        if( aSubs == null ) {
            RKLog.Log("ExtractSrts failed.");
            return -1;
        }

        // just assume all tracks need fixing for now.
        for( Map.Entry<MKVTrack,String> sub : aSubs ) {
            Map.Entry<MKVTrack,String> e = new java.util.AbstractMap.SimpleEntry<MKVTrack,String>(sub.getKey(),sub.getValue());
            aSubsRepaired.add(e);
        }

        if( aSubsRepaired.size() == 0 ) {
            RKLog.Log("No subtitles repaired.");
            return 0;
        }

        // Remove all subs that need repair
        ArrayList<MKVTrack> aSubsRemove = new ArrayList<MKVTrack>();

        for( Map.Entry<MKVTrack,String> sub : aSubsRepaired ) {
            aSubsRemove.add(sub.getKey());
        }

        br = MP4BoxHelper.RemoveSubtitles(f, aSubsRemove, mp4box);
        if( br == false ) {
            RKLog.Log("RemoveSubtitles failed.");
            return -1;
        }

        // Add back all repaired subs
        br = MP4BoxHelper.AddSubtitles(f, aSubsRepaired, mp4box);
        if( br == false ) {
            RKLog.Log("AddSubtitles failed.");
            return -1;
        }

        return aSubsRepaired.size();
    }

    public static List<Map.Entry<MKVTrack,String>> ExtractSrts( File f, List<MKVTrack> tracks, String mp4box ) {

        List<Map.Entry<MKVTrack,String>> aSubs = new java.util.ArrayList<Map.Entry<MKVTrack,String>>();

        for( MKVTrack t : tracks ) {
            int nr;

            String filenameBase = FileUtils.getAbsolutePathWithoutExtension(f.getAbsolutePath());
            File fExtractedSrt = new File(String.format("%s_%d_text.srt",filenameBase,t.TrackId));

            if( t.CodecID.compareToIgnoreCase("sbtl:tx3g") != 0 ) {
                continue;
            }

            // extract the srt
            ArrayList<String> cmdArgs = new ArrayList<String>();
            cmdArgs.add(mp4box);

            cmdArgs.add( "-srt" );
            cmdArgs.add( String.format("%d",t.TrackId) );
            cmdArgs.add( f.getAbsolutePath() );

            nr = Misc.ExecuteProcess(cmdArgs.toArray(new String[cmdArgs.size()]), null, false, null, null);
            if( nr != 0 ) {
                RKLog.Log("MP4Box srt extraction of track id %d for %s failed.", t.TrackId, f.getAbsolutePath());
                return null;
            }

            Map.Entry<MKVTrack,String> e = new java.util.AbstractMap.SimpleEntry<MKVTrack,String>(t,fExtractedSrt.getAbsolutePath());
            aSubs.add(e);
        }

        return aSubs;
    }

    public static boolean RemoveSubtitles( File f, List<MKVTrack> tracks, String mp4box ) {

        int nr;

        ArrayList<String> cmdArgs = new ArrayList<String>();

        cmdArgs.add( mp4box );
        for( MKVTrack t : tracks ) {
            RKLog.Log("Removing trackID %d from %s.", t.TrackId, f.getAbsolutePath());

            cmdArgs.add( "-rem" );
            cmdArgs.add( String.format("%d",t.TrackId) );
        }

        cmdArgs.add( f.getAbsolutePath() );

        nr = Misc.ExecuteProcess( cmdArgs.toArray(new String[cmdArgs.size()]), null, false, null, null );
        if( nr != 0 ) {
            RKLog.Log("Removing tracks failed for %s.", f.getAbsolutePath());
            return false;
        }

        return true;
    }

    public static boolean AddSubtitles( File f, List<Map.Entry<MKVTrack,String>> tracks, String mp4box ) {

        ArrayList<String> cmdArgs = new ArrayList<String>();
        cmdArgs.add( mp4box );

        for( Map.Entry<MKVTrack,String> sub : tracks ) {
            RKLog.Log("Adding back trackID %d to %s.", sub.getKey().TrackId, f.getAbsolutePath());

            cmdArgs.add("-add");

            String options = String.format("%s:lang=%s",sub.getValue(),sub.getKey().Language);
            if( sub.getKey().GroupId != -1 ) {
                options += String.format(":group=%d",sub.getKey().GroupId);
            }

            if( sub.getKey().Disabled == true ) {
                options += ":disable";
            }

            cmdArgs.add( options );
        }

        cmdArgs.add( f.getAbsolutePath() );

        int nr = Misc.ExecuteProcess( cmdArgs.toArray(new String[cmdArgs.size()]), null, false, null, null );
        if( nr != 0 ) {
            RKLog.Log("Adding subtitle(s) failed for %s.", f.getAbsolutePath());
            return false;
        }

        return true;
    }

    public static boolean SetLanguage( File f, Integer trackId, String language, String mp4box ) {

        int nr;

        ArrayList<String> cmdArgs = new ArrayList<String>();

        cmdArgs.add( mp4box );
        cmdArgs.add( "-lang" );
        cmdArgs.add( String.format("%d=%s",trackId,language) );
        cmdArgs.add( f.getAbsolutePath() );

        nr = Misc.ExecuteProcess( cmdArgs.toArray(new String[cmdArgs.size()]), f.getParent(), false, null, null );
        if( nr != 0 ) {
            RKLog.Log("SetLanguage failed for %s.", f.getAbsolutePath());
            return false;
        }

        return true;
    }

}
