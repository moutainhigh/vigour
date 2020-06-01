/*
 * Copyright (C) 2012-2016 Facebook, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.r.framework.thrift.server.core.server.netty.handler;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.util.AttributeKey;
import io.netty.util.Timeout;
import io.netty.util.Timer;
import io.netty.util.TimerTask;
import org.apache.thrift.TApplicationException;
import org.apache.thrift.TException;
import org.apache.thrift.TProcessor;
import org.apache.thrift.protocol.TBinaryProtocol;
import org.apache.thrift.protocol.TMessage;
import org.apache.thrift.protocol.TMessageType;
import org.apache.thrift.protocol.TProtocol;
import org.r.framework.thrift.common.netty.NettyTransport;
import org.r.framework.thrift.common.netty.ThriftMessage;
import org.r.framework.thrift.server.core.server.netty.core.TDuplexProtocolFactory;
import org.r.framework.thrift.server.core.server.netty.core.TProtocolPair;
import org.r.framework.thrift.server.core.server.netty.core.TTransportPair;
import org.r.framework.thrift.server.core.wrapper.ServerDef;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Dispatch DefaultTransport to the TProcessor and write output back.
 * <p>
 * Note that all current async thrift clients are capable of sending multiple requests at once
 * but not capable of handling out-of-order responses to those requests, so this dispatcher
 * sends the requests in order. (Eventually this will be conditional on a flag in the thrift
 * message header for future async clients that can handle out-of-order responses).
 * <p>
 * 分派TNiftyTransport到TProcessor并进行处理和响应
 * <p>
 * 注意：所有的thrift同步客户端都有能力同一时间发送多个请求，但是没有能力处理无序的响应。
 * 因此，此分派器进行有序的响应
 * 未来可能会通过消息头的标志位进行扩展，标志客户端是否能处理无序的响应
 */
// TODO: 20-5-6 类逻辑异常复杂，应该进行重构优化
public class MessageDispatcher extends ChannelInboundHandlerAdapter {


    private final TProcessor processor;
    private final Executor exe;
    private final long taskTimeoutMillis;
    private final Timer taskTimeoutTimer;
    private final long queueTimeoutMillis;
    private final int queuedResponseLimit;
    private final Map<Integer, ThriftMessage> responseMap = new HashMap<>();
    private final AtomicInteger dispatcherSequenceId = new AtomicInteger(0);
    private final AtomicInteger lastResponseWrittenId = new AtomicInteger(0);
    private final TDuplexProtocolFactory duplexProtocolFactory;

    public MessageDispatcher(ServerDef def, Timer timer) {
        this.processor = def.getProcessor();
        this.duplexProtocolFactory = def.getDuplexProtocolFactory();
        this.queuedResponseLimit = 16;
        this.exe = def.getExecutor();
        this.taskTimeoutMillis = (def.getTaskTimeout() == null ? 0 : 0);
        this.taskTimeoutTimer = (def.getTaskTimeout() == null ? null : timer);
        this.queueTimeoutMillis = (def.getQueueTimeout() == null ? 0 : 0);
    }


    private void checkResponseOrderingRequirements(ChannelHandlerContext ctx, ThriftMessage message) {
        boolean messageRequiresOrderedResponses = message.isOrderedResponsesRequired();

        if (!DispatcherContext.isResponseOrderingRequirementInitialized(ctx)) {
            // This is the first request. This message will decide whether all responses on the
            // channel must be strictly ordered, or whether out-of-order is allowed.
            DispatcherContext.setResponseOrderingRequired(ctx, messageRequiresOrderedResponses);
        } else {
            // This is not the first request. Verify that the ordering requirement on this message
            // is consistent with the requirement on the channel itself.
            if (messageRequiresOrderedResponses != DispatcherContext.isResponseOrderingRequired(ctx)) {
                throw new IllegalStateException("Every message on a single channel must specify the same requirement for response ordering");
            }
        }
    }

