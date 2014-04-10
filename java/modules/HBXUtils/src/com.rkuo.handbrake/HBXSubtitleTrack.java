package com.rkuo.handbrake;

/**
 * Created by IntelliJ IDEA.
 * User: rkuo
 * Date: 8/28/12
 * Time: 3:08 AM
 * To change this template use File | Settings | File Templates.
 */
public class HBXSubtitleTrack {
    public Integer  TrackNumber;
    public Integer  TrackId;
    public String   Description;
    public String   Language;
    public String   Filename;
    public Boolean  Default;

    public HBXSubtitleTrack() {
        TrackNumber = -1;
        TrackId = -1;
        Description = "English";
        Language = "eng";
        Filename = "";
        Default = false;
        return;
    }
}
