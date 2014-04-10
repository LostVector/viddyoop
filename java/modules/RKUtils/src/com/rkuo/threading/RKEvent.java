package com.rkuo.threading;

/**
 * Created by IntelliJ IDEA.
 * User: rkuo
 * Date: Oct 6, 2010
 * Time: 11:09:20 PM
 * To change this template use File | Settings | File Templates.
 */
public class RKEvent {
    protected boolean _isSet;

    public RKEvent() {
        _isSet = false;
    }

    public void Set() {
        synchronized( this ) {
            _isSet = true;
        }
    }

    public void Reset() {
        synchronized( this ) {
            _isSet = false;
        }
    }

    public boolean isSet() {
        synchronized( this ) {
            return _isSet;
        }
    }

    public boolean Wait( long msec ) {

        long    now;

        now = System.currentTimeMillis();
        while( true ) {
            synchronized( this ) {
                if( _isSet == true ) {
                    _isSet = false;
                    return true;
                }
            }

            // we check here to ensure that no time is spent sleeping if we are passed a 0 wait value
            if( System.currentTimeMillis() - now >= msec ) {
                break;
            }

            try {
                Thread.sleep( 100 );
            }
            catch( InterruptedException iex ) {
                return false;
            }
        }

        return false;
    }
}
