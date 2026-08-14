package io.riverdb.engine.relational;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.text.Utf8Text;
import io.riverdb.base.type.SqlTypeDescriptor;
import java.nio.ByteBuffer;

/** Caller-owned resolved logical table identity. */
public final class TableDefinition {
  public static final int MAXIMUM_INDEXES = 4;
  static final int INDEX_NONE = 0;
  static final int INDEX_BUILDING = 1;
  static final int INDEX_READY = 2;
  static final int INDEX_DROPPING = 3;

  private RelationalSchemaGate owner;
  private int tableId;
  private final int[] uniqueIndexTableIds = new int[MAXIMUM_INDEXES];
  private final int[] uniqueIndexStates = new int[MAXIMUM_INDEXES];
  private final int[] uniqueIndexColumns = new int[MAXIMUM_INDEXES];
  private final boolean[] uniqueIndexes = new boolean[MAXIMUM_INDEXES];
  private final boolean[] constraintIndexes = new boolean[MAXIMUM_INDEXES];
  private final long[] defaultValues = new long[TableSchema.MAXIMUM_COLUMNS];
  private final byte[] defaultTextBytes = new byte[TableSchema.MAXIMUM_ROW_BYTES];
  private final int[] typeDescriptors = new int[TableSchema.MAXIMUM_COLUMNS];
  private final long[] checkValues = new long[TableSchema.MAXIMUM_COLUMNS];
  private final int[] checkComparisons = new int[TableSchema.MAXIMUM_COLUMNS];
  private final int[] referenceTableIds = new int[TableSchema.MAXIMUM_COLUMNS];
  private final ColumnName keyColumnName = new ColumnName();
  private final ColumnName valueColumnName = new ColumnName();
  private final ColumnName[] additionalColumns =
      new ColumnName[TableSchema.MAXIMUM_COLUMNS - 2];
  private int uniqueIndexCount;
  private int columnCount;
  private long notNullMask;
  private long defaultMask;
  private long checkMask;
  private long referenceMask;
  private long schemaVersion;
  private long schemaAdmission;
  private boolean available;
  private boolean identity;
  private int defaultTextBytesUsed;

  public TableDefinition() {
    for (int index = 0; index < additionalColumns.length; index++) {
      additionalColumns[index] = new ColumnName();
    }
  }

  public void reset() {
    owner = null;
    tableId = 0;
    for (int index = 0; index < uniqueIndexCount; index++) {
      uniqueIndexTableIds[index] = 0;
      uniqueIndexStates[index] = INDEX_NONE;
      uniqueIndexColumns[index] = 0;
      uniqueIndexes[index] = false;
      constraintIndexes[index] = false;
    }
    uniqueIndexCount = 0;
    keyColumnName.reset();
    valueColumnName.reset();
    for (int index = 0; index < columnCount - 2; index++) {
      additionalColumns[index].reset();
    }
    for (int index = 0; index < columnCount; index++) {
      typeDescriptors[index] = 0;
    }
    columnCount = 0;
    notNullMask = 0;
    defaultMask = 0;
    defaultTextBytesUsed = 0;
    checkMask = 0;
    referenceMask = 0;
    schemaVersion = 0;
    schemaAdmission = 0;
    available = false;
    identity = false;
    defaultTextBytesUsed = 0;
  }

  void set(
      RelationalSchemaGate schemaGate,
      int id,
      int valueIndexTableId,
      int valueIndexState) {
    set(schemaGate, id, valueIndexTableId, valueIndexState, "key", "value");
  }

  void set(
      RelationalSchemaGate schemaGate,
      int id,
      int valueIndexTableId,
      int valueIndexState,
      CharSequence keyName,
      CharSequence valueName) {
    owner = schemaGate;
    tableId = id;
    columnCount = 2;
    notNullMask = 1;
    defaultMask = 0;
    typeDescriptors[0] = SqlTypeDescriptor.BIGINT;
    typeDescriptors[1] = SqlTypeDescriptor.BIGINT;
    checkMask = 0;
    referenceMask = 0;
    uniqueIndexCount = 0;
    identity = false;
    if (valueIndexTableId > 0) {
      setIndex(0, valueIndexTableId, valueIndexState, 1, true);
      uniqueIndexCount = 1;
    }
    keyColumnName.set(keyName);
    valueColumnName.set(valueName);
    schemaVersion = schemaGate.version();
    schemaAdmission = 0;
    available = true;
  }

