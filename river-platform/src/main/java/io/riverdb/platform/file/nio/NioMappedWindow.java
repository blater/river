package io.riverdb.platform.file.nio;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.FileChannel;

/** One bounded mapping; {@link NioDurableFile} owns its lifecycle lock and force pin. */
final class NioMappedWindow implements AutoCloseable {
  static final int BYTES = 16 * 1024 * 1024;
  private final FileChannel channel;
  private final int windowBytes;
  private Arena arena;
  private MappedByteBuffer writerBytes;
  private MappedByteBuffer forceBytes;
  private boolean closed;
  private long offset;
  private boolean dirty;
  private int dirtyStart;
  private int dirtyEnd;
  private boolean capturedDirty;
  private int capturedDirtyStart;
  private int capturedDirtyEnd;

  NioMappedWindow(FileChannel channel, int windowBytes) {
    this.channel = channel;
    this.windowBytes = windowBytes;
  }

  int offset(long position) { return (int) (position - offset); }
  int remaining(long position) { return writerBytes.capacity() - offset(position); }
  boolean covers(long position) {
    return writerBytes != null && position >= offset && position - offset < writerBytes.capacity();
  }

  boolean map(long position, boolean writing) throws IOException {
    if (closed) throw new ClosedChannelException();
    if (covers(position)) return false;
    release();
    long start = position - position % windowBytes;
    long oldSize = channel.size();
    long length = writing ? Math.min(windowBytes, Long.MAX_VALUE - start)
        : Math.min(windowBytes, oldSize - start);
    boolean metadataChanged = writing && start + length > oldSize;
    Arena opened = Arena.ofShared();
    try {
      MemorySegment mapped = channel.map(FileChannel.MapMode.READ_WRITE, start, length, opened);
      writerBytes = (MappedByteBuffer) mapped.asByteBuffer();
      forceBytes = writerBytes.duplicate();
      arena = opened;
      offset = start;
      return metadataChanged;
    } catch (IOException | RuntimeException | Error failure) {
      opened.close();
      throw failure;
    }
  }

  void read(long position, ByteBuffer target, int count) {
    int targetPosition = target.position();
    target.put(targetPosition, writerBytes, offset(position), count);
    target.position(targetPosition + count);
  }

  void write(long position, ByteBuffer source, int count) {
    int start = offset(position);
    int sourcePosition = source.position();
    writerBytes.put(start, source, sourcePosition, count);
    source.position(sourcePosition + count);
    if (!dirty) {
      dirtyStart = start;
      dirtyEnd = start + count;
      dirty = true;
    } else {
      dirtyStart = Math.min(dirtyStart, start);
      dirtyEnd = Math.max(dirtyEnd, start + count);
    }
  }

  void force() {
    if (!dirty) return;
    forceRange(offset + dirtyStart, offset + dirtyEnd);
  }

  void forceRange(long start, long end) {
    if (!dirty || end <= start || start >= offset + dirtyEnd || end <= offset + dirtyStart) {
      return;
    }
    int localStart = (int) Math.max(0, start - offset);
    int localEnd = (int) Math.min(writerBytes.capacity(), end - offset);
    if (localStart >= localEnd) return;
    writerBytes.force(localStart, localEnd - localStart);
    subtractDirty(localStart, localEnd);
  }

  boolean captureForce(long start, long end, boolean range) {
    capturedDirty = false;
    if (!dirty) return false;
    int localStart = dirtyStart;
    int localEnd = dirtyEnd;
    if (range) {
      if (end <= start || start >= offset + dirtyEnd || end <= offset + dirtyStart) {
        return false;
      }
      localStart = (int) Math.max(dirtyStart, start - offset);
      localEnd = (int) Math.min(dirtyEnd, end - offset);
    }
    if (localStart >= localEnd) return false;
    capturedDirtyStart = localStart;
    capturedDirtyEnd = localEnd;
    capturedDirty = true;
    return true;
  }

  void forceCaptured() {
    if (capturedDirty) {
      forceBytes.force(capturedDirtyStart, capturedDirtyEnd - capturedDirtyStart);
    }
  }

  void completeCapturedForce(boolean succeeded) {
    if (succeeded && capturedDirty) subtractDirty(capturedDirtyStart, capturedDirtyEnd);
    capturedDirty = false;
    capturedDirtyStart = 0;
    capturedDirtyEnd = 0;
  }

  void release() {
    if (arena == null) return;
    force();
    unmap();
  }

  private void unmap() {
    if (arena == null) return;
    arena.close();
    arena = null;
    writerBytes = null;
    forceBytes = null;
    dirty = false;
    dirtyStart = 0;
    dirtyEnd = 0;
    capturedDirty = false;
    capturedDirtyStart = 0;
    capturedDirtyEnd = 0;
  }

  @Override
  public void close() {
    closed = true;
    try { force(); }
    finally { unmap(); }
  }

  private void subtractDirty(int start, int end) {
    if (start <= dirtyStart && end >= dirtyEnd) {
      dirty = false;
    } else if (start <= dirtyStart) {
      dirtyStart = end;
    } else if (end >= dirtyEnd) {
      dirtyEnd = start;
    }
    if (dirtyStart >= dirtyEnd) dirty = false;
  }
}
