package io.riverdb.engine.schema.catalog;

import io.riverdb.engine.schema.KeyDescriptor;

/** Exact immutable key-array pair assembled from one complete catalog generation. */
record CatalogKeyDescriptorArrays(KeyDescriptor[] secondary, KeyDescriptor[] foreign) { }
