package io.riverdb.engine.schema;

import io.riverdb.base.sql.SqlShapeLimits;
import io.riverdb.base.tuple.TupleShape;

/** Immutable ordered key parts and their storage or constraint semantics. */
public final class KeyDescriptor {
  public static final int MAXIMUM_NAME_LENGTH = 64;
  public static final int KIND_PRIMARY = 1;
  public static final int KIND_UNIQUE = 2;
  public static final int KIND_FOREIGN = 3;
  public static final int KIND_SECONDARY = 4;
  public static final int PRIMARY = KIND_PRIMARY;
  public static final int UNIQUE = KIND_UNIQUE;
  public static final int FOREIGN = KIND_FOREIGN;
  public static final int SECONDARY = KIND_SECONDARY;
  public static final int MAXIMUM_PARTS = SqlShapeLimits.MAX_KEY_PARTS;
  private final long keyId;
  private final ColumnDescriptorSet columns;
  private final int kind;
  private final boolean unique;
  private final TupleShape shape;
  private final int[] columnOrdinals;
  private final long referencedKeyId;
  private final String name;
  private final long byteCharge;

  KeyDescriptor(
      long id,
      ColumnDescriptorSet columnSet,
      int keyKind,
      boolean isUnique,
      TupleShape tupleShape,
      int[] ordinals,
      long referencedId,
      String keyName) {
    keyId = id;
    columns = columnSet;
    kind = keyKind;
    unique = isUnique;
    shape = tupleShape;
    columnOrdinals = ordinals;
    referencedKeyId = referencedId;
    name = keyName;
    byteCharge = SchemaByteCharge.object(32, 4)
        + SchemaByteCharge.array(Integer.BYTES, ordinals.length)
        + (name == null ? 0 : SchemaByteCharge.string(name.length()))
        + tupleShape.retainedByteCharge();
  }

  /** Caller-owned publication result for one immutable key descriptor. */
  public static final class Result {
    private KeyDescriptor value;

    public void reset() {
      value = null;
    }

    public KeyDescriptor value() {
      return value;
    }

    void set(KeyDescriptor published) {
      value = published;
    }
  }

  public static io.riverdb.base.error.StatusCode create(
      long keyId,
      int kind,
      boolean unique,
      ColumnDescriptorSet columns,
      int[] ordinals,
      long referencedKeyId,
      Result result,
      io.riverdb.base.error.StatusDetail detail) {
    return KeyDescriptorFactory.create(
        keyId, kind, unique, columns, ordinals, referencedKeyId,
        null, result, detail, true);
  }

  public static io.riverdb.base.error.StatusCode createNamed(
      long keyId,
      int kind,
      boolean unique,
      ColumnDescriptorSet columns,
      int[] ordinals,
      long referencedKeyId,
      CharSequence name,
      Result result,
      io.riverdb.base.error.StatusDetail detail) {
    return KeyDescriptorFactory.create(
        keyId, kind, unique, columns, ordinals, referencedKeyId,
        name, result, detail, true);
  }

  public static io.riverdb.base.error.StatusCode createForTest(
      int kind,
      boolean unique,
      ColumnDescriptorSet columns,
      int[] ordinals,
      long referencedKeyId,
      Result result,
      io.riverdb.base.error.StatusDetail detail) {
    return KeyDescriptorFactory.create(
        0, kind, unique, columns, ordinals, referencedKeyId,
        null, result, detail, false);
  }

  public static io.riverdb.base.error.StatusCode createNamedForTest(
      int kind,
      boolean unique,
      ColumnDescriptorSet columns,
      int[] ordinals,
      long referencedKeyId,
      CharSequence name,
      Result result,
      io.riverdb.base.error.StatusDetail detail) {
    return createNamedUnbound(
        kind, unique, columns, ordinals, referencedKeyId, name, result, detail);
  }

  /** Creates a privately proposed key whose durable identity is bound at catalog reservation. */
  public static io.riverdb.base.error.StatusCode createNamedUnbound(
      int kind,
      boolean unique,
      ColumnDescriptorSet columns,
      int[] ordinals,
      long referencedKeyId,
      CharSequence name,
      Result result,
      io.riverdb.base.error.StatusDetail detail) {
    return KeyDescriptorFactory.create(
        0, kind, unique, columns, ordinals, referencedKeyId,
        name, result, detail, false);
  }

  public static io.riverdb.base.error.StatusCode createForTest(
      int kind,
      boolean unique,
      ColumnDescriptorSet columns,
      int[] ordinals,
      Result result,
      io.riverdb.base.error.StatusDetail detail) {
    return createForTest(kind, unique, columns, ordinals, 0, result, detail);
  }

  public static io.riverdb.base.error.StatusCode createForTest(
      int kind,
      boolean unique,
      ColumnDescriptorSet columns,
      int[] ordinals,
      Result result) {
    return createForTest(kind, unique, columns, ordinals, 0, result, null);
  }

  public long keyId() {
    return keyId;
  }

  ColumnDescriptorSet columns() {
    return columns;
  }

  public int kind() {
    return kind;
  }

  public boolean isUnique() {
    return unique;
  }

  public int partCount() {
    return columnOrdinals.length;
  }

  public int columnOrdinalAt(int index) {
    return index >= 0 && index < columnOrdinals.length ? columnOrdinals[index] : -1;
  }

  public int typeDescriptorAt(int index) {
    return shape.descriptorAt(index);
  }

  public int comparisonFamilyAt(int index) {
    return shape.comparisonFamilyAt(index);
  }

  public TupleShape shape() {
    return shape;
  }

  public long referencedKeyId() {
    return referencedKeyId;
  }

  public boolean hasName() {
    return name != null;
  }

  public String name() {
    return name;
  }

  public boolean matchesName(CharSequence candidate) {
    if (name == null || candidate == null || name.length() != candidate.length()) return false;
    for (int index = 0; index < name.length(); index++) {
      if (name.charAt(index) != candidate.charAt(index)) return false;
    }
    return true;
  }

  public int maximumEncodedBytes() {
    return shape.maximumEncodedBytes();
  }

  public long byteCharge() {
    return byteCharge;
  }

}
