package com.rkuo.xml;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.w3c.tidy.Tidy;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamWriter;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.*;

/**
 * Created by IntelliJ IDEA.
 * User: rkuo
 * Date: Sep 10, 2010
 * Time: 12:28:29 AM
 * To change this template use File | Settings | File Templates.
 */
public class XMLHelper {

    public static String CleanXml(String input) {

        Document doc;

        try {
            Tidy tidy = new Tidy();
            tidy.setQuiet(true);
            tidy.setShowWarnings(false);
            doc = tidy.parseDOM(new ByteArrayInputStream(input.getBytes("UTF-8")), null);
        }
        catch( IOException ioex ) {
            return null;
        }

        return XMLHelper.ToString(doc);
    }

    public static DocumentBuilder GetDocumentBuilder() {
        DocumentBuilderFactory dbf;
        DocumentBuilder db;

        dbf = DocumentBuilderFactory.newInstance();

        dbf.setValidating(false);

        try {
            dbf.setFeature("http://xml.org/sax/features/namespaces", false);
            dbf.setFeature("http://xml.org/sax/features/validation", false);
            dbf.setFeature("http://apache.org/xml/features/nonvalidating/load-dtd-grammar", false);
            dbf.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);

            db = dbf.newDocumentBuilder();
        }
        catch( ParserConfigurationException pex ) {
            return null;
        }

        return db;
    }

   public static String ToString(Document doc) {
        try {
            DOMSource domSource = new DOMSource(doc);
            StringWriter writer = new StringWriter();
            StreamResult result = new StreamResult(writer);
            TransformerFactory tf = TransformerFactory.newInstance();
            Transformer transformer = tf.newTransformer();
            transformer.transform(domSource, result);
            return writer.toString();
        }
        catch( TransformerException ex ) {
            ex.printStackTrace();
            return null;
        }
    }
    
    public static void WriteElement( XMLStreamWriter w, String name, String value ) throws XMLStreamException {
        w.writeStartElement( name );
        w.writeCharacters( value );
        w.writeEndElement();
        return;
    }

    public static String GetElementValue(Document doc, String sXmlName) {
        NodeList nodes;
        Element e;
        String sValue;

        sValue = null;

        nodes = doc.getElementsByTagName(sXmlName);
        if (nodes.getLength() > 0) {
            e = (Element) nodes.item(0);
            sValue = e.getFirstChild().getNodeValue();
        }

        return sValue;
    }

    public static boolean ElementExists(Document doc, String sElementName) {
        NodeList nodes;

        nodes = doc.getElementsByTagName(sElementName);
        if (nodes.getLength() == 0) {
            return false;
        }

        return true;
    }

    public static Document ToDocument(String sXml) {

        Document doc;
        DocumentBuilder db;

        db = XMLHelper.GetDocumentBuilder();
        if( db == null ) {
            return null;
        }

        try {
            doc = db.parse(new InputSource(new StringReader(sXml)));
        }
        catch( SAXException saxex ) {
            return null;
        }
        catch( IOException ioex ) {
            return null;
        }

        return doc;
    }

    public static Document ToDocument(InputStream is) {

        Document doc;
        DocumentBuilder db;

        db = XMLHelper.GetDocumentBuilder();
        if( db == null ) {
            return null;
        }

        try {
            doc = db.parse(is);
        }
        catch (SAXException saxex) {
            return null;
        }
        catch (IOException ioex) {
            return null;
        }

        return doc;
    }
}
