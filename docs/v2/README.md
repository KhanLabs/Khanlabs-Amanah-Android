# Protocol v2 schema and test vectors

Copies of `docs/v2/` in [KhanLabs/khanlabs-lanshare](https://github.com/KhanLabs/khanlabs-lanshare). If they differ, that one is right.

- `schema.json`: a JSON Schema for every v2 message. Validate requests and replies against it in tests.
- `vectors.json`: test vectors for chunk and tree digests, device proofs, the pairing MAC and batch records. The Android unit tests must pass every vector. The private keys in it are for tests only.
