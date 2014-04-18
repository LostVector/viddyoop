package com.rkuo.handbrake;

/**
 * Created by IntelliJ IDEA.
 * User: rkuo
 * Date: 8/17/12
 * Time: 1:58 AM
 * To change this template use File | Settings | File Templates.
 */
public interface IHandBrakeExeCallback {
    public void KeepAlive( String line );
    public void Process( String line, double n );
}