  void set(
      RelationalSchemaGate schemaGate,
      int id,
      int valueIndexTableId,
      int valueIndexState,
      int indexColumn,
      TableDefinition schema) {
    set(
        schemaGate,
        id,
        valueIndexTableId,
        valueIndexState,
        indexColumn,
        schema,
        true);
  }

  void set(
      RelationalSchemaGate schemaGate,
      int id,
      int valueIndexTableId,
      int valueIndexState,
      int indexColumn,
      TableDefinition schema,
      boolean unique) {
    owner = schemaGate;
    tableId = id;
    columnCount = schema.columnCount();
    notNullMask = schema.notNullMask;
    copyTypes(schema);
    copyDefaults(schema);
    copyChecks(schema);
    copyReferences(schema);
    identity = schema.identity;
    for (int index = 0; index < columnCount; index++) {
      writableColumn(index).set(schema.columnName(index));
    }
    copyIndexes(schema);
    if (valueIndexTableId > 0) {
      upsertIndex(valueIndexTableId, valueIndexState, indexColumn, unique);
    }
    schemaVersion = schemaGate.version();
    schemaAdmission = 0;
    available = true;
  }

  void set(
      RelationalSchemaGate schemaGate,
      int id,
      int valueIndexTableId,
      int valueIndexState,
      int indexColumn,
      TableSchema schema) {
    owner = schemaGate;
    tableId = id;
    columnCount = schema.columnCount();
    notNullMask = schema.notNullMask();
    defaultMask = schema.defaultMask();
    checkMask = schema.checkMask();
    referenceMask = schema.referenceMask();
    identity = schema.hasIdentity();
    for (int index = 0; index < columnCount; index++) {
      defaultValues[index] = schema.defaultValue(index);
      typeDescriptors[index] = schema.typeDescriptor(index);
      checkComparisons[index] = schema.checkComparison(index);
      checkValues[index] = schema.checkValue(index);
      referenceTableIds[index] = schema.referenceTableId(index);
    }
    defaultTextBytesUsed = schema.defaultTextBytes();
    for (int index = 0; index < defaultTextBytesUsed; index++) {
      defaultTextBytes[index] = schema.defaultTextByte(index);
    }
    uniqueIndexCount = 0;
    if (valueIndexTableId > 0) {
      setIndex(0, valueIndexTableId, valueIndexState, indexColumn, true);
      uniqueIndexCount = 1;
    }
    for (int index = 0; index < columnCount; index++) {
      writableColumn(index).set(schema.columnName(index));
    }
    schemaVersion = schemaGate.version();
    schemaAdmission = 0;
    available = true;
  }

  void set(
      RelationalSchemaGate schemaGate,
      int id,
      int valueIndexTableId,
      int valueIndexState,
      int indexColumn,
      ByteBuffer source,
      int columnsOffset,
      int columns,
      long requiredNotNullMask,
      long requiredDefaultMask,
      int typeDescriptorsOffset,
      boolean requiredIdentity,
      long requiredCheckMask,
      int checksOffset,
      int checkValuesOffset,
      long requiredReferenceMask,
      int referenceTableIdsOffset,
      int defaultsOffset,
      int defaultTextOffset,
      int defaultTextLength) {
    owner = schemaGate;
    tableId = id;
    columnCount = columns;
    notNullMask = requiredNotNullMask;
    defaultMask = requiredDefaultMask;
    identity = requiredIdentity;
    checkMask = requiredCheckMask;
    referenceMask = requiredReferenceMask;
    for (int index = 0; index < columns; index++) {
      defaultValues[index] = source.getLong(defaultsOffset + index * Long.BYTES);
      typeDescriptors[index] = source.getInt(
          typeDescriptorsOffset + index * Integer.BYTES);
      checkComparisons[index] = source.getInt(checksOffset + index * Integer.BYTES);
      checkValues[index] = source.getLong(checkValuesOffset + index * Long.BYTES);
      referenceTableIds[index] = source.getInt(
          referenceTableIdsOffset + index * Integer.BYTES);
    }
    defaultTextBytesUsed = defaultTextLength;
    for (int index = 0; index < defaultTextLength; index++) {
      defaultTextBytes[index] = source.get(defaultTextOffset + index);
    }
    uniqueIndexCount = 0;
    if (valueIndexTableId > 0) {
      setIndex(0, valueIndexTableId, valueIndexState, indexColumn, true);
      uniqueIndexCount = 1;
    }
    int offset = columnsOffset;
    for (int index = 0; index < columns; index++) {
      int length = source.getInt(offset);
      offset += Integer.BYTES;
      writableColumn(index).set(source, offset, length);
      offset += length;
    }
    schemaVersion = schemaGate.version();
    schemaAdmission = 0;
    available = true;
  }

