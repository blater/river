package io.riverdb.buildpolicy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import org.w3c.dom.Element;
import org.xml.sax.SAXException;

/** Validates Gradle dependency-verification XML against resolved JAR checksums. */
final class ProvenanceGradleMetadataVerifier {
  private static final String NAMESPACE = "https://schema.gradle.org/dependency-verification";

  private ProvenanceGradleMetadataVerifier() {
  }

  static void verify(Path metadata, Map<String, String> resolved) throws IOException {
    if (!Files.isRegularFile(metadata) || Files.isSymbolicLink(metadata)) {
      throw new IllegalArgumentException(
          "Gradle dependency verification metadata is absent or not a real file");
    }
    DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
    factory.setNamespaceAware(true);
    factory.setXIncludeAware(false);
    factory.setExpandEntityReferences(false);
    try {
      factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
      factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
      factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
      factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
      factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
      Element root = factory.newDocumentBuilder().parse(metadata.toFile()).getDocumentElement();
      if (!"verification-metadata".equals(root.getLocalName())
          || !NAMESPACE.equals(root.getNamespaceURI())) {
        throw new IllegalArgumentException(
            "Gradle dependency verification metadata has an unexpected root");
      }
      requireSingleText(root, "verify-metadata", "true");
      requireAbsent(root, "trusted-artifacts");
      requireAbsent(root, "trusted-keys");
      requireAbsent(root, "ignored-keys");
      Map<String, String> verifiedJars = verifiedJars(root);
      if (!verifiedJars.equals(resolved)) {
        throw new IllegalArgumentException(
            "Gradle verification JAR set differs from resolved dependencies: expected "
                + resolved + ", got " + verifiedJars);
      }
    } catch (ParserConfigurationException | SAXException failure) {
      throw new IllegalArgumentException(
          "Gradle dependency verification metadata is malformed", failure);
    }
  }

  private static Map<String, String> verifiedJars(Element root) {
    Map<String, String> verifiedJars = new LinkedHashMap<>();
    var components = root.getElementsByTagNameNS(NAMESPACE, "component");
    for (int componentIndex = 0; componentIndex < components.getLength(); componentIndex++) {
      Element component = (Element) components.item(componentIndex);
      String coordinate = requiredAttribute(component, "group") + ":"
          + requiredAttribute(component, "name") + ":"
          + requiredAttribute(component, "version");
      var artifacts = component.getElementsByTagNameNS(NAMESPACE, "artifact");
      for (int artifactIndex = 0; artifactIndex < artifacts.getLength(); artifactIndex++) {
        Element artifact = (Element) artifacts.item(artifactIndex);
        String artifactName = requiredAttribute(artifact, "name");
        var checksums = artifact.getElementsByTagNameNS(NAMESPACE, "sha256");
        if (checksums.getLength() != 1) {
          throw new IllegalArgumentException(
              "Gradle verification artifact must have one SHA-256: " + artifactName);
        }
        String checksum = requiredAttribute((Element) checksums.item(0), "value");
        if (!ProvenanceLedgerParser.sha256Pattern().matcher(checksum).matches()) {
          throw new IllegalArgumentException(
              "Gradle verification SHA-256 is malformed: " + artifactName);
        }
        if (artifactName.endsWith(".jar")
            && verifiedJars.putIfAbsent(coordinate, checksum) != null) {
          throw new IllegalArgumentException(
              "Gradle verification has multiple JARs for " + coordinate);
        }
      }
    }
    return verifiedJars;
  }

  private static void requireSingleText(Element root, String name, String expected) {
    var nodes = root.getElementsByTagNameNS(NAMESPACE, name);
    if (nodes.getLength() != 1 || !expected.equals(nodes.item(0).getTextContent().strip())) {
      throw new IllegalArgumentException(
          "Gradle verification metadata requires " + name + "=" + expected);
    }
  }

  private static void requireAbsent(Element root, String name) {
    if (root.getElementsByTagNameNS(NAMESPACE, name).getLength() != 0) {
      throw new IllegalArgumentException(
          "Gradle verification metadata must not contain " + name);
    }
  }

  private static String requiredAttribute(Element element, String name) {
    String value = element.getAttribute(name);
    if (value.isBlank()) {
      throw new IllegalArgumentException(
          "Gradle verification metadata has a blank " + name + " attribute");
    }
    return value;
  }
}
