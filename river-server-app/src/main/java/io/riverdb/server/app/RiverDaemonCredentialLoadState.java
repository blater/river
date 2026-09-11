package io.riverdb.server.app;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.id.DatabaseIncarnation;
import io.riverdb.engine.api.SessionPermissions;
import io.riverdb.platform.riverd.RiverDirectory;
import io.riverdb.platform.riverd.RiverDirectoryResult;
import io.riverdb.protocol.auth.TokenAuthenticator;
import io.riverdb.protocol.auth.TokenAuthenticatorOpenResult;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.Arrays;
import javax.security.auth.Destroyable;
import org.bouncycastle.jce.provider.BouncyCastleProvider;

/** Owns the acquired state while one credential generation is loaded. */
final class RiverDaemonCredentialLoadState {
  private final RiverDirectory security;
  private final DatabaseIncarnation incarnation;
  private byte[] manifest;
  private byte[] token;
  private byte[] privateKeyBytes;
  private byte[] certificateBytes;
  private PrivateKey privateKey;
  private RiverDirectory generations;
  private RiverDirectory generationDirectory;
  private RiverDaemonCredentialManifest.Parsed manifestFields;
  private RiverDaemonCredentials.Material opened;

  RiverDaemonCredentialLoadState(RiverDirectory security, DatabaseIncarnation incarnation) {
    this.security = security;
    this.incarnation = incarnation;
  }

  StatusCode read() {
    try {
      RiverDaemonCredentials.BytesResult manifestResult =
          new RiverDaemonCredentials.BytesResult();
      StatusCode status = RiverDaemonCredentialFiles.read(
          security, RiverDaemonCredentialStorage.SECURITY_FILE,
          RiverDaemonCredentialManifest.MAX_BYTES,
          manifestResult);
      if (!status.isOk()) return status;
      manifest = manifestResult.value();
      manifestFields = RiverDaemonCredentialManifest.parse(manifest);
      if (manifestFields == null) return StatusCode.CORRUPTION;
      String[] fields = manifestFields.publicFields;
      long high = canonicalLong(fields[1]);
      long low = canonicalLong(fields[2]);
      long generation = canonicalPositiveLong(fields[3]);
      if (high != incarnation.high() || low != incarnation.low()
          || !"1".equals(fields[4])
          || !Integer.toString(SessionPermissions.ALL).equals(fields[5])
          || !"raw-256".equals(fields[6]) || !"ec-secp256r1".equals(fields[7])
          || !"sha256-with-ecdsa".equals(fields[8])) return StatusCode.CORRUPTION;
      if (!("generations/" + generation + "/token.bin").equals(fields[11])
          || !("generations/" + generation + "/server-private-key.pkcs8").equals(fields[12])
          || !("generations/" + generation + "/server-certificate.der").equals(fields[13])) {
        return StatusCode.CORRUPTION;
      }
      return readGeneration(fields, generation);
    } catch (Exception failure) {
      return StatusCode.CORRUPTION;
    }
  }

  private StatusCode readGeneration(String[] fields, long generation) throws Exception {
    RiverDirectoryResult generationsResult = new RiverDirectoryResult();
    StatusCode status = security.openDirectory(
        RiverDaemonCredentialStorage.GENERATIONS_DIRECTORY, generationsResult);
    if (!status.isOk()) return status;
    generations = generationsResult.directory();
    RiverDirectoryResult generationResult = new RiverDirectoryResult();
    status = generations.openDirectory(Long.toString(generation), generationResult);
    if (!status.isOk()) return status;
    generationDirectory = generationResult.directory();
    RiverDaemonCredentials.BytesResult tokenResult =
        new RiverDaemonCredentials.BytesResult();
    RiverDaemonCredentials.BytesResult privateResult =
        new RiverDaemonCredentials.BytesResult();
    RiverDaemonCredentials.BytesResult certificateResult =
        new RiverDaemonCredentials.BytesResult();
    status = RiverDaemonCredentialFiles.read(generationDirectory, "token.bin",
        RiverDaemonCredentials.TOKEN_BYTES, tokenResult);
    if (status.isOk()) status = RiverDaemonCredentialFiles.read(
        generationDirectory, "server-private-key.pkcs8",
        RiverDaemonCredentialCertificate.PRIVATE_KEY_MAX_BYTES, privateResult);
    if (status.isOk()) status = RiverDaemonCredentialFiles.read(
        generationDirectory, "server-certificate.der",
        RiverDaemonCredentialCertificate.CERTIFICATE_MAX_BYTES, certificateResult);
    token = tokenResult.value();
    privateKeyBytes = privateResult.value();
    certificateBytes = certificateResult.value();
    if (!status.isOk()) return status;
    if (token == null || token.length != RiverDaemonCredentials.TOKEN_BYTES
        || !RiverDaemonCredentialManifest.matchesDigest(token, manifestFields.tokenDigest())
        || !RiverDaemonCredentialManifest.matchesDigest(
            privateKeyBytes, manifestFields.privateKeyDigest())
        || !RiverDaemonCredentialManifest.matchesDigest(
            certificateBytes, manifestFields.certificateDigest())) {
      return StatusCode.CORRUPTION;
    }
    return openMaterial(fields, generation);
  }

