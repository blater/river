package io.riverdb.engine.sql;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.type.SqlTypeDescriptor;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class SqlDistinctValueStoreTest {
  @Test
  void spillsMultipleSortedRunsAndMergesThemExactly(@TempDir Path root) {
    SqlMaterializedTestFixture fixture = SqlMaterializedTestFixture.open(root);
    SqlDistinctValueStore values = new SqlDistinctValueStore(
        fixture.budget());
    SqlBlockRow row = new SqlBlockRow();
    assertEquals(StatusCode.OK, values.begin(SqlTypeDescriptor.BIGINT));
    assertEquals(StatusCode.OK, row.reset(1));
    for (int value = 0; value < 5_001; value++) {
      row.setValue(0, value % 4_321);
      assertEquals(StatusCode.OK, values.add(row, 0));
    }
    long[] count = new long[1];
    assertEquals(StatusCode.OK, values.finish(count));
    assertEquals(4_321, count[0]);
    assertEquals(StatusCode.OK, values.close());
    fixture.close();
  }

  @Test
  void distinguishesTextValues(@TempDir Path root) {
    SqlMaterializedTestFixture fixture = SqlMaterializedTestFixture.open(root);
    SqlDistinctValueStore values = new SqlDistinctValueStore(
        fixture.budget());
    SqlBlockRow row = new SqlBlockRow();
    assertEquals(StatusCode.OK, values.begin(SqlTypeDescriptor.BIGINT));
    assertEquals(StatusCode.OK, row.reset(1));
    row.setValue(0, 7);
    assertEquals(StatusCode.OK, values.add(row, 0));
    long[] count = new long[1];
    assertEquals(StatusCode.OK, values.finish(count));
    assertEquals(StatusCode.OK, values.close());

    assertEquals(StatusCode.OK, values.begin(SqlTypeDescriptor.varchar(16)));
    assertEquals(StatusCode.OK, values.reset());
    assertEquals(StatusCode.OK, row.reset(1));
    assertEquals(StatusCode.OK, row.setText(0, "zeta".toCharArray(), 0, 4));
    assertEquals(StatusCode.OK, values.add(row, 0));
    assertEquals(StatusCode.OK, row.reset(1));
    assertEquals(StatusCode.OK, row.setText(0, "alpha".toCharArray(), 0, 5));
    assertEquals(StatusCode.OK, values.add(row, 0));
    assertEquals(StatusCode.OK, values.finish(count));
    assertEquals(2, count[0]);
    assertEquals(StatusCode.OK, values.close());
    fixture.close();
  }

  @Test
  void preservesWideDecimalLanesAndApproximateZeroSemantics(@TempDir Path root) {
    SqlMaterializedTestFixture fixture = SqlMaterializedTestFixture.open(root);
    SqlDistinctValueStore source = new SqlDistinctValueStore(fixture.budget());
    SqlDistinctValueStore copied = new SqlDistinctValueStore(fixture.budget());
    SqlBlockRow row = new SqlBlockRow();
    long[] count = new long[1];
    int decimal = SqlTypeDescriptor.decimal(38, 18);

    assertEquals(StatusCode.OK, source.begin(decimal));
    assertEquals(StatusCode.OK, copied.begin(decimal));
    addDecimal(source, row, 0, 1);
    addDecimal(source, row, 1, 1);
    addDecimal(source, row, 0, 1);
    assertEquals(StatusCode.OK, source.finish(count));
    assertEquals(2, count[0]);
    assertEquals(StatusCode.OK, copied.copyFrom(source));
    assertEquals(StatusCode.OK, copied.finish(count));
    assertEquals(2, count[0]);

    assertEquals(StatusCode.OK, source.begin(SqlTypeDescriptor.DOUBLE));
    addDecimal(source, row, 0, Double.doubleToRawLongBits(0.0d));
    addDecimal(source, row, 0, Double.doubleToRawLongBits(-0.0d));
    addDecimal(source, row, 0, Double.doubleToRawLongBits(1.5d));
    addDecimal(source, row, 0, Double.doubleToRawLongBits(1.5d));
    assertEquals(StatusCode.OK, source.finish(count));
    assertEquals(2, count[0]);

    assertEquals(StatusCode.OK, copied.close());
    assertEquals(StatusCode.OK, source.close());
    fixture.close();
  }

  private static void addDecimal(
      SqlDistinctValueStore values, SqlBlockRow row, long high, long low) {
    assertEquals(StatusCode.OK, row.reset(1));
    row.setDecimal128(0, high, low);
    assertEquals(StatusCode.OK, values.add(row, 0));
  }
}
