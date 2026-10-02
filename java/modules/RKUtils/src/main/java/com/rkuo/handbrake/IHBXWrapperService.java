package com.rkuo.handbrake;

import com.rkuo.threading.ThreadState;

import java.rmi.*;

/**
 * Created by IntelliJ IDEA.
 * User: rkuo
 * Date: Oct 1, 2010
 * Time: 9:53:17 PM
 * To change this template use File | Settings | File Templates.
 */
public interface IHBXWrapperService extends java.rmi.Remote {
//    public void Invoke( String[] args ) throws RemoteException;
    public void Start( String sJar, String[] args ) throws RemoteException;
    public void Stop() throws RemoteException;
    public ThreadState getThreadState() throws RemoteException;

    public String readLine() throws RemoteException;
    public boolean putFile( String target, long offset, byte[] data, int len ) throws RemoteException;
}
