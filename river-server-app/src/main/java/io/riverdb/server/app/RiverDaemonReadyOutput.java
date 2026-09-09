package io.riverdb.server.app;

import io.riverdb.base.error.StatusCode;
import io.riverdb.platform.riverd.RiverDaemonFileSystem;
import io.riverdb.protocol.ProtocolFrameCodec;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

/** Owns the single readiness observation and its optional stdout mirror. */
final class RiverDaemonReadyOutput {
  private static final byte[] READY = "riverd_status=ready\n".getBytes(StandardCharsets.UTF_8);

  private RiverDaemonReadyOutput() { }

  static StatusCode publish(
      RiverDaemonFileSystem filesystem,
      Path registryRoot,
      RiverDaemonRuntimeRecords.Metadata metadata,
      String certificateSha256,
      PrintStream out,
      PrintStream err) {
    Path datadir = Path.of(metadata.datadir);
    String prefix = "riverd_datadir=" + metadata.datadir + "\n"
        + "riverd_data=" + datadir.resolve("database") + "\n"
        + "riverd_identity=" + datadir.resolve("instance.properties") + "\n"
        + "riverd_runtime_file=" + datadir.resolve("runtime.properties") + "\n"
        + "riverd_registry_record="
        + registryRoot.resolve(RiverDaemonRuntimeRecords.registryName(metadata.datadir)) + "\n"
        + "riverd_listen_address=" + metadata.listenAddress + "\n"
        + "riverd_listen_port=" + metadata.listenPort + "\n"
        + "riverd_pid=" + metadata.owner.pid + "\n"
        + "riverd_protocol=river-v" + ProtocolFrameCodec.VERSION + "\n"
        + "riverd_transport=tls-v1.3\n"
        + "riverd_client_config=" + metadata.clientConfig + "\n"
        + "riverd_server_certificate_sha256=" + certificateSha256 + "\n";
    byte[] prefixBytes = prefix.getBytes(StandardCharsets.UTF_8);
    boolean fileMode = !"none".equals(metadata.readyFile);
    if (fileMode) {
      StatusCode status = RiverDaemonRuntimeRecords.publishReady(
          filesystem, registryRoot, metadata, Path.of(metadata.readyFile), certificateSha256);
      // Publication may already be visible on failure. Never continue serving after a
      // durability error, and never try to retract an observed readiness commitment.
      if (!status.isOk()) return status;
    }
    out.write(prefixBytes, 0, prefixBytes.length);
    out.flush();
    if (!out.checkError()) {
      // One final bounded record. A subsequent output error cannot tell us whether a
      // consumer observed this line; the caller still shuts down with IO_FAILURE.
      out.write(READY, 0, READY.length);
      out.flush();
    }
    if (!out.checkError()) return StatusCode.OK;
    if (!fileMode) return StatusCode.IO_FAILURE;
    err.println("riverd: readiness file published; stdout mirror could not be written.");
    return StatusCode.OK;
  }

  static void printSummary(
      RiverDaemonRuntimeRecords.Metadata metadata, int maximumConnections, PrintStream err) {
    String endpoint = new RiverDaemonEndpoint(metadata.listenAddress, metadata.listenPort).toString();
    err.println("River server ready");
    err.println("  data directory: " + metadata.datadir);
    err.println("  endpoint: " + endpoint);
    err.println("  stop: river stop " + endpoint);
    err.println("  client configuration: " + metadata.clientConfig);
    err.println("  maximum connections: " + maximumConnections);
    err.flush();
  }
}
