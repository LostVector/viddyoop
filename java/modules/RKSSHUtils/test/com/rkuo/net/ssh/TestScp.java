package com.rkuo.net.ssh;

import java.net.URL;

import junit.runner.Version;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class TestScp {

    @Rule
    public final TemporaryFolder temp = new TemporaryFolder();

    @Test
    public void testSftpList() {

        String[] aFilenames;

        try {
            Sftp s = new Sftp( "", "", "" );

            aFilenames = s.SftpList("");
            if( aFilenames.length == 0 ) {
                Assert.fail("SftpList should not have returned zero filenames.");
            }
        }
        catch( Exception ex ) {
            Assert.fail("SftpList Exceptioned.");
        }

        return;
    }


    @Test
    public void testScpTo() {

        System.out.println("JUnit version is: " + Version.id());

        Boolean br;

        try {
            Scp scp = new Scp( "username", "password", "hostname" );
            URL url = this.getClass().getResource("test.xml");
            if( url == null ) {
                System.out.println( "test.xml not found." );
                throw new Exception();
            }

            System.out.println( "Test file: " + url.getPath() );
            br = scp.ScpTo(url.getPath(), "/home/root/test2.xml", null);
        }
        catch( Exception ex ) {
            br = false;
        }

        Assert.assertTrue(br);
        return;
    }

    @Test
    public void testScpFrom() {

        System.out.println("JUnit version is: " + Version.id());

        Boolean br;

        try {
//            String workingDir = System.getProperty("user.dir");
            String username, password, hostname, scpSource;
            java.io.File scpTarget;

            username = "";
            password = "";
            hostname = "";
            scpSource = "/Volumes/Storage/Shared/HBX/temp/test.xml";
            scpTarget = new java.io.File( temp.getRoot() + java.io.File.separator + "text.xml" );

            System.out.format( "Scp source: %s@%s:%s\n", username, hostname, scpTarget );
            System.out.format( "Scp target: %s\n", scpTarget.getAbsolutePath() );

            Scp scp = new Scp( username, password, hostname );

            br = scp.ScpFrom( scpSource, scpTarget.getAbsolutePath(), null );
            if( br == false ) {
                throw new Exception("ScpFrom failed.");
            }

            br = scpTarget.exists();
        }
        catch( Exception ex ) {
            System.out.println( ex.getMessage() );
            br = false;
        }

        Assert.assertTrue(br);
        return;
    }

    @Test
    public void testSftpIsDirectory() {
        String username, password, hostname, target;
        boolean br;

        username = "";
        password = "";
        hostname = "";

        Sftp s = new Sftp( username, password, hostname );

        try {
            target = "/home/root/cloudera";
            br = s.SftpIsDirectory( target );
            if( br == false ) {
                throw new Exception( String.format("IsDirectory returned false for %s, should return true.", target) );
            }

            target = "/home/root/Downloads/license.txt";
            br = s.SftpIsDirectory( target );
            if( br == true ) {
                throw new Exception( String.format("IsDirectory returned true for %s, should return false.", target) );
            }
        }
        catch( Exception ex ) {
            System.out.println( ex.getMessage() );
            Assert.assertTrue(false);
        }

        return;
    }

    @Test
    public void testSftpRename() {
        String username, password, hostname, target1, target2;
        boolean br;

        username = "";
        password = "";
        hostname = "";
        target1 = "/Volumes/Storage/Shared/HBX/temp/dir1/test.xml";
        target2 = "/Volumes/Storage/Shared/HBX/temp/dir2/test.xml";

        Sftp s = new Sftp( username, password, hostname );
        Scp scp = new Scp( username, password, hostname );

        try {
            br = s.SftpDelete(target1);
            br = s.SftpDelete(target2);

            URL url = this.getClass().getResource("test.xml");
            if( url == null ) {
                System.out.println( "test.xml not found." );
                throw new Exception();
            }

            System.out.println( "Test file: " + url.getPath() );
            br = scp.ScpTo(url.getPath(), target1, null);
            if( br == false ) {
                Assert.fail( String.format("ScpTo failed (target = %s.)\n",target1) );
            }

            br = s.SftpRename(target1, target2);
            if( br == false ) {
                Assert.fail( String.format("SftpRename failed (source = %s, target = %s.)\n",target1,target2) );
            }
        }
        catch( Exception ex ) {
            System.out.println( ex.getMessage() );
            Assert.fail("testSftpRename exceptioned.");
        }

        return;
    }

    @Test
    public void testSSHGetFreeDiskSpaceOutput() {

        String username, password, hostname;
        String output;

        username = "";
        password = "";
        hostname = "";

       // Scp scp = new Scp( username, password, hostname );

        try {
            output = Scp.SSHExecOutput( username, password, hostname, "df -m /home/root/cloudera" );
            if( output != null ) {
                System.out.print(output);
            }
        }
        catch( Exception ex ) {
            System.out.println( ex.getMessage() );
            Assert.fail("testSSHGetFreeDiskSpace exceptioned.");
        }

        return;
    }

    @Test
    public void testSSHGetFreeDiskSpace() {

        String username, password, hostname, directory;
        long free;

        username = "";
        password = "";
        hostname = "";
        directory = "";

       Scp scp = new Scp( username, password, hostname );

        try {
            free = scp.GetFreeDiskSpace(directory);
            Assert.assertTrue(free > 0);
        }
        catch( Exception ex ) {
            System.out.println( ex.getMessage() );
            Assert.fail("testSSHGetFreeDiskSpace exceptioned.");
        }

        return;
    }
}
