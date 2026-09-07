package io.riverdb.bench.tpcc;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class TpccLoaderShapeTest {
  @Test
  void admitsEveryStandardEntityWidthAndRejectsPlaceholderDrift() {
    String[] tables = {"warehouse", "district", "item", "stock", "customer",
        "history", "orders", "new_order", "order_line"};
    int[] columns = {9, 11, 5, 17, 21, 8, 8, 3, 10};
    for (int index = 0; index < tables.length; index++) {
      String table = tables[index];
      String sql = "INSERT INTO " + table + " VALUES ("
          + "?,".repeat(columns[index] - 1) + "?)";
      assertDoesNotThrow(() -> TpccLoaderShape.validate(table, sql));
    }
    assertThrows(IllegalArgumentException.class,
        () -> TpccLoaderShape.validate("customer", "INSERT INTO customer VALUES (?)"));
  }

  @Test
  void initialLineCountsStayWithinDeclaredBounds() {
    TpccConfig config = TpccConfig.parse(new String[] {
        "--url=jdbc:river://localhost:9", "--tiny", "--artifact=shape.properties"
    });
    TpccLoader loader = new TpccLoader(config);
    for (int district = 1; district <= 10; district++) {
      for (int order = 1; order <= 30; order++) {
        int lines = loader.initialLineCount(1, district, order);
        assertTrue(lines >= 5 && lines <= 15);
        assertTrue(loader.initialLineCount(2, district, order) >= 5);
        assertTrue(loader.initialLineCount(2, district, order) <= 15);
      }
    }
  }
}
