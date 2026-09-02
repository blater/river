package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.error.StatusDetail;
import io.riverdb.engine.runtime.materialized.SqlMaterializedPagedByteStream;
import java.nio.ByteBuffer;
import java.util.zip.CRC32C;

/** Reusable variable-record buffer, checksum, and paged-stream transfer state. */
final class SqlSortSpillRecordIO {
  private final SqlMaterializedPagedByteStream.AppendResult append =
      new SqlMaterializedPagedByteStream.AppendResult();
  private final StatusDetail detail = new StatusDetail(160);
  private final CRC32C checksum = new CRC32C();
  private ByteBuffer record;

  StatusCode reserve(int bytes, SqlRetainedArrayAllocator allocator) {
    if (record != null && record.capacity() >= bytes) return StatusCode.OK;
    try {
      record = allocator.direct(bytes);
      return StatusCode.OK;
    } catch (OutOfMemoryError failure) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
  }

  ByteBuffer buffer() { return record; }

  void prepare(int dataBytes) {
    record.clear();
    record.limit(Integer.BYTES + dataBytes + Integer.BYTES);
    record.putInt(dataBytes);
  }

  void finish(int dataBytes) {
    record.putInt(checksum(Integer.BYTES, dataBytes));
    record.flip();
  }

  StatusCode append(SqlMaterializedPagedByteStream stream) {
    return stream.append(record, append, detail);
  }

  StatusCode read(SqlMaterializedPagedByteStream stream, long offset, int length) {
    record.clear();
    record.limit(length);
    StatusCode status = stream.read(offset, record, detail);
    if (status.isOk()) record.flip();
    return status;
  }

  StatusCode skip(
      SqlMaterializedPagedByteStream stream, long offset, long count, int minimum,
      SqlSortSpill.OffsetResult result) {
    long current = offset;
    for (long row = 0; row < count; row++) {
      StatusCode status = read(stream, current, Integer.BYTES);
      if (!status.isOk()) return status;
      int dataBytes = record.getInt(0);
      if (dataBytes < minimum) return StatusCode.CORRUPTION;
      long recordBytes = (long) Integer.BYTES + dataBytes + Integer.BYTES;
      if (current > Long.MAX_VALUE - recordBytes) return StatusCode.CORRUPTION;
      current += recordBytes;
    }
    result.value = current;
    return StatusCode.OK;
  }

  StatusCode copy(
      SqlMaterializedPagedByteStream input, long inputOffset,
      SqlMaterializedPagedByteStream output, long outputOffset,
      int minimum, int maximum, SqlSortSpill.OffsetResult result) {
    StatusCode status = read(input, inputOffset, Integer.BYTES);
    if (!status.isOk()) return status;
    int dataBytes = record.getInt(0);
    if (dataBytes < minimum || dataBytes > maximum) return StatusCode.CORRUPTION;
    int bytes = Integer.BYTES + dataBytes + Integer.BYTES;
    status = read(input, inputOffset, bytes);
    if (!status.isOk()) return status;
    if (output.logicalLength() == outputOffset) {
      status = output.append(record, append, detail);
      if (status.isOk() && append.offset() != outputOffset) status = StatusCode.CORRUPTION;
    } else if (outputOffset >= 0 && outputOffset <= output.logicalLength() - bytes) {
      status = output.overwrite(outputOffset, record, detail);
    } else {
      status = StatusCode.CORRUPTION;
    }
    if (status.isOk()) {
      result.value = inputOffset + bytes;
      result.output = outputOffset + bytes;
    }
    return status;
  }

  int checksum(int offset, int length) {
    checksum.reset();
    for (int index = 0; index < length; index++) checksum.update(record.get(offset + index));
    return (int) checksum.getValue();
  }
}
