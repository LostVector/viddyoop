package com.rkuo.logging;

import com.rkuo.util.FileUtils;

import java.io.*;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

/**
 * Created by IntelliJ IDEA.
 * User: rkuo
 * Date: Aug 15, 2010
 * Time: 2:47:45 PM
 * To change this template use File | Settings | File Templates.
 */
public class RKLog {

    public static final String DATE_FORMAT_NOW = "yyyy-MM-dd HH:mm:ss";
    public static PrintStream _out = null;
    public static String _outFile = "";

    public static void ConsoleLog( String format, Object ... args ) {

        String line;

        line = GenerateCarriageReturnLine( format, args );

        try {
            PrintStream sysOut = new PrintStream(System.out,true,"UTF-8");
            sysOut.print( line );
            sysOut.flush();
        }
        catch( Exception ex ) {
            // do nothing
        }

        line = GenerateLogLine( format, args );

        if( _out != null ) {
            _out.print( line );
            _out.flush();
        }

        if( _outFile.length() > 0 ) {
            internal_writefile( line );
        }

        return;
    }

    public static void Log( String format, Object ... args ) {

        String line = GenerateLogLine( format, args );

        try {
            PrintStream sysOut = new PrintStream(System.out,true,"UTF-8");
            sysOut.print( line );
            sysOut.flush();
        }
        catch( Exception ex ) {
            // do nothing
        }

        if( _out != null ) {
            _out.print( line );
            _out.flush();
        }

        if( _outFile.length() > 0 ) {
            internal_writefile( line );
        }

        return;
    }

    public static void println( String line ) {

        String  outLine;

        if( line == null ) {
            return;
        }

        outLine = GeneratePrintln( line );

        try {
            PrintStream sysOut = new PrintStream(System.out,true,"UTF-8");
            sysOut.print( outLine );
            sysOut.flush();
        }
        catch( Exception ex ) {
            // do nothing
        }

        if( _out != null ) {
            _out.print( outLine );
            _out.flush();
        }

        if( _outFile.length() > 0 ) {
            internal_writefile( outLine );
        }

        return;
    }

    protected static String GenerateCarriageReturnLine( String format, Object ... args ) {
        Calendar            cal;
        SimpleDateFormat    sdf;
        String              line;

        cal = Calendar.getInstance();
        sdf = new SimpleDateFormat(DATE_FORMAT_NOW);

        line = String.format( "[%s] ", sdf.format(cal.getTime()) );
        line += String.format(format, args);
        line += "\r";
        return line;
    }

    protected static String GenerateLogLine( String format, Object ... args ) {
        Calendar            cal;
        SimpleDateFormat    sdf;
        String              line;

        cal = Calendar.getInstance();
        sdf = new SimpleDateFormat(DATE_FORMAT_NOW);

        line = String.format( "[%s] ", sdf.format(cal.getTime()) );
        line += String.format(format, args);
        line += "\n";
        return line;
    }

    protected static String GeneratePrintln( String line ) {

        String  outLine;

        if( line.endsWith("\n") == true ) {
            outLine = line;
        }
        else {
            outLine = line + "\n";
        }

        return outLine;
    }

    protected static void internal_writefile( String line ) {

        FileOutputStream fos;
        OutputStreamWriter osw;

        if( _outFile == null ) {
            return;
        }

        try {
            fos = new FileOutputStream(_outFile, true);
        }
        catch( FileNotFoundException fnfex ) {
            return;
        }

        try {
            osw = new OutputStreamWriter(fos, "UTF-8");
        }
        catch( UnsupportedEncodingException ueex ) {
            return;
        }

        try {
            osw.write( line );
            osw.close();
        }
        catch( IOException ioex ) {
            return;
        }

        return;
    }
    public static void setOut( PrintStream out ) {
        _out = out;
        return;
    }

    public static void setOutFile( String sOutFile ) {

        String  finalOutFile;
        int     counter;

        counter = 0;
        finalOutFile = sOutFile;
        while( true ) {
            File    f;

            f = new File( finalOutFile );
            if( f.exists() == false ) {
                break;
            }

            counter++;
            finalOutFile = FileUtils.getAbsolutePathWithoutExtension(sOutFile) + "-" + Integer.toString(counter) + "." + com.rkuo.io.File.GetExtension(sOutFile);
        }

        _outFile = finalOutFile;
        return;
    }
}
