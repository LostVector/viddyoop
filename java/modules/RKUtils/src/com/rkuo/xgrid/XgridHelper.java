package com.rkuo.xgrid;

import com.rkuo.logging.RKLog;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.*;
import java.text.DateFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;

import com.rkuo.xml.*;

/**
 * Created by IntelliJ IDEA.
 * User: rkuo
 * Date: Sep 24, 2010
 * Time: 12:38:57 PM
 * To change this template use File | Settings | File Templates.
 */
public class XgridHelper {

    public static Long[] XgridGetJobs() {

        Process             process;

        String              line;
        InputStream is;
        InputStreamReader isr;
        BufferedReader bufr;

        StringWriter sw;

        ArrayList<String> cmdArgs;
        ArrayList<Long>     aJobs;

        aJobs = new ArrayList<Long>();
        sw = new StringWriter();

        cmdArgs = new ArrayList<String>();
        cmdArgs.add("xgrid");

        cmdArgs.add("-hostname");
        cmdArgs.add("macserver.domain.com");

        cmdArgs.add("-f");
        cmdArgs.add("xml");

        cmdArgs.add("-auth");
        cmdArgs.add("Password");

        cmdArgs.add("-password");
        cmdArgs.add("xgrid");

        cmdArgs.add("-job");
        cmdArgs.add("list");

        try {
            ProcessBuilder      pb;

            pb = new ProcessBuilder(cmdArgs);
            pb.redirectErrorStream( true );

            process = pb.start();
        }
        catch( IOException ioex ) {
            return null;
        }

        is = process.getInputStream();
        isr = new InputStreamReader(is);
        bufr = new BufferedReader(isr);

        while (true) {

            int exitCode;

            exitCode = Integer.MIN_VALUE;
            try {
                exitCode = process.exitValue();
            }
            catch( IllegalThreadStateException itsex ) {
                // process has not exited yet ... this is ok
            }

            // We put this here so that any buffered data can be spooled out
            // after reading the exit code but prior to exiting
            while( true ) {
                try {
                    line = bufr.readLine();
                }
                catch (IOException ioex) {
                    break;
                }

                if( line == null ) {
                    break;
                }

//                System.out.println(line);
                sw.write( line );
            }

            if( exitCode != Integer.MIN_VALUE ) {
                if( exitCode != 0 ) {
                    return null;
                }

                break;
            }
        }

        try {
            DocumentBuilder db;
            Document        doc;

            db = XMLHelper.GetDocumentBuilder();
            doc = db.parse( new ByteArrayInputStream(sw.toString().getBytes()) );
            doc.getDocumentElement().normalize();
            NodeList nodes = doc.getElementsByTagName("string");

            for(int x=0; x < nodes.getLength(); x++) {

                Element el;
                String  v;

                el = (Element)nodes.item(x);
                v = el.getFirstChild().getNodeValue();
                aJobs.add( Long.parseLong(v) );
            }
        }
        catch( Exception ex ) {
            ex.printStackTrace();
        }

        return aJobs.toArray( new Long[aJobs.size()] );
    }

    private String GetTextValue(Element ele, String tagName) {
		String textVal = null;
		NodeList nl = ele.getElementsByTagName(tagName);
		if(nl != null && nl.getLength() > 0) {
			Element el = (Element)nl.item(0);
			textVal = el.getFirstChild().getNodeValue();
		}

		return textVal;
	}

