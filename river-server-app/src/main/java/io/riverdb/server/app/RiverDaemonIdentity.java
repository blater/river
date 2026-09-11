package io.riverdb.server.app;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.id.DatabaseIncarnation;
import io.riverdb.platform.riverd.FileIdentity;
import io.riverdb.platform.riverd.RiverDaemonFileSystem;
import io.riverdb.platform.riverd.RiverDirectory;
import io.riverdb.platform.riverd.RiverFile;
import io.riverdb.platform.riverd.RiverLock;
import java.nio.file.Path;
import java.security.SecureRandom;

/** Descriptor-relative first-create and restart identity state for the installed daemon. */
public final class RiverDaemonIdentity {
  public static final String INSTANCE_FILE = "instance.properties";
  public static final String LOCK_FILE = "instance.lock";
  public static final String DATABASE_NAME = "database";
  public static final String SECURITY_NAME = "security";

  private RiverDaemonIdentity() {
  }

  public static StatusCode openExisting(Path datadir, RiverDaemonFileSystem filesystem,
      SecureRandom random, long pid, long processStartEpochMillis, IdentityResult result) {
    return RiverDaemonIdentityRestart.openExisting(datadir, filesystem, random, pid,
        processStartEpochMillis, result);
  }

  static StatusCode cleanupCommittedResidue(IdentityResult result) {
    return RiverDaemonIdentityResidueCleanup.cleanupCommittedResidue(result);
  }

  public static StatusCode beginCreate(Path datadir, RiverDaemonFileSystem filesystem,
      DatabaseIncarnation incarnation, SecureRandom random, long pid,
      long processStartEpochMillis, IdentityResult result) {
    return RiverDaemonIdentityCreate.beginCreate(datadir, filesystem, incarnation, random, pid,
        processStartEpochMillis, result);
  }

  static StatusCode handoffOwner(IdentityResult result) {
    return RiverDaemonIdentityRestart.handoffOwner(result);
  }

  public static StatusCode completeCreate(IdentityResult result) {
    return RiverDaemonIdentityPublication.completeCreate(result);
  }

  public static final class IdentityResult {
    private RiverDirectory directory;
    private RiverLock lock;
    private DatabaseIncarnation incarnation;
    private long generation;
    private String nonce;
    private RiverDirectory staging;
    private RiverDirectory database;
    private RiverDirectory security;
    private RiverFile lockFile;
    private boolean databasePublished;
    private boolean securityPublished;
    private String datadir;
    private long ownerPid;
    private long ownerStart;
    private String ownerNonce;
    private RiverDaemonIdentityRecords.LockRecord priorOwner;
    private boolean needsOwnerHandoff;
    private String stageRepairName;
    private FileIdentity stageRepairIdentity;

    public void reset() {
      directory = null;
      lock = null;
      incarnation = null;
      generation = 0;
      nonce = null;
      staging = null;
      database = null;
      security = null;
      lockFile = null;
      databasePublished = false;
      securityPublished = false;
      datadir = null;
      ownerPid = 0;
      ownerStart = 0;
      ownerNonce = null;
      priorOwner = null;
      needsOwnerHandoff = false;
      stageRepairName = null;
      stageRepairIdentity = null;
    }

    void complete(RiverDirectory openedDirectory, RiverLock openedLock,
        DatabaseIncarnation openedIncarnation, long openedGeneration) {
      directory = openedDirectory;
      lock = openedLock;
      incarnation = openedIncarnation;
      generation = openedGeneration;
    }

