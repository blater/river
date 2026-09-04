package io.riverdb.bench.tpcc;

/** Deterministic one-terminal ownership of a home warehouse and district. */
record TpccTerminalHome(int warehouse, int district) {
  static TpccTerminalHome at(TpccConfig config, int terminal) {
    if (config == null || terminal < 0 || terminal >= config.terminals()) {
      throw new IllegalArgumentException("invalid terminal home request");
    }
    long homeCount = (long) config.warehouses() * config.districts();
    long slot = terminal % homeCount;
    return new TpccTerminalHome(
        (int) (slot / config.districts() + 1),
        (int) (slot % config.districts() + 1));
  }
}
