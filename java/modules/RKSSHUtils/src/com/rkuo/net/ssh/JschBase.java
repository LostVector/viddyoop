package com.rkuo.net.ssh;

import com.jcraft.jsch.JSch;
import com.jcraft.jsch.Session;

/**
 * Created with IntelliJ IDEA.
 * User: rkuo
 * Date: 3/25/14
 * Time: 11:56 PM
 * To change this template use File | Settings | File Templates.
 */
public abstract class JschBase {

    protected static int CONNECT_TIMEOUT = 60000;
    protected static int SERVER_ALIVE = 10000;

    protected static Session OpenSession(String user, String password, String host) {
        Session session = null;

        try {
            JSch jsch = new JSch();
            java.util.Properties config = new java.util.Properties();
            config.put("StrictHostKeyChecking", "no");

            session = jsch.getSession(user, host, 22);
            session.setConfig(config);
            session.setPassword(password);
            session.connect( CONNECT_TIMEOUT );
            session.setServerAliveInterval( SERVER_ALIVE );
        }
        catch (Exception e) {
            e.printStackTrace();
            return null;
        }

        return session;
    }

    public static void disconnect(Session o) {
        if( o == null ) {
            return;
        }

        try {
            o.disconnect();
        }
        catch( Exception ex ) {
            //log the exception
        }
        finally {
            o = null;
        }

        return;
    }

}
