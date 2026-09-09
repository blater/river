package io.riverdb.server.app;

import io.riverdb.base.error.StatusCode;
import io.riverdb.platform.file.DirectoryEntryType;
import io.riverdb.platform.file.DirectoryListResult;
import io.riverdb.platform.file.DirectoryOperationResult;
import io.riverdb.platform.riverd.FileIdentity;
import io.riverdb.platform.riverd.RiverDaemonFileSystem;
import io.riverdb.platform.riverd.RiverDirectory;
import io.riverdb.platform.riverd.RiverFile;
import io.riverdb.platform.riverd.RiverFileResult;
import io.riverdb.platform.riverd.RiverOpenMode;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.HexFormat;

/** Cooperative, owner-bound shutdown request and server-side lifecycle control. */
final class RiverDaemonStop {
  private static final long POLL_MILLIS = 50L;
  private static final SecureRandom NONCES = new SecureRandom();

  private RiverDaemonStop() {
  }

  /** Publishes or joins a request and waits for the owning server to finish. */
  static StatusCode request(RiverDaemonTarget target, long timeoutMillis) {
    if (target == null || timeoutMillis <= 0) return StatusCode.INVALID_EXTERNAL_INPUT;
    RequestState state = new RequestState(target, timeoutMillis);
    StatusCode primary = state.run();
    StatusCode cleanup = state.cleanup();
    return cleanup.isOk() ? primary : cleanup;
  }

  private static final class RequestState {
    final RiverDaemonTarget target;
    final long deadline;
    final String nonce = nonce();
    final String stageName = RiverDaemonStopRequest.STAGE_PREFIX + nonce
        + RiverDaemonStopRequest.STAGE_SUFFIX;
    FileIdentity ownedIdentity;
    boolean published;
    boolean joined;

    RequestState(RiverDaemonTarget target, long timeoutMillis) {
      this.target = target;
      deadline = deadline(timeoutMillis);
    }

    StatusCode run() {
      while (!expired(deadline)) {
        Scan scan = scan(target.directory, false);
        if (scan.status == StatusCode.RETRY) {
          if (!sleepPoll()) return StatusCode.CANCELLED;
          continue;
        }
        if (!scan.status.isOk()) return scan.status;
        if (scan.acceptedCount > 1) {
          return StatusCode.CORRUPTION;
        }
        if (scan.accepted != null) {
          if (scan.request != null) {
            StatusCode requestStatus = validateRequest(scan.request.record, target);
            if (!requestStatus.isOk()) return StatusCode.CORRUPTION;
            if (published && ownedIdentity != null && ownedIdentity.equals(scan.request.identity)) {
              StatusCode remove = removeIfOwned(target.directory,
                  RiverDaemonStopRequest.REQUEST_NAME, scan.request.identity);
              if (!remove.isOk()) return remove;
            }
          }
          StatusCode status = validateAccepted(scan.accepted, target);
          if (!status.isOk()) return status;
          return awaitCompletion(target, deadline);
        }
        if (scan.request != null) {
          StatusCode status = validateRequest(scan.request.record, target);
          if (!status.isOk()) return status;
          joined = true;
          if (published && scan.request.record.requestNonce.equals(nonce)) {
            ownedIdentity = scan.request.identity;
          }
          status = target.revalidate(false);
          if (status == StatusCode.NOT_OWNER) {
            status = cleanup();
            if (!status.isOk()) return status;
            status = ensureClean(target);
            return status == StatusCode.TIMEOUT ? StatusCode.NOT_OWNER : status;
          }
          if (!status.isOk()) return status;
          if (!sleepPoll()) return StatusCode.CANCELLED;
          continue;
        }
        if (joined) return awaitCompletion(target, deadline);
        if (ownedIdentity == null) {
          StatusCode status = target.revalidate(true);
          if (!status.isOk()) return status;
          Stage stage = createStage(target, stageName);
          if (stage.status == StatusCode.CONFLICT) continue;
          if (!stage.status.isOk()) return stage.status;
          ownedIdentity = stage.identity;
        }
        StatusCode status = target.revalidate(true);
        if (!status.isOk()) return status;
        status = target.lockHeld();
        if (status != StatusCode.OK) return status;
        status = publish(target.directory, stageName);
        if (status == StatusCode.OK) {
          published = true;
          joined = true;
        } else if (status != StatusCode.CONFLICT) {
          return status;
        }
        if (!sleepPoll()) return StatusCode.CANCELLED;
      }
      return StatusCode.TIMEOUT;
    }

