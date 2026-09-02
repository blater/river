package io.riverdb.engine.relational;

import io.riverdb.base.column.ColumnBitSet;
import io.riverdb.base.error.StatusCode;
import io.riverdb.base.sql.SqlShapeLimits;
import java.nio.ByteBuffer;

/** Caller-owned resolved logical table identity. */
public final class TableDefinition {
  static final int INDEX_NONE = 0;
  static final int INDEX_BUILDING = 1;
  static final int INDEX_READY = 2;
  static final int INDEX_DROPPING = 3;

  RelationalSchemaGate owner;
  int tableId;
  int[] uniqueIndexTableIds = new int[0];
  int[] uniqueIndexStates = new int[0];
  int[] uniqueIndexColumns = new int[0];
  boolean[] uniqueIndexes = new boolean[0];
  boolean[] constraintIndexes = new boolean[0];
  long[] defaultValues = new long[0];
  byte[] defaultKinds = new byte[0];
  byte[] defaultTextBytes = new byte[0];
  int[] typeDescriptors = new int[0];
  int[] valueOffsets = new int[0];
  long[] checkValues = new long[0];
  int[] checkComparisons = new int[0];
  int[] checkTypeDescriptors = new int[0];
  int[] checkNodeCounts = new int[0];
  int[] checkNodeOffsets = new int[0];
  byte[] checkOperators = new byte[0];
  long[] checkOperands = new long[0];
  int[] checkNodeDescriptors = new int[0];
  int[] checkValidationStack = new int[0];
  int[] referenceTableIds = new int[0];
  TableDefinitionColumnName[] columnNames = new TableDefinitionColumnName[0];
  final ColumnBitSet notNullColumns = new ColumnBitSet();
  final ColumnBitSet defaultColumns = new ColumnBitSet();
  final ColumnBitSet checkColumns = new ColumnBitSet();
  final ColumnBitSet referenceColumns = new ColumnBitSet();
  int uniqueIndexCount;
  int columnCount;
  long schemaVersion;
  long schemaAdmission;
  long durableSchemaId;
  long durableRowLayoutId;
  long durableCatalogGeneration;
  boolean available;
  boolean identity;
  boolean descriptorView;
  int primaryIndexColumn = -1;
  int defaultTextBytesUsed;
  int checkNodeCount;
  volatile int layoutColumns;

  public TableDefinition() { }

  public void reset() {
    TableDefinitionStateLoader.reset(this);
  }

  StatusCode set(
      RelationalSchemaGate schemaGate,
      int id,
      int valueIndexTableId,
      int valueIndexState) {
    return set(schemaGate, id, valueIndexTableId, valueIndexState, "key", "value");
  }

  StatusCode set(
      RelationalSchemaGate schemaGate,
      int id,
      int valueIndexTableId,
      int valueIndexState,
      CharSequence keyName,
      CharSequence valueName) {
    return TableDefinitionStateLoader.setMinimal(
        this,
        schemaGate,
        id,
        valueIndexTableId,
        valueIndexState,
        keyName,
        valueName);
  }

  StatusCode set(
      RelationalSchemaGate schemaGate,
      int id,
      int valueIndexTableId,
      int valueIndexState,
      int indexColumn,
      TableDefinition schema) {
    return set(
        schemaGate,
        id,
        valueIndexTableId,
        valueIndexState,
        indexColumn,
        schema,
        true);
  }

  StatusCode set(
      RelationalSchemaGate schemaGate,
      int id,
      int valueIndexTableId,
      int valueIndexState,
      int indexColumn,
      TableDefinition schema,
      boolean unique) {
    return TableDefinitionStateLoader.setDefinition(
        this,
        schemaGate,
        id,
        valueIndexTableId,
        valueIndexState,
        indexColumn,
        schema,
        unique);
  }

  StatusCode set(
      RelationalSchemaGate schemaGate,
      int id,
      int valueIndexTableId,
      int valueIndexState,
      int indexColumn,
      TableSchema schema) {
    return TableDefinitionStateLoader.setSchema(
        this,
        schemaGate,
        id,
        valueIndexTableId,
        valueIndexState,
        indexColumn,
        schema);
  }

  public int tableId() {
    return tableId;
  }

  /** Global catalog generation under which this resolved definition is valid. */
  public long catalogGeneration() { return schemaVersion; }

  /** Admission identity for the schema publication that produced this definition. */
  public long catalogAdmission() { return schemaAdmission; }

  public long durableSchemaId() { return durableSchemaId; }

  public long durableRowLayoutId() { return durableRowLayoutId; }

  public long durableCatalogGeneration() { return durableCatalogGeneration; }

  /** Exact primitive identity comparison for retained catalog dependencies. */
  public boolean matchesCatalogIdentity(
      int expectedTableId,
      long expectedCatalogGeneration,
      long expectedCatalogAdmission,
      long expectedSchemaId,
      long expectedRowLayoutId,
      long expectedDurableGeneration) {
    return available
        && tableId == expectedTableId
        && schemaVersion == expectedCatalogGeneration
        && schemaAdmission == expectedCatalogAdmission
        && durableSchemaId == expectedSchemaId
        && durableRowLayoutId == expectedRowLayoutId
        && durableCatalogGeneration == expectedDurableGeneration;
  }

  long statisticsSchemaId() {
    return durableSchemaId > 0 ? durableSchemaId : tableId;
  }

  long statisticsRowLayoutId() {
    return durableRowLayoutId > 0 ? durableRowLayoutId : tableId;
  }

  long statisticsCatalogGeneration() {
    return durableCatalogGeneration > 0 ? durableCatalogGeneration : 1;
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

  public boolean hasPrimaryIndexOn(int column) {
    return column >= 0 && primaryIndexColumn == column;
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
    return maximumRowBytes();
  }

  public int fixedRowBytes() {
    return TableDefinitionRowLayout.fixedBytes(this);
  }

  public int maximumRowBytes() {
    return TableDefinitionRowLayout.maximumBytes(this);
  }

  public int nullBitmapOffset() {
    return TableDefinitionRowLayout.nullBitmapOffset(this);
  }

  public int nullBitmapBytes() {
    return TableDefinitionRowLayout.nullBitmapBytes(this);
  }

  /** Offset of the low/value lane for one non-key column in the legacy row image. */
  public int valueOffset(int column) {
    return TableDefinitionRowLayout.valueOffset(this, column);
  }

  /** Offset of the high lane for a wide DECIMAL, or the regular value lane otherwise. */
  public int highValueOffset(int column) {
    return TableDefinitionRowLayout.highValueOffset(this, column);
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

  long notNullWord(int word) {
    return notNullColumns.word(word);
  }

  long defaultWord(int word) {
    return defaultColumns.word(word);
  }

  int bitmapWordCount() {
    return (columnCount + Long.SIZE - 1) / Long.SIZE;
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
    return columnNames[index];
  }

  void setColumnName(int index, CharSequence name) {
    writableColumn(index).set(name);
  }

  void setColumnName(int index, ByteBuffer source, int offset, int bytes) {
    writableColumn(index).set(source, offset, bytes);
  }

  long checkWord(int word) {
    return TableDefinitionCheckView.word(this, word);
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
    return !referenceColumns.isEmpty();
  }

  long referenceWord(int word) {
    return referenceColumns.word(word);
  }

  boolean hasReference(int column) {
    return column > 0
        && column < columnCount
        && referenceColumns.get(column);
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
