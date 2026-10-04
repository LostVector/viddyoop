package com.rkuo.xml;

import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.w3c.dom.Document;

import javax.xml.stream.XMLOutputFactory;
import javax.xml.stream.XMLStreamWriter;
import java.io.StringWriter;

@RunWith(JUnit4.class)
public class XMLHelperTest {

    private static final String VALID_XML =
            "<config><name>viddyoop</name><cluster size=\"4\"/></config>";

    @Test
    public void testToDocumentValid() {
        Document doc = XMLHelper.ToDocument(VALID_XML);
        Assert.assertNotNull(doc);
        Assert.assertEquals("config", doc.getDocumentElement().getTagName());
    }

    @Test
    public void testToDocumentInvalid() {
        Assert.assertNull(XMLHelper.ToDocument("<config><unclosed></config>"));
        Assert.assertNull(XMLHelper.ToDocument("this is not xml at all"));
        Assert.assertNull(XMLHelper.ToDocument(""));
    }

    @Test
    public void testGetElementValue() {
        Document doc = XMLHelper.ToDocument(VALID_XML);
        Assert.assertNotNull(doc);
        Assert.assertEquals("viddyoop", XMLHelper.GetElementValue(doc, "name"));
    }

    @Test
    public void testGetElementValueMissingElementReturnsNull() {
        Document doc = XMLHelper.ToDocument(VALID_XML);
        Assert.assertNotNull(doc);
        Assert.assertNull(XMLHelper.GetElementValue(doc, "nonexistent"));
    }

    @Test
    public void testElementExists() {
        Document doc = XMLHelper.ToDocument(VALID_XML);
        Assert.assertNotNull(doc);
        Assert.assertTrue(XMLHelper.ElementExists(doc, "name"));
        Assert.assertTrue(XMLHelper.ElementExists(doc, "cluster"));
        Assert.assertFalse(XMLHelper.ElementExists(doc, "nonexistent"));
    }

    @Test
    public void testToStringRoundTrip() {
        Document doc = XMLHelper.ToDocument(VALID_XML);
        Assert.assertNotNull(doc);

        String out = XMLHelper.ToString(doc);
        Assert.assertNotNull(out);
        Assert.assertTrue(out.contains("<name>viddyoop</name>"));
        Assert.assertTrue(out.contains("<cluster"));

        // the serialized form should itself be parseable
        Assert.assertNotNull(XMLHelper.ToDocument(out));
    }

    @Test
    public void testCleanXmlRepairsMalformedHtml() {
        String malformed = "<html><body><p>hello <b>world</p></body></html>";

        String cleaned = XMLHelper.CleanXml(malformed);
        Assert.assertNotNull(cleaned);
        Assert.assertTrue(cleaned.contains("hello"));
        Assert.assertTrue(cleaned.contains("world"));
        // the unclosed <b> should have been closed by tidy
        Assert.assertTrue(cleaned.contains("</b>"));
    }

    @Test
    public void testCleanXmlHandlesPlainText() {
        String cleaned = XMLHelper.CleanXml("just some plain text with no markup");
        Assert.assertNotNull(cleaned);
        Assert.assertTrue(cleaned.contains("just some plain text with no markup"));
    }

    @Test
    public void testWriteElement() throws Exception {
        StringWriter sw = new StringWriter();
        XMLStreamWriter w = XMLOutputFactory.newInstance().createXMLStreamWriter(sw);
        try {
            w.writeStartDocument();
            w.writeStartElement("root");
            XMLHelper.WriteElement(w, "key", "value");
            w.writeEndElement();
            w.writeEndDocument();
        }
        finally {
            w.close();
        }

        String out = sw.toString();
        Assert.assertTrue(out.contains("<key>value</key>"));
        Assert.assertNotNull(XMLHelper.ToDocument(out));
        Assert.assertEquals("value", XMLHelper.GetElementValue(XMLHelper.ToDocument(out), "key"));
    }
}
