package io.riverdb.server.app;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.id.DatabaseIncarnation;
import io.riverdb.base.id.WalGeneration;
import io.riverdb.engine.EmbeddedLockDiagnosticsConfig;
import io.riverdb.engine.EmbeddedRiver;
import io.riverdb.engine.api.DatabaseOpenResult;
import io.riverdb.engine.api.RiverDatabase;
import io.riverdb.engine.runtime.DatabaseResourcePlanRequest;
import io.riverdb.platform.riverd.RiverDaemonFileSystem;
import io.riverdb.platform.riverd.RiverDirectory;
import io.riverdb.platform.riverd.RiverDirectoryResult;
import io.riverdb.server.CredentialValidityFence;
import io.riverdb.server.CredentialValidityFenceOpenResult;
import io.riverdb.server.LoopbackRiverServer;
import io.riverdb.server.LoopbackServerLimits;
import io.riverdb.server.LoopbackServerOpenResult;
import java.net.InetAddress;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.security.MessageDigest;
import java.time.Instant;
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

  private RiverDaemonInstance(
      RiverDaemonIdentity.IdentityResult identity, Path datadir) {
    this.identity = identity;
    this.datadir = datadir;
  }

  /**
   * Opens a first-created authenticated instance.  Restart uses
   * {@link #prepareRestart} followed by {@link #openPreparedRestart}, allowing the outer runtime
   * owner to recover stale metadata while the validated instance lock remains held.
   */
  public static StatusCode open(
      Path datadir,
      RiverDaemonFileSystem filesystem,
      SecureRandom random,
      DatabaseIncarnation requestedIncarnation,
      String host,
      InetAddress bindAddress,
      int port,
      LoopbackServerLimits limits,
      DatabaseResourcePlanRequest resourcePlan,
      EmbeddedLockDiagnosticsConfig lockDiagnostics,
      int maximumActiveTransactions,
      OpenResult result) {
    if (result == null || datadir == null || filesystem == null || random == null
        || host == null || bindAddress == null || limits == null || resourcePlan == null
        || lockDiagnostics == null || maximumActiveTransactions <= 0) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    result.reset();
    ProcessMetadata process = ProcessMetadata.read();
    if (process == null) return StatusCode.FEATURE_NOT_SUPPORTED;

    RiverDaemonIdentity.IdentityResult identity = new RiverDaemonIdentity.IdentityResult();
    if (requestedIncarnation == null || !requestedIncarnation.isValid()) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    StatusCode status = RiverDaemonIdentity.beginCreate(
        datadir, filesystem, requestedIncarnation, random, process.pid,
        process.startMillis, identity);
    if (!status.isOk()) return status;

    RiverDaemonInstance state = new RiverDaemonInstance(identity, datadir);
    status = openCreate(state, random, host, bindAddress, port, limits, resourcePlan,
        lockDiagnostics, maximumActiveTransactions);
    if (!status.isOk()) {
      state.close();
      return status;
    }
    return result.complete(state);
  }

  /** Opens and locks the validated identity before the runtime owner removes stale metadata. */
  public static StatusCode prepareRestart(
      Path datadir,
      RiverDaemonFileSystem filesystem,
      SecureRandom random,
      DatabaseResourcePlanRequest resourcePlan,
      EmbeddedLockDiagnosticsConfig lockDiagnostics,
      int maximumActiveTransactions,
      RestartPreparation result) {
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
      status = validateRestart(state, resourcePlan, lockDiagnostics, maximumActiveTransactions);
      if (status.isOk()) result.complete(state);
      else state.close();
    }
    return status;
  }

  /** Completes a prepared restart after the outer process owner has recovered stale metadata. */
  public static StatusCode openPreparedRestart(
      RestartPreparation preparation,
      SecureRandom random,
      String host,
      InetAddress bindAddress,
      int port,
      LoopbackServerLimits limits,
      OpenResult result) {
    if (preparation == null || result == null || random == null || host == null
        || bindAddress == null || limits == null || preparation.state == null) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    result.reset();
    RiverDaemonInstance state = preparation.state;
    StatusCode status = RiverDaemonIdentity.handoffOwner(state.identity);
    if (!status.isOk()) {
      state.close();
      preparation.state = null;
      return status;
    }
    status = openListener(state, random, host, bindAddress, port, limits);
    if (!status.isOk()) {
      state.close();
      preparation.state = null;
      return status;
    }
    preparation.state = null;
    return result.complete(state);
  }

  private static StatusCode openCreate(
      RiverDaemonInstance state,
      SecureRandom random,
      String host,
      InetAddress bindAddress,
      int port,
      LoopbackServerLimits limits,
      DatabaseResourcePlanRequest resourcePlan,
      EmbeddedLockDiagnosticsConfig lockDiagnostics,
      int maximumActiveTransactions) {
    RiverDaemonIdentity.IdentityResult identity = state.identity;
    DatabaseIncarnation incarnation = identity.incarnation();
    boolean securityPublished = identity.securityPublished();
    boolean databasePublished = identity.databasePublished();

    boolean recovering = identity.needsOwnerHandoff();
    if (securityPublished || recovering) {
      RiverDaemonCredentials.CredentialResult credentials =
          new RiverDaemonCredentials.CredentialResult();
      StatusCode status = RiverDaemonCredentials.load(
          identity.security(), incarnation, credentials);
      if (!status.isOk()) return status;
      state.material = credentials.material();
    } else {
      RiverDaemonCredentials.CredentialResult credentials =
          new RiverDaemonCredentials.CredentialResult();
      Instant createdAt = Instant.now();
      StatusCode status = RiverDaemonCredentials.generate(
          incarnation, identity.generation(), random, createdAt, credentials);
      if (!status.isOk()) return status;
      state.material = credentials.material();
      status = RiverDaemonCredentials.persist(
          state.material, identity.security(), incarnation, createdAt);
      if (!status.isOk()) return status;
    }

    StatusCode status;
    if (databasePublished || recovering) {
      Path existingDatabase = databasePublished
          ? state.datadir.resolve(RiverDaemonIdentity.DATABASE_NAME)
          : state.datadir.resolve(
              ".riverd-bootstrap-" + identity.nonce()).resolve(RiverDaemonIdentity.DATABASE_NAME);
      DatabaseOpenResult databaseResult = new DatabaseOpenResult();
      status = EmbeddedRiver.openExisting(
          resourcePlan, existingDatabase, incarnation, WalGeneration.of(1),
          maximumActiveTransactions, lockDiagnostics, databaseResult);
      if (!status.isOk()) return status;
      state.database = databaseResult.database();
      status = state.closeDatabase();
      if (!status.isOk()) return status;
    } else {
      Path stagedDatabase = state.datadir.resolve(
          ".riverd-bootstrap-" + identity.nonce()).resolve(RiverDaemonIdentity.DATABASE_NAME);
      DatabaseOpenResult databaseResult = new DatabaseOpenResult();
      status = EmbeddedRiver.create(
          resourcePlan, stagedDatabase, incarnation, WalGeneration.of(1),
          maximumActiveTransactions, lockDiagnostics, databaseResult);
      if (!status.isOk()) return status;
      state.database = databaseResult.database();
      status = state.closeDatabase();
      if (!status.isOk()) return status;
    }

    status = RiverDaemonIdentity.completeCreate(identity);
    if (!status.isOk()) return status;

    return openPublishedComponents(
        state, random, host, bindAddress, port, limits, resourcePlan,
        lockDiagnostics, maximumActiveTransactions);
  }

  private static StatusCode validateRestart(
      RiverDaemonInstance state,
      DatabaseResourcePlanRequest resourcePlan,
      EmbeddedLockDiagnosticsConfig lockDiagnostics,
      int maximumActiveTransactions) {
    RiverDaemonIdentity.IdentityResult identity = state.identity;
    StatusCode status;

    RiverDirectoryResult databaseResult = new RiverDirectoryResult();
    status = identity.directory().openDirectory(
        RiverDaemonIdentity.DATABASE_NAME, databaseResult);
    if (!status.isOk()) return status;
    state.databaseDirectory = databaseResult.directory();

    RiverDirectoryResult securityResult = new RiverDirectoryResult();
    status = identity.directory().openDirectory(
        RiverDaemonIdentity.SECURITY_NAME, securityResult);
    if (!status.isOk()) return status;
    state.securityDirectory = securityResult.directory();

    RiverDaemonCredentials.CredentialResult credentials =
        new RiverDaemonCredentials.CredentialResult();
    status = RiverDaemonCredentials.load(
        state.securityDirectory, identity.incarnation(), credentials);
    if (!status.isOk()) return status;
    state.material = credentials.material();

    DatabaseOpenResult databaseOpen = new DatabaseOpenResult();
    status = EmbeddedRiver.openExisting(
        resourcePlan,
        state.datadir.resolve(RiverDaemonIdentity.DATABASE_NAME),
        identity.incarnation(), WalGeneration.of(1), maximumActiveTransactions,
        lockDiagnostics, databaseOpen);
    if (!status.isOk()) return status;
    state.database = databaseOpen.database();

    status = RiverDaemonIdentity.cleanupCommittedResidue(identity);
    return status;
  }

  private static StatusCode openPublishedComponents(
      RiverDaemonInstance state,
      SecureRandom random,
      String host,
      InetAddress bindAddress,
      int port,
      LoopbackServerLimits limits,
      DatabaseResourcePlanRequest resourcePlan,
      EmbeddedLockDiagnosticsConfig lockDiagnostics,
      int maximumActiveTransactions) {
    RiverDaemonIdentity.IdentityResult identity = state.identity;
    DatabaseOpenResult databaseResult = new DatabaseOpenResult();
    StatusCode status = EmbeddedRiver.openExisting(
        resourcePlan,
        state.datadir.resolve(RiverDaemonIdentity.DATABASE_NAME),
        identity.incarnation(), WalGeneration.of(1), maximumActiveTransactions,
        lockDiagnostics, databaseResult);
    if (!status.isOk()) return status;
    state.database = databaseResult.database();

    return openListener(state, random, host, bindAddress, port, limits);
  }

  private static StatusCode openValidityFence(RiverDaemonInstance state) {
    if (state.validityFence != null || state.material == null
        || state.material.certificate() == null) {
      return state.validityFence == null
          ? StatusCode.INVARIANT_BROKEN : StatusCode.CONFLICT;
    }
    long notBeforeMillis = state.material.certificate().getNotBefore().getTime();
    long notAfterMillis = state.material.certificate().getNotAfter().getTime();
    CredentialValidityFenceOpenResult opened = new CredentialValidityFenceOpenResult();
    StatusCode status = CredentialValidityFence.create(
        notBeforeMillis, notAfterMillis, opened);
    if (status.isOk()) state.validityFence = opened.fence();
    return status;
  }

  private static StatusCode openListener(
      RiverDaemonInstance state,
      SecureRandom random,
      String host,
      InetAddress bindAddress,
      int port,
      LoopbackServerLimits limits) {
    StatusCode status = openValidityFence(state);
    if (!status.isOk()) return status;
    RiverDaemonTlsContext.TlsContextResult tls = new RiverDaemonTlsContext.TlsContextResult();
    status = RiverDaemonTlsContext.create(state.material, random, tls);
    if (!status.isOk()) return status;
    state.tls = tls;
    LoopbackServerOpenResult serverResult = new LoopbackServerOpenResult();
    status = LoopbackRiverServer.startAuthenticated(
        state.database, bindAddress, port, tls.context(), state.material.authenticator(),
        state.validityFence, limits, serverResult);
    if (!status.isOk()) return status;
    state.server = serverResult.server();
    state.clientConfiguration = state.datadir.resolve(RiverDaemonIdentity.SECURITY_NAME)
        .resolve("client.properties");
    RiverDirectory security = state.securityDirectory == null
        ? state.identity.security() : state.securityDirectory;
    status = RiverDaemonCredentials.publishClientConfiguration(
        state.material, security, state.datadir.resolve(RiverDaemonIdentity.SECURITY_NAME),
        state.identity.incarnation(), host, state.server.port(), state.identity.ownerNonce());
    return status;
  }

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

  private StatusCode closeDatabase() {
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

  private record ProcessMetadata(long pid, long startMillis) {
    static ProcessMetadata read() {
      ProcessHandle.Info info = ProcessHandle.current().info();
      if (ProcessHandle.current().pid() <= 0 || info.startInstant().isEmpty()) return null;
      return new ProcessMetadata(
          ProcessHandle.current().pid(), info.startInstant().get().toEpochMilli());
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
