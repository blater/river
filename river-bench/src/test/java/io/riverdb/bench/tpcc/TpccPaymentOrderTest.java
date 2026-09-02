package io.riverdb.bench.tpcc;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Payment acquires contended rows in the standard warehouse, district, customer order. */
final class TpccPaymentOrderTest {
  @Test
  void executesWarehouseThenDistrictThenCustomer() throws Exception {
    JdbcRecorder jdbc = new JdbcRecorder();
    TpccInputs.Payment input = new TpccInputs.Payment();
    input.warehouse = 1;
    input.district = 2;
    input.customerWarehouse = 1;
    input.customerDistrict = 2;
    input.customer = 3;
    input.amount = new BigDecimal("12.34");
    input.date = new Timestamp(1);

    try (TpccPayment payment = new TpccPayment(jdbc.connection())) {
      payment.execute(input);
    }

    assertEquals(List.of(
        "UPDATE warehouse", "SELECT warehouse",
        "UPDATE district", "SELECT district",
        "SELECT customer", "UPDATE customer",
        "INSERT history", "COMMIT"), jdbc.executed);
  }

  private static final class JdbcRecorder {
    final List<String> executed = new ArrayList<>();

    Connection connection() {
      return proxy(Connection.class, (ignored, method, arguments) -> switch (method.getName()) {
        case "prepareStatement" -> statement((String) arguments[0]);
        case "commit" -> record("COMMIT");
        case "rollback", "close" -> null;
        default -> defaultValue(method);
      });
    }

    private PreparedStatement statement(String sql) {
      return proxy(PreparedStatement.class, (ignored, method, arguments) ->
          switch (method.getName()) {
            case "executeUpdate" -> {
              executed.add(label(sql));
              yield 1;
            }
            case "executeQuery" -> {
              executed.add(label(sql));
              yield rows(sql);
            }
            case "close", "setInt", "setString", "setBigDecimal", "setTimestamp" -> null;
            default -> defaultValue(method);
          });
    }

    private ResultSet rows(String sql) {
      return proxy(ResultSet.class, new InvocationHandler() {
        private boolean available = true;

        @Override
        public Object invoke(Object ignored, Method method, Object[] arguments) {
          return switch (method.getName()) {
            case "next" -> {
              boolean result = available;
              available = false;
              yield result;
            }
            case "getString" -> sql.contains("c_credit")
                ? (Integer) arguments[0] == 1 ? "GC" : "" : "NAME";
            case "close" -> null;
            default -> defaultValue(method);
          };
        }
      });
    }

    private Object record(String value) {
      executed.add(value);
      return null;
    }

    private static String label(String sql) {
      if (sql.startsWith("UPDATE warehouse")) return "UPDATE warehouse";
      if (sql.startsWith("SELECT w_name")) return "SELECT warehouse";
      if (sql.startsWith("UPDATE district")) return "UPDATE district";
      if (sql.startsWith("SELECT d_name")) return "SELECT district";
      if (sql.startsWith("SELECT c_credit")) return "SELECT customer";
      if (sql.startsWith("UPDATE customer")) return "UPDATE customer";
      if (sql.startsWith("INSERT INTO history")) return "INSERT history";
      return sql;
    }
  }

  @SuppressWarnings("unchecked")
  private static <T> T proxy(Class<T> type, InvocationHandler handler) {
    return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, handler);
  }

  private static Object defaultValue(Method method) {
    Class<?> type = method.getReturnType();
    if (type == boolean.class) return false;
    if (type == byte.class) return (byte) 0;
    if (type == short.class) return (short) 0;
    if (type == int.class) return 0;
    if (type == long.class) return 0L;
    if (type == float.class) return 0F;
    if (type == double.class) return 0D;
    if (type == char.class) return (char) 0;
    return null;
  }
}
