package dev.yu.worldrepair.worldtool.nbt;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Small, strict NBT codec for the standalone tool. It deliberately has no Minecraft runtime
 * dependency and enforces limits before allocating collections or arrays.
 */
public final class Nbt {
    public static final byte END = 0;
    public static final byte BYTE = 1;
    public static final byte SHORT = 2;
    public static final byte INT = 3;
    public static final byte LONG = 4;
    public static final byte FLOAT = 5;
    public static final byte DOUBLE = 6;
    public static final byte BYTE_ARRAY = 7;
    public static final byte STRING = 8;
    public static final byte LIST = 9;
    public static final byte COMPOUND = 10;
    public static final byte INT_ARRAY = 11;
    public static final byte LONG_ARRAY = 12;

    private Nbt() {
    }

    public record Limits(long maxBytes, int maxDepth, int maxCollectionLength, int maxStringBytes) {
        public Limits {
            if (maxBytes < 1 || maxDepth < 1 || maxCollectionLength < 1 || maxStringBytes < 1) {
                throw new IllegalArgumentException("NBT limits must be positive");
            }
        }

        public static Limits conservative() {
            return new Limits(16L * 1_024 * 1_024, 64, 1_000_000, 1_048_576);
        }
    }

    public record Root(String name, Tag tag) {
        public Root {
            if (name == null || tag == null || tag.type() == END) {
                throw new IllegalArgumentException("Invalid NBT root");
            }
        }
    }

    public sealed interface Tag permits ByteTag, ShortTag, IntTag, LongTag, FloatTag, DoubleTag,
            ByteArrayTag, StringTag, ListTag, CompoundTag, IntArrayTag, LongArrayTag {
        byte type();

        Tag deepCopy();
    }

    public record ByteTag(byte value) implements Tag {
        @Override
        public byte type() {
            return BYTE;
        }

        @Override
        public Tag deepCopy() {
            return this;
        }
    }

    public record ShortTag(short value) implements Tag {
        @Override
        public byte type() {
            return SHORT;
        }

        @Override
        public Tag deepCopy() {
            return this;
        }
    }

    public record IntTag(int value) implements Tag {
        @Override
        public byte type() {
            return INT;
        }

        @Override
        public Tag deepCopy() {
            return this;
        }
    }

    public record LongTag(long value) implements Tag {
        @Override
        public byte type() {
            return LONG;
        }

        @Override
        public Tag deepCopy() {
            return this;
        }
    }

    public record FloatTag(float value) implements Tag {
        @Override
        public byte type() {
            return FLOAT;
        }

        @Override
        public Tag deepCopy() {
            return this;
        }
    }

    public record DoubleTag(double value) implements Tag {
        @Override
        public byte type() {
            return DOUBLE;
        }

        @Override
        public Tag deepCopy() {
            return this;
        }
    }

    public static final class ByteArrayTag implements Tag {
        private final byte[] value;

        public ByteArrayTag(byte[] value) {
            this.value = value.clone();
        }

        public byte[] value() {
            return value.clone();
        }

        byte[] rawValue() {
            return value;
        }

        @Override
        public byte type() {
            return BYTE_ARRAY;
        }

        @Override
        public Tag deepCopy() {
            return new ByteArrayTag(value);
        }
    }

    public record StringTag(String value) implements Tag {
        public StringTag {
            if (value == null) {
                throw new IllegalArgumentException("NBT string cannot be null");
            }
        }

        @Override
        public byte type() {
            return STRING;
        }

        @Override
        public Tag deepCopy() {
            return this;
        }
    }

    public static final class ListTag implements Tag {
        private final byte elementType;
        private final ArrayList<Tag> values;

        public ListTag(byte elementType, List<? extends Tag> values) {
            requireType(elementType, true);
            if (elementType == END && !values.isEmpty()) {
                throw new IllegalArgumentException("Non-empty NBT list cannot use END element type");
            }
            this.elementType = elementType;
            this.values = new ArrayList<>(values.size());
            for (Tag value : values) {
                if (value == null || value.type() != elementType) {
                    throw new IllegalArgumentException("Mixed or null NBT list element");
                }
                this.values.add(value);
            }
        }

        public byte elementType() {
            return elementType;
        }

        public int size() {
            return values.size();
        }

        public Tag get(int index) {
            return values.get(index);
        }

        public Tag set(int index, Tag value) {
            if (value == null || value.type() != elementType) {
                throw new IllegalArgumentException("Wrong or null NBT list element");
            }
            return values.set(index, value);
        }

        public Tag remove(int index) {
            return values.remove(index);
        }

        public List<Tag> values() {
            return Collections.unmodifiableList(values);
        }

        @Override
        public byte type() {
            return LIST;
        }

        @Override
        public Tag deepCopy() {
            List<Tag> copied = new ArrayList<>(values.size());
            for (Tag value : values) {
                copied.add(value.deepCopy());
            }
            return new ListTag(elementType, copied);
        }
    }

    public static final class CompoundTag implements Tag {
        private final LinkedHashMap<String, Tag> values;