  public int tableId() {
    return tableId;
  }

  public boolean isAvailable() {
    return available;
  }

  public boolean hasUniqueValueIndex() {
    return readyIndexCount() > 0;
  }

  public boolean hasBuildingUniqueValueIndex() {
    return buildingIndexSlot() >= 0;
  }

  public boolean hasUniqueIndexOn(int column) {
    int slot = readyIndexSlotOn(column);
    return slot >= 0 && uniqueIndexes[slot];
  }

  public boolean hasIndexOn(int column) {
    return readyIndexSlotOn(column) >= 0;
  }

  public CharSequence keyColumnName() {
    return keyColumnName;
  }

  public CharSequence valueColumnName() {
    return valueColumnName;
  }

  public boolean matchesKeyColumn(CharSequence name) {
    return keyColumnName.matches(name);
  }

  public boolean matchesValueColumn(CharSequence name) {
    return valueColumnName.matches(name);
  }

  public int columnCount() {
    return columnCount;
  }

  public CharSequence columnName(int index) {
    return index >= 0 && index < columnCount ? writableColumn(index) : null;
  }

  public boolean isNullable(int column) {
    return column >= 0
        && column < columnCount
        && (notNullMask & 1L << column) == 0;
  }

  public boolean hasDefault(int column) {
    return column > 0
        && column < columnCount
        && (defaultMask & 1L << column) != 0;
  }

  public boolean isVarchar(int column) {
    return column > 0
        && column < columnCount
        && SqlTypeDescriptor.typeId(typeDescriptors[column])
            == SqlTypeDescriptor.TYPE_ID_VARCHAR;
  }

  public int typeDescriptor(int column) {
    return column >= 0 && column < columnCount ? typeDescriptors[column] : 0;
  }

  public boolean hasIdentity() {
    return identity;
  }

  public boolean checksSatisfied(long primaryKey, ByteBuffer row) {
    for (int column = 0; column < columnCount; column++) {
      if ((checkMask & 1L << column) == 0
          || column > 0 && isNull(row, column)) {
        continue;
      }
      long actual = column == 0
          ? primaryKey : row.getLong(row.position() + (column - 1) * Long.BYTES);
      long required = checkValues[column];
      boolean satisfied = switch (checkComparisons[column]) {
        case TableSchema.CHECK_EQUAL -> actual == required;
        case TableSchema.CHECK_NOT_EQUAL -> actual != required;
        case TableSchema.CHECK_LESS_THAN -> actual < required;
        case TableSchema.CHECK_LESS_OR_EQUAL -> actual <= required;
        case TableSchema.CHECK_GREATER_THAN -> actual > required;
        case TableSchema.CHECK_GREATER_OR_EQUAL -> actual >= required;
        default -> false;
      };
      if (!satisfied) {
        return false;
      }
    }
    return true;
  }

  public long defaultValue(int column) {
    return hasDefault(column) ? defaultValues[column] : 0;
  }

  public int defaultTextLength(int column) {
    if (!hasDefault(column) || !isVarchar(column)) {
      return -1;
    }
    long handle = defaultValues[column];
    int offset = (int) (handle >>> 32);
    int length = (int) handle;
    return offset >= 0 && length >= 0 && offset <= defaultTextBytesUsed - length
        ? length : -1;
  }

  public int copyDefaultText(int column, ByteBuffer target) {
    int length = defaultTextLength(column);
    if (length < 0 || target == null || target.remaining() < length) {
      return -1;
    }
    int offset = (int) (defaultValues[column] >>> 32);
    target.put(defaultTextBytes, offset, length);
    return length;
  }

  int defaultTextBytes() {
    return defaultTextBytesUsed;
  }

  byte defaultTextByte(int index) {
    return index >= 0 && index < defaultTextBytesUsed ? defaultTextBytes[index] : 0;
  }

  public int findColumn(CharSequence name) {
    for (int index = 0; index < columnCount; index++) {
      if (writableColumn(index).matches(name)) {
        return index;
      }
    }
    return -1;
  }

  public int rowBytes() {
    return maximumRowBytes();
  }

