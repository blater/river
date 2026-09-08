package io.riverdb.server.app;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.id.DatabaseIncarnation;
import io.riverdb.base.id.WalGeneration;
import io.riverdb.engine.EmbeddedRiver;
import io.riverdb.engine.api.DatabaseOpenResult;
import io.riverdb.engine.api.RiverDatabase;
import io.riverdb.engine.runtime.DatabaseResourcePlanRequest;
import io.riverdb.platform.riverd.RiverDaemonFileSystem;
import io.riverdb.platform.riverd.RiverDirectory;
import io.riverdb.platform.riverd.RiverDirectoryResult;
import io.riverdb.server.LoopbackRiverServer;
import io.riverdb.server.LoopbackServerLimits;
import io.riverdb.server.LoopbackServerOpenResult;
import io.riverdb.server.SecurityAuditLog;
import io.riverdb.server.SecurityAuditLogFactory;
import io.riverdb.server.SecurityAuditOpenResult;
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
  protected SecurityAuditLog audit;
  protected LoopbackRiverServer server;
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
      boolean firstCreate,
      DatabaseIncarnation requestedIncarnation,
      String host,
      InetAddress bindAddress,
      int port,
      LoopbackServerLimits limits,
      DatabaseResourcePlanRequest resourcePlan,
      int maximumActiveTransactions,
      OpenResult result) {
    if (result == null || datadir == null || filesystem == null || random == null
        || host == null || bindAddress == null || limits == null || resourcePlan == null
        || maximumActiveTransactions <= 0) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    if (!firstCreate) return StatusCode.FEATURE_NOT_SUPPORTED;
    result.reset();
    ProcessMetadata process = ProcessMetadata.read();
    if (process == null) return StatusCode.FEATURE_NOT_SUPPORTED;

    RiverDaemonIdentity.IdentityResult identity = new RiverDaemonIdentity.IdentityResult();
    if (requestedIncarnation == null || !requestedIncarnation.isValid()) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    StatusCode status = RiverDaemonIdentity.beginCreate(
        datadir, filesystem, requestedIncarnation, random, process.pid,
        process.startMillis, process.command, identity);
    if (!status.isOk()) return status;

    RiverDaemonInstance state = new RiverDaemonInstance(identity, datadir);
    status = openCreate(state, random, host, bindAddress, port, limits, resourcePlan,
        maximumActiveTransactions);
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
      int maximumActiveTransactions,
      RestartPreparation result) {
    if (result == null || datadir == null || filesystem == null || random == null
        || resourcePlan == null || maximumActiveTransactions <= 0) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    result.reset();
    ProcessMetadata process = ProcessMetadata.read();
    if (process == null) return StatusCode.FEATURE_NOT_SUPPORTED;
    RiverDaemonIdentity.IdentityResult identity = new RiverDaemonIdentity.IdentityResult();
    StatusCode status = RiverDaemonIdentity.openExisting(
        datadir, filesystem, random, process.pid, process.startMillis, process.command, identity);
    if (status.isOk()) {
      RiverDaemonInstance state = new RiverDaemonInstance(identity, datadir);
      status = validateRestart(state, resourcePlan, maximumActiveTransactions);
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
      int maximumActiveTransactions) {
    RiverDaemonIdentity.IdentityResult identity = state.identity;
    DatabaseIncarnation incarnation = identity.incarnation();
    boolean securityPublished = identity.securityPublished();
    boolean databasePublished = identity.databasePublished();
    boolean auditPublished = identity.auditPublished();

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
          maximumActiveTransactions, databaseResult);
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
          maximumActiveTransactions, databaseResult);
      if (!status.isOk()) return status;
      state.database = databaseResult.database();
      status = state.closeDatabase();
      if (!status.isOk()) return status;
    }

    status = openAuditForCreate(state, auditPublished, recovering);
    if (!status.isOk()) return status;
    status = state.closeAudit();
    if (!status.isOk()) return status;
    status = RiverDaemonIdentity.completeCreate(identity);
    if (!status.isOk()) return status;

    return openPublishedComponents(
        state, random, host, bindAddress, port, limits, resourcePlan,
        maximumActiveTransactions);
  }

  private static StatusCode validateRestart(
      RiverDaemonInstance state,
      DatabaseResourcePlanRequest resourcePlan,
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

    RiverDirectoryResult auditResult = new RiverDirectoryResult();
    status = identity.directory().openDirectory(RiverDaemonIdentity.AUDIT_NAME, auditResult);
    if (!status.isOk()) return status;
    status = openAudit(state, auditResult.directory(), false);
    if (!status.isOk()) return status;

    DatabaseOpenResult databaseOpen = new DatabaseOpenResult();
    status = EmbeddedRiver.openExisting(
        resourcePlan,
        state.datadir.resolve(RiverDaemonIdentity.DATABASE_NAME),
        identity.incarnation(), WalGeneration.of(1), maximumActiveTransactions, databaseOpen);
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
      int maximumActiveTransactions) {
    RiverDaemonIdentity.IdentityResult identity = state.identity;
    DatabaseOpenResult databaseResult = new DatabaseOpenResult();
    StatusCode status = EmbeddedRiver.openExisting(
        resourcePlan,
        state.datadir.resolve(RiverDaemonIdentity.DATABASE_NAME),
        identity.incarnation(), WalGeneration.of(1), maximumActiveTransactions, databaseResult);
    if (!status.isOk()) return status;
    state.database = databaseResult.database();

    RiverDirectoryResult auditResult = new RiverDirectoryResult();
    status = identity.directory().openDirectory(RiverDaemonIdentity.AUDIT_NAME, auditResult);
    if (!status.isOk()) return status;
    status = openAudit(state, auditResult.directory(), false);
    if (!status.isOk()) return status;
    return openListener(state, random, host, bindAddress, port, limits);
  }

  private static StatusCode openAuditForCreate(
      RiverDaemonInstance state, boolean published, boolean recovering) {
    RiverDaemonIdentity.IdentityResult identity = state.identity;
    RiverDirectory parent = published ? identity.directory() : identity.staging();
    RiverDirectoryResult auditResult = new RiverDirectoryResult();
    StatusCode status = parent.openDirectory(RiverDaemonIdentity.AUDIT_NAME, auditResult);
    if (!status.isOk()) return status;
    return openAudit(state, auditResult.directory(), !published && !recovering);
  }

  private static StatusCode openAudit(
      RiverDaemonInstance state, RiverDirectory directory, boolean create) {
    SecurityAuditOpenResult auditResult = new SecurityAuditOpenResult();
    StatusCode status = create
        ? SecurityAuditLogFactory.create(
            directory, state.identity.incarnation(), state.material.generation(),
            SecurityAuditLogFactory.DEFAULT_ACTIVE_MAXIMUM_BYTES,
            SecurityAuditLogFactory.DEFAULT_PENDING_MAXIMUM_BYTES, auditResult)
        : SecurityAuditLogFactory.open(
            directory, state.identity.incarnation(), state.material.generation(),
            SecurityAuditLogFactory.DEFAULT_ACTIVE_MAXIMUM_BYTES,
            SecurityAuditLogFactory.DEFAULT_PENDING_MAXIMUM_BYTES, auditResult);
    if (status.isOk()) state.audit = auditResult.audit();
    return status;
  }

  private static StatusCode openListener(
      RiverDaemonInstance state,
      SecureRandom random,
      String host,
      InetAddress bindAddress,
      int port,
      LoopbackServerLimits limits) {
    RiverDaemonTlsContext.TlsContextResult tls = new RiverDaemonTlsContext.TlsContextResult();
    StatusCode status = RiverDaemonTlsContext.create(state.material, random, tls);
    if (!status.isOk()) return status;
    state.tls = tls;
    LoopbackServerOpenResult serverResult = new LoopbackServerOpenResult();
    status = LoopbackRiverServer.startAuthenticated(
        state.database, bindAddress, port, tls.context(), state.material.authenticator(),
        state.audit, limits, serverResult);
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
    servicesClosed = true;
    StatusCode status = StatusCode.OK;
    LoopbackRiverServer ownedServer = server;
    server = null;
    status = firstFailure(status, ownedServer == null ? StatusCode.OK : ownedServer.close());
    SecurityAuditLog ownedAudit = audit;
    audit = null;
    status = firstFailure(status, closeAuditValue(ownedAudit));
    RiverDaemonTlsContext.TlsContextResult ownedTls = tls;
    tls = null;
    status = firstFailure(status, ownedTls == null ? StatusCode.OK
        : RiverDaemonTlsContext.cleanup(ownedTls));
    RiverDaemonCredentials.Material ownedMaterial = material;
    material = null;
    status = firstFailure(status, ownedMaterial == null ? StatusCode.OK : ownedMaterial.destroy());
    RiverDatabase ownedDatabase = database;
    database = null;
    status = firstFailure(status, ownedDatabase == null ? StatusCode.OK : ownedDatabase.close());
    RiverDirectory ownedDatabaseDirectory = databaseDirectory;
    databaseDirectory = null;
    status = firstFailure(status, closeDirectory(ownedDatabaseDirectory));
    RiverDirectory ownedSecurityDirectory = securityDirectory;
    securityDirectory = null;
    status = firstFailure(status, closeDirectory(ownedSecurityDirectory));
    return status;
  }

  /** Closes services first, then releases the held identity lock and directory capabilities. */
  public synchronized StatusCode close() {
    if (closed) return StatusCode.CLOSED;
    StatusCode status = closeServices();
    if (status == StatusCode.CLOSED) status = StatusCode.OK;
    RiverDaemonIdentity.IdentityResult ownedIdentity = identity;
    identity = null;
    status = firstFailure(status, ownedIdentity == null ? StatusCode.OK : ownedIdentity.close());
    closed = true;
    return status;
  }

  private static StatusCode closeAuditValue(SecurityAuditLog value) {
    if (value == null) return StatusCode.OK;
    value.beginClose();
    StatusCode status = value.finishClose();
    return status == StatusCode.CLOSED ? StatusCode.OK : status;
  }

  private StatusCode closeDatabase() {
    RiverDatabase value = database;
    database = null;
    StatusCode status = value == null ? StatusCode.OK : value.close();
    return status == StatusCode.CLOSED ? StatusCode.OK : status;
  }

  private StatusCode closeAudit() {
    SecurityAuditLog value = audit;
    audit = null;
    return closeAuditValue(value);
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

  private record ProcessMetadata(long pid, long startMillis, String command) {
    static ProcessMetadata read() {
      ProcessHandle.Info info = ProcessHandle.current().info();
      if (ProcessHandle.current().pid() <= 0 || info.startInstant().isEmpty()
          || info.command().isEmpty()) return null;
      return new ProcessMetadata(
          ProcessHandle.current().pid(), info.startInstant().get().toEpochMilli(),
          info.command().get());
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