  private StatusCode openMaterial(String[] fields, long generation) throws Exception {
    BouncyCastleProvider provider = new BouncyCastleProvider();
    privateKey = KeyFactory.getInstance("EC", provider).generatePrivate(
        new java.security.spec.PKCS8EncodedKeySpec(privateKeyBytes));
    CertificateFactory factory = CertificateFactory.getInstance("X.509", provider);
    X509Certificate certificate = (X509Certificate) factory.generateCertificate(
        new java.io.ByteArrayInputStream(certificateBytes));
    Instant createdAt = certificate.getNotBefore().toInstant()
        .plusSeconds(RiverDaemonCredentialCertificate.VALIDITY_BACKDATE_SECONDS);
    if (canonicalLong(fields[9]) != certificate.getNotBefore().toInstant().getEpochSecond()
        || canonicalPositiveLong(fields[10]) != certificate.getNotAfter().toInstant().getEpochSecond()) {
      return StatusCode.CORRUPTION;
    }
    RiverDaemonCredentials.Material material = new RiverDaemonCredentials.Material(
        generation, token, privateKey, certificate, provider);
    token = null;
    privateKey = null;
    try {
      StatusCode status = RiverDaemonCredentials.validate(material, incarnation, createdAt);
      if (status.isOk()) {
        TokenAuthenticatorOpenResult authenticatorResult = new TokenAuthenticatorOpenResult();
        status = TokenAuthenticator.create(material.token(), material.token().length, 1,
            SessionPermissions.ALL, authenticatorResult);
        if (status.isOk()) {
          material.setAuthenticator(authenticatorResult.authenticator());
          opened = material;
        }
      }
      if (opened != material) material.destroy();
      return status;
    } catch (RuntimeException failure) {
      material.destroy();
      return StatusCode.CORRUPTION;
    }
  }

  RiverDaemonCredentials.Material opened() {
    return opened;
  }

  void discardOpened() {
    opened = null;
  }

  StatusCode close(StatusCode status) {
    if (generationDirectory != null) {
      status = RiverDaemonCredentialStorage.closeDirectory(status, generationDirectory);
    }
    if (generations != null) {
      status = RiverDaemonCredentialStorage.closeDirectory(status, generations);
    }
    if (!status.isOk() && opened != null) {
      opened.destroy();
      opened = null;
    }
    return status;
  }

  void clear() {
    clearBytes(manifest);
    clearBytes(token);
    clearBytes(privateKeyBytes);
    clearBytes(certificateBytes);
    if (privateKey instanceof Destroyable destroyable) {
      try {
        destroyable.destroy();
      } catch (Exception ignored) {
        // The load result already carries the primary status.
      }
    }
    if (manifestFields != null) manifestFields.clear();
  }

  private static void clearBytes(byte[] bytes) {
    if (bytes != null) Arrays.fill(bytes, (byte) 0);
  }

  private static long canonicalLong(String value) {
    long parsed = Long.parseLong(value);
    if (!Long.toString(parsed).equals(value)) throw new IllegalArgumentException("noncanonical");
    return parsed;
  }

  private static long canonicalPositiveLong(String value) {
    long parsed = canonicalLong(value);
    if (parsed <= 0) throw new IllegalArgumentException("nonpositive");
    return parsed;
  }
}