    StatusCode cleanup() {
      if (ownedIdentity == null) return StatusCode.OK;
      StatusCode stage = removeIfOwned(target.directory, stageName, ownedIdentity);
      StatusCode request = removeIfOwned(target.directory,
          RiverDaemonStopRequest.REQUEST_NAME, ownedIdentity);
      return stage.isOk() ? request : stage;
    }
  }

  /** Server-side owner called by the existing lifecycle control thread. */
  static final class Control {
    private final RiverDaemonFileSystem filesystem;
    private final RiverDaemonIdentity.IdentityResult identity;
    private final RiverDaemonRuntimeRecords.Metadata metadata;
    private final RiverFileResult probeResult = new RiverFileResult();
    private String acceptedNonce;
    private FileIdentity acceptedIdentity;

    Control(RiverDaemonFileSystem filesystem,
        RiverDaemonIdentity.IdentityResult identity,
        RiverDaemonRuntimeRecords.Metadata metadata) {
      this.filesystem = filesystem;
      this.identity = identity;
      this.metadata = metadata;
    }

    /** Returns CANCELLED only after the request is atomically accepted and forced. */
    synchronized StatusCode poll() {
      if (filesystem == null || identity == null || metadata == null
          || identity.directory() == null || identity.lock() == null) {
        return StatusCode.INVALID_EXTERNAL_INPUT;
      }
      if (acceptedNonce != null) {
        StatusCode forced = RiverDaemonRuntimeRecords.force(identity.directory());
        return forced.isOk() ? StatusCode.CANCELLED : forced;
      }
      Probe probe = probeRequest(identity.directory(), probeResult);
      if (!probe.status.isOk()) return probe.status;
      if (probe.entry == null) return StatusCode.OK;
      StatusCode status = validateForServer(probe.entry.record);
      if (status != StatusCode.OK) return status;
      String accepted = RiverDaemonStopRequest.ACCEPTED_PREFIX
          + probe.entry.record.requestNonce;
      status = publishAccepted(identity.directory(), probe.entry.name, accepted);
      if (status == StatusCode.CONFLICT) return StatusCode.OK;
      if (!status.isOk()) return status;
      acceptedNonce = probe.entry.record.requestNonce;
      acceptedIdentity = probe.entry.identity;
      status = RiverDaemonRuntimeRecords.force(identity.directory());
      if (!status.isOk()) return status;
      return StatusCode.CANCELLED;
    }

    /** Removes the accepted receipt after runtime and registry cleanup. */
    synchronized StatusCode cleanup() {
      if (identity == null || identity.directory() == null) {
        return StatusCode.INVALID_EXTERNAL_INPUT;
      }
      if (acceptedNonce == null) return StatusCode.OK;
      StatusCode status = removeIfOwned(identity.directory(),
          RiverDaemonStopRequest.ACCEPTED_PREFIX + acceptedNonce, acceptedIdentity);
      if (status.isOk()) {
        acceptedNonce = null;
        acceptedIdentity = null;
      }
      return status;
    }

    private StatusCode validateForServer(RiverDaemonStopRequest.Record record) {
      if (record == null || metadata == null || identity == null) return StatusCode.CORRUPTION;
      if (record.high != metadata.incarnation.high() || record.low != metadata.incarnation.low()
          || !record.ownerNonce.equals(metadata.owner.nonce)) return StatusCode.NOT_OWNER;
      RiverFileResult result = new RiverFileResult();
      StatusCode status = identity.directory().openFile(
          RiverDaemonRuntimeRecords.RUNTIME_NAME, RiverOpenMode.EXISTING, result);
      if (status != StatusCode.OK) return status == StatusCode.CONFLICT
          ? StatusCode.NOT_OWNER : status;
      RiverFile file = result.file();
      RiverDaemonRuntimeRecords.ReadResult read = RiverDaemonRuntimeRecords.read(file);
      StatusCode closeStatus = file.close();
      if (!read.status.isOk()) return read.status;
      if (!closeStatus.isOk() && closeStatus != StatusCode.CLOSED) return closeStatus;
      RiverDaemonRuntimeRecords.RuntimeRecord runtime =
          RiverDaemonRuntimeRecords.parseRuntime(read.bytes);
      Arrays.fill(read.bytes, (byte) 0);
      if (runtime == null || !runtime.matches(metadata.datadir, metadata.incarnation, metadata.owner)
          || !record.runtimeChecksum.equals(runtime.checksum)) return StatusCode.NOT_OWNER;
      return StatusCode.OK;
    }
  }

