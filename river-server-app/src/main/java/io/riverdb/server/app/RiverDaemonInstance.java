package io.riverdb.server.app;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.id.DatabaseIncarnation;
import io.riverdb.engine.EmbeddedLockDiagnosticsConfig;
import io.riverdb.engine.api.RiverDatabase;
import io.riverdb.engine.runtime.DatabaseResourcePlanRequest;
import io.riverdb.platform.riverd.RiverDaemonFileSystem;
import io.riverdb.platform.riverd.RiverDirectory;
import io.riverdb.server.CredentialValidityFence;
import io.riverdb.server.LoopbackRiverServer;
import io.riverdb.server.LoopbackServerLimits;
import java.net.InetAddress;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.security.MessageDigest;
import java.util.HexFormat;

/**
 * The small installed-daemon composition owner.  The caller chooses first-create versus restart;
 * identity has no absent-path status which would make probing that choice safe here.
 */
public final class RiverDaemonInstance {
  protected RiverDaemonIdentity.IdentityResult identity;
  protected Path datadir;
  protected RiverDatabase database;
  protected RiverDaemonCredentials.Material material;
  protected LoopbackRiverServer server;
  protected CredentialValidityFence validityFence;
  protected RiverDaemonTlsContext.TlsContextResult tls;
  protected RiverDirectory databaseDirectory;
  protected RiverDirectory securityDirectory;
  protected Path clientConfiguration;
  private boolean closed;
  private boolean servicesClosed;

  RiverDaemonInstance(
      RiverDaemonIdentity.IdentityResult identity, Path datadir) {
    this.identity = identity;
    this.datadir = datadir;
  }

  /** Opens a first-created authenticated instance. */
  public static StatusCode open(
      Path datadir, RiverDaemonFileSystem filesystem, SecureRandom random,
      DatabaseIncarnation requestedIncarnation, String host, InetAddress bindAddress,
      int port, LoopbackServerLimits limits, DatabaseResourcePlanRequest resourcePlan,
      EmbeddedLockDiagnosticsConfig lockDiagnostics, int maximumActiveTransactions,
      OpenResult result) {
    return RiverDaemonInstanceAdmission.open(datadir, filesystem, random, requestedIncarnation,
        host, bindAddress, port, limits, resourcePlan, lockDiagnostics,
        maximumActiveTransactions, result);
  }

  /** Opens and locks the validated identity before stale metadata recovery. */
  public static StatusCode prepareRestart(
      Path datadir, RiverDaemonFileSystem filesystem, SecureRandom random,
      DatabaseResourcePlanRequest resourcePlan, EmbeddedLockDiagnosticsConfig lockDiagnostics,
      int maximumActiveTransactions, RestartPreparation result) {
    return RiverDaemonInstanceAdmission.prepareRestart(datadir, filesystem, random, resourcePlan,
        lockDiagnostics, maximumActiveTransactions, result);
  }

  /** Completes a prepared restart after stale metadata recovery. */
  public static StatusCode openPreparedRestart(
      RestartPreparation preparation, SecureRandom random, String host, InetAddress bindAddress,
      int port, LoopbackServerLimits limits, OpenResult result) {
    return RiverDaemonInstanceAdmission.openPreparedRestart(
        preparation, random, host, bindAddress, port, limits, result);
  }

  /**
   * Opens a first-created authenticated instance.  Restart uses
   * {@link #prepareRestart} followed by {@link #openPreparedRestart}, allowing the outer runtime
   * owner to recover stale metadata while the validated instance lock remains held.
   */


  public RiverDatabase database() { return database; }
  public LoopbackRiverServer server() { return server; }
  public DatabaseIncarnation incarnation() { return identity.incarnation(); }
  public Path clientConfiguration() { return clientConfiguration; }
  public long credentialGeneration() { return material == null ? 0 : material.generation(); }

  /** Returns whether all service owners reached terminal cleanup. */
  public synchronized boolean servicesClosed() {
    return servicesClosed;
  }

  /** Checks the instance credential fence for the foreground lifecycle owner. */
  public synchronized StatusCode checkCredentialValidity() {
    return validityFence == null ? StatusCode.INVARIANT_BROKEN : validityFence.checkNow();
  }

  public String serverCertificateSha256() {
    if (material == null || material.certificate() == null) return null;
    try {
      return HexFormat.of().formatHex(
          MessageDigest.getInstance("SHA-256").digest(material.certificate().getEncoded()));
    } catch (Exception failure) {
      return null;
    }
  }

