package io.riverdb.server.app;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.EmbeddedLockDiagnosticsConfig;
import io.riverdb.base.id.DatabaseIncarnation;
import io.riverdb.platform.riverd.RiverDaemonFileSystemResult;
import io.riverdb.platform.riverd.RiverDaemonFileSystems;
import io.riverdb.server.LoopbackServerLimits;
import java.io.IOException;
import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;

/** Real authenticated instance owner for client-file CLI/JDBC tests. */
public final class GeneratedClientFileTestFixture {
  private static final DatabaseIncarnation INCARNATION =
      DatabaseIncarnation.of(0x5445535446495854L, 0x5552453030303031L);

  private final RiverDaemonInstance.OpenResult opened;

  private GeneratedClientFileTestFixture(RiverDaemonInstance.OpenResult opened) {
    this.opened = opened;
  }

  public static GeneratedClientFileTestFixture open(Path root) throws IOException {
    if (root == null) throw new IOException("missing test root");
    RiverDaemonFileSystemResult filesystem = new RiverDaemonFileSystemResult();
    StatusCode status = RiverDaemonFileSystems.current(filesystem);
    if (!status.isOk()) throw new IOException("select riverd filesystem: " + status);
    RiverDaemonResources.Result resources = new RiverDaemonResources.Result();
    status = RiverDaemonResources.compile(16, resources);
    if (!status.isOk()) throw new IOException("admit authenticated test resources: " + status);
    Path datadir = root.toRealPath().resolve("instance");
    SecureRandom random = new SecureRandom();
    RiverDaemonInstance.OpenResult opened = new RiverDaemonInstance.OpenResult();
    status = RiverDaemonInstance.open(
        datadir, filesystem.fileSystem(), random,
        INCARNATION, "127.0.0.1", InetAddress.getByName("127.0.0.1"), 0,
        LoopbackServerLimits.defaults(16), resources.request(),
        EmbeddedLockDiagnosticsConfig.disabled(), resources.maximumActiveTransactions(), opened);
    if (!status.isOk()) throw new IOException("open authenticated test instance: " + status);
    return new GeneratedClientFileTestFixture(opened);
  }

  public Path clientFile() {
    return opened.instance().clientConfiguration();
  }

  public RiverDaemonInstance instance() {
    return opened.instance();
  }

  /** Replaces only the owned token bytes so the generated config still authenticates via its real path. */
  public void replaceTokenWithWrongValue() throws IOException {
    String record = Files.readString(clientFile());
    String prefix = "token-file=";
    Path token = null;
    for (String line : record.split("\\n")) {
      if (line.startsWith(prefix)) {
        token = Path.of(line.substring(prefix.length()));
        break;
      }
    }
    if (token == null) throw new IOException("generated client record has no token path");
    Files.write(token, new byte[RiverDaemonCredentials.TOKEN_BYTES]);
  }

  public StatusCode close() {
    return opened.close();
  }
}
