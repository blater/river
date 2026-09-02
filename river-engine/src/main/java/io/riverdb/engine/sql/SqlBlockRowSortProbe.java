package io.riverdb.engine.sql;

import io.riverdb.base.collection.BoundedArrayGrowth;
import io.riverdb.base.error.StatusCode;
import io.riverdb.base.error.StatusDetail;
import io.riverdb.engine.runtime.materialized.SqlMaterializedPagedByteStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Bounded caller-reused key reader and comparator for external block ordering. */
final class SqlBlockRowSortProbe {
  private final SqlBlockRowPagedIndexRecord leftIndex = new SqlBlockRowPagedIndexRecord();
  private final SqlBlockRowPagedIndexRecord rightIndex = new SqlBlockRowPagedIndexRecord();
  private final SqlSessionShapeBudget budget;
  private final StatusDetail detail = new StatusDetail(128);
  private ByteBuffer leftKey;
  private ByteBuffer rightKey;
  private StatusCode status = StatusCode.OK;

  SqlBlockRowSortProbe(SqlSessionShapeBudget shapeBudget) { budget = shapeBudget; }

  int compare(
      long left,
      long right,
      SqlMaterializedPagedByteStream index,
      SqlMaterializedPagedByteStream keys,
      SqlBlockRowSortKeyCodec shape) {
    if (!status.isOk()) return 0;
    status = readIndex(index, keys.logicalLength(), left, leftIndex);
    if (status.isOk()) status = readIndex(index, keys.logicalLength(), right, rightIndex);
    if (status.isOk()) status = prepareKeys(leftIndex.keyLength(), rightIndex.keyLength());
    if (status.isOk()) status = readKey(keys, leftIndex, leftKey);
    if (status.isOk()) status = readKey(keys, rightIndex, rightKey);
    if (status.isOk()) status = SqlBlockRowSortKeyValidation.validate(leftKey, shape);
    if (status.isOk()) status = SqlBlockRowSortKeyValidation.validate(rightKey, shape);
    if (!status.isOk()) return 0;
    int compared = SqlBlockRowSortKeyCompare.compare(leftKey, rightKey, shape);
    return compared != 0 ? compared : Long.compare(left, right);
  }

  StatusCode status() { return status; }

  void reset() { status = StatusCode.OK; }

  void close() {
    release(leftKey);
    release(rightKey);
    leftKey = null;
    rightKey = null;
    status = StatusCode.OK;
  }

  private StatusCode readIndex(
      SqlMaterializedPagedByteStream stream,
      long keysLength,
      long ordinal,
      SqlBlockRowPagedIndexRecord target) {
    if (ordinal < 0 || ordinal > Long.MAX_VALUE / SqlBlockRowPagedIndexRecord.BYTES) {
      return StatusCode.CORRUPTION;
    }
    target.prepareRead();
    StatusCode read = stream.read(ordinal * SqlBlockRowPagedIndexRecord.BYTES,
        target.bytes(), detail);
    if (read.isOk()) read = target.validate(ordinal);
    return read.isOk() ? target.validateKeyBounds(keysLength) : read;
  }

  private StatusCode readKey(
      SqlMaterializedPagedByteStream stream,
      SqlBlockRowPagedIndexRecord record,
      ByteBuffer target) {
    target.clear();
    target.limit(record.keyLength());
    StatusCode read = stream.read(record.keyOffset(), target, detail);
    if (read.isOk()) target.flip();
    return read;
  }

  private StatusCode prepareKeys(int leftBytes, int rightBytes) {
    leftKey = reserve(leftKey, leftBytes);
    if (leftKey == null) return StatusCode.RESOURCE_EXHAUSTED;
    rightKey = reserve(rightKey, rightBytes);
    return rightKey == null ? StatusCode.RESOURCE_EXHAUSTED : StatusCode.OK;
  }

  private ByteBuffer reserve(ByteBuffer current, int required) {
    if (current != null && current.capacity() >= required) return current;
    int old = current == null ? 0 : current.capacity();
    int capacity = BoundedArrayGrowth.capacity(
        old, required, SqlBlockRowRecordCodec.MAXIMUM_RECORD_BYTES, 256);
    if (capacity < 0 || (budget != null && !budget.reserve(capacity).isOk())) return null;
    try {
      ByteBuffer grown = ByteBuffer.allocateDirect(capacity).order(ByteOrder.BIG_ENDIAN);
      if (budget != null && old > 0) budget.rollback(old);
      return grown;
    } catch (OutOfMemoryError failure) {
      if (budget != null) budget.rollback(capacity);
      return null;
    }
  }

  private void release(ByteBuffer buffer) {
    if (budget != null && buffer != null) budget.rollback(buffer.capacity());
  }

}
