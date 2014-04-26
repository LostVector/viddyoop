package com.rkuo.mapreduce.mrx264;

import com.rkuo.handbrake.HBXAudioTrack;
import com.rkuo.logging.RKLog;
import com.rkuo.mkvtoolnix.MKVExeHelper;
import com.rkuo.mkvtoolnix.MKVTrack;
import com.rkuo.util.FileUtils;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.LongWritable;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.mapreduce.Mapper;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Created with IntelliJ IDEA.
 * User: rkuo
 * Date: 4/17/14
 * Time: 8:34 PM
 * To change this template use File | Settings | File Templates.
 */
public class HBXMapper264 extends Mapper<LongWritable,Text,LongWritable,Text> {

    protected String Username;
    protected String Password;
    protected String Hostname;

    protected String Source;

    protected String MKVInfo;
    protected String MKVMerge;
    protected String MKVExtract;

    @Override
    protected void setup(Mapper.Context context) throws IOException, InterruptedException {
        Configuration c = context.getConfiguration();

        Username = c.get("username");
        Password = c.get("password");
        Hostname = c.get("hostname");

        Source = c.get("source");

        MKVInfo = c.get("mkvinfo");
        MKVMerge = c.get("mkvmerge");
        MKVExtract = c.get("mkvextract");
        return;
    }

    // map will assign a key to the text value (for text input format, this is a line number)
    // since in this case our function does no real work, we will assign a key of 1 to any value we receive
    // this will cause only 1 reduce task to be run
    @Override
	protected void map(LongWritable key, Text value, Context context) throws IOException, InterruptedException {

        List<String> files = new ArrayList<String>();
        int nr;

        // get frame count
//        Long frames = FFMpegExeHelper.GetExactFrameCount(ffMpeg, Source);

        MKVTrack[] tracks;
        int vTrackId, aTrackId;
        String timecode;

        tracks = MKVExeHelper.ExecuteMKVInfo(MKVInfo, Source);
        if( tracks == null ) {
            RKLog.Log( "EncodeOriginal: ExecuteMKVInfo failed." );
            return;
        }

//        File d = new File(".");
//        d.getCanonicalPath();

        vTrackId = SelectPreferredVideoTrack(tracks);
        if( vTrackId == -1 ) {
            throw new IOException("SelectPreferredVideoTrack failed.");
        }

        // extract video timecodes
        timecode = MKVExeHelper.ExtractTimecodes(MKVExtract,Source,vTrackId);
        if( timecode == null ) {
            throw new IOException("ExtractTimecodes failed on video track.");
        }

        // chunk the input file's video according to a fixed duration (60s)
        // this can be improved eventually so that we use a stats file to intelligently decide where to split
        files = MKVExeHelper.Split(MKVMerge, Source);
        if( files == null ) {
            throw new IOException("Split failed.");
        }

        // extract the input file's audio
//        files = MKVExeHelper.Split(MKVInfo,Source);
//        if( files == null ) {
//            throw new IOException("Split failed.");
//        }

        aTrackId = SelectPreferredAudioTrack("eng",tracks);
        if( aTrackId == -1 ) {
            throw new IOException("SelectPreferredAudioTrack failed.");
        }

        FileSystem fs = FileSystem.get(context.getConfiguration());

        context.getConfiguration().get("mapred.output.dir");

        // distribute
        for( int y=0; y < files.size(); y++ ) {
            String file = files.get(y);
            String hdfsFile = FileUtils.PathCombine("/user/vidoop",file);
            fs.copyFromLocalFile(new Path(file),new Path(hdfsFile));
            context.write(new LongWritable(y),new Text(hdfsFile));
        }

        return;
	}

    // simply the first video track for now
    protected int SelectPreferredVideoTrack( MKVTrack[] tracks ) {

        for( MKVTrack t : tracks ) {
            if( t.Type.compareTo("video") == 0) {
                return t.TrackId;
            }
        }

        return -1;
    }

    protected int SelectPreferredAudioTrack( String language, MKVTrack[] tracks ) {

        // look for first english ac3 5.x track
        RKLog.Log( "Looking for a %s AC3 5.x track.", language );
        for( MKVTrack t : tracks ) {
            if( t.Language.compareToIgnoreCase(language) == 0 ) {
                if( t.CodecID.compareToIgnoreCase("A_AC3") == 0 ) {
                    if( (t.Channels == 5) || (t.Channels == 6) ) {
                        RKLog.Log( "Using audio track %d. (%s, %s, %d).", t.TrackId, t.Language, t.CodecID, t.Channels );
                        return t.TrackId;
                    }
                }
            }
        }

        // otherwise, look for the first 5.1 track
        RKLog.Log( "Looking for any AC3 5.x track." );
        for( MKVTrack t : tracks ) {
            if( t.CodecID.compareToIgnoreCase("A_AC3") == 0 ) {
                if( (t.Channels == 5) || (t.Channels == 6) ) {
                    RKLog.Log( "Using audio track %d. (%s, %s, %s).", t.TrackId, t.Language, t.CodecID, t.Channels );
                    return t.TrackId;
                }
            }
        }

        // otherwise, just look for any english track
        RKLog.Log( "Looking for any %s track.", language );
        for( MKVTrack t : tracks ) {
            if( t.Language.compareToIgnoreCase(language) == 0 ) {
                RKLog.Log( "Using audio track %d. (%s, %s, %s).", t.TrackId, t.Language, t.CodecID, t.Channels );
                return t.TrackId;
            }
        }

        return -1;
    }
}