  public int fixedRowBytes() {
    return columnCount * Long.BYTES;
  }

  public int maximumRowBytes() {
    int bytes = fixedRowBytes();
    for (int column = 1; column < columnCount; column++) {
      if (isVarchar(column)) {
        bytes += SqlTypeDescriptor.parameterOne(typeDescriptors[column]) * 4;
      }
    }
    return bytes;
  }

  public int nullMaskOffset() {
    return (columnCount - 1) * Long.BYTES;
  }

  public boolean isNull(ByteBuffer row, int column) {
    return row != null
        && column > 0
        && column < columnCount
        && row.remaining() >= fixedRowBytes()
        && row.remaining() <= maximumRowBytes()
        && (row.getLong(row.position() + nullMaskOffset()) & 1L << column) != 0;
  }

  public boolean isValidRow(ByteBuffer row) {
    if (row == null
        || row.remaining() < fixedRowBytes()
        || row.remaining() > maximumRowBytes()) {
      return false;
    }
    int base = row.position();
    long nullMask = row.getLong(base + nullMaskOffset());
    if (!isValidNullMask(nullMask)) {
      return false;
    }
    int payloadOffset = fixedRowBytes();
    for (int column = 1; column < columnCount; column++) {
      long slot = row.getLong(base + (column - 1) * Long.BYTES);
      if (!isVarchar(column)) {
        if ((nullMask & 1L << column) != 0
            ? slot != 0 : !TableSchema.validFixedValue(typeDescriptors[column], slot)) {
          return false;
        }
        continue;
      }
      if ((nullMask & 1L << column) != 0) {
        if (slot != 0) {
          return false;
        }
        continue;
      }
      int offset = (int) (slot >>> 32);
      int length = (int) slot;
      if (offset != payloadOffset
          || length < 0
          || offset > row.remaining() - length
          || Utf8Text.validate(
              row,
              base + offset,
              length,
              SqlTypeDescriptor.parameterOne(typeDescriptors[column])) < 0) {
        return false;
      }
      payloadOffset += length;
    }
    return payloadOffset == row.remaining();
  }

  public int textOffset(ByteBuffer row, int column) {
    if (!isVarchar(column) || isNull(row, column)) {
      return -1;
    }
    return (int) (row.getLong(
        row.position() + (column - 1) * Long.BYTES) >>> 32);
  }

  public int textLength(ByteBuffer row, int column) {
    if (!isVarchar(column) || isNull(row, column)) {
      return -1;
    }
    return (int) row.getLong(row.position() + (column - 1) * Long.BYTES);
  }

  public boolean isValidNullMask(long nullMask) {
    long allowed = ((1L << columnCount) - 1) & ~1L;
    return available
        && (nullMask & ~allowed) == 0
        && (nullMask & notNullMask) == 0;
  }

  long notNullMask() {
    return notNullMask;
  }

  long defaultMask() {
    return defaultMask;
  }

  int uniqueValueIndexTableId() {
    int slot = buildingIndexSlot();
    if (slot < 0) {
      slot = firstReadyIndexSlot();
    }
    return slot < 0 ? 0 : uniqueIndexTableIds[slot];
  }

  int uniqueValueIndexState() {
    int slot = buildingIndexSlot();
    if (slot < 0) {
      slot = firstReadyIndexSlot();
    }
    return slot < 0 ? INDEX_NONE : uniqueIndexStates[slot];
  }

  int uniqueValueIndexColumn() {
    int slot = buildingIndexSlot();
    if (slot < 0) {
      slot = firstReadyIndexSlot();
    }
    return slot < 0 ? -1 : uniqueIndexColumns[slot];
  }

  int uniqueIndexCount() {
    return uniqueIndexCount;
  }

  int uniqueIndexTableId(int slot) {
    return slot >= 0 && slot < uniqueIndexCount ? uniqueIndexTableIds[slot] : 0;
  }

  int uniqueIndexState(int slot) {
    return slot >= 0 && slot < uniqueIndexCount ? uniqueIndexStates[slot] : INDEX_NONE;
  }

  int uniqueIndexColumn(int slot) {
    return slot >= 0 && slot < uniqueIndexCount ? uniqueIndexColumns[slot] : -1;
  }

  boolean indexIsUnique(int slot) {
    return slot >= 0 && slot < uniqueIndexCount && uniqueIndexes[slot];
  }

  boolean indexIsConstraint(int slot) {
    return slot >= 0 && slot < uniqueIndexCount && constraintIndexes[slot];
  }

