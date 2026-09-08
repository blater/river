package io.riverdb.bench.tpcc;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.EmbeddedLockDiagnosticsConfig;
import io.riverdb.platform.riverd.RiverDaemonFileSystemResult;
import io.riverdb.platform.riverd.RiverDaemonFileSystems;
import io.riverdb.server.LoopbackServerLimits;
import io.riverdb.server.app.GeneratedClientFileTestFixture;
import io.riverdb.server.app.RiverDaemonInstance;
import io.riverdb.server.app.RiverDaemonResources;
import java.net.InetAddress;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.ArrayList;

/** Child entry point used by the lifecycle acceptance to exercise a real process restart. */
public final class TpccLifecycleChildMain {
  private TpccLifecycleChildMain() {}

  public static void main(String[] arguments) throws Exception {
    Path root = Path.of(required(arguments, "--root="));
    Path artifact = Path.of(required(arguments, "--artifact="));
    String phase = required(arguments, "--phase=");
    int warmup = Integer.parseInt(required(arguments, "--warmup-seconds="));
    int measured = Integer.parseInt(required(arguments, "--measured-seconds="));
    boolean tiny = has(arguments, "--tiny");

    GeneratedClientFileTestFixture fixture = null;
    RiverDaemonInstance.OpenResult restarted = null;
    Path clientFile;
    if (phase.equals("load-run-checkpoint")) {
      fixture = GeneratedClientFileTestFixture.open(root);
      clientFile = fixture.clientFile();
    } else if (phase.equals("recovery-verify")) {
      restarted = openExisting(root);
      clientFile = restarted.instance().clientConfiguration();
    } else {
      throw new IllegalArgumentException("unknown lifecycle phase: " + phase);
    }
    try {
      ArrayList<String> values = new ArrayList<>();
      values.add("--url=jdbc:river:client-file:" + clientFile);
      values.add("--artifact=" + artifact);
      values.add("--phase=" + phase);
      values.add("--warmup-seconds=" + warmup);
      values.add("--measured-seconds=" + measured);
      String jfr = System.getenv("RIVER_TPCC_TEST_JFR");
      if (jfr != null && phase.equals("load-run-checkpoint")) values.add("--jfr=" + jfr);
      if (tiny) {
        values.add("--tiny");
        values.add("--scheduling=no-wait-stress");
      }
      TpccAcceptanceMain.main(values.toArray(String[]::new));
    } finally {
      StatusCode status = fixture == null ? restarted.close() : fixture.close();
      if (!status.isOk()) throw new IllegalStateException("close lifecycle fixture: " + status);
    }
  }

  private static RiverDaemonInstance.OpenResult openExisting(Path root) throws Exception {
    RiverDaemonFileSystemResult filesystem = new RiverDaemonFileSystemResult();
    require(RiverDaemonFileSystems.current(filesystem), "select lifecycle filesystem");
    RiverDaemonResources.Result resources = new RiverDaemonResources.Result();
    require(RiverDaemonResources.compile(16, resources), "compile lifecycle resources");
    RiverDaemonInstance.RestartPreparation preparation =
        new RiverDaemonInstance.RestartPreparation();
    SecureRandom random = new SecureRandom();
    StatusCode status = RiverDaemonInstance.prepareRestart(
        root.toRealPath().resolve("instance"), filesystem.fileSystem(), random,
        resources.request(), EmbeddedLockDiagnosticsConfig.disabled(),
        resources.maximumActiveTransactions(), preparation);
    if (!status.isOk()) throw new IllegalStateException("prepare lifecycle restart: " + status);
    RiverDaemonInstance.OpenResult result = new RiverDaemonInstance.OpenResult();
    status = RiverDaemonInstance.openPreparedRestart(
        preparation, random, "127.0.0.1", InetAddress.getByName("127.0.0.1"), 0,
        LoopbackServerLimits.defaults(16), result);
    if (!status.isOk()) {
      preparation.close();
      throw new IllegalStateException("open lifecycle restart: " + status);
    }
    return result;
  }

  private static void require(StatusCode status, String operation) {
    if (status == null || !status.isOk()) {
      throw new IllegalStateException(operation + ": " + status);
    }
  }

  private static String required(String[] arguments, String prefix) {
    for (String argument : arguments) {
      if (argument.startsWith(prefix)) return argument.substring(prefix.length());
    }
    throw new IllegalArgumentException("missing " + prefix);
  }

  private static boolean has(String[] arguments, String value) {
    for (String argument : arguments) {
      if (argument.equals(value)) return true;
    }
    return false;
  }
}