  /** Removes only controls bound to the owner proved absent by the new lock holder. */
  static StatusCode recoverStale(RiverDaemonFileSystem filesystem,
      RiverDaemonIdentity.IdentityResult identity) {
    if (filesystem == null || identity == null || identity.directory() == null
        || identity.lock() == null || identity.priorOwner() == null) return StatusCode.OK;
    Scan scan = scan(identity.directory(), true);
    if (!scan.status.isOk()) return scan.status;
    RiverDaemonIdentityRecords.LockRecord old = identity.priorOwner();
    boolean removed = false;
    for (int index = 0; index < scan.entryCount; index++) {
      ControlEntry entry = scan.entries[index];
      if (entry.record.high != old.high || entry.record.low != old.low
          || !entry.record.ownerNonce.equals(old.nonce)) continue;
      StatusCode status = identity.directory().removeOwned(entry.name, entry.identity,
          new DirectoryOperationResult());
      if (!status.isOk()) return status;
      removed = true;
    }
    return removed ? RiverDaemonRuntimeRecords.force(identity.directory()) : StatusCode.OK;
  }

  private static StatusCode validateRequest(RiverDaemonStopRequest.Record record,
      RiverDaemonTarget target) {
    if (record == null) return StatusCode.CORRUPTION;
    if (record.high != target.owner.high || record.low != target.owner.low
        || !record.ownerNonce.equals(target.owner.nonce)) return StatusCode.NOT_OWNER;
    return record.runtimeChecksum.equals(target.runtimeChecksum)
        ? StatusCode.OK : StatusCode.NOT_OWNER;
  }

  private static StatusCode validateAccepted(ControlEntry entry, RiverDaemonTarget target) {
    if (entry == null || entry.record == null) return StatusCode.CORRUPTION;
    if (target != null && (entry.record.high != target.owner.high
        || entry.record.low != target.owner.low
        || !entry.record.ownerNonce.equals(target.owner.nonce))) return StatusCode.CORRUPTION;
    if (target != null && target.runtimeChecksum != null
        && !entry.record.runtimeChecksum.equals(target.runtimeChecksum)) return StatusCode.CORRUPTION;
    return StatusCode.OK;
  }

  private static StatusCode awaitCompletion(RiverDaemonTarget target, long deadline) {
    while (!expired(deadline)) {
      StatusCode status = target.lockHeld();
      if (status == StatusCode.NOT_OWNER) {
        status = ensureClean(target);
        if (status != StatusCode.TIMEOUT && status != StatusCode.RETRY) return status;
      } else if (status != StatusCode.OK) return status;
      if (!sleepPoll()) return StatusCode.CANCELLED;
    }
    return StatusCode.TIMEOUT;
  }

  private static StatusCode ensureClean(RiverDaemonTarget target) {
    StatusCode status = target.revalidate(false);
    if (status != StatusCode.OK && status != StatusCode.NOT_OWNER) return status;
    Scan scan = scan(target.directory, false);
    if (!scan.status.isOk()) return scan.status;
    if (scan.request != null || scan.acceptedCount != 0) {
      return StatusCode.TIMEOUT;
    }
    RiverFileResult runtime = new RiverFileResult();
    status = target.directory.openFile(RiverDaemonRuntimeRecords.RUNTIME_NAME,
        RiverOpenMode.EXISTING, runtime);
    if (status == StatusCode.OK) {
      runtime.file().close();
      return StatusCode.TIMEOUT;
    }
    return status == StatusCode.CONFLICT ? StatusCode.OK : status;
  }

  private static Stage createStage(RiverDaemonTarget target, String name) {
    RiverFileResult result = new RiverFileResult();
    StatusCode status = target.directory.openFile(name, RiverOpenMode.CREATE_NEW, result);
    if (!status.isOk()) return Stage.failure(status);
    RiverFile file = result.file();
    String nonce = name.substring(RiverDaemonStopRequest.STAGE_PREFIX.length(),
        name.length() - RiverDaemonStopRequest.STAGE_SUFFIX.length());
    String body = RiverDaemonStopRequest.encode(target.owner.high, target.owner.low,
        target.owner.nonce, nonce, target.runtimeChecksum, System.currentTimeMillis());
    status = RiverDaemonRuntimeRecords.write(file, body.getBytes(StandardCharsets.UTF_8));
    FileIdentity identity = file.identity();
    StatusCode closeStatus = file.close();
    if (status.isOk() && !closeStatus.isOk() && closeStatus != StatusCode.CLOSED) status = closeStatus;
    if (status.isOk()) return new Stage(StatusCode.OK, identity);
    StatusCode cleanup = removeIfOwned(target.directory, name, identity);
    return Stage.failure(cleanup.isOk() ? status : cleanup);
  }

