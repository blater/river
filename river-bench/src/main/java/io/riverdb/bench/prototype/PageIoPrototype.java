package io.riverdb.bench.prototype;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import io.riverdb.base.error.StatusCode;

/** Reusable positional NIO page operations backed only by an owned temp file. */
public final class PageIoPrototype implements AutoCloseable {
  private final int pageSize;
  private final Path path;
  private final FileChannel channel;
  private final ByteBuffer writeBuffer;
  private final ByteBuffer readBuffer;
  private final PageIoCounters counters = new PageIoCounters();
  private boolean closed;

  private PageIoPrototype(int pageSize, Path path, FileChannel channel) {
    this.pageSize = pageSize;
    this.path = path;
    this.channel = channel;
    writeBuffer = ByteBuffer.allocateDirect(pageSize).order(ByteOrder.LITTLE_ENDIAN);
    readBuffer = ByteBuffer.allocateDirect(pageSize).order(ByteOrder.LITTLE_ENDIAN);
  }

  public static StatusCode openTemp(int pageSize, PageIoOpenResult target) {
    if (pageSize != 8 * 1024 && pageSize != 16 * 1024 && pageSize != 32 * 1024) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    try {
      Path path = Files.createTempFile("river-page-io-", ".scratch");
      FileChannel channel = FileChannel.open(
        path,
        StandardOpenOption.READ,
        StandardOpenOption.WRITE,
        StandardOpenOption.TRUNCATE_EXISTING
      );
      target.value = new PageIoPrototype(pageSize, path, channel);
      return StatusCode.OK;
    } catch (IOException failure) {
      target.value = null;
      return StatusCode.IO_FAILURE;
    }
  }

  public void prepare(long seed) {
    writeBuffer.clear();
    for (int offset = 0; offset < pageSize; offset += Long.BYTES) {
      writeBuffer.putLong(offset, seed ^ offset);
    }
  }

  public StatusCode writePage(long pageNumber) {
    writeBuffer.position(0);
    writeBuffer.limit(pageSize);
    long position = pageNumber * pageSize;
    int total = 0;
    try {
      while (writeBuffer.hasRemaining()) {
        int written = channel.write(writeBuffer, position + total);
        if (written <= 0) {
          counters.failures++;
          return StatusCode.IO_FAILURE;
        }
        total += written;
      }
      counters.writtenBytes += total;
      return StatusCode.OK;
    } catch (IOException failure) {
      counters.failures++;
      return StatusCode.IO_FAILURE;
    }
  }

  public StatusCode readPage(long pageNumber) {
    readBuffer.clear();
    long position = pageNumber * pageSize;
    int total = 0;
    try {
      while (readBuffer.hasRemaining()) {
        int read = channel.read(readBuffer, position + total);
        if (read <= 0) {
          counters.failures++;
          return StatusCode.IO_FAILURE;
        }
        total += read;
      }
      counters.readBytes += total;
      return StatusCode.OK;
    } catch (IOException failure) {
      counters.failures++;
      return StatusCode.IO_FAILURE;
    }
  }

  public StatusCode force() {
    try {
      channel.force(false);
      counters.forceCalls++;
      return StatusCode.OK;
    } catch (IOException failure) {
      counters.failures++;
      return StatusCode.IO_FAILURE;
    }
  }

  public long readLong(int offset) {
    return readBuffer.getLong(offset);
  }

  public int pageSize() {
    return pageSize;
  }

  public PageIoCounters counters() {
    return counters;
  }

  @Override
  public void close() {
    if (closed) {
      return;
    }
    closed = true;
    try {
      channel.close();
    } catch (IOException failure) {
      counters.failures++;
    }
    try {
      Files.deleteIfExists(path);
    } catch (IOException failure) {
      counters.failures++;
    }
  }
}
