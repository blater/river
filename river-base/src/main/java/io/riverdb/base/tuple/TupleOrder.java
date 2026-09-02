package io.riverdb.base.tuple;

import io.riverdb.base.error.StatusCode;

/** Exact-size immutable direction and NULL-placement policy for a tuple shape. */
public final class TupleOrder {
  public static final int ASC_NULLS_FIRST = 0;
  public static final int ASC_NULLS_LAST = 1;
  public static final int DESC_NULLS_FIRST = 2;
  public static final int DESC_NULLS_LAST = 3;

  private final byte[] policies;

  private TupleOrder(byte[] ownedPolicies) {
    policies = ownedPolicies;
  }

  public static final class Result {
    private TupleOrder value;

    public void reset() {
      value = null;
    }

    public TupleOrder value() {
      return value;
    }
  }

  public static StatusCode create(
      TupleShape shape, byte[] source, int offset, Result result) {
    if (result == null) return StatusCode.INVALID_EXTERNAL_INPUT;
    result.reset();
    int count = shape == null ? -1 : shape.partCount();
    if (source == null || offset < 0 || count <= 0 || offset > source.length - count) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    byte[] copy;
    try {
      copy = new byte[count];
    } catch (OutOfMemoryError error) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    for (int index = 0; index < count; index++) {
      int policy = Byte.toUnsignedInt(source[offset + index]);
      if (policy > DESC_NULLS_LAST) return StatusCode.INVALID_EXTERNAL_INPUT;
      copy[index] = (byte) policy;
    }
    try {
      result.value = new TupleOrder(copy);
    } catch (OutOfMemoryError error) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    return StatusCode.OK;
  }

  public int partCount() {
    return policies.length;
  }

  public boolean descending(int index) {
    int policy = policyAt(index);
    return policy == DESC_NULLS_FIRST || policy == DESC_NULLS_LAST;
  }

  public boolean nullsFirst(int index) {
    int policy = policyAt(index);
    return policy == ASC_NULLS_FIRST || policy == DESC_NULLS_FIRST;
  }

  private int policyAt(int index) {
    return index >= 0 && index < policies.length
        ? Byte.toUnsignedInt(policies[index]) : -1;
  }
}
