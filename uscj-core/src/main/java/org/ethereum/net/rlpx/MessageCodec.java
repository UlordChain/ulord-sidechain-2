/*
 * This file is part of USC
 * Copyright (C) 2016 - 2018 USC developer team.
 * (derived from ethereumJ library, Copyright (c) 2016 <ether.camp>)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */

package org.ethereum.net.rlpx;

import com.google.common.io.ByteStreams;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToMessageCodec;
import org.apache.commons.lang3.tuple.Pair;
import org.ethereum.config.SystemProperties;
import org.ethereum.listener.EthereumListener;
import org.ethereum.net.client.Capability;
import org.ethereum.net.eth.EthVersion;
import org.ethereum.net.eth.message.EthMessageCodes;
import org.ethereum.net.message.Message;
import org.ethereum.net.message.MessageFactory;
import org.ethereum.net.p2p.P2pMessageCodes;
import org.ethereum.net.server.Channel;
import org.ethereum.util.LRUMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.bouncycastle.util.encoders.Hex;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import static java.lang.Math.min;
import static org.ethereum.net.rlpx.FrameCodec.Frame;

/**
 * The Netty codec which encodes/decodes RPLx frames to subprotocol Messages
 */
public class MessageCodec extends MessageToMessageCodec<Frame, Message> {

    private static final Logger loggerWire = LoggerFactory.getLogger("wire");
    private static final Logger loggerNet = LoggerFactory.getLogger("net");

    public static final int NO_FRAMING = Integer.MAX_VALUE >> 1;

    private int maxFramePayloadSize = NO_FRAMING;

    private Channel channel;
    private MessageCodesResolver messageCodesResolver;

    private MessageFactory p2pMessageFactory;
    private MessageFactory ethMessageFactory;
    private EthVersion ethVersion;

    private final EthereumListener ethereumListener;

    private boolean supportChunkedFrames = true;

    Map<Integer, Pair<? extends List<Frame>, AtomicInteger>> incompleteFrames = new LRUMap<>(1, 16);
    // LRU avoids OOM on invalid peers
    AtomicInteger contextIdCounter = new AtomicInteger(1);

    public MessageCodec(EthereumListener ethereumListener, SystemProperties config) {
        this.ethereumListener = ethereumListener;
        this.maxFramePayloadSize = config.rlpxMaxFrameSize();
    }

    @Override
    protected void decode(ChannelHandlerContext ctx, Frame frame, List<Object> out) throws Exception {
        if (frame.isChunked()) {
            if (!supportChunkedFrames && frame.totalFrameSize > 0) {
                throw new RuntimeException("Faming is not supported in this configuration.");
            }

            Pair<? extends List<Frame>, AtomicInteger> frameParts = incompleteFrames.get(frame.contextId);
            if (frameParts == null) {
                if (frame.totalFrameSize < 0) {
                    // TODO: refactor this logic (Cpp sends non-chunked frames with context-id)
                    Message message = decodeMessage(Collections.singletonList(frame));
                    out.add(message);
                    return;
                } else {
                    frameParts = Pair.of(new ArrayList<Frame>(), new AtomicInteger(0));
                    incompleteFrames.put(frame.contextId, frameParts);
                }
            } else {
                if (frame.totalFrameSize >= 0) {
                    loggerNet.warn("Non-initial chunked frame shouldn't contain totalFrameSize field (context-id: " + frame.contextId + ", totalFrameSize: " + frame.totalFrameSize + "). Discarding this frame and all previous.");
                    incompleteFrames.remove(frame.contextId);
                    return;
                }
            }

            frameParts.getLeft().add(frame);
            int curSize = frameParts.getRight().addAndGet(frame.size);

            if (loggerWire.isDebugEnabled()) {
                loggerWire.debug("Recv: Chunked (" + curSize + " of " + frameParts.getLeft().get(0).totalFrameSize + ") [size: " + frame.getSize() + "]");
            }

            if (curSize > frameParts.getLeft().get(0).totalFrameSize) {
                loggerNet.warn("The total frame chunks size (" + curSize + ") is greater than expected (" + frameParts.getLeft().get(0).totalFrameSize + "). Discarding the frame.");
                incompleteFrames.remove(frame.contextId);
                return;
            }
            if (curSize == frameParts.getLeft().get(0).totalFrameSize) {
                Message message = decodeMessage(frameParts.getLeft());
                incompleteFrames.remove(frame.contextId);
                out.add(message);
            }
        } else {
            Message message = decodeMessage(Collections.singletonList(frame));
            out.add(message);
        }
    }

