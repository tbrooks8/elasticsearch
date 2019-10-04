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

import org.elasticsearch.common.bytes.BytesArray;
import org.elasticsearch.common.bytes.BytesReference;
import org.elasticsearch.common.bytes.CompositeBytesReference;
import org.elasticsearch.common.bytes.ReleasableBytesReference;
import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.common.lease.Releasables;

import java.io.IOException;
import java.util.ArrayList;
import java.util.function.Consumer;

public class InboundAggregator {

    private final Consumer<AggregatedMessage> messageConsumer;
    private final ArrayList<ReleasableBytesReference> contentAggregation = new ArrayList<>();
    private Header currentHeader;

    public InboundAggregator(Consumer<AggregatedMessage> messageConsumer) {
        this.messageConsumer = messageConsumer;
    }

    public void pingReceived(BytesReference ping) {
        assert ping.length() == 6;
        this.messageConsumer.accept(new AggregatedMessage(null, BytesArray.EMPTY, true));
    }

    public void headerReceived(Header header) {
        if (currentHeader != null) {
            currentHeader = null;
            throw new IllegalStateException("Header already received.");
        }

        currentHeader = header;
    }

    public void contentReceived(ReleasableBytesReference content) throws IOException {
        if (currentHeader == null) {
            throw new IllegalStateException("Received content without header");
        } else if (content.getReference().length() != 0) {
            contentAggregation.add(content);
        } else {
            BytesReference[] references = new BytesReference[contentAggregation.size()];
            int i = 0;
            for (ReleasableBytesReference reference : contentAggregation) {
                references[i++] = reference.getReference();
            }
            CompositeBytesReference aggregatedContent = new CompositeBytesReference(references);
            try {
                messageConsumer.accept(new AggregatedMessage(currentHeader, aggregatedContent, false));
            } finally {
                Releasables.close(contentAggregation);
                contentAggregation.clear();
                currentHeader = null;
            }
        }
    }
}
