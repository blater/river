package io.riverdb.engine.relational;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.text.BoundedByteSource;
import io.riverdb.base.text.Utf8TextArena;
import io.riverdb.base.type.SqlTypeDescriptor;
import io.riverdb.base.type.SqlValueDomain;
import io.riverdb.engine.schema.TableDescriptor;
import java.nio.ByteBuffer;

/**
 * Statement-owned changed values over a borrowed original row. An unchanged column reads the
 * original directly; changed text belongs to this instance until its next {@link #begin}.
 */
public final class SqlMutationValues implements SqlValueAccess {
  private final Utf8TextArena text = new Utf8TextArena();
  private int[] generations = new int[0];
  private int[] offsets = new int[0];
  private int[] lengths = new int[0];
  private long[] highs = new long[0];
  private long[] lows = new long[0];
  private boolean[] nulls = new boolean[0];
  private TableDescriptor table;
  private SqlValueAccess original;
  private int generation;

  public int capacity() { return generations.length; }

  public int textCapacity() { return text.capacity(); }

  boolean boundTo(TableDescriptor descriptor) {
    return table != null && descriptor != null
        && table.tableId() == descriptor.tableId()
        && table.rowLayoutId() == descriptor.rowLayoutId()
        && table.catalogGeneration() == descriptor.catalogGeneration()
        && table.columnCount() == descriptor.columnCount();
  }

  public StatusCode reserve(TableDescriptor descriptor, int textBytes) {
    if (descriptor == null || textBytes < 0) return StatusCode.INVALID_EXTERNAL_INPUT;
    int columns = descriptor.columnCount();
    if (columns > generations.length) {
      int[] nextGenerations;
      int[] nextOffsets;
      int[] nextLengths;
      long[] nextHighs;
      long[] nextLows;
      boolean[] nextNulls;
      try {
        nextGenerations = new int[columns];
        nextOffsets = new int[columns];
        nextLengths = new int[columns];
        nextHighs = new long[columns];
        nextLows = new long[columns];
        nextNulls = new boolean[columns];
      } catch (OutOfMemoryError exhausted) {
        return StatusCode.RESOURCE_EXHAUSTED;
      }
      StatusCode status = text.reserve(textBytes, textBytes);
      if (!status.isOk()) return status;
      generations = nextGenerations;
      offsets = nextOffsets;
      lengths = nextLengths;
      highs = nextHighs;
      lows = nextLows;
      nulls = nextNulls;
      generation = 0;
      return StatusCode.OK;
    }
    return text.reserve(textBytes, textBytes);
  }

  public StatusCode begin(TableDescriptor descriptor, SqlValueAccess source) {
    if (descriptor == null || descriptor.columnCount() > generations.length
        || source != null && !admittedSource(descriptor, source)) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    if (generation == Integer.MAX_VALUE) {
      java.util.Arrays.fill(generations, 0);
      generation = 0;
    }
    generation++;
    text.reset();
    table = descriptor;
    original = source;
    return StatusCode.OK;
  }

  public void reset() {
    table = null;
    original = null;
    text.reset();
  }

  public StatusCode setFixed(int column, int descriptor, long value) {
    if (!unassigned(column, descriptor) || !SqlValueDomain.validFixed(descriptor, value)) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    assign(column, false, value >> 63, value, 0, 0);
    return StatusCode.OK;
  }

  public StatusCode setDecimal128(int column, int descriptor, long high, long low) {
    if (!unassigned(column, descriptor)
        || !SqlValueDomain.validDecimal128(descriptor, high, low)) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    assign(column, false, high, low, 0, 0);
    return StatusCode.OK;
  }

  public StatusCode setTextBytes(
      int column, int descriptor, ByteBuffer source, int offset, int length) {
    if (!unassigned(column, descriptor)
        || SqlTypeDescriptor.typeId(descriptor) != SqlTypeDescriptor.TYPE_ID_VARCHAR) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    StatusCode status = text.append(
        source, offset, length, SqlTypeDescriptor.parameterOne(descriptor));
    if (!status.isOk()) return status;
    assign(column, false, 0, 0, text.lastOffset(), text.lastLength());
    return StatusCode.OK;
  }

