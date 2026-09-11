package io.riverdb.server.app;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.id.DatabaseIncarnation;
import io.riverdb.engine.api.SessionPermissions;
import io.riverdb.platform.riverd.RiverDirectory;
import io.riverdb.protocol.auth.TokenAuthenticator;
import io.riverdb.protocol.auth.TokenAuthenticatorOpenResult;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.Arrays;
import javax.security.auth.DestroyFailedException;
import javax.security.auth.Destroyable;
import org.bouncycastle.jce.provider.BouncyCastleProvider;

/** Generates and validates one launcher-owned credential generation. */
public final class RiverDaemonCredentials {
  public static final int TOKEN_BYTES = 32;

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
      X509Certificate certificate = RiverDaemonCredentialCertificate.create(
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
    return RiverDaemonCredentialCertificate.validate(material, incarnation, createdAt);
  }

  /** Writes a complete generation beneath a staged security directory. */
  static StatusCode persist(
      Material material,
      RiverDirectory security,
      DatabaseIncarnation incarnation,
      Instant createdAt) {
    return RiverDaemonCredentialStorage.persist(material, security, incarnation, createdAt);
  }

  /** Loads, validates, and reconstructs the current generation from a verified security handle. */
  static StatusCode load(
      RiverDirectory security,
      DatabaseIncarnation incarnation,
      CredentialResult result) {
    return RiverDaemonCredentialStorage.load(security, incarnation, result);
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
    return RiverDaemonCredentialClientConfiguration.publish(
        material, security, securityPath, incarnation, host, port, ownerNonce);
  }

  static final class BytesResult {
    private byte[] value;

    byte[] value() {
      return value;
    }

    void set(byte[] value) {
      this.value = value;
    }

    void clear() {
      if (value != null) Arrays.fill(value, (byte) 0);
      value = null;
    }
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

    byte[] token() {
      return token;
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

    void setAuthenticator(TokenAuthenticator authenticator) {
      this.authenticator = authenticator;
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
