package io.riverdb.base.type;

import io.riverdb.base.column.ColumnBitSet;
import io.riverdb.base.error.StatusCode;
import io.riverdb.base.text.Utf8TextArena;
import java.nio.ByteBuffer;

/**
 * Caller-owned, reusable primitive SQL value lanes with packed UTF-8 text.
 * Each lane is write-once between {@link #clearForSize} or {@link #reset} calls.
 */
public final class SqlValueBuffer {
  private final ColumnBitSet nulls = new ColumnBitSet();
  private final Utf8TextArena text = new Utf8TextArena();
  private final SqlValueLaneStorage lanes = new SqlValueLaneStorage();
  private int count;

  /**
   * Reserves lane and text storage without changing logical values. Growth is bounded by the
   * caller's semantic limits and may occur only at an owning admission boundary. Failure preserves
   * all logical contents; capacity obtained by an earlier component remains retained.
   */
  public StatusCode reserve(
      int requestedLanes,
      int maximumLanes,
      int requestedTextBytes,
      int maximumTextBytes) {
    if (requestedLanes < 0 || maximumLanes < 0
        || requestedTextBytes < 0 || maximumTextBytes < 0) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    if (requestedLanes > maximumLanes || count > maximumLanes
        || requestedTextBytes > maximumTextBytes || text.used() > maximumTextBytes) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    StatusCode status = reserveLanes(requestedLanes, maximumLanes);
    if (!status.isOk()) {
      return status;
    }
    status = nulls.reserve(requestedLanes, maximumLanes);
    return status.isOk() ? text.reserve(requestedTextBytes, maximumTextBytes) : status;
  }

  /** Clears the previous logical row and publishes the requested lane count. */
  public StatusCode clearForSize(int lanes) {
    if (lanes < 0) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    if (lanes > this.lanes.capacity()) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    StatusCode status = nulls.clearForSize(lanes);
    if (!status.isOk()) {
      return status;
    }
    this.lanes.clear(count);
    text.reset();
    count = lanes;
    return StatusCode.OK;
  }

  /** Clears all logical lanes while retaining high-water storage. */
  public void reset() {
    clearForSize(0);
  }

  public StatusCode setFixed(int index, int descriptor, long value) {
    if (!unassigned(index) || !SqlValueDomain.validFixed(descriptor, value)) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    publish(index, descriptor, value >> (Long.SIZE - 1), value, 0, 0);
    return StatusCode.OK;
  }

  /** Stores one allocation-free signed 128-bit DECIMAL unscaled value. */
  public StatusCode setDecimal128(
      int index, int descriptor, long high, long low) {
    if (!unassigned(index)
        || !SqlValueDomain.validDecimal128(descriptor, high, low)) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    publish(index, descriptor, high, low, 0, 0);
    return StatusCode.OK;
  }

  public StatusCode setText(int index, int descriptor, CharSequence value) {
    if (!unassigned(index)
        || SqlTypeDescriptor.typeId(descriptor) != SqlTypeDescriptor.TYPE_ID_VARCHAR
        || !SqlTypeDescriptor.isValid(descriptor)) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    StatusCode status = text.append(value, SqlTypeDescriptor.parameterOne(descriptor));
    if (!status.isOk()) {
      return status;
    }
    publish(index, descriptor, 0, 0, text.lastOffset(), text.lastLength());
    return StatusCode.OK;
  }

  public StatusCode setText(
      int index, int descriptor, char[] value, int offset, int length) {
    if (!unassigned(index)
        || SqlTypeDescriptor.typeId(descriptor) != SqlTypeDescriptor.TYPE_ID_VARCHAR
        || !SqlTypeDescriptor.isValid(descriptor)) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    StatusCode status = text.append(
        value, offset, length, SqlTypeDescriptor.parameterOne(descriptor));
    if (!status.isOk()) {
      return status;
    }
    publish(index, descriptor, 0, 0, text.lastOffset(), text.lastLength());
    return StatusCode.OK;
  }

