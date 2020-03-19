/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.elasticsearch.transport;

import org.elasticsearch.common.bytes.ReleasableBytesReference;
import org.elasticsearch.common.lease.Releasable;
import org.elasticsearch.common.lease.Releasables;

import java.io.IOException;
import java.util.ArrayList;
import java.util.function.BiConsumer;

public class InboundPipeline implements Releasable {

    private static final AggregatedMessage PING_MESSAGE = new AggregatedMessage(null, null, true);

    private final InboundDecoder decoder;
    private final InboundAggregator aggregator;
    private final BiConsumer<TcpChannel, AggregatedMessage> messageHandler;

    public InboundPipeline(InboundDecoder decoder, InboundAggregator aggregator,
                           BiConsumer<TcpChannel, AggregatedMessage> messageHandler) {
        this.decoder = decoder;
        this.aggregator = aggregator;
        this.messageHandler = messageHandler;
    }

    @Override
    public void close() {
        Releasables.close(decoder, aggregator);
    }

    public int handleBytes(TcpChannel channel, ReleasableBytesReference reference) throws IOException {
        int bytesConsumed = 0;
        final ArrayList<Object> fragments = new ArrayList<>();

        boolean continueHandling = true;

        while (continueHandling) {
            boolean continueDecoding = true;
            while (continueDecoding) {
                final int remaining = reference.length() - bytesConsumed;
                if (remaining != 0) {
                    try (ReleasableBytesReference slice = reference.retainedSlice(bytesConsumed, remaining)) {
                        final int bytesDecoded = decoder.handle(slice, fragments::add);
                        if (bytesDecoded != 0) {
                            bytesConsumed += bytesDecoded;
                            if (fragments.isEmpty() == false && endOfMessage(fragments.get(fragments.size() - 1))) {
                                continueDecoding = false;
                            }
                        } else {
                            continueDecoding = false;
                        }
                    }
                } else {
                    continueDecoding = false;
                }
            }

            if (fragments.isEmpty()) {
                continueHandling = false;
            } else {
                try {
                    forwardFragments(channel, fragments);
                } finally {
                    for (Object fragment : fragments) {
                        if (fragment instanceof ReleasableBytesReference) {
                            ((ReleasableBytesReference) fragment).close();
                        }
                    }
                    fragments.clear();
                }
            }
        }


        return bytesConsumed;
    }

    private void forwardFragments(TcpChannel channel, ArrayList<Object> fragments) {
        for (Object fragment : fragments) {
            if (fragment instanceof Header) {
                assert aggregator.isAggregating() == false;
                aggregator.headerReceived((Header) fragment);
            } else if (fragment == InboundDecoder.PING) {
                assert aggregator.isAggregating() == false;
                messageHandler.accept(channel, PING_MESSAGE);
            } else if (fragment == InboundDecoder.END_CONTENT) {
                assert aggregator.isAggregating();
                final AggregatedMessage aggregated = aggregator.finishAggregation();
                try (Releasable toClose = aggregated.getContent()) {
                    messageHandler.accept(channel, aggregated);
                }
            } else {
                assert aggregator.isAggregating();
                assert fragment instanceof ReleasableBytesReference;
                aggregator.aggregate((ReleasableBytesReference) fragment);
            }
        }
    }

    private boolean endOfMessage(Object fragment) {
        return fragment == InboundDecoder.PING || fragment == InboundDecoder.END_CONTENT;
    }
}
