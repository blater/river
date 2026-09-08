package io.riverdb.server.app;

import io.riverdb.base.error.StatusCode;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.Enumeration;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSessionContext;

/**
 * Launcher-owned TLS context construction and post-listener JSSE cleanup.
 *
 * <p>The credential owner retains the private key and destroys it with the
 * authenticator. This owner clears all temporary keystore and JSSE session
 * references that it creates.
 */
final class RiverDaemonTlsContext {
  private static final String KEY_ALIAS = "riverd-server";
  private static final int PASSWORD_LENGTH = 32;

  private RiverDaemonTlsContext() {
  }

  static StatusCode create(
      RiverDaemonCredentials.Material material,
      java.security.SecureRandom random,
      TlsContextResult result) {
    if (result == null) return StatusCode.INVALID_EXTERNAL_INPUT;
    result.reset();
    if (material == null || random == null || material.privateKey() == null
        || material.certificate() == null) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    char[] password = new char[PASSWORD_LENGTH];
    KeyStore keyStore = null;
    KeyManagerFactory keyManagers = null;
    try {
      fillPassword(password, random);
      keyStore = KeyStore.getInstance("PKCS12");
      keyStore.load(null, password);
      PrivateKey privateKey = material.privateKey();
      X509Certificate certificate = material.certificate();
      keyStore.setKeyEntry(KEY_ALIAS, privateKey, password, new X509Certificate[] {certificate});
      keyManagers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
      keyManagers.init(keyStore, password);
      SSLContext context = SSLContext.getInstance("TLSv1.3");
      context.init(keyManagers.getKeyManagers(), null, random);
      result.complete(context);
      return StatusCode.OK;
    } catch (Exception failure) {
      result.reset();
      return StatusCode.INVARIANT_BROKEN;
    } finally {
      Arrays.fill(password, '\0');
      keyManagers = null;
      keyStore = null;
    }
  }

  /**
   * Invalidates all sessions visible through this context and releases the
   * caller-owned context reference. The caller destroys credential material
   * after this method and after the listener workers have stopped.
   */
  static StatusCode cleanup(TlsContextResult result) {
    if (result == null) return StatusCode.INVALID_EXTERNAL_INPUT;
    SSLContext context = result.context;
    result.context = null;
    if (context == null) return StatusCode.OK;
    StatusCode status = StatusCode.OK;
    try {
      SSLSessionContext sessions = context.getServerSessionContext();
      Enumeration<byte[]> ids = sessions.getIds();
      while (ids.hasMoreElements()) {
        byte[] id = ids.nextElement();
        try {
          SSLSession session = sessions.getSession(id);
          if (session != null) session.invalidate();
        } catch (RuntimeException failure) {
          if (status.isOk()) status = StatusCode.IO_FAILURE;
        } finally {
          if (id != null) Arrays.fill(id, (byte) 0);
        }
      }
    } catch (RuntimeException failure) {
      status = StatusCode.IO_FAILURE;
    }
    return status;
  }

  private static void fillPassword(char[] password, java.security.SecureRandom random) {
    final char[] alphabet =
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789".toCharArray();
    for (int index = 0; index < password.length; index++) {
      password[index] = alphabet[random.nextInt(alphabet.length)];
    }
    Arrays.fill(alphabet, '\0');
  }

  static final class TlsContextResult {
    private SSLContext context;

    void reset() {
      context = null;
    }

    void complete(SSLContext opened) {
      context = opened;
    }

    SSLContext context() {
      return context;
    }
  }
}
