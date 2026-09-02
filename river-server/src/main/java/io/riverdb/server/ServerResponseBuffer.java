package io.riverdb.server;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.api.RetainedMemoryLease;
import io.riverdb.engine.api.TransactionProgramResult;
import io.riverdb.engine.api.TransactionProgramResultAdmission;
import io.riverdb.protocol.ProtocolFrameCodec;
import java.nio.ByteBuffer;

/** Geometrically retained response target with encode-only retry. */
final class ServerResponseBuffer implements TransactionProgramResultAdmission {
  private final RetainedMemoryLease memory;
  private byte[] bytes = new byte[ProtocolFrameCodec.MAXIMUM_FRAME_BYTES];
  private ByteBuffer buffer = ByteBuffer.wrap(bytes);

  ServerResponseBuffer(RetainedMemoryLease retainedMemory) {
    memory = retainedMemory;
    if (!memory.resize(bytes.length).isOk()) {
      throw new IllegalArgumentException("response memory lease");
    }
  }

  StatusCode process(SessionEndpoint endpoint, ByteBuffer request) {
    StatusCode status = endpoint.process(request, buffer);
    boolean maximumReserved = false;
    while (status == StatusCode.RESOURCE_EXHAUSTED) {
      if (!maximumReserved) {
        StatusCode reserved = memory.awaitResize(ProtocolFrameCodec.MAXIMUM_RESPONSE_BYTES);
        if (!reserved.isOk()) return reserved;
        maximumReserved = true;
      }
      StatusCode grown = growReserved();
      if (!grown.isOk()) return grown;
      status = endpoint.retryResponse(buffer);
    }
    return status;
  }

  byte[] bytes() { return bytes; }
  ByteBuffer buffer() { return buffer; }

  @Override
  public StatusCode admit(TransactionProgramResult result) {
    int required = ProtocolFrameCodec.programResultResponseBytes(StatusCode.OK, result);
    return required <= 0 ? StatusCode.RESOURCE_EXHAUSTED : ensureCapacity(required);
  }

  StatusCode releaseHighWater() {
    if (bytes.length == ProtocolFrameCodec.MAXIMUM_FRAME_BYTES) return StatusCode.OK;
    try {
      byte[] released = new byte[ProtocolFrameCodec.MAXIMUM_FRAME_BYTES];
      ByteBuffer view = ByteBuffer.wrap(released);
      int previous = bytes.length;
      bytes = released;
      buffer = view;
      memory.resize(released.length);
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
    StatusCode admitted = memory.resize(capacity);
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
      memory.resize(retainedOnFailure);
      return StatusCode.RESOURCE_EXHAUSTED;
    }
  }
}
