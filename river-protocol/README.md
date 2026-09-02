# River protocol slice

This module currently defines the bounded wire contract consumed by the
loopback TCP server. It is an unreleased, pre-V1 contract: River may replace it
directly as the SQL value model, batching, authorization, and cancellation
capabilities grow. No mixed-version or migration machinery is promised before
V1.

Frames are big-endian and contain a 32-byte header: magic, protocol version,
message type, flags, positive request ID, payload length, and a zero reserved
field. Payloads are limited to 16 KB before use. Requests are ordered and one
session may own at most one active query. Query open and each subsequent
`FETCH` grant credit for one bounded row. The server retains exactly one
lookahead row so the response carrying the final row can also carry end of
stream and transaction completion.

Responses carry the stable River status code, result flags, affected-row and
column counts, commit sequence, key, rows returned, and the bounded typed
result shape. Fixed values use one big-endian 64-bit word, except
`DECIMAL(19..38)`, whose signed unscaled value uses a descriptor-selected
big-endian high/low pair. Encoding writes directly into caller-owned output
storage; decoding uses caller-owned reusable carriers. Statement decoding creates the one
`String` currently required by the embedded engine API, while fetch and
response paths are allocation-free after warmup.

A successful query-open response carries the bounded ASCII projection names,
column count, and first row when one exists. Empty and singleton results close
their engine query during open; longer results close while producing the final
fetch response. A client that receives end of stream completes query close
locally rather than sending an EOF fetch or `CLOSE_QUERY`.

Authenticated connections negotiate protocol version inside TLS 1.3, verify
the server hostname, and use a fresh server challenge plus TLS exporter keying
material in an HMAC proof. Raw tokens are never placed in protocol frames. The
server stores only the token hash; proof buffers and channel-binding material
are erased after authentication. Tokens are therefore required to be random,
high-entropy credentials rather than human passwords.

The secure loopback service maps its configured token to one positive service
principal ID and a fixed `READ`, `WRITE`, `SCHEMA`, and `ADMIN` permission
mask. The engine checks that immutable mask after parsing and before admission.
Authentication decisions and statement admissions are appended to a bounded,
forced audit file before they take effect. A full or corrupt audit refuses new
work; rotation and repair are explicit operator actions.

The production server admits connections into a fixed number of preallocated
slots. Each admitted connection owns its frame buffers and a virtual thread;
an idle connection therefore cannot serialize other sessions. Connections
above the configured cap are closed before a database session is created.

The only production listeners remain deliberately bound to the operating
system's loopback address. Non-loopback service remains unavailable until a
multi-principal credential and administration consumer requires SQL-managed
roles/grants and a network listener policy.
