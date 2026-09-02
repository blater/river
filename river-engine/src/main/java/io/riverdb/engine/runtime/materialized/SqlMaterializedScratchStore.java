package io.riverdb.engine.runtime.materialized;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.error.StatusDetail;
import java.nio.file.Path;

/** One runtime-named store directory and its bounded set of retained channels. */
public final class SqlMaterializedScratchStore {
  private final SqlMaterializedScratchOwner owner;
  private final Path path;
  private final SqlMaterializedScratchFile[] files =
      new SqlMaterializedScratchFile[SqlMaterializedScratchFileKind.count()];
  SqlMaterializedScratchStore next;

  SqlMaterializedScratchStore(SqlMaterializedScratchOwner owningQuery, Path retainedPath) {
    owner = owningQuery;
    path = retainedPath;
  }

  public StatusCode open(
      SqlMaterializedScratchFileKind kind,
      SqlMaterializedScratchFile.Result target,
      StatusDetail detail) {
    if (target == null) return StatusCode.INVALID_EXTERNAL_INPUT;
    target.reset();
    if (detail != null) detail.reset();
    if (kind == null) {
      return fail(
          detail, StatusCode.INVALID_EXTERNAL_INPUT,
          "invalid scratch file kind");
    }
    long identity = owner.allocateFileIdentity();
    if (identity <= 0) {
      return owner.usable()
          ? fail(detail, StatusCode.RESOURCE_EXHAUSTED, "scratch file identity exhausted")
          : fail(detail, StatusCode.CLOSED, "scratch owner is closing");
    }
    synchronized (owner) {
      return openOwned(kind, identity, target, detail);
    }
  }

  private StatusCode openOwned(
      SqlMaterializedScratchFileKind kind,
      long identity,
      SqlMaterializedScratchFile.Result target,
      StatusDetail detail) {
    if (!owner.usable()) return fail(detail, StatusCode.CLOSED, "scratch owner is closing");
    int slot = kind.ordinal();
    if (files[slot] != null) {
      return fail(detail, StatusCode.CONFLICT, "scratch file already exists");
    }
    Path filePath;
    try {
      filePath = path.resolve(kind.fileName());
    } catch (OutOfMemoryError failure) {
      return fail(detail, StatusCode.RESOURCE_EXHAUSTED, "cannot retain scratch file path");
    }
    StatusCode status = SqlMaterializedScratchFile.create(
        owner, identity, filePath, target, detail);
    if (status.isOk()) files[slot] = target.file();
    return status;
  }

  void cleanup(SqlMaterializedScratchCleanup.State state) {
    long checkpoint = state.checkpoint();
    for (int index = 0; index < files.length; index++) {
      SqlMaterializedScratchFile file = files[index];
      if (file != null) file.cleanup(state);
    }
    SqlMaterializedScratchCleanup.deleteTree(path, state);
    if (state.unchanged(checkpoint)) {
      for (int index = 0; index < files.length; index++) files[index] = null;
    }
  }

  public Path path() { return path; }

  private static StatusCode fail(StatusDetail detail, StatusCode code, String message) {
    if (detail != null) detail.set(code).append(message);
    return code;
  }

  public static final class Result {
    private SqlMaterializedScratchStore store;

    public void reset() { store = null; }
    void set(SqlMaterializedScratchStore value) { store = value; }
    public SqlMaterializedScratchStore store() { return store; }
  }
}
