package io.riverdb.engine.sql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.text.BoundedByteSource;
import io.riverdb.base.type.SqlTypeDescriptor;
import io.riverdb.engine.relational.SqlValueAccess;
import io.riverdb.engine.relational.StoredTableColumnSelection;
import io.riverdb.engine.schema.ColumnDescriptorSet;
import io.riverdb.engine.schema.TableDescriptor;
import io.riverdb.sql.SqlCommand;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

final class SqlBorrowedBlockRowTest {
  @Test
  void borrowedRowReadsNoTextBytesAndRetainedCopyReadsEachByteOnce() {
    NoDecodeText source = new NoDecodeText("多🙂");
    SqlBlockRow borrowed = new SqlBlockRow();
    assertEquals(StatusCode.OK, borrowed.borrow(source));
    assertEquals(0, source.readCount);
    SqlBlockRow retained = new SqlBlockRow();
    assertEquals(StatusCode.OK, retained.copyFrom(borrowed));
    assertEquals(source.length(), source.readCount);
    assertTrue(retained.hasUtf8(0));
  }

  @Test
  void wideBorrowReadsOnlyTheNumericFieldUsedByTheConsumer() {
    final int[] reads = {0};
    SqlValueAccess source = new SqlValueAccess() {
      @Override public int count() { return 672; }
      @Override public int descriptorAt(int column) { return SqlTypeDescriptor.BIGINT; }
      @Override public boolean isNull(int column) { return false; }
      @Override public long valueAt(int column) { reads[0]++; return 42; }
      @Override public long highValueAt(int column) { return 0; }
      @Override public int textByteLengthAt(int column) { return -1; }
      @Override public int textByteOffsetAt(int column) { return -1; }
      @Override public BoundedByteSource textSource(int column) { return null; }
      @Override public int copyTextChars(int column, char[] target, int offset) { return -1; }
    };
    SqlBlockRow row = new SqlBlockRow();
    assertEquals(StatusCode.OK, row.borrow(source));
    assertEquals(0, reads[0]);
    assertEquals(42, row.value(671));
    assertEquals(1, reads[0]);
    assertEquals(StatusCode.OK, row.reset(0));
    assertEquals(1, reads[0]);
  }

  @Test
  void indexBoundBorrowsAdmittedUtf8AndChecksNarrowerWidth() {
    NoDecodeText source = new NoDecodeText("多🙂");
    SqlBlockRow row = new SqlBlockRow();
    assertEquals(StatusCode.OK, row.borrow(source));
    int wide = SqlTypeDescriptor.varchar(8);
    SqlDescriptorPrimaryValues values = new SqlDescriptorPrimaryValues();
    assertEquals(StatusCode.OK, values.begin(textTable(wide), 0, new SqlCommand()));
    assertEquals(StatusCode.OK, values.borrowAdmittedText(0, wide, wide, row, 0));
    assertEquals(0, source.readCount);
    assertEquals(7, values.textByteLengthAt(0));
    assertEquals(source.getByte(0), values.textSource(0).getByte(0));

    int narrow = SqlTypeDescriptor.varchar(2);
    assertEquals(StatusCode.OK, values.begin(textTable(narrow), 0, new SqlCommand()));
    assertEquals(StatusCode.OK, values.borrowAdmittedText(0, wide, narrow, row, 0));
    assertEquals(narrow, values.descriptorAt(0));

    int tooNarrow = SqlTypeDescriptor.varchar(1);
    assertEquals(StatusCode.OK, values.begin(textTable(tooNarrow), 0, new SqlCommand()));
    assertEquals(StatusCode.RESOURCE_EXHAUSTED,
        values.borrowAdmittedText(0, wide, tooNarrow, row, 0));
    assertEquals(0, values.descriptorAt(0));
  }

