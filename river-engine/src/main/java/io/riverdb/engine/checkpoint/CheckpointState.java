package io.riverdb.engine.checkpoint;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.id.DatabaseIncarnation;
import io.riverdb.base.id.WalGeneration;

/** Caller-owned checkpoint authority with the MVCC metadata needed by its stable page base. */
public final class CheckpointState {
  public static final int MAXIMUM_ROWS = 64 * 1024;
  /** Positive logical row ids are encoded in an unsigned 32-bit domain. */
  public static final long MAXIMUM_RUNTIME_ROWS = 0xFFFF_FFFEL;
  private static final int PAGE_SHIFT = 12;
  private static final int PAGE_SIZE = 1 << PAGE_SHIFT;
  private static final int PAGE_MASK = PAGE_SIZE - 1;

  private final PagedLongs deletedWords = new PagedLongs(MAXIMUM_RUNTIME_ROWS / Long.SIZE + 1);
  private final PagedLongs rowCommitSequences = new PagedLongs(MAXIMUM_RUNTIME_ROWS);
  private final PagedLongs previousRowIds = new PagedLongs(MAXIMUM_RUNTIME_ROWS);
  private DatabaseIncarnation database;
  private WalGeneration walGeneration;
  private long checkpointId;
  private long commitSequence;
  private long maximumTransactionId;
  private long defaultRowCommitSequence;
  private long obsoleteVersionCount;
  private int pageCount;
  private long rowCount;
  private boolean available;

  public void reset() {
    database = null;
    walGeneration = null;
    checkpointId = 0;
    commitSequence = 0;
    maximumTransactionId = 0;
    defaultRowCommitSequence = 0;
    obsoleteVersionCount = 0;
    pageCount = 0;
    rowCount = 0;
    available = false;
    rowCommitSequences.clear();
    previousRowIds.clear();
    deletedWords.clear();
  }

  public StatusCode set(
      DatabaseIncarnation incarnation,
      WalGeneration generation,
      long id,
      long committedAt,
      long maximumTx,
      int pages,
      long rows) {
    if (incarnation == null
        || !incarnation.isValid()
        || generation == null
        || !generation.isValid()
        || id <= 0
        || committedAt <= 0
        || maximumTx <= 0
        || pages <= 0
        || rows < 0
        || rows > MAXIMUM_ROWS) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    return setInternal(
        incarnation, generation, id, committedAt, maximumTx, pages, rows, false);
  }

  /** Installs the scalable runtime checkpoint shape; the legacy public setter remains bounded. */
  public StatusCode setLarge(
      DatabaseIncarnation incarnation,
      WalGeneration generation,
      long id,
      long committedAt,
      long maximumTx,
      int pages,
      long rows) {
    if (rows < 0 || rows > MAXIMUM_RUNTIME_ROWS) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    return setInternal(
        incarnation, generation, id, committedAt, maximumTx, pages, rows, true);
  }

  private StatusCode setInternal(
      DatabaseIncarnation incarnation,
      WalGeneration generation,
      long id,
      long committedAt,
      long maximumTx,
      int pages,
      long rows,
      boolean large) {
    if (incarnation == null
        || !incarnation.isValid()
        || generation == null
        || !generation.isValid()
        || id <= 0
        || committedAt <= 0
        || maximumTx <= 0
        || pages <= 0
        || rows < 0
        || rows > MAXIMUM_RUNTIME_ROWS
        || !large && rows > MAXIMUM_ROWS) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    reset();
    database = incarnation;
    walGeneration = generation;
    checkpointId = id;
    commitSequence = committedAt;
    maximumTransactionId = maximumTx;
    defaultRowCommitSequence = committedAt;
    pageCount = pages;
    rowCount = rows;
    available = true;
    return StatusCode.OK;
  }

  public StatusCode setRowVersion(
      long rowId,
      long committedAt,
      long previousRowId,
      boolean deleted) {
    if (!available
        || rowId <= 0
        || rowId > rowCount
        || committedAt <= 0
        || committedAt > commitSequence
        || previousRowId < 0
        || previousRowId >= rowId) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    rowCommitSequences.set(rowId, committedAt);
    previousRowIds.set(rowId, previousRowId);
    if (previousRowId > 0) obsoleteVersionCount++;
    long bit = rowId - 1;
    long mask = 1L << (bit & 63);
    if (deleted) {
      deletedWords.set(bit >>> 6, deletedWords.get(bit >>> 6) | mask);
    } else {
      deletedWords.set(bit >>> 6, deletedWords.get(bit >>> 6) & ~mask);
    }
    return StatusCode.OK;
  }

  public StatusCode setDeleted(long rowId) {
    if (rowId <= 0 || rowId > rowCount) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    long bit = rowId - 1;
    deletedWords.set(
        bit >>> 6, deletedWords.get(bit >>> 6) | 1L << (bit & 63));
    return StatusCode.OK;
  }

  public boolean isDeleted(long rowId) {
    if (rowId <= 0 || rowId > rowCount) {
      return false;
    }
    long bit = rowId - 1;
    return (deletedWords.get(bit >>> 6) & 1L << (bit & 63)) != 0;
  }

  public long rowCommitSequence(long rowId) {
    if (rowId <= 0 || rowId > rowCount) return 0;
    long value = rowCommitSequences.get(rowId);
    return value == 0 ? defaultRowCommitSequence : value;
  }

  public long previousRowId(long rowId) {
    return rowId > 0 && rowId <= rowCount ? previousRowIds.get(rowId) : 0;
  }

  public DatabaseIncarnation database() {
    return database;
  }

  public WalGeneration walGeneration() {
    return walGeneration;
  }

  public long checkpointId() {
    return checkpointId;
  }

  public long commitSequence() {
    return commitSequence;
  }

  public long maximumTransactionId() {
    return maximumTransactionId;
  }

  public int pageCount() {
    return pageCount;
  }

  public long rowCount() {
    return rowCount;
  }

  public boolean isAvailable() {
    return available;
  }

  public long obsoleteVersionCount() {
    return obsoleteVersionCount;
  }

  private static final class PagedLongs {
    private final long[][] pages;

    PagedLongs(long maximumElements) {
      long pageCount = (maximumElements + PAGE_SIZE - 1) >>> PAGE_SHIFT;
      if (pageCount > Integer.MAX_VALUE) {
        throw new IllegalArgumentException("logical row address space is too large");
      }
      pages = new long[(int) pageCount][];
    }

    long get(long index) {
      if (index < 0 || (index >>> PAGE_SHIFT) >= pages.length) return 0;
      int page = (int) (index >>> PAGE_SHIFT);
      return pages[page] != null ? pages[page][(int) index & PAGE_MASK] : 0;
    }

    void set(long index, long value) {
      if (index < 0 || (index >>> PAGE_SHIFT) >= pages.length) return;
      int page = (int) (index >>> PAGE_SHIFT);
      long[] values = pages[page];
      if (values == null) values = pages[page] = new long[PAGE_SIZE];
      values[(int) index & PAGE_MASK] = value;
    }

    void clear() {
      for (long[] page : pages) {
        if (page != null) java.util.Arrays.fill(page, 0);
      }
    }
  }

}