  int readyIndexSlotOn(int column) {
    for (int index = 0; index < uniqueIndexCount; index++) {
      if (uniqueIndexStates[index] == INDEX_READY && uniqueIndexColumns[index] == column) {
        return index;
      }
    }
    return -1;
  }

  int readyIndexSlotForTableId(int indexTableId) {
    for (int index = 0; index < uniqueIndexCount; index++) {
      if (uniqueIndexStates[index] == INDEX_READY
          && uniqueIndexTableIds[index] == indexTableId) {
        return index;
      }
    }
    return -1;
  }

  int readyIndexCount() {
    int count = 0;
    for (int index = 0; index < uniqueIndexCount; index++) {
      if (uniqueIndexStates[index] == INDEX_READY) {
        count++;
      }
    }
    return count;
  }

  int buildingIndexSlot() {
    for (int index = 0; index < uniqueIndexCount; index++) {
      if (uniqueIndexStates[index] == INDEX_BUILDING) {
        return index;
      }
    }
    return -1;
  }

  StatusCode upsertIndex(int tableId, int state, int column) {
    return upsertIndex(tableId, state, column, true);
  }

  StatusCode upsertIndex(int tableId, int state, int column, boolean unique) {
    return upsertIndex(tableId, state, column, unique, false);
  }

  StatusCode upsertIndex(
      int tableId,
      int state,
      int column,
      boolean unique,
      boolean constraint) {
    if (tableId <= 0
        || (state != INDEX_BUILDING && state != INDEX_READY && state != INDEX_DROPPING)
        || column <= 0
        || column >= columnCount) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    for (int index = 0; index < uniqueIndexCount; index++) {
      if (uniqueIndexTableIds[index] == tableId || uniqueIndexColumns[index] == column) {
        setIndex(index, tableId, state, column, unique, constraint);
        return StatusCode.OK;
      }
    }
    if (uniqueIndexCount >= MAXIMUM_INDEXES) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    setIndex(uniqueIndexCount++, tableId, state, column, unique, constraint);
    return StatusCode.OK;
  }

  StatusCode removeIndex(int tableId) {
    for (int index = 0; index < uniqueIndexCount; index++) {
      if (uniqueIndexTableIds[index] != tableId) {
        continue;
      }
      for (int move = index; move < uniqueIndexCount - 1; move++) {
        setIndex(
            move,
            uniqueIndexTableIds[move + 1],
            uniqueIndexStates[move + 1],
            uniqueIndexColumns[move + 1],
            uniqueIndexes[move + 1],
            constraintIndexes[move + 1]);
      }
      uniqueIndexCount--;
      setIndex(uniqueIndexCount, 0, INDEX_NONE, 0, false, false);
      return StatusCode.OK;
    }
    return StatusCode.CONFLICT;
  }

  StatusCode renameColumn(
      CharSequence currentName,
      CharSequence renamedName) {
    if (!RelationalKey.validName(currentName)
        || !RelationalKey.validName(renamedName)) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    int column = findColumn(currentName);
    if (column < 0 || findColumn(renamedName) >= 0) {
      return StatusCode.CONFLICT;
    }
    writableColumn(column).set(renamedName);
    return StatusCode.OK;
  }

  boolean isOwnedBy(RelationalSchemaGate schemaGate) {
    return schemaGate != null && schemaGate.owns(this);
  }

  void bindSchema(
      RelationalSchemaGate schemaGate,
      long requiredSchemaVersion,
      long requiredSchemaAdmission) {
    owner = schemaGate;
    schemaVersion = requiredSchemaVersion;
    schemaAdmission = requiredSchemaAdmission;
  }

  boolean matchesSchema(
      RelationalSchemaGate schemaGate,
      long publishedSchemaVersion,
      long publishedSchemaAdmission,
      long activeSchemaAdmission) {
    return available
        && owner == schemaGate
        && (schemaAdmission == 0 && schemaVersion == publishedSchemaVersion
            || schemaAdmission != 0
                && schemaVersion == publishedSchemaVersion
                && schemaAdmission == publishedSchemaAdmission
            || schemaAdmission != 0
                && activeSchemaAdmission != 0
                && schemaAdmission == activeSchemaAdmission
                && schemaVersion == publishedSchemaVersion + 1);
  }

  private ColumnName writableColumn(int index) {
    return index == 0
        ? keyColumnName : index == 1 ? valueColumnName : additionalColumns[index - 2];
  }

