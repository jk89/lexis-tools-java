# lexis-tools-java

The Java tools for [Lexis](https://github.com/jk89/lexis), a fork of ScyllaDB
that replaces the Murmur3 partitioner with byte-ordered partitioning, built on
tablets. In Lexis the token of a partition is an order-preserving encoding of
its partition key.

The tools read and write Lexis sstables (`sstabledump`, `sstablemetadata`,
`sstableloader`), and `nodetool` works against a Lexis node. Tokens are shown
and accepted as hex, for example in `nodetool repair -st ... -et ...`.

Docker image: `docker pull 0x6a6f6e6e79/lexis-tools-java`

## Base and licence

Forked from ScyllaDB's scylla-tools-java at commit `e505a6d3bb`. Licensed
under the Apache License 2.0 ([LICENSE.txt](LICENSE.txt),
[NOTICE.txt](NOTICE.txt)).

The upstream README is kept unchanged in [README_SCYLLA.md](README_SCYLLA.md).