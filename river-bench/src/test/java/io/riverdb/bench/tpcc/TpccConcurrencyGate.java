package io.riverdb.bench.tpcc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Pauses only after the real Payment warehouse UPDATE has returned with its lock held. */
final class TpccConcurrencyGate {
  final CountDownLatch held = new CountDownLatch(1);
  final CountDownLatch release = new CountDownLatch(1);

  Connection wrap(Connection actual) {
    return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),
        new Class<?>[] {Connection.class}, (proxy, method, arguments) -> {
          Object result = invoke(actual, method, arguments);
          if (method.getName().equals("prepareStatement")
              && ((String) arguments[0]).startsWith("UPDATE warehouse SET")) {
            return statement((PreparedStatement) result);
          }
          return result;
        });
  }

  private PreparedStatement statement(PreparedStatement actual) {
    return (PreparedStatement) Proxy.newProxyInstance(PreparedStatement.class.getClassLoader(),
        new Class<?>[] {PreparedStatement.class}, (proxy, method, arguments) -> {
          Object result = invoke(actual, method, arguments);
          if (method.getName().equals("executeUpdate")) {
            assertEquals(1, result);
            held.countDown();
            assertTrue(release.await(5, TimeUnit.SECONDS), "Payment gate was not released");
          }
          return result;
        });
  }

  private static Object invoke(Object target, Method method, Object[] arguments) throws Throwable {
    try {
      return method.invoke(target, arguments);
    } catch (InvocationTargetException failure) {
      throw failure.getCause();
    }
  }
}
