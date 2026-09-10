package io.riverdb.platform.file.nio;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.FileChannel;

/** One bounded mapping; the file owner serializes access and closes it before truncation. */
final class NioMappedWindow implements AutoCloseable {
  static final int BYTES = 16 * 1024 * 1024;
  private final FileChannel channel;
  private final int windowBytes;
  private Arena arena;
  private MappedByteBuffer mappedBytes;
  private boolean metadataDirty;
  private boolean closed;
  private long offset;
  private boolean dirty;
  private int dirtyStart;
  private int dirtyEnd;

  NioMappedWindow(FileChannel channel, int windowBytes) {
    this.channel = channel;
    this.windowBytes = windowBytes;
  }

  int offset(long position) { return (int) (position - offset); }
  int remaining(long position) { return mappedBytes.capacity() - offset(position); }

  void map(long position, boolean writing) throws IOException {
    if (closed) throw new ClosedChannelException();
    if (mappedBytes != null && position >= offset && position - offset < mappedBytes.capacity()) return;
    release();
    long start = position - position % windowBytes;
    long oldSize = channel.size();
    long length = writing ? Math.min(windowBytes, Long.MAX_VALUE - start)
        : Math.min(windowBytes, oldSize - start);
    metadataDirty |= writing && start + length > oldSize;
    Arena opened = Arena.ofShared();
    try {
      MemorySegment mapped = channel.map(FileChannel.MapMode.READ_WRITE, start, length, opened);
      mappedBytes = (MappedByteBuffer) mapped.asByteBuffer();
      arena = opened;
      offset = start;
    } catch (IOException | RuntimeException | Error failure) {
      opened.close();
      throw failure;
    }
  }

  void read(long position, ByteBuffer target, int count) {
    int targetPosition = target.position();
    target.put(targetPosition, mappedBytes, offset(position), count);
    target.position(targetPosition + count);
  }

  void write(long position, ByteBuffer source, int count) {
    int start = offset(position);
    int sourcePosition = source.position();
    mappedBytes.put(start, source, sourcePosition, count);
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
    int localEnd = (int) Math.min(mappedBytes.capacity(), end - offset);
    if (localStart >= localEnd) return;
    mappedBytes.force(localStart, localEnd - localStart);
    if (localStart <= dirtyStart && localEnd >= dirtyEnd) {
      dirty = false;
    } else if (localStart <= dirtyStart) {
      dirtyStart = localEnd;
    } else if (localEnd >= dirtyEnd) {
      dirtyEnd = localStart;
    }
    if (dirtyStart >= dirtyEnd) dirty = false;
  }

  boolean metadataDirty() { return metadataDirty; }
  void metadataForced() { metadataDirty = false; }

  void release() {
    if (arena == null) return;
    force();
    unmap();
  }

  private void unmap() {
    if (arena == null) return;
    arena.close();
    arena = null;
    mappedBytes = null;
    dirty = false;
    dirtyStart = 0;
    dirtyEnd = 0;
  }

  @Override
  public synchronized void close() {
    closed = true;
    try { force(); }
    finally { unmap(); }
  }
}
