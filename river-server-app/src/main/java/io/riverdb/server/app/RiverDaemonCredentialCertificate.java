package io.riverdb.server.app;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.id.DatabaseIncarnation;
import java.math.BigInteger;
import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.Date;
import java.util.HexFormat;
import java.util.Set;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.ExtendedKeyUsage;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.GeneralName;
import org.bouncycastle.asn1.x509.GeneralNames;
import org.bouncycastle.asn1.x509.KeyPurposeId;
import org.bouncycastle.asn1.x509.KeyUsage;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.jce.ECNamedCurveTable;
import org.bouncycastle.math.ec.ECPoint;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;

/** Owns the generated certificate shape and its complete validation policy. */
final class RiverDaemonCredentialCertificate {
  static final int PRIVATE_KEY_MAX_BYTES = 2048;
  static final int CERTIFICATE_MAX_BYTES = 4096;
  static final long VALIDITY_BACKDATE_SECONDS = 5 * 60;
  private static final long VALIDITY_SECONDS = 365 * 24 * 60 * 60;

  private RiverDaemonCredentialCertificate() {
  }

  static StatusCode validate(
      RiverDaemonCredentials.Material material,
      DatabaseIncarnation incarnation,
      Instant createdAt) {
    if (material == null || incarnation == null || !incarnation.isValid() || createdAt == null) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    if (material.generation() <= 0 || material.token() == null
        || material.token().length != RiverDaemonCredentials.TOKEN_BYTES
        || material.privateKey() == null || material.certificate() == null) {
      return StatusCode.CORRUPTION;
    }
    byte[] privateKey = null;
    byte[] certificate = null;
    try {
      privateKey = material.privateKey().getEncoded();
      certificate = material.certificate().getEncoded();
      if (privateKey == null || privateKey.length > PRIVATE_KEY_MAX_BYTES
          || certificate == null || certificate.length > CERTIFICATE_MAX_BYTES) {
        return StatusCode.CORRUPTION;
      }
      BouncyCastleProvider provider = material.provider();
      CertificateFactory factory = CertificateFactory.getInstance("X.509", provider);
      X509Certificate certificateObject = (X509Certificate) factory.generateCertificate(
          new java.io.ByteArrayInputStream(certificate));
      PublicKey publicKey = certificateObject.getPublicKey();
      certificateObject.verify(publicKey, provider);
      if (!"EC".equalsIgnoreCase(publicKey.getAlgorithm())
          || !"EC".equalsIgnoreCase(material.privateKey().getAlgorithm())) {
        return StatusCode.CORRUPTION;
      }
      if (!(publicKey instanceof ECPublicKey certificatePublic)
          || !(material.privateKey() instanceof ECPrivateKey privateEc)
          || !sameCurve(certificatePublic)
          || !matchesPublicKey(privateEc, certificatePublic)) {
        return StatusCode.CORRUPTION;
      }
      Instant second = createdAt.truncatedTo(ChronoUnit.SECONDS);
      Date expectedBefore = Date.from(second.minusSeconds(VALIDITY_BACKDATE_SECONDS));
      Date expectedAfter = Date.from(second.plusSeconds(VALIDITY_SECONDS));
      if (certificateObject.getVersion() != 3
          || certificateObject.getSerialNumber().signum() <= 0
          || certificateObject.getSerialNumber().bitLength() > 128
          || !certificateObject.getNotBefore().equals(expectedBefore)
          || !certificateObject.getNotAfter().equals(expectedAfter)
          || !validNow(certificateObject)
          || certificateObject.getBasicConstraints() != -1
          || !certificateObject.getIssuerX500Principal().equals(
              certificateObject.getSubjectX500Principal())
          || !"SHA256WITHECDSA".equals(normalize(certificateObject.getSigAlgName()))) {
        return StatusCode.CORRUPTION;
      }
      if (!Set.of(Extension.basicConstraints.getId(), Extension.keyUsage.getId()).equals(
              certificateObject.getCriticalExtensionOIDs())
          || !Set.of(Extension.extendedKeyUsage.getId(), Extension.subjectAlternativeName.getId())
              .equals(certificateObject.getNonCriticalExtensionOIDs())) {
        return StatusCode.CORRUPTION;
      }
      boolean[] usage = certificateObject.getKeyUsage();
      if (usage == null || !usage[0]) return StatusCode.CORRUPTION;
      for (int index = 1; index < usage.length; index++) {
        if (usage[index]) return StatusCode.CORRUPTION;
      }
      if (certificateObject.getExtendedKeyUsage() == null
          || certificateObject.getExtendedKeyUsage().size() != 1
          || !KeyPurposeId.id_kp_serverAuth.getId().equals(
              certificateObject.getExtendedKeyUsage().get(0))) {
        return StatusCode.CORRUPTION;
      }
      if (!hasExpectedNames(certificateObject)) return StatusCode.CORRUPTION;
      if (!subjectName(certificateObject).equals(
          "CN=riverd-" + incarnationHex(incarnation))) {
        return StatusCode.CORRUPTION;
      }
      return StatusCode.OK;
    } catch (Exception failure) {
      return StatusCode.CORRUPTION;
    } finally {
      if (privateKey != null) Arrays.fill(privateKey, (byte) 0);
      if (certificate != null) Arrays.fill(certificate, (byte) 0);
    }
  }