    private void processRequest(
            final ChannelHandlerContext ctx,
            final ThriftMessage message,
            final NettyTransport messageTransport,
            final TProtocol inProtocol,
            final TProtocol outProtocol) {
        // Remember the ordering of requests as they arrive, used to enforce an order on the
        // responses.
        final int requestSequenceId = dispatcherSequenceId.incrementAndGet();

        if (DispatcherContext.isResponseOrderingRequired(ctx)) {
            synchronized (responseMap) {
                // Limit the number of pending responses (responses which finished out of order, and are
                // waiting for previous requests to be finished so they can be written in order), by
                // blocking further channel reads. Due to the way Netty fface_identityrame decoders work, this is more
                // of an estimate than a hard limit. Netty may continue to decode and process several
                // more requests that were in the latest read, even while further reads on the channel
                // have been blocked.
                if (requestSequenceId > lastResponseWrittenId.get() + queuedResponseLimit &&
                        !DispatcherContext.isChannelReadBlocked(ctx)) {
                    DispatcherContext.blockChannelReads(ctx);
                }
            }
        }

        try {
            exe.execute(new Runnable() {
                @Override
                public void run() {
                    final AtomicBoolean responseSent = new AtomicBoolean(false);
                    // Use AtomicReference as a generic holder class to be able to mark it final
                    // and pass into inner classes. Since we only use .get() and .set(), we don't
                    // actually do any atomic operations.
                    final AtomicReference<Timeout> expireTimeout = new AtomicReference<>(null);

                    try {
                        try {
                            /*检查有没有超时*/
                            long timeRemaining = 0;
                            long timeElapsed = System.currentTimeMillis() - message.getProcessStartTimeMillis();
                            /*检查队列等待时间*/
                            if (queueTimeoutMillis > 0) {
                                if (timeElapsed >= queueTimeoutMillis) {
                                    TApplicationException taskTimeoutException = new TApplicationException(
                                            TApplicationException.INTERNAL_ERROR,
                                            "Task stayed on the queue for " + timeElapsed +
                                                    " milliseconds, exceeding configured queue timeout of " + queueTimeoutMillis +
                                                    " milliseconds."
                                    );
                                    sendTApplicationException(taskTimeoutException, ctx, message, requestSequenceId, messageTransport,
                                            inProtocol, outProtocol);
                                    return;
                                }
                            }
                            /*检查任务执行时间*/
                            else if (taskTimeoutMillis > 0) {
                                if (timeElapsed >= taskTimeoutMillis) {
                                    TApplicationException taskTimeoutException = new TApplicationException(
                                            TApplicationException.INTERNAL_ERROR,
                                            "Task stayed on the queue for " + timeElapsed +
                                                    " milliseconds, exceeding configured task timeout of " + taskTimeoutMillis +
                                                    " milliseconds."
                                    );
                                    sendTApplicationException(taskTimeoutException, ctx, message, requestSequenceId, messageTransport,
                                            inProtocol, outProtocol);
                                    return;
                                } else {
                                    timeRemaining = taskTimeoutMillis - timeElapsed;
                                }
                            }
                            /*如果都没有超时，则执行如下的逻辑*/
                            if (timeRemaining > 0) {
                                /*设置任务超时的处理*/
                                expireTimeout.set(taskTimeoutTimer.newTimeout(new TimerTask() {
                                    @Override
                                    public void run(Timeout timeout) throws Exception {
                                        // The immediateFuture returned by processors isn't cancellable, cancel() and
                                        // isCanceled() always return false. Use a flag to detect task expiration.
                                        if (responseSent.compareAndSet(false, true)) {
                                            TApplicationException ex = new TApplicationException(
                                                    TApplicationException.INTERNAL_ERROR,
                                                    "Task timed out while executing."
                                            );
                                            // Create a temporary transport to send the exception
                                            ByteBuf duplicateBuffer = message.getBuffer().duplicate();
                                            duplicateBuffer.resetReaderIndex();
                                            NettyTransport temporaryTransport = new NettyTransport(
                                                    ctx.channel(),
                                                    duplicateBuffer,
                                                    message.getTransportType());
                                            TProtocolPair protocolPair = duplexProtocolFactory.getProtocolPair(
                                                    TTransportPair.fromSingleTransport(temporaryTransport));
                                            sendTApplicationException(ex, ctx, message,
                                                    requestSequenceId,
                                                    temporaryTransport,
                                                    protocolPair.getInputProtocol(),
                                                    protocolPair.getOutputProtocol());
//                                            sendTApplicationException(ex, ctx, message,
//                                                    requestSequenceId,
//                                                    temporaryTransport,
//                                                    protocol,
//                                                    protocol);
                                        }
                                    }
                                }, timeRemaining, TimeUnit.MILLISECONDS));
                            }
                            /*上面一大坨代码都是检查超时的问题，下面的4行才是处理业务*/
//                            ConnectionContext connectionContext = ConnectionContexts.getContext(ctx.getChannel());
//                            RequestContext requestContext = new NiftyRequestContext(connectionContext, inProtocol, outProtocol, messageTransport);
//                            RequestContexts.setCurrentContext(requestContext);
                            if (processor != null) {
                                processor.process(inProtocol, outProtocol);
                            }
//                            processFuture = processorFactory.getProcessor(messageTransport).process(inProtocol, outProtocol, requestContext);
                        } finally {
                            // RequestContext does NOT stay set while we are waiting for the process
                            // future to complete. This is by design because we'll might move on to the
                            // next request using this thread before this one is completed. If you need
                            // the context throughout an asynchronous handler, you need to read and store
                            // it before returning a future.
//                            RequestContexts.clearCurrentContext();
                        }
                        /*上面的代码使用了线程，此处设置线程完成后的回调，把处理好的数据写回到数据链中，实现了io/业务分离*/
                        deleteExpirationTimer(expireTimeout.get());
                        try {
                            // Only write response if the client is still there and the task timeout
                            // hasn't expired.
                            if (ctx.channel().isActive() && responseSent.compareAndSet(false, true)) {
                                ThriftMessage response = message.getMessageFactory().create(
                                        messageTransport.getOutputBuffer());
                                writeResponse(ctx, response, requestSequenceId,
                                        DispatcherContext.isResponseOrderingRequired(ctx));
                            }
                        } catch (Throwable t) {
                            t.printStackTrace();
                            onDispatchException(ctx, t);
                        }
                    } catch (TException e) {
                        e.printStackTrace();
                        onDispatchException(ctx, e);
                    }
                }
            });
        } catch (RejectedExecutionException ex) {
            TApplicationException x = new TApplicationException(TApplicationException.INTERNAL_ERROR,
                    "Server overloaded");
            sendTApplicationException(x, ctx, message, requestSequenceId, messageTransport, inProtocol, outProtocol);
        }
    }

