package fixture.bytecode;

import java.util.Formatter;
import java.util.List;
import java.util.function.IntSupplier;
import java.util.stream.Collectors;

final class NegativeHotPath {
  private NegativeHotPath() {
  }

  static Integer boxingValueOf(int value) {
    return Integer.valueOf(value);
  }

  @SuppressWarnings({"deprecation", "removal"})
  static Integer boxingConstructor(int value) {
    return new Integer(value);
  }

  static long stream(List<String> values) {
    return values.stream().count();
  }

  static List<String> collector(List<String> values) {
    return values.stream().collect(Collectors.toList());
  }

  static String format(int value) {
    return String.format("%d", value);
  }

  static String formatter(int value) {
    return new Formatter().format("%d", value).toString();
  }

  static RuntimeException exceptionConstruction() {
    return new RuntimeException("failure");
  }

  static void exceptionThrow() {
    throw new RuntimeException("failure");
  }

  static Object objectAllocation() {
    return new Object();
  }

  static int[] arrayAllocation(int length) {
    return new int[length];
  }

  static String concat(int value) {
    return "value=" + value;
  }

  static IntSupplier capturedLambda(int value) {
    return () -> value;
  }

  static void varargs(int value) {
    accept(value);
  }

  static void accept(Object... values) {
  }
}
