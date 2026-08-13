# Third-Party Notices

The benchmark-only mock collector reads and compiles protobuf schema files from a separate,
read-only Glowroot checkout:

- Project: Glowroot
- Source: <https://github.com/glowroot/glowroot>
- License: Apache License 2.0
- Reference revision used during implementation: `622dc6f`

No Glowroot source or protobuf schema file is copied into this repository. Generated benchmark
classes stay under Maven's `target` directory. The schemas, generated classes, and mock collector
are test infrastructure; none is included in the `java-rust-glowroot-agent` runtime JAR.

If the mock collector or its generated classes are redistributed, the Glowroot Apache License 2.0
terms apply. The standalone agent implementation remains licensed under this repository's MIT
License.

The native exporter uses the collector field numbers and service names defined by that public wire
contract. The implementation itself is independent Rust code.
