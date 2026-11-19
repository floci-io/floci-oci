package io.floci.oci.services.objectstorage;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;

/** The small amount of S3 XML the compatibility facade writes and reads. */
final class S3Xml {

    static final String NS = "http://s3.amazonaws.com/doc/2006-03-01/";
    static final String CONTENT_TYPE = "application/xml";

    private final StringBuilder out = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>");

    static S3Xml document(String root) {
        S3Xml xml = new S3Xml();
        xml.out.append('<').append(root).append(" xmlns=\"").append(NS).append("\">");
        return xml;
    }

    S3Xml open(String name) {
        out.append('<').append(name).append('>');
        return this;
    }

    S3Xml close(String name) {
        out.append("</").append(name).append('>');
        return this;
    }

    S3Xml element(String name, Object value) {
        if (value == null) {
            return this;
        }
        out.append('<').append(name).append('>').append(escape(String.valueOf(value))).append("</").append(name)
                .append('>');
        return this;
    }

    String end(String root) {
        return out.append("</").append(root).append('>').toString();
    }

    static String error(String code, String message, String resource, String requestId) {
        return new S3Xml().open("Error")
                .element("Code", code)
                .element("Message", message)
                .element("Resource", resource)
                .element("RequestId", requestId)
                .close("Error").out.toString();
    }

    static String escape(String value) {
        StringBuilder escaped = new StringBuilder(value.length());
        for (char c : value.toCharArray()) {
            switch (c) {
                case '&' -> escaped.append("&amp;");
                case '<' -> escaped.append("&lt;");
                case '>' -> escaped.append("&gt;");
                case '"' -> escaped.append("&quot;");
                case '\'' -> escaped.append("&apos;");
                default -> escaped.append(c);
            }
        }
        return escaped.toString();
    }

    /** Parses a request body with DOCTYPEs and external entities refused. */
    static Document parse(byte[] body) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        factory.setNamespaceAware(false);
        DocumentBuilder builder = factory.newDocumentBuilder();
        return builder.parse(new ByteArrayInputStream(body));
    }

    /** The direct children of {@code parent} named {@code name}. */
    static List<Element> children(Element parent, String name) {
        List<Element> result = new ArrayList<>();
        NodeList nodes = parent.getChildNodes();
        for (int i = 0; i < nodes.getLength(); i++) {
            Node node = nodes.item(i);
            if (node instanceof Element e && name.equals(localName(e))) {
                result.add(e);
            }
        }
        return result;
    }

    /** The text of the first {@code name} child, exactly as sent: object keys and tag values may hold spaces. */
    static String childText(Element parent, String name) {
        List<Element> found = children(parent, name);
        return found.isEmpty() ? null : found.get(0).getTextContent();
    }

    private static String localName(Element e) {
        String name = e.getTagName();
        int colon = name.indexOf(':');
        return colon < 0 ? name : name.substring(colon + 1);
    }
}
