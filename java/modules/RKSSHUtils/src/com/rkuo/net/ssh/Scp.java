package com.rkuo.net.ssh;

import com.jcraft.jsch.*;

import java.io.*;
import java.util.ArrayList;
import java.util.Vector;

/**
 * Created by IntelliJ IDEA.
 * User: rkuo
 * Date: 8/20/12
 * Time: 8:54 PM
 * To change this template use File | Settings | File Templates.
 */
public class Scp extends JschBase {

    protected String Username;
    protected String Password;
    protected String Hostname;

    public Scp( String username, String password, String hostname ) {
        this.Username = username;
        this.Password = password;
        this.Hostname = hostname;
        return;
    }

    public boolean ScpTo(String local, String remote, ISSHProgressCallback callback ) {
        return InternalScpTo(this.Username, this.Password, this.Hostname, local, remote, callback);
    }

    public boolean ScpFrom(String remote, String local, ISSHProgressCallback callback ) {
        return InternalScpFrom( this.Username, this.Password, this.Hostname, remote, local, callback );
    }

    public long GetFreeDiskSpace(String directory) {
        return InternalSSHGetFreeDiskSpace(this.Username, this.Password, this.Hostname, directory);
    }

    // Sends a file from the local computer to the remote ssh host
    // callback will be called every 1MB
    protected static boolean InternalScpTo(String user, String password, String host, String local, String remote, ISSHProgressCallback callback ) {

        FileInputStream fis = null;
        Session session = null;
        Channel channel = null;
        OutputStream out = null;
        InputStream in = null;

        try {
            String command;
            int nr;

            // for progress callback
            long lastCallbackMB = 0;
            long bytesWritten = 0;
            long MB = 1024 * 1024;

            boolean ptimestamp = true;
            JSch jsch = new JSch();
            java.util.Properties config = new java.util.Properties();
            session = jsch.getSession(user, host, 22);

            config.put("StrictHostKeyChecking", "no");
            session.setConfig(config);
            session.setPassword(password);
            session.setServerAliveInterval( SERVER_ALIVE );

            // username and password will be given via UserInfo interface.
//            UserInfo ui = new MyUserInfo();
//            session.setUserInfo(ui);
            session.connect( CONNECT_TIMEOUT );

            // exec 'scp -t rfile' remotely
            command = "scp -c arcfour " + (ptimestamp ? "-p" : "") + " -t \"" + remote + "\"";
            channel = session.openChannel("exec");
            ((ChannelExec) channel).setCommand(command);

            // get I/O streams for remote scp
            out = channel.getOutputStream();
            in = channel.getInputStream();

            channel.connect( CONNECT_TIMEOUT );

            nr = checkAck(in);
            if (nr != 0) {
                throw new Exception();
            }

            File _lfile = new File(local);

            if (ptimestamp == true) {
                command = "T " + (_lfile.lastModified() / 1000) + " 0";
                // The access time should be sent here,
                // but it is not accessible with JavaAPI ;-<
                command += (" " + (_lfile.lastModified() / 1000) + " 0\n");
                out.write(command.getBytes());
                out.flush();

                nr = checkAck(in);
                if (nr != 0) {
                    throw new Exception();
                }
            }

            // send "C0644 filesize filename", where filename should not include '/'
            long filesize = _lfile.length();
            command = "C0644 " + filesize + " ";
            if (local.lastIndexOf('/') > 0) {
                command += local.substring(local.lastIndexOf('/') + 1);
            } else {
                command += local;
            }
            command += "\n";
            out.write(command.getBytes());
            out.flush();

            nr = checkAck(in);
            if (nr != 0) {
                throw new Exception();
            }

            // send local content
            fis = new FileInputStream(local);
            byte[] buf = new byte[1024];
            while (true) {
                int len;

                len = fis.available();
                if( len == 0 ) {
                    if( bytesWritten != filesize ) {
                        System.out.println("Read blocked.");
                        Thread.sleep(1000);
                        continue;
                    }

                    break;
                }

                len = fis.read(buf, 0, buf.length);
                if (len <= 0) {
                    break;
                }

                out.write(buf, 0, len); //out.flush();

                // callback once per MB written
                bytesWritten += len;
                if( (bytesWritten / MB) > lastCallbackMB ) {
                    lastCallbackMB = bytesWritten / MB;
                    if( callback != null ) {
                        callback.Progress( String.format("%d",bytesWritten) );
                    }
                }
            }

            fis.close();
            fis = null;

            // send '\0'
            buf[0] = 0;
            out.write(buf, 0, 1);
            out.flush();

            nr = checkAck(in);
            if (nr != 0) {
                throw new Exception("checkAck failed.");
            }

        }
        catch (Exception e) {
            e.printStackTrace();
            System.out.println(e.getMessage());
            return false;
        }
        finally {
            close(fis);
            close(out);
            close(in);
            disconnect(channel);
            disconnect(session);
        }

        return true;
    }

