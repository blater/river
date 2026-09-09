package io.riverdb.server.app;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.id.DatabaseIncarnation;
import io.riverdb.engine.EmbeddedLockDiagnosticsConfig;
import io.riverdb.platform.riverd.RiverDaemonFileSystem;
import io.riverdb.platform.riverd.RiverDaemonFileSystemResult;
import io.riverdb.platform.riverd.RiverDaemonFileSystems;
import io.riverdb.platform.riverd.RiverDirectoryResult;
import io.riverdb.platform.riverd.RiverFileResult;
import io.riverdb.platform.riverd.RiverFile;
import io.riverdb.platform.riverd.RiverOpenMode;
import io.riverdb.server.LoopbackServerLimits;
import io.riverdb.server.LoopbackRiverServer;
import java.net.InetAddress;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Foreground start owner for the installed riverd process. */
final class RiverdForeground {
  private RiverdForeground() { }

  static StatusCode run(RiverdCommandResult command, Path home) {
    RiverDaemonPaths.Result paths = new RiverDaemonPaths.Result();
    StatusCode status = RiverDaemonPaths.resolve(command.datadir(), command.readyFile(), home, paths);
    if (!status.isOk()) return status;
    RiverDaemonFileSystemResult filesystemResult = new RiverDaemonFileSystemResult();
    status = RiverDaemonFileSystems.current(filesystemResult);
    if (!status.isOk()) return status;
    RiverDaemonFileSystem filesystem = filesystemResult.fileSystem();
    RiverDaemonResources.Result resources = new RiverDaemonResources.Result();
    status = RiverDaemonResources.compile(command.maximumConnections(), resources);
    if (!status.isOk()) return status;
    status = RiverDaemonPaths.verify(filesystem, paths);
    if (!status.isOk()) return status;
    status = RiverDaemonPaths.ensureParents(filesystem, paths.datadir);
    if (!status.isOk()) return status;
    if (paths.ready != null && paths.ready.getParent() != null) {
      status = RiverDaemonPaths.ensureParents(filesystem, paths.ready.getParent());
      if (!status.isOk()) return status;
    }
    RiverDirectoryResult registryResult = new RiverDirectoryResult();
    status = RiverDaemonPaths.ensureDirectory(filesystem, paths.registry, registryResult);
    if (!status.isOk()) return status;
    status = registryResult.directory().close();
    if (!status.isOk() && status != StatusCode.CLOSED) return status;
    // Parent creation changed the namespace; revalidate every prospective object before
    // identity/database mutation begins.
    status = RiverDaemonPaths.verify(filesystem, paths);
    if (!status.isOk()) return status;

    Probe probe = probeAuthority(filesystem, paths.datadir);
    if (!probe.status.isOk()) return probe.status;
    SecureRandom random = new SecureRandom();
    InetAddress address;
    try {
      address = literalAddress(command.ip());
    } catch (Exception failure) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    RiverDaemonInstance.OpenResult opened = new RiverDaemonInstance.OpenResult();
    RiverDaemonInstance.RestartPreparation preparation = null;
    if (probe.committed) {
      preparation = new RiverDaemonInstance.RestartPreparation();
      status = RiverDaemonInstance.prepareRestart(paths.datadir, filesystem, random,
          resources.request(), EmbeddedLockDiagnosticsConfig.disabled(),
          resources.maximumActiveTransactions(), preparation);
      if (status.isOk()) status = RiverDaemonRuntimeRecords.recoverStale(
          paths.datadir, filesystem, preparation.identity(), paths.registry);
      if (status.isOk()) status = RiverDaemonInstance.openPreparedRestart(
          preparation, random, command.ip(), address, command.port(),
          LoopbackServerLimits.defaults(command.maximumConnections()), opened);
    } else {
      status = RiverDaemonInstance.open(paths.datadir, filesystem, random,
          randomIncarnation(random), command.ip(), address, command.port(),
          LoopbackServerLimits.defaults(command.maximumConnections()), resources.request(),
          EmbeddedLockDiagnosticsConfig.disabled(), resources.maximumActiveTransactions(), opened);
    }
    if (!status.isOk()) {
      if (preparation != null) preparation.close();
      return status;
    }

    RiverDaemonInstance instance = opened.instance();
    RiverDaemonIdentity.IdentityResult identity = opened.identity();
    String certificateSha256 = instance.serverCertificateSha256();
    if (identity == null || certificateSha256 == null) {
      instance.close();
      return StatusCode.IO_FAILURE;
    }
    RiverDaemonRuntimeRecords.Metadata metadata = new RiverDaemonRuntimeRecords.Metadata(
        paths.datadir.toString(), instance.incarnation(), identity.currentOwner(), command.ip(),
        instance.server().port(), instance.credentialGeneration(), RiverDaemonVersion.value(),
        instance.clientConfiguration().toString(), paths.ready);
    Lifecycle lifecycle = new Lifecycle(instance, identity, filesystem, paths.registry, metadata);
    Runtime.getRuntime().addShutdownHook(new Thread(() -> {
      lifecycle.shutdown();
      StatusCode closed = lifecycle.status();
      if (!closed.isOk()) RiverdMain.reportFailure(closed, "riverd shutdown failed");
    }, "riverd-shutdown"));
    status = lifecycle.publish(filesystem, paths, certificateSha256);
    if (!status.isOk()) {
      lifecycle.shutdown();
      return status;
    }
    RiverDaemonReadyOutput.printSummary(metadata, command.maximumConnections(), System.err);
    try {
      while (!lifecycle.stopped()) {
        if (!lifecycle.serverRunning()) {
          lifecycle.shutdown();
          break;
        }
        lifecycle.await(1_000L);
      }
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      lifecycle.shutdown();
    }
    return lifecycle.status();
  }

