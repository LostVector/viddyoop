package com.rkuo.Executables;

import com.rkuo.shared.HBXEncodingStats;
import com.rkuo.handbrake.HBXWrapperParams;
import com.rkuo.handbrake.IHandBrakeExeCallback;
import com.rkuo.logging.RKLog;
import com.rkuo.net.ssh.ISSHProgressCallback;
import com.rkuo.net.ssh.Scp;
import com.rkuo.net.ssh.Sftp;
import com.rkuo.util.FileUtils;
import com.rkuo.util.Misc;

import java.io.*;
import java.util.ArrayList;

public class HBXHadoopWrapperLogic extends HBXBaseWrapperLogic {

    protected IHandBrakeExeCallback callback;
    protected ISSHProgressCallback sshCallback;

    public HBXHadoopWrapperLogic( IHandBrakeExeCallback callback ) {
        this.callback = callback;
        sshCallback = new HadoopWrapperProgressCallback( this.callback );
        return;
    }

    protected boolean SetFilePermissions( String filename ) {

        int nr;

        ArrayList<String> args = new ArrayList<String>();

        args.add("chmod");
        args.add("755");
        args.add(filename);
        nr = Misc.ExecuteProcess(args.toArray(new String[args.size()]));
        if (nr != 0) {
//            RKLog.Log("chmod of %s failed.", filename);
            return false;
        }

        return true;
    }

    /*
    Retrieves an entire remote directory to a local temp directory and sets the permissions for certain
    special executables appropriately.
     */
    @Override
    protected boolean PullResources(HBXWrapperParams hbxwp, HBXWrapperLogicState state) {

        boolean br;
        String[] aResources;
        File fTemp;

        Sftp s = new Sftp( state.ResourcesUsername, state.ResourcesPassword, state.ResourcesHostname );
        Scp scp = new Scp( state.ResourcesUsername, state.ResourcesPassword, state.ResourcesHostname );

        aResources = s.SftpList(hbxwp.ResourcesLocation);
        for( int x=0; x < aResources.length; x++ ) {
            File fRemote = new File( FileUtils.PathCombine(hbxwp.ResourcesLocation,aResources[x])) ;
            File fLocal = new File( FileUtils.PathCombine(hbxwp.TempDirectory,aResources[x])) ;

            br = scp.ScpFrom(fRemote.getAbsolutePath(), fLocal.getAbsolutePath(), this.sshCallback );
            if (br == false) {
                RKLog.Log("SCP %s to local drive failed.", aResources[x]);
                return false;
            }
        }

        fTemp = new File(hbxwp.Handbrake_x64);
        state.fLocalHandBrake64 = new File(FileUtils.PathCombine(hbxwp.TempDirectory, fTemp.getName()));
        br = SetFilePermissions( state.fLocalHandBrake64.getAbsolutePath() );
        if (br == false) {
            RKLog.Log("SetFilePermissions on %s failed.", state.fLocalHandBrake64.getName() );
            return false;
        }

        fTemp = new File(hbxwp.MKVInfo);
        state.mkvInfoExe = FileUtils.PathCombine(hbxwp.TempDirectory, fTemp.getName());
        br = SetFilePermissions( state.mkvInfoExe );
        if (br == false) {
            RKLog.Log("SetFilePermissions on %s failed.", state.mkvInfoExe );
            return false;
        }

        fTemp = new File(hbxwp.MKVExtract);
        state.mkvExtractExe = FileUtils.PathCombine(hbxwp.TempDirectory, fTemp.getName());
        br = SetFilePermissions( state.mkvExtractExe );
        if (br == false) {
            RKLog.Log("SetFilePermissions on %s failed.", state.mkvExtractExe );
            return false;
        }

        fTemp = new File(hbxwp.SSAConverter);
        state.ssaConverterExe = FileUtils.PathCombine(hbxwp.TempDirectory, fTemp.getName());
//        br = SetFilePermissions( state.ssaConverterExe );
//        if (br == false) {
//            RKLog.Log("SetFilePermissions on %s failed.", state.ssaConverterExe );
//            return false;
//        }

        RKLog.Log("PullResources succeeded.");
        return true;
    }

