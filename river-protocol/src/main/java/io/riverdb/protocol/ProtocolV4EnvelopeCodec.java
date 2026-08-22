package io.riverdb.protocol;

import io.riverdb.base.error.StatusCode;
import java.nio.ByteBuffer;

/** Canonical network-big-endian bounds for the three protocol v4 data envelopes. */
public final class ProtocolV4EnvelopeCodec {
  public static final int VERSION = 4;
  public static final int KIND_SQL_REQUEST = 1;
  public static final int KIND_QUERY_METADATA = 2;
  public static final int KIND_PACKED_ROW = 3;
  public static final int MAXIMUM_COLUMNS = 255;
  public static final int MAXIMUM_PACKED_VALUE_BYTES = 4_096;
  public static final int MAXIMUM_SQL_REQUEST_BYTES = 256 * 1024;
  public static final int MAXIMUM_QUERY_METADATA_BYTES = 32 * 1024;
  public static final int MAXIMUM_PACKED_ROW_BYTES = 8 * 1024;
  public static final int HEADER_BYTES = 64;

  private static final long MAGIC = 0x5249565052563400L; // RIVPRV4\0
  private static final int DESCRIPTOR_BYTES = Integer.BYTES;
  private static final int OFFSET_LENGTH_BYTES = Integer.BYTES * 2;
  private static final int METADATA_LENGTH_BYTES = Short.BYTES;

  private ProtocolV4EnvelopeCodec() {
  }

  /**
   * Encodes an envelope header at {@code start}. The body is written by the kind-specific owner.
   * Prefix means SQL text bytes, packed name bytes, or packed row-value bytes respectively.
   */
  public static StatusCode encode(
      ByteBuffer target,
      int start,
      int kind,
      int totalBytes,
      int elementCount,
      int prefixBytes,
      long firstMask,
      long secondMask,
      long thirdMask,
      long fourthMask) {
    if (target == null
        || target.isReadOnly()
        || start < 0
        || target.limit() - start < totalBytes
        || !valid(
            kind,
            totalBytes,
            elementCount,
            prefixBytes,
            firstMask,
            secondMask,
            thirdMask,
            fourthMask)) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    putLong(target, start, MAGIC);
    putInt(target, start + 8, VERSION);
    putInt(target, start + 12, kind);
    putInt(target, start + 16, totalBytes);
    putInt(target, start + 20, elementCount);
    putInt(target, start + 24, prefixBytes);
    putInt(target, start + 28, 0);
    putLong(target, start + 32, firstMask);
    putLong(target, start + 40, secondMask);
    putLong(target, start + 48, thirdMask);
    putLong(target, start + 56, fourthMask);
    return StatusCode.OK;
  }

  public static StatusCode decode(
      ByteBuffer source, int start, ProtocolV4Envelope result) {
    if (result == null) return StatusCode.INVALID_EXTERNAL_INPUT;
    result.reset();
    if (source == null || start < 0 || source.limit() - start < HEADER_BYTES) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    int kind = getInt(source, start + 12);
    int totalBytes = getInt(source, start + 16);
    int elements = getInt(source, start + 20);
    int prefix = getInt(source, start + 24);
    long first = getLong(source, start + 32);
    long second = getLong(source, start + 40);
    long third = getLong(source, start + 48);
    long fourth = getLong(source, start + 56);
    if (getLong(source, start) != MAGIC
        || getInt(source, start + 8) != VERSION
        || getInt(source, start + 28) != 0
        || source.limit() - start != totalBytes
        || !valid(kind, totalBytes, elements, prefix, first, second, third, fourth)) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    result.set(kind, totalBytes, elements, prefix, first, second, third, fourth);
    return StatusCode.OK;
  }

