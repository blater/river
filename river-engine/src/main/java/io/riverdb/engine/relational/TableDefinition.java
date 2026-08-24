package io.riverdb.engine.relational;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.text.Utf8Text;
import java.nio.ByteBuffer;

/** Caller-owned resolved logical table identity. */
public final class TableDefinition {
  public static final int MAXIMUM_INDEXES = 4;
  static final int INDEX_NONE = 0;
  static final int INDEX_BUILDING = 1;
  static final int INDEX_READY = 2;
  static final int INDEX_DROPPING = 3;

  RelationalSchemaGate owner;
  int tableId;
  final int[] uniqueIndexTableIds = new int[MAXIMUM_INDEXES];
  final int[] uniqueIndexStates = new int[MAXIMUM_INDEXES];
  final int[] uniqueIndexColumns = new int[MAXIMUM_INDEXES];
  final boolean[] uniqueIndexes = new boolean[MAXIMUM_INDEXES];
  final boolean[] constraintIndexes = new boolean[MAXIMUM_INDEXES];
  final long[] defaultValues = new long[TableSchema.MAXIMUM_COLUMNS];
  final byte[] defaultKinds = new byte[TableSchema.MAXIMUM_COLUMNS];
  final byte[] defaultTextBytes = new byte[TableSchema.MAXIMUM_ROW_BYTES];
  final int[] typeDescriptors = new int[TableSchema.MAXIMUM_COLUMNS];
  final long[] checkValues = new long[TableSchema.MAXIMUM_COLUMNS];
  final int[] checkComparisons = new int[TableSchema.MAXIMUM_COLUMNS];
  final int[] checkTypeDescriptors = new int[TableSchema.MAXIMUM_COLUMNS];
  final byte[] checkNodeCounts = new byte[TableSchema.MAXIMUM_COLUMNS];
  final byte[] checkNodeOffsets = new byte[TableSchema.MAXIMUM_COLUMNS];
  final byte[] checkOperators = new byte[TableSchema.MAXIMUM_CHECK_NODES];
  final long[] checkOperands = new long[TableSchema.MAXIMUM_CHECK_NODES];
  final int[] checkNodeDescriptors =
      new int[TableSchema.MAXIMUM_CHECK_NODES];
  final int[] checkValidationStack =
      new int[TableSchema.MAXIMUM_CHECK_NODES];
  final int[] referenceTableIds = new int[TableSchema.MAXIMUM_COLUMNS];
  final TableDefinitionColumnName keyColumnName = new TableDefinitionColumnName();
  final TableDefinitionColumnName valueColumnName = new TableDefinitionColumnName();
  final TableDefinitionColumnName[] additionalColumns =
      new TableDefinitionColumnName[TableSchema.MAXIMUM_COLUMNS - 2];
  int uniqueIndexCount;
  int columnCount;
  long notNullMask;
  long defaultMask;
  long checkMask;
  long referenceMask;
  long schemaVersion;
  long schemaAdmission;
  boolean available;
  boolean identity;
  int defaultTextBytesUsed;
  int checkNodeCount;

  public TableDefinition() {
    for (int index = 0; index < additionalColumns.length; index++) {
      additionalColumns[index] = new TableDefinitionColumnName();
    }
  }

  public void reset() {
    TableDefinitionStateLoader.reset(this);
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
    TableDefinitionStateLoader.setMinimal(
        this,
        schemaGate,
        id,
        valueIndexTableId,
        valueIndexState,
        keyName,
        valueName);
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
    TableDefinitionStateLoader.setDefinition(
        this,
        schemaGate,
        id,
        valueIndexTableId,
        valueIndexState,
        indexColumn,
        schema,
        unique);
  }

