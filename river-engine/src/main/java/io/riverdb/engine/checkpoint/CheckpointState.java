package io.riverdb.engine.checkpoint;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.id.DatabaseIncarnation;
import io.riverdb.base.id.WalGeneration;

/** Caller-owned checkpoint authority with the MVCC metadata needed by its stable page base. */
public final class CheckpointState {
  public static final int MAXIMUM_ROWS = 64 * 1024;
  public static final int MAXIMUM_RUNTIME_ROWS = Integer.MAX_VALUE - 1;
  private static final int PAGE_SHIFT = 12;
  private static final int PAGE_SIZE = 1 << PAGE_SHIFT;
  private static final int PAGE_MASK = PAGE_SIZE - 1;

  private final PagedLongs deletedWords = new PagedLongs(MAXIMUM_RUNTIME_ROWS / Long.SIZE + 1);
  private final PagedLongs rowCommitSequences = new PagedLongs(MAXIMUM_RUNTIME_ROWS);
  private final PagedInts previousRowIds = new PagedInts(MAXIMUM_RUNTIME_ROWS);
  private DatabaseIncarnation database;
  private WalGeneration walGeneration;
  private long checkpointId;
  private long commitSequence;
  private long maximumTransactionId;
  private int pageCount;
  private int rowCount;
  private boolean available;

  public void reset() {
    int previousRows = rowCount;
    database = null;
    walGeneration = null;
    checkpointId = 0;
    commitSequence = 0;
    maximumTransactionId = 0;
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
      int rows) {
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
      int rows) {
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
      int rows,
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
    pageCount = pages;
    rowCount = rows;
    available = true;
    for (int rowId = 1; rowId <= rows; rowId++) {
      rowCommitSequences.set(rowId, committedAt);
      previousRowIds.set(rowId, 0);
    }
    return StatusCode.OK;
  }

  public StatusCode setRowVersion(
      int rowId,
      long committedAt,
      int previousRowId,
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
    int bit = rowId - 1;
    long mask = 1L << (bit & 63);
    if (deleted) {
      deletedWords.set(bit >>> 6, deletedWords.get(bit >>> 6) | mask);
    } else {
      deletedWords.set(bit >>> 6, deletedWords.get(bit >>> 6) & ~mask);
    }
    return StatusCode.OK;
  }

  public StatusCode setDeleted(int rowId) {
    if (rowId <= 0 || rowId > rowCount) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    int bit = rowId - 1;
    deletedWords.set(
        bit >>> 6, deletedWords.get(bit >>> 6) | 1L << (bit & 63));
    return StatusCode.OK;
  }

  public boolean isDeleted(int rowId) {
    if (rowId <= 0 || rowId > rowCount) {
      return false;
    }
    int bit = rowId - 1;
    return (deletedWords.get(bit >>> 6) & 1L << (bit & 63)) != 0;
  }

  public long rowCommitSequence(int rowId) {
    return rowId > 0 && rowId <= rowCount ? rowCommitSequences.get(rowId) : 0;
  }

  public int previousRowId(int rowId) {
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

  public int rowCount() {
    return rowCount;
  }

  public boolean isAvailable() {
    return available;
  }

  private static final class PagedLongs {
    private final long[][] pages;

    PagedLongs(int maximumElements) {
      pages = new long[(int) (((long) maximumElements + PAGE_SIZE) >>> PAGE_SHIFT)][];
    }

    long get(int index) {
      int page = index >>> PAGE_SHIFT;
      return page < pages.length && pages[page] != null
          ? pages[page][index & PAGE_MASK] : 0;
    }

    void set(int index, long value) {
      int page = index >>> PAGE_SHIFT;
      if (page >= pages.length) return;
      long[] values = pages[page];
      if (values == null) values = pages[page] = new long[PAGE_SIZE];
      values[index & PAGE_MASK] = value;
    }

    void clear() {
      for (long[] page : pages) {
        if (page != null) java.util.Arrays.fill(page, 0);
      }
    }
  }

  private static final class PagedInts {
    private final int[][] pages;

    PagedInts(int maximumElements) {
      pages = new int[(int) (((long) maximumElements + PAGE_SIZE) >>> PAGE_SHIFT)][];
    }

    int get(int index) {
      int page = index >>> PAGE_SHIFT;
      return page < pages.length && pages[page] != null
          ? pages[page][index & PAGE_MASK] : 0;
    }

    void set(int index, int value) {
      int page = index >>> PAGE_SHIFT;
      if (page >= pages.length) return;
      int[] values = pages[page];
      if (values == null) values = pages[page] = new int[PAGE_SIZE];
      values[index & PAGE_MASK] = value;
    }

    void clear() {
      for (int[] page : pages) {
        if (page != null) java.util.Arrays.fill(page, 0);
      }
    }
  }
}
