package io.riverdb.bench.tpcc;

/** Dense argument and step layout for one TPC-C New-Order line-count shape. */
final class TpccRiverNewOrderLayout {
  static final int MINIMUM_LINES = 5;
  static final int MAXIMUM_LINES = 15;
  static final int WAREHOUSE = 0;
  static final int DISTRICT = 1;
  static final int CUSTOMER = 2;
  static final int ENTRY = 3;
  static final int LINE_COUNT = 4;
  static final int ALL_LOCAL = 5;
  static final int HEADER_ARGUMENTS = 6;
  static final int ARGUMENTS_PER_LINE = 4;
  static final int HEADER_STEPS = 7;
  static final int STEPS_PER_LINE = 4;

  private TpccRiverNewOrderLayout() { }

  static int item(int line) { return lineBase(line); }
  static int quantity(int line) { return lineBase(line) + 1; }
  static int supplyWarehouse(int line) { return lineBase(line) + 2; }
  static int lineNumber(int line) { return lineBase(line) + 3; }
  static int ten(int lines) { return constants(lines); }
  static int ninetyOne(int lines) { return constants(lines) + 1; }
  static int one(int lines) { return constants(lines) + 2; }
  static int zero(int lines) { return constants(lines) + 3; }
  static int argumentCount(int lines) { return constants(lines) + 4; }
  static int stepCount(int lines) { return HEADER_STEPS + STEPS_PER_LINE * lines; }
  static int itemStep(int line) { return HEADER_STEPS + STEPS_PER_LINE * line; }

  static int failureKind(int lines, int step) {
    if (step == stepCount(lines)) return 11;
    if (step < 0) return -1;
    if (step >= HEADER_STEPS) {
      return HEADER_STEPS + (step - HEADER_STEPS) % STEPS_PER_LINE;
    }
    return switch (step) {
      case 0 -> 0;
      case 1 -> 1;
      case 2 -> 3;
      case 3 -> 5;
      case 4 -> 4;
      case 5 -> 6;
      case 6 -> 2;
      default -> -1;
    };
  }

  static boolean validLines(int lines) {
    return lines >= MINIMUM_LINES && lines <= MAXIMUM_LINES;
  }

  private static int lineBase(int line) {
    return HEADER_ARGUMENTS + ARGUMENTS_PER_LINE * line;
  }

  private static int constants(int lines) {
    return HEADER_ARGUMENTS + ARGUMENTS_PER_LINE * lines;
  }
}
