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

import static java.lang.String.format;

import java.nio.ByteBuffer;
import java.util.zip.CRC32;
import java.util.zip.Checksum;
import dev.pocketengine.runtime.providers.bedrock.eventstream.Message;

public final class Prelude {
    static final int LENGTH = 8;
    public static final int LENGTH_WITH_CRC = LENGTH + 4;

    private final int totalLength;
    private final long headersLength;

    private Prelude(int totalLength, long headersLength) {
        this.totalLength = totalLength;
        this.headersLength = headersLength;
    }

    public static Prelude decode(ByteBuffer buf) {
        buf = buf.duplicate();

        long computedPreludeCrc = computePreludeCrc(buf);

        long totalLength = Integer.toUnsignedLong(buf.getInt());
        long headersLength = Integer.toUnsignedLong(buf.getInt());
        long wirePreludeCrc = Integer.toUnsignedLong(buf.getInt());
        if (computedPreludeCrc != wirePreludeCrc) {
            throw new IllegalArgumentException(format("Prelude checksum failure: expected 0x%x, computed 0x%x",
                wirePreludeCrc, computedPreludeCrc));
        }

        // These are structural bounds. Smithy's service-side 128 KiB/24 MiB limits
        // are not client protocol-validity rules. MessageDecoder applies the app budget.
        long payloadLength = totalLength - headersLength - Message.MESSAGE_OVERHEAD;
        if (payloadLength < 0 || totalLength > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("eventstream_invalid_lengths");
        }

        return new Prelude(Math.toIntExact(totalLength), headersLength);
    }

    private static long computePreludeCrc(ByteBuffer buf) {
        byte[] prelude = new byte[Prelude.LENGTH];
        buf.duplicate().get(prelude);

        Checksum crc = new CRC32();
        crc.update(prelude, 0, prelude.length);
        return crc.getValue();
    }

    public int getTotalLength() {
        return totalLength;
    }

    public long getHeadersLength() {
        return headersLength;
    }
}

