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

package org.elasticsearch.action.bulk;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.ActionType;
import org.elasticsearch.action.support.ActionFilters;
import org.elasticsearch.action.support.WriteResponse;
import org.elasticsearch.action.support.replication.ReplicatedWriteRequest;
import org.elasticsearch.action.support.replication.ReplicationResponse;
import org.elasticsearch.action.support.replication.TransportBatchedWriteAction;
import org.elasticsearch.action.update.UpdateHelper;
import org.elasticsearch.cluster.action.index.MappingUpdatedAction;
import org.elasticsearch.cluster.action.shard.ShardStateAction;
import org.elasticsearch.cluster.service.ClusterService;
import org.elasticsearch.common.Nullable;
import org.elasticsearch.common.concurrent.CompletableContext;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.index.shard.IndexShard;
import org.elasticsearch.indices.IndicesService;
import org.elasticsearch.threadpool.ThreadPool;
import org.elasticsearch.transport.TransportRequestOptions;
import org.elasticsearch.transport.TransportService;

import java.io.IOException;

/**
 * Performs shard-level bulk (index, delete or update) operations
 */
public class TransportBatchedShardBulkAction extends TransportBatchedWriteAction<BulkShardRequest, BulkShardRequest, BulkShardResponse> {

    public static final String ACTION_NAME = BulkAction.NAME + "[s]";
    public static final ActionType<BulkShardResponse> TYPE = new ActionType<>(ACTION_NAME, BulkShardResponse::new);

    private static final ActionListener<Void> FLUSH_DONE_MARKER = ActionListener.wrap(() -> {});
    private static final Logger logger = LogManager.getLogger(TransportBatchedShardBulkAction.class);

    private final BatchedShardExecutor batchedShardExecutor;


    @Inject
    public TransportBatchedShardBulkAction(Settings settings, TransportService transportService, ClusterService clusterService,
                                           IndicesService indicesService, ThreadPool threadPool, ShardStateAction shardStateAction,
                                           MappingUpdatedAction mappingUpdatedAction, UpdateHelper updateHelper,
                                           ActionFilters actionFilters) {
        super(settings, ACTION_NAME, transportService, clusterService, indicesService, threadPool, shardStateAction, actionFilters,
            BulkShardRequest::new, BulkShardRequest::new, ThreadPool.Names.WRITE, false);
        this.batchedShardExecutor = new BatchedShardExecutor(clusterService, threadPool, updateHelper, mappingUpdatedAction);
    }

    @Override
    protected TransportRequestOptions transportOptions(Settings settings) {
        return BulkAction.INSTANCE.transportOptions(settings);
    }

    @Override
    protected BulkShardResponse newResponseInstance(StreamInput in) throws IOException {
        return new BulkShardResponse(in);
    }

    @Override
    protected void shardOperationOnPrimary(BulkShardRequest request, IndexShard primary,
                                           ActionListener<PrimaryResult<BulkShardRequest, BulkShardResponse>> listener) {
        CompletableContext<BatchedShardExecutor.FlushResult> flushContext = new CompletableContext<>();

        ActionListener<BulkShardResponse> writeListener = new ActionListener<>() {
            @Override
            public void onResponse(BulkShardResponse response) {
                listener.onResponse(new WritePrimaryResult<>(request, response));
            }

            @Override
            public void onFailure(Exception e) {
                listener.onFailure(e);
            }
        };

        ActionListener<BatchedShardExecutor.FlushResult> flushListener = new ActionListener<>() {
            @Override
            public void onResponse(BatchedShardExecutor.FlushResult flushResult) {
                assert flushContext.isDone() == false;
                flushContext.complete(flushResult);
            }

            @Override
            public void onFailure(Exception e) {
                assert flushContext.isDone() == false;
                flushContext.completeExceptionally(e);
            }
        };

        batchedShardExecutor.primary(request, primary, writeListener, flushListener);
    }

    /**
     * Result of taking the action on the primary.
     *
     * NOTE: public for testing
     */
    public static class WritePrimaryResult<ReplicaRequest extends ReplicatedWriteRequest<ReplicaRequest>,
        Response extends ReplicationResponse & WriteResponse> extends PrimaryResult<ReplicaRequest, Response> {

        public WritePrimaryResult(ReplicaRequest request, @Nullable Response finalResponse) {
            super(request, finalResponse, null);
        }

        @Override
        public void runPostReplicationActions(ActionListener<Void> listener) {

        }
    }


    @Override
    public WriteReplicaResult shardOperationOnReplica(BulkShardRequest request, IndexShard replica) {
        throw new AssertionError("Override the async method, so the synchronous method should not be called");
    }

    @Override
    protected void shardOperationOnReplica(BulkShardRequest request, IndexShard replica, ActionListener<ReplicaResult> listener) {

    }

}
