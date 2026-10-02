package com.rkuo.Executables;

import java.io.*;
import java.io.File;
import java.net.InetAddress;
import java.net.MalformedURLException;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.nio.channels.FileChannel;
import java.rmi.Naming;
import java.rmi.NotBoundException;
import java.rmi.RMISecurityManager;
import java.rmi.RemoteException;
import java.util.ArrayList;

import com.rkuo.logging.RKLog;
import com.rkuo.shared.HBXWrapperConfig;
import com.rkuo.threading.ThreadState;
import com.rkuo.util.*;
import com.rkuo.handbrake.*;

// Watches a input directory
// Moves the files to a processing directory
// Submits an xgrid job for each file
public class HBXWrapperExe {

    private static long TIMEOUT_PUSH = 5 * 60 * 1000; // 15 min (in ms)
    private static String LOCAL_CONFIGURATION = "/private/var/xgrid/hbxconfig.xml";

    public static void main( String[] args ) {

        String  hostname;
        CommandLineParser       clp;
        ShutdownThread          shutdownThread;
        HBXWrapperParams        hbxwp;

        HBXBaseWrapperLogic baseWrapper = new HBXWrapperLogic();

        RKLog.Log( "HBXWrapper starting." );

        hostname = "";

        try {
            hostname = InetAddress.getLocalHost().getHostName();
        }
        catch( UnknownHostException uhex ) {
            // non-fatal
        }

        RKLog.Log( "Executing on %s.", hostname );

        try {
            shutdownThread = new ShutdownThread();
            Runtime.getRuntime().addShutdownHook( shutdownThread );
        }
        catch( Exception ex ) {
            RKLog.Log( "Could not add shutdown hook." );
            return;
        }

        clp = new CommandLineParser();

        clp.Parse(args);

        hbxwp = HBXWrapperParams.GetHBXWrapperParams( clp );
        if( hbxwp == null ) {
            RKLog.Log( "Missing required parameters." );
            baseWrapper.ReportError("");
            return;
        }

        File    fConfig;

        fConfig = new File( LOCAL_CONFIGURATION );
        if( fConfig.exists() == true ) {
            // pass everything to a remote invocation

            String  sConfig;
            HBXWrapperConfig hbxConfig;
            boolean             br;
            File    f;

            RKLog.Log( "Local proxy configuration file found.  Will proxy work to a remote instance." );

            hbxConfig = new HBXWrapperConfig();

            sConfig = com.rkuo.io.File.ToString( LOCAL_CONFIGURATION );
            br = hbxConfig.Deserialize( sConfig );
            if( br == false ) {
                RKLog.Log( "hbxConfig.Deserialize failed" );
                baseWrapper.ReportError( "" );
                return;
            }

            f = new File( hbxwp.Source );

            RKLog.setOutFile( "/private/tmp/" + f.getName() + ".txt" );

            try {
                InvokeRemote( hbxConfig, hbxwp );
            }
            catch( Exception ex ) {
                RKLog.Log( "InvokeRemote exceptioned." + " " + ex.getMessage() );
                baseWrapper.ReportError( "" );
            }                  

            return;
        }
        else {
            // execute work locally
            try {
                IHBXExecutor executor = (IHBXExecutor)baseWrapper;

                executor.Execute( hbxwp );
                RKLog.Log( "HBXWrapper: Exiting..." );
            }
            finally {
/*                try {
                    OutputStream    out;

                    out = HBXWrapperService.getOutputStream();
                    if( out != null ) {
                        out.close();
                    }
                }
                catch( IOException ioex ) {
                    // do nothing
                }
 */
            }
        }

        return;
    }

