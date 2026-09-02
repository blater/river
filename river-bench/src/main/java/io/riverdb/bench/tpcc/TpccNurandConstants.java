package io.riverdb.bench.tpcc;

/** Pinned and independently validated load/run NURand constants. */
record TpccNurandConstants(int loadLast, int runLast, int customerId, int itemId) {
  static final TpccNurandConstants STANDARD = new TpccNurandConstants(157, 223, 259, 7_919);

  TpccNurandConstants {
    int difference = Math.abs(runLast - loadLast);
    if (loadLast < 0 || loadLast > 255 || runLast < 0 || runLast > 255
        || difference < 65 || difference > 119 || difference == 96 || difference == 112
        || customerId < 0 || customerId > 1_023 || itemId < 0 || itemId > 8_191) {
      throw new IllegalArgumentException("invalid load/run NURand constants");
    }
  }
}
