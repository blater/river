package io.riverdb.base.tuple;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.sql.SqlShapeLimits;
import io.riverdb.base.type.SqlTypeDescriptor;

/** Immutable exact-size description of ordered typed tuple parts. */
public final class TupleShape {
  public static final int MAXIMUM_PARTS = SqlShapeLimits.MAX_TUPLE_PARTS;
  private static final int[] EMPTY = new int[0];

  private final int[] descriptors;
  private final long descriptorHash;
  private final int maximumEncodedBytes;
  private final long retainedByteCharge;

  private TupleShape(int[] ownedDescriptors) {
    descriptors = ownedDescriptors;
    descriptorHash = hash(ownedDescriptors);
    maximumEncodedBytes = TupleEncodingSize.maximumBytes(ownedDescriptors);
    retainedByteCharge = TupleEncodingSize.shapeRetainedBytes(ownedDescriptors.length);
  }

  /** Caller-owned publication carrier used to keep allocation failure status-based. */
  public static final class Result {
    private TupleShape value;

    public void reset() {
      value = null;
    }

    public TupleShape value() {
      return value;
    }

    /** Publishes an already immutable shape without copying its descriptor tuple. */
    public StatusCode use(TupleShape existing) {
      value = existing;
      return existing == null ? StatusCode.INVALID_EXTERNAL_INPUT : StatusCode.OK;
    }
  }

  public static StatusCode create(
      int[] source, int offset, int count, Result result) {
    if (result == null) return StatusCode.INVALID_EXTERNAL_INPUT;
    result.reset();
    if (source == null || offset < 0 || count < 0
        || count > MAXIMUM_PARTS
        || offset > source.length - count) {
      return count > MAXIMUM_PARTS
          ? StatusCode.RESOURCE_EXHAUSTED : StatusCode.INVALID_EXTERNAL_INPUT;
    }
    int[] copy;
    try {
      copy = count == 0 ? EMPTY : new int[count];
    } catch (OutOfMemoryError error) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    for (int index = 0; index < count; index++) {
      int descriptor = source[offset + index];
      if (!SqlTypeDescriptor.isValid(descriptor)) {
        return StatusCode.INVALID_EXTERNAL_INPUT;
      }
      copy[index] = descriptor;
    }
    try {
      result.value = new TupleShape(copy);
    } catch (OutOfMemoryError error) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    return StatusCode.OK;
  }

  public static StatusCode create(int[] source, Result result) {
    return create(source, 0, source == null ? -1 : source.length, result);
  }

  public int partCount() {
    return descriptors.length;
  }

  public int descriptorAt(int index) {
    return index >= 0 && index < descriptors.length ? descriptors[index] : 0;
  }

  public long descriptorHash() {
    return descriptorHash;
  }

  /** Maximum canonical generic-tuple bytes when every part is present. */
  public int maximumEncodedBytes() {
    return maximumEncodedBytes;
  }

  /** Maximum physical-index-key bytes after appending a logical-row-ID suffix. */
  public int maximumPhysicalEncodedBytes() {
    return TupleEncodingSize.physicalBytes(maximumEncodedBytes);
  }

  public int comparisonFamilyAt(int index) {
    return SqlTypeDescriptor.comparisonFamily(descriptorAt(index));
  }

  /** Deterministic conservative charge for this object and its exact-sized descriptor array. */
  public long retainedByteCharge() {
    return retainedByteCharge;
  }

  public StatusCode copyDescriptors(int[] destination, int offset) {
    if (destination == null || offset < 0 || offset > destination.length - descriptors.length) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    System.arraycopy(descriptors, 0, destination, offset, descriptors.length);
    return StatusCode.OK;
  }

  public boolean sameDescriptors(TupleShape other) {
    if (other == null || descriptors.length != other.descriptors.length
        || descriptorHash != other.descriptorHash) return false;
    for (int index = 0; index < descriptors.length; index++) {
      if (descriptors[index] != other.descriptors[index]) return false;
    }
    return true;
  }

  public boolean matchesDescriptors(int[] source, int offset, int count) {
    if (source == null || offset < 0 || count != descriptors.length
        || offset > source.length - count) return false;
    for (int index = 0; index < count; index++) {
      if (descriptors[index] != source[offset + index]) return false;
    }
    return true;
  }

  private static long hash(int[] values) {
    long value = 0xcbf29ce484222325L;
    value = mix(value, values.length);
    for (int descriptor : values) value = mix(value, descriptor);
    return value;
  }

  private static long mix(long hash, int value) {
    for (int shift = 0; shift < Integer.SIZE; shift += Byte.SIZE) {
      hash ^= value >>> shift & 0xff;
      hash *= 0x100000001b3L;
    }
    return hash;
  }

}
