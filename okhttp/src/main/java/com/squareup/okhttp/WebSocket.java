/*
 * Copyright (C) 2014 Square, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.squareup.okhttp;

import com.squareup.okhttp.internal.NamedRunnable;
import com.squareup.okhttp.internal.Util;
import com.squareup.okhttp.internal.ws.WebSocketReader;
import com.squareup.okhttp.internal.ws.WebSocketWriter;
import java.io.IOException;
import java.net.ProtocolException;
import java.net.Socket;
import java.util.Random;
import okio.Buffer;
import okio.BufferedSink;
import okio.BufferedSource;
import okio.ByteString;
import okio.Okio;

import static com.squareup.okhttp.internal.ws.WebSocketReader.FrameCallback;

/** Blocking interface to connect and write to a web socket. */
public final class WebSocket {
  /** Magic value which must be appended to the {@link #key} in a response header. */
  private static final String ACCEPT_MAGIC = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";

  /** The format of a message payload. */
  public enum PayloadType {
    /** UTF8-encoded text data. */
    TEXT,
    /** Arbitrary binary data. */
    BINARY
  }

  private final OkHttpClient client;
  private final Request request;
  private final Random random;
  private final String key;

  /** True after calling {@link #close(int, String)}. No writes are allowed afterward. */
  private volatile boolean writerClosed;
  /** True after a close frame was read by the reader. No frames will follow it. */
  private volatile boolean readerClosed;

  private boolean connected;
  private Connection connection;

  private WebSocketWriter writer;

  WebSocket(OkHttpClient client, Request request, Random random) {
    this.client = client;
    this.random = random;

    if (!"GET".equals(request.method())) {
      throw new IllegalArgumentException("Request must be GET: " + request.method());
    }
    String url = request.urlString();
    String httpUrl;
    if (url.startsWith("ws://")) {
      httpUrl = "http://" + url.substring(5);
    } else if (url.startsWith("wss://")) {
      httpUrl = "https://" + url.substring(6);
    } else {
      throw new IllegalArgumentException("Request url must use 'ws' or 'wss' protocol: " + url);
    }

    byte[] nonce = new byte[16];
    random.nextBytes(nonce);
    key = ByteString.of(nonce).base64();

    this.request = request.newBuilder()
        .url(httpUrl)
        .header("Upgrade", "websocket")
        .header("Connection", "Upgrade")
        .header("Sec-WebSocket-Key", key)
        .header("Sec-WebSocket-Version", "13")
        .build();
  }

  /** The HTTP request which initiated this web socket. */
  public Request request() {
    return request;
  }

  /**
   * Connects the web socket and blocks until the response can be processed. Once connected all
   * messages from the server are sent to the {@code listener}.
   * <p>
   * Note that transport-layer success (receiving a HTTP response code,
   * headers and body) does not necessarily indicate application-layer success:
   * {@code response} may still indicate an unhappy HTTP response code like 404
   * or 500.
   *
   * @throws IOException if the request could not be executed due to
   *     a connectivity problem or timeout. Because networks can
   *     fail during an exchange, it is possible that the remote server
   *     accepted the request before the failure.
   *
   * @throws IllegalStateException when the web socket has already been connected.
   */
  public Response connect(WebSocketListener listener) throws IOException {
    if (connected) throw new IllegalStateException("Already connected");
    if (writerClosed) throw new IllegalStateException("Closed");

    Call call = new Call(client, null, request);
    Response response = call.getResponse(true);
    if (response.code() != 101) {
      call.engine.releaseConnection();
    } else {
      if (!"websocket".equalsIgnoreCase(response.header("Upgrade"))
          || !"Upgrade".equalsIgnoreCase(response.header("Connection"))) {
        throw new ProtocolException("FAIL!"); // TODO
      }
      String accept = response.header("Sec-WebSocket-Accept");
      String acceptExpected = Util.shaBase64(key + ACCEPT_MAGIC);
      if (!acceptExpected.equals(accept)) {
        throw new ProtocolException("FAIL!"); // TODO
      }

      connection = call.engine.getConnection();
      if (!connection.clearOwner()) {
        throw new IllegalStateException("WAT?"); // TODO
      }
      connection.setOwner(this);
      connected = true;

      Socket socket = connection.getSocket();

      BufferedSink sink = Okio.buffer(Okio.sink(socket));
      writer = new WebSocketWriter(true, sink, random);

      BufferedSource source = Okio.buffer(Okio.source(socket));
      WebSocketReader reader = new WebSocketReader(true, source, listener, new FrameCallback() {
        @Override public void onPing(final Buffer buffer) {
          new Thread(new NamedRunnable("WebSocket PongWriter") {
            @Override protected void execute() {
              try {
                writer.writePong(buffer);
              } catch (IOException e) {
                // TODO now what?
              }
            }
          });
        }

        @Override public void onClose(Buffer buffer) throws IOException {
          serverClose(buffer);
        }
      });

      ReaderRunnable readerRunnable = new ReaderRunnable(request.urlString(), reader, listener);
      new Thread(readerRunnable).start();
    }
    return response;
  }

