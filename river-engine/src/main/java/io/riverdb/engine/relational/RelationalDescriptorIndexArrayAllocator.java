package io.riverdb.engine.relational;

import io.riverdb.engine.schema.KeyDescriptor;

/** Allocation boundary for exact-sized descriptor-index successor arrays. */
interface RelationalDescriptorIndexArrayAllocator {
  RelationalDescriptorIndexArrayAllocator STANDARD = new RelationalDescriptorIndexArrayAllocator() {
    @Override
    public int[] integers(int count) { return new int[count]; }

    @Override
    public KeyDescriptor[] keys(int count) { return new KeyDescriptor[count]; }
  };

  int[] integers(int count);

  KeyDescriptor[] keys(int count);
}