  public StatusCode setText(
      int column, int descriptor, char[] chars, int offset, int length) {
    if (!unassigned(column, descriptor)
        || SqlTypeDescriptor.typeId(descriptor) != SqlTypeDescriptor.TYPE_ID_VARCHAR) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    StatusCode status = text.append(
        chars, offset, length, SqlTypeDescriptor.parameterOne(descriptor));
    if (!status.isOk()) return status;
    assign(column, false, 0, 0, text.lastOffset(), text.lastLength());
    return StatusCode.OK;
  }

  public StatusCode setText(int column, int descriptor, CharSequence chars) {
    if (!unassigned(column, descriptor)
        || SqlTypeDescriptor.typeId(descriptor) != SqlTypeDescriptor.TYPE_ID_VARCHAR) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    StatusCode status = text.append(chars, SqlTypeDescriptor.parameterOne(descriptor));
    if (!status.isOk()) return status;
    assign(column, false, 0, 0, text.lastOffset(), text.lastLength());
    return StatusCode.OK;
  }

  public StatusCode setNull(int column, int descriptor) {
    if (!unassigned(column, descriptor)) return StatusCode.INVALID_EXTERNAL_INPUT;
    assign(column, true, 0, 0, 0, 0);
    return StatusCode.OK;
  }

  @Override
  public int count() { return table == null ? 0 : table.columnCount(); }

  @Override
  public int descriptorAt(int column) {
    return valid(column) && (changed(column) || original != null)
        ? table.typeDescriptorAt(column) : 0;
  }

  @Override
  public boolean isNull(int column) {
    return changed(column) ? nulls[column]
        : original != null && original.isNull(column);
  }

  @Override
  public long valueAt(int column) {
    return changed(column) ? lows[column]
        : original == null ? 0 : original.valueAt(column);
  }

  @Override
  public long highValueAt(int column) {
    return changed(column) ? highs[column]
        : original == null ? 0 : original.highValueAt(column);
  }

  @Override
  public int textByteLengthAt(int column) {
    return changed(column) ? textColumn(column) && !nulls[column] ? lengths[column] : -1
        : original == null ? -1 : original.textByteLengthAt(column);
  }

  @Override
  public int textByteOffsetAt(int column) {
    return changed(column) ? textColumn(column) && !nulls[column] ? offsets[column] : -1
        : original == null ? -1 : original.textByteOffsetAt(column);
  }

  @Override
  public BoundedByteSource textSource(int column) {
    return changed(column) ? text : original == null ? null : original.textSource(column);
  }

  @Override
  public int copyTextChars(int column, char[] destination, int destinationOffset) {
    if (changed(column)) {
      return textColumn(column) && !nulls[column]
          ? text.copyChars(offsets[column], lengths[column], destination, destinationOffset)
          : -1;
    }
    return original == null ? -1
        : original.copyTextChars(column, destination, destinationOffset);
  }

  private boolean valid(int column) {
    return table != null && column >= 0 && column < table.columnCount();
  }

  private boolean admittedSource(TableDescriptor descriptor, SqlValueAccess source) {
    if (source instanceof StoredTableRowView stored) {
      return stored.boundTo(descriptor) && stored.count() == descriptor.columnCount();
    }
    if (source instanceof SqlMutationValues changed) {
      return changed != this && changed.table == descriptor
          && changed.original == null;
    }
    return false;
  }

  private boolean changed(int column) {
    return valid(column) && generations[column] == generation;
  }

  private boolean textColumn(int column) {
    return SqlTypeDescriptor.typeId(table.typeDescriptorAt(column))
        == SqlTypeDescriptor.TYPE_ID_VARCHAR;
  }

  private boolean unassigned(int column, int descriptor) {
    return valid(column) && !changed(column) && table.typeDescriptorAt(column) == descriptor;
  }

  private void assign(
      int column, boolean nullValue, long high, long low, int offset, int length) {
    generations[column] = generation;
    nulls[column] = nullValue;
    highs[column] = high;
    lows[column] = low;
    offsets[column] = offset;
    lengths[column] = length;
  }
}
