/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * A copy of the License is located at
 *
 *  http://aws.amazon.com/apache2.0
 *
 * or in the "license" file accompanying this file. This file is distributed
 * on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

// Adapted from AWS SDK for Java v2.57.4; see ../UPSTREAM.md (or ../../UPSTREAM.md in internal/).
package dev.pocketengine.runtime.providers.bedrock.eventstream.internal;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.BufferUnderflowException;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;

public final class Utils {

    private Utils() {
    }

    static String readShortString(ByteBuffer buf) {
        int length = buf.get() & 0xFF;
        checkStringBounds(length, 255);
        if (length > buf.remaining()) throw new BufferUnderflowException();
        byte[] bytes = new byte[length];
        buf.get(bytes);
        return strictUtf8(bytes);
    }

    public static String readString(ByteBuffer buf) {
        int length = buf.getShort() & 0xFFFF;
        checkByteArrayBounds(length);
        if (length > buf.remaining()) throw new BufferUnderflowException();
        byte[] bytes = new byte[length];
        buf.get(bytes);
        return strictUtf8(bytes);
    }

    public static byte[] readBytes(ByteBuffer buf) {
        int length = buf.getShort() & 0xFFFF;
        checkByteArrayBounds(length);
        if (length > buf.remaining()) throw new BufferUnderflowException();
        byte[] bytes = new byte[length];
        buf.get(bytes);
        return bytes;
    }

    static void writeShortString(DataOutputStream dos, String string) throws IOException {
        byte[] bytes = string.getBytes(UTF_8);
        checkStringBounds(bytes.length, 255);
        dos.writeByte(bytes.length);
        dos.write(bytes);
    }

    public static void writeString(DataOutputStream dos, String string) throws IOException {
        byte[] bytes = string.getBytes(UTF_8);
        checkByteArrayBounds(bytes.length);
        writeBytes(dos, bytes);
    }

    public static void writeBytes(DataOutputStream dos, byte[] bytes) throws IOException {
        checkByteArrayBounds(bytes.length);
        dos.writeShort((short) bytes.length);
        dos.write(bytes);
    }

    public static String strictUtf8(byte[] bytes) {
        try {
            return UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException invalid) {
            throw new IllegalArgumentException("eventstream_invalid_utf8");
        }
    }

    private static void checkByteArrayBounds(int length) {
        // Header value lengths are unsigned 16-bit values; empty values are legal.
        if (length < 0 || length > 65535) throw new IllegalArgumentException("eventstream_header_value_length");
    }

    private static void checkStringBounds(int length, int maxLength) {
        if (length == 0) {
            throw new IllegalArgumentException("Strings may not be empty");
        }
        if (length > maxLength) {
            throw new IllegalArgumentException("Illegal string length: " + length);
        }
    }
}

