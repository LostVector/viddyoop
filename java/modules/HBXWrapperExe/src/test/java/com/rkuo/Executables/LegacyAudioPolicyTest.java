package com.rkuo.Executables;

import com.rkuo.handbrake.HBXAudioTrack;
import com.rkuo.handbrake.AudioTrackSelection;
import com.rkuo.handbrake.HBXScanParams;
import com.rkuo.handbrake.HBXSubtitleTrack;
import com.rkuo.handbrake.HandBrakeExeParams;
import java.util.Arrays;
import org.junit.Test;
import static org.junit.Assert.*;

/** Characterizes the existing wrapper before its policy is reused by the new pipeline. */
public class LegacyAudioPolicyTest {
    static HBXAudioTrack track(int number, String language, String codec, String surround) {
        HBXAudioTrack track = new HBXAudioTrack();
        track.TrackNumber = number;
        track.Language = language;
        track.Codec = codec;
        track.SurroundNotation = surround;
        return track;
    }

    static HBXScanParams scan(HBXAudioTrack... tracks) {
        HBXScanParams scan = new HBXScanParams();
        scan.AudioTracks.addAll(Arrays.asList(tracks));
        return scan;
    }

    @Test public void primaryPrefersConfiguredLanguageDolbySurround() {
        HBXAudioTrack foreign = track(1, "Japanese", "ac3", "5.1 ch");
        HBXAudioTrack stereo = track(2, "English", "aac", "2.0 ch");
        HBXAudioTrack preferred = track(3, "English", "eac3", "5.1 ch");
        assertSame(preferred, HBXBaseWrapperLogic.GetPrimaryTrack("english", scan(foreign, stereo, preferred)));
    }

    @Test public void primaryPrefersDolbySurroundOverEarlierDtsInSameLanguage() {
        // Recent NAS inputs can contain both; preserve the code's codec preference.
        HBXAudioTrack dts = track(1, "English", "dts", "5.1 ch");
        HBXAudioTrack dolby = track(2, "English", "eac3", "5.1 ch");
        assertSame(dolby, HBXBaseWrapperLogic.GetPrimaryTrack("English", scan(dts, dolby)));
    }

    @Test public void foreignDolbySurroundPrecedesPrimaryLanguageStereo() {
        HBXAudioTrack stereo = track(1, "English", "aac", "2.0 ch");
        HBXAudioTrack foreign = track(2, "Japanese", "ac3", "5.1 ch");
        assertSame(foreign, HBXBaseWrapperLogic.GetPrimaryTrack("English", scan(stereo, foreign)));
    }

    @Test public void primaryFallsBackToFirstMatchingLanguageWithoutDolbySurround() {
        HBXAudioTrack foreign = track(1, "French", "dts", "5.1 ch");
        HBXAudioTrack first = track(2, "English", "aac", "2.0 ch");
        HBXAudioTrack later = track(3, "English", "dts", "5.1 ch");
        assertSame(first, HBXBaseWrapperLogic.GetPrimaryTrack("English", scan(foreign, first, later)));
    }

    @Test public void eligibleDolbyTracksKeepInputOrderRatherThanRankingChannelsOrCodec() {
        HBXAudioTrack first = track(1, "English", "eac3", "5.0 ch");
        HBXAudioTrack later = track(2, "English", "ac3", "5.1 ch");
        assertSame(first, HBXBaseWrapperLogic.GetPrimaryTrack("English", scan(first, later)));
    }

    @Test public void secondaryPrefersDolbySurroundOnlyWithinItsLanguage() {
        HBXAudioTrack foreign = track(1, "English", "ac3", "5.1 ch");
        HBXAudioTrack stereo = track(2, "Japanese", "aac", "2.0 ch");
        HBXAudioTrack preferred = track(3, "Japanese", "eac3", "5.0 ch");
        assertSame(preferred, HBXBaseWrapperLogic.GetBestAudioTrack("Japanese", scan(foreign, stereo, preferred)));
        assertSame(stereo, HBXBaseWrapperLogic.GetBestAudioTrack("Japanese", scan(foreign, stereo)));
        assertNull(HBXBaseWrapperLogic.GetBestAudioTrack("Japanese", scan(foreign)));
    }

    @Test public void selectionHelpersLeaveFirstTrackFallbackToCaller() {
        HBXScanParams scan = scan(track(1, "French", "dts", "5.1 ch"));
        assertNull(HBXBaseWrapperLogic.GetPrimaryTrack("English", scan));
        assertNull(HBXBaseWrapperLogic.GetBestAudioTrack("Japanese", scan));
    }

