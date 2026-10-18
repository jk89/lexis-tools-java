/*
 * SPDX-License-Identifier: Apache-2.0
 */
package org.lexis.dht;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.List;

import org.apache.cassandra.db.marshal.*;

/**
 * Order-preserving encoding of partition keys, as used by Lexis'
 * LexisByteOrderedPartitioner (dht/ordered_encoding.cc in Lexis). The token of
 * a partition is this encoding, so it must match the server byte for byte.
 *
 * Each component is type-encoded, its 0x00 bytes are escaped as 0x00 0x01,
 * and it is terminated by 0x00 0x00. Unsigned byte order of the result is
 * partition key order for the types with an exact rule.
 */
public final class LexisOrderedEncoding
{
    private LexisOrderedEncoding() {}

    private static void escape(ByteArrayOutputStream buf, int b)
    {
        b &= 0xff;
        if (b == 0)
        {
            buf.write(0x00);
            buf.write(0x01);
        }
        else
        {
            buf.write(b);
        }
    }

    private static void escapeAll(ByteArrayOutputStream buf, byte[] bytes)
    {
        for (byte b : bytes)
            escape(buf, b);
    }

    private static void escapeLong(ByteArrayOutputStream buf, long v)
    {
        for (int shift = 56; shift >= 0; shift -= 8)
            escape(buf, (int) (v >>> shift));
    }

    private static void escapeInt(ByteArrayOutputStream buf, int v)
    {
        for (int shift = 24; shift >= 0; shift -= 8)
            escape(buf, v >>> shift);
    }

    private static void terminate(ByteArrayOutputStream buf)
    {
        buf.write(0x00);
        buf.write(0x00);
    }

    // Float lex transform ported from kaebase v2:
    // https://github.com/kaepek/kaebase/blob/FEAT/scaling/v2/crates/common/src/encoding/float.rs
    static long float64ToLex(double v)
    {
        long bits = Double.doubleToRawLongBits(v);
        return bits >= 0 ? bits | Long.MIN_VALUE : ~bits;
    }

    private static byte[] bytesOf(ByteBuffer bb)
    {
        byte[] b = new byte[bb.remaining()];
        bb.duplicate().get(b);
        return b;
    }

    /** Magnitude (big-endian, two's complement input) and sign. */
    private static byte[] magnitude(byte[] twos, boolean[] negativeOut)
    {
        byte[] mag = twos.clone();
        boolean negative = mag.length > 0 && (mag[0] & 0x80) != 0;
        if (negative)
        {
            for (int i = 0; i < mag.length; i++)
                mag[i] = (byte) ~mag[i];
            for (int i = mag.length - 1; i >= 0; i--)
            {
                mag[i] = (byte) (mag[i] + 1);
                if (mag[i] != 0)
                    break;
            }
        }
        int start = 0;
        while (start < mag.length && mag[start] == 0)
            start++;
        negativeOut[0] = negative;
        byte[] out = new byte[mag.length - start];
        System.arraycopy(mag, start, out, 0, out.length);
        return out;
    }

    private static void encodeVarint(ByteArrayOutputStream buf, byte[] twos)
    {
        boolean[] neg = new boolean[1];
        byte[] mag = magnitude(twos, neg);
        int flip = neg[0] ? 0xff : 0x00;
        escape(buf, neg[0] ? 0x7f : 0x80);
        escapeInt(buf, mag.length ^ (neg[0] ? 0xffffffff : 0));
        for (byte b : mag)
            escape(buf, (b & 0xff) ^ flip);
    }

    /** Decimal digits of a big-endian magnitude. */
    private static String decimalDigits(byte[] mag)
    {
        byte[] m = mag.clone();
        StringBuilder digits = new StringBuilder();
        boolean nonzero = true;
        while (nonzero)
        {
            nonzero = false;
            int rem = 0;
            for (int i = 0; i < m.length; i++)
            {
                int cur = (rem << 8) | (m[i] & 0xff);
                m[i] = (byte) (cur / 10);
                rem = cur % 10;
                if (m[i] != 0)
                    nonzero = true;
            }
            digits.append((char) ('0' + rem));
        }
        // The loop emits one digit even for zero; strip leading zeros of the result.
        String s = digits.reverse().toString();
        int i = 0;
        while (i < s.length() - 1 && s.charAt(i) == '0')
            i++;
        return s.substring(i);
    }

