package fixture.reference;

final class Table {
  Kernel forbiddenKernel;
  java.util.List<Kernel> forbiddenGenericKernel;

  static final class Nested {
    Kernel forbidden(Kernel kernel) {
      return new Kernel();
    }
  }

  static final class DescriptorOnly {
    void forbidden() {
      Helper.accept(Helper.kernel());
    }
  }

  static final class TypeOperations {
    boolean forbidden(Object value) {
      Kernel[] kernels = new Kernel[1];
      return value instanceof Kernel && kernels.length == 1;
    }
  }
}

final class Kernel {
  Store forbiddenStore;

  Table forbiddenTable(Table table) {
    Store constructed = new Store();
    constructed.accept(table);
    return table;
  }
}

final class Store {
  Table forbiddenTable() {
    return new Table();
  }

  void accept(Table table) {
  }
}

final class Helper {
  static Kernel kernel() {
    return null;
  }

  static void accept(Kernel kernel) {
  }
}
