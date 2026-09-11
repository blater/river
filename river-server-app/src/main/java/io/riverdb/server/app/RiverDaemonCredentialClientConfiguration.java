package io.riverdb.server.app;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.id.DatabaseIncarnation;
import io.riverdb.platform.file.DirectoryOperationResult;
import io.riverdb.platform.riverd.RiverDirectory;
import io.riverdb.platform.riverd.RiverFile;
import io.riverdb.platform.riverd.RiverFileResult;
import io.riverdb.platform.riverd.RiverOpenMode;
import java.nio.charset.StandardCharsets;

/** Publishes the listener-bound client discovery record under the instance lock. */
final class RiverDaemonCredentialClientConfiguration {
  private static final String SECURITY_CLIENT_FILE = "client.properties";

  private RiverDaemonCredentialClientConfiguration() {
  }

  static StatusCode publish(
      RiverDaemonCredentials.Material material,
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
        || material.certificate() == null || material.token() == null
        || material.token().length != RiverDaemonCredentials.TOKEN_BYTES) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    byte[] certificate = null;
    byte[] recordBytes = null;
    RiverFile stage = null;
    StatusCode status = StatusCode.OK;
    try {
      certificate = material.certificate().getEncoded();
      String generation = Long.toString(material.generation());
      String certificatePath = securityPath.toAbsolutePath().normalize().resolve(
          "generations").resolve(generation).resolve("server-certificate.der").toString();
      String tokenPath = securityPath.toAbsolutePath().normalize().resolve(
          "generations").resolve(generation).resolve("token.bin").toString();
      String body = RiverDaemonIdentityRecords.record(java.util.List.of(
          "format=riverd-client-v1",
          "database-incarnation-high=" + incarnation.high(),
          "database-incarnation-low=" + incarnation.low(),
          "credential-generation=" + material.generation(),
          "principal-id=1",
          "transport=tls-v1.3",
          "protocol=river-v5",
          "host=" + host,
          "port=" + port,
          "server-certificate-file=" + certificatePath,
          "server-certificate-sha256=" + RiverDaemonCredentialManifest.hexDigest(certificate),
          "token-file=" + tokenPath));
      recordBytes = body.getBytes(StandardCharsets.UTF_8);
      String stageName = ".client-" + ownerNonce + ".stage";
      RiverFileResult stageResult = new RiverFileResult();
      status = security.openFile(stageName, RiverOpenMode.CREATE_NEW, stageResult);
      if (status.isOk()) {
        stage = stageResult.file();
        status = RiverDaemonCredentialFiles.write(stage, recordBytes);
        boolean targetExists = false;
        if (status.isOk()) {
          RiverFileResult target = new RiverFileResult();
          StatusCode targetStatus = security.openFile(
              SECURITY_CLIENT_FILE, RiverOpenMode.EXISTING, target);
          if (targetStatus.isOk()) {
            targetExists = true;
            StatusCode targetClose = target.file().close();
            if (!targetClose.isOk() && targetClose != StatusCode.CLOSED) {
              status = targetClose;
            }
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
      }
    } catch (Exception failure) {
      status = StatusCode.INVARIANT_BROKEN;
    } finally {
      if (stage != null) {
        StatusCode close = stage.close();
        if (status.isOk() && !close.isOk() && close != StatusCode.CLOSED) status = close;
      }
      if (certificate != null) java.util.Arrays.fill(certificate, (byte) 0);
      if (recordBytes != null) java.util.Arrays.fill(recordBytes, (byte) 0);
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

  private static boolean validHost(String value) {
    return "localhost".equals(value) || "127.0.0.1".equals(value) || "::1".equals(value);
  }
}