  void set(
      RelationalSchemaGate schemaGate,
      int id,
      int valueIndexTableId,
      int valueIndexState,
      int indexColumn,
      TableSchema schema) {
    TableDefinitionStateLoader.setSchema(
        this,
        schemaGate,
        id,
        valueIndexTableId,
        valueIndexState,
        indexColumn,
        schema);
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
      int checkTypeDescriptorsOffset,
      int checkNodeCountsOffset,
      int checkProgramOffset,
      long requiredReferenceMask,
      int referenceTableIdsOffset,
      int defaultsOffset,
      int defaultKindsOffset,
      int defaultTextOffset,
      int defaultTextLength) {
    TableDefinitionStateLoader.setPersisted(
        this,
        schemaGate,
        id,
        valueIndexTableId,
        valueIndexState,
        indexColumn,
        source,
        columnsOffset,
        columns,
        requiredNotNullMask,
        requiredDefaultMask,
        typeDescriptorsOffset,
        requiredIdentity,
        requiredCheckMask,
        checksOffset,
        checkValuesOffset,
        checkTypeDescriptorsOffset,
        checkNodeCountsOffset,
        checkProgramOffset,
        requiredReferenceMask,
        referenceTableIdsOffset,
        defaultsOffset,
        defaultKindsOffset,
        defaultTextOffset,
        defaultTextLength);
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
    return TableDefinitionColumnView.keyName(this);
  }

  public CharSequence valueColumnName() {
    return TableDefinitionColumnView.valueName(this);
  }

  public boolean matchesKeyColumn(CharSequence name) {
    return TableDefinitionColumnView.matchesKey(this, name);
  }

  public boolean matchesValueColumn(CharSequence name) {
    return TableDefinitionColumnView.matchesValue(this, name);
  }

  public int columnCount() {
    return TableDefinitionColumnView.count(this);
  }

  public CharSequence columnName(int index) {
    return TableDefinitionColumnView.name(this, index);
  }

  TableDefinitionColumnName columnNameAt(int index) {
    return writableColumn(index);
  }

  public boolean isNullable(int column) {
    return TableDefinitionColumnView.nullable(this, column);
  }

  public boolean hasDefault(int column) {
    return TableDefinitionColumnView.hasDefault(this, column);
  }

  public boolean isVarchar(int column) {
    return TableDefinitionColumnView.varchar(this, column);
  }

  public int typeDescriptor(int column) {
    return TableDefinitionColumnView.typeDescriptor(this, column);
  }

  boolean supportsSecondaryIndex(int column) {
    return TableDefinitionColumnView.supportsSecondaryIndex(this, column);
  }

  public boolean hasIdentity() {
    return identity;
  }

  public long defaultValue(int column) {
    return TableDefinitionColumnView.defaultValue(this, column);
  }

  public int defaultKind(int column) {
    return TableDefinitionColumnView.defaultKind(this, column);
  }

  public int defaultTextLength(int column) {
    return TableDefinitionColumnView.defaultTextLength(this, column);
  }

  public int copyDefaultText(int column, ByteBuffer target) {
    return TableDefinitionColumnView.copyDefaultText(this, column, target);
  }

  int defaultTextBytes() {
    return TableDefinitionColumnView.defaultTextBytes(this);
  }

  byte defaultTextByte(int index) {
    return TableDefinitionColumnView.defaultTextByte(this, index);
  }

  public int findColumn(CharSequence name) {
    return TableDefinitionColumnView.findColumn(this, name);
  }

  public int rowBytes() {
    return TableDefinitionColumnView.maximumRowBytes(this);
  }

  public int fixedRowBytes() {
    return TableDefinitionColumnView.fixedRowBytes(this);
  }

  public int maximumRowBytes() {
    return TableDefinitionColumnView.maximumRowBytes(this);
  }

  public int nullMaskOffset() {
    return TableDefinitionColumnView.nullMaskOffset(this);
  }

  public boolean isNull(ByteBuffer row, int column) {
    return TableDefinitionRowCodec.isNull(this, row, column);
  }

  public boolean isValidRow(ByteBuffer row) {
    return TableDefinitionRowCodec.isValidRow(this, row);
  }

  public int textOffset(ByteBuffer row, int column) {
    return TableDefinitionRowCodec.textOffset(this, row, column);
  }

  public int textLength(ByteBuffer row, int column) {
    return TableDefinitionRowCodec.textLength(this, row, column);
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
    return TableDefinitionIndexView.uniqueValueIndexTableId(this);
  }

  int uniqueValueIndexState() {
    return TableDefinitionIndexView.uniqueValueIndexState(this);
  }

  int uniqueValueIndexColumn() {
    return TableDefinitionIndexView.uniqueValueIndexColumn(this);
  }

