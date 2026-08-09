# `river-base`

This module owns River's dependency-free identifiers, status values,
cancellation, and diagnostic ownership guards.

The typed ID records are boundary and control-plane values at this stage. Do
not allocate them per row, page lookup, journal record, or other designated
kernel hot-path operation. Kernel structures should retain primitive fields or
caller-owned views until P09 establishes measured representation and allocation
budgets.

`Lsn` and `JournalPosition` prevent accidental interchange today, but their
numeric ranges, sentinel meanings, and physical/logical mapping are not frozen.
P10 owns that compatibility contract. Durable codecs must not be based on the
current Java record layout before P10 is accepted.
