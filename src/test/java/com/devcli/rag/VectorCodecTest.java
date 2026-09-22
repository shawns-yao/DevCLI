package com.devcli.rag;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class VectorCodecTest {

    @Test
    void encodesFloatVectorsAsCompactLittleEndianBlobs() {
        float[] vector = {1.25f, -2.5f, 0.0f};

        byte[] encoded = VectorCodec.encode(vector);

        assertEquals(vector.length * Float.BYTES, encoded.length);
        assertArrayEquals(vector, VectorCodec.decode(encoded));
    }

    @Test
    void rejectsMalformedBlobLength() {
        assertThrows(IllegalArgumentException.class, () -> VectorCodec.decode(new byte[3]));
    }
}
