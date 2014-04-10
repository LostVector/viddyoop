package com.rkuo.handbrake;

/**
 * Created by IntelliJ IDEA.
 * User: rkuo
 * Date: Sep 25, 2010
 * Time: 3:02:14 AM
 * To change this template use File | Settings | File Templates.
 */
public class MKVTrack {
    public int TrackNumber;
    public int TrackId;
    public int GroupId;
    public String Name;
    public String Type;
    public String CodecID;
    public String Language;
    public int Channels;
    public int Samples;
    public boolean Default;
    public boolean Forced;
    public boolean Disabled;

    public MKVTrack() {
        TrackNumber = -1;
        TrackId = -1;
        GroupId = -1;
        Name = "";
        Type = "";
        CodecID = "";

        // if no language is detected, the implied language is "eng"
        Language = "eng";
        Channels = -1;
        Samples = -1;

        // if no default flag is set, default = true is implied
        Default = true;
        Forced = false;
        Disabled = false;
        return;        
    }

    public static MKVTrack FindMKVAudioTrack( MKVTrack[] mkvTracks, String sCodecID, String sLanguage, int nChannels ) {

        MKVTrack tReturn;

        tReturn = null;

        for (MKVTrack mkvTrack : mkvTracks) {

            if( mkvTrack.Type.compareToIgnoreCase("audio") != 0 ) {
                continue;
            }

            if( sCodecID.length() > 0 ) {
                if (mkvTrack.CodecID.compareToIgnoreCase(sCodecID) != 0) {
                    continue;
                }
            }

            if( sLanguage.length() > 0 ) {
                if (mkvTrack.Language.compareToIgnoreCase(sLanguage) != 0) {
                    continue;
                }
            }

            if( nChannels != -1 ) {
                if (mkvTrack.Channels != nChannels) {
                    continue;
                }
            }

            tReturn = mkvTrack;
            break;
        }

        return tReturn;
    }
}
