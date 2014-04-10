package com.rkuo.Executables;

import com.rkuo.handbrake.*;
import com.rkuo.logging.RKLog;
import com.rkuo.util.FileUtils;
import com.rkuo.util.Misc;

import java.io.*;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Date;

/**
 * Created by IntelliJ IDEA.
 * User: rkuo
 * Date: Oct 3, 2010
 * Time: 1:31:38 PM
 * To change this template use File | Settings | File Templates.
 */
public class HBXWrapperLogic extends HBXBaseWrapperLogic {

//    private static long SCAN_DELAY_MS = 60000;
//    private static long EVENT_LOOP_DELAY_MS = 1000;
    private static long TIMEOUT_COPY = 15 * 60 * 1000; // 15 min (in ms)
    private static String PREFERRED_WORKING_DIR = "/private/tmp/hbxwrapper";

    @Override
    public boolean PreExecute( HBXWrapperParams hbxwp ) {
        // default to the standard OSX temp directory.  Allow specifying the temp directory (useful if running on Windows)
        if( hbxwp.TempDirectory.length() == 0 ) {
            // locally invoked instance
            hbxwp.TempDirectory = PREFERRED_WORKING_DIR;
        }

        return true;
    }
/*
    private static boolean InitializeParams( HBXWrapperParams hbxwp, HBXWrapperLogicState state ) {

        state.fSource = new File( hbxwp.Source );
        state.fTarget = new File( hbxwp.Target );
        state.fStats = new File( hbxwp.Stats );

        state.fXml = new File( hbxwp.Source + ".xgrid.xml" );
        state.fTempTarget = new File( hbxwp.Target + ".tmp" );

        state.fLocalSource = new File( FileUtils.PathCombine(hbxwp.TempDirectory,state.fSource.getName()) );
        state.fLocalTarget = new File( FileUtils.PathCombine(hbxwp.TempDirectory,state.fTarget.getName()) );

        state.fFailed = new File( hbxwp.Source + ".failed.txt" );

        state.handbrakeExe = GetHandbrakeExe( hbxwp.Handbrake_x86, hbxwp.Handbrake_x64 );
        if( state.handbrakeExe == null ) {
            RKLog.Log( "Couldn't find Handbrake executables." );
            return false;
        }

        state.Abort = hbxwp.Abort;

        RKLog.Log( "Using exe: %s", state.handbrakeExe );
        return true;
    }
 */

    @Override
    protected boolean PullResources( HBXWrapperParams hbxwp, HBXWrapperLogicState state ) {
        File    fTemp;

        fTemp = new File(hbxwp.Handbrake_x64);
        state.fLocalHandBrake64 = new File( FileUtils.PathCombine(hbxwp.TempDirectory, fTemp.getName()) );

//        fTemp = new File(hbxwp.Handbrake_x86);
//        state.fLocalHandBrake32 = new File( FileUtils.PathCombine(hbxwp.TempDirectory, fTemp.getName()) );
        return true;
    }

    @Override
    protected boolean PullOriginal( HBXWrapperLogicState state ) {

        // network IO is too unstable, so instead we copy it to the local drive before starting the encode.
        long    lSourceLength, lLocalSourceLength;
        boolean br;

        // Read the value first in case there is a network error later
        lSourceLength = state.fSource.length();
        if( lSourceLength == 0 ) {
            RKLog.Log( "Length of %s is 0. File does not exist?", state.fSource.getAbsolutePath() );
            return false;
        }

        // now do the fault tolerant copy to the local drive
        RKLog.Log( "Copying %s to local drive. Source length is %d.", state.fSource.getName(), lSourceLength );
        br = com.rkuo.io.File.ReliableCopy( state.fSource.getAbsolutePath(), state.fLocalSource.getAbsolutePath(), TIMEOUT_COPY );
        if( br == false ) {
            RKLog.Log( "Copying %s to local drive failed.", state.fSource.getName() );
            return false;
        }

        lLocalSourceLength = state.fLocalSource.length();
        if( lSourceLength != lLocalSourceLength ) {
            RKLog.Log( "Error: Source length is %d. Local source length is %d.", lSourceLength, lLocalSourceLength );
            return false;
        }

        RKLog.Log( "Copy succeeded. Local source length is %d.", lLocalSourceLength );
        return true;
    }

    @Override
    protected boolean PushOutputStats( HBXEncodingStats hbxes, HBXWrapperLogicState state ) {

        OutputStreamWriter osw;
        boolean br;

        osw = Misc.GetUTF8FileWriter( state.fLocalStats.getAbsolutePath() );
        if( osw == null ) {
            return false;
        }

        try {
            osw.write( hbxes.Serialize() );
            osw.close();
        }
        catch( IOException ioex ) {
            return false;
        }

        br = com.rkuo.io.File.ReliableCopy( state.fLocalStats.getAbsolutePath(), state.fStats.getAbsolutePath(), TIMEOUT_COPY );
        if( br == false ) {
            return false;
        }

        return true;
    }

