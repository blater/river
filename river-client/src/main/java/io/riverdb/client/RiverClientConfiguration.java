package io.riverdb.client;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.id.DatabaseIncarnation;
import io.riverdb.platform.file.FileSizeResult;
import io.riverdb.platform.file.IoResult;
import io.riverdb.platform.riverd.RiverDaemonFileSystem;
import io.riverdb.platform.riverd.RiverDaemonFileSystemResult;
import io.riverdb.platform.riverd.RiverDaemonFileSystems;
import io.riverdb.platform.riverd.RiverDirectory;
import io.riverdb.platform.riverd.RiverDirectoryResult;
import io.riverdb.platform.riverd.RiverFile;
import io.riverdb.platform.riverd.RiverFileResult;
import io.riverdb.platform.riverd.RiverOpenMode;
import io.riverdb.protocol.auth.TokenProof;
import java.io.ByteArrayInputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.HexFormat;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/** Validated, immutable view of one riverd-generated client.properties file. */
public final class RiverClientConfiguration {
  private static final int MAX_RECORD_BYTES = 8192;
  private static final int MAX_CERTIFICATE_BYTES = 4096;
  private static final String[] KEYS = {
      "format", "database-incarnation-high", "database-incarnation-low",
      "credential-generation", "principal-id", "transport", "protocol", "host", "port",
      "server-certificate-file", "server-certificate-sha256", "token-file", "record-sha256"
  };

  private final RiverDaemonFileSystem fileSystem;
  private final DatabaseIncarnation incarnation;
  private final long credentialGeneration;
  private final String host;
  private final int port;
  private final Path certificateFile;
  private final byte[] certificateDigest;
  private final Path tokenFile;

  private RiverClientConfiguration(
      RiverDaemonFileSystem fileSystem,
      DatabaseIncarnation incarnation,
      long credentialGeneration,
      String host,
      int port,
      Path certificateFile,
      byte[] certificateDigest,
      Path tokenFile) {
    this.fileSystem = fileSystem;
    this.incarnation = incarnation;
    this.credentialGeneration = credentialGeneration;
    this.host = host;
    this.port = port;
    this.certificateFile = certificateFile;
    this.certificateDigest = certificateDigest;
    this.tokenFile = tokenFile;
  }

  public static StatusCode load(
      Path path, RiverDaemonFileSystem fileSystem, RiverClientConfigurationResult result) {
    if (result == null) return StatusCode.INVALID_EXTERNAL_INPUT;
    result.reset();
    if (fileSystem == null || !validAbsolutePath(path)) return StatusCode.INVALID_EXTERNAL_INPUT;
    BytesResult bytes = new BytesResult();
    StatusCode status = readBounded(fileSystem, path, MAX_RECORD_BYTES, bytes);
    if (!status.isOk()) {
      bytes.clear();
      return status;
    }
    try {
      String text = decode(bytes.value);
      String[] lines = text.split("\\n", -1);
      if (lines.length != KEYS.length + 1 || !lines[lines.length - 1].isEmpty()) {
        return StatusCode.CORRUPTION;
      }
      String[] values = new String[KEYS.length];
      for (int index = 0; index < KEYS.length; index++) {
        String line = lines[index];
        int equals = line.indexOf('=');
        if (equals <= 0 || line.indexOf('=', equals + 1) >= 0
            || !KEYS[index].equals(line.substring(0, equals))) {
          return StatusCode.CORRUPTION;
        }
        values[index] = line.substring(equals + 1);
        if (!validValue(values[index])) return StatusCode.CORRUPTION;
      }
      if (!lowerHex(values[12], 64)) return StatusCode.CORRUPTION;
      int checksumLineStart = text.lastIndexOf("record-sha256=");
      if (checksumLineStart <= 0 || text.charAt(checksumLineStart - 1) != '\n') {
        return StatusCode.CORRUPTION;
      }
      byte[] checksumPrefix = text.substring(0, checksumLineStart)
          .getBytes(StandardCharsets.UTF_8);
      byte[] computed = MessageDigest.getInstance("SHA-256").digest(checksumPrefix);
      if (!MessageDigest.isEqual(computed, HexFormat.of().parseHex(values[12]))) {
        return StatusCode.CORRUPTION;
      }
      if (!"riverd-client-v1".equals(values[0]) || !"1".equals(values[4])
          || !"tls-v1.3".equals(values[5]) || !"river-v4".equals(values[6])) {
        return StatusCode.CORRUPTION;
      }
      long high = canonicalLong(values[1]);
      long low = canonicalLong(values[2]);
      DatabaseIncarnation incarnation = DatabaseIncarnation.of(high, low);
      long generation = canonicalPositiveLong(values[3]);
      if (!validHost(values[7])) return StatusCode.CORRUPTION;
      long portValue = canonicalPositiveLong(values[8]);
      if (portValue > 65535) return StatusCode.CORRUPTION;
      Path certificate = absoluteNormalized(values[9]);
      Path token = absoluteNormalized(values[11]);
      if (certificate == null || token == null || !lowerHex(values[10], 64)) {
        return StatusCode.CORRUPTION;
      }
      result.complete(new RiverClientConfiguration(
          fileSystem, incarnation, generation, values[7], (int) portValue, certificate,
          HexFormat.of().parseHex(values[10]), token));
      return StatusCode.OK;
    } catch (CharacterCodingException failure) {
      return StatusCode.CORRUPTION;
    } catch (GeneralSecurityException failure) {
      return StatusCode.INVARIANT_BROKEN;
    } catch (IllegalArgumentException failure) {
      return StatusCode.CORRUPTION;
    } finally {
      bytes.clear();
    }
  }

