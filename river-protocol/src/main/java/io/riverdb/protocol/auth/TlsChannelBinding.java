package io.riverdb.protocol.auth;

import io.riverdb.base.error.StatusCode;
import java.util.Arrays;
import javax.net.ssl.ExtendedSSLSession;
import javax.net.ssl.SSLKeyException;
import javax.net.ssl.SSLSession;

/** Exports matching TLS 1.3 keying material on the client and server endpoints. */
public final class TlsChannelBinding {
  public static final int BINDING_BYTES = 32;
  private static final String LABEL = "EXPORTER-River-Authentication";

  private TlsChannelBinding() {
  }

  public static StatusCode export(SSLSession session, byte[] output) {
    if (!(session instanceof ExtendedSSLSession extended)
        || output == null
        || output.length < BINDING_BYTES) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    Arrays.fill(output, 0, BINDING_BYTES, (byte) 0);
    byte[] exported = null;
    try {
      exported = extended.exportKeyingMaterialData(LABEL, null, BINDING_BYTES);
      if (exported == null || exported.length != BINDING_BYTES) {
        return StatusCode.CORRUPTION;
      }
      System.arraycopy(exported, 0, output, 0, BINDING_BYTES);
      return StatusCode.OK;
    } catch (SSLKeyException failure) {
      return StatusCode.IO_FAILURE;
    } finally {
      if (exported != null) {
        Arrays.fill(exported, (byte) 0);
      }
    }
  }
}
