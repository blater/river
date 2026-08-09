package fixture.bytecode;

final class AdversarialHotPath {
  private AdversarialHotPath() {
  }

  static void interfaceInvoke(Action action) {
    action.run();
  }

  static String dynamicConcat(int value) {
    return "adversarial=" + value;
  }

  static int[] primitiveArray(int length) {
    return new int[length];
  }

  static int[][] multiArray(int rows, int columns) {
    return new int[rows][columns];
  }

  static int tableSwitch(int value) {
    return switch (value) {
      case 1 -> 10;
      case 2 -> 20;
      case 3 -> 30;
      default -> 0;
    };
  }

  static int lookupSwitch(int value) {
    return switch (value) {
      case 1 -> 10;
      case 100 -> 20;
      default -> 0;
    };
  }

  static int reservedVictim() {
    return 1;
  }

  static int wideVictim(int value) {
    return value;
  }

  @SuppressWarnings("removal")
  static ThreadDeath threadDeath() {
    return new ThreadDeath();
  }

  static MisleadingException misleadingException() {
    return new MisleadingException();
  }

  static int customStream(CustomStream value) {
    return value.stream();
  }

  interface Action {
    void run();
  }

  static final class MisleadingException {
  }

  static final class CustomStream {
    int stream() {
      return 1;
    }
  }
}
