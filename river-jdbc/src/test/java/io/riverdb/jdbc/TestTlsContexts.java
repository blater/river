package io.riverdb.jdbc;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;

/** In-memory localhost identity used only by the TLS integration test. */
final class TestTlsContexts {
  private static final char[] PASSWORD = "river-test-only".toCharArray();
  private static final String PRIVATE_KEY = """
      MIIEvQIBADANBgkqhkiG9w0BAQEFAASCBKcwggSjAgEAAoIBAQD1mEt1guOw+Zo6
      g9zJitb3sY9VfdSXjNYWg/7RuQmQP8GjQeWSPYVzC6F6kvudP/wErYGeRjJ4Hvg1
      DEgJFWAAqo9hCw+f7agPCtYDkMpDUzQe5chUrsrt1vOQ3RVFJ7bhY1aaDL3/6sPe
      wQcu9iEk9a+/4c7X+bEEPUYs6wa/YwL570JqnHszfBP3F1YpGXJiufj7ztW5D7eV
      70QUqPbRZvEzSQdn2vnWydXSuDj3K+HYr2XAb2XVbIe8HJgaHz1UaRSCr89VjVkp
      PcI5gpV2z97M0q4QfajhsacR7WenGTdcJXfPglHe6Dmxo8zCrFyvl7VCzCz1vBbX
      ZyosUpo7AgMBAAECggEAdBIhm6ycKtCUpRIb/36oQVFAMkHxfxyXM/X3MSw4Cl4v
      iJaExtxfnTNCgplD5JpQY++Sonh/ssjEOuIOi4h4Imh5sf4VyNp7wnw3EGFWToJc
      0Dt+NhOUIdskqvLp8hCmfJhD3jOmenR8VOM6n2XA3WRmRxQT+3vtPtksuN7tBfb6
      7aGZT1AzJN7I1apiyyOwm6YxA2tQB7K3x5K/td0zXYyf/m9sqt108DliBTgWoRFW
      yxCZR6XmFv3YeC0SRs4xjH5+gCAVepn4FKAN/8E+QSX9rZndTkC99oIeTCMQy1Qa
      M3FK8SDhd9DPX0bFq04WKgthEEdogbhe3bnpDPEubQKBgQD/F23aniA1XaHm8er6
      omyZTqgMt4GgrZwUgUeJ+xIsubeVqkMWw13bCVU6RUrBul9jtUkCoYQftzbH5NpT
      jELGGqyqCMTd+famEfzMBYPhBo2QVRvH2J+Xu3sV3EFYBYMzBPpxHknlErOQRpU1
      DnpGhDGWdQmh/8Pw3Nz/eeatLQKBgQD2eDUaLoFzhTGFIpqD+KW3nT1hLzXd7REp
      jlrXpZjP/vKRuZ5jtRA9wyJClwKXM5u1fZtBoe+39F04Rqj6nHY4EOS8xnkKSbpo
      VgnoG8avB6Y8zNc+4rUQ50PnlRCztRFZVraqnYBTogOZMnkE3F7OKrEfESIkqYjP
      7OuuPeoWBwKBgGRTI5panFJAu/8nYAIoxUtaOw+dUHcQMJWD7s3el9xNQl2zc+VM
      +um9du6O+ALPONrvHoLRYTRUC1B8uj0nqc9eCFBrWHnw6EeSRXk8JgznVd/RCTcF
      BGdZjeQgFvb2XA5Bw/mfLvLP8x0oFWCjjAWV0ibZautyuu5M/wHfQJEVAoGACi8l
      YpORXcifJzUq1VEs581jf+SHgUgOcX0kW8zH57BNLaBR3yTYKvUpMT80KDNFt9Yc
      2DVsxlkekPi6esCEUgTArtw0nCw3u5/ygNjE+O6D+/cq3bpYaP5lW+hY7FQLB3oB
      ykL/znTfWTEwtva0JCi3cOUwV/t5X74Wbvi393kCgYEA4EcrQXUxqm0ZVCFzWAfN
      SBH1idNVCnXxYoWzfOyDE5NB2Ltgh3GQziWnE9LNRJD6+7mznYeFFdnaW8iOJiUC
      MkPaNcsBX+xSUe7xeMVi6T0bZ9C5cTD671IDDWKmmVsi2SQDfRekLHJgAlVpV3fh
      w05xFSd5LE1aRWQKdoIKkj4=
      """;
  private static final String CERTIFICATE = """
      MIIDHzCCAgegAwIBAgIUTi8VbkKyY+70upn7adUQORJNTh4wDQYJKoZIhvcNAQEL
      BQAwFDESMBAGA1UEAwwJbG9jYWxob3N0MB4XDTI2MDgxMTAzMjI1OFoXDTM2MDgw
      ODAzMjI1OFowFDESMBAGA1UEAwwJbG9jYWxob3N0MIIBIjANBgkqhkiG9w0BAQEF
      AAOCAQ8AMIIBCgKCAQEA9ZhLdYLjsPmaOoPcyYrW97GPVX3Ul4zWFoP+0bkJkD/B
      o0Hlkj2FcwuhepL7nT/8BK2BnkYyeB74NQxICRVgAKqPYQsPn+2oDwrWA5DKQ1M0
      HuXIVK7K7dbzkN0VRSe24WNWmgy9/+rD3sEHLvYhJPWvv+HO1/mxBD1GLOsGv2MC
      +e9Capx7M3wT9xdWKRlyYrn4+87VuQ+3le9EFKj20WbxM0kHZ9r51snV0rg49yvh
      2K9lwG9l1WyHvByYGh89VGkUgq/PVY1ZKT3COYKVds/ezNKuEH2o4bGnEe1npxk3
      XCV3z4JR3ug5saPMwqxcr5e1Qsws9bwW12cqLFKaOwIDAQABo2kwZzAdBgNVHQ4E
      FgQULU468KG+G0KJhOnv2AFjH9jJdJQwHwYDVR0jBBgwFoAULU468KG+G0KJhOnv
      2AFjH9jJdJQwDwYDVR0TAQH/BAUwAwEB/zAUBgNVHREEDTALgglsb2NhbGhvc3Qw
      DQYJKoZIhvcNAQELBQADggEBAHCanrVHJ2lGtOi+zQejAaoSyAiFY0kOOOTwIUvP
      ESB3zpkxxSz1DmFqzFLaIoHIo7vzl50CoDOUcehV2cGxyEHT8U+auV5OUKdlhqlm
      2SncoIO0ZwKYs1TArSzWuEzInop9Ck3s8ULCFC1dkPKyZ3VfJIGxCzk4sIQws1M8
      CFjvt2PHcZBCCFRtZNSanwmH50BF90zPiRfH1rdrkGOT0J8hUnPC3LcE+6Pd/M8q
      KamieRWZViRvhl21gsEdbJu2tuq820JPgddc+4VDNX0EK0xUs/pkTLox2ZgKoEqT
      tCATjY9QDuS4MytFLEq7WFVgI65mYkkbHiWtDxbOZYH6vAc=
      """;
  private static final String WRONG_HOST_CERTIFICATE = """
      MIIDKzCCAhOgAwIBAgIUe9FX2AWa9n3ooNW+v171tU6DvrwwDQYJKoZIhvcNAQEL
      BQAwGDEWMBQGA1UEAwwNbm90LWxvY2FsaG9zdDAeFw0yNjA4MTEwMzMzMjVaFw0z
      NjA4MDgwMzMzMjVaMBgxFjAUBgNVBAMMDW5vdC1sb2NhbGhvc3QwggEiMA0GCSqG
      SIb3DQEBAQUAA4IBDwAwggEKAoIBAQD1mEt1guOw+Zo6g9zJitb3sY9VfdSXjNYW
      g/7RuQmQP8GjQeWSPYVzC6F6kvudP/wErYGeRjJ4Hvg1DEgJFWAAqo9hCw+f7agP
      CtYDkMpDUzQe5chUrsrt1vOQ3RVFJ7bhY1aaDL3/6sPewQcu9iEk9a+/4c7X+bEE
      PUYs6wa/YwL570JqnHszfBP3F1YpGXJiufj7ztW5D7eV70QUqPbRZvEzSQdn2vnW
      ydXSuDj3K+HYr2XAb2XVbIe8HJgaHz1UaRSCr89VjVkpPcI5gpV2z97M0q4Qfajh
      sacR7WenGTdcJXfPglHe6Dmxo8zCrFyvl7VCzCz1vBbXZyosUpo7AgMBAAGjbTBr
      MB0GA1UdDgQWBBQtTjrwob4bQomE6e/YAWMf2Ml0lDAfBgNVHSMEGDAWgBQtTjrw
      ob4bQomE6e/YAWMf2Ml0lDAPBgNVHRMBAf8EBTADAQH/MBgGA1UdEQQRMA+CDW5v
      dC1sb2NhbGhvc3QwDQYJKoZIhvcNAQELBQADggEBAL7ryLok1QVcWMbr2ZlkKG1x
      iW7XHR0dHCRkt8TDpv7tRC4tUKBGgdixxXQmz0k1YvyaVpsAgKpi0aWXHG5KcXbB
      7YvXeyrqWosA+nUePJfzIpXBIL0fnypNWBXxhLvA+iJt3LA1WoXBSQVvmVoV34TQ
      5JQMrhYawmKEbrMWj/e0WXICFHl9mRlcQ6iq0ABbjQCs5+aRCeN/b1SF67ZaPNcN
      IVRTowkrKRmZzReuYC2O5fL+zMd8sGSLT3Hnci0yc7UmSaQgd1EdX1/7+daxcRul
      NCe82N3Ke3/+kJpNvVvqvSY3PyQuSABm4Q17olJ+djC5XnxCxcBc2viCxr3yBII=
      """;

