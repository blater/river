package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.relational.TableSchema;
import io.riverdb.engine.runtime.materialized.SqlMaterializedPagedByteStream;
import java.nio.ByteBuffer;

/** Validates and decodes canonical variable-length spill records. */
final class SqlSortSpillRecordReader {
  private final SqlSortSpillRecordIO records;
  private final SqlSortSpillDecodedRows decoded;
  private final SqlSortGeneratedTextSpill generatedText;
  private boolean containsText;
  private int projections;

  SqlSortSpillRecordReader(
      SqlSortSpillRecordIO recordIO,
      SqlSortSpillDecodedRows decodedRows,
      SqlSortGeneratedTextSpill generated) {
    records = recordIO;
    decoded = decodedRows;
    generatedText = generated;
  }

  void configure(int projectedColumns, boolean textRows) {
    projections = projectedColumns;
    containsText = textRows;
  }

  StatusCode decodeOutput(
      SqlMaterializedPagedByteStream stream, long offset,
      SqlSortSpill.OffsetResult next) {
    return decode(stream, offset, 0, next, true);
  }

  StatusCode decodeHead(
      SqlMaterializedPagedByteStream stream, long offset, int slot,
      SqlSortSpill.OffsetResult next) {
    return decode(stream, offset, slot, next, false);
  }

  StatusCode skip(
      SqlMaterializedPagedByteStream stream, long offset, long count,
      SqlSortSpill.OffsetResult result) {
    return records.skip(stream, offset, count, fixedBytes(), result);
  }

  StatusCode copy(
      SqlMaterializedPagedByteStream input, long inputOffset,
      SqlMaterializedPagedByteStream output, long outputOffset,
      SqlSortSpill.OffsetResult result) {
    int minimum = fixedBytes() + (containsText ? Integer.BYTES : 0);
    int maximum = minimum + (containsText ? TableSchema.MAXIMUM_ROW_BYTES : 0);
    return records.copy(input, inputOffset, output, outputOffset, minimum, maximum, result);
  }

  int fixedBytes() {
    return SqlSortSpillRecordLayout.fixedBytes(
        projections, decoded.nulls.nullWordCount(), generatedText.recordBytes());
  }

  private StatusCode decode(
      SqlMaterializedPagedByteStream stream, long offset, int slot,
      SqlSortSpill.OffsetResult next, boolean outputRecord) {
    ByteBuffer record = records.buffer();
    StatusCode status = readRecord(stream, offset, Integer.BYTES);
    if (!status.isOk()) return status;
    int dataBytes = record.getInt(0);
    int minimum = fixedBytes() + (containsText ? Integer.BYTES : 0);
    int maximum = minimum + (containsText ? TableSchema.MAXIMUM_ROW_BYTES : 0);
    if (dataBytes < minimum || dataBytes > maximum
        || (!containsText && dataBytes != fixedBytes())) return StatusCode.CORRUPTION;
    int recordBytes = Integer.BYTES + dataBytes + Integer.BYTES;
    status = readRecord(stream, offset, recordBytes);
    if (!status.isOk()) return status;
    if (record.getInt(Integer.BYTES + dataBytes)
        != records.checksum(Integer.BYTES, dataBytes)) return StatusCode.CORRUPTION;
    record.position(Integer.BYTES);
    decoded.keyHighs[slot] = record.getLong();
    decoded.keys[slot] = record.getLong();
    decoded.primaryKeys[slot] = record.getLong();
    decoded.ordinals[slot] = record.getLong();
    long keyNull = record.getLong();
    if (keyNull < 0 || keyNull > 1) return StatusCode.CORRUPTION;
    decoded.keyNulls[slot] = keyNull != 0;
    status = decoded.nulls.read(record, slot, projections);
    int valueStart = slot * projections;
    for (int index = 0; status.isOk() && index < projections; index++) {
      decoded.highs[valueStart + index] = record.getLong();
      decoded.values[valueStart + index] = record.getLong();
    }
    if (status.isOk() && outputRecord) status = generatedText.readOutput(record);
    else if (status.isOk()) generatedText.skip(record);
    if (status.isOk() && containsText) status = readRowBytes(record, slot, dataBytes);
    if (status.isOk()) next.value = offset + recordBytes;
    return status;
  }

  private StatusCode readRowBytes(ByteBuffer record, int slot, int dataBytes) {
    int rowLength = record.getInt();
    if (rowLength < 0 || rowLength > TableSchema.MAXIMUM_ROW_BYTES
        || dataBytes != fixedBytes() + Integer.BYTES + rowLength) {
      return StatusCode.CORRUPTION;
    }
    int targetOffset = slot * TableSchema.MAXIMUM_ROW_BYTES;
    for (int index = 0; index < rowLength; index++) {
      decoded.rows.put(targetOffset + index, record.get());
    }
    decoded.rowLengths[slot] = rowLength;
    return StatusCode.OK;
  }

  private StatusCode readRecord(
      SqlMaterializedPagedByteStream stream, long offset, int length) {
    return records.read(stream, offset, length);
  }
}
