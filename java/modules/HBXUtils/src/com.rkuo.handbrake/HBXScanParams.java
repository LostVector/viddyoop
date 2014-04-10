package com.rkuo.handbrake;

import java.util.ArrayList;

/**
 * Created by IntelliJ IDEA.
 * User: rkuo
 * Date: Aug 25, 2010
 * Time: 12:58:46 AM
 * To change this template use File | Settings | File Templates.
 */
public class HBXScanParams {
    public long     Duration;
    public int      XRes;
    public int      YRes;
    public int      HdVideo;
    public int      Chapters;
    public boolean  Valid;
    public int      ExitCode;
    public ArrayList<HBXAudioTrack> AudioTracks;
    public ArrayList<HBXSubtitleTrack> SubtitleTracks;

    public HBXScanParams() {
        XRes = -1;
        YRes = -1;
        HdVideo = -1;
        Duration = -1;
        Chapters = 0;
        Valid = false;
        ExitCode = Integer.MIN_VALUE;

        AudioTracks = new ArrayList<HBXAudioTrack>();
        SubtitleTracks = new ArrayList<HBXSubtitleTrack>();
        return;
    }

    public static int FindAudioTrack( ArrayList<HBXAudioTrack> audioTracks, String sLanguage, String sCodec, String sSurroundNotation ) {

        int nTrack;

        nTrack = -1;

        // Get the first english ac3 5.1 track
        for (HBXAudioTrack at : audioTracks) {

            if( sLanguage.length() > 0 ) {
                if (at.Language.toLowerCase().contains(sLanguage) == false) {
                    continue;
                }
            }

            if( sCodec.length() > 0 ) {
                if (at.Codec.toLowerCase().contains(sCodec) == false) {
                    continue;
                }
            }

            if( sSurroundNotation.length() > 0 ) {
                if (at.SurroundNotation.toLowerCase().contains(sSurroundNotation) == false) {
                    continue;
                }
            }

            nTrack = at.TrackNumber;
            break;
        }

        return nTrack;
    }
}