  private static Probe probeAuthority(RiverDaemonFileSystem filesystem, Path datadir) {
    RiverDirectoryResult directoryResult = new RiverDirectoryResult();
    StatusCode status = filesystem.openDirectory(datadir, directoryResult);
    if (status == StatusCode.CONFLICT) return new Probe(StatusCode.OK, false);
    if (!status.isOk()) return new Probe(status, false);
    RiverFileResult fileResult = new RiverFileResult();
    status = directoryResult.directory().openFile(
        RiverDaemonIdentity.INSTANCE_FILE, RiverOpenMode.EXISTING, fileResult);
    RiverFile openedFile = fileResult.file();
    StatusCode fileClose = openedFile == null ? StatusCode.OK : openedFile.close();
    StatusCode close = directoryResult.directory().close();
    if (status.isOk() && fileClose != StatusCode.OK && fileClose != StatusCode.CLOSED) {
      status = fileClose;
    }
    if (status == StatusCode.CONFLICT) status = StatusCode.OK;
    if (status.isOk() && close != StatusCode.OK && close != StatusCode.CLOSED) status = close;
    return new Probe(status, status.isOk() && fileResult.file() != null);
  }

  private static InetAddress literalAddress(String value) throws Exception {
    byte[] bytes = "::1".equals(value)
        ? new byte[] {0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1}
        : new byte[] {(byte) 127, 0, 0, 1};
    return InetAddress.getByAddress(value, bytes);
  }

  private record Probe(StatusCode status, boolean committed) { }

  private static final class Lifecycle {
    private final RiverDaemonInstance instance;
    private final RiverDaemonIdentity.IdentityResult identity;
    private final RiverDaemonFileSystem filesystem;
    private final Path registry;
    private final RiverDaemonRuntimeRecords.Metadata metadata;
    private final CountDownLatch stopped = new CountDownLatch(1);
    private StatusCode status = StatusCode.OK;

    Lifecycle(RiverDaemonInstance instance, RiverDaemonIdentity.IdentityResult identity,
        RiverDaemonFileSystem filesystem,
        Path registry, RiverDaemonRuntimeRecords.Metadata metadata) {
      this.instance = instance;
      this.identity = identity;
      this.filesystem = filesystem;
      this.registry = registry;
      this.metadata = metadata;
    }

    void await(long millis) throws InterruptedException {
      stopped.await(millis, TimeUnit.MILLISECONDS);
    }

    synchronized StatusCode publish(
        RiverDaemonFileSystem filesystem, RiverDaemonPaths.Result paths, String certificateSha256) {
      if (stopped()) return StatusCode.CANCELLED;
      StatusCode current = instance.checkCredentialValidity();
      if (current.isOk()) current = publishRuntimeAndRegistry(filesystem, paths, identity, metadata);
      if (current.isOk()) current = RiverDaemonReadyOutput.publish(
          filesystem, paths.registry, metadata, certificateSha256, System.out, System.err);
      return current;
    }

    synchronized void shutdown() {
      if (stopped.getCount() == 0) return;
      status = firstFailure(status, instance.closeServices());
      if (instance.servicesClosed()) {
        status = firstFailure(status, RiverDaemonRuntimeRecords.cleanupCurrent(
            filesystem, identity, registry, metadata));
        status = firstFailure(status, instance.close());
      }
      // A nonterminal database close retains its lock and runtime records until process
      // termination. A later process must recover that exact abandoned ownership.
      stopped.countDown();
    }

    boolean stopped() { return stopped.getCount() == 0; }
    synchronized boolean serverRunning() {
      if (stopped()) return false;
      StatusCode validity = instance.checkCredentialValidity();
      if (!validity.isOk()) {
        status = firstFailure(status, validity);
        return false;
      }
      LoopbackRiverServer server = instance.server();
      if (server != null && server.isRunning()) return true;
      status = firstFailure(status, StatusCode.IO_FAILURE);
      return false;
    }
    synchronized StatusCode status() { return status; }
  }

  private static StatusCode publishRuntimeAndRegistry(
      RiverDaemonFileSystem filesystem, RiverDaemonPaths.Result paths,
      RiverDaemonIdentity.IdentityResult identity, RiverDaemonRuntimeRecords.Metadata metadata) {
    StatusCode status = RiverDaemonPaths.verify(filesystem, paths);
    if (status.isOk()) {
      status = RiverDaemonRuntimeRecords.publishRuntime(identity.directory(), metadata);
    }
    if (!status.isOk()) return status;
    RiverDirectoryResult registryResult = new RiverDirectoryResult();
    status = filesystem.openDirectory(paths.registry, registryResult);
    if (!status.isOk()) return status;
    status = RiverDaemonRuntimeRecords.publishRegistry(registryResult.directory(), metadata);
    StatusCode close = registryResult.directory().close();
    return status.isOk() && close != StatusCode.OK && close != StatusCode.CLOSED ? close : status;
  }

  private static DatabaseIncarnation randomIncarnation(SecureRandom random) {
    long high = random.nextLong();
    long low = random.nextLong();
    if (high == 0 && low == 0) low = 1;
    return DatabaseIncarnation.of(high, low);
  }

  private static StatusCode firstFailure(StatusCode first, StatusCode next) {
    return first.isOk() && next != StatusCode.OK && next != StatusCode.CLOSED ? next : first;
  }
}
