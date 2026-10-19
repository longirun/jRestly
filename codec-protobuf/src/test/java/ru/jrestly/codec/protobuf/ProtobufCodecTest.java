package ru.jrestly.codec.protobuf;

import com.google.protobuf.ByteString;
import com.google.protobuf.Descriptors;
import com.google.protobuf.Message;
import com.google.protobuf.Parser;
import com.google.protobuf.Struct;
import com.google.protobuf.Timestamp;
import com.google.protobuf.UnknownFieldSet;
import com.google.protobuf.Value;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Unit contract for {@link ProtobufCodec} (ADR-0074): family claim,
 * parser resolution (generated {@code PARSER} field, hand-written
 * {@code getDefaultInstance()} fallback, fail-fast), and the binary
 * {@code prettyPrint}.
 */
@DisplayName("ADR-0074: ProtobufCodec unit contract")
class ProtobufCodecTest {

    private final ProtobufCodec codec = new ProtobufCodec();

    /** The canonical Timestamp parser: the single object both resolution paths must converge on. */
    private static final Parser<? extends Message> TIMESTAMP_PARSER =
            Timestamp.getDefaultInstance().getParserForType();

    private static Timestamp fixtureTimestamp() {
        return Timestamp.newBuilder()
                .setSeconds(1_700_000_000L)
                .setNanos(123_456_789)
                .build();
    }

    @Test
    @DisplayName("claims application/x-protobuf, application/protobuf and *+protobuf, and nothing else")
    void supportsClaimsTheProtobufFamily() {
        assertTrue(codec.supports("application/x-protobuf"));
        assertTrue(codec.supports("application/protobuf"));
        assertTrue(codec.supports("application/vnd.company.v1+protobuf"));
        assertFalse(codec.supports("application/proto"));
        assertFalse(codec.supports("application/x-proto"));
        assertFalse(codec.supports("application/protobuf2"));
        assertFalse(codec.supports("application/grpc+proto"));
    }

    @Test
    @DisplayName("a serialized Message parses back into the generated type")
    void deserializesPayloadIntoGeneratedMessage() {
        Timestamp original = fixtureTimestamp();
        byte[] wire = codec.serialize(original, "application/x-protobuf");
        assertArrayEquals(original.toByteArray(), wire);

        Timestamp bound = codec.deserialize(wire, Timestamp.class, "application/x-protobuf");
        assertEquals(1_700_000_000L, bound.getSeconds());
        assertEquals(123_456_789, bound.getNanos());
    }

    @Test
    @DisplayName("nested well-known types (Struct/Value maps) round-trip")
    void deserializesStructWithNestedValues() {
        Struct original = Struct.newBuilder()
                .putFields("name", Value.newBuilder().setStringValue("Anton").build())
                .putFields("age", Value.newBuilder().setNumberValue(42).build())
                .build();
        byte[] wire = original.toByteArray();

        Struct bound = codec.deserialize(wire, Struct.class, "application/protobuf");
        assertEquals("Anton", bound.getFieldsOrThrow("name").getStringValue());
        assertEquals(42d, bound.getFieldsOrThrow("age").getNumberValue());
    }

    @Test
    @DisplayName("a null or empty payload deserializes to null (SPI contract)")
    void emptyAndNullPayloadsDeserializeToNull() {
        assertNull(codec.deserialize(null, Timestamp.class, "application/x-protobuf"));
        assertNull(codec.deserialize(new byte[0], Timestamp.class, "application/x-protobuf"));
    }

    @Test
    @DisplayName("a corrupt payload fails with an unchecked exception naming the target type")
    void corruptPayloadThrowsUncheckedIOException() {
        UncheckedIOException ex = assertThrows(UncheckedIOException.class,
                () -> codec.deserialize(new byte[]{(byte) 0xFF, (byte) 0x01}, Timestamp.class,
                        "application/x-protobuf"));
        assertTrue(ex.getMessage().contains(Timestamp.class.getName()),
                "the message must name the target type: " + ex.getMessage());
    }