  public static int sqlRequestBytes(int sqlBytes, int parameters, int packedValueBytes) {
    if (sqlBytes <= 0
        || parameters < 0
        || parameters > MAXIMUM_COLUMNS
        || packedValueBytes < 0
        || packedValueBytes > MAXIMUM_PACKED_VALUE_BYTES) {
      return 0;
    }
    long total = (long) HEADER_BYTES + sqlBytes
        + (long) parameters * (DESCRIPTOR_BYTES + OFFSET_LENGTH_BYTES)
        + packedValueBytes;
    return total <= MAXIMUM_SQL_REQUEST_BYTES ? (int) total : 0;
  }

  public static int queryMetadataBytes(int columns, int packedNameBytes) {
    if (columns <= 0
        || columns > MAXIMUM_COLUMNS
        || packedNameBytes < columns
        || packedNameBytes > columns * ProtocolFrameCodec.MAXIMUM_COLUMN_NAME_BYTES) {
      return 0;
    }
    int total = HEADER_BYTES
        + columns * (DESCRIPTOR_BYTES + METADATA_LENGTH_BYTES)
        + packedNameBytes;
    return total <= MAXIMUM_QUERY_METADATA_BYTES ? total : 0;
  }

  public static int packedRowBytes(int columns, int packedValueBytes) {
    if (columns <= 0
        || columns > MAXIMUM_COLUMNS
        || packedValueBytes < 0
        || packedValueBytes > MAXIMUM_PACKED_VALUE_BYTES) {
      return 0;
    }
    int total = HEADER_BYTES
        + columns * (DESCRIPTOR_BYTES + OFFSET_LENGTH_BYTES)
        + packedValueBytes;
    return total <= MAXIMUM_PACKED_ROW_BYTES ? total : 0;
  }

  private static boolean valid(
      int kind,
      int totalBytes,
      int elements,
      int prefixBytes,
      long firstMask,
      long secondMask,
      long thirdMask,
      long fourthMask) {
    if (elements < 0
        || elements > MAXIMUM_COLUMNS
        || prefixBytes < 0
        || !validMask(elements, firstMask, secondMask, thirdMask, fourthMask)) {
      return false;
    }
    return switch (kind) {
      case KIND_SQL_REQUEST -> totalBytes == sqlRequestBytes(
          prefixBytes,
          elements,
          totalBytes - HEADER_BYTES - prefixBytes
              - elements * (DESCRIPTOR_BYTES + OFFSET_LENGTH_BYTES));
      case KIND_QUERY_METADATA -> totalBytes == queryMetadataBytes(elements, prefixBytes);
      case KIND_PACKED_ROW -> totalBytes == packedRowBytes(elements, prefixBytes);
      default -> false;
    };
  }

  private static boolean validMask(
      int elements, long first, long second, long third, long fourth) {
    return noBitsFrom(first, elements)
        && noBitsFrom(second, elements - 64)
        && noBitsFrom(third, elements - 128)
        && noBitsFrom(fourth, elements - 192)
        && (fourth & Long.MIN_VALUE) == 0;
  }

  private static boolean noBitsFrom(long word, int usedBits) {
    if (usedBits <= 0) return word == 0;
    if (usedBits >= Long.SIZE) return true;
    return (word & (-1L << usedBits)) == 0;
  }

  private static void putInt(ByteBuffer target, int offset, int value) {
    target.put(offset, (byte) (value >>> 24));
    target.put(offset + 1, (byte) (value >>> 16));
    target.put(offset + 2, (byte) (value >>> 8));
    target.put(offset + 3, (byte) value);
  }

  private static int getInt(ByteBuffer source, int offset) {
    return Byte.toUnsignedInt(source.get(offset)) << 24
        | Byte.toUnsignedInt(source.get(offset + 1)) << 16
        | Byte.toUnsignedInt(source.get(offset + 2)) << 8
        | Byte.toUnsignedInt(source.get(offset + 3));
  }

  private static void putLong(ByteBuffer target, int offset, long value) {
    putInt(target, offset, (int) (value >>> 32));
    putInt(target, offset + Integer.BYTES, (int) value);
  }

  private static long getLong(ByteBuffer source, int offset) {
    return (long) getInt(source, offset) << 32
        | Integer.toUnsignedLong(getInt(source, offset + Integer.BYTES));
  }
}
