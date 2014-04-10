package com.rkuo.util;

import com.rkuo.logging.RKLog;

import java.io.*;
import java.util.ArrayList;
import java.util.Collections;

/**
 * Created by IntelliJ IDEA.
 * User: rkuo
 * Date: Aug 14, 2010
 * Time: 7:53:08 PM
 * To change this template use File | Settings | File Templates.
 */
public class Misc {

    public static OutputStreamWriter GetUTF8FileWriter( String fileName ) {
        FileOutputStream    fos;
        OutputStreamWriter  osw;

        try {
            fos = new FileOutputStream(fileName);
        }
        catch( FileNotFoundException fnfex ) {
            return null;
        }

        try {
            osw = new OutputStreamWriter(fos, "UTF-8");
        }
        catch( UnsupportedEncodingException ueex ) {
            return null;
        }

        return osw;
    }

    public static boolean isWindows(){

		String os = System.getProperty("os.name").toLowerCase();
		//windows
	    return (os.indexOf( "win" ) >= 0); 
	}

    public static void Sleep( long ms ) {
        try {
            Thread.sleep( 1000 );
        }
        catch( InterruptedException iex ) {

        }
        
        return;
    }

    public static String GetTimeString( long elapsedTime ) {

        String format = String.format("%%0%dd", 2);
        elapsedTime = elapsedTime / 1000;
        String seconds = String.format(format, elapsedTime % 60);
        String minutes = String.format(format, (elapsedTime % 3600) / 60);
        String hours = String.format(format, elapsedTime / 3600);
        String time = hours + ":" + minutes + ":" + seconds;
        return time;
    }

    public static int ExecuteProcess( String exePath ) {

        ArrayList<String>   cmdArgs;

        cmdArgs = new ArrayList<String>();
        cmdArgs.add( exePath );

        return ExecuteProcess( cmdArgs.toArray(new String[cmdArgs.size()]) );
    }

    public static int ExecuteProcess( String[] cmdArgs ) {
        return ExecuteProcess( cmdArgs, null, true, null, null );
    }

    public static int ExecuteProcess(String[] cmdArgs, String workingDir, boolean bEcho, String outputFilename, ExecuteProcessCallback epCallback) {

        Process process;
        String line;
        InputStream is;
        InputStreamReader isr;
        BufferedReader bufr;

        FileWriter fWriter = null;
        BufferedWriter writer = null;

        int returnCode;

        returnCode = Integer.MAX_VALUE;

        try {
            ProcessBuilder pb;

            pb = new ProcessBuilder(cmdArgs);
            pb.redirectErrorStream(true);

            if( workingDir != null ) {
                pb.directory(new File(workingDir));
            }

            process = pb.start();
        }
        catch (IOException ioex) {
            return returnCode;
        }

        is = process.getInputStream();
        isr = new InputStreamReader(is);
        bufr = new BufferedReader(isr);

        try {
            if (outputFilename != null) {
                fWriter = new FileWriter(outputFilename);
                writer = new BufferedWriter(fWriter);
            }

            while (true) {

                int exitCode;

                exitCode = Integer.MIN_VALUE;
                try {
                    exitCode = process.exitValue();
                }
                catch (IllegalThreadStateException itsex) {
                    // process has not exited yet ... this is ok
                }

                while (true) {
                    try {
                        line = bufr.readLine();
                    }
                    catch (IOException ioex) {
                        break;
                    }

                    if (line == null) {
                        break;
                    }

                    if (epCallback != null) {
                        epCallback.ProcessLine(line);
                    }

                    if (bEcho == true) {
                        System.out.println(line);
                    }

                    if (outputFilename != null) {
                        writer.write(line);
                        writer.newLine();
                        writer.flush();
                    }
                }

                if (exitCode != Integer.MIN_VALUE) {
                    returnCode = exitCode;
                    break;
                }
            }
        }
        catch (Exception ex) {
            Misc.close(writer);
        }

        return returnCode;
    }

    public static void close(Closeable c) {
        if (c == null) return;
        try {
            c.close();
        } catch (IOException e) {
            //log the exception
        }
        finally {
            c = null;
        }

        return;
    }

    public static void printArgs( String[] args ) {
        // let's print the constructed command line for reference
        String command;

        command = "";
        for( String c : args ) {
            command += c + " ";
        }

        RKLog.Log( "Command line: %s", command );
        return;
    }
}