    @Test
    @DisplayName("a non-Message target class fails fast instead of mis-binding")
    void nonMessageTargetFailsFast() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> codec.deserialize(new byte[]{1, 2, 3}, String.class, "application/x-protobuf"));
        assertTrue(ex.getMessage().contains(String.class.getName()),
                "the message must name the offending class: " + ex.getMessage());
    }

    @Test
    @DisplayName("a non-Class target type (e.g. a parameterized List) fails fast")
    void nonClassTargetFailsFast() {
        ParameterizedType listOfObjects = new ParameterizedType() {
            @Override
            public Type[] getActualTypeArguments() {
                return new Type[] { Object.class };
            }

            @Override
            public Type getRawType() {
                return List.class;
            }

            @Override
            public Type getOwnerType() {
                return null;
            }
        };
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> codec.deserialize(new byte[]{1, 2, 3}, listOfObjects, "application/x-protobuf"));
        assertTrue(ex.getMessage().contains("class"),
                "the message must explain the expectation: " + ex.getMessage());
    }

    @Test
    @DisplayName("a Message value serializes to exactly its toByteArray() wire bytes")
    void serializesMessageToWireBytes() {
        byte[] wire = codec.serialize(fixtureTimestamp(), "application/protobuf");
        assertArrayEquals(fixtureTimestamp().toByteArray(), wire);
    }

    @Test
    @DisplayName("serializing null yields the empty payload (SPI contract)")
    void serializeNullValueYieldsEmptyPayload() {
        assertArrayEquals(new byte[0], codec.serialize(null, "application/x-protobuf"));
    }

    @Test
    @DisplayName("a non-Message value fails fast instead of being silently skipped")
    void serializeNonMessageFailsFast() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> codec.serialize("plain string", "application/x-protobuf"));
        assertTrue(ex.getMessage().contains(String.class.getName()),
                "the message must name the offending class: " + ex.getMessage());
    }

    @Test
    @DisplayName("the protoc-generated public PARSER field is the first resolution path and gets cached")
    void parserIsCachedAcrossCalls() {
        Parser<?> first = codec.parserFor(FakeGeneratedMessage.class);
        assertSame(TIMESTAMP_PARSER, first, "resolution must find the public PARSER field");
        assertSame(first, codec.parserFor(FakeGeneratedMessage.class), "the parser must be cached");
        codec.deserialize(fixtureTimestamp().toByteArray(), Timestamp.class, "application/x-protobuf");
        assertSame(TIMESTAMP_PARSER, codec.parserFor(Timestamp.class),
                "well-known types (private PARSER) must resolve via the static factory and be cached");
    }

    @Test
    @DisplayName("a hand-written Message without PARSER resolves via static getDefaultInstance()")
    void handWrittenMessageResolvesViaStaticDefaultInstance() {
        assertSame(TIMESTAMP_PARSER, codec.parserFor(StubWithFactory.class),
                "resolution must fall back to the static default instance + getParserForType()");
    }

    @Test
    @DisplayName("a Message with neither PARSER nor a static factory fails fast with an actionable message")
    void messageWithoutParserOrFactoryFailsFast() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> codec.parserFor(StubNoFactory.class));
        assertTrue(ex.getMessage().contains("PARSER"),
                "the message must explain what is expected: " + ex.getMessage());
    }

    @Test
    @DisplayName("prettyPrint renders the binary payload as hex, 16 bytes per line")
    void prettyPrintRendersHexWithLineWrapping() {
        byte[] payload = new byte[17];
        for (int i = 0; i < payload.length; i++) {
            payload[i] = (byte) i;
        }
        assertEquals("00 01 02 03 04 05 06 07 08 09 0a 0b 0c 0d 0e 0f\n10",
                codec.prettyPrint(payload, "application/x-protobuf", null));
        assertNull(codec.prettyPrint(null, "application/x-protobuf", null));
        assertEquals("", codec.prettyPrint(new byte[0], "application/x-protobuf", null));
    }

    /**
     * Mimics the protoc output shape: a class whose public surface carries the
     * {@code public static final PARSER} field (what user-generated messages
     * always expose — unlike the well-known types, whose {@code PARSER} is
     * private). Not a {@code Message} itself: this test drives the
     * {@code parserFor} resolution path directly.
     */
    static final class FakeGeneratedMessage {
        public static final Parser<? extends Message> PARSER = TIMESTAMP_PARSER;

        private FakeGeneratedMessage() {
        }
    }

    /**
     * A hand-written {@code Message} with a static {@code getDefaultInstance()}
     * but no {@code PARSER} field: exercises the fallback resolution branch.
     */
    static final class StubWithFactory extends StubBase {
        public static StubWithFactory getDefaultInstance() {
            return new StubWithFactory();
        }
    }

    /**
     * The same contract surface without any static
     * {@code getDefaultInstance()}: resolution must fail fast.
     */
    static final class StubNoFactory extends StubBase {
    }

    /** Shared skeleton: the full {@code Message} surface, no parser field. */
    abstract static class StubBase implements Message {
        @Override
        public Parser<? extends Message> getParserForType() {
            return TIMESTAMP_PARSER;
        }

        @Override
        public Message.Builder newBuilderForType() {
            throw new UnsupportedOperationException();
        }

        @Override
        public Message.Builder toBuilder() {
            throw new UnsupportedOperationException();
        }

        @Override
        public Message getDefaultInstanceForType() {
            return this;
        }

        @Override
        public List<String> findInitializationErrors() {
            return List.of();
        }

        @Override
        public String getInitializationErrorString() {
            return null;
        }

        @Override
        public Descriptors.Descriptor getDescriptorForType() {
            return Timestamp.getDescriptor();
        }

        @Override
        public Map<Descriptors.FieldDescriptor, Object> getAllFields() {
            return Map.of();
        }

        @Override
        public boolean hasOneof(Descriptors.OneofDescriptor oneof) {
            return false;
        }

        @Override
        public Descriptors.FieldDescriptor getOneofFieldDescriptor(Descriptors.OneofDescriptor oneof) {
            return null;
        }

        @Override
        public boolean hasField(Descriptors.FieldDescriptor field) {
            return false;
        }

        @Override
        public Object getField(Descriptors.FieldDescriptor field) {
            return null;
        }

        @Override
        public int getRepeatedFieldCount(Descriptors.FieldDescriptor field) {
            return 0;
        }

        @Override
        public Object getRepeatedField(Descriptors.FieldDescriptor field, int index) {
            return null;
        }

        @Override
        public UnknownFieldSet getUnknownFields() {
            return UnknownFieldSet.getDefaultInstance();
        }

        @Override
        public boolean isInitialized() {
            return true;
        }

        @Override
        public void writeTo(com.google.protobuf.CodedOutputStream output) {
        }

        @Override
        public int getSerializedSize() {
            return 0;
        }

        @Override
        public ByteString toByteString() {
            return ByteString.EMPTY;
        }

        @Override
        public byte[] toByteArray() {
            return new byte[0];
        }

        @Override
        public void writeTo(OutputStream output) {
        }

        @Override
        public void writeDelimitedTo(OutputStream output) {
        }
    }
}
