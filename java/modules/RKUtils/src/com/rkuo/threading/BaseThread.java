package com.rkuo.threading;

/**
 * Created by IntelliJ IDEA.
 * User: rkuo
 * Date: Oct 5, 2010
 * Time: 3:18:12 AM
 * To change this template use File | Settings | File Templates.
 */
public abstract class BaseThread implements Runnable {

    protected Object Lock = new Object();
    protected ThreadState   _state;
    protected RKEvent       _evAbort;
    protected Thread        _thread;

    public BaseThread() {
        _evAbort = new RKEvent();
        _thread = new Thread( this );
        _state = ThreadState.Idle;
        return;
    }

    public void Start() {
        Stop();

        synchronized( this ) {
            _thread.start();
        }

        return;
    }

    public void Stop() {

        synchronized( this.Lock ) {
            // if there is a job running, terminate it

            if( _state == ThreadState.Idle ) {
                return;
            }

            if( _state == ThreadState.Finished ) {
                return;
            }
        }

        _evAbort.Set();

        while( true ) {
            ThreadState state;

            synchronized( this.Lock ) {
                state = _state;
            }

            if( state == ThreadState.Running ) {
                try {
                    Thread.sleep( 1000 );
                }
                catch( InterruptedException iex ) {
                    break;
                }

                continue;
            }

            break;
        }

        _evAbort.Reset();
        return;
    }

    public ThreadState getThreadState() {

        ThreadState state;

        synchronized( this.Lock ) {
            state = _state;
        }

        return state;
    }

    public void run() {

        synchronized( this.Lock ) {
            _state = ThreadState.Starting;
        }

        ThreadWork( _evAbort );

        synchronized( this.Lock ) {
            _state = ThreadState.Finished;
        }

        return;
    }

    public abstract void ThreadWork( RKEvent evAbort );
}