    private static boolean encodeDecimal(ByteArrayOutputStream buf, byte[] raw)
    {
        if (raw.length < 4)
            return false;
        long scale = ByteBuffer.wrap(raw, 0, 4).getInt();
        byte[] unscaled = new byte[raw.length - 4];
        System.arraycopy(raw, 4, unscaled, 0, unscaled.length);
        boolean[] neg = new boolean[1];
        byte[] mag = magnitude(unscaled, neg);
        if (mag.length == 0)
        {
            escape(buf, 0x80);
            return true;
        }
        String digits = decimalDigits(mag);
        int end = digits.length();
        while (end > 1 && digits.charAt(end - 1) == '0')
        {
            end--;
            scale--;
        }
        digits = digits.substring(0, end);
        long exponent = digits.length() - scale;
        long biased = exponent ^ Long.MIN_VALUE;
        int flip = neg[0] ? 0xff : 0x00;
        escape(buf, neg[0] ? 0x7f : 0x81);
        escapeLong(buf, neg[0] ? ~biased : biased);
        for (int i = 0; i < digits.length(); i++)
            escape(buf, digits.charAt(i) ^ flip);
        if (neg[0])
            escape(buf, 0xff);
        return true;
    }

    private static void encodeComponent(ByteArrayOutputStream buf, AbstractType<?> type, byte[] raw)
    {
        if (raw.length == 0)
        {
            terminate(buf);
            return;
        }
        if (type instanceof ReversedType)
            type = ((ReversedType<?>) type).baseType;

        if ((type instanceof LongType || type instanceof CounterColumnType) && raw.length == 8)
        {
            escapeLong(buf, ByteBuffer.wrap(raw).getLong() ^ Long.MIN_VALUE);
        }
        else if (type instanceof Int32Type && raw.length == 4)
        {
            escapeInt(buf, ByteBuffer.wrap(raw).getInt() ^ Integer.MIN_VALUE);
        }
        else if (type instanceof ShortType && raw.length == 2)
        {
            int v = (ByteBuffer.wrap(raw).getShort() & 0xffff) ^ 0x8000;
            escape(buf, v >>> 8);
            escape(buf, v);
        }
        else if (type instanceof ByteType && raw.length == 1)
        {
            escape(buf, (raw[0] & 0xff) ^ 0x80);
        }
        else if (type instanceof BooleanType && raw.length == 1)
        {
            escape(buf, raw[0] != 0 ? 1 : 0);
        }
        else if (type instanceof TimestampType || type instanceof DateType || type instanceof SimpleDateType || type instanceof TimeType)
        {
            // Read up to 8 bytes as an unsigned big-endian number, then add 2^63.
            long v = 0;
            for (int i = 0; i < raw.length && i < 8; i++)
                v = (v << 8) | (raw[i] & 0xff);
            escapeLong(buf, v + Long.MIN_VALUE);
        }
        else if (type instanceof DoubleType && raw.length == 8)
        {
            escapeLong(buf, float64ToLex(ByteBuffer.wrap(raw).getDouble()));
        }
        else if (type instanceof FloatType && raw.length == 4)
        {
            escapeLong(buf, float64ToLex(ByteBuffer.wrap(raw).getFloat()));
        }
        else if (type instanceof IntegerType)
        {
            encodeVarint(buf, raw);
        }
        else if (type instanceof DecimalType)
        {
            if (!encodeDecimal(buf, raw))
                escapeAll(buf, raw);
        }
        else
        {
            // Text, ascii, blob, uuid, timeuuid, inet, duration, and types with no
            // order-preserving rule (tuples, UDTs, collections): raw bytes, like the server.
            escapeAll(buf, raw);
        }
        terminate(buf);
    }

    /** Encodes the serialized components of a partition key, in partition key order. */
    public static byte[] encode(List<AbstractType<?>> types, List<ByteBuffer> components)
    {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        for (int i = 0; i < components.size(); i++)
        {
            byte[] raw = bytesOf(components.get(i));
            if (i >= types.size())
            {
                // More components than partition key columns: raw bytes, like the server.
                escapeAll(buf, raw);
                terminate(buf);
            }
            else
            {
                encodeComponent(buf, types.get(i), raw);
            }
        }
        return buf.toByteArray();
    }
}