    protected static void InvokeRemote( HBXWrapperConfig hbxConfig, HBXWrapperParams hbxwp ) {

        IHBXWrapperService service;
        File fJar;
        boolean br;

        service = null;

        // Assign security manager
		if( System.getSecurityManager() == null ) {
			System.setSecurityManager( new RMISecurityManager() );
		}
/*
        workerThread = new HBXWrapperThread(hbxConfig,hbxwp);

        while( true ) {
            try {
                Thread.sleep(1000);
            }
            catch( InterruptedException iex ) {

            }

            if( workerThread.Started == true ) {
                break;
            }
        }
 */
        RKLog.Log( "Looking up remote service." );
        try {
            service = (IHBXWrapperService) Naming.lookup( hbxConfig.ServiceUrl );
        }
        catch( NotBoundException nbex ) {
            RKLog.Log( nbex.getMessage() );
        }
        catch( MalformedURLException muex ) {
            RKLog.Log( muex.getMessage() );
        }
        catch( RemoteException rex ) {
            RKLog.Log( rex.getMessage() );
        }

        if( service == null ) {
            RKLog.Log( "HBXWrapperService lookup returned null." );
            return;
        }

        RKLog.Log( "Pushing files to remote worker." );
        try {
            fJar = new File(com.rkuo.Executables.HBXWrapperExe.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            RKLog.Log( "Current jar path is %s.", fJar.getAbsolutePath() );
        }
        catch( URISyntaxException urisex ) {
            RKLog.Log( "Could not obtain path of current jar. " + urisex.getMessage()  );
            return;
        }

        try {
            br = ReliablePush( service, fJar, TIMEOUT_PUSH );
            if( br == false ) {
                RKLog.Log( "ReliablePush failed." );
                return;
            }

            RKLog.Log( "%s pushed.", fJar.getName() );
        }
        catch( RemoteException rex ) {
            RKLog.Log( "Files not pushed to remote worker. " + rex.getMessage()  );
            return;
        }

        RKLog.Log( "Calling service.Start." );
        try {
            String[]    args;

            args = GetRemoteArgs( hbxConfig, hbxwp );

            service.Start( fJar.getName(), args );
        }
        catch( RemoteException rex ) {
            RKLog.Log( "service.Start exceptioned. " + rex.getMessage()  );
            return;
        }

        RKLog.Log( "Polling remote service..." );
        while( true ) {
            ThreadState state;

            state = ThreadState.Running;

            try {
                Thread.sleep(1000);
            }
            catch( InterruptedException iex ) {
                // do nothing
            }

            try {
                // The remote process will abort if this is not called at least once every 60 seconds
                state = service.getThreadState();
            }
            catch( RemoteException rex ) {
                RKLog.Log( "service.getThreadState exceptioned. " + rex.getMessage()  );
                // do nothing?  Occasional exceptions here are bombing the proxy
            }

            while( true ) {
                String  line;

                line = null;

                try {
                    line = service.readLine();
                }
                catch( RemoteException rex ) {
                    RKLog.Log( "service.readLine exceptioned. " + rex.getMessage()  );
                    break;
                }

                if( line == null ) {
                    break;
                }

                RKLog.println( line );
            }

            if( state == ThreadState.Idle || state == ThreadState.Finished ) {
                RKLog.Log( "ThreadState is no longer running.  Polling halted." );
                break;
            }
        }

        return;
    }

    private static String[] GetRemoteArgs( HBXWrapperConfig hbxConfig, HBXWrapperParams hbxwp ) {
        ArrayList<String> args;
        String              sSourceName;
        String              sTargetName;
        String              sStatsName;
        String              sProxySource;
        String              sProxyTarget;
        String              sProxyStats;

        sSourceName = new File( hbxwp.Source ).getName();
        sProxySource = String.format( "%s\\%s", hbxConfig.SourceDirectory, sSourceName );

        sTargetName = new File( hbxwp.Target ).getName();
        sProxyTarget = String.format( "%s\\%s", hbxConfig.TargetDirectory, sTargetName );

        sStatsName = new File( hbxwp.Stats ).getName();
        sProxyStats = String.format( "%s\\%s", hbxConfig.StatsDirectory, sStatsName );

        args = new ArrayList<String>();

        args.add( String.format("/tempdir:%s", hbxConfig.TempDirectory) );
        args.add( String.format("/handbrake_x86:%s", hbxConfig.Handbrake) );
        args.add( String.format("/handbrake_x64:%s", hbxConfig.Handbrake) );
        args.add( String.format("/source:%s", sProxySource) );
        args.add( String.format("/target:%s", sProxyTarget) );
        args.add( String.format("/stats:%s", sProxyStats) );

        return args.toArray( new String[args.size()] );
    }

    public static boolean ReliablePush( IHBXWrapperService service, File fSource, long timeout) throws RemoteException {

        long    lastSuccess;
        long    lastPosition;
        boolean bFinished;
        byte[]  buf;

        buf = new byte[65536];

        lastSuccess = System.currentTimeMillis();
        lastPosition = 0;
        bFinished = false;

        while( true ) {
            FileInputStream fisSource;

            fisSource = null;

            try {
                FileChannel fcSource;

                RKLog.Log( "Starting fault tolerant push at position %d.", lastPosition );

                fisSource = new FileInputStream(fSource);

                fcSource = fisSource.getChannel();
                fcSource.position( lastPosition );

                while( true ) {

                    int len;

                    len = fisSource.read( buf );
                    if( len <= 0 ) {
                        bFinished = true;
                        break;
                    }

                    service.putFile( fSource.getName(), lastPosition, buf, len );

                    lastPosition = fcSource.position();
                    lastSuccess = System.currentTimeMillis();
                }
            }
            catch (FileNotFoundException ex) {
                RKLog.Log( ex.getMessage() + " in the specified directory." );
            }
            catch (IOException ioex) {
                RKLog.Log( ioex.getMessage() );
            }
            finally {
                Misc.close( fisSource );
            }

            if( bFinished == true ) {
                break;
            }

            if( System.currentTimeMillis() > lastSuccess + timeout ) {
                RKLog.Log( "Timeout exceeded: %d ms", timeout );
                break;
            }

            try {
                Thread.sleep( 60000 );
            }
            catch( InterruptedException iex ) {
                // do nothing
            }
        }

        return bFinished;
    }
}
