package io.riverdb.server.app;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.id.DatabaseIncarnation;
import io.riverdb.platform.riverd.RiverDaemonFileSystem;
import java.nio.file.Path;

/** Runtime records; readiness output publication remains launcher-owned. */
public final class RiverDaemonRuntimeRecords {
  private RiverDaemonRuntimeRecords() {
  }

  /**
   * Removes stale runtime and ready records after the identity owner has validated the database
   * and credential contents. The identity lock must remain held throughout.
   */
  public static StatusCode recoverStale(
      Path datadir,
      RiverDaemonFileSystem filesystem,
      RiverDaemonIdentity.IdentityResult identity,
      Path runtimeRoot) {
    return RiverDaemonRuntimeStaleRecovery.recover(datadir, filesystem, identity, runtimeRoot);
  }

  static final class Metadata {
    final String datadir;
    final DatabaseIncarnation incarnation;
    final RiverDaemonIdentityRecords.LockRecord owner;
    final String listenAddress;
    final int listenPort;
    final long credentialGeneration;
    final String riverVersion;
    final String clientConfig;
    final String readyFile;
    final Path runtimeRoot;

    Metadata(
        String datadir,
        DatabaseIncarnation incarnation,
        RiverDaemonIdentityRecords.LockRecord owner,
        String listenAddress,
        int listenPort,
        long credentialGeneration,
        String riverVersion,
        String clientConfig,
        Path readyFile,
        Path runtimeRoot) {
      this.datadir = datadir;
      this.incarnation = incarnation;
      this.owner = owner;
      this.listenAddress = listenAddress;
      this.listenPort = listenPort;
      this.credentialGeneration = credentialGeneration;
      this.riverVersion = riverVersion;
      this.clientConfig = clientConfig;
      this.readyFile = readyFile == null ? "none" : readyFile.toString();
      this.runtimeRoot = runtimeRoot;
    }

    boolean valid() {
      return RiverDaemonRuntimeStorage.validDirectoryPath(datadir) && runtimeRoot != null
          && RiverDaemonRuntimeStorage.validDirectoryPath(runtimeRoot.toString())
          && incarnation != null && incarnation.isValid()
          && owner != null && owner.datadir.equals(datadir)
          && owner.high == incarnation.high() && owner.low == incarnation.low()
          && owner.pid > 0 && owner.start >= 0
          && owner.nonce.matches("[0-9a-f]{32}")
          && RiverDaemonRuntimeStorage.validAddress(listenAddress)
          && listenPort >= 1 && listenPort <= 65535 && credentialGeneration > 0
          && RiverDaemonRuntimeStorage.validText(riverVersion)
          && RiverDaemonRuntimeStorage.validDirectoryPath(clientConfig)
          && ("none".equals(readyFile)
              || RiverDaemonRuntimeStorage.validDirectoryPath(readyFile));
    }
  }

}
