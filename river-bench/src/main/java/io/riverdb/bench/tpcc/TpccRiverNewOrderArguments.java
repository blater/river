package io.riverdb.bench.tpcc;

import io.riverdb.base.type.LocalTemporal;
import io.riverdb.base.type.SqlTypeDescriptor;
import io.riverdb.engine.api.TransactionProgramArguments;
import java.sql.SQLException;
import java.time.LocalDateTime;

/** Populates one reusable dense primitive argument arena for New-Order. */
final class TpccRiverNewOrderArguments {
  private final TransactionProgramArguments values = new TransactionProgramArguments();
  private final int[] executionOrder = new int[TpccRiverNewOrderLayout.MAXIMUM_LINES];
  private final int invalidItem;

  TpccRiverNewOrderArguments(int maximumItem) {
    if (maximumItem <= 0 || maximumItem == Integer.MAX_VALUE) {
      throw new IllegalArgumentException("invalid maximum item");
    }
    invalidItem = maximumItem + 1;
  }

  TransactionProgramArguments bind(TpccInputs.NewOrder input) throws SQLException {
    if (input == null || !TpccRiverNewOrderLayout.validLines(input.lines)) {
      throw new SQLException("invalid TPC-C New-Order input", "22003");
    }
    values.reset();
    integer(TpccRiverNewOrderLayout.WAREHOUSE, input.warehouse);
    integer(TpccRiverNewOrderLayout.DISTRICT, input.district);
    integer(TpccRiverNewOrderLayout.CUSTOMER, input.customer);
    timestamp(TpccRiverNewOrderLayout.ENTRY, input.entry.toLocalDateTime());
    integer(TpccRiverNewOrderLayout.LINE_COUNT, input.lines);
    integer(TpccRiverNewOrderLayout.ALL_LOCAL, allLocal(input) ? 1 : 0);
    order(input);
    for (int executionLine = 0; executionLine < input.lines; executionLine++) {
      int inputLine = executionOrder[executionLine];
      integer(TpccRiverNewOrderLayout.item(executionLine), input.item[inputLine]);
      integer(TpccRiverNewOrderLayout.quantity(executionLine), input.quantity[inputLine]);
      integer(
          TpccRiverNewOrderLayout.supplyWarehouse(executionLine),
          input.supplyWarehouse[inputLine]);
      integer(TpccRiverNewOrderLayout.lineNumber(executionLine), inputLine + 1);
    }
    integer(TpccRiverNewOrderLayout.ten(input.lines), 10);
    integer(TpccRiverNewOrderLayout.ninetyOne(input.lines), 91);
    integer(TpccRiverNewOrderLayout.one(input.lines), 1);
    integer(TpccRiverNewOrderLayout.zero(input.lines), 0);
    return values;
  }

  private void order(TpccInputs.NewOrder input) {
    for (int line = 0; line < input.lines; line++) {
      int insertion = line;
      while (insertion > 0
          && compare(input, line, executionOrder[insertion - 1]) < 0) {
        executionOrder[insertion] = executionOrder[insertion - 1];
        insertion--;
      }
      executionOrder[insertion] = line;
    }
  }

  private int compare(TpccInputs.NewOrder input, int left, int right) {
    boolean leftInvalid = input.item[left] == invalidItem;
    boolean rightInvalid = input.item[right] == invalidItem;
    if (leftInvalid != rightInvalid) return leftInvalid ? 1 : -1;
    int warehouse = Integer.compare(
        input.supplyWarehouse[left], input.supplyWarehouse[right]);
    if (warehouse != 0) return warehouse;
    int item = Integer.compare(input.item[left], input.item[right]);
    return item != 0 ? item : Integer.compare(left, right);
  }

  void release() throws SQLException {
    TpccRiverStatus.require(values.release(), "release New-Order arguments");
  }

  private void integer(int slot, int value) throws SQLException {
    TpccRiverStatus.require(
        values.setFixed(slot, SqlTypeDescriptor.INTEGER, value), "bind New-Order argument");
  }

  private void timestamp(int slot, LocalDateTime value) throws SQLException {
    long micros = Math.addExact(
        Math.multiplyExact(value.toLocalDate().toEpochDay(), LocalTemporal.MICROSECONDS_PER_DAY),
        value.toLocalTime().toNanoOfDay() / 1_000L);
    TpccRiverStatus.require(values.setFixed(
        slot, SqlTypeDescriptor.timestamp(6), micros), "bind New-Order timestamp");
  }

  private static boolean allLocal(TpccInputs.NewOrder input) {
    for (int line = 0; line < input.lines; line++) {
      if (input.supplyWarehouse[line] != input.warehouse) return false;
    }
    return true;
  }
}
