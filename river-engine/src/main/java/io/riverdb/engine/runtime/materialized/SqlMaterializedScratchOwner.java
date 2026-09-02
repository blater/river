package io.riverdb.engine.runtime.materialized;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.error.StatusDetail;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Query-local scratch owner whose close invalidates cached pages before closing files. */
public final class SqlMaterializedScratchOwner {
  private final SqlMaterializedScratchRuntime runtime;
  private final SqlMaterializedPagePool pagePool;
  private final long identity;
  private final Path path;
  private final SqlMaterializedScratchCleanup.State cleanup =
      new SqlMaterializedScratchCleanup.State();
  private SqlMaterializedScratchStore stores;
  SqlMaterializedScratchOwner next;
  private boolean closing;
  private boolean closed;

  SqlMaterializedScratchOwner(
      SqlMaterializedScratchRuntime owningRuntime,
      SqlMaterializedPagePool pages,
      long ownerIdentity,
      Path retainedPath) {
    runtime = owningRuntime;
    pagePool = pages;
    identity = ownerIdentity;
    path = retainedPath;
  }

  public synchronized StatusCode openStore(
      SqlMaterializedScratchStore.Result target,
      StatusDetail detail) {
    if (target == null) return StatusCode.INVALID_EXTERNAL_INPUT;
    target.reset();
    if (detail != null) detail.reset();
    if (!usable()) return fail(detail, StatusCode.CLOSED, "scratch owner is closing");
    Path directory = null;
    try {
      directory = Files.createTempDirectory(path, "store-");
      SqlMaterializedScratchStore store = new SqlMaterializedScratchStore(this, directory);
      store.next = stores;
      stores = store;
      target.set(store);
      return StatusCode.OK;
    } catch (IOException | SecurityException failure) {
      if (directory != null) SqlMaterializedScratchCleanup.deleteUnreported(directory);
      return fail(detail, StatusCode.IO_FAILURE, "cannot create materialized store directory");
    } catch (OutOfMemoryError failure) {
      if (directory != null) SqlMaterializedScratchCleanup.deleteUnreported(directory);
      return fail(detail, StatusCode.RESOURCE_EXHAUSTED, "cannot retain materialized store");
    }
  }

  public synchronized StatusCode pinNew(
      SqlMaterializedScratchFile file,
      long pageNumber,
      SqlMaterializedPagePin target) {
    StatusCode validation = validatePin(file);
    return validation.isOk()
        ? pagePool.pinNew(file.pageIo(), identity, pageNumber, target)
        : validation;
  }

  public synchronized StatusCode pinExisting(
      SqlMaterializedScratchFile file,
      long pageNumber,
      SqlMaterializedPagePin target) {
    StatusCode validation = validatePin(file);
    return validation.isOk()
        ? pagePool.pinExisting(file.pageIo(), identity, pageNumber, target)
        : validation;
  }

  public synchronized StatusCode markDirty(SqlMaterializedPagePin pin) {
    if (!usable()) return StatusCode.CLOSED;
    if (pin == null || pin.owner() != identity) return StatusCode.NOT_OWNER;
    return pagePool.markDirty(pin);
  }

  public synchronized StatusCode unpin(SqlMaterializedPagePin pin) {
    if (pin == null || pin.owner() != identity) return StatusCode.NOT_OWNER;
    return pagePool.unpin(pin);
  }

  public synchronized StatusCode invalidate(SqlMaterializedScratchFile file) {
    StatusCode validation = validatePin(file);
    return validation.isOk()
        ? pagePool.invalidateFile(identity, file.fileIdentity())
        : validation;
  }

  public synchronized StatusCode flush(SqlMaterializedScratchFile file) {
    StatusCode validation = validatePin(file);
    return validation.isOk()
        ? pagePool.flushFile(identity, file.fileIdentity())
        : validation;
  }

  public synchronized StatusCode reservePages(int count) {
    if (!usable()) return StatusCode.CLOSED;
    return pagePool.reserve(identity, count);
  }

  public synchronized StatusCode releasePages() {
    if (!usable()) return StatusCode.CLOSED;
    return pagePool.releaseReservation(identity);
  }

  public synchronized StatusCode releasePages(int count) {
    if (!usable()) return StatusCode.CLOSED;
    return pagePool.releaseReservation(identity, count);
  }

  public StatusCode close(StatusDetail detail) {
    StatusCode status;
    boolean detach;
    synchronized (this) {
      if (closed) {
        if (detail != null) detail.reset();
        return StatusCode.OK;
      }
      SqlMaterializedScratchCleanup.State state = cleanup.begin(detail);
      cleanupLocked(state);
      status = state.status();
      detach = closed;
    }
    if (detach) runtime.detach(this);
    return status;
  }

  synchronized void cleanup(SqlMaterializedScratchCleanup.State state) {
    cleanupLocked(state);
  }

  private void cleanupLocked(SqlMaterializedScratchCleanup.State state) {
    closing = true;
    long checkpoint = state.checkpoint();
    StatusCode invalidation = pagePool.invalidateOwner(identity);
    if (!invalidation.isOk()) state.record(invalidation, path);
    SqlMaterializedScratchStore store = stores;
    while (store != null) {
      store.cleanup(state);
      store = store.next;
    }
    SqlMaterializedScratchCleanup.deleteTree(path, state);
    if (state.unchanged(checkpoint)) {
      stores = null;
      closed = true;
    }
  }

  synchronized boolean usable() { return !closing && !closed; }
  long allocateFileIdentity() { return runtime.allocateFileIdentity(); }
  public long identity() { return identity; }
  public Path path() { return path; }
  public synchronized boolean isClosed() { return closed; }

  private StatusCode validatePin(SqlMaterializedScratchFile file) {
    if (!usable()) return StatusCode.CLOSED;
    return file != null && file.ownedBy(this)
        ? StatusCode.OK : StatusCode.NOT_OWNER;
  }

  private static StatusCode fail(StatusDetail detail, StatusCode code, String message) {
    if (detail != null) detail.set(code).append(message);
    return code;
  }

  public static final class Result {
    private SqlMaterializedScratchOwner owner;

    public void reset() { owner = null; }
    void set(SqlMaterializedScratchOwner value) { owner = value; }
    public SqlMaterializedScratchOwner owner() { return owner; }
  }
}
