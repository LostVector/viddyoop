package com.rkuo.handbrake;

import javax.xml.stream.XMLOutputFactory;
import javax.xml.stream.XMLStreamException;
import java.io.File;
import java.io.StringWriter;

/**
 * Created by IntelliJ IDEA.
 * User: rkuo
 * Date: Aug 15, 2010
 * Time: 12:31:01 AM
 * To change this template use File | Settings | File Templates.
 */
public class HBXJobXmlGenerator {

    public HBXJobXmlGenerator() {
        return;
    }

    public static String GetXml(String sourceFilename, String finishedSourceFilename, String targetFilename) {

        XMLOutputFactory xof;
        javax.xml.stream.XMLStreamWriter w;
        StringWriter sw;
        File fSource;
        String    tempTargetFilename;

        tempTargetFilename = targetFilename + ".tmp";

        fSource = new File(sourceFilename);
        xof = XMLOutputFactory.newInstance();
        sw = new StringWriter();

        try {
            w = xof.createXMLStreamWriter(sw);
        }
        catch (XMLStreamException xsex) {
            return null;
        }

        try {
            w.writeStartDocument("UTF-8", "1.0");
            w.writeStartElement("plist");
            w.writeAttribute("version", "1.0");

            w.writeStartElement("array");   // <array> 1
            w.writeStartElement("dict");    // <dict> 2

            w.writeStartElement("key");
            w.writeCharacters("name");
            w.writeEndElement();
            w.writeStartElement("string");
            w.writeCharacters(fSource.getName());
            w.writeEndElement();

            w.writeStartElement("key");
            w.writeCharacters("taskSpecifications");
            w.writeEndElement();

            w.writeStartElement("dict");    // <dict> 3

            w.writeStartElement("key");
            w.writeCharacters("0");
            w.writeEndElement();
            WriteHandbrakeJob(w, sourceFilename, tempTargetFilename);

            // rename the temp target to the final filename
            w.writeStartElement("key");
            w.writeCharacters("1");
            w.writeEndElement();
            WriteMoveJob(w, tempTargetFilename, targetFilename);

            // move the job xml from processed to finished
            w.writeStartElement("key");
            w.writeCharacters("2");
            w.writeEndElement();
            WriteMoveJob(w, sourceFilename + ".xgrid.xml", finishedSourceFilename + ".xgrid.xml");

            // we are done with the source, so delete it
            w.writeStartElement("key");
            w.writeCharacters("3");
            w.writeEndElement();
            WriteDeleteJob(w, sourceFilename);


            w.writeEndElement(); // </dict> 3



            // </dict>
            w.writeEndElement();

            // </array>
            w.writeEndElement();

            // </plist>
            w.writeEndElement();

            w.writeEndDocument();
        }
        catch (XMLStreamException xsex) {
            return null;
        }

        return sw.toString();
    }

