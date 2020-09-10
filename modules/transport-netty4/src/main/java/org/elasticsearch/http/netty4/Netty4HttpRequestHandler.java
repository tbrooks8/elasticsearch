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

package org.elasticsearch.http.netty4;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import org.elasticsearch.ExceptionsHelper;
import org.elasticsearch.common.lease.Releasables;
import org.elasticsearch.http.HttpPipeline;
import org.elasticsearch.http.HttpRequest;

class Netty4HttpRequestHandler extends SimpleChannelInboundHandler<HttpRequest> {

    private final HttpPipeline pipeline;
    private final Netty4HttpServerTransport serverTransport;

    Netty4HttpRequestHandler(HttpPipeline pipeline, Netty4HttpServerTransport serverTransport) {
        this.pipeline = pipeline;
        this.serverTransport = serverTransport;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, HttpRequest httpRequest) {
        final Netty4HttpChannel channel = ctx.channel().attr(Netty4HttpServerTransport.HTTP_CHANNEL_KEY).get();
        boolean success = false;
        try {
            pipeline.handleHttpRequest(channel, httpRequest);
            success = true;
        } finally {
            if (success == false) {
                httpRequest.release();
            }
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        ExceptionsHelper.maybeDieOnAnotherThread(cause);
        Netty4HttpChannel channel = ctx.channel().attr(Netty4HttpServerTransport.HTTP_CHANNEL_KEY).get();
        if (cause instanceof Error) {
            serverTransport.onException(channel, new Exception(cause));
        } else {
            serverTransport.onException(channel, (Exception) cause);
        }
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        Releasables.closeWhileHandlingException(pipeline);
        super.channelInactive(ctx);
    }
}