  private int firstReadyIndexSlot() {
    for (int index = 0; index < uniqueIndexCount; index++) {
      if (uniqueIndexStates[index] == INDEX_READY) {
        return index;
      }
    }
    return -1;
  }

  private void copyIndexes(TableDefinition source) {
    uniqueIndexCount = source.uniqueIndexCount;
    for (int index = 0; index < uniqueIndexCount; index++) {
      setIndex(
          index,
          source.uniqueIndexTableIds[index],
          source.uniqueIndexStates[index],
          source.uniqueIndexColumns[index],
          source.uniqueIndexes[index],
          source.constraintIndexes[index]);
    }
  }

  private void copyDefaults(TableDefinition source) {
    defaultMask = source.defaultMask;
    for (int index = 0; index < columnCount; index++) {
      defaultValues[index] = source.defaultValues[index];
    }
    defaultTextBytesUsed = source.defaultTextBytesUsed;
    System.arraycopy(
        source.defaultTextBytes,
        0,
        defaultTextBytes,
        0,
        defaultTextBytesUsed);
  }

  private void copyTypes(TableDefinition source) {
    for (int index = 0; index < columnCount; index++) {
      typeDescriptors[index] = source.typeDescriptors[index];
    }
  }

  private void copyChecks(TableDefinition source) {
    checkMask = source.checkMask;
    for (int index = 0; index < columnCount; index++) {
      checkComparisons[index] = source.checkComparisons[index];
      checkValues[index] = source.checkValues[index];
    }
  }

  long checkMask() {
    return checkMask;
  }

  int checkComparison(int column) {
    return column >= 0 && column < columnCount ? checkComparisons[column] : 0;
  }

  long checkValue(int column) {
    return column >= 0 && column < columnCount ? checkValues[column] : 0;
  }

  boolean hasReferences() {
    return referenceMask != 0;
  }

  long referenceMask() {
    return referenceMask;
  }

  boolean hasReference(int column) {
    return column > 0
        && column < columnCount
        && (referenceMask & 1L << column) != 0;
  }

  int referenceTableId(int column) {
    return hasReference(column) ? referenceTableIds[column] : 0;
  }

  boolean referencesTable(int referencedTableId) {
    for (int column = 1; column < columnCount; column++) {
      if (hasReference(column) && referenceTableIds[column] == referencedTableId) {
        return true;
      }
    }
    return false;
  }

  private void copyReferences(TableDefinition source) {
    referenceMask = source.referenceMask;
    for (int column = 0; column < columnCount; column++) {
      referenceTableIds[column] = source.referenceTableIds[column];
    }
  }

  private void setIndex(int slot, int tableId, int state, int column, boolean unique) {
    setIndex(slot, tableId, state, column, unique, false);
  }

  private void setIndex(
      int slot,
      int tableId,
      int state,
      int column,
      boolean unique,
      boolean constraint) {
    uniqueIndexTableIds[slot] = tableId;
    uniqueIndexStates[slot] = state;
    uniqueIndexColumns[slot] = column;
    uniqueIndexes[slot] = unique;
    constraintIndexes[slot] = constraint;
  }

  private static final class ColumnName implements CharSequence {
    private final char[] characters = new char[TableSchema.MAXIMUM_NAME_LENGTH];
    private int length;

    void reset() {
      length = 0;
    }

    void set(CharSequence name) {
      length = name.length();
      for (int index = 0; index < length; index++) {
        characters[index] = name.charAt(index);
      }
    }

    void set(ByteBuffer source, int offset, int bytes) {
      length = bytes;
      for (int index = 0; index < length; index++) {
        characters[index] = (char) Byte.toUnsignedInt(source.get(offset + index));
      }
    }

    boolean matches(CharSequence name) {
      if (name == null || name.length() != length) {
        return false;
      }
      for (int index = 0; index < length; index++) {
        if (name.charAt(index) != characters[index]) {
          return false;
        }
      }
      return true;
    }

    @Override
    public int length() {
      return length;
    }

    @Override
    public char charAt(int index) {
      if (index < 0 || index >= length) {
        throw new IndexOutOfBoundsException(index);
      }
      return characters[index];
    }

    @Override
    public CharSequence subSequence(int start, int end) {
      if (start < 0 || end < start || end > length) {
        throw new IndexOutOfBoundsException(start);
      }
      return new String(characters, start, end - start);
    }
  }
}