  /** Loads a generated client file using the selected platform filesystem owner. */
  public static StatusCode load(Path path, RiverClientConfigurationResult result) {
    if (result == null) return StatusCode.INVALID_EXTERNAL_INPUT;
    RiverDaemonFileSystemResult selected = new RiverDaemonFileSystemResult();
    StatusCode status = RiverDaemonFileSystems.current(selected);
    if (!status.isOk()) {
      result.reset();
      return status;
    }
    return load(path, selected.fileSystem(), result);
  }

  public DatabaseIncarnation incarnation() {
    return incarnation;
  }

  public long credentialGeneration() {
    return credentialGeneration;
  }

  public String host() {
    return host;
  }

  public int port() {
    return port;
  }

  public Path certificateFile() {
    return certificateFile;
  }

  public Path tokenFile() {
    return tokenFile;
  }

  /** Opens and authenticates using only this validated configuration. */
  public StatusCode connect(RiverClientOpenResult result) {
    return RiverClientConnector.connect(this, result);
  }

  StatusCode readToken(BytesResult result) {
    StatusCode status = readBounded(fileSystem, tokenFile, TokenProof.PROOF_BYTES, result);
    if (!status.isOk()) return status;
    return result.value.length == TokenProof.PROOF_BYTES
        ? StatusCode.OK : StatusCode.CORRUPTION;
  }