  /**
   * Stream a message payload to the server of the specified {code type}.
   * <p>
   * You must call {@link BufferedSink#close() close()} to complete the message. Calls to
   * {@link BufferedSink#flush() flush()} write a frame fragment. The message may be empty.
   *
   * @throws IllegalStateException if not connected, already closed, or another writer is active.
   */
  public BufferedSink newMessageSink(PayloadType type) {
    if (writerClosed) throw new IllegalStateException("Closed");
    if (!connected) throw new IllegalStateException("Not connected");

    return writer.newMessageSink(type);
  }

  /**
   * Send a message payload the server of the specified {@code type}.
   *
   * @throws IllegalStateException if not connected, already closed, or another writer is active.
   */
  public void sendMessage(PayloadType type, Buffer payload) throws IOException {
    if (writerClosed) throw new IllegalStateException("Closed");
    if (!connected) throw new IllegalStateException("Not connected");

    writer.sendMessage(type, payload);
  }

  /**
   * Send a close frame to the server.
   * <p>
   * The corresponding {@link WebSocketListener} will continue to get messages until its
   * {@link WebSocketListener#onClose onClose()} method is called.
   * <p>
   * It is an error to call this method before calling close on an active writer. Calling this
   * method more than once has no effect.
   */
  public void close(int code, String reason) throws IOException {
    if (writerClosed) return;
    writerClosed = true;

    if (writer != null) {
      writer.writeClose(code, reason);
      writer = null;

      if (readerClosed) {
        // The reader has also indicated a desire to close, immediately kill the connection.
        closeConnection();
      }
    }
  }

  /** Called on the reader thread when a close frame is encountered. */
  private void serverClose(Buffer buffer) throws IOException {
    if (writerClosed) {
      // The writer has already indicated a desire to close. Move to kill the connection.
      closeConnection();
    } else {
      // The writer thread will read no more frames so use it to send the response.
      writer.writeClose(buffer);
      writer = null;
      writerClosed = true;
    }
    readerClosed = true;
  }

  private void closeConnection() throws IOException {
    connection.closeIfOwnedBy(this);
    connection = null;
  }

  /** True if this web socket is closed and can no longer be written to. */
  public boolean isClosed() {
    return writerClosed;
  }

  private class ReaderRunnable extends NamedRunnable {
    private final WebSocketReader reader;
    private final WebSocketListener listener;

    public ReaderRunnable(String url, WebSocketReader reader, WebSocketListener listener) {
      super("WebSocketReader " + url);
      this.reader = reader;
      this.listener = listener;
    }

    @Override protected void execute() {
      while (!readerClosed) {
        try {
          reader.readMessage();
        } catch (IOException e) {
          // TODO close socket
          listener.onFailure(e);
          readerClosed = true;
        }
      }
    }
  }
}