    public static XgridJobAttributes XgridGetJobAttributes( Long jobId ) {

        Process             process;

        String              line;
        InputStream         is;
        InputStreamReader   isr;
        BufferedReader      bufr;

        ArrayList<String>   cmdArgs;
        XgridJobAttributes  xja;
        StringWriter        sw;

        sw = new StringWriter();
        xja = new XgridJobAttributes();

        cmdArgs = new ArrayList<String>();
        cmdArgs.add("xgrid");

        cmdArgs.add("-hostname");
        cmdArgs.add("macserver.domain.com");

//        cmdArgs.add("-f");
//        cmdArgs.add("xml");

        cmdArgs.add("-auth");
        cmdArgs.add("Password");

        cmdArgs.add("-password");
        cmdArgs.add("xgrid");

        cmdArgs.add("-job");
        cmdArgs.add("attributes");

        cmdArgs.add("-id");
        cmdArgs.add( jobId.toString() );

        try {
            ProcessBuilder      pb;

            pb = new ProcessBuilder(cmdArgs);
            pb.redirectErrorStream( true );

            process = pb.start();
        }
        catch( IOException ioex ) {
            return null;
        }

        is = process.getInputStream();
        isr = new InputStreamReader(is);
        bufr = new BufferedReader(isr);

        while (true) {

            int exitCode;

            exitCode = Integer.MIN_VALUE;
            try {
                exitCode = process.exitValue();
            }
            catch( IllegalThreadStateException itsex ) {
                // process has not exited yet ... this is ok
            }

            // We put this here so that any buffered data can be spooled out
            // after reading the exit code but prior to exiting
            while( true ) {
                try {
                    line = bufr.readLine();
                }
                catch (IOException ioex) {
                    break;
                }

                if( line == null ) {
                    break;
                }

//                System.out.println(line);
                sw.write( line );

                if (line.endsWith(";") == true) {
                    String tempLine;
                    String[] parts;
                    String name, value;

                    DateFormat df;
                    Date d;

                    df = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z");

                    tempLine = line.substring(0, line.length() - 1);
                    parts = tempLine.split("=");
                    if( parts.length == 2 ) {
                        name = parts[0].trim();
                        value = parts[1].trim();

                        if( value.startsWith("\"") == true && value.endsWith("\"") == true ) {
                            value = value.substring(1, value.length()-1);
                        }

                        if (name.compareToIgnoreCase("activeCPUPower") == 0) {
                            xja.activeCPUPower = Long.parseLong(value);
                        } else if (name.compareToIgnoreCase("dateNow") == 0) {
                            try {
                                xja.dateNow = df.parse(value);
                            }
                            catch (ParseException pex) {
                                // Do nothing
                            }
                        } else if (name.compareToIgnoreCase("dateSubmitted") == 0) {
                            try {
                                xja.dateSubmitted = df.parse(value);
                            }
                            catch (ParseException pex) {
                                // Do nothing
                            }
                        } else if (name.compareToIgnoreCase("dateStarted") == 0) {
                            try {
                                xja.dateStarted = df.parse(value);
                            }
                            catch (ParseException pex) {
                                // Do nothing
                            }
                        } else if (name.compareToIgnoreCase("dateStopped") == 0) {
                            try {
                                xja.dateStopped = df.parse(value);
                            }
                            catch (ParseException pex) {
                                // Do nothing
                            }
                        } else if (name.compareToIgnoreCase("jobStatus") == 0) {
                            xja.jobStatus = value;
                        } else if (name.compareToIgnoreCase("name") == 0) {
                            xja.name = value;
                        } else if (name.compareToIgnoreCase("percentDone") == 0) {
                            xja.percentDone = Double.parseDouble(value);
                        } else if (name.compareToIgnoreCase("taskCount") == 0) {
                            xja.taskCount = Long.parseLong(value);
                        } else if (name.compareToIgnoreCase("undoneTaskCount") == 0) {
                            xja.undoneTaskCount = Long.parseLong(value);
                        } else {
                            // nothing
                        }
                    }
                }
            }

            if( exitCode != Integer.MIN_VALUE ) {
                if( exitCode != 0 ) {
                    return null;
                }

                break;
            }
        }

/*
        try {
            DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
            DocumentBuilder db = dbf.newDocumentBuilder();
            Document doc = db.parse( new ByteArrayInputStream(sw.toString().getBytes()) );
            doc.getDocumentElement().normalize();
            NodeList nodes = doc.getElementsByTagName("dict");

            NodeList    dict;

            dict = nodes.item(1).getChildNodes();

            String currentKey;
            String currentString;

            for(int x=0; x < dict.getLength(); x++) {

                Element el;
                String  v;

                el = (Element)nodes.item(x);
                v = el.getFirstChild().getNodeValue();
                RKLog.Log("value: %s", v);
            }
        }
        catch( Exception ex ) {
            ex.printStackTrace();
        }
 */

        return xja;
    }

