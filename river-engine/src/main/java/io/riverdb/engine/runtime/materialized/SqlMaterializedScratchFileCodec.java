package io.riverdb.engine.runtime.materialized;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.error.StatusDetail;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Exact, private version-one codec for materialized scratch file and page headers.
 *
 * <p>All accesses are absolute and big endian. The caller owns the supplied buffers and the
 * reusable header result. No object is created by encode or validate.
 */
public final class SqlMaterializedScratchFileCodec {
  public static final int FORMAT_VERSION = 1;
  public static final int PAGE_FORMAT_VERSION = 1;
  public static final int FILE_HEADER_BYTES = 64;
  public static final int PAGE_HEADER_BYTES = 32;
  public static final int KNOWN_FLAGS = 0;

  private static final long FILE_MAGIC_BASE = 0x5249564552534300L;
  private static final int PAGE_MAGIC = 0x52504731;
  private static final int HEADER_CRC_OFFSET = 56;

  private SqlMaterializedScratchFileCodec() {}

  public static StatusCode encodeFileHeader(
      ByteBuffer target,
      SqlMaterializedScratchFileKind kind,
      int pageBytes,
      int fixedRecordBytes,
      int flags,
      long fileIdentity,
      long publishedCount,
      long logicalLength) {
    if (target == null || kind == null || target.capacity() < FILE_HEADER_BYTES
        || pageBytes <= PAGE_HEADER_BYTES || (pageBytes & 7) != 0 || fixedRecordBytes < 0
        || (flags & ~KNOWN_FLAGS) != 0 || fileIdentity <= 0
        || publishedCount < 0 || logicalLength < 0) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    target.order(ByteOrder.BIG_ENDIAN);
    target.putLong(0, magic(kind));
    target.putInt(8, FORMAT_VERSION);
    target.putInt(12, FILE_HEADER_BYTES);
    target.putInt(16, fileCode(kind));
    target.putInt(20, pageBytes);
    target.putInt(24, fixedRecordBytes);
    target.putInt(28, flags);
    target.putLong(32, fileIdentity);
    target.putLong(40, publishedCount);
    target.putLong(48, logicalLength);
    target.putInt(56, checksum(target, 0, HEADER_CRC_OFFSET));
    target.putInt(60, 0);
    return StatusCode.OK;
  }

  public static StatusCode validateFileHeader(
      ByteBuffer source,
      SqlMaterializedScratchFileKind expectedKind,
      int expectedPageBytes,
      long expectedFileIdentity,
      Header target,
      StatusDetail detail) {
    if (source == null || expectedKind == null || target == null
        || source.capacity() < FILE_HEADER_BYTES) {
      return fail(detail, StatusCode.INVALID_EXTERNAL_INPUT, "invalid materialized file header buffer");
    }
    target.reset();
    source.order(ByteOrder.BIG_ENDIAN);
    long actualMagic = source.getLong(0);
    int version = source.getInt(8);
    int headerBytes = source.getInt(12);
    int fileCode = source.getInt(16);
    int pageBytes = source.getInt(20);
    int fixedRecordBytes = source.getInt(24);
    int flags = source.getInt(28);
    long identity = source.getLong(32);
    long count = source.getLong(40);
    long length = source.getLong(48);
    int storedCrc = source.getInt(56);
    int reserved = source.getInt(60);
    if (actualMagic != magic(expectedKind)) {
      return fail(detail, StatusCode.CORRUPTION, "materialized file magic mismatch");
    }
    if (version != FORMAT_VERSION) {
      return versionFailure(detail, "file", expectedKind.name(), FORMAT_VERSION, version);
    }
    if (headerBytes != FILE_HEADER_BYTES) {
      return fail(detail, StatusCode.CORRUPTION, "materialized file header bytes mismatch");
    }
    if (fileCode != fileCode(expectedKind)) {
      return fail(detail, StatusCode.CORRUPTION, "materialized file kind mismatch");
    }
    if (pageBytes <= PAGE_HEADER_BYTES || pageBytes != expectedPageBytes) {
      return fail(detail, StatusCode.CORRUPTION, "materialized file page bytes mismatch");
    }
    if (fixedRecordBytes < 0) {
      return fail(detail, StatusCode.CORRUPTION, "materialized file record bytes invalid");
    }
    if ((flags & ~KNOWN_FLAGS) != 0) {
      return fail(detail, StatusCode.CORRUPTION, "materialized file flags invalid");
    }
    if (identity <= 0 || identity != expectedFileIdentity) {
      return fail(detail, StatusCode.CORRUPTION, "materialized file identity mismatch");
    }
    if (count < 0 || length < 0) {
      return fail(detail, StatusCode.CORRUPTION, "materialized file published length invalid");
    }
    if (fixedRecordBytes > 0
        && (length % fixedRecordBytes != 0 || count != length / fixedRecordBytes)) {
      return fail(detail, StatusCode.CORRUPTION, "materialized file fixed-record lengths mismatch");
    }
    if (reserved != 0 || storedCrc != checksum(source, 0, HEADER_CRC_OFFSET)) {
      return fail(detail, StatusCode.CORRUPTION, "materialized file header checksum mismatch");
    }
    target.set(version, fileCode, pageBytes, fixedRecordBytes, flags, identity, count, length);
    return StatusCode.OK;
  }

