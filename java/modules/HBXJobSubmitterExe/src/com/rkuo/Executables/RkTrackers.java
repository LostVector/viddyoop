package com.rkuo.Executables;

import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlElementWrapper;

import java.util.List;

/**
 * Created with IntelliJ IDEA.
 * User: rkuo
 * Date: 3/30/14
 * Time: 5:01 PM
 * To change this template use File | Settings | File Templates.
 */
public class RkTrackers {
    @JacksonXmlElementWrapper(useWrapping=false)
    public List<RkTracker> trackers;
}