        public CompoundTag() {
            this.values = new LinkedHashMap<>();
        }

        public CompoundTag(Map<String, ? extends Tag> values) {
            this();
            for (Map.Entry<String, ? extends Tag> entry : values.entrySet()) {
                put(entry.getKey(), entry.getValue());
            }
        }

        public Tag get(String key) {
            return values.get(key);
        }

        public CompoundTag getCompound(String key) {
            Tag tag = values.get(key);
            return tag instanceof CompoundTag compound ? compound : null;
        }

        public ListTag getList(String key) {
            Tag tag = values.get(key);
            return tag instanceof ListTag list ? list : null;
        }

        public String getString(String key) {
            Tag tag = values.get(key);
            return tag instanceof StringTag string ? string.value() : null;
        }

        public void put(String key, Tag value) {
            if (key == null || value == null || value.type() == END) {
                throw new IllegalArgumentException("Invalid compound entry");
            }
            values.put(key, value);
        }

        public Tag remove(String key) {
            return values.remove(key);
        }

        public boolean contains(String key) {
            return values.containsKey(key);
        }

        public boolean isEmpty() {
            return values.isEmpty();
        }

        public int size() {
            return values.size();
        }

        public Set<String> keys() {
            return Collections.unmodifiableSet(values.keySet());
        }

        public Map<String, Tag> values() {
            return Collections.unmodifiableMap(values);
        }

        @Override
        public byte type() {
            return COMPOUND;
        }

        @Override
        public Tag deepCopy() {
            CompoundTag copied = new CompoundTag();
            for (Map.Entry<String, Tag> entry : values.entrySet()) {
                copied.put(entry.getKey(), entry.getValue().deepCopy());
            }
            return copied;
        }
    }

    public static final class IntArrayTag implements Tag {
        private final int[] value;

        public IntArrayTag(int[] value) {
            this.value = value.clone();
        }

        public int[] value() {
            return value.clone();
        }

        int[] rawValue() {
            return value;
        }

        @Override
        public byte type() {
            return INT_ARRAY;
        }

        @Override
        public Tag deepCopy() {
            return new IntArrayTag(value);
        }
    }

    public static final class LongArrayTag implements Tag {
        private final long[] value;

        public LongArrayTag(long[] value) {
            this.value = value.clone();
        }

        public long[] value() {
            return value.clone();
        }

        long[] rawValue() {
            return value;
        }

        @Override
        public byte type() {
            return LONG_ARRAY;
        }

        @Override
        public Tag deepCopy() {
            return new LongArrayTag(value);
        }
    }

    public static Root readRoot(InputStream input, Limits limits) throws IOException {
        // Do not add a buffering layer here. RegionFile performs a trailing-byte check on the
        // caller-owned decompression stream after this method returns. A private buffered stream
        // could prefetch and hide malformed bytes from that check.
        CountingInputStream counted = new CountingInputStream(input, limits.maxBytes());
        DataInputStream data = new DataInputStream(counted);
        byte type = data.readByte();
        requireReadableType(type, false);
        if (type == END) {
            throw new IOException("NBT root cannot be END");
        }
        String name = readString(data, limits);
        Tag tag = readPayload(data, type, limits, 1);
        return new Root(name, tag);
    }

    public static void writeRoot(Root root, OutputStream output) throws IOException {
        DataOutputStream data = new DataOutputStream(new BufferedOutputStream(output));
        data.writeByte(root.tag().type());
        writeString(data, root.name());
        writePayload(data, root.tag());
        data.flush();
    }

