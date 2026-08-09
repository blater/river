package io.riverdb.bench.harness;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/** Caller-buffered TSV encoder used by generated workloads. */
final class BoundedTsvOutput {
  static final int MINIMUM_SCRATCH_BYTES = 64;
  static final int MAXIMUM_SCRATCH_BYTES = 1 << 20;

  private final OutputStream output;
  private final byte[] buffer;
  private int position;
  private long byteCount;

  BoundedTsvOutput(OutputStream output, byte[] buffer) {
    this.output = output;
    this.buffer = buffer;
  }

  static boolean validScratch(byte[] scratch) {
    return scratch.length >= MINIMUM_SCRATCH_BYTES
        && scratch.length <= MAXIMUM_SCRATCH_BYTES;
  }

  void append(char value) throws IOException {
    appendByte((byte) value);
  }

  void appendAscii(String value) throws IOException {
    for (int index = 0; index < value.length(); index++) {
      char character = value.charAt(index);
      if (character > 0x7f) {
        throw new IllegalArgumentException("non-ASCII value passed to appendAscii");
      }
      appendByte((byte) character);
    }
  }

  void appendUtf8(byte[] value) throws IOException {
    int offset = 0;
    while (offset < value.length) {
      if (position == buffer.length) {
        flush();
      }
      int length = Math.min(buffer.length - position, value.length - offset);
      System.arraycopy(value, offset, buffer, position, length);
      position += length;
      offset += length;
    }
  }

  void appendLong(long value) throws IOException {
    if (value == Long.MIN_VALUE) {
      appendAscii("-9223372036854775808");
      return;
    }
    if (value < 0) {
      append('-');
      value = -value;
    }
    int digits = 1;
    long remaining = value;
    while (remaining >= 10) {
      remaining /= 10;
      digits++;
    }
    ensure(digits);
    int cursor = position + digits;
    long current = value;
    while (cursor > position) {
      buffer[--cursor] = (byte) ('0' + current % 10);
      current /= 10;
    }
    position += digits;
  }

  void finish() throws IOException {
    flush();
  }

  long byteCount() {
    return byteCount + position;
  }

  private void ensure(int length) throws IOException {
    if (buffer.length - position < length) {
      flush();
    }
  }

  private void appendByte(byte value) throws IOException {
    if (position == buffer.length) {
      flush();
    }
    buffer[position++] = value;
  }

  private void flush() throws IOException {
    if (position == 0) {
      return;
    }
    output.write(buffer, 0, position);
    byteCount = Math.addExact(byteCount, position);
    position = 0;
  }

  static byte[] utf8(String value) {
    return value.getBytes(StandardCharsets.UTF_8);
  }
}
