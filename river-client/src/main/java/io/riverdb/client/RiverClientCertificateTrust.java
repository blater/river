package io.riverdb.client;

import io.riverdb.base.error.StatusCode;
import io.riverdb.platform.riverd.RiverDaemonFileSystem;
import java.io.ByteArrayInputStream;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/** Authenticates the configured certificate and creates its exact TLS trust context. */
final class RiverClientCertificateTrust {
  private static final int MAX_CERTIFICATE_BYTES = 4096;

  private RiverClientCertificateTrust() { }

  static StatusCode create(
      RiverDaemonFileSystem fileSystem,
      java.nio.file.Path certificateFile,
      byte[] certificateDigest,
      RiverClientConfiguration.ContextResult result) {
    if (result == null) return StatusCode.INVALID_EXTERNAL_INPUT;
    result.reset();
    RiverClientConfiguration.BytesResult bytes =
        new RiverClientConfiguration.BytesResult();
    StatusCode status = RiverClientFileReader.readBounded(
        fileSystem, certificateFile, MAX_CERTIFICATE_BYTES, bytes);
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
