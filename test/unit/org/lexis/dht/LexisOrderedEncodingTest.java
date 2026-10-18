/*
 * SPDX-License-Identifier: Apache-2.0
 */
package org.lexis.dht;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.Test;

import org.apache.cassandra.db.marshal.*;
import org.apache.cassandra.dht.IPartitioner;
import org.apache.cassandra.dht.Token;
import org.apache.cassandra.utils.Hex;

import static org.junit.Assert.*;

public class LexisOrderedEncodingTest
{
    private static AbstractType<?> type(String name)
    {
        switch (name)
        {
            case "int": return Int32Type.instance;
            case "bigint": return LongType.instance;
            case "smallint": return ShortType.instance;
            case "tinyint": return ByteType.instance;
            case "boolean": return BooleanType.instance;
            case "double": return DoubleType.instance;
            case "float": return FloatType.instance;
            case "text": return UTF8Type.instance;
            case "blob": return BytesType.instance;
            case "timestamp": return TimestampType.instance;
            case "time": return TimeType.instance;
            case "date": return SimpleDateType.instance;
            case "varint": return IntegerType.instance;
            case "decimal": return DecimalType.instance;
            case "uuid": return UUIDType.instance;
            default: throw new IllegalArgumentException(name);
        }
    }

    // Vectors generated from Lexis' server encoder (dht/ordered_encoding.cc):
    // "<types> <serialized components> <token>", comma-separated lists, hex.
    @Test
    public void matchesServerVectors() throws Exception
    {
        List<String> lines = Files.readAllLines(Paths.get(System.getProperty("cassandra.test.data", "test/data"), "lexis", "ordered_encoding_vectors.txt"),
                                                StandardCharsets.UTF_8);
        int n = 0;
        for (String line : lines)
        {
            if (line.isEmpty())
                continue;
            String[] parts = line.split(" ", -1);
            List<AbstractType<?>> types = new ArrayList<>();
            for (String t : parts[0].split(","))
                types.add(type(t));
            List<ByteBuffer> values = new ArrayList<>();
            for (String v : parts[1].split(",", -1))
                values.add(ByteBuffer.wrap(Hex.hexToBytes(v)));
            byte[] got = LexisOrderedEncoding.encode(types, values);
            assertArrayEquals("vector: " + line, Hex.hexToBytes(parts[2]), got);
            n++;
        }
        assertEquals(640, n);
    }

    @Test
    public void partitionerTokensAreKeyOrdered()
    {
        IPartitioner p = LexisByteOrderedPartitioner.forTable(LexisByteOrderedPartitioner.instance,
                                                              Arrays.<AbstractType<?>>asList(Int32Type.instance));
        Token prev = null;
        for (int v : new int[]{ Integer.MIN_VALUE, -70000, -1, 0, 1, 255, 256, Integer.MAX_VALUE })
        {
            Token t = p.getToken(Int32Type.instance.decompose(v));
            if (prev != null)
                assertTrue(prev.compareTo(t) < 0);
            assertSame(p, t.getPartitioner());
            prev = t;
        }
        // One shared instance per key type list.
        assertSame(p, LexisByteOrderedPartitioner.forTable(LexisByteOrderedPartitioner.instance,
                                                           Arrays.<AbstractType<?>>asList(Int32Type.instance)));
        // Hex token strings round-trip through the token factory.
        Token t = p.getToken(Int32Type.instance.decompose(143));
        assertEquals(t, p.getTokenFactory().fromString(p.getTokenFactory().toString(t)));
        assertEquals("80000100018f0000", p.getTokenFactory().toString(t));
    }
}
