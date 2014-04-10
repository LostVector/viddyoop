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
 * Time: 4:38:38 PM
 * To change this template use File | Settings | File Templates.
 */
public class HBXEncodingStats {

    public String Name;

    public Long OriginalLength;
    public Integer OriginalXRes;
    public Integer OriginalYRes;

    public Long EncodedLength;
    public Integer EncodedXRes;
    public Integer EncodedYRes;
    public Long Duration;

    public String MachineName;

    public Date EncodingStarted;
    public Date EncodingFinished;
    public Date DateStarted;
    public Date DateFinished;

    public HBXEncodingStats() {

        Name = "";

        OriginalLength = -1L;
        OriginalXRes = -1;
        OriginalYRes = -1;

        EncodedLength = -1L;
        EncodedXRes = -1;
        EncodedYRes = -1;
        Duration = -1L;

        MachineName = "";

        EncodingStarted = new Date(0);
        EncodingFinished = new Date(0);
        DateStarted = new Date(0);
        DateFinished = new Date(0);

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
            w.writeStartElement("HBEncodingStats");

            XMLHelper.WriteElement( w, "Name", Name );

            XMLHelper.WriteElement( w, "OriginalLength", OriginalLength.toString() );
            XMLHelper.WriteElement( w, "OriginalXRes", OriginalXRes.toString() );
            XMLHelper.WriteElement( w, "OriginalYRes", OriginalYRes.toString() );

            XMLHelper.WriteElement( w, "EncodedLength", EncodedLength.toString() );
            XMLHelper.WriteElement( w, "EncodedXRes", EncodedXRes.toString() );
            XMLHelper.WriteElement( w, "EncodedYRes", EncodedYRes.toString() );
            XMLHelper.WriteElement( w, "Duration", Duration.toString() );

            XMLHelper.WriteElement( w, "MachineName", MachineName.toString() );

            SimpleDateFormat sdf = (SimpleDateFormat) DateFormat.getDateInstance();
            sdf.applyPattern("EEE MMM d HH:mm:ss z yyyy");

            XMLHelper.WriteElement( w, "EncodingStarted", sdf.format(EncodingStarted) );
            XMLHelper.WriteElement( w, "EncodingFinished", sdf.format(EncodingFinished) );
            XMLHelper.WriteElement( w, "DateStarted", sdf.format(DateStarted) );
            XMLHelper.WriteElement( w, "DateFinished", sdf.format(DateFinished) );

            w.writeEndElement();
            w.writeEndDocument();
        }
        catch( XMLStreamException xsex ) {
            return null;
        }

        return sw.toString();
    }

    public boolean Deserialize( String xml ) {

        Document    doc;
        String      value;

        doc = XMLHelper.ToDocument( xml );

        value = XMLHelper.GetElementValue( doc, "Name" );
        if( value == null ) {
            return false;
        }

        Name = value;

        value = XMLHelper.GetElementValue( doc, "OriginalLength" );
        if( value == null ) {
            return false;
        }

        OriginalLength = Long.parseLong(value);

        value = XMLHelper.GetElementValue( doc, "OriginalXRes" );
        if( value == null ) {
            return false;
        }

        OriginalXRes = Integer.parseInt(value);

        value = XMLHelper.GetElementValue( doc, "OriginalYRes" );
        if( value == null ) {
            return false;
        }

        OriginalYRes = Integer.parseInt(value);

        value = XMLHelper.GetElementValue( doc, "EncodedLength" );
        if( value == null ) {
            return false;
        }

        EncodedLength = Long.parseLong(value);

        value = XMLHelper.GetElementValue( doc, "EncodedXRes" );
        if( value == null ) {
            return false;
        }

        EncodedXRes = Integer.parseInt(value);

        value = XMLHelper.GetElementValue( doc, "EncodedYRes" );
        if( value == null ) {
            return false;
        }

        EncodedYRes = Integer.parseInt(value);

        value = XMLHelper.GetElementValue( doc, "Duration" );
        if( value == null ) {
            return false;
        }

        Duration = Long.parseLong(value);

        value = XMLHelper.GetElementValue( doc, "MachineName" );
        if( value == null ) {
            return false;
        }

        MachineName = value;

        value = XMLHelper.GetElementValue( doc, "EncodingStarted" );
        if( value == null ) {
            return false;
        }

        SimpleDateFormat sdf = (SimpleDateFormat) DateFormat.getDateInstance();
        sdf.applyPattern("EEE MMM d HH:mm:ss z yyyy");

        try {
            EncodingStarted = sdf.parse( value );
        }
        catch( ParseException pex ) {
            return false;
        }

        value = XMLHelper.GetElementValue( doc, "EncodingFinished" );
        if( value == null ) {
            return false;
        }

        try {
            EncodingFinished = sdf.parse(value);
        }
        catch (ParseException pex) {
            return false;
        }

        value = XMLHelper.GetElementValue(doc, "DateStarted");
        if (value == null) {
            return false;
        }

        try {
            DateStarted = sdf.parse(value);
        }
        catch (ParseException pex) {
            return false;
        }

        value = XMLHelper.GetElementValue(doc, "DateFinished");
        if (value == null) {
            return false;
        }

        try {
            DateFinished = sdf.parse(value);
        }
        catch (ParseException pex) {
            return false;
        }

        return true;
    }
}