    @Test public void sharedSelectionKeepsPrimaryThenDistinctSecondary() {
        HBXAudioTrack english = track(3, "English", "ac3", "5.1 ch");
        HBXAudioTrack japanese = track(1, "Japanese", "dts", "5.1 ch");
        assertEquals(Arrays.asList(english, japanese),
                AudioTrackSelection.select("English", "Japanese", scan(japanese, english)));
    }

    @Test public void sharedSelectionDoesNotDuplicateSecondaryUsedAsForeignPrimary() {
        HBXAudioTrack english = track(1, "English", "aac", "2.0 ch");
        HBXAudioTrack japanese = track(2, "Japanese", "eac3", "5.1 ch");
        assertEquals(Arrays.asList(japanese),
                AudioTrackSelection.select("English", "Japanese", scan(english, japanese)));
    }

    @Test public void sharedSelectionUsesSecondaryAloneBeforeFirstTrackFallback() {
        HBXAudioTrack french = track(1, "French", "aac", "2.0 ch");
        HBXAudioTrack japanese = track(2, "Japanese", "dts", "5.1 ch");
        assertEquals(Arrays.asList(japanese),
                AudioTrackSelection.select("English", "Japanese", scan(french, japanese)));
        assertEquals(Arrays.asList(french),
                AudioTrackSelection.select("English", "Japanese", scan(french)));
    }

    @Test public void aacGroupPrecedesSurroundWithExistingSettingsAndStereoSkip() {
        HBXAudioTrack[] tracks = {
            track(3, "English", "ac3", "5.1 ch"),
            track(7, "Japanese", "flac", "5.1 ch"),
            track(8, "French", "aac", "2.0 ch")
        };
        HandBrakeExeParams params = parameters(tracks);
        assertEquals(Arrays.asList(3, 7, 8, 3, 7), params.AudioTracks);
        assertEquals(Arrays.asList(HandBrakeExeParams.AudioEncoderOption.CA_AAC,
                HandBrakeExeParams.AudioEncoderOption.CA_AAC,
                HandBrakeExeParams.AudioEncoderOption.CA_AAC,
                HandBrakeExeParams.AudioEncoderOption.COPY_AC3,
                HandBrakeExeParams.AudioEncoderOption.FFAC3), params.AudioEncoders);
        assertEquals(Arrays.asList(224, 224, 224, 0, 448), params.AudioBitrates);
        assertEquals(Arrays.asList(48, 48, 48, 0, 0), params.AudioSampleRates);
        assertEquals(Arrays.asList(2.0, 2.0, 2.0, 0.0, 0.0), params.AudioDynamicRangeCompressions);
        assertEquals(HandBrakeExeParams.AudioMixdownOption.DPL2, params.AudioMixdowns.get(0));
        assertEquals(HandBrakeExeParams.AudioMixdownOption.AUTO, params.AudioMixdowns.get(3));
    }

    @Test public void characterizesEac3WorkaroundWithoutTreatingItAsCorrectModernFallback() {
        HandBrakeExeParams params = parameters(track(1, "English", "eac3", "5.1 ch"));
        assertEquals(HandBrakeExeParams.AudioEncoderOption.COPY_AC3, params.AudioEncoders.get(1));
        assertEquals(Integer.valueOf(0), params.AudioBitrates.get(1));
    }

    @Test public void subtitlePriorityUsesLegacyBudgetAfterAudioExpansion() {
        HBXSubtitleTrack[] subtitles = new HBXSubtitleTrack[32];
        for (int i = 0; i < subtitles.length; i++) {
            subtitles[i] = new HBXSubtitleTrack();
            subtitles[i].Language = "jpn";
            subtitles[i].Filename = "other-" + i + ".srt";
        }
        subtitles[29].Language = "fre";
        subtitles[29].Default = true;
        subtitles[30].Language = "spa";
        subtitles[31].Language = "eng";
        HandBrakeExeParams params = HBXBaseWrapperLogic.GenerateHandbrakeParams("HandBrakeCLI", "input.mkv", "output.m4v",
                1920, 1080, new HBXAudioTrack[]{track(1, "English", "ac3", "5.1 ch")}, subtitles, true, null, null);
        assertEquals(2, params.AudioTracks.size());
        assertEquals(28, params.SrtFiles.size());
        assertEquals(Arrays.asList("eng", "spa", "fre"), params.SrtLanguages.subList(0, 3));
        assertEquals(Integer.valueOf(3), params.SrtDefault);
        assertEquals("other-24.srt", params.SrtFiles.get(27));
    }

    private static HandBrakeExeParams parameters(HBXAudioTrack... tracks) {
        return HBXBaseWrapperLogic.GenerateHandbrakeParams("HandBrakeCLI", "input.mkv", "output.m4v",
                1920, 1080, tracks, new HBXSubtitleTrack[0], true, null, null);
    }
}
