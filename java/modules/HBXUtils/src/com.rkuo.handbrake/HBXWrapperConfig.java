package com.rkuo.handbrake;

import com.rkuo.xml.XMLHelper;
import org.w3c.dom.Document;

import javax.xml.stream.XMLOutputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamWriter;
import java.io.StringWriter;

/**
 * Created by IntelliJ IDEA.
 * User: rkuo
 * Date: Sep 4, 2010
 * Time: 4:38:29 PM
 * To change this template use File | Settings | File Templates.
 */
public class HBXWrapperConfig {
    public Boolean HandleHighPriorityEncodes;
    public String  ServiceUrl; // e.g. rmi://your.domain.com/HBXWrapperService
    public String  TempDirectory;
    public String  Handbrake;
    public String  SourceDirectory;
    public String  TargetDirectory;
    public String  StatsDirectory;

    public HBXWrapperConfig() {

        HandleHighPriorityEncodes = false;
        ServiceUrl = "";
        TempDirectory = "";
        Handbrake = "";
        SourceDirectory = "";
        TargetDirectory = "";
        StatsDirectory = "";
        return;
    }

    public String Serialize() {
        XMLOutputFactory xof;
        XMLStreamWriter w;
        StringWriter sw;

        xof = XMLOutputFactory.newInstance();
        sw = new StringWriter();

        try {
            w = xof.createXMLStreamWriter( sw );
        }
        catch( XMLStreamException xsex ) {
            return null;
        }

        try {
            w.writeStartDocument("UTF-8", "1.0");
            w.writeStartElement("HBXWrapperConfig");

            WriteElement( w, "HandleHighPriorityEncodes", HandleHighPriorityEncodes.toString() );
            WriteElement( w, "ServiceUrl", ServiceUrl);
            WriteElement( w, "TempDirectory", TempDirectory );
            WriteElement( w, "Handbrake", Handbrake );
            WriteElement( w, "SourceDirectory", SourceDirectory );
            WriteElement( w, "TargetDirectory", TargetDirectory );
            WriteElement( w, "StatsDirectory", StatsDirectory );

            w.writeEndElement();
            w.writeEndDocument();
        }
        catch( XMLStreamException xsex ) {
            return null;
        }

        return sw.toString();
    }

    public boolean Deserialize( String xml ) {

        Document doc;
        String      value;

        doc = XMLHelper.ToDocument( xml );

        value = XMLHelper.GetElementValue( doc, "HandleHighPriorityEncodes" );
        if( value != null ) {
            try {
                HandleHighPriorityEncodes = Boolean.parseBoolean(value);
            }
            catch( Exception ex ) {
                // do nothing
            }
        }

        value = XMLHelper.GetElementValue( doc, "ServiceUrl" );
        if( value == null ) {
            return false;
        }

        ServiceUrl = value;

        value = XMLHelper.GetElementValue( doc, "TempDirectory" );
        if( value == null ) {
            return false;
        }

        TempDirectory = value;

        value = XMLHelper.GetElementValue( doc, "Handbrake" );
        if( value == null ) {
            return false;
        }

        Handbrake = value;

        value = XMLHelper.GetElementValue( doc, "SourceDirectory" );
        if( value == null ) {
            return false;
        }

        SourceDirectory = value;

        value = XMLHelper.GetElementValue( doc, "TargetDirectory" );
        if( value == null ) {
            return false;
        }

        TargetDirectory = value;

        value = XMLHelper.GetElementValue( doc, "StatsDirectory" );
        if( value == null ) {
            return false;
        }

        StatsDirectory = value;

        return true;
    }

    public void WriteElement( XMLStreamWriter w, String name, String value ) throws XMLStreamException {
        w.writeStartElement( name );
        w.writeCharacters( value );
        w.writeEndElement();
        return;
    }
}
