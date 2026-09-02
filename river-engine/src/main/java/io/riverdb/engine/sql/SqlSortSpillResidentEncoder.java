package io.riverdb.engine.sql;

import io.riverdb.engine.relational.TableSchema;
import java.nio.ByteBuffer;

/** Direct encoder from one resident sorted row into the paged spill record. */
final class SqlSortSpillResidentEncoder {
  private final SqlSortSpillRecordIO records;
  private final SqlSortGeneratedTextSpill generatedText;
  private int projections;
  private boolean textRows;
  private long nextOrdinal;

  SqlSortSpillResidentEncoder(
      SqlSortSpillRecordIO recordIO, SqlSortGeneratedTextSpill generated) {
    records = recordIO;
    generatedText = generated;
  }

  void begin(int projectionCount, boolean containsText) {
    projections = projectionCount;
    textRows = containsText;
    nextOrdinal = 0;
  }

  void encode(
      long[] keyHighs, long[] keys, boolean[] keyNulls, long[] primaryKeys,
      SqlSortNullWords nulls, long[] highs, long[] values,
      int[] rowSlots, int[] rowLengths, ByteBuffer rows,
      byte[] textLengths, char[] text, int row, int fixedBytes) {
    int rowLength = textRows ? rowLengths[rowSlots[row]] : 0;
    int dataBytes = fixedBytes + (textRows ? Integer.BYTES + rowLength : 0);
    records.prepare(dataBytes);
    ByteBuffer record = records.buffer();
    record.putLong(keyHighs[row]);
    record.putLong(keys[row]);
    record.putLong(primaryKeys[row]);
    record.putLong(nextOrdinal++);
    record.putLong(keyNulls[row] ? 1 : 0);
    nulls.write(record, row);
    int valueStart = row * projections;
    for (int index = 0; index < projections; index++) {
      record.putLong(highs[valueStart + index]);
      record.putLong(values[valueStart + index]);
    }
    generatedText.write(record, row, textLengths, text);
    if (textRows) {
      record.putInt(rowLength);
      int sourceOffset = rowSlots[row] * TableSchema.MAXIMUM_ROW_BYTES;
      for (int index = 0; index < rowLength; index++) record.put(rows.get(sourceOffset + index));
    }
    records.finish(dataBytes);
  }
}