  /** Stores one canonical UTF-8 slice without changing the source buffer state. */
  public StatusCode setTextBytes(
      int index, int descriptor, ByteBuffer source, int offset, int length) {
    if (!unassigned(index)
        || SqlTypeDescriptor.typeId(descriptor) != SqlTypeDescriptor.TYPE_ID_VARCHAR
        || !SqlTypeDescriptor.isValid(descriptor)) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    StatusCode status = text.append(
        source, offset, length, SqlTypeDescriptor.parameterOne(descriptor));
    if (!status.isOk()) {
      return status;
    }
    publish(index, descriptor, 0, 0, text.lastOffset(), text.lastLength());
    return StatusCode.OK;
  }

  public StatusCode setNull(int index, int descriptor) {
    if (!unassigned(index) || !SqlTypeDescriptor.isValid(descriptor)) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    lanes.publish(index, descriptor, 0, 0, 0, 0);
    nulls.set(index);
    return StatusCode.OK;
  }

  public int count() {
    return count;
  }

  public int capacity() {
    return lanes.capacity();
  }

  public long valueAt(int index) {
    return validIndex(index) ? lanes.lowAt(index) : 0;
  }

  /** High signed lane; meaningful for DECIMAL values and sign-extended for compact fixed values. */
  public long highValueAt(int index) {
    return validIndex(index) ? lanes.highAt(index) : 0;
  }

  public int descriptorAt(int index) {
    return validIndex(index) ? lanes.descriptorAt(index) : 0;
  }

  public boolean isNull(int index) {
    return validIndex(index) && nulls.get(index);
  }

  /** Returns one logical null-bitmap word without exposing mutable storage. */
  public long nullWord(int word) {
    return nulls.word(word);
  }

  public int textByteLengthAt(int index) {
    return isText(index) && !nulls.get(index) ? lanes.textLengthAt(index) : -1;
  }

  public int textBytesUsed() {
    return text.used();
  }

  public int textCapacity() {
    return text.capacity();
  }

  public int textMaximumBytes() {
    return text.maximumBytes();
  }

  /** Returns one unsigned byte from a non-null text lane, or {@code -1}. */
  public int textByteAt(int index, int byteIndex) {
    int length = textByteLengthAt(index);
    return byteIndex < 0 || byteIndex >= length
        ? -1 : text.byteAt(lanes.textOffsetAt(index) + byteIndex);
  }

  public StatusCode copyTextBytes(int index, byte[] destination, int destinationOffset) {
    int length = textByteLengthAt(index);
    return length < 0
        ? StatusCode.INVALID_EXTERNAL_INPUT
        : text.copyBytes(lanes.textOffsetAt(index), length, destination, destinationOffset);
  }

  /** Returns the copied UTF-16 code-unit count, or {@code -1} for invalid input or capacity. */
  public int copyTextChars(int index, char[] destination, int destinationOffset) {
    int length = textByteLengthAt(index);
    return length < 0
        ? -1
        : text.copyChars(lanes.textOffsetAt(index), length, destination, destinationOffset);
  }

  private StatusCode reserveLanes(int requested, int maximum) {
    return lanes.reserve(requested, maximum, count);
  }

  private void publish(
      int index,
      int descriptor,
      long highValue,
      long lowValue,
      int textOffset,
      int textLength) {
    lanes.publish(index, descriptor, highValue, lowValue, textOffset, textLength);
    nulls.clear(index);
  }

  private boolean validIndex(int index) {
    return index >= 0 && index < count;
  }

  private boolean unassigned(int index) {
    return validIndex(index) && lanes.descriptorAt(index) == 0;
  }

  private boolean isText(int index) {
    return validIndex(index)
        && SqlTypeDescriptor.typeId(lanes.descriptorAt(index))
            == SqlTypeDescriptor.TYPE_ID_VARCHAR;
  }
}