    @Override
    protected boolean PushEncoded( HBXWrapperLogicState state ) {

        boolean br;

        // Be nice and delete the local source since we're done with it.  Xgrid should reap this, but whatever
        br = state.fLocalSource.delete();
        if( br == false ) {
            RKLog.Log( "Failed to delete %s.", state.fLocalSource.getAbsolutePath() );
        }
        else {
            RKLog.Log( "Deleted %s.", state.fLocalSource.getAbsolutePath() );
        }

        // network IO is too unstable so we execute locally.  Copy the file back to the target.
        RKLog.Log( "Copy %s back to network drive as %s...", state.fLocalTarget.getName(), state.fTempTarget.getName() );
        com.rkuo.io.File.ReliableCopy( state.fLocalTarget.getAbsolutePath(), state.fTempTarget.getAbsolutePath(), TIMEOUT_COPY );
        if( br == false ) {
            RKLog.Log( "Copying %s back to network drive failed.", state.fLocalTarget.getName() );
            return false;
        }

        if( state.fLocalTarget.length() != state.fTempTarget.length() ) {
            RKLog.Log( "Local target size (%d) does not match temp target size. (%d)", state.fLocalTarget.length(), state.fTempTarget.length() );
            return false;
        }

        RKLog.Log( "Copy back to network drive succeeded." );

        // Move from tmp to final target
        br = state.fTempTarget.renameTo( state.fTarget );
        if( br == false ) {
            RKLog.Log( "Failed to rename to %s.", state.fTarget.getAbsolutePath() );
            return false;
        }

        RKLog.Log( "Renamed to %s.", state.fTarget.getAbsolutePath() );

        // Be nice and delete the local target since we're done with it.  Xgrid should reap this, but whatever
        br = state.fLocalTarget.delete();
        if( br == false ) {
            RKLog.Log( "Failed to delete %s.", state.fLocalTarget.getAbsolutePath() );
        }
        else {
            RKLog.Log( "Deleted %s.", state.fLocalTarget.getAbsolutePath() );
        }

        File fXml = new File( state.fSource.getAbsolutePath() + ".xgrid.xml" );

        br = fXml.delete();
        if( br == false ) {
            RKLog.Log( "Failed to delete %s.", fXml.getAbsolutePath() );
        }
        else {
            RKLog.Log( "Deleted %s.", fXml.getAbsolutePath() );
        }

        // Delete the source
        // It is important that this is the last step as the subsequent Xgrid tasks checks
        // whether or not the source has been deleted to determine the success of this program.
        br = state.fSource.delete();
        if( br == false ) {
            RKLog.Log( "Failed to delete %s.", state.fSource.getAbsolutePath() );
        }
        else {
            RKLog.Log( "Deleted %s.", state.fSource.getAbsolutePath() );
        }

        return true;
    }

    @Override
    protected void HBXCleanup(String directoryName) {

        File d;
        String[] filenames;

        d = new File(directoryName);

        for( String supportedFiletype : HBXConstants.SupportedFiletypes ) {
            filenames = FileUtils.ListFiles(d, supportedFiletype);
            if (filenames != null) {
                for (String filename : filenames) {
                    File f;

                    f = new File(filename);
                    f.delete();
                }
            }
        }

        filenames = FileUtils.ListFiles(d, ".m4v");
        if (filenames != null) {
            for (String filename : filenames) {
                File f;

                f = new File(filename);
                f.delete();
            }
        }

        filenames = FileUtils.ListFiles(d, ".tmp");
        if (filenames != null) {
            for (String filename : filenames) {
                File f;

                f = new File(filename);
                f.delete();
            }
        }

        return;
    }

    @Override
    public void ReportError( String failedFilename ) {

        RKLog.Log( "Throwing taskError = failJob to Xgrid and exiting..." );

//        try {
//            File f = new File(failedFilename);
//
//            OutputStream out = new FileOutputStream(f);
//
//            out.close();
//        }
//        catch (FileNotFoundException ex) {
//            System.out.println( ex.getMessage() + " in the specified directory." );
//        }
//        catch (IOException ioex) {
//            System.out.println( ioex.getMessage() );
//        }

        // seems like we need to sleep for a bit to let xgrid catch our output.
        // Doesn't matter if we flush.  Sloppy...
        try {
            Thread.sleep( 30 * 1000 );
        }
        catch( InterruptedException iex ) {
            // do nothing
        }

        RKLog.println( "<xgrid>{control = taskError; taskError = failJob;}</xgrid>" );
        return;
    }

    @Override
    protected IHandBrakeExeCallback GetHandBrakeCallback() {
        return new HBXXgridHandBrakeExeCallback();
    }
}
