package com.retro.bamfos.updater.engine.ping;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

// Uncompressed NBT reader/writer for servers.dat that preserves unknown fields, icons, and other servers byte-for-byte.
public final class Nbt {

    /** One tagged value. The runtime type of `v` follows the tag id; compounds use LinkedHashMap to
     *  preserve entry order. */
    public record Val(int type, Object v) {
    }

    /** Lists carry their element tag id separately because an empty list still has one. */
    public record ListVal(int elemType, List<Val> items) {
    }

    public record Root(String name, LinkedHashMap<String, Val> compound) {
    }

    public static Root read(InputStream raw) throws IOException {
        DataInputStream in = new DataInputStream(raw);
        if (in.readByte() != 0x0A) {
            throw new IOException("not an uncompressed NBT compound root");
        }
        String name = readString(in);
        return new Root(name, readCompound(in));
    }

    public static byte[] write(Root root) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bos);
        out.writeByte(0x0A);
        writeString(out, root.name());
        writeCompound(out, root.compound());
        return bos.toByteArray();
    }

    private static LinkedHashMap<String, Val> readCompound(DataInputStream in) throws IOException {
        LinkedHashMap<String, Val> map = new LinkedHashMap<>();
        int tag;
        while ((tag = in.readByte()) != 0) {
            String key = readString(in);
            map.put(key, readPayload(in, tag));
        }
        return map;
    }

    private static Val readPayload(DataInputStream in, int tag) throws IOException {
        return new Val(tag, switch (tag) {
            case 0x01 -> in.readByte();
            case 0x02 -> in.readShort();
            case 0x03 -> in.readInt();
            case 0x04 -> in.readLong();
            case 0x05 -> in.readFloat();
            case 0x06 -> in.readDouble();
            case 0x07 -> {
                byte[] b = new byte[checked(in.readInt())];
                in.readFully(b);
                yield b;
            }
            case 0x08 -> readString(in);
            case 0x09 -> {
                int elem = in.readByte();
                int count = checked(in.readInt());
                List<Val> items = new ArrayList<>(Math.max(0, count));
                for (int i = 0; i < count; i++) {
                    items.add(readPayload(in, elem));
                }
                yield new ListVal(elem, items);
            }
            case 0x0A -> readCompound(in);
            case 0x0B -> {
                int[] a = new int[checked(in.readInt())];
                for (int i = 0; i < a.length; i++) {
                    a[i] = in.readInt();
                }
                yield a;
            }
            case 0x0C -> {
                long[] a = new long[checked(in.readInt())];
                for (int i = 0; i < a.length; i++) {
                    a[i] = in.readLong();
                }
                yield a;
            }
            default -> throw new IOException("unknown NBT tag " + tag);
        });
    }

    @SuppressWarnings("unchecked")
    private static void writePayload(DataOutputStream out, Val val) throws IOException {
        switch (val.type()) {
            case 0x01 -> out.writeByte((Byte) val.v());
            case 0x02 -> out.writeShort((Short) val.v());
            case 0x03 -> out.writeInt((Integer) val.v());
            case 0x04 -> out.writeLong((Long) val.v());
            case 0x05 -> out.writeFloat((Float) val.v());
            case 0x06 -> out.writeDouble((Double) val.v());
            case 0x07 -> {
                byte[] b = (byte[]) val.v();
                out.writeInt(b.length);
                out.write(b);
            }
            case 0x08 -> writeString(out, (String) val.v());
            case 0x09 -> {
                ListVal l = (ListVal) val.v();
                out.writeByte(l.elemType());
                out.writeInt(l.items().size());
                for (Val item : l.items()) {
                    writePayload(out, item);
                }
            }
            case 0x0A -> writeCompound(out, (LinkedHashMap<String, Val>) val.v());
            case 0x0B -> {
                int[] a = (int[]) val.v();
                out.writeInt(a.length);
                for (int i : a) {
                    out.writeInt(i);
                }
            }
            case 0x0C -> {
                long[] a = (long[]) val.v();
                out.writeInt(a.length);
                for (long l : a) {
                    out.writeLong(l);
                }
            }
            default -> throw new IOException("unknown NBT tag " + val.type());
        }
    }

    private static void writeCompound(DataOutputStream out, LinkedHashMap<String, Val> map) throws IOException {
        for (var e : map.entrySet()) {
            out.writeByte(e.getValue().type());
            writeString(out, e.getKey());
            writePayload(out, e.getValue());
        }
        out.writeByte(0);
    }

    /* NBT strings use Java modified UTF-8 (DataOutput.writeUTF), not standard UTF-8. They match for
       ASCII but differ for non-ASCII and 4-byte characters, so use the modified codec to round-trip
       accented and emoji server names cleanly. */
    private static String readString(DataInputStream in) throws IOException {
        return in.readUTF();
    }

    private static void writeString(DataOutputStream out, String s) throws IOException {
        out.writeUTF(s);
    }

    private static final int MAX_ELEMS = 8_388_608; // servers.dat is tiny; reject absurd lengths

    /** Bound an untrusted array length before allocating, a hostile servers.dat could otherwise
     *  hand us 0x7FFFFFFF and OOM the boot, breaking the "malformed = no-signal, never a crash" rule.
     *  Negative also catches an int overflow from a *4 / *8 element size upstream. */
    private static int checked(int n) throws IOException {
        if (n < 0 || n > MAX_ELEMS) {
            throw new IOException("NBT array length out of range: " + n);
        }
        return n;
    }

    private Nbt() {
    }
}
