package io.riverdb.platform.file.nio;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.FileChannel;

/** One bounded mapping; the file owner serializes access and closes it before truncation. */
final class NioMappedWindow implements AutoCloseable {
  static final int BYTES = 16 * 1024 * 1024;
  private final FileChannel channel;
  private final int windowBytes;
  private Arena arena;
  private ByteBuffer bytes;
  private MemorySegment segment;
  private boolean metadataDirty;
  private boolean closed;
  private long offset;
  private boolean dirty;

  NioMappedWindow(FileChannel channel, int windowBytes) {
    this.channel = channel;
    this.windowBytes = windowBytes;
  }

  int offset(long position) { return (int) (position - offset); }
  int remaining(long position) { return bytes.capacity() - offset(position); }

  void map(long position, boolean writing) throws IOException {
    if (closed) throw new ClosedChannelException();
    if (bytes != null && position >= offset && position - offset < bytes.capacity()) return;
    release();
    long start = position - position % windowBytes;
    long oldSize = channel.size();
    long length = writing ? Math.min(windowBytes, Long.MAX_VALUE - start)
        : Math.min(windowBytes, oldSize - start);
    metadataDirty |= writing && start + length > oldSize;
    Arena opened = Arena.ofShared();
    try {
      MemorySegment mapped = channel.map(FileChannel.MapMode.READ_WRITE, start, length, opened);
      bytes = mapped.asByteBuffer();
      segment = mapped;
      arena = opened;
      offset = start;
    } catch (IOException | RuntimeException | Error failure) {
      opened.close();
      throw failure;
    }
  }

  void read(long position, ByteBuffer target, int count) {
    int targetPosition = target.position();
    target.put(targetPosition, bytes, offset(position), count);
    target.position(targetPosition + count);
  }

  void write(long position, ByteBuffer source, int count) {
    int start = offset(position);
    int sourcePosition = source.position();
    bytes.put(start, source, sourcePosition, count);
    source.position(sourcePosition + count);
    dirty = true;
  }

  void force() {
    if (!dirty) return;
    segment.force();
    dirty = false;
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
    bytes = null;
    segment = null;
    dirty = false;
  }

  @Override
  public synchronized void close() {
    closed = true;
    try { force(); }
    finally { unmap(); }
  }
}
