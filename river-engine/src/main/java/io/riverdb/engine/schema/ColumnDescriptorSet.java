package io.riverdb.engine.schema;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.error.StatusDetail;

/** Immutable exact-size column names, nullability, and SQL type descriptors. */
public final class ColumnDescriptorSet {
  public static final int MAXIMUM_NAME_SCALARS = 255;
  private final int[] typeDescriptors;
  private final byte[] nullable;
  private final ColumnNameTable names;
  private final ColumnConstraintDescriptorSet constraints;
  private final long byteCharge;

  ColumnDescriptorSet(
      int[] descriptors, byte[] nullability, ColumnNameTable nameTable,
      ColumnConstraintDescriptorSet constraintSet, long charge) {
    typeDescriptors = descriptors;
    nullable = nullability;
    names = nameTable;
    constraints = constraintSet;
    byteCharge = charge;
  }

  /** Caller-owned publication result for one immutable column set. */
  public static final class Result {
    private ColumnDescriptorSet value;

    public void reset() {
      value = null;
    }

    public ColumnDescriptorSet value() {
      return value;
    }

    void set(ColumnDescriptorSet published) {
      value = published;
    }
  }

  public static StatusCode create(
      int[] descriptors, CharSequence[] names, boolean[] nullability, Result result) {
    int count = descriptors == null ? -1 : descriptors.length;
    return create(descriptors, 0, names, 0, nullability, 0, count, result, null);
  }

  public static StatusCode createConstrained(
      int[] descriptors, CharSequence[] names, boolean[] nullability,
      ColumnConstraintDescriptorSet constraints, Result result, StatusDetail detail) {
    int count = descriptors == null ? -1 : descriptors.length;
    return createConstrained(
        descriptors, names, nullability, count, constraints, result, detail);
  }

  public static StatusCode createConstrained(
      int[] descriptors, CharSequence[] names, boolean[] nullability, int count,
      ColumnConstraintDescriptorSet constraints, Result result, StatusDetail detail) {
    return ColumnDescriptorSetFactory.create(
        descriptors, 0, names, 0, nullability, 0, count,
        constraints, result, detail);
  }

  public static StatusCode create(
      int[] descriptors,
      CharSequence[] names,
      boolean[] nullability,
      Result result,
      StatusDetail detail) {
    int count = descriptors == null ? -1 : descriptors.length;
    return create(descriptors, 0, names, 0, nullability, 0, count, result, detail);
  }

  public static StatusCode create(
      int[] descriptors,
      int descriptorOffset,
      CharSequence[] names,
      int nameOffset,
      boolean[] nullability,
      int nullabilityOffset,
      int count,
      Result result,
      StatusDetail detail) {
    return ColumnDescriptorSetFactory.create(
        descriptors, descriptorOffset, names, nameOffset, nullability, nullabilityOffset,
        count, result, detail);
  }

  public int count() {
    return typeDescriptors.length;
  }

  public int typeDescriptorAt(int index) {
    return index >= 0 && index < typeDescriptors.length ? typeDescriptors[index] : 0;
  }

  public boolean isNullable(int index) {
    return index >= 0 && index < nullable.length && nullable[index] != 0;
  }

  public int defaultKindAt(int index) {
    return constraints == null || index < 0 || index >= count()
        ? 0 : constraints.defaultKindAt(index);
  }

  public long defaultHighAt(int index) {
    return constraints == null || index < 0 || index >= count()
        ? 0 : constraints.defaultHighAt(index);
  }

  public long defaultValueAt(int index) {
    return constraints == null || index < 0 || index >= count()
        ? 0 : constraints.defaultValueAt(index);
  }

  public int checkComparisonAt(int index) {
    return constraints == null || index < 0 || index >= count()
        ? 0 : constraints.checkComparisonAt(index);
  }

  public int checkTypeAt(int index) {
    return constraints == null || index < 0 || index >= count()
        ? 0 : constraints.checkTypeAt(index);
  }

  public long checkHighAt(int index) {
    return constraints == null || index < 0 || index >= count()
        ? 0 : constraints.checkHighAt(index);
  }

  public long checkValueAt(int index) {
    return constraints == null || index < 0 || index >= count()
        ? 0 : constraints.checkValueAt(index);
  }

  public int nameByteLength(int index) {
    return names.length(index);
  }

  public int nameByteAt(int index, int byteIndex) {
    return names.byteAt(index, byteIndex);
  }

  public StatusCode copyNameBytes(int index, byte[] destination, int destinationOffset) {
    return names.copyBytes(index, destination, destinationOffset);
  }

  public int copyNameChars(int index, char[] destination, int destinationOffset) {
    return names.copyChars(index, destination, destinationOffset);
  }

  public int find(CharSequence name) {
    return names.find(name);
  }

  public long byteCharge() {
    return byteCharge;
  }
}
