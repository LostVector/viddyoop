package com.rkuo.handbrake;

import java.util.ArrayList;
import java.util.List;

/**
 * Created by IntelliJ IDEA.
 * User: root
 * Date: 2/9/14
 * Time: 12:14 AM
 * To change this template use File | Settings | File Templates.
 */
public class SrtObject {
    public Integer TrackId;
    public String Filename;
    public List<SrtEntry> entries;

    public SrtObject() {
        entries = new ArrayList<SrtEntry>();
        return;
    }
}
