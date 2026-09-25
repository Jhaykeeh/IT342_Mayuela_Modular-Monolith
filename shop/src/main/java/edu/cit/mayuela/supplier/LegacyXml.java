package edu.cit.mayuela.supplier;

import java.io.StringReader;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

/**
 * Hand-written XML translation for LegacySupply. Produces the request
 * documents and parses the response documents the old partner service
 * understands. All XML knowledge stays inside this adapter.
 */
final class LegacyXml {

    private LegacyXml() {
    }

    /** <AuthRequest> body for POST /auth/token */
    static String authRequest(String clientId, String apiKey) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<AuthRequest>"
                + "<ClientId>" + xml(clientId) + "</ClientId>"
                + "<ApiKey>" + xml(apiKey) + "</ApiKey>"
                + "</AuthRequest>";
    }

    /** <PurchaseOrder> body for POST /purchase-orders */
    static String purchaseOrder(String supplierSku, int qty, String buyerRef) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<PurchaseOrder>"
                + "<SupplierSku>" + xml(supplierSku) + "</SupplierSku>"
                + "<Qty>" + qty + "</Qty>"
                + "<BuyerRef>" + xml(buyerRef) + "</BuyerRef>"
                + "</PurchaseOrder>";
    }

    /** Parses the SessionToken out of an <AuthResponse>. */
    static String sessionToken(String xml) {
        return text(parse(xml), "SessionToken");
    }

    /** Parses a <PurchaseOrderAck>. */
    static Ack parseAck(String xml) {
        Document doc = parse(xml);
        return new Ack(text(doc, "PoNumber"),
                parseInt(text(doc, "StatusCode")),
                text(doc, "SupplierSku"),
                parseInt(text(doc, "Qty")),
                text(doc, "Uom"),
                text(doc, "BuyerRef"));
    }

    /** Parses a <PurchaseOrderStatus>. */
    static OrderStatus parseStatus(String xml) {
        Document doc = parse(xml);
        return new OrderStatus(text(doc, "PoNumber"),
                parseInt(text(doc, "StatusCode")),
                text(doc, "SupplierSku"),
                parseInt(text(doc, "Qty")),
                text(doc, "Uom"),
                text(doc, "BuyerRef"));
    }

    /** Parses a <PurchaseOrderList> into the orders it contains. */
    static List<OrderStatus> parseOrderList(String xml) {
        Document doc = parse(xml);
        NodeList nodes = doc.getElementsByTagName("PurchaseOrder");
        List<OrderStatus> out = new ArrayList<>();
        for (int i = 0; i < nodes.getLength(); i++) {
            Element e = (Element) nodes.item(i);
            out.add(new OrderStatus(text(e, "PoNumber"),
                    parseInt(text(e, "StatusCode")),
                    text(e, "SupplierSku"),
                    parseInt(text(e, "Qty")),
                    text(e, "Uom"),
                    text(e, "BuyerRef")));
        }
        return out;
    }

    /** Extracts the <Code> out of an <LSError> document, or "" if absent. */
    static String errorCode(String xml) {
        try {
            return text(parse(xml), "Code");
        } catch (Exception e) {
            return "";
        }
    }

    /** Extracts the <Message> out of an <LSError> document, or "" if absent. */
    static String errorMessage(String xml) {
        try {
            return text(parse(xml), "Message");
        } catch (Exception e) {
            return "";
        }
    }

    private static Document parse(String xml) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setExpandEntityReferences(false);
            return factory.newDocumentBuilder().parse(new InputSource(new StringReader(xml)));
        } catch (Exception e) {
            throw new IllegalArgumentException("Cannot parse LegacySupply XML", e);
        }
    }

    private static String text(Element parent, String tag) {
        NodeList nodes = parent.getElementsByTagName(tag);
        if (nodes.getLength() == 0) {
            return "";
        }
        return nodes.item(0).getTextContent() == null ? "" : nodes.item(0).getTextContent().trim();
    }

    private static String text(Document doc, String tag) {
        return text(doc.getDocumentElement(), tag);
    }

    private static int parseInt(String s) {
        if (s == null || s.isBlank()) {
            return 0;
        }
        return (int) Double.parseDouble(s.trim());
    }

    private static String xml(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    record Ack(String poNumber, int statusCode, String supplierSku, int qty, String uom, String buyerRef) {
    }

    record OrderStatus(String poNumber, int statusCode, String supplierSku, int qty, String uom, String buyerRef) {
    }
}