  /** Closes listener, TLS, credentials, database, and duplicate component handles. */
  public synchronized StatusCode closeServices() {
    if (servicesClosed) return StatusCode.CLOSED;
    StatusCode status = StatusCode.OK;

    if (server != null) {
      StatusCode closedServer = server.close();
      status = firstFailure(status, closedServer);
      if (terminal(closedServer)) server = null;
    }
    if (validityFence != null) {
      StatusCode closedFence = validityFence.close();
      status = firstFailure(status, closedFence);
      if (terminal(closedFence)) validityFence = null;
    }
    if (tls != null) {
      StatusCode cleanedTls = RiverDaemonTlsContext.cleanup(tls);
      status = firstFailure(status, cleanedTls);
      if (terminal(cleanedTls)) tls = null;
    }
    if (material != null) {
      StatusCode destroyedMaterial = material.destroy();
      status = firstFailure(status, destroyedMaterial);
      if (terminal(destroyedMaterial)) material = null;
    }
    if (database != null) {
      StatusCode closedDatabase = closeDatabaseValue(database);
      status = firstFailure(status, closedDatabase);
      if (terminal(closedDatabase)) database = null;
    }
    if (databaseDirectory != null) {
      StatusCode closedDatabaseDirectory = closeDirectory(databaseDirectory);
      status = firstFailure(status, closedDatabaseDirectory);
      if (terminal(closedDatabaseDirectory)) databaseDirectory = null;
    }
    if (securityDirectory != null) {
      StatusCode closedSecurityDirectory = closeDirectory(securityDirectory);
      status = firstFailure(status, closedSecurityDirectory);
      if (terminal(closedSecurityDirectory)) securityDirectory = null;
    }
    if (status.isOk()) servicesClosed = true;
    return status;
  }

  /** Closes services first, then releases the held identity lock and directory capabilities. */
  public synchronized StatusCode close() {
    if (closed) return StatusCode.CLOSED;
    StatusCode status = closeServices();
    if (!status.isOk() && status != StatusCode.CLOSED) return status;
    if (status == StatusCode.CLOSED) status = StatusCode.OK;
    if (identity != null) {
      StatusCode closedIdentity = identity.close();
      status = firstFailure(status, closedIdentity);
      if (terminal(closedIdentity)) identity = null;
    }
    if (status.isOk()) closed = true;
    return status;
  }

  private static boolean terminal(StatusCode status) {
    return status == StatusCode.OK || status == StatusCode.CLOSED;
  }

  private static StatusCode closeDatabaseValue(RiverDatabase value) {
    if (value == null) return StatusCode.OK;
    StatusCode status = value.close();
    return status == StatusCode.CLOSED ? StatusCode.OK : status;
  }

  StatusCode closeDatabase() {
    RiverDatabase value = database;
    StatusCode status = closeDatabaseValue(value);
    if (terminal(status)) database = null;
    return status;
  }

  /** Caller-owned transfer result for a completely opened instance. */
  public static final class OpenResult {
    private RiverDaemonInstance instance;

    public void reset() {
      instance = null;
    }

    StatusCode complete(RiverDaemonInstance opened) {
      if (opened == null) return StatusCode.INVALID_EXTERNAL_INPUT;
      instance = opened;
      return StatusCode.OK;
    }

    public RiverDaemonInstance instance() { return instance; }
    public RiverDaemonIdentity.IdentityResult identity() {
      return instance == null ? null : instance.identity;
    }

    public StatusCode close() {
      return instance == null ? StatusCode.OK : instance.close();
    }
  }

  /** Held identity returned before stale runtime metadata is recovered by the outer owner. */
  public static final class RestartPreparation {
    private RiverDaemonInstance state;

    public void reset() {
      state = null;
    }

    void complete(RiverDaemonInstance opened) {
      state = opened;
    }

    RiverDaemonInstance state() {
      return state;
    }

    void clear() {
      state = null;
    }

    public RiverDaemonIdentity.IdentityResult identity() {
      return state == null ? null : state.identity;
    }

    public StatusCode close() {
      if (state == null) return StatusCode.OK;
      StatusCode status = state.close();
      state = null;
      return status;
    }
  }

  private static StatusCode closeDirectory(RiverDirectory directory) {
    if (directory == null) return StatusCode.OK;
    StatusCode status = directory.close();
    return status == StatusCode.CLOSED ? StatusCode.OK : status;
  }

  private static StatusCode firstFailure(StatusCode first, StatusCode next) {
    return first.isOk() && next != null && next != StatusCode.OK && next != StatusCode.CLOSED
        ? next : first;
  }
}
