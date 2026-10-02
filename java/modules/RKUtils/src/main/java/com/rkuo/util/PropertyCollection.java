package com.rkuo.util;

import javax.xml.stream.XMLOutputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamWriter;
import java.io.StringWriter;
import java.util.Map;
import java.util.TreeMap;

/**
 * Created by IntelliJ IDEA.
 * User: rkuo
 * Date: Sep 4, 2010
 * Time: 6:10:42 PM
 * To change this template use File | Settings | File Templates.
 */
public class PropertyCollection {
    Map<String,String> _properties;
    
    public PropertyCollection() {
        _properties = new TreeMap<String,String>();
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

            for( Map.Entry<String,String> entry : _properties.entrySet() ) {
                WriteElement( w, entry.getKey(), entry.getValue() );
            }

            w.writeEndElement();
            w.writeEndDocument();
        }
        catch( XMLStreamException xsex ) {
            return null;
        }

        return sw.toString();
    }

    public void WriteElement( XMLStreamWriter w, String name, String value ) throws XMLStreamException {
        w.writeStartElement( name );
        w.writeCharacters( value );
        w.writeEndElement();
        return;
    }
}
