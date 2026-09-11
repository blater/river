package io.riverdb.server.app;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.id.DatabaseIncarnation;
import io.riverdb.platform.file.DirectoryOperationResult;
import io.riverdb.platform.riverd.RiverDirectory;
import io.riverdb.platform.riverd.RiverDirectoryResult;
import java.time.Instant;
import java.util.Arrays;

/** Owns generation directory I/O, acquired handles, and cleanup precedence. */
final class RiverDaemonCredentialStorage {
  static final String SECURITY_FILE = "security.properties";
  static final String GENERATIONS_DIRECTORY = "generations";

  private RiverDaemonCredentialStorage() {
  }

  static StatusCode persist(
      RiverDaemonCredentials.Material material,
      RiverDirectory security,
      DatabaseIncarnation incarnation,
      Instant createdAt) {
    if (material == null || security == null || incarnation == null || createdAt == null
        || !incarnation.isValid() || material.token() == null
        || material.token().length != RiverDaemonCredentials.TOKEN_BYTES
        || material.privateKey() == null || material.certificate() == null) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    byte[] privateKey = null;
    byte[] certificate = null;
    StatusCode status = StatusCode.OK;
    try {
      privateKey = material.privateKey().getEncoded();
      certificate = material.certificate().getEncoded();
      if (privateKey == null
          || privateKey.length > RiverDaemonCredentialCertificate.PRIVATE_KEY_MAX_BYTES
          || certificate == null
          || certificate.length > RiverDaemonCredentialCertificate.CERTIFICATE_MAX_BYTES) {
        status = StatusCode.CORRUPTION;
      } else {
        String generationName = Long.toString(material.generation());
        status = persistGeneration(
            security, material, incarnation, generationName, privateKey, certificate);
      }
    } catch (Exception failure) {
      status = StatusCode.INVARIANT_BROKEN;
    } finally {
      if (privateKey != null) Arrays.fill(privateKey, (byte) 0);
      if (certificate != null) Arrays.fill(certificate, (byte) 0);
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

  private static StatusCode persistGeneration(
      RiverDirectory security,
      RiverDaemonCredentials.Material material,
      DatabaseIncarnation incarnation,
      String generationName,
      byte[] privateKey,
      byte[] certificate) {
    RiverDirectory generations = null;
    RiverDirectory generation = null;
    StatusCode status = StatusCode.OK;
    try {
      RiverDirectoryResult generationsResult = new RiverDirectoryResult();
      status = security.createDirectory(GENERATIONS_DIRECTORY, generationsResult);
      if (status.isOk()) {
        generations = generationsResult.directory();
        RiverDirectoryResult generationResult = new RiverDirectoryResult();
        status = generations.createDirectory(generationName, generationResult);
        if (status.isOk()) {
          generation = generationResult.directory();
          status = writeGeneration(
              security, generations, generation, material, incarnation, generationName,
              privateKey, certificate);
        }
      }
    } catch (Exception failure) {
      status = StatusCode.INVARIANT_BROKEN;
    } finally {
      if (generation != null) status = closeDirectory(status, generation);
      if (generations != null) status = closeDirectory(status, generations);
    }
    return status;
  }

  private static StatusCode writeGeneration(
      RiverDirectory security,
      RiverDirectory generations,
      RiverDirectory generation,
      RiverDaemonCredentials.Material material,
      DatabaseIncarnation incarnation,
      String generationName,
      byte[] privateKey,
      byte[] certificate) throws Exception {
    StatusCode status = RiverDaemonCredentialFiles.writeChild(
        generation, "token.bin", material.token());
    if (!status.isOk()) return status;
    status = RiverDaemonCredentialFiles.writeChild(
        generation, "server-private-key.pkcs8", privateKey);
    if (!status.isOk()) return status;
    status = RiverDaemonCredentialFiles.writeChild(
        generation, "server-certificate.der", certificate);
    if (!status.isOk()) return status;
    DirectoryOperationResult forced = new DirectoryOperationResult();
    status = generation.force(forced);
    if (!status.isOk()) return status;
    forced = new DirectoryOperationResult();
    status = generations.force(forced);
    if (!status.isOk()) return status;
    byte[] manifestBytes = RiverDaemonCredentialManifest.encode(
        material, incarnation, generationName, privateKey, certificate);
    try {
      return RiverDaemonCredentialFiles.writeChild(
          security, SECURITY_FILE, manifestBytes);
    } finally {
      Arrays.fill(manifestBytes, (byte) 0);
    }
  }

  static StatusCode load(
      RiverDirectory security,
      DatabaseIncarnation incarnation,
      RiverDaemonCredentials.CredentialResult result) {
    if (security == null || incarnation == null || !incarnation.isValid() || result == null) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    result.reset();
    RiverDaemonCredentialLoadState state =
        new RiverDaemonCredentialLoadState(security, incarnation);
    StatusCode status = state.read();
    status = state.close(status);
    if (status.isOk()) {
      if (state.opened() == null) {
        status = StatusCode.CORRUPTION;
      } else {
        status = result.complete(state.opened());
        if (status.isOk()) state.discardOpened();
      }
    }
    if (state.opened() != null) state.opened().destroy();
    state.clear();
    return status;
  }

  static StatusCode closeDirectory(StatusCode status, RiverDirectory directory) {
    try {
      StatusCode close = directory.close();
      return status.isOk() && !close.isOk() && close != StatusCode.CLOSED ? close : status;
    } catch (RuntimeException failure) {
      return status.isOk() ? StatusCode.INVARIANT_BROKEN : status;
    }
  }

}
