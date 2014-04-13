package com.rkuo.handbrake;

import com.rkuo.mkvtoolnix.MKVExeHelper;
import com.rkuo.mkvtoolnix.MKVTrack;
import com.rkuo.mp4box.MP4BoxHelper;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.util.List;

@RunWith(JUnit4.class)
public class HBXExeHelperTest {

    @Test
    public void testExecuteMKVInfo() {

        MKVTrack[] tracks;

        tracks = MKVExeHelper.ExecuteMKVInfo("/Applications/Mkvtoolnix.app/Contents/MacOS/mkvinfo", "/Users/root/Downloads/hbxtest/test.mkv");
        Assert.assertEquals( tracks.length, 9 );
        return;
    }

    @Test
    public void testConvertDTSToAC3() {

        int returnCode;

        String src = "/Users/root/Downloads/hbxtest/test-tiffs.1080p.mkv";
        String tgt = "/Users/root/Downloads/hbxtest/test-tiffs.1080p-AC3.mkv";
        String aften = "/Users/root/Downloads/hbxtest/aften";
        String dcadec = "/Users/root/Downloads/hbxtest/dcadec";
        String mkvInfo = "/Applications/Mkvtoolnix.app/Contents/MacOS/mkvinfo";
        String mkvExtract = "/Applications/Mkvtoolnix.app/Contents/MacOS/mkvextract";
        String mkvMerge = "/Applications/Mkvtoolnix.app/Contents/MacOS/mkvmerge";
        String working = "/private/tmp/dtstoac3";
        MKVTrack tPreferred = new MKVTrack();

        tPreferred.TrackId = 1;
        returnCode = MKVExeHelper.ConvertDTSToAC3(src, tgt, mkvExtract, mkvMerge, aften, dcadec, working, tPreferred);
        Assert.assertTrue( returnCode == 0 );
        return;
    }

    @Test
    public void testExecuteHandbrakeScan() {
        HBXScanParams hbxsp;

        String handbrakePath = "/Users/root/Downloads/hbxtest/HandBrakeCLI-x64-0.9.9-osx";
        String moviePath = "/Users/root/Downloads/hbxtest/test.mkv";

        hbxsp = HBXExeHelper.ExecuteHandbrakeScan(handbrakePath, moviePath, true);
        Assert.assertTrue( hbxsp != null );
        return;
    }

    @Test
    public void testMP4BoxScan() {
        List<MKVTrack> aTracks;

        String mp4boxPath = "/Applications/Osmo4.app/Contents/MacOS/MP4Box";
        String moviePath = "/Users/root/Downloads/hbxtest/test.m4v";

        aTracks = MP4BoxHelper.ExecuteMP4BoxScan(mp4boxPath, moviePath, true);
        Assert.assertTrue( aTracks != null );
        return;
    }

    @Test
    public void testRepairM4VSubtitlesWithBlankLines() {

        int nr;
        String testFilename = "/Users/root/Downloads/hbxtest/test.m4v";
        String mp4box = "/Applications/Osmo4.app/Contents/MacOS/MP4Box";

        nr = MP4BoxHelper.RepairM4VSubtitlesWithBlankLines( testFilename, mp4box );
        Assert.assertTrue( nr >= 0 );
        return;
    }

    @Test
    public void testCleanRepairM4VSubtitlesWithBlankLines() {

        boolean br;
        String testFilename = "/Users/root/Downloads/hbxtest/test.m4v";
        String mp4box = "/Applications/Osmo4.app/Contents/MacOS/MP4Box";
        String tempDirectory = "/private/tmp/m4vrepair";

        br = MP4BoxHelper.CleanRepairM4VSubtitlesWithBlankLines( testFilename, mp4box, tempDirectory );
        Assert.assertTrue( br );
        return;
    }

    @Test
    public void testRepairM4VSubtitlesWithLinefeeds() {

        int nr;

        String testFilename = "/Users/root/Downloads/hbxtest/test.m4v";
        String mp4box = "/Applications/Osmo4.app/Contents/MacOS/MP4Box";

        nr = MP4BoxHelper.RepairM4VSubtitlesWithLinefeeds( testFilename, mp4box );
        Assert.assertTrue( nr >= 0 );
        return;
    }

    @Test
    public void testCleanRepairM4VSubtitlesWithLinefeeds() {

        boolean br;
        String testFilename = "/Users/root/Downloads/hbxtest/test.m4v";
        String mp4box = "/Applications/Osmo4.app/Contents/MacOS/MP4Box";
        String tempDirectory = "/private/tmp/m4vrepair";

        br = MP4BoxHelper.CleanRepairM4VSubtitlesWithLinefeeds( testFilename, mp4box, tempDirectory );
        Assert.assertTrue( br );
        return;
    }
}