  @Test
  void joinRowEncodesAndComparesAdmittedUtf8WithoutCharacterCopy() {
    NoDecodeText first = new NoDecodeText("多🌊");
    NoDecodeText second = new NoDecodeText("多🙂");
    StoredTableColumnSelection columns = new StoredTableColumnSelection();
    assertEquals(StatusCode.OK, columns.selectNone(1));
    columns.selectAll();
    SqlBlockRow left = new SqlBlockRow();
    SqlBlockRow right = new SqlBlockRow();
    assertEquals(StatusCode.OK, left.reset(1));
    assertEquals(StatusCode.OK, right.reset(1));
    assertEquals(StatusCode.OK, left.borrow(first, columns));
    assertEquals(StatusCode.OK, right.borrow(second, columns));
    assertEquals(3, left.textLength(0));
    assertTrue(new SqlBlockRowValueComparator().compare(
        left, 0, first.descriptorAt(0), right, 0, second.descriptorAt(0)) < 0);
    SqlBlockRow computed = new SqlBlockRow();
    assertEquals(StatusCode.OK, computed.reset(1));
    char[] highBmp = {(char) 0xe000};
    assertEquals(StatusCode.OK, computed.setText(0, highBmp, 0, highBmp.length));
    NoDecodeText astral = new NoDecodeText("🌊");
    SqlBlockRow astralRow = new SqlBlockRow();
    assertEquals(StatusCode.OK, astralRow.reset(1));
    assertEquals(StatusCode.OK, astralRow.borrow(astral, columns));
    assertTrue(new SqlBlockRowValueComparator().compare(
        astralRow, 0, astral.descriptorAt(0),
        computed, 0, astral.descriptorAt(0)) > 0);
    assertTrue(new SqlJoinHashKey().decoded(left, 0, first.descriptorAt(0))
        != new SqlJoinHashKey().decoded(right, 0, second.descriptorAt(0)));

    SqlBlockSchema schema = new SqlBlockSchema();
    schema.set(1);
    schema.setColumn(0, "value", first.descriptorAt(0), false);
    SqlBlockRowRecordCodec record = new SqlBlockRowRecordCodec();
    assertEquals(StatusCode.OK, record.encode(left, schema, 0));
    SqlBlockRowSortKeyCodec sortKey = new SqlBlockRowSortKeyCodec(null);
    assertTrue(sortKey.beginSingle(schema, 0, false));
    assertEquals(StatusCode.OK, sortKey.encode(left));
    assertEquals(1 + Integer.BYTES + first.length(), sortKey.bytes().remaining());
    SqlJoinSortRow joinSort = new SqlJoinSortRow();
    assertEquals(StatusCode.OK, joinSort.prepare());
    assertEquals(StatusCode.OK, joinSort.encode(
        left, new int[] {first.descriptorAt(0)}, 1));
    long textHandle = joinSort.row().getLong(0);
    assertEquals(first.length(), (int) textHandle);
    assertEquals(first.getByte(0), joinSort.row().getByte((int) (textHandle >>> 32)));

    SqlBlockRow retained = new SqlBlockRow();
    assertEquals(StatusCode.OK, retained.copyFrom(left));
    assertTrue(retained.hasUtf8(0));
    FailingBytes allocator = new FailingBytes();
    SqlBlockRow pressured = new SqlBlockRow(allocator);
    allocator.fail = true;
    assertEquals(StatusCode.RESOURCE_EXHAUSTED, pressured.copyFrom(left));
    allocator.fail = false;
    assertEquals(StatusCode.OK, pressured.copyFrom(left));
    assertTrue(pressured.hasUtf8(0));
    assertEquals(StatusCode.OK, left.borrow(second, columns));
    assertEquals(StatusCode.OK, record.encode(retained, schema, 1));
    SqlScanRowResult result = new SqlScanRowResult();
    assertEquals(StatusCode.OK, result.beginProjected(
        0, new int[] {first.descriptorAt(0)}, 1));
    assertEquals(StatusCode.OK, result.setUtf8At(
        0, retained.utf8Slice(0), 0, retained.utf8Length(0)));
    char[] published = new char[8];
    assertEquals("多🌊", new String(published, 0, result.copyTextAt(0, published, 0)));

    SqlBlockRow decoded = new SqlBlockRow();
    assertEquals(StatusCode.OK, record.decode(decoded, schema, 1));
    assertEquals(3, decoded.textLength(0));
    assertEquals('多', decoded.textCharacter(0, 0));
    assertEquals(0x1f30a, Character.toCodePoint(
        decoded.textCharacter(0, 1), decoded.textCharacter(0, 2)));
  }

  private static final class NoDecodeText implements SqlValueAccess, BoundedByteSource {
    private final byte[] bytes;
    private int readCount;

    private NoDecodeText(String text) { bytes = text.getBytes(StandardCharsets.UTF_8); }

    @Override public int count() { return 1; }
    @Override public int descriptorAt(int column) { return SqlTypeDescriptor.varchar(8); }
    @Override public boolean isNull(int column) { return false; }
    @Override public long valueAt(int column) { return 0; }
    @Override public long highValueAt(int column) { return 0; }
    @Override public int textByteLengthAt(int column) { return bytes.length; }
    @Override public int textByteOffsetAt(int column) { return 0; }
    @Override public BoundedByteSource textSource(int column) { return this; }
    @Override public int copyTextChars(int column, char[] target, int offset) {
      throw new AssertionError("borrowed text was decoded");
    }
    @Override public int length() { return bytes.length; }
    @Override public byte getByte(int offset) {
      readCount++;
      return bytes[offset];
    }
  }

  private static TableDescriptor textTable(int descriptor) {
    ColumnDescriptorSet.Result columns = new ColumnDescriptorSet.Result();
    assertEquals(StatusCode.OK, ColumnDescriptorSet.create(
        new int[] {descriptor}, new CharSequence[] {"value"},
        new boolean[] {false}, columns));
    TableDescriptor.Result result = new TableDescriptor.Result();
    assertEquals(StatusCode.OK, TableDescriptor.createProposedSuccessor(
        1, 1, 1, columns.value(), null, null, null, result, null));
    return result.value();
  }

  private static final class FailingBytes extends SqlRetainedArrayAllocator {
    private boolean fail;

    @Override byte[] bytes(int capacity) {
      if (fail) throw new OutOfMemoryError("injected");
      return super.bytes(capacity);
    }
  }
}
