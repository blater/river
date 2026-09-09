package io.riverdb.platform.file.nio;

import io.riverdb.base.error.StatusCode;
import io.riverdb.platform.file.DurableFile;
import io.riverdb.platform.file.FileIoMode;
import io.riverdb.platform.file.FileSizeResult;
import io.riverdb.platform.file.ForceMode;
import io.riverdb.platform.file.IoResult;
import java.io.IOException;
import java.io.UncheckedIOException;
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
  private volatile boolean closed;
  private final NioMappedWindow mappedHeader;
  private final NioMappedWindow mappedData;
  private boolean mappedMetadataDirty;

  NioDurableFile(
      NioDurableDirectory owner,
      FileChannel channel,
      long generation,
      int slot,
      long slotEpoch,
      FileIoMode mode) {
    this.owner = owner;
    this.channel = channel;
    this.generation = generation;
    this.slot = slot;
    this.slotEpoch = slotEpoch;
    mappedHeader = mode == FileIoMode.MAPPED ? new NioMappedWindow(channel, 4096) : null;
    mappedData = mode == FileIoMode.MAPPED ? new NioMappedWindow(channel, NioMappedWindow.BYTES) : null;
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
    if (mappedData != null) return transferMapped(position, target, result, false);
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
    if (position > Long.MAX_VALUE - source.remaining()) return StatusCode.RESOURCE_EXHAUSTED;
    if (mappedData != null) return transferMapped(position, source, result, true);
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
      if (mappedData == null) {
        channel.force(mode == ForceMode.CONTENT_AND_METADATA);
      } else {
        synchronized (mappedData) {
          mappedData.force();
          mappedHeader.force();
          if (mode == ForceMode.CONTENT_AND_METADATA
              && (mappedMetadataDirty || mappedData.metadataDirty() || mappedHeader.metadataDirty())) {
            channel.force(true);
            mappedMetadataDirty = false;
            mappedData.metadataForced();
            mappedHeader.metadataForced();
          }
        }
      }
      owner.counters().recordForce();
      return StatusCode.OK;
    } catch (UncheckedIOException failure) {
      return NioStatusMapper.known(failure.getCause());
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
    if (mappedData == null) return resize(sizeBytes);
    synchronized (mappedData) {
      try {
        try { mappedData.release(); }
        finally { mappedHeader.release(); }
        mappedMetadataDirty = true;
        return resize(sizeBytes);
      } catch (UncheckedIOException failure) {
        return NioStatusMapper.known(failure.getCause());
      }
    }
  }

  private StatusCode resize(long sizeBytes) {
    try {
      long currentSize = channel.size();
      if (sizeBytes < currentSize) {
        channel.truncate(sizeBytes);
      } else if (sizeBytes > currentSize) {
        extensionByte.clear();
        int zeroProgress = 0;
        while (extensionByte.hasRemaining()) {
          int written = channel.write(extensionByte, sizeBytes - 1);
          if (written == 0 && ++zeroProgress == MAX_ZERO_PROGRESS) return StatusCode.RETRY;
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
    StatusCode mappedStatus = closeMappings();
    StatusCode channelStatus = owner.closeHandle(this, channel, generation, slot, slotEpoch);
    return mappedStatus.isOk() ? channelStatus : mappedStatus;
  }

  private StatusCode transferMapped(long position, ByteBuffer buffer, IoResult result, boolean write) {
    int initial = buffer.position();
    StatusCode status = StatusCode.OK;
    try {
      synchronized (mappedData) {
        long end = write ? Long.MAX_VALUE : channel.size();
        while (buffer.hasRemaining() && position < end) {
          NioMappedWindow window = position < 4096 ? mappedHeader : mappedData;
          window.map(position, write);
          int count = (int) Math.min(buffer.remaining(), Math.min(window.remaining(position), end - position));
          if (position < 4096) count = (int) Math.min(count, 4096 - position);
          if (write) window.write(position, buffer, count);
          else window.read(position, buffer, count);
          position += count;
        }
      }
    } catch (UncheckedIOException failure) {
      status = NioStatusMapper.known(failure.getCause());
    } catch (IOException failure) {
      status = NioStatusMapper.known(failure);
    } catch (OutOfMemoryError failure) {
      status = StatusCode.RESOURCE_EXHAUSTED;
    }
    int transferred = buffer.position() - initial;
    result.setBytesTransferred(transferred);
    if (write) owner.counters().recordWrite(transferred);
    else owner.counters().recordRead(transferred);
    return status;
  }

  private StatusCode closeMappings() {
    if (mappedData == null) return StatusCode.OK;
    try {
      synchronized (mappedData) {
        try { mappedData.close(); }
        finally { mappedHeader.close(); }
      }
      return StatusCode.OK;
    } catch (UncheckedIOException failure) {
      return NioStatusMapper.known(failure.getCause());
    }
  }

  StatusCode closeForGenerationChange() {
    closed = true;
    StatusCode mappedStatus = closeMappings();
    try {
      channel.close();
      return mappedStatus;
    } catch (IOException failure) {
      return NioStatusMapper.known(failure);
    }
  }
}
