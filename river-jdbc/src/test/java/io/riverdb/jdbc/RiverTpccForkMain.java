package io.riverdb.jdbc;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.EmbeddedLockDiagnosticsConfig;
import io.riverdb.platform.riverd.RiverDaemonFileSystemResult;
import io.riverdb.platform.riverd.RiverDaemonFileSystems;
import io.riverdb.server.LoopbackServerLimits;
import io.riverdb.server.app.RiverDaemonInstance;
import io.riverdb.server.app.RiverDaemonResources;
import io.riverdb.server.app.GeneratedClientFileTestFixture;
import java.net.InetAddress;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.DriverManager;

/** One-purpose child process for the TPCC committed-instance restart probes. */
public final class RiverTpccForkMain {
  private RiverTpccForkMain() {}

  public static void main(String[] args) throws Exception {
    if (args.length != 2) throw new IllegalArgumentException("datadir and probe mode required");
    if ("create-recovery".equals(args[1])) {
      GeneratedClientFileTestFixture fixture =
          GeneratedClientFileTestFixture.open(Path.of(args[0]).toRealPath());
      try {
        RiverTpccJdbcAcceptanceTest.runTransactionFamilies(fixture.clientFile());
      } finally {
        require(fixture.close(), "close initial recovery owner");
      }
      return;
    }
    if ("create-lock".equals(args[1])) {
      GeneratedClientFileTestFixture fixture =
          GeneratedClientFileTestFixture.open(Path.of(args[0]).toRealPath());
      require(fixture.close(), "close initial lock owner");
      return;
    }
    Path datadir = Path.of(args[0]).toRealPath();
    RiverDaemonFileSystemResult filesystem = new RiverDaemonFileSystemResult();
    require(RiverDaemonFileSystems.current(filesystem), "select filesystem");
    RiverDaemonResources.Result resources = new RiverDaemonResources.Result();
    require(RiverDaemonResources.compile(16, resources), "admit resources");
    RiverDaemonInstance.RestartPreparation preparation =
        new RiverDaemonInstance.RestartPreparation();
    SecureRandom random = new SecureRandom();
    require(RiverDaemonInstance.prepareRestart(
        datadir, filesystem.fileSystem(), random, resources.request(),
        EmbeddedLockDiagnosticsConfig.disabled(), resources.maximumActiveTransactions(),
        preparation), "prepare restart");
    RiverDaemonInstance.OpenResult opened = new RiverDaemonInstance.OpenResult();
    StatusCode status = RiverDaemonInstance.openPreparedRestart(
        preparation, random, "127.0.0.1", InetAddress.getByName("127.0.0.1"), 0,
        LoopbackServerLimits.defaults(16), opened);
    if (!status.isOk()) {
      preparation.close();
      throw new IllegalStateException("open restart: " + status);
    }
    try {
      Path clientFile = opened.instance().clientConfiguration();
      try (Connection connection = DriverManager.getConnection(
          RiverDriver.CLIENT_FILE_PREFIX + clientFile)) {
        if ("recovery".equals(args[1])) {
          RiverTpccJdbcAcceptanceTest.assertBusinessInvariants(connection);
          RiverTpccJdbcAcceptanceTest.stockLevel(connection);
        } else if ("lock-timeout".equals(args[1])) {
          // The runtime properties were written to the committed database before this process.
          RiverTpccJdbcAcceptanceTest.runLockWaitTimeout(clientFile);
        } else {
          throw new IllegalArgumentException("unknown probe mode: " + args[1]);
        }
      }
    } finally {
      require(opened.close(), "close restart");
    }
  }

  private static void require(StatusCode status, String operation) {
    if (status == null || !status.isOk()) {
      throw new IllegalStateException(operation + ": " + status);
    }
  }
}
