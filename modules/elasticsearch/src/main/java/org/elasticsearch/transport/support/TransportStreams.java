/*
 * Licensed to Elastic Search and Shay Banon under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. Elastic Search licenses this
 * file to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
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

package org.elasticsearch.transport.support;

import org.elasticsearch.common.io.stream.*;
import org.elasticsearch.transport.TransportRequestOptions;
import org.elasticsearch.transport.TransportResponseOptions;

import java.io.IOException;

/**
 * @author kimchy (shay.banon)
 */
public class TransportStreams {

    public static final int HEADER_SIZE = 4 + 8 + 1;

    public static void writeHeader(byte[] data, int dataLength, long requestId, byte status) {
        writeInt(data, 0, dataLength + 9);  // add the requestId and the status
        writeLong(data, 4, requestId);
        data[12] = status;
    }

    // same as writeLong in StreamOutput

    private static void writeLong(byte[] buffer, int offset, long value) {
        buffer[offset++] = ((byte) (value >> 56));
        buffer[offset++] = ((byte) (value >> 48));
        buffer[offset++] = ((byte) (value >> 40));
        buffer[offset++] = ((byte) (value >> 32));
        buffer[offset++] = ((byte) (value >> 24));
        buffer[offset++] = ((byte) (value >> 16));
        buffer[offset++] = ((byte) (value >> 8));
        buffer[offset] = ((byte) (value));
    }

    // same as writeInt in StreamOutput

    private static void writeInt(byte[] buffer, int offset, int value) {
        buffer[offset++] = ((byte) (value >> 24));
        buffer[offset++] = ((byte) (value >> 16));
        buffer[offset++] = ((byte) (value >> 8));
        buffer[offset] = ((byte) (value));
    }

    private static final byte STATUS_REQRES = 1 << 0;
    private static final byte STATUS_ERROR = 1 << 1;
    private static final byte STATUS_COMPRESS = 1 << 2;

    public static boolean statusIsRequest(byte value) {
        return (value & STATUS_REQRES) == 0;
    }

    public static byte statusSetRequest(byte value) {
        value &= ~STATUS_REQRES;
        return value;
    }

    public static byte statusSetResponse(byte value) {
        value |= STATUS_REQRES;
        return value;
    }

    public static boolean statusIsError(byte value) {
        return (value & STATUS_ERROR) != 0;
    }

    public static byte statusSetError(byte value) {
        value |= STATUS_ERROR;
        return value;
    }

    public static boolean statusIsCompress(byte value) {
        return (value & STATUS_COMPRESS) != 0;
    }

    public static byte statusSetCompress(byte value) {
        value |= STATUS_COMPRESS;
        return value;
    }

    public static byte[] buildRequest(final long requestId, final String action, final Streamable message, TransportRequestOptions options) throws IOException {
        byte status = 0;
        status = TransportStreams.statusSetRequest(status);

        BytesStreamOutput wrapped;
        if (options.compress()) {
            status = TransportStreams.statusSetCompress(status);
            HandlesStreamOutput stream = CachedStreamOutput.cachedHandlesLzfBytes();
            stream.writeUTF(action);
            message.writeTo(stream);
            stream.flush();
            wrapped = ((BytesStreamOutput) ((LZFStreamOutput) stream.wrappedOut()).wrappedOut());
            stream.cleanHandles();
        } else {
            HandlesStreamOutput stream = CachedStreamOutput.cachedHandlesBytes();
            stream.writeUTF(action);
            message.writeTo(stream);
            stream.flush();
            wrapped = ((BytesStreamOutput) stream.wrappedOut());
            stream.cleanHandles();
        }

        byte[] data = new byte[HEADER_SIZE + wrapped.size()];
        TransportStreams.writeHeader(data, wrapped.size(), requestId, status);
        System.arraycopy(wrapped.unsafeByteArray(), 0, data, HEADER_SIZE, wrapped.size());

        return data;
    }

    public static byte[] buildResponse(final long requestId, Streamable message, TransportResponseOptions options) throws IOException {
        byte status = 0;
        status = TransportStreams.statusSetResponse(status);

        BytesStreamOutput wrapped;
        if (options.compress()) {
            status = TransportStreams.statusSetCompress(status);
            HandlesStreamOutput stream = CachedStreamOutput.cachedHandlesLzfBytes();
            message.writeTo(stream);
            stream.flush();
            wrapped = ((BytesStreamOutput) ((LZFStreamOutput) stream.wrappedOut()).wrappedOut());
        } else {
            HandlesStreamOutput stream = CachedStreamOutput.cachedHandlesBytes();
            message.writeTo(stream);
            stream.flush();
            wrapped = ((BytesStreamOutput) stream.wrappedOut());
        }


        byte[] data = new byte[HEADER_SIZE + wrapped.size()];
        TransportStreams.writeHeader(data, wrapped.size(), requestId, status);
        System.arraycopy(wrapped.unsafeByteArray(), 0, data, HEADER_SIZE, wrapped.size());

        return data;
    }
}
