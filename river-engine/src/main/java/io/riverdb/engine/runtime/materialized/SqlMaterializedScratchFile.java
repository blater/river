package io.riverdb.engine.runtime.materialized;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.error.StatusDetail;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/** Retained CREATE_NEW scratch channel; its store is the sole close owner. */
public final class SqlMaterializedScratchFile {
  private final SqlMaterializedScratchOwner owner;
  private final long fileIdentity;
  private final Path path;
  private final SqlMaterializedFilePageIo pageIo;
  private FileChannel channel;

  private SqlMaterializedScratchFile(
      SqlMaterializedScratchOwner owningQuery,
      long identity,
      Path retainedPath,
      FileChannel retainedChannel) {
    owner = owningQuery;
    fileIdentity = identity;
    path = retainedPath;
    channel = retainedChannel;
    pageIo = new SqlMaterializedFilePageIo(identity, retainedChannel);
  }

  static StatusCode create(
      SqlMaterializedScratchOwner owner,
      long identity,
      Path path,
      Result target,
      StatusDetail detail) {
    target.reset();
    if (owner == null || owner.identity() <= 0 || identity <= 0 || path == null) {
      return fail(detail, StatusCode.INVALID_EXTERNAL_INPUT, "invalid scratch file identity");
    }
    FileChannel opened = null;
    try {
      opened = FileChannel.open(
          path,
          StandardOpenOption.CREATE_NEW,
          StandardOpenOption.READ,
          StandardOpenOption.WRITE);
      SqlMaterializedScratchFile file =
          new SqlMaterializedScratchFile(owner, identity, path, opened);
      target.set(file);
      return StatusCode.OK;
    } catch (IOException | SecurityException failure) {
      closeAndDelete(opened, path);
      return fail(detail, StatusCode.IO_FAILURE, "cannot create materialized scratch file");
    } catch (OutOfMemoryError failure) {
      closeAndDelete(opened, path);
      return fail(detail, StatusCode.RESOURCE_EXHAUSTED, "cannot retain materialized scratch file");
    }
  }

  void cleanup(SqlMaterializedScratchCleanup.State state) {
    FileChannel retained = channel;
    if (retained != null) {
      try {
        retained.close();
      } catch (IOException | SecurityException failure) {
        state.record(StatusCode.IO_FAILURE, path);
      }
      if (!retained.isOpen()) channel = null;
    }
    SqlMaterializedScratchCleanup.deleteTree(path, state);
  }

  StatusCode truncate() {
    FileChannel retained = channel;
    if (retained == null) return StatusCode.CLOSED;
    try {
      retained.truncate(0);
      return StatusCode.OK;
    } catch (IOException | SecurityException failure) {
      return StatusCode.IO_FAILURE;
    }
  }

  boolean ownedBy(SqlMaterializedScratchOwner expected) { return owner == expected; }
  SqlMaterializedPageIo pageIo() { return pageIo; }
  public long ownerIdentity() { return owner.identity(); }
  public long fileIdentity() { return fileIdentity; }
  public Path path() { return path; }
  /** Borrowed channel valid until the owning query closes; callers must not close it. */
  public FileChannel channel() { return channel; }

  private static void closeAndDelete(FileChannel opened, Path path) {
    if (opened == null) return;
    try { opened.close(); } catch (IOException | SecurityException ignored) { }
    try { Files.deleteIfExists(path); } catch (IOException | SecurityException ignored) { }
  }

  private static StatusCode fail(StatusDetail detail, StatusCode code, String message) {
    if (detail != null) detail.set(code).append(message);
    return code;
  }

  public static final class Result {
    private SqlMaterializedScratchFile file;

    public void reset() { file = null; }
    void set(SqlMaterializedScratchFile value) { file = value; }
    public SqlMaterializedScratchFile file() { return file; }
  }
}
