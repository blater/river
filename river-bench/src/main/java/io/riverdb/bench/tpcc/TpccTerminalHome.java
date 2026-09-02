package io.riverdb.bench.tpcc;

/** Deterministic one-terminal ownership of a home warehouse and district. */
record TpccTerminalHome(int warehouse, int district) {
  static TpccTerminalHome at(TpccConfig config, int terminal) {
    if (config == null || terminal < 0 || terminal >= config.terminals()) {
      throw new IllegalArgumentException("invalid terminal home request");
    }
    int slot = terminal % (config.warehouses() * config.districts());
    return new TpccTerminalHome(
        slot / config.districts() + 1,
        slot % config.districts() + 1);
  }
}