    protected static boolean InternalScpFrom(String user, String password, String host, String remote, String local, ISSHProgressCallback callback ) {

        FileOutputStream fos = null;
        Session session = null;
        Channel channel = null;
        OutputStream out = null;
        InputStream in = null;
        try {
            String prefix = null;
            if (new File(local).isDirectory()) {
                prefix = local + File.separator;
            }

            // for progress callback
            long lastCallbackMB = 0;
            long bytesWritten = 0;
            long MB = 1024 * 1024;

            JSch jsch = new JSch();
            java.util.Properties config = new java.util.Properties();
            session = jsch.getSession(user, host, 22);

            config.put("StrictHostKeyChecking", "no");
            session.setConfig(config);
            session.setPassword(password);
            session.connect( CONNECT_TIMEOUT );
            session.setServerAliveInterval( SERVER_ALIVE );

            // exec 'scp -f rfile' remotely
            String command = "scp -c arcfour -f \"" + remote + "\"";
            channel = session.openChannel("exec");
            ((ChannelExec) channel).setCommand(command);

            // get I/O streams for remote scp
            out = channel.getOutputStream();
            in = channel.getInputStream();

            channel.connect( CONNECT_TIMEOUT );

            byte[] buf = new byte[1024];

            // send '\0'
            buf[0] = 0;
            out.write(buf, 0, 1);
            out.flush();

            while (true) {
                int c = checkAck(in);
                if (c != 'C') {
                    break;
                }

                // read '0644 '
                in.read(buf, 0, 5);

                long filesize = 0L;
                while (true) {
                    if (in.read(buf, 0, 1) < 0) {
                        // error
                        break;
                    }
                    if (buf[0] == ' ') break;
                    filesize = filesize * 10L + (long) (buf[0] - '0');
                }

                String file = null;
                for (int i = 0; ; i++) {
                    in.read(buf, i, 1);
                    if (buf[i] == (byte) 0x0a) {
                        file = new String(buf, 0, i);
                        break;
                    }
                }

                //System.out.println("filesize="+filesize+", file="+file);

                // send '\0'
                buf[0] = 0;
                out.write(buf, 0, 1);
                out.flush();

                // read a content of lfile
                fos = new FileOutputStream(prefix == null ? local : prefix + file);
                int foo;
                while (true) {
                    if (buf.length < filesize) foo = buf.length;
                    else foo = (int) filesize;
                    foo = in.read(buf, 0, foo);
                    if (foo < 0) {
                        // error
                        break;
                    }
                    fos.write(buf, 0, foo);
                    filesize -= foo;
                    if (filesize == 0L) break;

                    // callback once per MB written
                    bytesWritten += foo;
                    if( (bytesWritten / MB) > lastCallbackMB ) {
                        lastCallbackMB = bytesWritten / MB;
                        if( callback != null ) {
                            callback.Progress( String.format("%d",bytesWritten) );
                        }
                    }
                }
                fos.close();
                fos = null;

                if (checkAck(in) != 0) {
                    throw new Exception("checkAck failed.");
                }

                // send '\0'
                buf[0] = 0;
                out.write(buf, 0, 1);
                out.flush();
            }

        }
        catch (Exception e) {
            e.printStackTrace();
            System.out.println(e.getMessage());
            return false;
        }
        finally {
            close(fos);
            close(out);
            close(in);
            disconnect(channel);
            disconnect(session);
        }

        File fLocal = new File(local);

        if (fLocal.exists() == false) {
            return false;
        }

        return true;
    }


