package com.rkuo.deprecated;

import com.rkuo.handbrake.HBXWrapperParams;

import javax.xml.stream.XMLOutputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamWriter;
import java.io.File;
import java.io.UnsupportedEncodingException;
import java.io.ByteArrayOutputStream;

/**
 * Created by IntelliJ IDEA.
 * User: rkuo
 * Date: Aug 15, 2010
 * Time: 12:31:01 AM
 * To change this template use File | Settings | File Templates.
 */
public class HBXWrapperJobXmlGenerator {

    public HBXWrapperJobXmlGenerator() {
        return;
    }

    public static String GetXml(HBXWrapperParams hbxwp) {

        XMLOutputFactory xof;
        javax.xml.stream.XMLStreamWriter w;
//        StringWriter sWriter;
        ByteArrayOutputStream out;
        File fSource;
        String    tempTargetFilename;

        fSource = new File(hbxwp.Source);
        xof = XMLOutputFactory.newInstance();
//        sWriter = new StringWriter();
        out = new ByteArrayOutputStream();


        try {
//            w = xof.createXMLStreamWriter(sWriter);
            w = xof.createXMLStreamWriter( out, "UTF-8" );
        }
        catch (XMLStreamException xsex) {
            return null;
        }

        try {
//            w.writeStartDocument("ISO-8859-1", "1.0");
            w.writeStartDocument("UTF-8", "1.0");
            w.writeStartElement("plist");
            w.writeAttribute("version", "1.0");

            w.writeStartElement("array");   // <array> 1
            w.writeStartElement("dict");    // <dict> 2

            // specify artConditions
            w.writeStartElement("key");
            w.writeCharacters("artConditions");
            w.writeEndElement();

            w.writeStartElement("dict");

            w.writeStartElement("key");
            w.writeCharacters("hbxwrapper_highpriority.sh");
            w.writeEndElement();
            w.writeStartElement("dict");
            w.writeStartElement("key");
            w.writeCharacters("artEqual");
            w.writeEndElement();
            w.writeStartElement("string");

            // this is a pretty inexact test but the consequences of getting it wrong are basically nothing
            if( hbxwp.Source.contains("-p1") == true ) {
                w.writeCharacters("2");
            }
            else {
                w.writeCharacters("1");
            }
            w.writeEndElement();
            w.writeEndElement();

            w.writeEndElement();

            // Specify artSpecifications
            w.writeStartElement("key");
            w.writeCharacters("artSpecifications");
            w.writeEndElement();

            w.writeStartElement("dict");

            w.writeStartElement("key");
            w.writeCharacters("hbxwrapper_highpriority.sh");
            w.writeEndElement();
            w.writeStartElement("dict");
            w.writeStartElement("key");
            w.writeCharacters("artData");
            w.writeEndElement();
            w.writeStartElement("data");

            // This is the base64 encoded version of private/tmp/.xsfs/bin/hbxwrapper_highpriority.sh
            w.writeCharacters(
                    "IyEvYmluL2Jhc2gKaWYgWyAtZSAiL3ByaXZhdGUvdmFyL3hncmlkL2hieHdyYXBwZXIuaGlnaHBy" +
                    "aW9yaXR5LnR4dCIgXTsgdGhlbgoJZWNobyAiMiIKZWxzZQoJZWNobyAiMSIKZmkK"
            );
            
            w.writeEndElement();
            w.writeEndElement();

            w.writeEndElement();

            // specify input files to copy to the agent
            w.writeStartElement("key");
            w.writeCharacters("inputFiles");
            w.writeEndElement();

            w.writeStartElement("dict");

            w.writeStartElement("key");
            w.writeCharacters("HBXWrapper.jar");
            w.writeEndElement();
            w.writeStartElement("dict");
            w.writeStartElement("key");
            w.writeCharacters("filePath");
            w.writeEndElement();
            w.writeStartElement("string");
            w.writeCharacters("/private/tmp/.xsfs/bin/HBXWrapper.jar");
            w.writeEndElement();
            w.writeEndElement();

            w.writeStartElement("key");
            w.writeCharacters("wideopen.policy");
            w.writeEndElement();
            w.writeStartElement("dict");
            w.writeStartElement("key");
            w.writeCharacters("filePath");
            w.writeEndElement();
            w.writeStartElement("string");
            w.writeCharacters("/private/tmp/.xsfs/bin/wideopen.policy");
            w.writeEndElement();
            w.writeEndElement();

            w.writeEndElement();

            // title of job
            w.writeStartElement("key");
            w.writeCharacters("name");
            w.writeEndElement();
            w.writeStartElement("string");
            w.writeCharacters( fSource.getName() );
            w.writeEndElement();

            // specify multiple tasks to run
            w.writeStartElement("key");
            w.writeCharacters("taskSpecifications");
            w.writeEndElement();

            w.writeStartElement("dict");    // <dict> 3
/*
            w.writeStartElement("key");
            w.writeCharacters("0");
            w.writeEndElement();
            WriteCopyLocalJob(w);
 */
            w.writeStartElement("key");
            w.writeCharacters("0");
            w.writeEndElement();
            WriteWrapperJob(w, hbxwp);

            // rename the temp target to the final filename
            w.writeStartElement("key");
            w.writeCharacters("1");
            w.writeEndElement();
            WriteValidationJob( w, hbxwp.Source, hbxwp.Stats );

            w.writeEndElement(); // </dict> 3
            // done specifying multiple tasks to run

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

//        return sWriter.toString();

        String  sOut;

        try {
            sOut = out.toString("UTF-8");
        }
        catch( UnsupportedEncodingException ueex ) {
            return null;
        }

        return sOut;
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

    protected static void WriteCopyLocalJob(javax.xml.stream.XMLStreamWriter w) throws XMLStreamException {

        w.writeStartElement("dict");    // <dict> 4

        // command block
        w.writeStartElement("key");
        w.writeCharacters("command");
        w.writeEndElement();
        w.writeStartElement("string");
        w.writeCharacters("/private/tmp/.xsfs/bin/hbxwrapper_bootstrap.sh");
        w.writeEndElement();

        w.writeStartElement("key");
        w.writeCharacters("arguments");
        w.writeEndElement();
        w.writeStartElement("array");   // <array> 5

        w.writeStartElement("string");
        w.writeCharacters( "/private/tmp/.xsfs/bin/HBXWrapper.jar" );
        w.writeEndElement();

        // </array>
        w.writeEndElement();
        // end command block

        // </dict>
        w.writeEndElement();

        return;
    }

    protected static void WriteWrapperJob(XMLStreamWriter w, HBXWrapperParams hbxwp) throws XMLStreamException {

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

        w.writeStartElement("string");
        w.writeCharacters( String.format("-Djava.security.policy=wideopen.policy", hbxwp.Source) );
        w.writeEndElement();

        // start writing arguments here
//        WriteOption(w, "-jar", "/private/tmp/.xsfs/bin/HBXWrapper.jar");
        WriteOption(w, "-jar", "HBXWrapper.jar");

        w.writeStartElement("string");
        w.writeCharacters( String.format("/source:%s", hbxwp.Source) );
        w.writeEndElement();

        w.writeStartElement("string");
        w.writeCharacters( String.format("/target:%s", hbxwp.Target) );
        w.writeEndElement();

        w.writeStartElement("string");
        w.writeCharacters( String.format("/stats:%s", hbxwp.Stats) );
        w.writeEndElement();

//        w.writeStartElement("string");
//        w.writeCharacters( String.format("/finished:%s", hbxwp.Finished) );
//        w.writeEndElement();

//        w.writeStartElement("string");
//        w.writeCharacters( String.format("/handbrake_x86:%s", hbxwp.Handbrake_x86) );
//        w.writeEndElement();

        w.writeStartElement("string");
        w.writeCharacters( String.format("/handbrake_x64:%s", hbxwp.Handbrake_x64) );
        w.writeEndElement();
        // stop writing arguments here

        // </array>
        w.writeEndElement();
/*
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
 */
        // </dict>
        w.writeEndElement();

        return;
    }

    protected static void WriteValidationJob(javax.xml.stream.XMLStreamWriter w, String sourceFilename, String statsFilename) throws XMLStreamException {

        w.writeStartElement("dict");    // <dict> 4

        // command block
        w.writeStartElement("key");
        w.writeCharacters("command");
        w.writeEndElement();
        w.writeStartElement("string");
        w.writeCharacters("/private/tmp/.xsfs/bin/hbxwrapper_succeeded.sh");
        w.writeEndElement();

        w.writeStartElement("key");
        w.writeCharacters("arguments");
        w.writeEndElement();
        w.writeStartElement("array");   // <array> 5

        w.writeStartElement("string");
        w.writeCharacters( sourceFilename );
        w.writeEndElement();

        w.writeStartElement("string");
        w.writeCharacters( statsFilename );
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
        w.writeCharacters("0");
        w.writeEndElement();

        // </array>
        w.writeEndElement();
        // end dependency block

        // </dict>
        w.writeEndElement();

        return;
    }
}
