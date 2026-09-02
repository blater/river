package io.riverdb.engine.api;

import io.riverdb.base.error.StatusDetail;
import io.riverdb.base.error.StatusCode;

/** Reusable bounded row result; unavailable with OK denotes end of stream. */
public final class RowResult {
  private final PublicResultValues values;
  private long key;
  private int columnCount;
  private boolean available;
  private QueryMetadata reservedMetadata;
  private long reservedGeneration;

  public RowResult() {
    this(RetainedMemoryLease.unbounded());
  }

  public RowResult(RetainedMemoryLease retainedMemory) {
    values = new PublicResultValues(retainedMemory);
  }

  public void reset() {
    key = 0;
    columnCount = 0;
    available = false;
    values.reset();
  }

  public StatusCode complete(
      long rowKey,
      long[] sourceValues,
      long sourceNullMask,
      int[] sourceTypeDescriptors,
      int columns) {
    if (sourceValues == null
        || sourceTypeDescriptors == null
        || columns <= 0
        || columns > Long.SIZE) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    StatusCode status = values.beginLegacy(
        sourceValues, sourceNullMask, sourceTypeDescriptors, columns);
    if (!status.isOk()) return status;
    key = rowKey;
    columnCount = columns;
    available = true;
    return StatusCode.OK;
  }

  public StatusCode complete(
      long rowKey,
      long[] sourceDecimalHighValues,
      long[] sourceValues,
      long[] sourceNullWords,
      int sourceNullWordCount,
      int[] sourceTypeDescriptors,
      int columns) {
    if (columns <= 0) return StatusCode.INVALID_EXTERNAL_INPUT;
    StatusCode status = values.begin(
        sourceDecimalHighValues, sourceValues, sourceNullWords,
        sourceNullWordCount, sourceTypeDescriptors, columns);
    if (!status.isOk()) return status;
    key = rowKey;
    columnCount = columns;
    available = true;
    return StatusCode.OK;
  }

  public StatusCode complete(
      long rowKey,
      long[] sourceValues,
      long[] sourceNullWords,
      int sourceNullWordCount,
      int[] sourceTypeDescriptors,
      int columns) {
    if (columns <= 0) return StatusCode.INVALID_EXTERNAL_INPUT;
    StatusCode status = values.begin(
        sourceValues, sourceNullWords, sourceNullWordCount, sourceTypeDescriptors, columns);
    if (!status.isOk()) return status;
    key = rowKey;
    columnCount = columns;
    available = true;
    return StatusCode.OK;
  }

  public StatusCode reserve(int columns, int textBytes) {
    return values.reserve(columns, textBytes);
  }

  public StatusCode reserve(QueryMetadata metadata, StatusDetail detail) {
    if (detail != null) detail.reset();
    if (metadata == null
        || metadata.columnCount() <= 0
        || metadata.reservationGeneration() <= 0) {
      return detail(detail, StatusCode.INVALID_EXTERNAL_INPUT, metadata);
    }
    if (reservedMetadata == metadata
        && reservedGeneration == metadata.reservationGeneration()) {
      return StatusCode.OK;
    }
    StatusCode status = values.reserve(
        metadata.columnCount(), metadata.maximumEncodedTextBytes());
    if (!status.isOk()) return detail(detail, status, metadata);
    reservedMetadata = metadata;
    reservedGeneration = metadata.reservationGeneration();
    return StatusCode.OK;
  }

  public boolean isReservedFor(QueryMetadata metadata) {
    return metadata != null
        && reservedMetadata == metadata
        && reservedGeneration == metadata.reservationGeneration();
  }

  public StatusCode setTextAt(
      int index,
      char[] source,
      int offset,
      int length) {
    if (!available
        || index < 0
        || index >= columnCount
        || source == null
        || offset < 0
        || length < 0
        || length > CommandResult.MAXIMUM_TEXT_CHARACTERS) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    return values.setText(index, source, offset, length);
  }


  /** Legacy scalar row-key field; descriptor composite/keyless rows report zero. */
  public long key() {
    return key;
  }

  public int columnCount() {
    return columnCount;
  }

  public long valueAt(int index) {
    return values.valueAt(index);
  }

  public short smallintAt(int index) {
    return PublicNumericValue.smallint(typeDescriptorAt(index), valueAt(index));
  }

  public int integerAt(int index) {
    return PublicNumericValue.integer(typeDescriptorAt(index), valueAt(index));
  }

  public long bigintAt(int index) {
    return PublicNumericValue.bigint(typeDescriptorAt(index), valueAt(index));
  }

  public long decimalUnscaledAt(int index) {
    return PublicNumericValue.decimal(typeDescriptorAt(index), valueAt(index));
  }

  public long decimalUnscaledHighAt(int index) {
    return PublicNumericValue.decimalHigh(
        typeDescriptorAt(index), values.decimalHighAt(index), valueAt(index));
  }

  public long decimalUnscaledLowAt(int index) {
    return PublicNumericValue.decimalLow(typeDescriptorAt(index), valueAt(index));
  }

  public float realAt(int index) {
    return PublicNumericValue.real(typeDescriptorAt(index), valueAt(index));
  }

  public double doubleAt(int index) {
    return PublicNumericValue.doubleValue(typeDescriptorAt(index), valueAt(index));
  }

  public boolean isNull(int index) {
    return values.isNull(index);
  }

  public long nullMask() {
    return values.nullWord(0);
  }

  public long nullWord(int word) {
    return values.nullWord(word);
  }

  public int nullWordCount() {
    return values.nullWordCount();
  }

  public boolean isVarchar(int index) {
    return values.isText(index);
  }

  public int typeDescriptorAt(int index) {
    return values.descriptorAt(index);
  }

  public int textLengthAt(int index) {
    return values.textLengthAt(index);
  }

  public int copyTextAt(int index, char[] destination, int offset) {
    int length = textLengthAt(index);
    if (length < 0
        || destination == null
        || offset < 0
        || offset > destination.length - length) {
      return -1;
    }
    return values.copyTextAt(index, destination, offset);
  }

  public char textCharacterAt(int index, int character) {
    return values.textCharacterAt(index, character);
  }

  public boolean isAvailable() {
    return available;
  }

  public StatusCode releaseHighWater() {
    reset();
    reservedMetadata = null;
    reservedGeneration = 0;
    return values.releaseHighWater();
  }

  public StatusCode release() {
    reset();
    reservedMetadata = null;
    reservedGeneration = 0;
    return values.release();
  }

  public long retainedBytes() { return values.retainedBytes(); }
  public static long maximumRetainedBytes() { return PublicResultValues.maximumRetainedBytes(); }
  public static long retainedFloorBytes() { return PublicResultValues.retainedFloorBytes(); }

  private static StatusCode detail(
      StatusDetail detail, StatusCode status, QueryMetadata metadata) {
    if (detail != null) {
      detail.set(status).append("result reservation columns=")
          .append(metadata == null ? 0 : metadata.columnCount())
          .append(" text-bytes=")
          .append(metadata == null ? 0 : metadata.maximumEncodedTextBytes());
    }
    return status;
  }
}
