package io.riverdb.engine.schema;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.error.StatusDetail;
import io.riverdb.base.sql.SqlShapeLimits;

/** Immutable physical table shape, row layout, and key descriptor references. */
public final class TableDescriptor {
  public static final int MAXIMUM_SECONDARY_KEYS = SqlShapeLimits.MAX_SECONDARY_INDEXES;
  public static final int MAXIMUM_FOREIGN_KEYS = SqlShapeLimits.MAX_FOREIGN_KEYS;
  private final long tableId;
  private final long schemaId;
  private final long rowLayoutId;
  private final long catalogGeneration;
  private final ColumnDescriptorSet columns;
  private final KeyDescriptor primaryKey;
  private final KeyDescriptor[] secondaryKeys;
  private final KeyDescriptor[] foreignKeys;
  private final TableLayout.Result layout;
  private final long byteCharge;

  TableDescriptor(
      long id,
      long durableSchemaId,
      long layoutId,
      long generation,
      ColumnDescriptorSet columnSet,
      KeyDescriptor primary,
      KeyDescriptor[] secondary,
      KeyDescriptor[] foreign,
      TableLayout.Result layoutResult,
      long charge) {
    tableId = id;
    schemaId = durableSchemaId;
    rowLayoutId = layoutId;
    catalogGeneration = generation;
    columns = columnSet;
    primaryKey = primary;
    secondaryKeys = secondary;
    foreignKeys = foreign;
    layout = layoutResult;
    byteCharge = charge;
  }

  /** Caller-owned publication result for one immutable table descriptor. */
  public static final class Result {
    private TableDescriptor value;

    public void reset() {
      value = null;
    }

    public TableDescriptor value() {
      return value;
    }

    void set(TableDescriptor published) {
      value = published;
    }
  }

  public static StatusCode create(
      long tableId,
      long rowLayoutId,
      long catalogGeneration,
      ColumnDescriptorSet columns,
      KeyDescriptor primaryKey,
      KeyDescriptor[] secondaryKeys,
      KeyDescriptor[] foreignKeys,
      Result result,
      StatusDetail detail) {
    return TableDescriptorFactory.create(
        tableId, 0, rowLayoutId, catalogGeneration, columns, primaryKey,
        secondaryKeys, foreignKeys, result, detail, true);
  }

  /** Freezes a descriptor under its exact durable catalog schema identity. */
  public static StatusCode createCatalogBound(
      long tableId,
      long schemaId,
      long rowLayoutId,
      long catalogGeneration,
      ColumnDescriptorSet columns,
      KeyDescriptor primaryKey,
      KeyDescriptor[] secondaryKeys,
      KeyDescriptor[] foreignKeys,
      Result result,
      StatusDetail detail) {
    return TableDescriptorFactory.create(
        tableId, schemaId, rowLayoutId, catalogGeneration, columns, primaryKey,
        secondaryKeys, foreignKeys, result, detail, true);
  }

  public static StatusCode createForTest(
      ColumnDescriptorSet columns,
      KeyDescriptor primaryKey,
      KeyDescriptor[] secondaryKeys,
      KeyDescriptor[] foreignKeys,
      Result result) {
    return createForTest(
        columns, primaryKey, secondaryKeys, foreignKeys, result, null);
  }

  /** Freezes a private successor proposal that may contain newly unbound key identities. */
  public static StatusCode createProposedSuccessor(
      long tableId,
      long rowLayoutId,
      long catalogGeneration,
      ColumnDescriptorSet columns,
      KeyDescriptor primaryKey,
      KeyDescriptor[] secondaryKeys,
      KeyDescriptor[] foreignKeys,
      Result result,
      StatusDetail detail) {
    return TableDescriptorFactory.create(
        tableId, 0, rowLayoutId, catalogGeneration, columns, primaryKey,
        secondaryKeys, foreignKeys, result, detail, false);
  }

  public static StatusCode createForTest(
      ColumnDescriptorSet columns,
      KeyDescriptor primaryKey,
      KeyDescriptor[] secondaryKeys,
      KeyDescriptor[] foreignKeys,
      Result result,
      StatusDetail detail) {
    return TableDescriptorFactory.create(
        1, 0, 1, 1, columns, primaryKey,
        secondaryKeys, foreignKeys, result, detail, false);
  }

  public long tableId() {
    return tableId;
  }

  /** Exact durable schema-generation identity, or zero for an unpublished proposal. */
  public long schemaId() {
    return schemaId;
  }

  public long rowLayoutId() {
    return rowLayoutId;
  }

  public long catalogGeneration() {
    return catalogGeneration;
  }

  public ColumnDescriptorSet columns() {
    return columns;
  }

  public int columnCount() {
    return columns.count();
  }

  public int typeDescriptorAt(int index) {
    return columns.typeDescriptorAt(index);
  }

  public boolean isNullable(int index) {
    return columns.isNullable(index);
  }

  public int findColumn(CharSequence name) {
    return columns.find(name);
  }

  public KeyDescriptor primaryKey() {
    return primaryKey;
  }

  public int secondaryKeyCount() {
    return secondaryKeys.length;
  }

  public KeyDescriptor secondaryKeyAt(int index) {
    return index >= 0 && index < secondaryKeys.length ? secondaryKeys[index] : null;
  }

  public int findSecondaryKey(CharSequence name) {
    for (int index = 0; index < secondaryKeys.length; index++) {
      if (secondaryKeys[index].matchesName(name)) return index;
    }
    return -1;
  }

  public int foreignKeyCount() {
    return foreignKeys.length;
  }

  public KeyDescriptor foreignKeyAt(int index) {
    return index >= 0 && index < foreignKeys.length ? foreignKeys[index] : null;
  }

  public int fixedOffsetAt(int index) {
    return index >= 0 && index < layout.offsets.length ? layout.offsets[index] : -1;
  }

  public int fixedWidthAt(int index) {
    return index >= 0 && index < layout.widths.length ? layout.widths[index] : 0;
  }

  public int nullBitmapBytes() {
    return layout.nullBytes;
  }

  public int encodedMaximumRowBytes() {
    return layout.maximumRowBytes;
  }

  public long byteCharge() {
    return byteCharge;
  }
}