    public static String XgridGetJobResults( Long jobId ) {

        Process             process;

        String              line;
        InputStream         is;
        InputStreamReader   isr;
        BufferedReader      bufr;

        ArrayList<String>   cmdArgs;
        XgridJobAttributes  xja;
        StringWriter        sw;

        sw = new StringWriter();
        xja = new XgridJobAttributes();

        cmdArgs = new ArrayList<String>();
        cmdArgs.add("xgrid");

        cmdArgs.add("-hostname");
        cmdArgs.add("macserver.domain.com");

//        cmdArgs.add("-f");
//        cmdArgs.add("xml");

        cmdArgs.add("-auth");
        cmdArgs.add("Password");

        cmdArgs.add("-password");
        cmdArgs.add("xgrid");

        cmdArgs.add("-job");
        cmdArgs.add("results");

        cmdArgs.add("-id");
        cmdArgs.add( jobId.toString() );

        try {
            ProcessBuilder      pb;

            pb = new ProcessBuilder(cmdArgs);
            pb.redirectErrorStream( true );

            process = pb.start();
        }
        catch( IOException ioex ) {
            return null;
        }

        is = process.getInputStream();
        isr = new InputStreamReader(is);
        bufr = new BufferedReader(isr);

        while (true) {

            int exitCode;

            exitCode = Integer.MIN_VALUE;
            try {
                exitCode = process.exitValue();
            }
            catch( IllegalThreadStateException itsex ) {
                // process has not exited yet ... this is ok
            }

            // We put this here so that any buffered data can be spooled out
            // after reading the exit code but prior to exiting
            while( true ) {
                try {
                    line = bufr.readLine();
                }
                catch (IOException ioex) {
                    break;
                }

                if( line == null ) {
                    break;
                }

               sw.write( line );
            }

            if( exitCode != Integer.MIN_VALUE ) {
                if( exitCode != 0 ) {
                    return null;
                }

                break;
            }
        }

        return sw.toString();
    }

    public static boolean XgridDeleteJob( Long jobId ) {

        Process             process;

        String              line;
        InputStream         is;
        InputStreamReader   isr;
        BufferedReader      bufr;

        ArrayList<String>   cmdArgs;

        cmdArgs = new ArrayList<String>();
        cmdArgs.add("xgrid");

        cmdArgs.add("-hostname");
        cmdArgs.add("macserver.domain.com");

        cmdArgs.add("-auth");
        cmdArgs.add("Password");

        cmdArgs.add("-password");
        cmdArgs.add("xgrid");

        cmdArgs.add("-job");
        cmdArgs.add("delete");

        cmdArgs.add("-id");
        cmdArgs.add( jobId.toString() );

        try {
            ProcessBuilder      pb;

            pb = new ProcessBuilder(cmdArgs);
            pb.redirectErrorStream( true );

            process = pb.start();
        }
        catch( IOException ioex ) {
            return false;
        }

        is = process.getInputStream();
        isr = new InputStreamReader(is);
        bufr = new BufferedReader(isr);

        while (true) {

            int exitCode;

            exitCode = Integer.MIN_VALUE;
            try {
                exitCode = process.exitValue();
            }
            catch( IllegalThreadStateException itsex ) {
                // process has not exited yet ... this is ok
            }

            // We put this here so that any buffered data can be spooled out
            // after reading the exit code but prior to exiting
            while( true ) {
                try {
                    line = bufr.readLine();
                }
                catch (IOException ioex) {
                    break;
                }

                if( line == null ) {
                    break;
                }
            }

            if( exitCode != Integer.MIN_VALUE ) {
                if( exitCode != 0 ) {
                    return false;
                }

                break;
            }
        }

        return true;
    }

    public static boolean SubmitXgridJob(String jobFilename) {

        Process process;
        String line;
        InputStream is;
        InputStreamReader isr;
        BufferedReader breader;
        ArrayList<String> xgridArgs;

        RKLog.Log("Submitting %s to Xgrid.", jobFilename);

        xgridArgs = new ArrayList<String>();
        xgridArgs.add("xgrid");

        xgridArgs.add("-hostname");
        xgridArgs.add("macserver.domain.com");

        xgridArgs.add("-auth");
        xgridArgs.add("Password");

        xgridArgs.add("-password");
        xgridArgs.add("xgrid");

        xgridArgs.add("-job");
        xgridArgs.add("batch");
        xgridArgs.add(jobFilename);

        try {
            process = new ProcessBuilder(xgridArgs).start();
        }
        catch( IOException ioex ) {
            return false;
        }

        is = process.getInputStream();
        isr = new InputStreamReader(is);
        breader = new BufferedReader(isr);

//                System.out.printf( "Output of running %s is:", Arrays.toString(args) );

        while (true) {
            try {
                line = breader.readLine();
            }
            catch (IOException ioex) {
                break;
            }

            if (line == null) {
                break;
            }

            RKLog.Log(line);
        }

        return true;
    }
}
