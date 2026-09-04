package io.riverdb.bench.tpcc;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.riverdb.jdbc.RiverTransactionDiagnostics;
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

/** Delivery locks every district's oldest-order range before mutating order lines. */
final class TpccDeliveryOrderTest {
  @Test
  void selectsAllDistrictsBeforeProcessingAnyOrder() throws Exception {
    JdbcRecorder jdbc = new JdbcRecorder();
    TpccInputs.Delivery input = new TpccInputs.Delivery();
    input.warehouse = 1;
    input.carrier = 2;
    input.date = new Timestamp(1);

    try (TpccDelivery delivery = new TpccDelivery(
        jdbc.connection(), jdbc.diagnostics(), 3)) {
      delivery.execute(input);
    }

    assertEquals(List.of(
        "SELECT oldest district=1",
        "SELECT oldest district=2",
        "SELECT oldest district=3",
        "DELETE new-order", "SELECT customer", "UPDATE order",
        "SELECT total", "UPDATE lines", "UPDATE customer",
        "DELETE new-order", "SELECT customer", "UPDATE order",
        "SELECT total", "UPDATE lines", "UPDATE customer",
        "DELETE new-order", "SELECT customer", "UPDATE order",
        "SELECT total", "UPDATE lines", "UPDATE customer",
        "COMMIT"), jdbc.executed);
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

    RiverTransactionDiagnostics diagnostics() {
      return proxy(RiverTransactionDiagnostics.class, (ignored, method, arguments) -> null);
    }

    private PreparedStatement statement(String sql) {
      return proxy(PreparedStatement.class, new InvocationHandler() {
        private int district;

        @Override
        public Object invoke(Object ignored, Method method, Object[] arguments) {
          return switch (method.getName()) {
            case "setInt" -> {
              if ((Integer) arguments[0] == 2) district = (Integer) arguments[1];
              yield null;
            }
            case "setBigDecimal", "setTimestamp", "close" -> null;
            case "executeQuery" -> {
              executed.add(label(sql, district));
              yield rows(sql, district);
            }
            case "executeUpdate" -> {
              executed.add(label(sql, district));
              yield sql.startsWith("UPDATE order_line") ? 5 : 1;
            }
            default -> defaultValue(method);
          };
        }
      });
    }

    private ResultSet rows(String sql, int district) {
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
            case "getInt" -> sql.startsWith("SELECT no_o_id") ? 100 + district : 7;
            case "getBigDecimal" -> BigDecimal.ONE;
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

    private static String label(String sql, int district) {
      if (sql.startsWith("SELECT no_o_id")) return "SELECT oldest district=" + district;
      if (sql.startsWith("DELETE FROM new_order")) return "DELETE new-order";
      if (sql.startsWith("SELECT o_c_id")) return "SELECT customer";
      if (sql.startsWith("UPDATE orders")) return "UPDATE order";
      if (sql.startsWith("SELECT SUM")) return "SELECT total";
      if (sql.startsWith("UPDATE order_line")) return "UPDATE lines";
      if (sql.startsWith("UPDATE customer")) return "UPDATE customer";
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