    private void deleteExpirationTimer(Timeout timeout) {
        if (timeout == null) {
            return;
        }
        timeout.cancel();
    }

    private void sendTApplicationException(
            TApplicationException x,
            ChannelHandlerContext ctx,
            ThriftMessage request,
            int responseSequenceId,
            NettyTransport requestTransport,
            TProtocol inProtocol,
            TProtocol outProtocol) {
        if (ctx.channel().isActive()) {
            try {
                TMessage message = inProtocol.readMessageBegin();
                outProtocol.writeMessageBegin(new TMessage(message.name, TMessageType.EXCEPTION, message.seqid));
                x.write(outProtocol);
                outProtocol.writeMessageEnd();
                requestTransport.setTApplicationException(x);
                outProtocol.getTransport().flush();

                ThriftMessage response = request.getMessageFactory().create(requestTransport.getOutputBuffer());
                writeResponse(ctx, response, responseSequenceId, DispatcherContext.isResponseOrderingRequired(ctx));
            } catch (TException ex) {
                ex.printStackTrace();
                onDispatchException(ctx, ex);
            }
        }
    }

    private void onDispatchException(ChannelHandlerContext ctx, Throwable t) {
        ctx.fireExceptionCaught(t);
        closeChannel(ctx);
    }

    private void writeResponse(ChannelHandlerContext ctx,
                               ThriftMessage response,
                               int responseSequenceId,
                               boolean isOrderedResponsesRequired) {
        if (isOrderedResponsesRequired) {
            writeResponseInOrder(ctx, response, responseSequenceId);
        } else {
            // No ordering required, just write the response immediately
            ctx.channel().writeAndFlush(response);
            lastResponseWrittenId.incrementAndGet();
        }
    }

