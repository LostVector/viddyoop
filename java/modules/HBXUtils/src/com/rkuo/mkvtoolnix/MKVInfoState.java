package com.rkuo.mkvtoolnix;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

public class MKVInfoState {
    public boolean bSegmentTracks;
    public MKVTrack currentTrack;
    public List<MKVTrack> tracks;

    public MKVInfoState() {
        bSegmentTracks = false;
        currentTrack = null;
        tracks = new ArrayList<MKVTrack>();
        return;
    }
}