    void setBootstrap(String openedNonce, RiverDirectory openedStaging,
        RiverDirectory openedDatabase, RiverDirectory openedSecurity, RiverFile openedLockFile,
        boolean openedDatabasePublished, boolean openedSecurityPublished, String openedDatadir,
        long openedOwnerPid, long openedOwnerStart, boolean openedNeedsOwnerHandoff,
        String openedStageRepairName, FileIdentity openedStageRepairIdentity) {
      nonce = openedNonce;
      staging = openedStaging;
      database = openedDatabase;
      security = openedSecurity;
      lockFile = openedLockFile;
      databasePublished = openedDatabasePublished;
      securityPublished = openedSecurityPublished;
      datadir = openedDatadir;
      ownerPid = openedOwnerPid;
      ownerStart = openedOwnerStart;
      ownerNonce = openedNonce;
      needsOwnerHandoff = openedNeedsOwnerHandoff;
      stageRepairName = openedStageRepairName;
      stageRepairIdentity = openedStageRepairIdentity;
    }

    void setLockFile(RiverFile openedLockFile) {
      lockFile = openedLockFile;
    }

    void setPriorOwner(RiverDaemonIdentityRecords.LockRecord openedPriorOwner) {
      priorOwner = openedPriorOwner;
    }

    void prepareRestart(String openedDatadir,
        RiverDaemonIdentityRecords.LockRecord openedPriorOwner, String openedOwnerNonce,
        long openedOwnerPid, long openedOwnerStart) {
      datadir = openedDatadir;
      priorOwner = openedPriorOwner;
      ownerNonce = openedOwnerNonce;
      ownerPid = openedOwnerPid;
      ownerStart = openedOwnerStart;
      needsOwnerHandoff = true;
    }

    public RiverDirectory directory() { return directory; }
    public RiverLock lock() { return lock; }
    public DatabaseIncarnation incarnation() { return incarnation; }
    public long generation() { return generation; }
    RiverDirectory staging() { return staging; }
    /** Verified private component capability; its owner must initialize and validate its contents. */
    RiverDirectory database() { return database; }
    /** Verified private component capability; its owner must initialize and validate its contents. */
    RiverDirectory security() { return security; }
    boolean databasePublished() { return databasePublished; }
    boolean securityPublished() { return securityPublished; }
    String nonce() { return nonce; }
    RiverFile lockFile() { return lockFile; }
    RiverDaemonIdentityRecords.LockRecord priorOwner() { return priorOwner; }
    String ownerNonce() { return ownerNonce; }
    boolean needsOwnerHandoff() { return needsOwnerHandoff; }
    long ownerPid() { return ownerPid; }
    long ownerStart() { return ownerStart; }
    String datadir() { return datadir; }
    FileIdentity stageRepairIdentity() { return stageRepairIdentity; }
    String stageRepairName() { return stageRepairName; }
    boolean readyForPublication() {
      return directory != null && lock != null && staging != null && database != null
          && security != null && nonce != null && lockFile != null;
    }
    void markDatabasePublished() { databasePublished = true; }
    void markSecurityPublished() { securityPublished = true; }
    void clearStageRepair() {
      stageRepairName = null;
      stageRepairIdentity = null;
    }
    void completeOwnerHandoff() { needsOwnerHandoff = false; }

    RiverDaemonIdentityRecords.LockRecord currentOwner() {
      return new RiverDaemonIdentityRecords.LockRecord(datadir, incarnation.high(),
          incarnation.low(), ownerPid, ownerStart, ownerNonce);
    }

    public synchronized StatusCode close() {
      StatusCode status = StatusCode.OK;
      status = combine(status, closeDirectory(security));
      status = combine(status, closeDirectory(database));
      status = combine(status, closeDirectory(staging));
      status = combine(status, lock == null ? StatusCode.OK : lock.close());
      status = combine(status, lockFile == null ? StatusCode.OK : lockFile.close());
      status = combine(status, closeDirectory(directory));
      security = null;
      database = null;
      staging = null;
      lock = null;
      lockFile = null;
      directory = null;
      return status;
    }

    private static StatusCode closeDirectory(RiverDirectory value) {
      return value == null ? StatusCode.OK : value.close();
    }

    private static StatusCode combine(StatusCode first, StatusCode next) {
      return first.isOk() && next != StatusCode.OK && next != StatusCode.CLOSED ? next : first;
    }
  }
}
