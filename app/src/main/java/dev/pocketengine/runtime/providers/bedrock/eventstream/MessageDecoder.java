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
package dev.pocketengine.runtime.providers.bedrock.eventstream;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;
import dev.pocketengine.runtime.providers.bedrock.eventstream.internal.Prelude;

/**
 * A simple decoder that accumulates chunks of bytes and emits eventstream
 * messages. Instances of this class are not thread-safe.
 */
public final class MessageDecoder {

    /**
     * Initial buffer size is 2kB. Will grow as needed to accommodate larger messages.
     */
    private static final int INITIAL_BUFFER_SIZE = 2048;
    private static final int DEFAULT_MAX_MESSAGE_BYTES = 512 * 1024;
    private final int maxMessageBytes;

    private final Consumer<Message> messageConsumer;
    private List<Message> bufferedOutput;
    private ByteBuffer buf;
    private Prelude currentPrelude;

    /**
     * Creates a {@code MessageDecoder} instance that will buffer messages internally as they are decoded. Decoded
     * messages can be obtained by calling {@link #getDecodedMessages()}.
     */
    public MessageDecoder() {
        this(DEFAULT_MAX_MESSAGE_BYTES);
    }

    public MessageDecoder(int maxMessageBytes) {
        if (maxMessageBytes < Message.MESSAGE_OVERHEAD) throw new IllegalArgumentException("eventstream_message_limit");
        this.maxMessageBytes = maxMessageBytes;
        this.messageConsumer = message -> this.bufferedOutput.add(message);
        this.bufferedOutput = new ArrayList<>();
        this.buf = ByteBuffer.allocate(Math.min(INITIAL_BUFFER_SIZE, maxMessageBytes));
    }

    /**
     * Creates a {@code MessageDecoder} instance that will publish messages incrementally to the supplied {@code
     * messageConsumer} as they are decoded. The resulting instance does not support the {@link #getDecodedMessages()}
     * operation, and will throw an exception if it is invoked.
     *
     * @param messageConsumer a function that consumes {@link Message} instances
     */
    public MessageDecoder(Consumer<Message> messageConsumer) {
        this(messageConsumer, DEFAULT_MAX_MESSAGE_BYTES);
    }

    /**
     * Application resource budget, checked before an advertised message can allocate memory.
     */
    public MessageDecoder(Consumer<Message> messageConsumer, int maxMessageBytes) {
        if (maxMessageBytes < Message.MESSAGE_OVERHEAD) throw new IllegalArgumentException("eventstream_message_limit");
        this.maxMessageBytes = maxMessageBytes;
        this.messageConsumer = java.util.Objects.requireNonNull(messageConsumer);
        this.buf = ByteBuffer.allocate(Math.min(INITIAL_BUFFER_SIZE, maxMessageBytes));
        this.bufferedOutput = null;
    }

    /**
     * Returns {@link Message} instances that have been decoded since this method was last invoked. Note that this
     * method is only supported if this decoder was not configured to use a custom message consumer.
     *
     * @return all messages decoded since the last invocation of this method
     */
    public List<Message> getDecodedMessages() {
        if (bufferedOutput == null) {
            throw new IllegalStateException("");
        }
        List<Message> ret = bufferedOutput;
        bufferedOutput = new ArrayList<>();
        return Collections.unmodifiableList(ret);
    }

    public void feed(byte[] bytes) {
        feed(ByteBuffer.wrap(bytes));
    }

    public void feed(byte[] bytes, int offset, int length) {
        feed(ByteBuffer.wrap(bytes, offset, length));
    }

    /**
     * Feed the contents of the given {@link ByteBuffer} into this decoder. Messages will be incrementally decoded and
     * buffered or published to the message consumer (depending on configuration).
     *
     * @param byteBuffer a {@link ByteBuffer} whose entire contents will be read into the decoder's internal buffer
     * @return this {@code MessageDecoder} instance
     */
    public MessageDecoder feed(ByteBuffer byteBuffer) {
        int bytesToRead = byteBuffer.remaining();
        int bytesConsumed = 0;
        while (bytesConsumed < bytesToRead) {
            ByteBuffer readView = updateReadView();
            if (currentPrelude == null) {
                // Validate the complete 12-byte prelude before allocating the advertised frame.
                int numBytesToWrite = Math.min(Prelude.LENGTH_WITH_CRC - readView.remaining(),
                    bytesToRead - bytesConsumed);

                feedBuf(byteBuffer, numBytesToWrite);

                bytesConsumed += numBytesToWrite;
                readView = updateReadView();

                // Have enough data to decode the prelude
                if (readView.remaining() >= Prelude.LENGTH_WITH_CRC) {
                    currentPrelude = Prelude.decode(readView.duplicate());
                    if (currentPrelude.getTotalLength() > maxMessageBytes) throw new LimitExceeded();
                    if (buf.capacity() < currentPrelude.getTotalLength()) {
                        // Don't have enough capacity to hold this message, grow the buffer
                        buf = ByteBuffer.allocate(currentPrelude.getTotalLength());
                        buf.put(readView);
                        readView = updateReadView();
                    }
                }
            }
            // We might not have received enough data to decode the prelude so check for null again
            if (currentPrelude != null) {
                // Only write up to what we need to decode the next message
                int numBytesToWrite = Math.min(currentPrelude.getTotalLength() - readView.remaining(),
                    bytesToRead - bytesConsumed);

                feedBuf(byteBuffer, numBytesToWrite);
                bytesConsumed += numBytesToWrite;
                readView = updateReadView();

                // If we have enough data to decode the message do so and reset the buffer for the next message
                if (readView.remaining() >= currentPrelude.getTotalLength()) {
                    messageConsumer.accept(Message.decode(currentPrelude, readView));
                    buf.clear();
                    currentPrelude = null;
                }
            }
        }

        return this;
    }

    /** Bytes needed before the next decode boundary; prevents consuming a later failed frame
     * before the caller has observed an earlier valid payload. */
    public int bytesNeeded() {
        return (currentPrelude == null ? Prelude.LENGTH_WITH_CRC : currentPrelude.getTotalLength()) - buf.position();
    }

    /** EOF is only clean if no part of the next prelude or message has been received. */
    public boolean atMessageBoundary() {
        return currentPrelude == null && buf.position() == 0;
    }

    public static final class LimitExceeded extends IllegalArgumentException {
        public LimitExceeded() { super("eventstream_message_limit"); }
    }

    private void feedBuf(ByteBuffer byteBuffer, int numBytesToWrite) {
        buf.put((ByteBuffer) byteBuffer.duplicate().limit(byteBuffer.position() + numBytesToWrite));
        byteBuffer.position(byteBuffer.position() + numBytesToWrite);
    }

    private ByteBuffer updateReadView() {
        return (ByteBuffer) buf.duplicate().flip();
    }

    /**
     * To be used by tests only.
     */
    int currentBufferSize() {
        return buf.capacity();
    }
}

