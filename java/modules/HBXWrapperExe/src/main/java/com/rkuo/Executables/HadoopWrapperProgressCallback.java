package com.rkuo.Executables;

import com.rkuo.handbrake.IHandBrakeExeCallback;
import com.rkuo.net.ssh.ISSHProgressCallback;

/**
 * Created by IntelliJ IDEA.
 * User: rkuo
 * Date: 8/20/12
 * Time: 10:46 PM
 * To change this template use File | Settings | File Templates.
 */
public class HadoopWrapperProgressCallback implements ISSHProgressCallback {

    protected IHandBrakeExeCallback callback;
    public HadoopWrapperProgressCallback( IHandBrakeExeCallback callback ) {
        this.callback = callback;
        return;
    }

    public void Progress( String line ) {
        this.callback.Process( line, 0 );
        return;
    }
}
