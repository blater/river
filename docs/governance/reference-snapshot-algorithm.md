# River external reference snapshot algorithm

Algorithm ID: `river-tree-sha256-v2`

Implementation:
`buildSrc/src/main/java/io/riverdb/buildpolicy/ProvenancePolicy.java`

This algorithm gives an approved, unversioned external workspace tree a stable
content identity without making that tree an input to an ordinary River build.

The verifier walks the selected real directory without following links. It
rejects an empty retained tree, every symbolic link, and every entry that is
neither a regular file nor a directory. After checking each entry's type, it
excludes only regular files whose exact basename is `.DS_Store`; a symbolic
link, special file, or directory with that name is not exempt, and near names
remain part of the identity. For each retained regular file it creates a
`/`-separated relative path and sorts paths by Java `String` natural order. It
does not include timestamps, ownership, permissions, or host-absolute paths.

The tree SHA-256 input starts with this ASCII header:

```text
river-tree-sha256-v2\n
```

For each sorted file, the verifier appends these bytes in order:

1. ASCII `file` and a zero byte;
2. the decimal UTF-8 byte length of the relative path and ASCII `:`;
3. the UTF-8 relative path and a zero byte;
4. the decimal file byte length and a zero byte;
5. the raw 32-byte SHA-256 digest of the file; and
6. ASCII line feed.

Lengths and zero separators make embedded whitespace and newlines
unambiguous. Including both path and content means a rename, addition, removal,
or byte change changes the tree identity. The ledger also records an upstream
URL and revision; those descriptive fields do not replace content verification.

Run the explicit verification from the River checkout with an absolute parent
of the named external workspaces:

```text
./gradlew --offline verifyReferenceSnapshots \
  -PriverReferenceWorkspaceRoot=/absolute/path/to/workspace
```

The task is intentionally excluded from `check` and `verify`. A detached clean
River checkout therefore proves only its checked-in inputs; a machine holding
the separately approved reference trees can additionally prove their identities.
