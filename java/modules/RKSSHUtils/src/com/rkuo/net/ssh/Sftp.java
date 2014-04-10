package com.rkuo.net.ssh;

import com.jcraft.jsch.*;

import java.util.ArrayList;
import java.util.Vector;

/**
 * Created by IntelliJ IDEA.
 * User: rkuo
 * Date: 8/21/12
 * Time: 12:36 PM
 * To change this template use File | Settings | File Templates.
 */
public class Sftp extends JschBase {
    protected static int CONNECT_TIMEOUT = 60000;
    protected static int SERVER_ALIVE = 10000;
    protected String Username;
    protected String Password;
    protected String Hostname;

    public Sftp( String username, String password, String hostname ) {
        this.Username = username;
        this.Password = password;
        this.Hostname = hostname;
        return;
    }

    public long SftpGetFileSize( String target ) {
        return InternalSftpGetFileSize( this.Username, this.Password, this.Hostname, target );
    }

    public boolean SftpIsDirectory( String target ) {
        return InternalSftpIsDirectory( this.Username, this.Password, this.Hostname, target );
    }

    public boolean SftpDelete( String target ) {
        return InternalSftpDelete( this.Username, this.Password, this.Hostname, target );
    }

    public boolean SftpRename( String source, String target ) {
        return InternalSftpRename( this.Username, this.Password, this.Hostname, source, target );
    }

    public String[] SftpList( String target ) {
        return InternalSftpList(this.Username, this.Password, this.Hostname, target);
    }


    protected static ChannelSftp SftpOpenChannel(Session session) {

        Channel channel = null;
        ChannelSftp c = null;

        try {
            channel = session.openChannel("sftp");
            channel.connect( CONNECT_TIMEOUT );
            c = (ChannelSftp) channel;
        }
        catch (Exception e) {
            e.printStackTrace();
            return null;
        }

        return c;
    }

    protected static boolean InternalSftpExists( String user, String pass, String host, String target ) {

        Session session = null;
        ChannelSftp c = null;
        boolean exists = false;

        try {

            do {
                session = OpenSession(user,pass,host);
                if( session == null ) {
                    break;
                }

                c = SftpOpenChannel(session);
                if( c == null ) {
                    break;
                }

                try {
                    SftpATTRS sftpATTRS = c.lstat( target );
                }
                catch (Exception e) {
                    e.printStackTrace();
                    break;
                }

                exists = true;
            } while( false );
        }
        finally {
            disconnect( c );
            disconnect( session );
        }

        return exists;
    }

    protected static boolean InternalSftpRename(String user, String pass, String host, String source, String target) {

        Session session = null;
        ChannelSftp c = null;
        boolean success = false;

        try {

            do {
                session = OpenSession(user,pass,host);
                if( session == null ) {
                    break;
                }

                c = SftpOpenChannel(session);
                if( c == null ) {
                    break;
                }

                try {
                    c.rename(source,target);
                }
                catch (Exception e) {
                    e.printStackTrace();
                    break;
                }

                success = true;
            } while( false );
        }
        finally {
            disconnect( c );
            disconnect( session );
        }

        return success;
    }

    /*
       protected static boolean SftpRename(String user, String password, String host, String source, String target) {

           Session session = OpenSession(user,password,host);
           if( session == null ) {
               return false;
           }

           ChannelSftp c = SftpOpenChannel(session);
           if( c == null ) {
               return false;
           }

           try {
               c.(source,target);
           }
           catch (Exception e) {
               e.printStackTrace();
               return false;
           }

           c.disconnect();
           session.disconnect();
           return true;
       }
    */
    protected static boolean InternalSftpDelete(String user, String pass, String host, String target) {

        Session session = null;
        ChannelSftp c = null;
        boolean success = false;

        try {

            do {
                session = OpenSession(user,pass,host);
                if( session == null ) {
                    break;
                }

                c = SftpOpenChannel(session);
                if( c == null ) {
                    break;
                }

                try {
                    c.rm(target);
                }
                catch (SftpException se) {
                    if( se.id == 2 ) {
                        // no such file ... this is ok.
                        break;
                    }

                    se.printStackTrace();
                    break;
                }
                catch (Exception e) {
                    e.printStackTrace();
                    break;
                }

                success = true;
            } while( false );
        }
        finally {
            disconnect( c );
            disconnect( session );
        }

        return success;
    }

     protected static boolean InternalSftpIsDirectory(String user, String pass, String host, String target) {

        Session session = null;
        ChannelSftp c = null;
        boolean success = false;

        try {

            do {
                session = OpenSession(user,pass,host);
                if( session == null ) {
                    break;
                }

                c = SftpOpenChannel(session);
                if( c == null ) {
                    break;
                }

                Vector lsEntries;

                try {
                    lsEntries = c.ls(target);
                }
                catch( SftpException ex ) {
                    break;
                }

                if( lsEntries.size() == 0 ) {
                    break;
                }

                for( Object o : lsEntries ) {
                    ChannelSftp.LsEntry e = (ChannelSftp.LsEntry) o;
                    if( e.getFilename().compareToIgnoreCase(".") != 0 ) {
                        continue;
                    }

                    if( e.getAttrs().isDir() == true) {
                        success = true;
                        break;
                    }
                }
            } while( false );
        }
        finally {
            disconnect( c );
            disconnect( session );
        }

        return success;
    }

    protected static String[] InternalSftpList(String user, String pass, String host, String target) {

        ArrayList<String> aFilenames = new ArrayList<String>();
        Session session = null;
        ChannelSftp c = null;

        try {

            do {
                session = OpenSession(user,pass,host);
                if( session == null ) {
                    break;
                }

                c = SftpOpenChannel(session);
                if( c == null ) {
                    break;
                }

                Vector lsEntries;

                try {
                    lsEntries = c.ls(target);
                }
                catch( SftpException ex ) {
                    break;
                }

                for( int x=0; x < lsEntries.size(); x++ ) {
                    ChannelSftp.LsEntry e = (ChannelSftp.LsEntry)lsEntries.get(x);
                    if( e.getAttrs().isDir() == true ) {
                        continue;
                    }

                    aFilenames.add(e.getFilename());
                }

            } while( false );
        }
        finally {
            disconnect( c );
            disconnect( session );
        }

        return aFilenames.toArray(new String[aFilenames.size()]);
    }

    protected static long InternalSftpGetFileSize(String user, String pass, String host, String target) {

        Session session = null;
        ChannelSftp c = null;
        long filesize = -1;

        try {

            do {
                session = OpenSession(user,pass,host);
                if( session == null ) {
                    break;
                }

                c = SftpOpenChannel(session);
                if( c == null ) {
                    break;
                }

                Vector lsEntries;
                ChannelSftp.LsEntry e;

                try {
                    lsEntries = c.ls(target);
                }
                catch( SftpException ex ) {
                    break;
                }

                if( lsEntries.size() == 0 ) {
                    break;
                }

                e = (ChannelSftp.LsEntry)lsEntries.get(0);

                filesize = e.getAttrs().getSize();
            } while( false );
        }
        finally {
            disconnect( c );
            disconnect( session );
        }

        return filesize;
   }


    public static void disconnect(ChannelSftp o) {
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