  private TestTlsContexts() {
  }

  static SSLContext server() throws GeneralSecurityException, IOException {
    return server(CERTIFICATE);
  }

  static SSLContext wrongHostnameServer()
      throws GeneralSecurityException, IOException {
    return server(WRONG_HOST_CERTIFICATE);
  }

  private static SSLContext server(String encodedCertificate)
      throws GeneralSecurityException, IOException {
    X509Certificate certificate = certificate(encodedCertificate);
    byte[] encodedKey = Base64.getMimeDecoder().decode(PRIVATE_KEY);
    PrivateKey key;
    try {
      key = KeyFactory.getInstance("RSA")
          .generatePrivate(new PKCS8EncodedKeySpec(encodedKey));
    } finally {
      java.util.Arrays.fill(encodedKey, (byte) 0);
    }
    KeyStore store = KeyStore.getInstance("PKCS12");
    store.load(null, null);
    store.setKeyEntry(
        "server",
        key,
        PASSWORD,
        new Certificate[] {certificate});
    KeyManagerFactory managers = KeyManagerFactory.getInstance(
        KeyManagerFactory.getDefaultAlgorithm());
    managers.init(store, PASSWORD);
    SSLContext context = SSLContext.getInstance("TLSv1.3");
    context.init(managers.getKeyManagers(), null, new SecureRandom());
    return context;
  }

  static SSLContext trustedClient() throws GeneralSecurityException, IOException {
    return trustedClient(CERTIFICATE);
  }

  static SSLContext wrongHostnameClient()
      throws GeneralSecurityException, IOException {
    return trustedClient(WRONG_HOST_CERTIFICATE);
  }

  private static SSLContext trustedClient(String encodedCertificate)
      throws GeneralSecurityException, IOException {
    KeyStore store = KeyStore.getInstance("PKCS12");
    store.load(null, null);
    store.setCertificateEntry("server", certificate(encodedCertificate));
    TrustManagerFactory managers = TrustManagerFactory.getInstance(
        TrustManagerFactory.getDefaultAlgorithm());
    managers.init(store);
    SSLContext context = SSLContext.getInstance("TLSv1.3");
    context.init(null, managers.getTrustManagers(), new SecureRandom());
    return context;
  }

  private static X509Certificate certificate(String source)
      throws GeneralSecurityException {
    byte[] encoded = Base64.getMimeDecoder().decode(source);
    return (X509Certificate) CertificateFactory.getInstance("X.509")
        .generateCertificate(new java.io.ByteArrayInputStream(encoded));
  }
}