  public static StatusCode encodePageHeader(
      ByteBuffer target, long fileIdentity, long pageNumber, int usedBytes) {
    if (target == null || target.capacity() < PAGE_HEADER_BYTES
        || fileIdentity <= 0 || pageNumber < 0
        || usedBytes < 0 || usedBytes > target.capacity() - PAGE_HEADER_BYTES) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    target.order(ByteOrder.BIG_ENDIAN);
    target.putInt(0, PAGE_MAGIC);
    target.putInt(4, PAGE_FORMAT_VERSION);
    target.putLong(8, fileIdentity);
    target.putLong(16, pageNumber);
    target.putInt(24, usedBytes);
    target.putInt(28, checksum(target, PAGE_HEADER_BYTES, usedBytes));
    return StatusCode.OK;
  }

  public static StatusCode validatePageHeader(
      ByteBuffer source,
      long expectedFileIdentity,
      long expectedPageNumber,
      PageHeader target,
      StatusDetail detail) {
    if (source == null || target == null || source.capacity() < PAGE_HEADER_BYTES) {
      return fail(detail, StatusCode.INVALID_EXTERNAL_INPUT, "invalid materialized page buffer");
    }
    target.reset();
    source.order(ByteOrder.BIG_ENDIAN);
    int magic = source.getInt(0);
    int version = source.getInt(4);
    long identity = source.getLong(8);
    long pageNumber = source.getLong(16);
    int used = source.getInt(24);
    int actualCrc = source.getInt(28);
    if (magic != PAGE_MAGIC) {
      return fail(detail, StatusCode.CORRUPTION, "materialized page magic mismatch");
    }
    if (version != PAGE_FORMAT_VERSION) {
      return versionFailure(detail, "page", null, PAGE_FORMAT_VERSION, version);
    }
    if (identity != expectedFileIdentity) {
      return fail(detail, StatusCode.CORRUPTION, "materialized page identity mismatch");
    }
    if (pageNumber != expectedPageNumber) {
      return fail(detail, StatusCode.CORRUPTION, "materialized page number mismatch");
    }
    if (used < 0 || used > source.capacity() - PAGE_HEADER_BYTES) {
      return fail(detail, StatusCode.CORRUPTION, "materialized page used length invalid");
    }
    if (actualCrc != checksum(source, PAGE_HEADER_BYTES, used)) {
      return fail(detail, StatusCode.CORRUPTION, "materialized page checksum mismatch");
    }
    target.set(version, identity, pageNumber, used);
    return StatusCode.OK;
  }

  static StatusCode validateResidentPageHeader(
      ByteBuffer source,
      long expectedFileIdentity,
      long expectedPageNumber,
      PageHeader target,
      StatusDetail detail) {
    if (source == null || target == null || source.capacity() < PAGE_HEADER_BYTES) {
      return fail(detail, StatusCode.INVALID_EXTERNAL_INPUT, "invalid materialized page buffer");
    }
    target.reset();
    source.order(ByteOrder.BIG_ENDIAN);
    int used = source.getInt(24);
    if (source.getInt(0) != PAGE_MAGIC || source.getInt(4) != PAGE_FORMAT_VERSION
        || source.getLong(8) != expectedFileIdentity
        || source.getLong(16) != expectedPageNumber
        || used < 0 || used > source.capacity() - PAGE_HEADER_BYTES) {
      return fail(detail, StatusCode.CORRUPTION, "materialized resident page header invalid");
    }
    target.set(PAGE_FORMAT_VERSION, expectedFileIdentity, expectedPageNumber, used);
    return StatusCode.OK;
  }

