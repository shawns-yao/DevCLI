package com.devcli.rag;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** SQLite 中 float32 向量的稳定二进制编码。 */
final class VectorCodec {
    private VectorCodec() {
    }

    static byte[] encode(float[] vector) {
        if (vector == null) {
            return null;
        }
        ByteBuffer buffer = ByteBuffer.allocate(vector.length * Float.BYTES)
                .order(ByteOrder.LITTLE_ENDIAN);
        for (float value : vector) {
            buffer.putFloat(value);
        }
        return buffer.array();
    }

    static float[] decode(byte[] encoded) {
        if (encoded == null) {
            return null;
        }
        if (encoded.length % Float.BYTES != 0) {
            throw new IllegalArgumentException("向量 BLOB 长度必须是 4 的倍数");
        }
        ByteBuffer buffer = ByteBuffer.wrap(encoded).order(ByteOrder.LITTLE_ENDIAN);
        float[] vector = new float[encoded.length / Float.BYTES];
        for (int i = 0; i < vector.length; i++) {
            vector[i] = buffer.getFloat();
        }
        return vector;
    }
}
