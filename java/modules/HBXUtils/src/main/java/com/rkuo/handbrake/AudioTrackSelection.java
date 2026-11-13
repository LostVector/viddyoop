package com.rkuo.handbrake;

import com.rkuo.logging.RKLog;
import java.util.ArrayList;

/**
 * Audio selection extracted from HBXBaseWrapperLogic without changing its precedence rules.
 * Languages retain the legacy scan's full-name representation at this boundary.
 */
public final class AudioTrackSelection {
    private AudioTrackSelection() {}

    /**
     * Select primary, distinct secondary, or the first available track, in legacy order.
     * The caller must reject audio-less sources before calling, as EncodeOriginal does.
     */
    public static ArrayList<HBXAudioTrack> select(String primaryLanguage, String secondaryLanguage, HBXScanParams scan) {
        ArrayList<HBXAudioTrack> aAudioTracks = new ArrayList<HBXAudioTrack>();
        HBXAudioTrack trackPrimary;
        HBXAudioTrack trackJapanese;

        trackPrimary = primary( primaryLanguage, scan );
        if( trackPrimary != null ) {
            aAudioTracks.add( trackPrimary );
        }

        // for anime, we want to add the original japanese track on top of the english one
        trackJapanese = secondary( secondaryLanguage, scan );
        if( trackJapanese != null ) {
            if( trackPrimary == null ) {
                aAudioTracks.add( trackJapanese );
            }
            else {
                // If the japanese track = primary track, don't add it again.
                if( trackJapanese.TrackNumber.equals(trackPrimary.TrackNumber) == false ) {
                    aAudioTracks.add( trackJapanese );
                }
            }
        }

        // if we didn't find any preferred audio tracks to encode, just use the first one
        if( aAudioTracks.size() == 0 ) {
            aAudioTracks.add( scan.AudioTracks.get(0) );
        }

        return aAudioTracks;
    }

    public static HBXAudioTrack primary( String language, HBXScanParams hbxsp ) {

        // look for first english ac3 5.1 track
        RKLog.Log( "Looking for a %s AC3 5.x track.", language );
        for( HBXAudioTrack at : hbxsp.AudioTracks ) {
            if( at.Language.compareToIgnoreCase(language) == 0 ) {
                if( at.Codec.compareToIgnoreCase("ac3") == 0 || at.Codec.compareToIgnoreCase("eac3") == 0 ) {
                    if( at.SurroundNotation.toLowerCase().contains("5.1") == true ) {
                        RKLog.Log( "Using audio track %d. (%s, %s, %s).", at.TrackNumber, at.Language, at.Codec, at.SurroundNotation );
                        return at;
                    }

                    if( at.SurroundNotation.toLowerCase().contains("5.0") == true ) {
                        RKLog.Log( "Using audio track %d. (%s, %s, %s).", at.TrackNumber, at.Language, at.Codec, at.SurroundNotation );
                        return at;
                    }
                }
            }
        }

        // otherwise, look for the first 5.1 track
        RKLog.Log( "Looking for any AC3 5.x track." );
        for( HBXAudioTrack at : hbxsp.AudioTracks ) {
            if( at.Codec.compareToIgnoreCase("ac3") == 0 || at.Codec.compareToIgnoreCase("eac3") == 0 ) {
                if( at.SurroundNotation.toLowerCase().contains("5.1") == true ) {
                    RKLog.Log( "Using audio track %d. (%s, %s, %s).", at.TrackNumber, at.Language, at.Codec, at.SurroundNotation );
                    return at;
                }

                if( at.SurroundNotation.toLowerCase().contains("5.0") == true ) {
                    RKLog.Log( "Using audio track %d. (%s, %s, %s).", at.TrackNumber, at.Language, at.Codec, at.SurroundNotation );
                    return at;
                }
            }
        }

        // otherwise, just look for any english track
        RKLog.Log( "Looking for any %s track.", language );
        for( HBXAudioTrack at : hbxsp.AudioTracks ) {
            if( at.Language.compareToIgnoreCase(language) == 0 ) {
                RKLog.Log( "Using audio track %d. (%s, %s, %s).", at.TrackNumber, at.Language, at.Codec, at.SurroundNotation );
                return at;
            }
        }

        return null;
    }

    /*
    This will find the best specified language track.  Right now, used for anime.
     */
    public static HBXAudioTrack secondary( String language, HBXScanParams hbxsp ) {

        // look for the first ac3 5.1 track in the specified language
        for( HBXAudioTrack at : hbxsp.AudioTracks ) {
            if( at.Language.compareToIgnoreCase(language) == 0 ) {
                if( at.Codec.compareToIgnoreCase("ac3") == 0 || at.Codec.compareToIgnoreCase("eac3") == 0  ) {
                    if( at.SurroundNotation.toLowerCase().contains("5.1") == true ) {
                        RKLog.Log( "Using audio track %d. (%s, %s, %s).", at.TrackNumber, at.Language, at.Codec, at.SurroundNotation );
                        return at;
                    }

                    if( at.SurroundNotation.toLowerCase().contains("5.0") == true ) {
                        RKLog.Log( "Using audio track %d. (%s, %s, %s).", at.TrackNumber, at.Language, at.Codec, at.SurroundNotation );
                        return at;
                    }
                }
            }
        }

        // otherwise, just look for the first track in the specified language
        for( HBXAudioTrack at : hbxsp.AudioTracks ) {
            if( at.Language.compareToIgnoreCase(language) == 0 ) {
                RKLog.Log( "Using audio track %d. (%s, %s, %s).", at.TrackNumber, at.Language, at.Codec, at.SurroundNotation );
                return at;
            }
        }

        return null;
    }

}
