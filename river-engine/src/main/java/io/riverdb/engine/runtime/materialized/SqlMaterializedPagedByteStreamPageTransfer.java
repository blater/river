package io.riverdb.engine.runtime.materialized;

import io.riverdb.base.error.StatusCode;
import java.nio.ByteBuffer;

/** One pinned page copy, including validation/finalization and unconditional unpin. */
final class SqlMaterializedPagedByteStreamPageTransfer {
  private SqlMaterializedPagedByteStreamPageTransfer() {}

  static StatusCode append(
      SqlMaterializedPagedByteStreamState state, ByteBuffer source, int sourceStart,
      int bytes, boolean fresh) {
    StatusCode status = fresh
        ? state.owner.pinNew(state.file, state.location.pageNumber(), state.pin)
        : state.owner.pinExisting(state.file, state.location.pageNumber(), state.pin);
    if (!status.isOk()) return status;
    StatusCode operation = StatusCode.OK;
    try {
      operation = prepareAppend(state, fresh);
      int chunk = Math.min(bytes - state.copied, state.location.payloadRemaining());
      if (operation.isOk() && chunk > 0) {
        copyIntoPage(state, source, sourceStart, chunk);
        state.mutated = true;
        operation = finishAppend(state, fresh, chunk);
      }
    } finally {
      StatusCode unpin = state.owner.unpin(state.pin);
      if (operation.isOk() && !unpin.isOk()) operation = unpin;
    }
    return operation;
  }

  static StatusCode read(
      SqlMaterializedPagedByteStreamState state, ByteBuffer target, int targetStart, int bytes) {
    StatusCode status = state.owner.pinExisting(
        state.file, state.location.pageNumber(), state.pin);
    if (!status.isOk()) return status;
    StatusCode operation = StatusCode.OK;
    try {
      state.internalDetail.reset();
      operation = validate(state);
      int pageOffset = state.location.payloadOffset()
          - SqlMaterializedScratchFileCodec.PAGE_HEADER_BYTES;
      int chunk = Math.min(bytes - state.copied, state.location.payloadRemaining());
      if (operation.isOk() && (pageOffset < 0 || pageOffset + chunk > state.pageHeader.usedBytes())) {
        operation = SqlMaterializedPagedByteStreamLifecycle.fail(
            state.internalDetail, StatusCode.CORRUPTION,
            "materialized page read exceeds used length");
      }
      if (operation.isOk()) {
        copyFromPage(state, target, targetStart, chunk);
        state.copied += chunk;
        state.position += chunk;
      }
    } finally {
      StatusCode unpin = state.owner.unpin(state.pin);
      if (operation.isOk() && !unpin.isOk()) operation = unpin;
    }
    return operation;
  }

  static StatusCode overwrite(
      SqlMaterializedPagedByteStreamState state, ByteBuffer source, int sourceStart, int bytes) {
    StatusCode status = state.owner.pinExisting(
        state.file, state.location.pageNumber(), state.pin);
    if (!status.isOk()) return status;
    StatusCode operation = StatusCode.OK;
    try {
      state.internalDetail.reset();
      operation = validate(state);
      int pageOffset = state.location.payloadOffset()
          - SqlMaterializedScratchFileCodec.PAGE_HEADER_BYTES;
      int chunk = Math.min(bytes - state.copied, state.location.payloadRemaining());
      if (operation.isOk()
          && (pageOffset < 0 || pageOffset + chunk > state.pageHeader.usedBytes())) {
        operation = StatusCode.CORRUPTION;
      }
      if (operation.isOk()) {
        copyIntoPage(state, source, sourceStart, chunk);
        state.mutated = true;
        operation = SqlMaterializedScratchFileCodec.updateResidentPageHeader(
            state.pin.buffer(), state.file.fileIdentity(), state.location.pageNumber(),
            state.pageHeader.usedBytes());
        if (operation.isOk()) operation = state.owner.markDirty(state.pin);
        if (operation.isOk()) {
          state.copied += chunk;
          state.position += chunk;
        }
      }
    } finally {
      StatusCode unpin = state.owner.unpin(state.pin);
      if (operation.isOk() && !unpin.isOk()) operation = unpin;
    }
    return operation;
  }

  private static StatusCode prepareAppend(
      SqlMaterializedPagedByteStreamState state, boolean fresh) {
    if (fresh) {
      return SqlMaterializedScratchFileCodec.encodePageHeader(
          state.pin.buffer(), state.file.fileIdentity(), state.location.pageNumber(), 0);
    }
    state.internalDetail.reset();
    StatusCode status = validate(state);
    if (status.isOk()
        && state.location.payloadOffset() - SqlMaterializedScratchFileCodec.PAGE_HEADER_BYTES
            > state.pageHeader.usedBytes()) {
      return SqlMaterializedPagedByteStreamLifecycle.fail(
          state.internalDetail, StatusCode.CORRUPTION, "materialized page has a gap");
    }
    return status;
  }

  private static void copyIntoPage(
      SqlMaterializedPagedByteStreamState state, ByteBuffer source, int sourceStart, int chunk) {
    for (int index = 0; index < chunk; index++) {
      state.pin.buffer().put(
          state.location.payloadOffset() + index,
          source.get(sourceStart + state.copied + index));
    }
  }

  private static StatusCode finishAppend(
      SqlMaterializedPagedByteStreamState state, boolean fresh, int chunk) {
    int used = state.location.payloadOffset()
        - SqlMaterializedScratchFileCodec.PAGE_HEADER_BYTES + chunk;
    if (!fresh && state.pageHeader.usedBytes() > used) used = state.pageHeader.usedBytes();
    StatusCode status = SqlMaterializedScratchFileCodec.updateResidentPageHeader(
        state.pin.buffer(), state.file.fileIdentity(), state.location.pageNumber(), used);
    if (status.isOk()) status = state.owner.markDirty(state.pin);
    if (status.isOk()) {
      state.copied += chunk;
      state.position += chunk;
    }
    return status;
  }

  private static void copyFromPage(
      SqlMaterializedPagedByteStreamState state, ByteBuffer target, int targetStart, int chunk) {
    for (int index = 0; index < chunk; index++) {
      target.put(targetStart + state.copied + index,
          state.pin.buffer().get(state.location.payloadOffset() + index));
    }
  }

  private static StatusCode validate(SqlMaterializedPagedByteStreamState state) {
    return SqlMaterializedScratchFileCodec.validateResidentPageHeader(
        state.pin.buffer(), state.file.fileIdentity(), state.location.pageNumber(),
        state.pageHeader, state.internalDetail);
  }
}