  private static StatusCode publish(RiverDirectory directory, String stageName) {
    RiverFileResult result = new RiverFileResult();
    StatusCode status = directory.openFile(stageName, RiverOpenMode.EXISTING, result);
    if (!status.isOk()) return status;
    RiverFile stage = result.file();
    status = directory.publishExclusive(stage, stageName, RiverDaemonStopRequest.REQUEST_NAME,
        new DirectoryOperationResult());
    StatusCode closeStatus = stage.close();
    if (status.isOk() && !closeStatus.isOk() && closeStatus != StatusCode.CLOSED) status = closeStatus;
    if (status.isOk()) status = RiverDaemonRuntimeRecords.force(directory);
    return status;
  }

  private static StatusCode publishAccepted(RiverDirectory directory, String requestName,
      String acceptedName) {
    RiverFileResult result = new RiverFileResult();
    StatusCode status = directory.openFile(requestName, RiverOpenMode.EXISTING, result);
    if (!status.isOk()) return status;
    RiverFile request = result.file();
    status = directory.publishExclusive(request, requestName, acceptedName,
        new DirectoryOperationResult());
    StatusCode closeStatus = request.close();
    if (status.isOk() && !closeStatus.isOk() && closeStatus != StatusCode.CLOSED) status = closeStatus;
    return status;
  }

  private static StatusCode removeIfOwned(RiverDirectory directory, String name,
      FileIdentity identity) {
    if (directory == null || identity == null) return StatusCode.OK;
    StatusCode status = directory.removeOwned(name, identity, new DirectoryOperationResult());
    if (status == StatusCode.CONFLICT) return StatusCode.OK;
    if (status.isOk()) status = RiverDaemonRuntimeRecords.force(directory);
    return status;
  }

  private static Probe probeRequest(RiverDirectory directory, RiverFileResult result) {
    result.reset();
    StatusCode status = directory.openFile(RiverDaemonStopRequest.REQUEST_NAME,
        RiverOpenMode.EXISTING, result);
    if (status == StatusCode.CONFLICT) return Probe.empty();
    if (!status.isOk()) return Probe.failure(status);
    RiverFile file = result.file();
    FileIdentity identity = file.identity();
    RiverDaemonRuntimeRecords.ReadResult read = RiverDaemonRuntimeRecords.read(file);
    StatusCode closeStatus = file.close();
    if (!read.status.isOk()) return Probe.failure(read.status);
    if (!closeStatus.isOk() && closeStatus != StatusCode.CLOSED) return Probe.failure(closeStatus);
    RiverDaemonStopRequest.Record record = RiverDaemonStopRequest.parse(read.bytes);
    Arrays.fill(read.bytes, (byte) 0);
    return record == null ? Probe.failure(StatusCode.CORRUPTION)
        : Probe.present(new ControlEntry(RiverDaemonStopRequest.REQUEST_NAME, identity, record));
  }

