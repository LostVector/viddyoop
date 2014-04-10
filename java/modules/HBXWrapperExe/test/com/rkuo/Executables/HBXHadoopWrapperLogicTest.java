package com.rkuo.Executables;

import com.rkuo.handbrake.*;
import com.rkuo.logging.RKLog;
import com.rkuo.net.ssh.Scp;
import com.rkuo.net.ssh.Sftp;
import com.rkuo.util.FileUtils;
import com.rkuo.util.OperatingSystem;
import junit.framework.TestCase;
import junit.runner.Version;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.net.URL;

/**
 * Created by IntelliJ IDEA.
 * User: rkuo
 * Date: 8/16/12
 * Time: 12:31 PM
 * To change this template use File | Settings | File Templates.
 */

@RunWith(JUnit4.class)
public class HBXHadoopWrapperLogicTest extends TestCase {

    @Rule
    public final TemporaryFolder temp = new TemporaryFolder();

    @Test
    public void testExecute() {

        Scp scp;
        Sftp sftp;

        System.out.println("JUnit version is: " + Version.id());

        HBXWrapperParams hbxwp = new HBXWrapperParams();
        IHandBrakeExeCallback callback = new HBXTestHandBrakeExeCallback();
        HBXHadoopWrapperLogic logic = new HBXHadoopWrapperLogic(callback);
        String remoteOriginalSource = "/Volumes/Storage/Shared/HBX/temp/test.mkv";
        String tempSourceName = "test.mkv";
        String localSource = temp.getRoot().getAbsolutePath() + java.io.File.separator + tempSourceName;
//        String workingDir = System.getProperty("user.dir");

        hbxwp.Source = "";
        hbxwp.Username = "";
        hbxwp.Password = "";
        hbxwp.Hostname = "";
        hbxwp.Target = "";
        hbxwp.Stats = "";
        hbxwp.TempDirectory = temp.getRoot().getAbsolutePath();

        scp = new Scp( hbxwp.Username, hbxwp.Password, hbxwp.Hostname );
        sftp = new Sftp( hbxwp.Username, hbxwp.Password, hbxwp.Hostname );

        // pre-execution cleanup
        scp.ScpFrom( remoteOriginalSource, localSource, null );
        scp.ScpTo(localSource, hbxwp.Source, null);
        sftp.SftpDelete(hbxwp.Target);
        sftp.SftpDelete(hbxwp.Stats);

        if( OperatingSystem.isMac() == true ) {
//            hbxwp.Handbrake_x86 = "/Volumes/Storage/Shared/HBX/bin/HandBrakeCLI-x86-0.9.5-osx";
            hbxwp.Handbrake_x64 = "/Volumes/Storage/Shared/HBX/bin/HandBrakeCLI-x64-0.9.5-osx";
        }
        else if( OperatingSystem.isUnix() == true ) {
//            hbxwp.Handbrake_x86 = "/Volumes/Storage/Shared/HBX/bin/HandBrakeCLI-x64-0.9.5-ubuntu";
            hbxwp.Handbrake_x64 = "/Volumes/Storage/Shared/HBX/bin/HandBrakeCLI-x64-0.9.5-ubuntu";
        }
        else if( OperatingSystem.isWindows() == true ) {
//            hbxwp.Handbrake_x86 = "/Volumes/Storage/Shared/HBX/bin/HandBrakeCLI_win32_x86.exe";
 //           hbxwp.Handbrake_x64 = "/Volumes/Storage/Shared/HBX/bin/HandBrakeCLI_win32_x86.exe";
        }
        else {
            Assert.assertTrue(false);
        }

        logic.Execute( hbxwp );
        return;
    }

    class HBXTestHandBrakeExeCallback implements IHandBrakeExeCallback {

        public HBXTestHandBrakeExeCallback() {
            return;
        }

        public void KeepAlive(String line) {
            return;
        }

        public void Process(String line, double d) {
            RKLog.println(String.format("%s", line));
            return;
        }
    }


    @Test
    public void testExtractMKVSubtitles() {

        String mkvPath = "/Users/root/Downloads/hbxtest/test.mkv";
        String mkvInfoPath = "/Applications/Mkvtoolnix.app/Contents/MacOS/mkvinfo";
        String mkvExtractPath = "/Applications/Mkvtoolnix.app/Contents/MacOS/mkvextract";
        String ssaConversionPath = "/Users/root/Downloads/hbxtest/ssa2srt.py";

        MKVTrack[] tracks;
        HBXSubtitleTrack[] subtitleTracks;
        java.io.File fMKV, fDir, fSub;

        tracks = HBXExeHelper.ExecuteMKVInfo(mkvInfoPath, mkvPath);

        fMKV = new java.io.File(mkvPath);
        fDir = new java.io.File(fMKV.getParent());
        subtitleTracks = HBXBaseWrapperLogic.ExtractMKVSubtitles(mkvExtractPath, ssaConversionPath, fMKV.getAbsolutePath(), tracks, null );
        Assert.assertTrue(subtitleTracks != null);

        Assert.assertEquals(subtitleTracks.length,6);

        fSub = new java.io.File( FileUtils.PathCombine(fDir.getAbsolutePath(), "eng.ass") );
        Assert.assertTrue( fSub.exists() );

        fSub = new java.io.File( FileUtils.PathCombine(fDir.getAbsolutePath(),"spa.ass") );
        Assert.assertTrue( fSub.exists() );

        fSub = new java.io.File( FileUtils.PathCombine(fDir.getAbsolutePath(),"rum.ass") );
        Assert.assertTrue( fSub.exists() );

        fSub = new java.io.File( FileUtils.PathCombine(fDir.getAbsolutePath(),"cze.ass") );
        Assert.assertTrue( fSub.exists() );

        fSub = new java.io.File( FileUtils.PathCombine(fDir.getAbsolutePath(),"hun.ass") );
        Assert.assertTrue( fSub.exists() );

        fSub = new java.io.File( FileUtils.PathCombine(fDir.getAbsolutePath(),"ger.ass") );
        Assert.assertTrue( fSub.exists() );
        return;
    }

}
