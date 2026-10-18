/*
 * SPDX-License-Identifier: Apache-2.0
 */
package org.lexis.dht;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.apache.cassandra.db.BufferDecoratedKey;
import org.apache.cassandra.db.DecoratedKey;
import org.apache.cassandra.db.marshal.AbstractType;
import org.apache.cassandra.db.marshal.CompositeType;
import org.apache.cassandra.dht.ByteOrderedPartitioner;
import org.apache.cassandra.dht.IPartitioner;

/**
 * Lexis' partitioner: the token of a partition is an order-preserving encoding
 * of its partition key (see {@link LexisOrderedEncoding}). Tokens are byte
 * strings compared unsigned and printed as hex, exactly like
 * {@link ByteOrderedPartitioner}'s, so token handling is inherited from it;
 * only computing a token from a key differs.
 *
 * Computing a token needs the partition key column types. The cluster-wide
 * instance ({@link #instance}) has none and can only parse and compare
 * tokens; table metadata uses {@link #forTable} to get an instance that knows
 * its table's key types.
 */
public class LexisByteOrderedPartitioner extends ByteOrderedPartitioner
{
    public static final LexisByteOrderedPartitioner instance = new LexisByteOrderedPartitioner(null);

    private final List<AbstractType<?>> keyTypes;

    private LexisByteOrderedPartitioner(List<AbstractType<?>> keyTypes)
    {
        this.keyTypes = keyTypes;
    }

    // One instance per list of key types: Cassandra compares partitioners with ==,
    // and table metadata is copied freely.
    private static final ConcurrentMap<List<AbstractType<?>>, LexisByteOrderedPartitioner> byKeyTypes = new ConcurrentHashMap<>();

    /** The partitioner for a table with the given partition key column types. */
    public static IPartitioner forTable(IPartitioner partitioner, List<AbstractType<?>> keyTypes)
    {
        if (!(partitioner instanceof LexisByteOrderedPartitioner))
            return partitioner;
        // Tools that rebuild a schema from an sstable header model a composite
        // partition key as one column of CompositeType; its components are the
        // real key columns.
        if (keyTypes.size() == 1 && keyTypes.get(0) instanceof CompositeType)
            keyTypes = ((CompositeType) keyTypes.get(0)).types;
        List<AbstractType<?>> key = Collections.unmodifiableList(new ArrayList<>(keyTypes));
        return byKeyTypes.computeIfAbsent(key, LexisByteOrderedPartitioner::new);
    }

    /** The partitioner for a table whose key validator is the given type. */
    public static IPartitioner forKeyValidator(IPartitioner partitioner, AbstractType<?> keyValidator)
    {
        List<AbstractType<?>> types = keyValidator instanceof CompositeType
                                    ? ((CompositeType) keyValidator).types
                                    : Collections.<AbstractType<?>>singletonList(keyValidator);
        return forTable(partitioner, types);
    }

    /** A token computed from a key; it reports the (per-table) partitioner that computed it. */
    public static final class LexisToken extends BytesToken
    {
        private final transient IPartitioner partitioner;

        LexisToken(byte[] token, IPartitioner partitioner)
        {
            super(token);
            this.partitioner = partitioner;
        }

        @Override
        public IPartitioner getPartitioner()
        {
            return partitioner;
        }
    }

    @Override
    public BytesToken getToken(ByteBuffer key)
    {
        if (keyTypes == null)
            throw new UnsupportedOperationException("LexisByteOrderedPartitioner needs the table's partition key types to compute a token");
        List<ByteBuffer> components = keyTypes.size() == 1
                                    ? Collections.singletonList(key)
                                    : CompositeType.splitName(key);
        return new LexisToken(LexisOrderedEncoding.encode(keyTypes, components), this);
    }

    @Override
    public DecoratedKey decorateKey(ByteBuffer key)
    {
        return new BufferDecoratedKey(getToken(key), key);
    }

    @Override
    public boolean equals(Object o)
    {
        return o instanceof LexisByteOrderedPartitioner;
    }

    @Override
    public int hashCode()
    {
        return LexisByteOrderedPartitioner.class.hashCode();
    }
}