  private static Scan scan(RiverDirectory directory, boolean includeStages) {
    if (directory == null) return Scan.failure(StatusCode.INVALID_EXTERNAL_INPUT);
    int capacity = 16;
    DirectoryListResult listing;
    StatusCode status;
    while (true) {
      listing = new DirectoryListResult(capacity);
      status = directory.list(listing);
      if (status != StatusCode.RESOURCE_EXHAUSTED) break;
      if (capacity > Integer.MAX_VALUE / 2) return Scan.failure(StatusCode.RESOURCE_EXHAUSTED);
      capacity *= 2;
    }
    // A directory entry may disappear between enumeration and its metadata read.
    if (status == StatusCode.CONFLICT) return Scan.failure(StatusCode.RETRY);
    if (!status.isOk()) return Scan.failure(status);
    Scan scan = new Scan(listing.size());
    for (int index = 0; index < listing.size(); index++) {
      String name = listing.name(index);
      boolean request = RiverDaemonStopRequest.REQUEST_NAME.equals(name);
      boolean stagePrefix = name.startsWith(RiverDaemonStopRequest.STAGE_PREFIX);
      boolean acceptedPrefix = name.startsWith(RiverDaemonStopRequest.ACCEPTED_PREFIX);
      boolean stage = stagePrefix
          && name.endsWith(RiverDaemonStopRequest.STAGE_SUFFIX);
      boolean accepted = acceptedPrefix;
      if (stagePrefix && !includeStages) continue;
      if (!request && !stage && !accepted && (stagePrefix || acceptedPrefix)) {
        return Scan.failure(StatusCode.CORRUPTION);
      }
      if (!request && !stage && !accepted) continue;
      if (listing.type(index) != DirectoryEntryType.FILE) return Scan.failure(StatusCode.CORRUPTION);
      String expectedNonce = request ? null : controlNonce(name,
          stage ? RiverDaemonStopRequest.STAGE_PREFIX : RiverDaemonStopRequest.ACCEPTED_PREFIX,
          stage ? RiverDaemonStopRequest.STAGE_SUFFIX : "");
      if (!request && (expectedNonce == null || !expectedNonce.matches("[0-9a-f]{32}"))) {
        return Scan.failure(StatusCode.CORRUPTION);
      }
      RiverFileResult result = new RiverFileResult();
      status = directory.openFile(name, RiverOpenMode.EXISTING, result);
      if (status == StatusCode.CONFLICT) return Scan.failure(StatusCode.RETRY);
      if (!status.isOk()) return Scan.failure(status);
      RiverFile file = result.file();
      FileIdentity identity = file.identity();
      RiverDaemonRuntimeRecords.ReadResult read = RiverDaemonRuntimeRecords.read(file);
      StatusCode closeStatus = file.close();
      if (!read.status.isOk()) {
        if (stage && includeStages) continue;
        return Scan.failure(read.status);
      }
      if (!closeStatus.isOk() && closeStatus != StatusCode.CLOSED) return Scan.failure(closeStatus);
      RiverDaemonStopRequest.Record record = RiverDaemonStopRequest.parse(read.bytes);
      Arrays.fill(read.bytes, (byte) 0);
      if (record == null || (!request && !record.requestNonce.equals(expectedNonce))) {
        // A stage is an unpublished writer-owned scratch file. During stale recovery an
        // incomplete or malformed stage is preserved for inspection; only fixed request and
        // accepted records are lifecycle authorities.
        if (stage && includeStages) continue;
        return Scan.failure(StatusCode.CORRUPTION);
      }
      ControlEntry entry = new ControlEntry(name, identity, record);
      scan.entries[scan.entryCount++] = entry;
      if (request) {
        if (scan.request != null) return Scan.failure(StatusCode.CORRUPTION);
        scan.request = entry;
      } else if (accepted) {
        scan.acceptedCount++;
        if (scan.accepted == null) scan.accepted = entry;
      }
    }
    return scan;
  }

  private static String controlNonce(String name, String prefix, String suffix) {
    int end = suffix.isEmpty() ? name.length() : name.length() - suffix.length();
    if (!name.startsWith(prefix) || end <= prefix.length()) return null;
    return name.substring(prefix.length(), end);
  }

  private static String nonce() {
    byte[] bytes = new byte[16];
    NONCES.nextBytes(bytes);
    return HexFormat.of().formatHex(bytes);
  }

  private static long deadline(long timeoutMillis) {
    long now = System.nanoTime();
    long nanos = timeoutMillis > Long.MAX_VALUE / 1_000_000L
        ? Long.MAX_VALUE : timeoutMillis * 1_000_000L;
    long result = now + nanos;
    return result < now ? Long.MAX_VALUE : result;
  }

  private static boolean expired(long deadline) { return System.nanoTime() >= deadline; }

  private static boolean sleepPoll() {
    try {
      Thread.sleep(POLL_MILLIS);
      return true;
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      return false;
    }
  }

  private static final class Probe {
    private static final Probe EMPTY = new Probe(StatusCode.OK, null);
    final StatusCode status;
    final ControlEntry entry;

    private Probe(StatusCode status, ControlEntry entry) {
      this.status = status;
      this.entry = entry;
    }

    static Probe empty() { return EMPTY; }
    static Probe present(ControlEntry entry) { return new Probe(StatusCode.OK, entry); }
    static Probe failure(StatusCode status) { return new Probe(status, null); }
  }

  private static final class ControlEntry {
    final String name;
    final FileIdentity identity;
    final RiverDaemonStopRequest.Record record;

    ControlEntry(String name, FileIdentity identity, RiverDaemonStopRequest.Record record) {
      this.name = name;
      this.identity = identity;
      this.record = record;
    }
  }

  private record Stage(StatusCode status, FileIdentity identity) {
    static Stage failure(StatusCode status) { return new Stage(status, null); }
  }

  private static final class Scan {
    final ControlEntry[] entries;
    int entryCount;
    ControlEntry request;
    ControlEntry accepted;
    int acceptedCount;
    StatusCode status = StatusCode.OK;

    Scan(int capacity) {
      entries = new ControlEntry[Math.max(1, capacity)];
    }

    static Scan failure(StatusCode status) {
      Scan scan = new Scan(1);
      scan.status = status;
      return scan;
    }
  }
}
