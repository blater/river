package io.riverdb.format.btree;

import io.riverdb.base.error.StatusCode;
import io.riverdb.format.FormatBytes;
import java.nio.ByteBuffer;
import java.util.zip.CRC32C;

/** Self-contained durable tuple-root registry record. */
public final class TupleIndexRootRecordCodec {
  public static final int VERSION = 3;
  public static final int BYTES = 216;
  public static final int STATE_BUILDING = 1;
  public static final int STATE_READY = 2;
  public static final int STATE_DROPPING = 3;
  public static final int STATE_ABSENT = 0;
  private static final long MAGIC = 0x5249565455505252L; // RIVTUPRR
  private static final int DESCRIPTOR_COUNT_OFFSET = 72;
  private static final int RESERVED_OFFSET = 76;
  private static final int DESCRIPTORS_OFFSET = 80;
  private static final int CHECKSUM_OFFSET = 208;
  private static final int COMPLEMENT_OFFSET = 212;

  private TupleIndexRootRecordCodec() { }

  public static StatusCode encode(
      ByteBuffer target, int start, int state, int rootPageId,
      long keyId, long ownerObjectId, long schemaId,
      long descriptorHash, long privateOwner, long generation,
      int[] descriptors, int descriptorOffset, int descriptorCount, CRC32C checksum) {
    int cursor = state == STATE_DROPPING && rootPageId == 0 ? 4 : 0;
    return encode(
        target, start, state, rootPageId, keyId, ownerObjectId, schemaId,
        descriptorHash, privateOwner, generation, cursor,
        descriptors, descriptorOffset, descriptorCount, checksum);
  }

  public static StatusCode encode(
      ByteBuffer target, int start, int state, int rootPageId,
      long keyId, long ownerObjectId, long schemaId,
      long descriptorHash, long privateOwner, long generation, int cleanupCursor,
      int[] descriptors, int descriptorOffset, int descriptorCount, CRC32C checksum) {
    if (!writable(target, start, checksum)
        || !TupleIndexRootRecordValidation.identity(state, rootPageId, keyId, ownerObjectId,
            schemaId, descriptorHash, privateOwner, generation, cleanupCursor)
        || !TupleIndexRootRecordValidation.descriptors(
            descriptors, descriptorOffset, descriptorCount, descriptorHash)) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    FormatBytes.putLong(target, start, MAGIC);
    FormatBytes.putInt(target, start + 8, VERSION);
    FormatBytes.putInt(target, start + 12, BYTES);
    FormatBytes.putInt(target, start + 16, state);
    FormatBytes.putInt(target, start + 20, rootPageId);
    FormatBytes.putLong(target, start + 24, keyId);
    FormatBytes.putLong(target, start + 32, ownerObjectId);
    FormatBytes.putLong(target, start + 40, schemaId);
    FormatBytes.putLong(target, start + 48, descriptorHash);
    FormatBytes.putLong(target, start + 56, privateOwner);
    FormatBytes.putLong(target, start + 64, generation);
    FormatBytes.putInt(target, start + DESCRIPTOR_COUNT_OFFSET, descriptorCount);
    FormatBytes.putInt(target, start + RESERVED_OFFSET, cleanupCursor);
    for (int index = 0; index < TupleKeyCodec.MAX_INDEX_KEY_PARTS; index++) {
      FormatBytes.putInt(target, start + DESCRIPTORS_OFFSET + index * Integer.BYTES,
          index < descriptorCount ? descriptors[descriptorOffset + index] : 0);
    }
    int value = FormatBytes.checksum(target, start, CHECKSUM_OFFSET, checksum);
    FormatBytes.putInt(target, start + CHECKSUM_OFFSET, value);
    FormatBytes.putInt(target, start + COMPLEMENT_OFFSET, ~value);
    return StatusCode.OK;
  }

  public static StatusCode decode(
      ByteBuffer source, int start, TupleIndexRootRecord result, CRC32C checksum) {
    if (result == null) return StatusCode.INVALID_EXTERNAL_INPUT;
    result.reset();
    if (source == null || checksum == null || start < 0) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    if (start > source.limit() - BYTES) return StatusCode.CORRUPTION;
    int state = FormatBytes.getInt(source, start + 16);
    int root = FormatBytes.getInt(source, start + 20);
    long key = FormatBytes.getLong(source, start + 24);
    long object = FormatBytes.getLong(source, start + 32);
    long schema = FormatBytes.getLong(source, start + 40);
    long hash = FormatBytes.getLong(source, start + 48);
    long owner = FormatBytes.getLong(source, start + 56);
    long generation = FormatBytes.getLong(source, start + 64);
    int cleanupCursor = FormatBytes.getInt(source, start + RESERVED_OFFSET);
    int descriptorCount = FormatBytes.getInt(source, start + DESCRIPTOR_COUNT_OFFSET);
    int stored = FormatBytes.getInt(source, start + CHECKSUM_OFFSET);
    if (FormatBytes.getLong(source, start) != MAGIC
        || FormatBytes.getInt(source, start + 8) != VERSION
        || FormatBytes.getInt(source, start + 12) != BYTES
        || FormatBytes.getInt(source, start + COMPLEMENT_OFFSET) != ~stored
        || FormatBytes.checksum(source, start, CHECKSUM_OFFSET, checksum) != stored
        || !TupleIndexRootRecordValidation.identity(
            state, root, key, object, schema, hash, owner, generation, cleanupCursor)
        || !TupleIndexRootRecordValidation.encoded(
            source, start, descriptorCount, DESCRIPTORS_OFFSET, hash)) {
      return StatusCode.CORRUPTION;
    }
    result.set(
        state, root, key, object, schema, hash, owner, generation,
        cleanupCursor, descriptorCount);
    for (int index = 0; index < descriptorCount; index++) {
      result.setDescriptorAt(index, FormatBytes.getInt(
          source, start + DESCRIPTORS_OFFSET + index * Integer.BYTES));
    }
    return StatusCode.OK;
  }

  private static boolean writable(ByteBuffer target, int start, CRC32C checksum) {
    return target != null && !target.isReadOnly() && checksum != null && start >= 0
        && start <= target.limit() - BYTES;
  }

}
