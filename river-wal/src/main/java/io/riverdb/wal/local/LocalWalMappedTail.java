package io.riverdb.wal.local;

import io.riverdb.base.error.StatusCode;
import io.riverdb.format.wal.WalFileHeaderCodec;
import io.riverdb.platform.file.DurableFile;
import io.riverdb.platform.file.ForceMode;
import io.riverdb.platform.file.IoResult;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.zip.CRC32C;

/** Durable logical end for a preallocated mapped WAL file. */
final class LocalWalMappedTail {
  private static final int SLOT_BYTES = 32;
  private static final int SLOTS_OFFSET = 64;
  private static final int CHECKSUM_OFFSET = 24;
  private static final int COMPLEMENT_OFFSET = 28;

  private final ByteBuffer slots = ByteBuffer.allocate(64).order(ByteOrder.LITTLE_ENDIAN);
  private final CRC32C checksum = new CRC32C();
  private final IoResult io = new IoResult();
  private long sequence;
  private long logicalEnd;

  StatusCode load(DurableFile file, long physicalSize) {
    if (file == null || physicalSize < WalFileHeaderCodec.HEADER_BYTES) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    StatusCode status = readFully(file, SLOTS_OFFSET, slots.capacity());
    if (!status.isOk()) {
      return status;
    }

    int first = validateSlot(0, physicalSize);
    int second = validateSlot(SLOT_BYTES, physicalSize);
    if (first < 0 && second < 0) {
      return StatusCode.CORRUPTION;
    }
    if (first == 0 && second == 0) return StatusCode.CORRUPTION;
    if (first > 0 && second <= 0) {
      sequence = slots.getLong(0);
      logicalEnd = slots.getLong(8);
      return StatusCode.OK;
    }
    if (second > 0 && first <= 0) {
      sequence = slots.getLong(SLOT_BYTES);
      logicalEnd = slots.getLong(SLOT_BYTES + 8);
      return StatusCode.OK;
    }
    if (first != 1 || second != 1) {
      return StatusCode.CORRUPTION;
    }
    long firstSequence = slots.getLong(0);
    long secondSequence = slots.getLong(SLOT_BYTES);
    if (firstSequence == secondSequence) return StatusCode.CORRUPTION;
    if (firstSequence > secondSequence) {
      sequence = firstSequence;
      logicalEnd = slots.getLong(8);
    } else {
      sequence = secondSequence;
      logicalEnd = slots.getLong(SLOT_BYTES + 8);
    }
    return StatusCode.OK;
  }

  StatusCode persist(DurableFile file, long nextLogicalEnd) {
    if (file == null || nextLogicalEnd < WalFileHeaderCodec.HEADER_BYTES) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    if (sequence == Long.MAX_VALUE) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    long nextSequence = sequence + 1L;
    int slotOffset = sequence == 0L || (sequence & 1L) == 0L
        ? 0 : SLOT_BYTES;
    slots.clear();
    slots.limit(SLOT_BYTES);
    slots.putLong(nextSequence);
    slots.putLong(nextLogicalEnd);
    slots.putLong(0L);
    checksum.reset();
    slots.flip();
    slots.limit(CHECKSUM_OFFSET);
    checksum.update(slots);
    int value = (int) checksum.getValue();
    slots.limit(SLOT_BYTES);
    slots.position(CHECKSUM_OFFSET);
    slots.putInt(value);
    slots.putInt(~value);
    slots.flip();

    StatusCode status = writeFully(file, SLOTS_OFFSET + slotOffset, SLOT_BYTES);
    if (!status.isOk()) {
      return status;
    }
    status = file.force(ForceMode.CONTENT_AND_METADATA);
    if (status.isOk()) {
      sequence = nextSequence;
      logicalEnd = nextLogicalEnd;
    }
    return status;
  }

  long logicalEnd() {
    return logicalEnd;
  }

  private int validateSlot(int offset, long physicalSize) {
    long slotSequence = slots.getLong(offset);
    long slotEnd = slots.getLong(offset + 8);
    long reserved = slots.getLong(offset + 16);
    int stored = slots.getInt(offset + CHECKSUM_OFFSET);
    int complement = slots.getInt(offset + COMPLEMENT_OFFSET);
    if (slotSequence == 0L && slotEnd == 0L && reserved == 0L
        && stored == 0 && complement == 0) {
      return 0;
    }
    if (slotSequence <= 0L || slotEnd < WalFileHeaderCodec.HEADER_BYTES
        || slotEnd > physicalSize || reserved != 0L || complement != ~stored) {
      return -1;
    }
    checksum.reset();
    slots.position(offset);
    slots.limit(offset + CHECKSUM_OFFSET);
    checksum.update(slots);
    slots.limit(slots.capacity());
    return (int) checksum.getValue() == stored ? 1 : -1;
  }

  private StatusCode readFully(DurableFile file, long position, int bytes) {
    slots.clear();
    slots.limit(bytes);
    io.reset();
    StatusCode status = file.read(position, slots, io);
    if (!status.isOk()) {
      return status;
    }
    return io.bytesTransferred() == bytes && slots.position() == bytes
        ? StatusCode.OK : StatusCode.IO_FAILURE;
  }

  private StatusCode writeFully(DurableFile file, long position, int bytes) {
    io.reset();
    StatusCode status = file.write(position, slots, io);
    if (!status.isOk()) {
      return status;
    }
    return io.bytesTransferred() == bytes && !slots.hasRemaining()
        ? StatusCode.OK : StatusCode.IO_FAILURE;
  }
}
