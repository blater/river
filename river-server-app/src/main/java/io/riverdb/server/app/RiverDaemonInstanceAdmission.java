package io.riverdb.server.app;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.id.DatabaseIncarnation;
import io.riverdb.engine.EmbeddedLockDiagnosticsConfig;
import io.riverdb.engine.runtime.DatabaseResourcePlanRequest;
import io.riverdb.platform.riverd.RiverDaemonFileSystem;
import io.riverdb.server.LoopbackServerLimits;
import java.net.InetAddress;
import java.nio.file.Path;
import java.security.SecureRandom;

/** Owns identity admission and handoff around one instance lifetime owner. */
final class RiverDaemonInstanceAdmission {
  private RiverDaemonInstanceAdmission() { }

  static StatusCode open(
      Path datadir, RiverDaemonFileSystem filesystem, SecureRandom random,
      DatabaseIncarnation requestedIncarnation, String host, InetAddress bindAddress,
      int port, LoopbackServerLimits limits, DatabaseResourcePlanRequest resourcePlan,
      EmbeddedLockDiagnosticsConfig lockDiagnostics, int maximumActiveTransactions,
      RiverDaemonInstance.OpenResult result) {
    if (result == null || datadir == null || filesystem == null || random == null
        || host == null || bindAddress == null || limits == null || resourcePlan == null
        || lockDiagnostics == null || maximumActiveTransactions <= 0) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    result.reset();
    ProcessMetadata process = ProcessMetadata.read();
    if (process == null) return StatusCode.FEATURE_NOT_SUPPORTED;
    if (requestedIncarnation == null || !requestedIncarnation.isValid()) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    RiverDaemonIdentity.IdentityResult identity = new RiverDaemonIdentity.IdentityResult();
    StatusCode status = RiverDaemonIdentity.beginCreate(
        datadir, filesystem, requestedIncarnation, random, process.pid,
        process.startMillis, identity);
    if (!status.isOk()) return status;
    RiverDaemonInstance state = new RiverDaemonInstance(identity, datadir);
    status = RiverDaemonInstanceStartup.openCreate(state, random, host, bindAddress, port, limits,
        resourcePlan, lockDiagnostics, maximumActiveTransactions);
    if (!status.isOk()) {
      state.close();
      return status;
    }
    return result.complete(state);
  }

  static StatusCode prepareRestart(
      Path datadir, RiverDaemonFileSystem filesystem, SecureRandom random,
      DatabaseResourcePlanRequest resourcePlan, EmbeddedLockDiagnosticsConfig lockDiagnostics,
      int maximumActiveTransactions, RiverDaemonInstance.RestartPreparation result) {
    if (result == null || datadir == null || filesystem == null || random == null
        || resourcePlan == null || lockDiagnostics == null || maximumActiveTransactions <= 0) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    result.reset();
    ProcessMetadata process = ProcessMetadata.read();
    if (process == null) return StatusCode.FEATURE_NOT_SUPPORTED;
    RiverDaemonIdentity.IdentityResult identity = new RiverDaemonIdentity.IdentityResult();
    StatusCode status = RiverDaemonIdentity.openExisting(
        datadir, filesystem, random, process.pid, process.startMillis, identity);
    if (status.isOk()) {
      RiverDaemonInstance state = new RiverDaemonInstance(identity, datadir);
      status = RiverDaemonInstanceStartup.validateRestart(
          state, resourcePlan, lockDiagnostics, maximumActiveTransactions);
      if (status.isOk()) result.complete(state);
      else state.close();
    }
    return status;
  }

  static StatusCode openPreparedRestart(
      RiverDaemonInstance.RestartPreparation preparation, SecureRandom random, String host,
      InetAddress bindAddress, int port, LoopbackServerLimits limits,
      RiverDaemonInstance.OpenResult result) {
    RiverDaemonInstance state = preparation == null ? null : preparation.state();
    if (preparation == null || result == null || random == null || host == null
        || bindAddress == null || limits == null || state == null) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    result.reset();
    StatusCode status = RiverDaemonIdentity.handoffOwner(state.identity);
    if (!status.isOk()) {
      state.close();
      preparation.clear();
      return status;
    }
    status = RiverDaemonInstanceStartup.openListener(state, random, host, bindAddress, port, limits);
    if (!status.isOk()) {
      state.close();
      preparation.clear();
      return status;
    }
    preparation.clear();
    return result.complete(state);
  }

  private record ProcessMetadata(long pid, long startMillis) {
    static ProcessMetadata read() {
      ProcessHandle.Info info = ProcessHandle.current().info();
      if (ProcessHandle.current().pid() <= 0 || info.startInstant().isEmpty()) return null;
      return new ProcessMetadata(
          ProcessHandle.current().pid(), info.startInstant().get().toEpochMilli());
    }
  }
}
