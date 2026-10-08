package id.mydev.peoplecore.common.command;

import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DefaultCommandResultSerializerTest {

    record SampleDto(Long id, String name, boolean active) {}

    private DefaultCommandResultSerializer serializer;

    @BeforeEach
    void setUp() {
        serializer = new DefaultCommandResultSerializer(new ObjectMapper());
    }

    @Test
    void serializesAndDeserializesPrimitiveAndSimpleTypes() {
        assertNull(serializer.serialize(null));
        assertEquals("\"hello\"", serializer.serialize("hello"));
        assertEquals("123", serializer.serialize(123L));
        assertEquals("true", serializer.serialize(true));

        assertThrows(NullPointerException.class, () -> serializer.deserialize(null, String.class));
        assertThrows(NullPointerException.class, () -> serializer.deserialize("123", null));
        assertNull(serializer.deserialize("123", Void.class));

        assertEquals("hello", serializer.deserialize("\"hello\"", String.class));
        assertEquals(123L, serializer.deserialize("123", Long.class));
        assertEquals(true, serializer.deserialize("true", Boolean.class));
    }

    @Test
    void serializesAndDeserializesComplexRecordDto() {
        SampleDto original = new SampleDto(10L, "John Doe", true);
        String json = serializer.serialize(original);
        assertNotNull(json);

        SampleDto roundTrip = serializer.deserialize(json, SampleDto.class);
        assertEquals(original, roundTrip);
        assertEquals(10L, roundTrip.id());
        assertEquals("John Doe", roundTrip.name());
        assertEquals(true, roundTrip.active());
    }

    @Test
    void rejectsMalformedPayloadForDto() {
        assertThrows(IllegalArgumentException.class, () ->
            serializer.deserialize("{malformed-json", SampleDto.class)
        );
    }
}
