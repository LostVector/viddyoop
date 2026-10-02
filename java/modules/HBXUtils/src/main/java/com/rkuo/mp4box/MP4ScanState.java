package com.rkuo.mp4box;

import com.rkuo.mkvtoolnix.MKVTrack;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

public class MP4ScanState {
    public MKVTrack currentTrack;
    public List<MKVTrack> tracks;
    public Pattern rpTrack;
    public Pattern rpMediaInfo;
    public Pattern rpAlternateGroupId;

    public MP4ScanState() {
        tracks = new ArrayList<MKVTrack>();
        String pattern;

        pattern = "Track # (\\d+) Info - TrackID (\\d+) - TimeScale .*";
        rpTrack = Pattern.compile(pattern);

        pattern = "Media Info: Language \"([A-Za-z0-9_;,\\-\\(\\) ]+)\" - Type \"([A-Za-z0-9_:-]+)\" - (\\d+) samples";
        rpMediaInfo = Pattern.compile(pattern);

        pattern = "Alternate Group ID (\\d+)";
        rpAlternateGroupId = Pattern.compile(pattern);

        return;
    }
}
