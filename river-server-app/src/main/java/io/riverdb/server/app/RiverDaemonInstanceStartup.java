package io.riverdb.server.app;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.id.DatabaseIncarnation;
import io.riverdb.base.id.WalGeneration;
import io.riverdb.engine.EmbeddedLockDiagnosticsConfig;
import io.riverdb.engine.EmbeddedRiver;
import io.riverdb.engine.api.DatabaseOpenResult;
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
import java.time.Instant;

/** Assembles credentials, database and authenticated services for one instance owner. */
final class RiverDaemonInstanceStartup {
  private RiverDaemonInstanceStartup() { }

  static StatusCode openCreate(
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

  static StatusCode validateRestart(
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

  static StatusCode openPublishedComponents(
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

  static StatusCode openValidityFence(RiverDaemonInstance state) {
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

  static StatusCode openListener(
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

}