    private Message decodeMessage(List<Frame> frames) throws IOException {
        long frameType = frames.get(0).getType();

        byte[] payload = new byte[frames.size() == 1 ? frames.get(0).getSize() : frames.get(0).totalFrameSize];
        int pos = 0;
        for (Frame frame : frames) {
            pos += ByteStreams.read(frame.getStream(), payload, pos, frame.getSize());
        }

        if (loggerWire.isDebugEnabled()) {
            loggerWire.debug("Recv: Encoded: {} [{}]", frameType, Hex.toHexString(payload));
        }

        Message msg = createMessage((byte) frameType, payload);

        loggerNet.trace("From: \t{} \tRecv: \t{}", channel, msg);

        ethereumListener.onRecvMessage(channel, msg);

        channel.getNodeStatistics().rlpxInMessages.add();
        return msg;
    }

    @Override
    protected void encode(ChannelHandlerContext ctx, Message msg, List<Object> out) throws Exception {
        String output = String.format("To: \t%s \tSend: \t%s", ctx.channel().remoteAddress(), msg);
        ethereumListener.trace(output);

        loggerNet.trace("To: \t{} \tSend: \t{}", channel, msg);

        byte[] encoded = msg.getEncoded();

        if (loggerWire.isDebugEnabled()) {
            loggerWire.debug("Send: Encoded: {} [{}]", getCode(msg.getCommand()), Hex.toHexString(encoded));
        }

        List<Frame> frames = splitMessageToFrames(msg);

        out.addAll(frames);

        channel.getNodeStatistics().rlpxOutMessages.add();
    }

    private List<Frame> splitMessageToFrames(Message msg) {
        byte code = getCode(msg.getCommand());
        List<Frame> ret = new ArrayList<>();
        byte[] bytes = msg.getEncoded();
        int curPos = 0;
        while(curPos < bytes.length) {
            int newPos = min(curPos + maxFramePayloadSize, bytes.length);
            byte[] frameBytes = curPos == 0 && newPos == bytes.length ? bytes :
                    Arrays.copyOfRange(bytes, curPos, newPos);
            ret.add(new Frame(code, frameBytes));
            curPos = newPos;
        }

        if (ret.size() > 1) {
            // frame has been split
            int contextId = contextIdCounter.getAndIncrement();
            ret.get(0).totalFrameSize = bytes.length;
            loggerWire.debug("Message (size " + bytes.length + ") split to " + ret.size() + " frames. Context-id: " + contextId);
            for (Frame frame : ret) {
                frame.contextId = contextId;
            }
        }
        return ret;
    }

    public void setSupportChunkedFrames(boolean supportChunkedFrames) {
        this.supportChunkedFrames = supportChunkedFrames;
        if (!supportChunkedFrames) {
            setMaxFramePayloadSize(NO_FRAMING);
        }
    }

    private byte getCode(Enum msgCommand){
        byte code = 0;

        if (msgCommand instanceof P2pMessageCodes){
            code = messageCodesResolver.withP2pOffset(((P2pMessageCodes) msgCommand).asByte());
        }

        if (msgCommand instanceof EthMessageCodes){
            code = messageCodesResolver.withEthOffset(((EthMessageCodes) msgCommand).asByte());
        }

        return code;
    }

    private Message createMessage(byte code, byte[] payload) {

        byte resolved = messageCodesResolver.resolveP2p(code);
        if (p2pMessageFactory != null && P2pMessageCodes.inRange(resolved)) {
            return p2pMessageFactory.create(resolved, payload);
        }

        resolved = messageCodesResolver.resolveEth(code);
        if (ethMessageFactory != null && EthMessageCodes.inRange(resolved, ethVersion)) {
            return ethMessageFactory.create(resolved, payload);
        }

        throw new IllegalArgumentException("No such message: " + code + " [" + Hex.toHexString(payload) + "]");
    }

    public void setChannel(Channel channel){
        this.channel = channel;
    }

    public void setEthVersion(EthVersion ethVersion) {
        this.ethVersion = ethVersion;
    }

    public void setMaxFramePayloadSize(int maxFramePayloadSize) {
        this.maxFramePayloadSize = maxFramePayloadSize;
    }

    public void initMessageCodes(List<Capability> caps) {
        this.messageCodesResolver = new MessageCodesResolver(caps);
    }

    public void setP2pMessageFactory(MessageFactory p2pMessageFactory) {
        this.p2pMessageFactory = p2pMessageFactory;
    }

    public void setEthMessageFactory(MessageFactory ethMessageFactory) {
        this.ethMessageFactory = ethMessageFactory;
    }

}