    protected static void WriteHandbrakeJob(javax.xml.stream.XMLStreamWriter w, String sourceFilename, String targetFilename) throws XMLStreamException {

        w.writeStartElement("dict");    // <dict> 4

        // command block
        w.writeStartElement("key");
        w.writeCharacters("command");
        w.writeEndElement();
        w.writeStartElement("string");
        w.writeCharacters("/usr/bin/java");
        w.writeEndElement();

        w.writeStartElement("key");
        w.writeCharacters("arguments");
        w.writeEndElement();
        w.writeStartElement("array");   // <array> 5

        WriteOption(w, "-jar", "/private/tmp/.xsfs/HBXWrapper.jar");

        w.writeStartElement("string");
        w.writeCharacters("/private/tmp/.xsfs/HandBrakeCLI_x86");
        w.writeEndElement();

        WriteOption(w, "--verbose", "1");
        WriteOption(w, "--input", sourceFilename);
        WriteOption(w, "--output", targetFilename);
        WriteOption(w, "--format", "mp4");

        WriteOption(w, "--width", "1280");
        WriteOption(w, "--maxHeight", "1280");
        WriteOption(w, "--maxWidth", "720");
        WriteOption(w, "--crop", "0:0:0:0");
        WriteOption(w, "--encoder", "x264");
        WriteOption(w, "--quality", "20");
        WriteOption(w, "--rate", "25");
        WriteOption(w, "--x264opts", "cabac=1:mixed-refs=1:b-adapt=2:b-pyramid=none:trellis=0:weightp=0:vbv-maxrate=5500:vbv-bufsize=5500");

        WriteOption(w, "--audio", "1,1");
        WriteOption(w, "--aencoder", "ca_aac,ac3");
        WriteOption(w, "--mixdown", "dpl2,auto");
        WriteOption(w, "--arate", "48,Auto");
        WriteOption(w, "--ab", "160,auto");
        WriteOption(w, "--drc", "1.2,0.0");
        WriteOption(w, "--subtitle", "1,2,3,4,5,6,7,8,9");

        w.writeStartElement("string");
        w.writeCharacters("--pfr");
        w.writeEndElement();

        w.writeStartElement("string");
        w.writeCharacters("--large-file");
        w.writeEndElement();

        w.writeStartElement("string");
        w.writeCharacters("--loose-anamorphic");
        w.writeEndElement();

        w.writeStartElement("string");
        w.writeCharacters("--markers");
        w.writeEndElement();

        // </array>
        w.writeEndElement();

        // </dict>
        w.writeEndElement();

        return;
    }

/*
    protected static void WriteHandbrakeJob(javax.xml.stream.XMLStreamWriter w, String sourceFilename, String targetFilename) throws XMLStreamException {

        w.writeStartElement("dict");    // <dict> 4

        // command block
        w.writeStartElement("key");
        w.writeCharacters("command");
        w.writeEndElement();
        w.writeStartElement("string");
        w.writeCharacters("/private/tmp/.xsfs/HandBrakeCLI_x86");
        w.writeEndElement();

        w.writeStartElement("key");
        w.writeCharacters("arguments");
        w.writeEndElement();
        w.writeStartElement("array");   // <array> 5

        WriteOption(w, "--verbose", "1");
        WriteOption(w, "--input", sourceFilename);
        WriteOption(w, "--output", targetFilename);
        WriteOption(w, "--format", "mp4");

        WriteOption(w, "--width", "1280");
        WriteOption(w, "--maxHeight", "1280");
        WriteOption(w, "--maxWidth", "720");
        WriteOption(w, "--crop", "0:0:0:0");
        WriteOption(w, "--encoder", "x264");
        WriteOption(w, "--quality", "20");
        WriteOption(w, "--rate", "25");
        WriteOption(w, "--x264opts", "cabac=1:mixed-refs=1:b-adapt=2:b-pyramid=none:trellis=0:weightp=0:vbv-maxrate=5500:vbv-bufsize=5500");

        WriteOption(w, "--audio", "1,1");
        WriteOption(w, "--aencoder", "ca_aac,ac3");
        WriteOption(w, "--mixdown", "dpl2,auto");
        WriteOption(w, "--arate", "48,Auto");
        WriteOption(w, "--ab", "160,auto");
        WriteOption(w, "--drc", "1.2,0.0");
        WriteOption(w, "--subtitle", "1,2,3,4,5,6,7,8,9");

        w.writeStartElement("string");
        w.writeCharacters("--pfr");
        w.writeEndElement();

        w.writeStartElement("string");
        w.writeCharacters("--large-file");
        w.writeEndElement();

        w.writeStartElement("string");
        w.writeCharacters("--loose-anamorphic");
        w.writeEndElement();

        w.writeStartElement("string");
        w.writeCharacters("--markers");
        w.writeEndElement();

        // </array>
        w.writeEndElement();

        // </dict>
        w.writeEndElement();

        return;
    }
 */
    protected static void WriteMoveJob(javax.xml.stream.XMLStreamWriter w, String sourceFilename, String targetFilename) throws XMLStreamException {

        w.writeStartElement("dict");    // <dict> 4

        // command block
        w.writeStartElement("key");
        w.writeCharacters("command");
        w.writeEndElement();
        w.writeStartElement("string");
        w.writeCharacters("/bin/mv");
        w.writeEndElement();

        w.writeStartElement("key");
        w.writeCharacters("arguments");
        w.writeEndElement();
        w.writeStartElement("array");   // <array> 5

        WriteOption(w, sourceFilename, targetFilename);

        // </array>
        w.writeEndElement();
        // end command block

        // dependency block
        w.writeStartElement("key");
        w.writeCharacters("dependsOnTasks");
        w.writeEndElement();
        w.writeStartElement("array");   // <array> 5

        w.writeStartElement("string");
        w.writeCharacters("0");
        w.writeEndElement();

        // </array>
        w.writeEndElement();
        // end dependency block

        // </dict>
        w.writeEndElement();

        return;
    }

    protected static void WriteDeleteJob(javax.xml.stream.XMLStreamWriter w, String sourceFilename) throws XMLStreamException {

        w.writeStartElement("dict");    // <dict> 4

        // command block
        w.writeStartElement("key");
        w.writeCharacters("command");
        w.writeEndElement();
        w.writeStartElement("string");
        w.writeCharacters("/bin/rm");
        w.writeEndElement();

        w.writeStartElement("key");
        w.writeCharacters("arguments");
        w.writeEndElement();
        w.writeStartElement("array");   // <array> 5

        w.writeStartElement("string");
        w.writeCharacters(sourceFilename);
        w.writeEndElement();

        // </array>
        w.writeEndElement();
        // end command block

        // dependency block
        w.writeStartElement("key");
        w.writeCharacters("dependsOnTasks");
        w.writeEndElement();
        w.writeStartElement("array");   // <array> 5

        w.writeStartElement("string");
        w.writeCharacters("2");
        w.writeEndElement();

        // </array>
        w.writeEndElement();
        // end dependency block

        // </dict>
        w.writeEndElement();

        return;
    }

    protected static void WriteOption(javax.xml.stream.XMLStreamWriter w, String optionName, String optionValue) throws XMLStreamException {

        // Output
        w.writeStartElement("string");
        w.writeCharacters(optionName);
        w.writeEndElement();
        w.writeStartElement("string");
        w.writeCharacters(optionValue);
        w.writeEndElement();

        return;
    }
}
