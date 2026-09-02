package io.riverdb.engine.runtime.materialized;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.error.StatusDetail;
import java.nio.ByteBuffer;

/** Runtime-owned append/read stream over the materialized page pool. */
public final class SqlMaterializedPagedByteStream {
  private final SqlMaterializedPagedByteStreamState state;

  private SqlMaterializedPagedByteStream(SqlMaterializedPagedByteStreamState retainedState) {
    state = retainedState;
  }

  public static StatusCode createNew(
      SqlMaterializedScratchOwner owner, SqlMaterializedScratchFile file,
      SqlMaterializedScratchFileKind kind, int pageBytes, int fixedRecordBytes, int flags,
      Result target, StatusDetail detail) {
    if (target == null) return StatusCode.INVALID_EXTERNAL_INPUT;
    target.reset();
    if (detail != null) detail.reset();
    if (!SqlMaterializedPagedByteStreamLifecycle.valid(
        owner, file, kind, pageBytes, fixedRecordBytes, flags, detail)) {
      return detail == null ? StatusCode.INVALID_EXTERNAL_INPUT : detail.code();
    }
    try {
      SqlMaterializedPagedByteStreamState created = new SqlMaterializedPagedByteStreamState(
          owner, file, kind, pageBytes, fixedRecordBytes, flags);
      StatusCode status = SqlMaterializedPagedByteStreamLifecycle.initializeNew(created, detail);
      if (status.isOk()) target.set(new SqlMaterializedPagedByteStream(created));
      return status;
    } catch (OutOfMemoryError failure) {
      return SqlMaterializedPagedByteStreamLifecycle.fail(
          detail, StatusCode.RESOURCE_EXHAUSTED, "cannot retain materialized byte stream");
    }
  }

  public static StatusCode openExisting(
      SqlMaterializedScratchOwner owner, SqlMaterializedScratchFile file,
      SqlMaterializedScratchFileKind kind, int pageBytes, Result target, StatusDetail detail) {
    if (target == null) return StatusCode.INVALID_EXTERNAL_INPUT;
    target.reset();
    if (detail != null) detail.reset();
    if (!SqlMaterializedPagedByteStreamLifecycle.valid(
        owner, file, kind, pageBytes, 0, 0, detail)) {
      return detail == null ? StatusCode.INVALID_EXTERNAL_INPUT : detail.code();
    }
    try {
      SqlMaterializedPagedByteStreamState opened = new SqlMaterializedPagedByteStreamState(
          owner, file, kind, pageBytes, 0, 0);
      StatusCode status = SqlMaterializedPagedByteStreamLifecycle.initializeExisting(opened, detail);
      if (status.isOk()) target.set(new SqlMaterializedPagedByteStream(opened));
      return status;
    } catch (OutOfMemoryError failure) {
      return SqlMaterializedPagedByteStreamLifecycle.fail(
          detail, StatusCode.RESOURCE_EXHAUSTED, "cannot retain materialized byte stream");
    }
  }

  public StatusCode append(ByteBuffer source, AppendResult target, StatusDetail detail) {
    return SqlMaterializedPagedByteStreamAppend.one(state, source, target, detail);
  }

  public StatusCode appendBytes(
      ByteBuffer source, long recordIncrement, AppendResult target, StatusDetail detail) {
    return SqlMaterializedPagedByteStreamAppend.bytes(state, source, recordIncrement, target, detail);
  }

  public StatusCode read(long offset, ByteBuffer target, StatusDetail detail) {
    return SqlMaterializedPagedByteStreamRead.read(state, offset, target, detail);
  }

  public StatusCode overwrite(long offset, ByteBuffer source, StatusDetail detail) {
    return SqlMaterializedPagedByteStreamOverwrite.write(state, offset, source, detail);
  }

  public StatusCode resetForReuse(Result target, StatusDetail detail) {
    if (target == null) return StatusCode.INVALID_EXTERNAL_INPUT;
    target.reset();
    StatusCode status = SqlMaterializedPagedByteStreamLifecycle.reset(state, detail);
    if (status.isOk()) target.set(this);
    return status;
  }

  public StatusCode close(StatusDetail detail) {
    return SqlMaterializedPagedByteStreamLifecycle.close(state, detail);
  }

  public StatusCode seal(StatusDetail detail) {
    return SqlMaterializedPagedByteStreamLifecycle.seal(state, detail);
  }
  public long publishedCount() { return state.publishedCount; }
  public long logicalLength() { return state.logicalLength; }
  public int pageBytes() { return state.pageBytes; }
  public int fixedRecordBytes() { return state.fixedRecordBytes; }
  public SqlMaterializedScratchFileKind kind() { return state.kind; }
  public int flags() { return state.flags; }
  public boolean isFailed() { return state.failed; }
  public boolean isClosed() { return state.closed; }

  /** Caller-owned append publication fields. */
  public static final class AppendResult {
    private long offset;
    private int bytes;
    private long newLength;
    private long newCount;

    public void reset() {
      offset = 0;
      bytes = 0;
      newLength = 0;
      newCount = 0;
    }

    void set(long newOffset, int newBytes, long length, long count) {
      offset = newOffset;
      bytes = newBytes;
      newLength = length;
      newCount = count;
    }

    public long offset() { return offset; }
    public int bytes() { return bytes; }
    public long newLength() { return newLength; }
    public long newCount() { return newCount; }
  }

  /** Caller-owned stream publication carrier. */
  public static final class Result {
    private SqlMaterializedPagedByteStream stream;

    public void reset() { stream = null; }
    private void set(SqlMaterializedPagedByteStream value) { stream = value; }
    public SqlMaterializedPagedByteStream stream() { return stream; }
  }
}
