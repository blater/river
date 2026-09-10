package io.riverdb.server.app;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.id.DatabaseIncarnation;
import io.riverdb.engine.api.SessionPermissions;
import io.riverdb.platform.file.DirectoryOperationResult;
import io.riverdb.platform.file.FileSizeResult;
import io.riverdb.platform.file.ForceMode;
import io.riverdb.platform.file.IoResult;
import io.riverdb.platform.riverd.RiverDirectory;
import io.riverdb.platform.riverd.RiverDirectoryResult;
import io.riverdb.platform.riverd.RiverFile;
import io.riverdb.platform.riverd.RiverFileResult;
import io.riverdb.platform.riverd.RiverOpenMode;
import io.riverdb.protocol.auth.TokenAuthenticator;
import io.riverdb.protocol.auth.TokenAuthenticatorOpenResult;
import java.math.BigInteger;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.MessageDigest;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.util.Arrays;
import java.util.Date;
import java.util.HexFormat;
import java.util.Set;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import javax.security.auth.DestroyFailedException;
import javax.security.auth.Destroyable;
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

/** Generates and validates one launcher-owned credential generation. */
public final class RiverDaemonCredentials {
  public static final int TOKEN_BYTES = 32;
  private static final int PRIVATE_KEY_MAX_BYTES = 2048;
  private static final int CERTIFICATE_MAX_BYTES = 4096;
  private static final long VALIDITY_BACKDATE_SECONDS = 5 * 60;
  private static final long VALIDITY_SECONDS = 365 * 24 * 60 * 60;
  private static final int MAX_SECURITY_RECORD_BYTES = 8192;
  private static final String SECURITY_FILE = "security.properties";
  private static final String SECURITY_CLIENT_FILE = "client.properties";
  private static final String GENERATIONS_DIRECTORY = "generations";
  private static final String[] SECURITY_KEYS = {
      "format", "database-incarnation-high", "database-incarnation-low",
      "credential-generation", "principal-id", "permission-mask", "token-algorithm",
      "key-algorithm", "signature-algorithm", "certificate-not-before-epoch-second",
      "certificate-not-after-epoch-second", "token-file", "private-key-file",
      "server-certificate-file", "token-sha256", "private-key-sha256",
      "server-certificate-sha256"
  };

  private RiverDaemonCredentials() {
  }

  public static StatusCode generate(
      DatabaseIncarnation incarnation,
      long generation,
      SecureRandom random,
      Instant createdAt,
      CredentialResult result) {
    if (result == null) return StatusCode.INVALID_EXTERNAL_INPUT;
    result.reset();
    if (incarnation == null || !incarnation.isValid() || generation <= 0
        || random == null || createdAt == null) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    byte[] token = new byte[TOKEN_BYTES];
    PrivateKey privateKey = null;
    try {
      random.nextBytes(token);
      BouncyCastleProvider provider = new BouncyCastleProvider();
      KeyPairGenerator generator = KeyPairGenerator.getInstance("EC", provider);
      generator.initialize(new java.security.spec.ECGenParameterSpec("secp256r1"), random);
      KeyPair pair = generator.generateKeyPair();
      privateKey = pair.getPrivate();
      X509Certificate certificate = certificate(
          pair, incarnation, random, createdAt, provider);
      Material material = new Material(generation, token, privateKey, certificate, provider);
      StatusCode valid = validate(material, incarnation, createdAt);
      if (!valid.isOk()) {
        material.destroy();
        return valid;
      }
      TokenAuthenticatorOpenResult authenticatorResult = new TokenAuthenticatorOpenResult();
      valid = TokenAuthenticator.create(
          token, token.length, 1, SessionPermissions.ALL, authenticatorResult);
      if (!valid.isOk()) {
        material.destroy();
        return valid;
      }
      material.authenticator = authenticatorResult.authenticator();
      result.complete(material);
      privateKey = null;
      return StatusCode.OK;
    } catch (Exception failure) {
      if (privateKey instanceof Destroyable destroyable) {
        destroy(destroyable);
      }
      Arrays.fill(token, (byte) 0);
      return StatusCode.INVARIANT_BROKEN;
    }
  }

