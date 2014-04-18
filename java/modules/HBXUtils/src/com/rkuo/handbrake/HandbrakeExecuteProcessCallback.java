package com.rkuo.handbrake;

import com.rkuo.util.ExecuteProcessCallback;

public class HandbrakeExecuteProcessCallback implements ExecuteProcessCallback {

    IHandBrakeExeCallback callback;

    public HandbrakeExecuteProcessCallback( IHandBrakeExeCallback callback ) {
        this.callback = callback;
    }

    @Override
    public void ProcessLine(String line) {
        callback.KeepAlive(line);
    }

}