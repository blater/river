package io.riverdb.server;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.api.RetainedMemoryLease;
import io.riverdb.engine.api.TransactionProgramResult;
import io.riverdb.engine.api.TransactionProgramResultAdmission;
import io.riverdb.protocol.ProtocolFrameCodec;
import io.riverdb.protocol.ProtocolRowBatch;
import java.nio.ByteBuffer;

/** Geometrically retained response target with encode-only retry. */
final class ServerResponseBuffer implements TransactionProgramResultAdmission {
  private static final int BATCH_BYTES = ProtocolFrameCodec.MAXIMUM_FRAME_BYTES;
  private final RetainedMemoryLease memory;
  private byte[] bytes = new byte[ProtocolFrameCodec.MAXIMUM_FRAME_BYTES];
  private ByteBuffer buffer = ByteBuffer.wrap(bytes);
  private byte[] batchBytes;
  private ByteBuffer batchBuffer;
  private int batchLength;

  ServerResponseBuffer(RetainedMemoryLease retainedMemory) {
    memory = retainedMemory;
    if (!memory.resize(bytes.length).isOk()) {
      throw new IllegalArgumentException("response memory lease");
    }
  }

  StatusCode process(SessionEndpoint endpoint, ByteBuffer request) {
    batchLength = 0;
    StatusCode status = endpoint.process(request, buffer);
    boolean maximumReserved = false;
    while (status == StatusCode.RESOURCE_EXHAUSTED) {
      releaseBatch();
      if (!maximumReserved) {
        StatusCode reserved = memory.awaitResize(ProtocolFrameCodec.MAXIMUM_RESPONSE_BYTES);
        if (!reserved.isOk()) return reserved;
        maximumReserved = true;
      }
      StatusCode grown = growReserved();
      if (!grown.isOk()) return grown;
      status = endpoint.retryResponse(buffer);
    }
    if (status.isOk()) status = packRows(endpoint);
    return status;
  }

  byte[] bytes() { return batchLength > 0 ? batchBytes : bytes; }
  int publishedBytes() { return batchLength > 0 ? batchLength : buffer.remaining(); }
  ByteBuffer buffer() { return buffer; }

  private StatusCode packRows(SessionEndpoint endpoint) {
    int first = buffer.remaining();
    int next = endpoint.nextBatchRowBytes();
    if (next <= 0 || first > BATCH_BYTES - next || !ensureBatch()) return StatusCode.OK;
    System.arraycopy(bytes, 0, batchBytes, 0, first);
    batchLength = first;
    int previousOffset = 0;
    while (next > 0 && next <= BATCH_BYTES - batchLength) {
      batchBuffer.limit(BATCH_BYTES);
      ProtocolRowBatch.markMore(batchBuffer, previousOffset);
      StatusCode status = endpoint.appendBatchRow(buffer);
      if (!status.isOk()) return status;
      int length = buffer.remaining();
      if (length > BATCH_BYTES - batchLength) return StatusCode.INVARIANT_BROKEN;
      previousOffset = batchLength;
      System.arraycopy(bytes, 0, batchBytes, batchLength, length);
      batchLength += length;
      next = endpoint.nextBatchRowBytes();
    }
    return StatusCode.OK;
  }

  private boolean ensureBatch() {
    if (batchBytes != null) return true;
    if (!memory.resize(bytes.length + BATCH_BYTES).isOk()) return false;
    try {
      batchBytes = new byte[BATCH_BYTES];
      batchBuffer = ByteBuffer.wrap(batchBytes);
      return true;
    } catch (OutOfMemoryError failure) {
      memory.resize(bytes.length);
      return false;
    }
  }

  private void releaseBatch() {
    if (batchBytes == null) return;
    batchBytes = null;
    batchBuffer = null;
    batchLength = 0;
    memory.resize(bytes.length);
  }

  @Override
  public StatusCode admit(TransactionProgramResult result) {
    int required = ProtocolFrameCodec.programResultResponseBytes(StatusCode.OK, result);
    if (required > bytes.length) releaseBatch();
    return required < 0 ? StatusCode.RESOURCE_EXHAUSTED
        : required == 0 ? StatusCode.INVALID_EXTERNAL_INPUT : ensureCapacity(required);
  }

  StatusCode releaseHighWater() {
    if (bytes.length == ProtocolFrameCodec.MAXIMUM_FRAME_BYTES) return StatusCode.OK;
    try {
      byte[] released = new byte[ProtocolFrameCodec.MAXIMUM_FRAME_BYTES];
      ByteBuffer view = ByteBuffer.wrap(released);
      int previous = bytes.length;
      bytes = released;
      buffer = view;
      memory.resize(released.length + (batchBytes == null ? 0 : BATCH_BYTES));
      return StatusCode.OK;
    } catch (OutOfMemoryError failure) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
  }

  StatusCode ensureCapacity(int required) {
    if (required < 0 || required > ProtocolFrameCodec.MAXIMUM_RESPONSE_BYTES) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    StatusCode status = StatusCode.OK;
    while (bytes.length < required && status.isOk()) status = growAdmitted();
    return status;
  }

  private StatusCode growAdmitted() {
    if (bytes.length >= ProtocolFrameCodec.MAXIMUM_RESPONSE_BYTES) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    int capacity = Math.min(ProtocolFrameCodec.MAXIMUM_RESPONSE_BYTES, bytes.length << 1);
    StatusCode admitted = memory.resize(capacity + (batchBytes == null ? 0 : BATCH_BYTES));
    if (!admitted.isOk()) return admitted;
    return replace(capacity, bytes.length);
  }

  private StatusCode growReserved() {
    if (bytes.length >= ProtocolFrameCodec.MAXIMUM_RESPONSE_BYTES) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    int capacity = Math.min(ProtocolFrameCodec.MAXIMUM_RESPONSE_BYTES, bytes.length << 1);
    return replace(capacity, ProtocolFrameCodec.MAXIMUM_RESPONSE_BYTES);
  }

  private StatusCode replace(int capacity, int retainedOnFailure) {
    try {
      byte[] grown = new byte[capacity];
      ByteBuffer view = ByteBuffer.wrap(grown);
      bytes = grown;
      buffer = view;
      return StatusCode.OK;
    } catch (OutOfMemoryError failure) {
      memory.resize(retainedOnFailure + (batchBytes == null ? 0 : BATCH_BYTES));
      return StatusCode.RESOURCE_EXHAUSTED;
    }
  }
}