  static StatusCode updateResidentPageHeader(
      ByteBuffer target, long fileIdentity, long pageNumber, int usedBytes) {
    if (target == null || target.capacity() < PAGE_HEADER_BYTES
        || fileIdentity <= 0 || pageNumber < 0
        || usedBytes < 0 || usedBytes > target.capacity() - PAGE_HEADER_BYTES) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    target.order(ByteOrder.BIG_ENDIAN);
    target.putInt(0, PAGE_MAGIC);
    target.putInt(4, PAGE_FORMAT_VERSION);
    target.putLong(8, fileIdentity);
    target.putLong(16, pageNumber);
    target.putInt(24, usedBytes);
    return StatusCode.OK;
  }

  public static long fileMagic(SqlMaterializedScratchFileKind kind) {
    return kind == null ? 0 : magic(kind);
  }

  private static long magic(SqlMaterializedScratchFileKind kind) {
    return FILE_MAGIC_BASE | fileCode(kind);
  }

  private static int fileCode(SqlMaterializedScratchFileKind kind) {
    return kind.ordinal() + 1;
  }

  private static int checksum(ByteBuffer buffer, int offset, int bytes) {
    int crc = ~0;
    for (int index = offset; index < offset + bytes; index++) {
      crc = CRC32C_TABLE[(crc ^ buffer.get(index)) & 0xff] ^ crc >>> 8;
    }
    return ~crc;
  }

  private static final int[] CRC32C_TABLE = crc32cTable();

  private static int[] crc32cTable() {
    int[] table = new int[256];
    for (int value = 0; value < table.length; value++) {
      int crc = value;
      for (int bit = 0; bit < 8; bit++) {
        crc = (crc & 1) == 0 ? crc >>> 1 : 0x82f63b78 ^ crc >>> 1;
      }
      table[value] = crc;
    }
    return table;
  }

  private static StatusCode versionFailure(
      StatusDetail detail, String kind, String fileKind, int expected, int actual) {
    if (detail != null) {
      detail.set(StatusCode.CORRUPTION).append("materialized ").append(kind)
          .append(" version");
      if (fileKind != null) detail.append(" kind ").append(fileKind);
      detail.append(" expected ").append(expected).append(" actual ").append(actual);
    }
    return StatusCode.CORRUPTION;
  }

  private static StatusCode fail(StatusDetail detail, StatusCode code, String message) {
    if (detail != null) detail.set(code).append(message);
    return code;
  }

  /** Caller-owned decoded file-header fields. */
  public static final class Header {
    private int version;
    private int fileCode;
    private int pageBytes;
    private int fixedRecordBytes;
    private int flags;
    private long fileIdentity;
    private long publishedCount;
    private long logicalLength;

    public void reset() {
      version = 0;
      fileCode = 0;
      pageBytes = 0;
      fixedRecordBytes = 0;
      flags = 0;
      fileIdentity = 0;
      publishedCount = 0;
      logicalLength = 0;
    }

    private void set(
        int newVersion, int newFileCode, int newPageBytes, int newFixedRecordBytes,
        int newFlags, long newIdentity, long newCount, long newLength) {
      version = newVersion;
      fileCode = newFileCode;
      pageBytes = newPageBytes;
      fixedRecordBytes = newFixedRecordBytes;
      flags = newFlags;
      fileIdentity = newIdentity;
      publishedCount = newCount;
      logicalLength = newLength;
    }

    public int version() { return version; }
    public int fileCode() { return fileCode; }
    public int pageBytes() { return pageBytes; }
    public int fixedRecordBytes() { return fixedRecordBytes; }
    public int flags() { return flags; }
    public long fileIdentity() { return fileIdentity; }
    public long publishedCount() { return publishedCount; }
    public long logicalLength() { return logicalLength; }
  }

  /** Caller-owned decoded page-header fields. */
  public static final class PageHeader {
    private int version;
    private long fileIdentity;
    private long pageNumber;
    private int usedBytes;

    public void reset() {
      version = 0;
      fileIdentity = 0;
      pageNumber = 0;
      usedBytes = 0;
    }

    private void set(int newVersion, long newIdentity, long newPageNumber, int newUsedBytes) {
      version = newVersion;
      fileIdentity = newIdentity;
      pageNumber = newPageNumber;
      usedBytes = newUsedBytes;
    }

    public int version() { return version; }
    public long fileIdentity() { return fileIdentity; }
    public long pageNumber() { return pageNumber; }
    public int usedBytes() { return usedBytes; }
  }
}