    private void writeResponseInOrder(ChannelHandlerContext ctx,
                                      ThriftMessage response,
                                      int responseSequenceId) {
        // Ensure responses to requests are written in the same order the requests
        // were received.
        synchronized (responseMap) {
            int currentResponseId = lastResponseWrittenId.get() + 1;
            if (responseSequenceId != currentResponseId) {
                // This response is NOT next in line of ordered responses, save it to
                // be sent later, after responses to all earlier requests have been
                // sent.
                responseMap.put(responseSequenceId, response);
            } else {
                // This response was next in line, write this response now, and see if
                // there are others next in line that should be sent now as well.
                do {
                    ctx.channel().writeAndFlush(response);
                    lastResponseWrittenId.incrementAndGet();
                    ++currentResponseId;
                    response = responseMap.remove(currentResponseId);
                } while (null != response);

                // Now that we've written some responses, check if reads should be unblocked
                if (DispatcherContext.isChannelReadBlocked(ctx)) {
                    int lastRequestSequenceId = dispatcherSequenceId.get();
                    if (lastRequestSequenceId <= lastResponseWrittenId.get() + queuedResponseLimit) {
                        DispatcherContext.unblockChannelReads(ctx);
                    }
                }
            }
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        // Any out of band exception are caught here and we tear down the socket
        closeChannel(ctx);

        // Send for logging
        ctx.fireExceptionCaught(cause);
    }


    private void closeChannel(ChannelHandlerContext ctx) {
        if (ctx.channel().isOpen()) {
            ctx.channel().close();
        }
    }


    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        DispatcherContext.unblockChannelReads(ctx);

        super.channelActive(ctx);
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (msg instanceof ThriftMessage) {
            ThriftMessage message = (ThriftMessage) msg;
            message.setProcessStartTimeMillis(System.currentTimeMillis());
            checkResponseOrderingRequirements(ctx, message);

            /*构造protocol
             * 如果是自定义new出来的protocol，会有读取数据为空的问题。
             * 以下的代码是fb的处理代码。包括了把数据链中的数据封装到protocol中，具体怎样实现还未研究出来
             *
             * */
            NettyTransport messageTransport = new NettyTransport(ctx.channel(), message);
            TTransportPair transportPair = TTransportPair.fromSingleTransport(messageTransport);
            TProtocolPair protocolPair = duplexProtocolFactory.getProtocolPair(transportPair);
            TProtocol inProtocol = protocolPair.getInputProtocol();
            TProtocol outProtocol = protocolPair.getOutputProtocol();
            TProtocol tp = new TBinaryProtocol(messageTransport);

            processRequest(ctx, message, messageTransport, tp, tp);
        } else {
            super.channelRead(ctx, msg);
        }

    }


    private static class DispatcherContext {
        private static final AttributeKey<DispatcherContext> DISPATCHERCONTEXT =
                AttributeKey.valueOf("dispatcherContext");
        private ReadBlockedState readBlockedState = ReadBlockedState.NOT_BLOCKED;
        private boolean responseOrderingRequired = false;
        private boolean responseOrderingRequirementInitialized = false;

        public static boolean isChannelReadBlocked(ChannelHandlerContext ctx) {
            return getDispatcherContext(ctx).readBlockedState == ReadBlockedState.BLOCKED;
        }

        public static void blockChannelReads(ChannelHandlerContext ctx) {
            // Remember that reads are blocked (there is no Channel.getReadable())
            getDispatcherContext(ctx).readBlockedState = ReadBlockedState.BLOCKED;

            // NOTE: this shuts down reads, but isn't a 100% guarantee we won't get any more messages.
            // It sets up the channel so that the polling loop will not report any new read events
            // and netty won't read any more data from the socket, but any messages already fully read
            // from the socket before this ran may still be decoded and arrive at this handler. Thus
            // the limit on queued messages before we block reads is more of a guidance than a hard
            // limit.
            ctx.channel().config().setAutoRead(false);
        }

        public static void unblockChannelReads(ChannelHandlerContext ctx) {
            // Remember that reads are unblocked (there is no Channel.getReadable())
            getDispatcherContext(ctx).readBlockedState = ReadBlockedState.NOT_BLOCKED;

            ctx.channel().config().setAutoRead(true);
        }

        public static void setResponseOrderingRequired(ChannelHandlerContext ctx, boolean required) {
            DispatcherContext dispatcherContext = getDispatcherContext(ctx);
            dispatcherContext.responseOrderingRequirementInitialized = true;
            dispatcherContext.responseOrderingRequired = required;
        }

        public static boolean isResponseOrderingRequired(ChannelHandlerContext ctx) {
            return getDispatcherContext(ctx).responseOrderingRequired;
        }

        public static boolean isResponseOrderingRequirementInitialized(ChannelHandlerContext ctx) {
            return getDispatcherContext(ctx).responseOrderingRequirementInitialized;
        }

        private static DispatcherContext getDispatcherContext(ChannelHandlerContext ctx) {
            DispatcherContext dispatcherContext = ctx.attr(DISPATCHERCONTEXT).get();
            if (dispatcherContext == null) {
                // No context was added yet, add one
                dispatcherContext = new DispatcherContext();
                ctx.attr(DISPATCHERCONTEXT).set(dispatcherContext);
            }
            return dispatcherContext;
        }

        private enum ReadBlockedState {
            NOT_BLOCKED,
            BLOCKED,
        }
    }
}