    public static String SSHExecOutput(String user, String password, String host, String command) {

        Session session = null;
        Channel c = null;
        String output = null;
        StringWriter writer = new StringWriter();

        try {

            do {
                int exitCode = -1;

                session = OpenSession(user, password, host);
                if( session == null ) {
                    break;
                }

                try {
                    InputStream in;
                    byte[] tmp = new byte[1024];

                    c = session.openChannel("exec");
                    ((ChannelExec) c).setCommand(command);

                    c.setInputStream(null);

                    ((ChannelExec) c).setErrStream(System.err);

                    in = c.getInputStream();

                    c.connect();

                    while( true ) {
                        while( in.available() > 0 ) {
                            int i = in.read(tmp, 0, 1024);
                            if( i < 0 ) {
                                break;
                            }
                            writer.write(new String(tmp, 0, i));
//                            System.out.print(new String(tmp, 0, i));
                        }

                        if( c.isClosed() ) {
                            exitCode = c.getExitStatus();
//                            System.out.println("exit-status: " + exitCode);
                            break;
                        }

                        try {
                            Thread.sleep(1000);
                        }
                        catch( Exception ee ) {
                        }
                    }
                }
                catch( Exception ex ) {
                    break;
                }

                if( exitCode != 0 ) {
                    break;
                }

                output = writer.toString();
            } while( false );
        }
        finally {
            disconnect(c);
            disconnect(session);
        }

        return output;
    }

    public static boolean SSHExec(String user, String password, String host, String command) {

        Session session = null;
        Channel c = null;
        boolean success = false;

        try {

            do {
                int exitCode = -1;

                session = OpenSession(user, password, host);
                if( session == null ) {
                    break;
                }

                try {
                    InputStream in;
                    byte[] tmp = new byte[1024];

                    c = session.openChannel("exec");
                    ((ChannelExec) c).setCommand(command);

                    c.setInputStream(null);

                    ((ChannelExec) c).setErrStream(System.err);

                    in = c.getInputStream();

                    c.connect();

                    while( true ) {
                        while( in.available() > 0 ) {
                            int i = in.read(tmp, 0, 1024);
                            if( i < 0 ) {
                                break;
                            }
                            System.out.print(new String(tmp, 0, i));
                        }

                        if( c.isClosed() ) {
                            exitCode = c.getExitStatus();
                            System.out.println("exit-status: " + exitCode);
                            break;
                        }

                        try {
                            Thread.sleep(1000);
                        }
                        catch( Exception ee ) {
                        }
                    }
                }
                catch( Exception ex ) {
                    break;
                }

                if( exitCode != 0 ) {
                    break;
                }

                success = true;
            } while( false );
        }
        finally {
            disconnect(c);
            disconnect(session);
        }

        return success;
    }

    protected static int checkAck(InputStream in) throws IOException {
        int b = in.read();
        // b may be 0 for success,
        //          1 for error,
        //          2 for fatal error,
        //          -1
        if (b == 0) return b;
        if (b == -1) return b;

        if (b == 1 || b == 2) {
            StringBuffer sb = new StringBuffer();
            int c;
            do {
                c = in.read();
                sb.append((char) c);
            }
            while (c != '\n');
            if (b == 1) { // error
                System.out.print(sb.toString());
            }
            if (b == 2) { // fatal error
                System.out.print(sb.toString());
            }
        }
        return b;
    }

    public static void close(Closeable o) {
        if( o == null ) {
            return;
        }

        try {
            o.close();
        }
        catch( Exception ex ) {
            //log the exception
        }
        finally {
            o = null;
        }

        return;
    }

    public static void disconnect(Channel o) {
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

    public static long InternalSSHGetFreeDiskSpace(String username, String password, String hostname, String directory) {

        String output;
        long free = -1;

        try {
            String diskLine;
            String[] columns;

            output = Scp.SSHExecOutput( username, password, hostname, String.format("df -m %s",directory) );
            if( output == null ) {
                return -1;
            }

            BufferedReader r = new BufferedReader(new StringReader(output));
            r.readLine();
            diskLine = r.readLine();
            columns = diskLine.split("\\s+");
            free = Long.parseLong(columns[3]) * 1024L * 1024L;
        }
        catch( Exception ex ) {
            System.out.println(ex.getMessage());
            return -1;
        }

        return free;
    }
}
