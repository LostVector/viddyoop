package com.rkuo.handbrake;

import com.rkuo.xml.XMLHelper;
import org.w3c.dom.Document;

import javax.xml.stream.XMLOutputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamWriter;
import java.io.StringWriter;
import java.text.DateFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * Created by IntelliJ IDEA.
 * User: rkuo
 * Date: Sep 4, 2010
 * Time: 4:38:29 PM
 * To change this template use File | Settings | File Templates.
 */
public class HBXOriginalStats {
    public String   Name;
    public Long     Length;
    public Integer  XRes;
    public Integer  YRes;
    public Integer  Normalized;
    public Long     Duration;
    public Date     DateAdded;

    public HBXOriginalStats() {

        Name = "";
        Length = -1L;
        XRes = -1;
        YRes = -1;
        Normalized = -1;
        Duration = -1L;
        DateAdded = new Date(0);
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
            w.writeStartElement("HBOriginalStats");

            WriteElement( w, "Name", Name );
            WriteElement( w, "Length", Length.toString() );
            WriteElement( w, "OriginalXRes", XRes.toString() );
            WriteElement( w, "OriginalYRes", YRes.toString() );
            WriteElement( w, "Normalized", Normalized.toString() );
            WriteElement( w, "Duration", Duration.toString() );

            SimpleDateFormat sdf = (SimpleDateFormat) DateFormat.getDateInstance();
            sdf.applyPattern("EEE MMM d HH:mm:ss z yyyy");

            WriteElement( w, "DateAdded", sdf.format(DateAdded) );

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

        value = XMLHelper.GetElementValue( doc, "Name" );
        if( value == null ) {
            return false;
        }

        Name = value;

        value = XMLHelper.GetElementValue( doc, "Length" );
        if( value == null ) {
            return false;
        }

        Length = Long.parseLong(value);

        value = XMLHelper.GetElementValue( doc, "OriginalXRes" );
        if( value == null ) {
            value = XMLHelper.GetElementValue( doc, "EncodedXRes" );
            if( value == null ) {
                return false;
            }
        }

        XRes = Integer.parseInt(value);

        value = XMLHelper.GetElementValue( doc, "OriginalYRes" );
        if( value == null ) {
            value = XMLHelper.GetElementValue( doc, "EncodedYRes" );
            if( value == null ) {
                return false;
            }
        }

        YRes = Integer.parseInt(value);

        value = XMLHelper.GetElementValue( doc, "Normalized" );
        if( value == null ) {
            return false;
        }

        Normalized = Integer.parseInt(value);

        value = XMLHelper.GetElementValue( doc, "Duration" );
        if( value == null ) {
            return false;
        }

        Duration = Long.parseLong(value);

        SimpleDateFormat sdf = (SimpleDateFormat) DateFormat.getDateInstance();
        sdf.applyPattern("EEE MMM d HH:mm:ss z yyyy");

        value = XMLHelper.GetElementValue(doc, "DateAdded");
        if (value == null) {
            return false;
        }

        try {
            DateAdded = sdf.parse(value);
        }
        catch (ParseException pex) {
            return false;
        }

        return true;
    }

    public void WriteElement( XMLStreamWriter w, String name, String value ) throws XMLStreamException {
        w.writeStartElement( name );
        w.writeCharacters( value );
        w.writeEndElement();
        return;
    }
}
