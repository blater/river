package io.riverdb.bench.security;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.Set;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;

/** Ephemeral JDK-only localhost TLS identity for one audit run. */
final class AuditTlsMaterial implements AutoCloseable {
  private static final String KEY_ALIAS = "river-audit-localhost";

  private final Path directory;
  private final Path store;
  private final Path passwordFile;
  final SSLContext serverContext;
  final SSLContext clientContext;

  private AuditTlsMaterial(Path directory, Path store, Path passwordFile,
      SSLContext serverContext, SSLContext clientContext) {
    this.directory = directory;
    this.store = store;
    this.passwordFile = passwordFile;
    this.serverContext = serverContext;
    this.clientContext = clientContext;
  }

  static AuditTlsMaterial create(Path output) throws Exception {
    if (!output.getFileSystem().supportedFileAttributeViews().contains("posix")) {
      throw new IOException("TLS material requires POSIX owner-only permissions");
    }
    FileAttribute<Set<PosixFilePermission>> directoryPermissions = PosixFilePermissions.asFileAttribute(
        EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.OWNER_EXECUTE));
    FileAttribute<Set<PosixFilePermission>> passwordPermissions = PosixFilePermissions.asFileAttribute(
        EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
    Path directory = Files.createDirectory(output.resolve("tls-material"), directoryPermissions);
    Path store = directory.resolve("localhost.p12");
    Path passwordFile = directory.resolve("password");
    try {
      Files.createFile(passwordFile, passwordPermissions);
      byte[] passwordBytes = new byte[32];
      new SecureRandom().nextBytes(passwordBytes);
      String password = HexFormat.of().formatHex(passwordBytes);
      Arrays.fill(passwordBytes, (byte) 0);
      Files.writeString(passwordFile, password, StandardCharsets.UTF_8);
      Path keytool = Path.of(System.getProperty("java.home"), "bin", "keytool");
      if (!Files.isExecutable(keytool)) keytool = Path.of("keytool");
      Process process = new ProcessBuilder(
          keytool.toString(), "-genkeypair", "-alias", KEY_ALIAS,
          "-keyalg", "RSA", "-keysize", "2048", "-validity", "2",
          "-dname", "CN=localhost", "-ext", "SAN=dns:localhost,ip:127.0.0.1",
          "-keystore", store.toString(), "-storetype", "PKCS12",
          "-storepass:file", passwordFile.toString(), "-keypass:file", passwordFile.toString(),
          "-noprompt")
          .redirectError(ProcessBuilder.Redirect.DISCARD)
          .redirectOutput(ProcessBuilder.Redirect.DISCARD)
          .start();
      if (process.waitFor() != 0) throw new IOException("keytool failed");
      char[] passwordChars = password.toCharArray();
      try (InputStream input = Files.newInputStream(store)) {
        KeyStore keyStore = KeyStore.getInstance("PKCS12");
        keyStore.load(input, passwordChars);
        KeyManagerFactory keyManagers = KeyManagerFactory.getInstance(
            KeyManagerFactory.getDefaultAlgorithm());
        keyManagers.init(keyStore, passwordChars);
        SSLContext server = SSLContext.getInstance("TLSv1.3");
        server.init(keyManagers.getKeyManagers(), null, new SecureRandom());
        KeyStore trustStore = KeyStore.getInstance("PKCS12");
        trustStore.load(null, null);
        Certificate certificate = keyStore.getCertificate(KEY_ALIAS);
        trustStore.setCertificateEntry(KEY_ALIAS, certificate);
        TrustManagerFactory trustManagers = TrustManagerFactory.getInstance(
            TrustManagerFactory.getDefaultAlgorithm());
        trustManagers.init(trustStore);
        SSLContext client = SSLContext.getInstance("TLSv1.3");
        client.init(null, trustManagers.getTrustManagers(), new SecureRandom());
        return new AuditTlsMaterial(directory, store, passwordFile, server, client);
      } finally {
        Arrays.fill(passwordChars, '\0');
      }
    } catch (Exception failure) {
      Files.deleteIfExists(store);
      Files.deleteIfExists(passwordFile);
      Files.deleteIfExists(directory);
      throw failure;
    }
  }

  @Override
  public void close() throws IOException {
    Files.deleteIfExists(store);
    Files.deleteIfExists(passwordFile);
    Files.deleteIfExists(directory);
  }
}
