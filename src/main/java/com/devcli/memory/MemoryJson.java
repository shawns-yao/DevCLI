package com.devcli.memory;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.module.SimpleModule;

import java.io.IOException;
import java.time.Instant;

final class MemoryJson {
    private MemoryJson() {
    }

    static ObjectMapper mapper() {
        SimpleModule time = new SimpleModule();
        time.addSerializer(Instant.class, new JsonSerializer<>() {
            @Override
            public void serialize(Instant value, JsonGenerator generator,
                                  SerializerProvider serializers) throws IOException {
                generator.writeString(value.toString());
            }
        });
        time.addDeserializer(Instant.class, new JsonDeserializer<>() {
            @Override
            public Instant deserialize(JsonParser parser,
                                       DeserializationContext context) throws IOException {
                return Instant.parse(parser.getValueAsString());
            }
        });
        return new ObjectMapper().registerModule(time);
    }
}
