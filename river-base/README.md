# `river-base`

This module owns River's dependency-free identifiers, status values,
cancellation, and diagnostic ownership guards.

The typed ID records are semantic boundary and control-plane values at this
stage. Their Java fields are not durable packed encodings. Kernel structures
may retain validated primitive fields or caller-owned views when storing a
wrapper would allocate on a designated hot path, but public seams must accept
or return the semantic type instead of an interchangeable raw unit. P09 and
the owning format gate establish measured representations and allocation
budgets.

`Lsn`, `WalGeneration`, and `JournalPosition` prevent accidental interchange
today. Durable codecs must not infer field widths, byte order, sentinel
encodings, or physical/logical mapping from the current Java record layouts;
ADR 0004 and K02 own those decisions.