  StatusCode createPinnedContext(ContextResult result) {
    if (result == null) return StatusCode.INVALID_EXTERNAL_INPUT;
    result.reset();
    BytesResult bytes = new BytesResult();
    StatusCode status = readBounded(fileSystem, certificateFile, MAX_CERTIFICATE_BYTES, bytes);
    if (!status.isOk()) {
      bytes.clear();
      return status;
    }
    try {
      byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes.value);
      if (!MessageDigest.isEqual(digest, certificateDigest)) return StatusCode.CORRUPTION;
      CertificateFactory factory = CertificateFactory.getInstance("X.509");
      X509Certificate certificate = (X509Certificate) factory.generateCertificate(
          new ByteArrayInputStream(bytes.value));
      if (!MessageDigest.isEqual(bytes.value, certificate.getEncoded())) {
        return StatusCode.CORRUPTION;
      }
      SSLContext context = SSLContext.getInstance("TLSv1.3");
      context.init(null, new TrustManager[] {new ExactCertificateTrustManager(certificate)}, null);
      result.complete(context);
      return StatusCode.OK;
    } catch (GeneralSecurityException failure) {
      return StatusCode.CORRUPTION;
    } finally {
      bytes.clear();
    }
  }

  private static StatusCode readBounded(
      RiverDaemonFileSystem fileSystem, Path path, int maximum, BytesResult result) {
    if (fileSystem == null || result == null || !validAbsolutePath(path)) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    result.clear();
    Path parentPath = path.getParent();
    Path fileName = path.getFileName();
    if (parentPath == null || fileName == null || !validChild(fileName.toString())) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    RiverDirectoryResult directoryResult = new RiverDirectoryResult();
    StatusCode status = fileSystem.openDirectory(parentPath, directoryResult);
    if (!status.isOk()) return status;
    RiverDirectory directory = directoryResult.directory();
    RiverFileResult fileResult = new RiverFileResult();
    RiverFile file = null;
    byte[] bytes = null;
    boolean transferred = false;
    try {
      status = directory.openFile(fileName.toString(), RiverOpenMode.EXISTING, fileResult);
      if (status.isOk()) {
        file = fileResult.file();
        FileSizeResult size = new FileSizeResult();
        status = file.size(size);
        long length = size.sizeBytes();
        if (status.isOk() && (length < 0 || length > maximum)) status = StatusCode.CORRUPTION;
        if (status.isOk()) {
          bytes = new byte[(int) length];
          ByteBuffer target = ByteBuffer.wrap(bytes);
          IoResult io = new IoResult();
          long position = 0;
          while (status.isOk() && target.hasRemaining()) {
            status = file.read(position, target, io);
            int transferredBytes = io.bytesTransferred();
            if (status.isOk() && transferredBytes <= 0) {
              status = StatusCode.IO_FAILURE;
            } else if (status.isOk()) {
              position += transferredBytes;
            }
          }
        }
      }
      if (status.isOk()) {
        result.value = bytes;
        transferred = true;
      }
    } finally {
      StatusCode closeFile = file == null ? StatusCode.OK : file.close();
      StatusCode closeDirectory = directory.close();
      if (status.isOk() && !closeFile.isOk()) status = closeFile;
      if (status.isOk() && !closeDirectory.isOk()) status = closeDirectory;
      if (!transferred && bytes != null) Arrays.fill(bytes, (byte) 0);
    }
    return status;
  }

  private static String decode(byte[] bytes) throws CharacterCodingException {
    return StandardCharsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes))
        .toString();
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

  private static boolean validAbsolutePath(Path path) {
    if (path == null || !path.isAbsolute() || !path.equals(path.normalize())) return false;
    return validValue(path.toString());
  }

  private static Path absoluteNormalized(String value) {
    Path path = Path.of(value);
    return validAbsolutePath(path) ? path : null;
  }

  private static boolean validChild(String value) {
    return !value.isEmpty() && !value.equals(".") && !value.equals("..")
        && value.indexOf('/') < 0 && value.indexOf('\\') < 0 && validValue(value);
  }

  private static boolean validValue(String value) {
    if (value.isEmpty() || !value.equals(value.strip())) return false;
    for (int index = 0; index < value.length(); index++) {
      char character = value.charAt(index);
      if (character == '=' || character == '\r' || character == '\n' || character == 0
          || Character.getType(character) == Character.CONTROL) return false;
    }
    return true;
  }

  private static boolean validHost(String value) {
    return "localhost".equals(value) || "127.0.0.1".equals(value) || "::1".equals(value);
  }

  private static boolean lowerHex(String value, int length) {
    if (value.length() != length) return false;
    for (int index = 0; index < value.length(); index++) {
      char character = value.charAt(index);
      if (!(character >= '0' && character <= '9')
          && !(character >= 'a' && character <= 'f')) return false;
    }
    return true;
  }

  static final class BytesResult {
    byte[] value;

    void clear() {
      if (value != null) Arrays.fill(value, (byte) 0);
      value = null;
    }
  }

  static final class ContextResult {
    SSLContext context;

    void reset() {
      context = null;
    }

    void complete(SSLContext opened) {
      context = opened;
    }
  }

  private static final class ExactCertificateTrustManager implements X509TrustManager {
    private final X509Certificate certificate;

    private ExactCertificateTrustManager(X509Certificate certificate) {
      this.certificate = certificate;
    }

    @Override
    public void checkClientTrusted(X509Certificate[] chain, String authType) {
      throw new UnsupportedOperationException("server-only trust manager");
    }

    @Override
    public void checkServerTrusted(X509Certificate[] chain, String authType)
        throws java.security.cert.CertificateException {
      if (chain == null || chain.length != 1) {
        throw new java.security.cert.CertificateException("server certificate mismatch");
      }
      chain[0].checkValidity();
      if (!MessageDigest.isEqual(chain[0].getEncoded(), certificate.getEncoded())) {
        throw new java.security.cert.CertificateException("server certificate mismatch");
      }
    }

    @Override
    public X509Certificate[] getAcceptedIssuers() {
      return new X509Certificate[] {certificate};
    }
  }
}
