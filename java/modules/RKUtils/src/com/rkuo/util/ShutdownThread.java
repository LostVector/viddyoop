package com.rkuo.util;

/**
 * Created by IntelliJ IDEA.
 * User: rkuo
 * Date: Aug 14, 2010
 * Time: 6:31:15 PM
 * To change this template use File | Settings | File Templates.
 */
public class ShutdownThread extends Thread {

    public boolean isShutdown() {
        synchronized( this ) {
            return _shutdown;
        }
    }

    protected boolean _shutdown;

    public ShutdownThread() {
        _shutdown = false;
        return;
    }

    public void run() {

        synchronized( this ) {
            _shutdown = true;
        }

        return;
    }

}