  int uniqueIndexCount() {
    return uniqueIndexCount;
  }

  int uniqueIndexTableId(int slot) {
    return TableDefinitionIndexView.uniqueIndexTableId(this, slot);
  }

  int uniqueIndexState(int slot) {
    return TableDefinitionIndexView.uniqueIndexState(this, slot);
  }

  int uniqueIndexColumn(int slot) {
    return TableDefinitionIndexView.uniqueIndexColumn(this, slot);
  }

  boolean indexIsUnique(int slot) {
    return TableDefinitionIndexView.indexIsUnique(this, slot);
  }

  boolean indexIsConstraint(int slot) {
    return TableDefinitionIndexView.indexIsConstraint(this, slot);
  }

  int readyIndexSlotOn(int column) {
    return TableDefinitionIndexView.readyIndexSlotOn(this, column);
  }

  int readyIndexSlotForTableId(int indexTableId) {
    return TableDefinitionIndexView.readyIndexSlotForTableId(this, indexTableId);
  }

  int readyIndexCount() {
    return TableDefinitionIndexView.readyIndexCount(this);
  }

  int buildingIndexSlot() {
    return TableDefinitionIndexView.buildingIndexSlot(this);
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
    return TableDefinitionIndexMutation.upsert(
        this, tableId, state, column, unique, constraint);
  }

  StatusCode removeIndex(int tableId) {
    return TableDefinitionIndexMutation.remove(this, tableId);
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
    return TableDefinitionSchemaView.isOwnedBy(this, schemaGate);
  }

  void bindSchema(
      RelationalSchemaGate schemaGate,
      long requiredSchemaVersion,
      long requiredSchemaAdmission) {
    TableDefinitionSchemaView.bind(
        this, schemaGate, requiredSchemaVersion, requiredSchemaAdmission);
  }

  boolean matchesSchema(
      RelationalSchemaGate schemaGate,
      long publishedSchemaVersion,
      long publishedSchemaAdmission,
      long activeSchemaAdmission) {
    return TableDefinitionSchemaView.matches(
        this,
        schemaGate,
        publishedSchemaVersion,
        publishedSchemaAdmission,
        activeSchemaAdmission);
  }

  private TableDefinitionColumnName writableColumn(int index) {
    return index == 0
        ? keyColumnName : index == 1 ? valueColumnName : additionalColumns[index - 2];
  }

  void setColumnName(int index, CharSequence name) {
    writableColumn(index).set(name);
  }

  void setColumnName(int index, ByteBuffer source, int offset, int bytes) {
    writableColumn(index).set(source, offset, bytes);
  }

  long checkMask() {
    return TableDefinitionCheckView.mask(this);
  }

  public boolean hasChecks() {
    return TableDefinitionCheckView.hasChecks(this);
  }

  public boolean hasCheck(int column) {
    return TableDefinitionCheckView.hasCheck(this, column);
  }

  public int checkComparison(int column) {
    return TableDefinitionCheckView.comparison(this, column);
  }

  public long checkValue(int column) {
    return TableDefinitionCheckView.value(this, column);
  }

  public int checkTypeDescriptor(int column) {
    return TableDefinitionCheckView.descriptor(this, column);
  }

  public int checkNodeCount(int column) {
    return TableDefinitionCheckView.nodeCount(this, column);
  }

  int checkNodeCount() {
    return TableDefinitionCheckView.totalNodes(this);
  }

  public int checkOperator(int column, int node) {
    return TableDefinitionCheckView.operator(this, column, node);
  }

  public long checkOperand(int column, int node) {
    return TableDefinitionCheckView.operand(this, column, node);
  }

  public int checkNodeDescriptor(int column, int node) {
    return TableDefinitionCheckView.nodeDescriptor(this, column, node);
  }

  int checkOperatorAt(int node) {
    return TableDefinitionCheckView.programOperator(this, node);
  }

  long checkOperandAt(int node) {
    return TableDefinitionCheckView.programOperand(this, node);
  }

  int checkNodeDescriptorAt(int node) {
    return TableDefinitionCheckView.programDescriptor(this, node);
  }

  int[] checkValidationStack() {
    return TableDefinitionCheckView.validationStack(this);
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

}
