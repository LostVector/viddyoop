package com.rkuo.handbrake;

/**
 * Created by IntelliJ IDEA.
 * User: rkuo
 * Date: Sep 20, 2010
 * Time: 8:46:57 PM
 * To change this template use File | Settings | File Templates.
 */
public class HBXAudioTrack {
    public Integer  TrackNumber;
    public String   Language;
    public String   Codec; // AC3, DTS
    public String   SurroundNotation; // Dolby Surround, 7.1 ch, 5.1 ch, 2.0 ch

    public HBXAudioTrack() {
        TrackNumber = -1;
        Language = "";
        Codec = "";
        SurroundNotation = "";
        return;
    }
}