  static X509Certificate create(
      KeyPair pair,
      DatabaseIncarnation incarnation,
      SecureRandom random,
      Instant createdAt,
      BouncyCastleProvider provider) throws Exception {
    byte[] serialBytes = new byte[16];
    BigInteger serial;
    do {
      random.nextBytes(serialBytes);
      serial = new BigInteger(1, serialBytes);
    } while (serial.signum() == 0);
    long second = createdAt.getEpochSecond();
    Date before = Date.from(Instant.ofEpochSecond(second - VALIDITY_BACKDATE_SECONDS));
    Date after = Date.from(Instant.ofEpochSecond(second + VALIDITY_SECONDS));
    X500Name name = new X500Name("CN=riverd-" + incarnationHex(incarnation));
    JcaX509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(
        name, serial, before, after, name, pair.getPublic());
    builder.addExtension(Extension.basicConstraints, true, new BasicConstraints(false));
    builder.addExtension(Extension.keyUsage, true, new KeyUsage(KeyUsage.digitalSignature));
    builder.addExtension(
        Extension.extendedKeyUsage, false, new ExtendedKeyUsage(KeyPurposeId.id_kp_serverAuth));
    builder.addExtension(Extension.subjectAlternativeName, false, new GeneralNames(new GeneralName[] {
        new GeneralName(GeneralName.dNSName, "localhost"),
        new GeneralName(GeneralName.iPAddress, "127.0.0.1"),
        new GeneralName(GeneralName.iPAddress, "::1")
    }));
    ContentSigner signer = new JcaContentSignerBuilder("SHA256withECDSA")
        .setProvider(provider)
        .build(pair.getPrivate());
    X509CertificateHolder holder = builder.build(signer);
    Arrays.fill(serialBytes, (byte) 0);
    return new JcaX509CertificateConverter().setProvider(provider).getCertificate(holder);
  }

  private static boolean hasExpectedNames(X509Certificate certificate) throws Exception {
    var names = certificate.getSubjectAlternativeNames();
    if (names == null || names.size() != 3) return false;
    boolean dns = false;
    boolean ipv4 = false;
    boolean ipv6 = false;
    for (var name : names) {
      if (name.size() != 2 || !(name.get(0) instanceof Integer)) return false;
      int type = (Integer) name.get(0);
      String value = String.valueOf(name.get(1));
      if (type == 2 && "localhost".equals(value)) dns = true;
      if (type == 7 && "127.0.0.1".equals(value)) ipv4 = true;
      if (type == 7 && ("::1".equals(value) || "0:0:0:0:0:0:0:1".equals(value))) ipv6 = true;
    }
    return dns && ipv4 && ipv6;
  }

  private static boolean validNow(X509Certificate certificate) {
    try {
      certificate.checkValidity(Date.from(Instant.now()));
      return true;
    } catch (Exception failure) {
      return false;
    }
  }

  private static boolean sameCurve(ECPublicKey key) {
    java.security.spec.ECParameterSpec actual = key.getParams();
    org.bouncycastle.jce.spec.ECParameterSpec expected =
        ECNamedCurveTable.getParameterSpec("secp256r1");
    if (!(actual.getCurve().getField() instanceof java.security.spec.ECFieldFp field)
        || actual.getCofactor() != expected.getH().intValue()
        || !actual.getOrder().equals(expected.getN())) return false;
    return field.getP().equals(expected.getCurve().getField().getCharacteristic())
        && actual.getCurve().getA().equals(expected.getCurve().getA().toBigInteger())
        && actual.getCurve().getB().equals(expected.getCurve().getB().toBigInteger())
        && actual.getGenerator().getAffineX().equals(
            expected.getG().getAffineXCoord().toBigInteger())
        && actual.getGenerator().getAffineY().equals(
            expected.getG().getAffineYCoord().toBigInteger());
  }

  private static boolean matchesPublicKey(ECPrivateKey privateKey, ECPublicKey publicKey) {
    org.bouncycastle.jce.spec.ECParameterSpec curve =
        ECNamedCurveTable.getParameterSpec("secp256r1");
    ECPoint derived = curve.getG().multiply(privateKey.getS()).normalize();
    return derived.getAffineXCoord().toBigInteger().equals(publicKey.getW().getAffineX())
        && derived.getAffineYCoord().toBigInteger().equals(publicKey.getW().getAffineY());
  }

  private static String subjectName(X509Certificate certificate) {
    return certificate.getSubjectX500Principal().getName("CANONICAL")
        .replace("cn=", "CN=");
  }

  static String incarnationHex(DatabaseIncarnation incarnation) {
    return HexFormat.of().toHexDigits(incarnation.high())
        + HexFormat.of().toHexDigits(incarnation.low());
  }

  private static String normalize(String value) {
    return value == null ? "" : value.replace("-", "").toUpperCase(java.util.Locale.ROOT);
  }
}
