package io.riverdb.engine.relational;

/** Injectable retained-entry allocation boundary for transactional DDL overlays. */
interface RelationalPreparedDescriptorAllocator {
  RelationalPreparedDescriptorAllocator STANDARD = new RelationalPreparedDescriptorAllocator() {
    @Override
    public RelationalPreparedDescriptorEntry entry() {
      return new RelationalPreparedDescriptorEntry();
    }

    @Override
    public RelationalPreparedDescriptorEntry[] entries(int size) {
      return new RelationalPreparedDescriptorEntry[size];
    }
  };

  RelationalPreparedDescriptorEntry entry();
  RelationalPreparedDescriptorEntry[] entries(int size);
}