    public static byte[] writeRootToBytes(Root root) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        writeRoot(root, output);
        return output.toByteArray();
    }

    public static String semanticSha256(Tag tag) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
        OutputStream sink = new java.security.DigestOutputStream(OutputStream.nullOutputStream(), digest);
        writeRoot(new Root("", tag), sink);
        return HexFormat.of().formatHex(digest.digest());
    }

    private static Tag readPayload(DataInputStream data, byte type, Limits limits, int depth) throws IOException {
        if (depth > limits.maxDepth()) {
            throw new IOException("NBT exceeds maximum depth " + limits.maxDepth());
        }
        return switch (type) {
            case BYTE -> new ByteTag(data.readByte());
            case SHORT -> new ShortTag(data.readShort());
            case INT -> new IntTag(data.readInt());
            case LONG -> new LongTag(data.readLong());
            case FLOAT -> new FloatTag(data.readFloat());
            case DOUBLE -> new DoubleTag(data.readDouble());
            case BYTE_ARRAY -> {
                int length = readLength(data, limits, "byte array");
                byte[] value = new byte[length];
                data.readFully(value);
                yield new ByteArrayTag(value);
            }
            case STRING -> new StringTag(readString(data, limits));
            case LIST -> {
                byte elementType = data.readByte();
                requireReadableType(elementType, true);
                int length = readLength(data, limits, "list");
                if (elementType == END && length != 0) {
                    throw new IOException("NBT list with END type must be empty");
                }
                List<Tag> values = new ArrayList<>(length);
                for (int index = 0; index < length; index++) {
                    values.add(readPayload(data, elementType, limits, depth + 1));
                }
                yield new ListTag(elementType, values);
            }
            case COMPOUND -> {
                CompoundTag compound = new CompoundTag();
                int entries = 0;
                while (true) {
                    byte childType = data.readByte();
                    if (childType == END) {
                        break;
                    }
                    requireReadableType(childType, false);
                    if (++entries > limits.maxCollectionLength()) {
                        throw new IOException("NBT compound exceeds entry limit");
                    }
                    String name = readString(data, limits);
                    if (compound.contains(name)) {
                        throw new IOException("Duplicate NBT compound key: " + name);
                    }
                    compound.put(name, readPayload(data, childType, limits, depth + 1));
                }
                yield compound;
            }
            case INT_ARRAY -> {
                int length = readLength(data, limits, "int array");
                int[] value = new int[length];
                for (int index = 0; index < length; index++) {
                    value[index] = data.readInt();
                }
                yield new IntArrayTag(value);
            }
            case LONG_ARRAY -> {
                int length = readLength(data, limits, "long array");
                long[] value = new long[length];
                for (int index = 0; index < length; index++) {
                    value[index] = data.readLong();
                }
                yield new LongArrayTag(value);
            }
            default -> throw new IOException("Unsupported NBT type " + type);
        };
    }

    private static void writePayload(DataOutputStream data, Tag tag) throws IOException {
        switch (tag) {
            case ByteTag value -> data.writeByte(value.value());
            case ShortTag value -> data.writeShort(value.value());
            case IntTag value -> data.writeInt(value.value());
            case LongTag value -> data.writeLong(value.value());
            case FloatTag value -> data.writeFloat(value.value());
            case DoubleTag value -> data.writeDouble(value.value());
            case ByteArrayTag value -> {
                data.writeInt(value.rawValue().length);
                data.write(value.rawValue());
            }
            case StringTag value -> writeString(data, value.value());
            case ListTag value -> {
                data.writeByte(value.elementType());
                data.writeInt(value.size());
                for (Tag element : value.values) {
                    writePayload(data, element);
                }
            }
            case CompoundTag value -> {
                for (Map.Entry<String, Tag> entry : value.values.entrySet()) {
                    data.writeByte(entry.getValue().type());
                    writeString(data, entry.getKey());
                    writePayload(data, entry.getValue());
                }
                data.writeByte(END);
            }
            case IntArrayTag value -> {
                data.writeInt(value.rawValue().length);
                for (int element : value.rawValue()) {
                    data.writeInt(element);
                }
            }
            case LongArrayTag value -> {
                data.writeInt(value.rawValue().length);
                for (long element : value.rawValue()) {
                    data.writeLong(element);
                }
            }
        }
    }

    private static int readLength(DataInputStream data, Limits limits, String type) throws IOException {
        int length = data.readInt();
        if (length < 0 || length > limits.maxCollectionLength()) {
            throw new IOException("Invalid NBT " + type + " length " + length);
        }
        return length;
    }

    private static String readString(DataInputStream data, Limits limits) throws IOException {
        int length = data.readUnsignedShort();
        if (length > limits.maxStringBytes()) {
            throw new IOException("NBT string exceeds byte limit");
        }
        byte[] bytes = new byte[length];
        data.readFully(bytes);
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException invalidUtf8) {
            throw new IOException("Invalid UTF-8 in NBT string", invalidUtf8);
        }
    }

    private static void writeString(DataOutputStream data, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > 65_535) {
            throw new IOException("NBT string exceeds format limit");
        }
        data.writeShort(bytes.length);
        data.write(bytes);
    }

    private static void requireType(byte type, boolean allowEnd) {
        int unsigned = Byte.toUnsignedInt(type);
        if (unsigned > LONG_ARRAY || (!allowEnd && type == END)) {
            throw new IllegalArgumentException("Invalid NBT type " + unsigned);
        }
    }

    private static void requireReadableType(byte type, boolean allowEnd) throws IOException {
        int unsigned = Byte.toUnsignedInt(type);
        if (unsigned > LONG_ARRAY || (!allowEnd && type == END)) {
            throw new IOException("Invalid NBT type " + unsigned);
        }
    }

    private static final class CountingInputStream extends FilterInputStream {
        private final long limit;
        private long read;

        private CountingInputStream(InputStream input, long limit) {
            super(input);
            this.limit = limit;
        }

        @Override
        public int read() throws IOException {
            int value = super.read();
            if (value >= 0) {
                account(1);
            }
            return value;
        }

        @Override
        public int read(byte[] bytes, int offset, int length) throws IOException {
            int count = super.read(bytes, offset, length);
            if (count > 0) {
                account(count);
            }
            return count;
        }

        private void account(int count) throws IOException {
            if (read > limit - count) {
                throw new IOException("NBT exceeds maximum decoded bytes " + limit);
            }
            read += count;
        }
    }
}