  public static StatusCode validate(
      Material material, DatabaseIncarnation incarnation, Instant createdAt) {
    if (material == null || incarnation == null || !incarnation.isValid() || createdAt == null) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    if (material.generation <= 0 || material.token == null || material.token.length != TOKEN_BYTES
        || material.privateKey == null || material.certificate == null) {
      return StatusCode.CORRUPTION;
    }
    byte[] privateKey = null;
    byte[] certificate = null;
    try {
      privateKey = material.privateKey.getEncoded();
      certificate = material.certificate.getEncoded();
      if (privateKey == null || privateKey.length > PRIVATE_KEY_MAX_BYTES
          || certificate == null || certificate.length > CERTIFICATE_MAX_BYTES) {
        return StatusCode.CORRUPTION;
      }
      BouncyCastleProvider provider = material.provider;
      CertificateFactory factory = CertificateFactory.getInstance("X.509", provider);
      X509Certificate certificateObject = (X509Certificate) factory.generateCertificate(
          new java.io.ByteArrayInputStream(certificate));
      PublicKey publicKey = certificateObject.getPublicKey();
      certificateObject.verify(publicKey, provider);
      if (!"EC".equalsIgnoreCase(publicKey.getAlgorithm())
          || !"EC".equalsIgnoreCase(material.privateKey.getAlgorithm())) {
        return StatusCode.CORRUPTION;
      }
      if (!(publicKey instanceof ECPublicKey certificatePublic)
          || !(material.privateKey instanceof ECPrivateKey privateEc)
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
      // Encoded private key bytes are credential-equivalent scratch material.
      // The certificate encoding is public, but clearing it keeps this path bounded and uniform.
      // Local arrays are intentionally not retained by the validator.
      if (privateKey != null) Arrays.fill(privateKey, (byte) 0);
      if (certificate != null) Arrays.fill(certificate, (byte) 0);
    }
  }

  /** Writes a complete generation beneath a staged security directory. */
  static StatusCode persist(
      Material material,
      RiverDirectory security,
      DatabaseIncarnation incarnation,
      Instant createdAt) {
    if (material == null || security == null || incarnation == null || createdAt == null
        || !incarnation.isValid() || material.token == null || material.token.length != TOKEN_BYTES
        || material.privateKey == null || material.certificate == null) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    byte[] privateKey = null;
    byte[] certificate = null;
    byte[] manifestBytes = null;
    RiverDirectory generations = null;
    RiverDirectory generation = null;
    StatusCode status = StatusCode.OK;
    try {
      privateKey = material.privateKey.getEncoded();
      certificate = material.certificate.getEncoded();
      if (privateKey == null || privateKey.length > PRIVATE_KEY_MAX_BYTES
          || certificate == null || certificate.length > CERTIFICATE_MAX_BYTES) {
        status = StatusCode.CORRUPTION;
      } else {
        RiverDirectoryResult generationsResult = new RiverDirectoryResult();
        status = security.createDirectory(GENERATIONS_DIRECTORY, generationsResult);
        if (status.isOk()) {
          generations = generationsResult.directory();
          RiverDirectoryResult generationResult = new RiverDirectoryResult();
          String generationName = Long.toString(material.generation);
          status = generations.createDirectory(generationName, generationResult);
          if (status.isOk()) {
            generation = generationResult.directory();
            status = writeChild(generation, "token.bin", material.token);
            if (status.isOk()) status = writeChild(generation, "server-private-key.pkcs8", privateKey);
            if (status.isOk()) status = writeChild(generation, "server-certificate.der", certificate);
            if (status.isOk()) {
              DirectoryOperationResult forced = new DirectoryOperationResult();
              status = generation.force(forced);
            }
            if (status.isOk()) {
              DirectoryOperationResult forced = new DirectoryOperationResult();
              status = generations.force(forced);
            }
            if (status.isOk()) {
              manifestBytes = encodeManifest(material, incarnation, generationName, privateKey, certificate);
              status = writeNamed(security, SECURITY_FILE, manifestBytes);
            }
          }
        }
      }
    } catch (Exception failure) {
      status = StatusCode.INVARIANT_BROKEN;
    } finally {
      if (generation != null) status = closeDirectory(status, generation);
      if (generations != null) status = closeDirectory(status, generations);
      if (privateKey != null) Arrays.fill(privateKey, (byte) 0);
      if (certificate != null) Arrays.fill(certificate, (byte) 0);
      if (manifestBytes != null) Arrays.fill(manifestBytes, (byte) 0);
    }
    if (status.isOk()) {
      try {
        DirectoryOperationResult forced = new DirectoryOperationResult();
        status = security.force(forced);
      } catch (Exception failure) {
        status = StatusCode.INVARIANT_BROKEN;
      }
    }
    return status;
  }

  /** Loads, validates, and reconstructs the current generation from a verified security handle. */
  static StatusCode load(
      RiverDirectory security,
      DatabaseIncarnation incarnation,
      CredentialResult result) {
    if (security == null || incarnation == null || !incarnation.isValid() || result == null) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    result.reset();
    byte[] manifest = null;
    byte[] token = null;
    byte[] privateKeyBytes = null;
    byte[] certificateBytes = null;
    PrivateKey privateKey = null;
    RiverDirectory generations = null;
    RiverDirectory generationDirectory = null;
    SecurityManifest manifestFields = null;
    Material opened = null;
    StatusCode status = StatusCode.OK;
    try {
      BytesResult manifestResult = new BytesResult();
      status = readChild(security, SECURITY_FILE, MAX_SECURITY_RECORD_BYTES, manifestResult);
      if (!status.isOk()) return status;
      manifest = manifestResult.value;
      manifestFields = parseManifest(manifest);
      if (manifestFields == null) return StatusCode.CORRUPTION;
      String[] fields = manifestFields.publicFields;
      long high = canonicalLong(fields[1]);
      long low = canonicalLong(fields[2]);
      long generation = canonicalPositiveLong(fields[3]);
      if (high != incarnation.high() || low != incarnation.low()
          || !"1".equals(fields[4]) || !Integer.toString(SessionPermissions.ALL).equals(fields[5])
          || !"raw-256".equals(fields[6]) || !"ec-secp256r1".equals(fields[7])
          || !"sha256-with-ecdsa".equals(fields[8])) return StatusCode.CORRUPTION;
      if (!("generations/" + generation + "/token.bin").equals(fields[11])
          || !("generations/" + generation + "/server-private-key.pkcs8").equals(fields[12])
          || !("generations/" + generation + "/server-certificate.der").equals(fields[13])) {
        return StatusCode.CORRUPTION;
      }
      RiverDirectoryResult generationsResult = new RiverDirectoryResult();
      status = security.openDirectory(GENERATIONS_DIRECTORY, generationsResult);
      if (status.isOk()) {
        generations = generationsResult.directory();
        RiverDirectoryResult generationResult = new RiverDirectoryResult();
        status = generations.openDirectory(Long.toString(generation), generationResult);
        if (status.isOk()) {
          generationDirectory = generationResult.directory();
          BytesResult tokenResult = new BytesResult();
          BytesResult privateResult = new BytesResult();
          BytesResult certificateResult = new BytesResult();
          status = readChild(generationDirectory, "token.bin", TOKEN_BYTES, tokenResult);
          if (status.isOk()) {
            status = readChild(generationDirectory, "server-private-key.pkcs8", PRIVATE_KEY_MAX_BYTES, privateResult);
          }
          if (status.isOk()) {
            status = readChild(generationDirectory, "server-certificate.der", CERTIFICATE_MAX_BYTES, certificateResult);
          }
          token = tokenResult.value;
          privateKeyBytes = privateResult.value;
          certificateBytes = certificateResult.value;
          if (status.isOk() && (token == null || token.length != TOKEN_BYTES
              || !matchesDigest(token, manifestFields.tokenDigest)
              || !matchesDigest(privateKeyBytes, manifestFields.privateKeyDigest)
              || !matchesDigest(certificateBytes, manifestFields.certificateDigest))) {
            status = StatusCode.CORRUPTION;
          }
          if (status.isOk()) {
            try {
              BouncyCastleProvider provider = new BouncyCastleProvider();
              privateKey = KeyFactory.getInstance("EC", provider).generatePrivate(
                  new java.security.spec.PKCS8EncodedKeySpec(privateKeyBytes));
              CertificateFactory factory = CertificateFactory.getInstance("X.509", provider);
              X509Certificate certificate = (X509Certificate) factory.generateCertificate(
                  new java.io.ByteArrayInputStream(certificateBytes));
              Instant createdAt = certificate.getNotBefore().toInstant().plusSeconds(VALIDITY_BACKDATE_SECONDS);
              if (canonicalLong(fields[9]) != certificate.getNotBefore().toInstant().getEpochSecond()
                  || canonicalPositiveLong(fields[10]) != certificate.getNotAfter().toInstant().getEpochSecond()) {
                status = StatusCode.CORRUPTION;
              }
              if (status.isOk()) {
                Material material = new Material(generation, token, privateKey, certificate, provider);
                token = null;
                privateKey = null;
                try {
                  status = validate(material, incarnation, createdAt);
                  if (status.isOk()) {
                    TokenAuthenticatorOpenResult authenticatorResult = new TokenAuthenticatorOpenResult();
                    status = TokenAuthenticator.create(material.token, material.token.length, 1,
                        SessionPermissions.ALL, authenticatorResult);
                    if (status.isOk()) {
                      material.authenticator = authenticatorResult.authenticator();
                      opened = material;
                    }
                  }
                } catch (RuntimeException failure) {
                  status = StatusCode.CORRUPTION;
                }
                if (opened != material) {
                  material.destroy();
                }
              }
            } catch (Exception failure) {
              status = StatusCode.CORRUPTION;
            }
          }
        }
      }
    } catch (Exception failure) {
      status = StatusCode.CORRUPTION;
    } finally {
      if (generationDirectory != null) {
        status = mergeCloseStatus(status, generationDirectory.close());
      }
      if (generations != null) {
        status = mergeCloseStatus(status, generations.close());
      }
      if (!status.isOk() && opened != null) {
        opened.destroy();
        opened = null;
      }
      if (manifest != null) Arrays.fill(manifest, (byte) 0);
      if (token != null) Arrays.fill(token, (byte) 0);
      if (privateKeyBytes != null) Arrays.fill(privateKeyBytes, (byte) 0);
      if (certificateBytes != null) Arrays.fill(certificateBytes, (byte) 0);
      if (privateKey instanceof Destroyable destroyable) destroy(destroyable);
      if (manifestFields != null) manifestFields.clear();
    }
    if (status.isOk()) {
      if (opened == null) {
        status = StatusCode.CORRUPTION;
      } else {
        status = result.complete(opened);
        if (status.isOk()) {
          opened = null;
        } else {
          opened.destroy();
        }
      }
    }
    if (opened != null) {
      opened.destroy();
    }
    return status;
  }

  /** Publishes listener-bound authenticated discovery state under the held instance lock. */
  static StatusCode publishClientConfiguration(
      Material material,
      RiverDirectory security,
      java.nio.file.Path securityPath,
      DatabaseIncarnation incarnation,
      String host,
      int port,
      String ownerNonce) {
    if (material == null || security == null || securityPath == null || incarnation == null
        || !incarnation.isValid() || !validHost(host) || port <= 0 || port > 65535
        || ownerNonce == null || !ownerNonce.matches("[0-9a-f]{32}")
        || !securityPath.isAbsolute() || !securityPath.equals(securityPath.normalize())
        || material.certificate == null || material.token == null
        || material.token.length != TOKEN_BYTES) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    byte[] certificate = null;
    byte[] recordBytes = null;
    try {
      certificate = material.certificate.getEncoded();
      String generation = Long.toString(material.generation);
      String certificatePath = securityPath.toAbsolutePath().normalize().resolve(
          "generations").resolve(generation).resolve("server-certificate.der").toString();
      String tokenPath = securityPath.toAbsolutePath().normalize().resolve(
          "generations").resolve(generation).resolve("token.bin").toString();
      String body = RiverDaemonIdentityRecords.record(java.util.List.of(
          "format=riverd-client-v1",
          "database-incarnation-high=" + incarnation.high(),
          "database-incarnation-low=" + incarnation.low(),
          "credential-generation=" + material.generation,
          "principal-id=1",
          "transport=tls-v1.3",
          "protocol=river-v5",
          "host=" + host,
          "port=" + port,
          "server-certificate-file=" + certificatePath,
          "server-certificate-sha256=" + hexDigest(certificate),
          "token-file=" + tokenPath));
      recordBytes = body.getBytes(StandardCharsets.UTF_8);
      String stageName = ".client-" + ownerNonce + ".stage";
      RiverFileResult stageResult = new RiverFileResult();
      StatusCode status = security.openFile(stageName, RiverOpenMode.CREATE_NEW, stageResult);
      if (!status.isOk()) return status;
      RiverFile stage = stageResult.file();
      status = writeBytes(stage, recordBytes);
      boolean targetExists = false;
      if (status.isOk()) {
        RiverFileResult target = new RiverFileResult();
        StatusCode targetStatus = security.openFile(SECURITY_CLIENT_FILE, RiverOpenMode.EXISTING, target);
        if (targetStatus.isOk()) {
          targetExists = true;
          target.file().close();
        } else if (targetStatus != StatusCode.CONFLICT) {
          status = targetStatus;
        }
      }
      if (status.isOk()) {
        DirectoryOperationResult publication = new DirectoryOperationResult();
        status = targetExists
            ? security.publishReplacement(stage, stageName, SECURITY_CLIENT_FILE, publication)
            : security.publishExclusive(stage, stageName, SECURITY_CLIENT_FILE, publication);
      }
      StatusCode close = stage.close();
      if (status.isOk() && !close.isOk() && close != StatusCode.CLOSED) status = close;
      if (status.isOk()) {
        DirectoryOperationResult forced = new DirectoryOperationResult();
        status = security.force(forced);
      }
      return status;
    } catch (Exception failure) {
      return StatusCode.INVARIANT_BROKEN;
    } finally {
      if (certificate != null) Arrays.fill(certificate, (byte) 0);
      if (recordBytes != null) Arrays.fill(recordBytes, (byte) 0);
    }
  }

  private static StatusCode writeChild(RiverDirectory directory, String name, byte[] bytes) {
    RiverFileResult fileResult = new RiverFileResult();
    StatusCode status = directory.openFile(name, RiverOpenMode.CREATE_NEW, fileResult);
    if (!status.isOk()) return status;
    RiverFile file = fileResult.file();
    status = writeBytes(file, bytes);
    StatusCode close = file.close();
    return status.isOk() && !close.isOk() && close != StatusCode.CLOSED ? close : status;
  }

  private static StatusCode writeNamed(RiverDirectory directory, String name, byte[] bytes) {
    return writeChild(directory, name, bytes);
  }

  private static StatusCode writeBytes(RiverFile file, byte[] bytes) {
    ByteBuffer source = ByteBuffer.wrap(bytes);
    IoResult io = new IoResult();
    long position = 0;
    while (source.hasRemaining()) {
      StatusCode status = file.write(position, source, io);
      if (!status.isOk()) return status;
      if (io.bytesTransferred() <= 0) return StatusCode.IO_FAILURE;
      position += io.bytesTransferred();
    }
    return file.force(ForceMode.CONTENT_AND_METADATA);
  }

  private static StatusCode readChild(
      RiverDirectory directory, String name, int maximumBytes, BytesResult result) {
    if (result == null) return StatusCode.INVALID_EXTERNAL_INPUT;
    result.clear();
    RiverFileResult fileResult = new RiverFileResult();
    StatusCode status = directory.openFile(name, RiverOpenMode.EXISTING, fileResult);
    if (!status.isOk()) return status;
    RiverFile file = fileResult.file();
    byte[] bytes = null;
    try {
      FileSizeResult size = new FileSizeResult();
      status = file.size(size);
      long length = size.sizeBytes();
      if (status.isOk() && (length < 0 || length > maximumBytes)) status = StatusCode.CORRUPTION;
      if (status.isOk()) {
        bytes = new byte[(int) length];
        ByteBuffer target = ByteBuffer.wrap(bytes);
        IoResult io = new IoResult();
        long position = 0;
        while (status.isOk() && target.hasRemaining()) {
          status = file.read(position, target, io);
          if (status.isOk() && io.bytesTransferred() <= 0) status = StatusCode.IO_FAILURE;
          if (status.isOk()) position += io.bytesTransferred();
        }
      }
      if (status.isOk()) result.value = bytes;
    } finally {
      StatusCode close = file.close();
      if (status.isOk() && !close.isOk() && close != StatusCode.CLOSED) status = close;
      if (!status.isOk() && bytes != null) Arrays.fill(bytes, (byte) 0);
    }
    return status;
  }

  private static byte[] encodeManifest(
      Material material,
      DatabaseIncarnation incarnation,
      String generationName,
      byte[] privateKey,
      byte[] certificate) throws Exception {
    ManifestWriter writer = new ManifestWriter();
    try {
      writer.field("format", "riverd-security-v1");
      writer.field("database-incarnation-high", Long.toString(incarnation.high()));
      writer.field("database-incarnation-low", Long.toString(incarnation.low()));
      writer.field("credential-generation", Long.toString(material.generation));
      writer.field("principal-id", "1");
      writer.field("permission-mask", Integer.toString(SessionPermissions.ALL));
      writer.field("token-algorithm", "raw-256");
      writer.field("key-algorithm", "ec-secp256r1");
      writer.field("signature-algorithm", "sha256-with-ecdsa");
      writer.field("certificate-not-before-epoch-second",
          Long.toString(material.certificate.getNotBefore().toInstant().getEpochSecond()));
      writer.field("certificate-not-after-epoch-second",
          Long.toString(material.certificate.getNotAfter().toInstant().getEpochSecond()));
      writer.field("token-file", "generations/" + generationName + "/token.bin");
      writer.field("private-key-file", "generations/" + generationName + "/server-private-key.pkcs8");
      writer.field("server-certificate-file", "generations/" + generationName + "/server-certificate.der");
      writer.digestField("token-sha256", material.token);
      writer.digestField("private-key-sha256", privateKey);
      writer.digestField("server-certificate-sha256", certificate);
      return writer.finish();
    } finally {
      writer.clear();
    }
  }

  private static SecurityManifest parseManifest(byte[] bytes) {
    if (bytes == null || bytes.length == 0 || bytes.length > MAX_SECURITY_RECORD_BYTES) return null;
    String[] fields = new String[14];
    byte[][] digests = new byte[3][];
    int position = 0;
    int prefixEnd = -1;
    try {
      for (int index = 0; index < SECURITY_KEYS.length; index++) {
        int lineEnd = lineEnd(bytes, position);
        if (lineEnd < 0) return invalidManifest(digests);
        int equals = equalsAt(bytes, position, lineEnd);
        if (equals <= position || !asciiEquals(bytes, position, equals, SECURITY_KEYS[index])) {
          return invalidManifest(digests);
        }
        int valueStart = equals + 1;
        int valueLength = lineEnd - valueStart;
        if (index >= 14) {
          if (valueLength != 64) return invalidManifest(digests);
          digests[index - 14] = parseHex(bytes, valueStart, valueLength);
          if (digests[index - 14] == null) return invalidManifest(digests);
        } else {
          fields[index] = decodeScalar(bytes, valueStart, valueLength);
          if (fields[index] == null) return invalidManifest(digests);
        }
        position = lineEnd + 1;
        if (index == SECURITY_KEYS.length - 1) prefixEnd = position;
      }
      int lineEnd = lineEnd(bytes, position);
      if (lineEnd < 0 || lineEnd != bytes.length - 1) return invalidManifest(digests);
      int equals = equalsAt(bytes, position, lineEnd);
      if (equals <= position || !asciiEquals(bytes, position, equals, "record-sha256")
          || lineEnd - equals - 1 != 64) return invalidManifest(digests);
      byte[] recordDigest = parseHex(bytes, equals + 1, 64);
      if (recordDigest == null) return invalidManifest(digests);
      byte[] actual = sha256(bytes, 0, prefixEnd);
      boolean valid = MessageDigest.isEqual(actual, recordDigest)
          && "riverd-security-v1".equals(fields[0]);
      Arrays.fill(actual, (byte) 0);
      Arrays.fill(recordDigest, (byte) 0);
      if (!valid) return invalidManifest(digests);
      return new SecurityManifest(fields, digests[0], digests[1], digests[2]);
    } catch (CharacterCodingException | RuntimeException failure) {
      return invalidManifest(digests);
    }
  }

  private static SecurityManifest invalidManifest(byte[][] digests) {
    for (byte[] digest : digests) {
      if (digest != null) Arrays.fill(digest, (byte) 0);
    }
    return null;
  }

  private static int lineEnd(byte[] bytes, int start) {
    for (int index = start; index < bytes.length; index++) {
      if (bytes[index] == '\n') return index;
    }
    return -1;
  }

  private static int equalsAt(byte[] bytes, int start, int end) {
    for (int index = start; index < end; index++) {
      if (bytes[index] == '=') return index;
    }
    return -1;
  }

  private static boolean asciiEquals(byte[] bytes, int start, int end, String expected) {
    if (end - start != expected.length()) return false;
    for (int index = 0; index < expected.length(); index++) {
      if (bytes[start + index] != (byte) expected.charAt(index)) return false;
    }
    return true;
  }

  private static String decodeScalar(byte[] bytes, int start, int length)
      throws CharacterCodingException {
    return StandardCharsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes, start, length)).toString();
  }

  private static byte[] parseHex(byte[] bytes, int start, int length) {
    if (length != 64) return null;
    byte[] result = new byte[32];
    for (int index = 0; index < result.length; index++) {
      int high = hexValue(bytes[start + index * 2]);
      int low = hexValue(bytes[start + index * 2 + 1]);
      if (high < 0 || low < 0) {
        Arrays.fill(result, (byte) 0);
        return null;
      }
      result[index] = (byte) ((high << 4) | low);
    }
    return result;
  }

  private static int hexValue(byte value) {
    if (value >= '0' && value <= '9') return value - '0';
    if (value >= 'a' && value <= 'f') return value - 'a' + 10;
    return -1;
  }

  private static byte[] sha256(byte[] bytes, int offset, int length) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      digest.update(bytes, offset, length);
      return digest.digest();
    } catch (java.security.NoSuchAlgorithmException failure) {
      throw new IllegalStateException("SHA-256 unavailable", failure);
    }
  }

  private static StatusCode mergeCloseStatus(StatusCode status, StatusCode close) {
    if (status.isOk() && !close.isOk() && close != StatusCode.CLOSED) return close;
    return status;
  }

  private static StatusCode closeDirectory(StatusCode status, RiverDirectory directory) {
    try {
      return mergeCloseStatus(status, directory.close());
    } catch (RuntimeException failure) {
      return status.isOk() ? StatusCode.INVARIANT_BROKEN : status;
    }
  }

  private static final class ManifestWriter {
    private final byte[] bytes = new byte[MAX_SECURITY_RECORD_BYTES];
    private int position;

    void field(String key, String value) {
      appendAscii(key);
      appendByte((byte) '=');
      byte[] encoded = value.getBytes(StandardCharsets.UTF_8);
      try {
        append(encoded, 0, encoded.length);
      } finally {
        Arrays.fill(encoded, (byte) 0);
      }
      appendByte((byte) '\n');
    }

    void digestField(String key, byte[] value) throws Exception {
      appendAscii(key);
      appendByte((byte) '=');
      byte[] digest = sha256(value, 0, value.length);
      appendHex(digest);
      Arrays.fill(digest, (byte) 0);
      appendByte((byte) '\n');
    }

    byte[] finish() throws Exception {
      byte[] digest = sha256(bytes, 0, position);
      appendAscii("record-sha256");
      appendByte((byte) '=');
      appendHex(digest);
      appendByte((byte) '\n');
      Arrays.fill(digest, (byte) 0);
      return Arrays.copyOf(bytes, position);
    }

    void clear() {
      Arrays.fill(bytes, (byte) 0);
    }

    private void appendAscii(String value) {
      for (int index = 0; index < value.length(); index++) {
        appendByte((byte) value.charAt(index));
      }
    }

    private void appendHex(byte[] value) {
      final byte[] digits = "0123456789abcdef".getBytes(StandardCharsets.US_ASCII);
      for (byte element : value) {
        appendByte(digits[(element >>> 4) & 0x0f]);
        appendByte(digits[element & 0x0f]);
      }
      Arrays.fill(digits, (byte) 0);
    }

    private void append(byte[] value, int offset, int length) {
      if (length < 0 || position > bytes.length - length) {
        throw new IllegalArgumentException("security manifest too large");
      }
      System.arraycopy(value, offset, bytes, position, length);
      position += length;
    }

    private void appendByte(byte value) {
      if (position == bytes.length) throw new IllegalArgumentException("security manifest too large");
      bytes[position++] = value;
    }
  }

  private static final class SecurityManifest {
    private final String[] publicFields;
    private byte[] tokenDigest;
    private byte[] privateKeyDigest;
    private byte[] certificateDigest;

    SecurityManifest(
        String[] publicFields,
        byte[] tokenDigest,
        byte[] privateKeyDigest,
        byte[] certificateDigest) {
      this.publicFields = publicFields;
      this.tokenDigest = tokenDigest;
      this.privateKeyDigest = privateKeyDigest;
      this.certificateDigest = certificateDigest;
    }

    void clear() {
      if (tokenDigest != null) Arrays.fill(tokenDigest, (byte) 0);
      if (privateKeyDigest != null) Arrays.fill(privateKeyDigest, (byte) 0);
      if (certificateDigest != null) Arrays.fill(certificateDigest, (byte) 0);
      tokenDigest = null;
      privateKeyDigest = null;
      certificateDigest = null;
    }
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

  private static String hexDigest(byte[] bytes) {
    byte[] digest = sha256(bytes, 0, bytes.length);
    String value = HexFormat.of().formatHex(digest);
    Arrays.fill(digest, (byte) 0);
    return value;
  }

  private static boolean matchesDigest(byte[] bytes, byte[] expected) {
    if (bytes == null || expected == null || expected.length != 32) return false;
    byte[] actual = null;
    try {
      actual = sha256(bytes, 0, bytes.length);
      return MessageDigest.isEqual(actual, expected);
    } catch (Exception failure) {
      return false;
    } finally {
      if (actual != null) Arrays.fill(actual, (byte) 0);
    }
  }

  private static boolean validHost(String value) {
    return "localhost".equals(value) || "127.0.0.1".equals(value) || "::1".equals(value);
  }

  static final class BytesResult {
    private byte[] value;

    void clear() {
      if (value != null) Arrays.fill(value, (byte) 0);
      value = null;
    }
  }

  private static X509Certificate certificate(
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

  private static String incarnationHex(DatabaseIncarnation incarnation) {
    return HexFormat.of().toHexDigits(incarnation.high())
        + HexFormat.of().toHexDigits(incarnation.low());
  }

  private static String normalize(String value) {
    return value == null ? "" : value.replace("-", "").toUpperCase(java.util.Locale.ROOT);
  }

  private static void destroy(Destroyable destroyable) {
    try {
      destroyable.destroy();
    } catch (DestroyFailedException ignored) {
      // The owning lifecycle reports cleanup failure when it destroys live material.
    }
  }

  static final class CredentialResult {
    private Material material;

    public void reset() {
      material = null;
    }

    public StatusCode complete(Material opened) {
      if (opened == null) return StatusCode.INVALID_EXTERNAL_INPUT;
      material = opened;
      return StatusCode.OK;
    }

    public Material material() {
      return material;
    }
  }

  static final class Material {
    private final long generation;
    private byte[] token;
    private PrivateKey privateKey;
    private final X509Certificate certificate;
    private final BouncyCastleProvider provider;
    private TokenAuthenticator authenticator;
    private boolean destroyed;

    Material(
        long generation,
        byte[] token,
        PrivateKey privateKey,
        X509Certificate certificate,
        BouncyCastleProvider provider) {
      this.generation = generation;
      this.token = token;
      this.privateKey = privateKey;
      this.certificate = certificate;
      this.provider = provider;
    }

    long generation() {
      return generation;
    }

    PrivateKey privateKey() {
      return privateKey;
    }

    X509Certificate certificate() {
      return certificate;
    }

    TokenAuthenticator authenticator() {
      return authenticator;
    }

    BouncyCastleProvider provider() {
      return provider;
    }

    synchronized StatusCode destroy() {
      if (destroyed) return StatusCode.OK;
      if (token != null) Arrays.fill(token, (byte) 0);
      token = null;
      StatusCode status = StatusCode.OK;
      TokenAuthenticator verifier = authenticator;
      authenticator = null;
      if (verifier != null) {
        try {
          status = verifier.destroy();
        } catch (RuntimeException failure) {
          status = StatusCode.IO_FAILURE;
        }
      }
      PrivateKey key = privateKey;
      privateKey = null;
      if (key instanceof Destroyable destroyable) {
        try {
          destroyable.destroy();
          if (!destroyable.isDestroyed()) status = StatusCode.IO_FAILURE;
        } catch (DestroyFailedException | RuntimeException failure) {
          if (status.isOk()) status = StatusCode.IO_FAILURE;
        }
      }
      destroyed = true;
      return status;
    }
  }
}
