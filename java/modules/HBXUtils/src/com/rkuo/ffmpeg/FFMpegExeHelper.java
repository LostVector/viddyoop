package com.rkuo.ffmpeg;

import com.rkuo.handbrake.IHandBrakeExeCallback;
import com.rkuo.logging.RKLog;
import com.rkuo.util.ExecuteProcessCallback;
import com.rkuo.util.Misc;

import java.util.ArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class FFMpegExeHelper {

    public static Long GetExactFrameCount( String ffMpegExe, String sourceFilename ) {

        ArrayList<String> cmdArgs;
        int exitCode;

        FrameCountCallback fc = new FrameCountCallback();

        // remux the file to avoid bugs
        RKLog.Log("Decoding file to get exact frame count.");
        cmdArgs = new ArrayList<String>();
        cmdArgs.add( ffMpegExe );
        cmdArgs.add( "-i" );
        cmdArgs.add( sourceFilename );
        cmdArgs.add( "-vcodec" );
        cmdArgs.add( "copy" );
        cmdArgs.add( "-f" );
        cmdArgs.add( "rawvideo" );
        cmdArgs.add( "-y" );
        cmdArgs.add( "/dev/null" );

        Misc.printArgs(cmdArgs.toArray(new String[cmdArgs.size()]));
        exitCode = Misc.ExecuteProcess( cmdArgs.toArray( new String[cmdArgs.size()]), null, false, null, fc );
        if( exitCode != 0 ) {
            RKLog.Log("ffmpeg failed.");
            return null;
        }

        RKLog.Log("GetExactFrameCount complete.");
        return fc.frames;
    }


}