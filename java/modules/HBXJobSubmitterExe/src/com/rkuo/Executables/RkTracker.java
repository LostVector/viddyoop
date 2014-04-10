package com.rkuo.Executables;

import com.fasterxml.jackson.annotation.JsonIgnore;

/**
 * Created with IntelliJ IDEA.
 * User: rkuo
 * Date: 10/14/13
 * Time: 10:08 PM
 * To change this template use File | Settings | File Templates.
 */
public class RkTracker {

    public static final int STATE_INACTIVE = 1;
    public static final int STATE_ACTIVATING = 2;
    public static final int STATE_ACTIVE = 3;
    public static final int STATE_DEACTIVATING = 4;

    public static final long NODE_ACTIVATION_TIMEOUT = 15L * 60L * 1000L;

    public String Hostname;
    public String Username;
    public String Password;
    public String MacAddress;
    public Boolean Managed;

    @JsonIgnore
    public Long LastAction;

    @JsonIgnore
    public Integer State;

    public RkTracker() {
        LastAction = Long.MIN_VALUE;
        State = STATE_INACTIVE;
    }
}
