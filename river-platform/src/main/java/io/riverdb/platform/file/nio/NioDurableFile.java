package io.riverdb.platform.file.nio;

import io.riverdb.base.error.StatusCode;
import io.riverdb.platform.file.DurableFile;
import io.riverdb.platform.file.FileSizeResult;
import io.riverdb.platform.file.ForceMode;
import io.riverdb.platform.file.IoResult;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;

final class NioDurableFile implements DurableFile {
  private static final int MAX_ZERO_PROGRESS = 16;

  private final NioDurableDirectory owner;
  private final FileChannel channel;
  private final long generation;
  private final int slot;
  private final long slotEpoch;
  private final ByteBuffer extensionByte = ByteBuffer.allocate(1);
  private boolean closed;

  NioDurableFile(
      NioDurableDirectory owner,
      FileChannel channel,
      long generation,
      int slot,
      long slotEpoch) {
    this.owner = owner;
    this.channel = channel;
    this.generation = generation;
    this.slot = slot;
    this.slotEpoch = slotEpoch;
  }

  @Override
  public StatusCode read(long position, ByteBuffer target, IoResult result) {
    if (target == null || result == null) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    result.reset();
    StatusCode admission = owner.admit(this, generation, slot, slotEpoch, closed);
    if (!admission.isOk()) {
      return admission;
    }
    if (position < 0 || target.isReadOnly()) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    int transferred = 0;
    int zeroProgress = 0;
    try {
      while (target.hasRemaining()) {
        int read = channel.read(target, position + transferred);
        if (read < 0) {
          break;
        }
        if (read == 0) {
          if (++zeroProgress == MAX_ZERO_PROGRESS) {
            result.setBytesTransferred(transferred);
            owner.counters().recordRead(transferred);
            return StatusCode.RETRY;
          }
          continue;
        }
        zeroProgress = 0;
        transferred += read;
      }
      result.setBytesTransferred(transferred);
      owner.counters().recordRead(transferred);
      return StatusCode.OK;
    } catch (IOException failure) {
      result.setBytesTransferred(transferred);
      owner.counters().recordRead(transferred);
      return NioStatusMapper.known(failure);
    }
  }

  @Override
  public StatusCode write(long position, ByteBuffer source, IoResult result) {
    if (source == null || result == null) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    result.reset();
    StatusCode admission = owner.admit(this, generation, slot, slotEpoch, closed);
    if (!admission.isOk()) {
      return admission;
    }
    if (position < 0) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    int transferred = 0;
    int initialPosition = source.position();
    int zeroProgress = 0;
    try {
      while (source.hasRemaining()) {
        int written = channel.write(source, position + transferred);
        if (written == 0) {
          if (++zeroProgress == MAX_ZERO_PROGRESS) {
            result.setBytesTransferred(transferred);
            owner.counters().recordWrite(transferred);
            return StatusCode.RETRY;
          }
          continue;
        }
        zeroProgress = 0;
        transferred += written;
      }
      result.setBytesTransferred(transferred);
      owner.counters().recordWrite(transferred);
      return StatusCode.OK;
    } catch (IOException failure) {
      int observed = source.position() - initialPosition;
      int completed = Math.max(transferred, observed);
      result.setBytesTransferred(completed);
      owner.counters().recordWrite(completed);
      return NioStatusMapper.known(failure);
    }
  }

  @Override
  public StatusCode force(ForceMode mode) {
    StatusCode admission = owner.admit(this, generation, slot, slotEpoch, closed);
    if (!admission.isOk()) {
      return admission;
    }
    if (mode == null) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    try {
      channel.force(mode == ForceMode.CONTENT_AND_METADATA);
      owner.counters().recordForce();
      return StatusCode.OK;
    } catch (IOException failure) {
      return NioStatusMapper.known(failure);
    }
  }

  @Override
  public StatusCode truncate(long sizeBytes) {
    StatusCode admission = owner.admit(this, generation, slot, slotEpoch, closed);
    if (!admission.isOk()) {
      return admission;
    }
    if (sizeBytes < 0) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    try {
      long currentSize = channel.size();
      if (sizeBytes < currentSize) {
        channel.truncate(sizeBytes);
      } else if (sizeBytes > currentSize) {
        extensionByte.clear();
        int zeroProgress = 0;
        while (extensionByte.hasRemaining()) {
          int written = channel.write(extensionByte, sizeBytes - 1);
          if (written == 0 && ++zeroProgress == MAX_ZERO_PROGRESS) {
            return StatusCode.RETRY;
          }
        }
        owner.counters().recordWrite(1);
      }
      return StatusCode.OK;
    } catch (IOException failure) {
      return NioStatusMapper.known(failure);
    }
  }

  @Override
  public StatusCode size(FileSizeResult result) {
    StatusCode admission = owner.admit(this, generation, slot, slotEpoch, closed);
    if (!admission.isOk()) {
      return admission;
    }
    if (result == null) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    try {
      result.setSizeBytes(channel.size());
      return StatusCode.OK;
    } catch (IOException failure) {
      return NioStatusMapper.known(failure);
    }
  }

  @Override
  public StatusCode close() {
    if (closed) {
      return StatusCode.CLOSED;
    }
    closed = true;
    return owner.closeHandle(this, channel, generation, slot, slotEpoch);
  }

  StatusCode closeForGenerationChange() {
    closed = true;
    try {
      channel.close();
      return StatusCode.OK;
    } catch (IOException failure) {
      return NioStatusMapper.known(failure);
    }
  }
}
