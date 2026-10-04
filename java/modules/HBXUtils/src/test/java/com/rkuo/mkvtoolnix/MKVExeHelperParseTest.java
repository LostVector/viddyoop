package com.rkuo.mkvtoolnix;

import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

@RunWith(JUnit4.class)
public class MKVExeHelperParseTest {

    private static String readFixture(String resourceName) throws Exception {
        InputStream is = MKVExeHelperParseTest.class.getResourceAsStream("/" + resourceName);
        Assert.assertNotNull("missing test fixture: " + resourceName, is);
        try {
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
        finally {
            is.close();
        }
    }

    @Test
    public void testParseMKVInfoTrackCount() throws Exception {
        String scan = readFixture("mkvinfo.txt");
        MKVInfoState state = MKVExeHelper.ParseMKVInfo(scan);

        Assert.assertNotNull(state);
        Assert.assertEquals(5, state.tracks.size());
    }

    @Test
    public void testParseMKVInfoAudioTrack() throws Exception {
        String scan = readFixture("mkvinfo.txt");
        MKVInfoState state = MKVExeHelper.ParseMKVInfo(scan);

        MKVTrack t = state.tracks.get(0);
        Assert.assertEquals(2, t.TrackNumber);
        Assert.assertEquals(0, t.TrackId);
        Assert.assertEquals("audio", t.Type);
        Assert.assertEquals("A_AC3", t.CodecID);
        Assert.assertEquals(6, t.Channels);
        // no default flag line present, so the implied default is true
        Assert.assertTrue(t.Default);
        Assert.assertFalse(t.Forced);
    }

    @Test
    public void testParseMKVInfoVideoTrack() throws Exception {
        String scan = readFixture("mkvinfo.txt");
        MKVInfoState state = MKVExeHelper.ParseMKVInfo(scan);

        MKVTrack t = state.tracks.get(1);
        Assert.assertEquals(3, t.TrackNumber);
        Assert.assertEquals(1, t.TrackId);
        Assert.assertEquals("video", t.Type);
        Assert.assertEquals("V_MPEG4/ISO/AVC", t.CodecID);
    }

    @Test
    public void testParseMKVInfoSubtitleTracks() throws Exception {
        String scan = readFixture("mkvinfo.txt");
        MKVInfoState state = MKVExeHelper.ParseMKVInfo(scan);

        // "FORCED" track: default flag 0, forced flag 1
        MKVTrack t = state.tracks.get(2);
        Assert.assertEquals(4, t.TrackNumber);
        Assert.assertEquals(2, t.TrackId);
        Assert.assertEquals("subtitles", t.Type);
        Assert.assertEquals("S_TEXT/UTF8", t.CodecID);
        Assert.assertEquals("FORCED", t.Name);
        Assert.assertFalse(t.Default);
        Assert.assertTrue(t.Forced);

        // "English" track: no default flag line, so implied default is true
        t = state.tracks.get(3);
        Assert.assertEquals(5, t.TrackNumber);
        Assert.assertEquals(3, t.TrackId);
        Assert.assertEquals("English", t.Name);
        Assert.assertTrue(t.Default);
        Assert.assertFalse(t.Forced);

        // "English SDH" track: default flag 0
        t = state.tracks.get(4);
        Assert.assertEquals(6, t.TrackNumber);
        Assert.assertEquals(4, t.TrackId);
        Assert.assertEquals("English SDH", t.Name);
        Assert.assertFalse(t.Default);
        Assert.assertFalse(t.Forced);
    }

    @Test
    public void testParseEmptyInputYieldsNoTracks() {
        MKVInfoState state = MKVExeHelper.ParseMKVInfo("");
        Assert.assertNotNull(state);
        Assert.assertEquals(0, state.tracks.size());
    }

    @Test
    public void testScanMKVInfoLineIgnoresLinesOutsideSegmentTracks() {
        MKVInfoState state = new MKVInfoState();

        MKVExeHelper.ScanMKVInfoLine("+ EBML head", state);
        MKVExeHelper.ScanMKVInfoLine("|+ Doc type: matroska", state);
        MKVExeHelper.ScanMKVInfoLine("| + Track number: 99", state);

        Assert.assertFalse(state.bSegmentTracks);
        Assert.assertEquals(0, state.tracks.size());
    }

    @Test
    public void testScanMKVInfoLineEntersSegmentTracksAndAddsTrack() {
        MKVInfoState state = new MKVInfoState();

        MKVExeHelper.ScanMKVInfoLine("|+ Segment tracks", state);
        Assert.assertTrue(state.bSegmentTracks);
        Assert.assertNull(state.currentTrack);

        MKVExeHelper.ScanMKVInfoLine("| + A track", state);
        Assert.assertEquals(1, state.tracks.size());
        Assert.assertSame(state.tracks.get(0), state.currentTrack);

        MKVExeHelper.ScanMKVInfoLine("|  + Track number: 2 (track ID for mkvmerge & mkvextract: 0)", state);
        Assert.assertEquals(2, state.currentTrack.TrackNumber);
        Assert.assertEquals(0, state.currentTrack.TrackId);

        MKVExeHelper.ScanMKVInfoLine("|   + Channels: 6", state);
        Assert.assertEquals(6, state.currentTrack.Channels);
    }

    @Test
    public void testScanMKVInfoLineExitsSegmentTracksOnNextSection() {
        MKVInfoState state = new MKVInfoState();

        MKVExeHelper.ScanMKVInfoLine("|+ Segment tracks", state);
        MKVExeHelper.ScanMKVInfoLine("| + A track", state);
        Assert.assertEquals(1, state.tracks.size());

        MKVExeHelper.ScanMKVInfoLine("|+ Chapters", state);
        Assert.assertFalse(state.bSegmentTracks);
        Assert.assertNull(state.currentTrack);

        // track attributes outside segment tracks are ignored
        MKVExeHelper.ScanMKVInfoLine("|  + Track number: 77", state);
        Assert.assertEquals(1, state.tracks.size());
    }

    @Test
    public void testScanMKVInfoLineIgnoresAttributeWithoutColon() {
        MKVInfoState state = new MKVInfoState();

        MKVExeHelper.ScanMKVInfoLine("|+ Segment tracks", state);
        MKVExeHelper.ScanMKVInfoLine("| + A track", state);
        MKVExeHelper.ScanMKVInfoLine("|  + CodecPrivate, length 42", state);

        Assert.assertEquals(1, state.tracks.size());
        Assert.assertEquals("", state.currentTrack.CodecID);
    }

    @Test
    public void testScanMKVDTS2AC3Progress() {
        Assert.assertEquals(0, MKVExeHelper.ScanMKVDTS2AC3Progress("Progress: 0 %"));
        Assert.assertEquals(47, MKVExeHelper.ScanMKVDTS2AC3Progress("Progress: 47 %"));
        Assert.assertEquals(100, MKVExeHelper.ScanMKVDTS2AC3Progress("Progress: 100 %"));
    }
}
