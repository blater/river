# ADR 0013: Use decimal size units in configuration and documentation

Status: Accepted

## Decision

River uses `KB`, `MB`, and `GB` for all user-facing size values in properties,
documentation, examples, diagnostics, and tests. Binary-unit suffixes are not
used.

Runtime size properties use the standard `KB`, `MB`, and `GB` labels. Exact
byte values remain valid where a format or page layout requires them.

Internal code may calculate in bytes, but it must not expose a different unit
vocabulary at a user-facing boundary. Values are read once at startup into an
immutable runtime configuration.

## Rationale

One unit vocabulary avoids making users translate between nominally similar
size names. Exact byte arithmetic remains available for implementation
invariants without leaking binary-unit terminology into configuration or
operations.
