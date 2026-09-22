package com.devcli.llm;

import okhttp3.Protocol;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AbstractOpenAiCompatibleClientProtocolTest {

    @Test
    void defaultsToStandardHttpNegotiation() {
        assertEquals(List.of(Protocol.HTTP_2, Protocol.HTTP_1_1),
                AbstractOpenAiCompatibleClient.resolveProtocols(null));
        assertEquals(List.of(Protocol.HTTP_2, Protocol.HTTP_1_1),
                AbstractOpenAiCompatibleClient.resolveProtocols("auto"));
    }

    @Test
    void supportsExplicitHttp11CompatibilityMode() {
        assertEquals(List.of(Protocol.HTTP_1_1),
                AbstractOpenAiCompatibleClient.resolveProtocols("HTTP_1_1"));
        assertEquals(List.of(Protocol.HTTP_1_1),
                AbstractOpenAiCompatibleClient.resolveProtocols("http-1.1"));
    }

    @Test
    void rejectsUnknownProtocolMode() {
        assertThrows(IllegalArgumentException.class,
                () -> AbstractOpenAiCompatibleClient.resolveProtocols("h2c"));
    }
}
