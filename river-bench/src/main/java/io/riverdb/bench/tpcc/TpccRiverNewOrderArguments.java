package io.riverdb.bench.tpcc;

import io.riverdb.base.type.LocalTemporal;
import io.riverdb.base.type.SqlTypeDescriptor;
import io.riverdb.engine.api.TransactionProgramArguments;
import java.sql.SQLException;
import java.time.LocalDateTime;

/** Populates one reusable dense primitive argument arena for New-Order. */
final class TpccRiverNewOrderArguments {
  private final TransactionProgramArguments values = new TransactionProgramArguments();

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
    for (int line = 0; line < input.lines; line++) {
      integer(TpccRiverNewOrderLayout.item(line), input.item[line]);
      integer(TpccRiverNewOrderLayout.quantity(line), input.quantity[line]);
      integer(TpccRiverNewOrderLayout.supplyWarehouse(line), input.supplyWarehouse[line]);
      integer(TpccRiverNewOrderLayout.lineNumber(line), line + 1);
    }
    integer(TpccRiverNewOrderLayout.ten(input.lines), 10);
    integer(TpccRiverNewOrderLayout.ninetyOne(input.lines), 91);
    integer(TpccRiverNewOrderLayout.one(input.lines), 1);
    integer(TpccRiverNewOrderLayout.zero(input.lines), 0);
    return values;
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
