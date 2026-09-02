package io.riverdb.engine.runtime.materialized;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.error.StatusDetail;
import io.riverdb.base.id.DatabaseIncarnation;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Database-open lifetime for exact retained scratch paths and positive identities. */
public final class SqlMaterializedScratchRuntime {
  private final SqlMaterializedPagePool pagePool;
  private final Path namespacePath;
  private final Path instancePath;
  private final Path primaryPath;
  private final SqlMaterializedScratchOwnership ownership;
  private final SqlMaterializedScratchCleanup.State cleanup =
      new SqlMaterializedScratchCleanup.State();
  private long nextOwnerIdentity = 1;
  private long nextFileIdentity = 1;
  private SqlMaterializedScratchOwner owners;
  private boolean closing;
  private boolean closed;

  private SqlMaterializedScratchRuntime(
      SqlMaterializedPagePool pages,
      Path namespace,
      Path instance,
      Path primary,
      SqlMaterializedScratchOwnership namespaceOwnership) {
    pagePool = pages;
    namespacePath = namespace;
    instancePath = instance;
    primaryPath = primary;
    ownership = namespaceOwnership;
  }

  public static StatusCode create(
      Path spillRoot,
      Path authoritativePrimaryPath,
      DatabaseIncarnation database,
      SqlMaterializedPagePool pagePool,
      OpenResult target,
      StatusDetail detail) {
    if (target == null) return StatusCode.INVALID_EXTERNAL_INPUT;
    target.reset();
    if (detail != null) detail.reset();
    if (pagePool == null) {
      return fail(detail, StatusCode.INVALID_EXTERNAL_INPUT, "missing page pool");
    }
    SqlMaterializedScratchNamespace.Result paths;
    try {
      paths = new SqlMaterializedScratchNamespace.Result();
    } catch (OutOfMemoryError failure) {
      return fail(detail, StatusCode.RESOURCE_EXHAUSTED, "cannot create scratch result");
    }
    StatusCode status = SqlMaterializedScratchNamespace.create(
        spillRoot, authoritativePrimaryPath, database, paths, detail);
    if (!status.isOk()) return status;
    try {
      SqlMaterializedScratchRuntime runtime = new SqlMaterializedScratchRuntime(
          pagePool, paths.namespace(), paths.instance(), paths.primary(), paths.ownership());
      target.set(runtime);
      return StatusCode.OK;
    } catch (OutOfMemoryError failure) {
      SqlMaterializedScratchCleanup.deleteUnreported(paths.instance());
      paths.ownership().close(new SqlMaterializedScratchCleanup.State().begin(null));
      return fail(detail, StatusCode.RESOURCE_EXHAUSTED, "cannot retain scratch runtime");
    }
  }

  public synchronized StatusCode openOwner(
      SqlMaterializedScratchOwner.Result target,
      StatusDetail detail) {
    if (target == null) return StatusCode.INVALID_EXTERNAL_INPUT;
    target.reset();
    if (detail != null) detail.reset();
    if (closing || closed) {
      return fail(detail, StatusCode.CLOSED, "scratch runtime is closing");
    }
    long identity = takeOwnerIdentity();
    if (identity <= 0) {
      return fail(
          detail, StatusCode.RESOURCE_EXHAUSTED,
          "scratch owner identity exhausted");
    }
    Path directory = null;
    try {
      directory = Files.createTempDirectory(instancePath, "query-");
      SqlMaterializedScratchOwner owner =
          new SqlMaterializedScratchOwner(this, pagePool, identity, directory);
      owner.next = owners;
      owners = owner;
      target.set(owner);
      return StatusCode.OK;
    } catch (IOException | SecurityException failure) {
      if (directory != null) SqlMaterializedScratchCleanup.deleteUnreported(directory);
      return fail(detail, StatusCode.IO_FAILURE, "cannot create query scratch directory");
    } catch (OutOfMemoryError failure) {
      if (directory != null) SqlMaterializedScratchCleanup.deleteUnreported(directory);
      return fail(detail, StatusCode.RESOURCE_EXHAUSTED, "cannot retain query scratch owner");
    }
  }

  public synchronized StatusCode close(StatusDetail detail) {
    if (closed) {
      if (detail != null) detail.reset();
      return StatusCode.OK;
    }
    closing = true;
    SqlMaterializedScratchCleanup.State state = cleanup.begin(detail);
    SqlMaterializedScratchOwner owner = owners;
    while (owner != null) {
      SqlMaterializedScratchOwner next = owner.next;
      long checkpoint = state.checkpoint();
      owner.cleanup(state);
      if (state.unchanged(checkpoint)) detach(owner);
      owner = next;
    }
    SqlMaterializedScratchCleanup.deleteTree(instancePath, state);
    if (state.status().isOk()) ownership.close(state);
    if (state.status().isOk()) closed = true;
    return state.status();
  }

  synchronized long allocateFileIdentity() {
    if (closing || closed) return 0;
    return takeFileIdentity();
  }

  synchronized void detach(SqlMaterializedScratchOwner target) {
    SqlMaterializedScratchOwner previous = null;
    SqlMaterializedScratchOwner current = owners;
    while (current != null && current != target) {
      previous = current;
      current = current.next;
    }
    if (current == null) return;
    if (previous == null) owners = current.next;
    else previous.next = current.next;
    current.next = null;
  }

  public Path namespacePath() { return namespacePath; }
  public Path instancePath() { return instancePath; }
  public Path primaryPath() { return primaryPath; }
  public synchronized boolean isClosed() { return closed; }

  private long takeOwnerIdentity() {
    long identity = nextOwnerIdentity;
    if (identity <= 0) return 0;
    nextOwnerIdentity = identity == Long.MAX_VALUE ? 0 : identity + 1;
    return identity;
  }

  private long takeFileIdentity() {
    long identity = nextFileIdentity;
    if (identity <= 0) return 0;
    nextFileIdentity = identity == Long.MAX_VALUE ? 0 : identity + 1;
    return identity;
  }

  private static StatusCode fail(StatusDetail detail, StatusCode code, String message) {
    if (detail != null) detail.set(code).append(message);
    return code;
  }

  public static final class OpenResult {
    private SqlMaterializedScratchRuntime runtime;

    public void reset() { runtime = null; }
    void set(SqlMaterializedScratchRuntime value) { runtime = value; }
    public SqlMaterializedScratchRuntime runtime() { return runtime; }
  }
}