    @Override
    protected boolean PullOriginal(HBXWrapperLogicState state) {

        Long lLocalSourceLength;
        boolean br;

        Scp scp = new Scp( state.Username, state.Password, state.Hostname );

        RKLog.Log("Pulling %s to local drive.", state.fSource.getName());
        br = scp.ScpFrom(state.fSource.getAbsolutePath(), state.fLocalSource.getAbsolutePath(), this.sshCallback );
        if (br == false) {
            return false;
        }

        lLocalSourceLength = state.fLocalSource.length();
        RKLog.Log("Pull succeeded. Local source length is %d.", lLocalSourceLength);
        return true;
    }

    @Override
    protected boolean PushEncoded(HBXWrapperLogicState state) {
        boolean br;

        Sftp s = new Sftp( state.Username, state.Password, state.Hostname );
        Scp scp = new Scp( state.Username, state.Password, state.Hostname );

        // Be nice and delete the local source since we're done with it.  Xgrid should reap this, but whatever
        br = state.fLocalSource.delete();
        if (br == false) {
            RKLog.Log("Failed to delete %s.", state.fLocalSource.getAbsolutePath());
        } else {
            RKLog.Log("Deleted %s.", state.fLocalSource.getAbsolutePath());
        }

        RKLog.Log("Pushing %s to remote drive.", state.fLocalTarget.getName());
        br = scp.ScpTo(state.fLocalTarget.getAbsolutePath(), state.fTempTarget.getAbsolutePath(), this.sshCallback );
        if (br == false) {
            RKLog.Log("SCP back to remote host failed.", state.fLocalTarget.getName());
            return false;
        }

        RKLog.Log("SCP back to network host succeeded.");
/*
        // Move from tmp to final target
        br = state.fTempTarget.renameTo( state.fTarget );
        if( br == false ) {
            RKLog.Log( "Failed to rename to %s.", state.fTarget.getAbsolutePath() );
            return false;
        }

        RKLog.Log( "Renamed to %s.", state.fTarget.getAbsolutePath() );
 */
        // Move from tmp to final target
//        String command = "mv " + state.fTempTarget.getAbsolutePath() + " " + state.fTarget.getAbsolutePath();
        br = s.SftpRename( state.fTempTarget.getAbsolutePath(), state.fTarget.getAbsolutePath() );
        if (br == false) {
            RKLog.Log("Failed to rename to %s.", state.fTarget.getAbsolutePath());
            return false;
        }

        RKLog.Log("Renamed to %s.", state.fTarget.getAbsolutePath());

        // Be nice and delete the local target since we're done with it.  Xgrid should reap this, but whatever
        br = state.fLocalTarget.delete();
        if (br == false) {
            RKLog.Log("Failed to delete %s.", state.fLocalTarget.getAbsolutePath());
        } else {
            RKLog.Log("Deleted %s.", state.fLocalTarget.getAbsolutePath());
        }

        // Delete the source
        // It is important that this is the last step as the subsequent Xgrid tasks checks
        // whether or not the source has been deleted to determine the success of this program.
        // It's unclear to me if this should go here or be a cleanup process managed by the master
        // it seems to me that making this nondestructive would be better
        br = s.SftpDelete( state.fSource.getAbsolutePath() );
        if (br == false) {
            RKLog.Log("Failed to delete %s.", state.fSource.getAbsolutePath());
        } else {
            RKLog.Log("Deleted %s.", state.fSource.getAbsolutePath());
        }

        return true;
    }

    @Override
    protected boolean PushOutputStats(HBXEncodingStats hbxes, HBXWrapperLogicState state) {

        OutputStreamWriter osw;
        boolean br;

        Scp scp = new Scp( state.Username, state.Password, state.Hostname );

        osw = Misc.GetUTF8FileWriter(state.fLocalStats.getAbsolutePath());
        if (osw == null) {
            return false;
        }

        try {
            osw.write(hbxes.Serialize());
            osw.close();
        }
        catch (IOException ioex) {
            return false;
        }

        RKLog.Log("Pushing %s to remote drive.", state.fLocalStats.getName());
        br = scp.ScpTo( state.fLocalStats.getAbsolutePath(), state.fStats.getAbsolutePath(), this.sshCallback );
        if (br == false) {
            return false;
        }

        return true;
    }

    @Override
    protected IHandBrakeExeCallback GetHandBrakeCallback() {
        return this.callback;
    }
}
