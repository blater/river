package io.riverdb.engine.runtime.materialized;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.error.StatusDetail;

/** Construction, header admission, and close gate for one stream state. */
final class SqlMaterializedPagedByteStreamLifecycle {
  private SqlMaterializedPagedByteStreamLifecycle() {}

  static boolean valid(
      SqlMaterializedScratchOwner owner, SqlMaterializedScratchFile file,
      SqlMaterializedScratchFileKind kind, int pageBytes, int fixedBytes, int flags,
      StatusDetail detail) {
    if (owner == null || file == null || kind == null || !file.ownedBy(owner)
        || pageBytes <= SqlMaterializedScratchFileCodec.PAGE_HEADER_BYTES
        || (pageBytes & 7) != 0 || fixedBytes < 0
        || (flags & ~SqlMaterializedScratchFileCodec.KNOWN_FLAGS) != 0) {
      fail(detail, StatusCode.INVALID_EXTERNAL_INPUT, "invalid materialized stream arguments");
      return false;
    }
    return true;
  }

  static StatusCode initializeNew(SqlMaterializedPagedByteStreamState state, StatusDetail detail) {
    StatusCode status = SqlMaterializedScratchFileCodec.encodeFileHeader(
        state.headerBuffer, state.kind, state.pageBytes, state.fixedRecordBytes, state.flags,
        state.file.fileIdentity(), 0, 0);
    return status.isOk() ? SqlMaterializedPagedByteStreamHeader.write(state, detail) : status;
  }

  static StatusCode initializeExisting(
      SqlMaterializedPagedByteStreamState state, StatusDetail detail) {
    StatusCode status = SqlMaterializedPagedByteStreamHeader.read(state, detail);
    if (status.isOk()) {
      state.fixedRecordBytes = state.header.fixedRecordBytes();
      state.publishedCount = state.header.publishedCount();
      state.logicalLength = state.header.logicalLength();
    }
    return status;
  }

  static StatusCode close(SqlMaterializedPagedByteStreamState state, StatusDetail detail) {
    if (detail != null) detail.reset();
    if (state.closed) return StatusCode.OK;
    if (state.pin.active()) {
      StatusCode status = state.owner.unpin(state.pin);
      if (!status.isOk()) return status;
    }
    StatusCode status = state.owner.invalidate(state.file);
    if (!status.isOk()) return status;
    state.closed = true;
    return StatusCode.OK;
  }

  static StatusCode seal(SqlMaterializedPagedByteStreamState state, StatusDetail detail) {
    if (detail != null) detail.reset();
    if (state.closed) return StatusCode.CLOSED;
    if (state.pin.active()) return StatusCode.CONFLICT;
    StatusCode status = state.owner.flush(state.file);
    if (status.isOk()) status = writeHeader(state, detail);
    if (status.isOk()) state.closed = true;
    return status;
  }

  private static StatusCode writeHeader(
      SqlMaterializedPagedByteStreamState state, StatusDetail detail) {
    StatusCode status = SqlMaterializedScratchFileCodec.encodeFileHeader(
        state.headerBuffer, state.kind, state.pageBytes, state.fixedRecordBytes, state.flags,
        state.file.fileIdentity(), state.publishedCount, state.logicalLength);
    return status.isOk() ? SqlMaterializedPagedByteStreamHeader.write(state, detail) : status;
  }

  static StatusCode reset(SqlMaterializedPagedByteStreamState state, StatusDetail detail) {
    if (detail != null) detail.reset();
    if (!state.closed || state.pin.active()) {
      return fail(detail, StatusCode.CONFLICT, "materialized stream is still active");
    }
    StatusCode status = state.owner.invalidate(state.file);
    if (status.isOk()) status = state.file.truncate();
    if (!status.isOk()) return fail(detail, status, "cannot reset materialized stream");
    state.publishedCount = 0;
    state.logicalLength = 0;
    state.failed = false;
    state.failureStatus = StatusCode.OK;
    state.copied = 0;
    state.position = 0;
    status = initializeNew(state, detail);
    if (status.isOk()) state.closed = false;
    else {
      state.failed = true;
      state.failureStatus = status;
    }
    return status;
  }

  static boolean usable(SqlMaterializedPagedByteStreamState state, StatusDetail detail) {
    if (state.closed) {
      fail(detail, StatusCode.CLOSED, "materialized stream is closed");
      return false;
    }
    if (state.failed) {
      fail(detail, state.failureStatus, "materialized stream is terminally failed");
      return false;
    }
    return true;
  }

  static StatusCode invalidInput(StatusDetail detail) {
    if (detail != null && detail.code() == StatusCode.OK) {
      detail.set(StatusCode.INVALID_EXTERNAL_INPUT).append("invalid materialized stream operation");
    }
    return detail == null || detail.code() == StatusCode.OK
        ? StatusCode.INVALID_EXTERNAL_INPUT : detail.code();
  }

  static StatusCode fail(StatusDetail detail, StatusCode code, String message) {
    if (detail != null) detail.set(code).append(message);
    return code;
  }
}
