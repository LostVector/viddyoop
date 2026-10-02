package com.rkuo.handbrake;

import com.rkuo.threading.ThreadState;

/**
 * Created by IntelliJ IDEA.
 * User: rkuo
 * Date: Oct 1, 2010
 * Time: 9:53:17 PM
 * To change this template use File | Settings | File Templates.
 */
public interface IHBXExecutor {
    public boolean Execute(HBXWrapperParams hbxwp);
}
