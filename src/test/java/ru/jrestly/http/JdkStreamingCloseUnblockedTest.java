package ru.jrestly.http;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.jrestly.JRestlyClient;
import ru.jrestly.annotation.Get;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Iterator;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ADR-0059 phase 3 spike, JDK leg: closing the SSE stream returned by the
 * default ({@code JdkHttpEngine}) transport must release a consumer blocked in
 * a body read. This is the premise of the idle-stall guard — the watchdog
 * closes the active stream from another (virtual) thread to unblock the
 * consumer and let the reconnection path take over.
 *
 * <p>The close chain under test is the production one:
 * {@code Stream<ServerSentEvent>.close()} → {@code lineStream.close()} →
 * {@code effectiveStream.close()} (the JDK response {@code InputStream}). The
 * OkHttp and HC5 legs are asserted in their parity suites; the JDK transport
 * has no submodule, so its gate lives here.
 */
class JdkStreamingCloseUnblockedTest {

    interface SseApi {

        @Get(path = "/sse")
        Stream<ServerSentEvent> events();
    }

    @Test
    @DisplayName("closing the JDK-backed SSE stream unblocks a blocked reader with IOException")
    void closingJdkStreamingStreamUnblocksBlockedReaderThread() throws Exception {
        try (ServerSocket serverSocket = new ServerSocket(0, 10, InetAddress.getByName("127.0.0.1"))) {
            int port = serverSocket.getLocalPort();
            Thread.ofVirtual().start(() -> {
                try (Socket socket = serverSocket.accept();
                     InputStream in = socket.getInputStream();
                     OutputStream out = socket.getOutputStream()) {
                    in.read(new byte[1024]);
                    String response = "HTTP/1.1 200 OK\r\n"
                            + "Content-Type: text/event-stream\r\n"
                            + "Connection: close\r\n\r\n"
                            + "data: hello\n\n";
                    out.write(response.getBytes(StandardCharsets.US_ASCII));
                    out.flush();
                    Thread.sleep(60_000);
                } catch (Exception ignored) {
                    // Server fixture: the client aborts the connection.
                }
            });

            JRestlyClient client = JRestlyClient.builder()
                    .baseUrl("http://127.0.0.1:" + port)
                    .build();
            try {
                SseApi api = client.get(SseApi.class);
                Stream<ServerSentEvent> events = api.events();
                try {
                    Iterator<ServerSentEvent> iterator = events.iterator();
                    assertEquals("hello", iterator.next().data());

                    AtomicReference<Throwable> thrown = new AtomicReference<>();
                    CountDownLatch blocked = new CountDownLatch(1);
                    Thread reader = Thread.ofVirtual().start(() -> {
                        try {
                            blocked.countDown();
                            iterator.next();
                        } catch (Throwable t) {
                            thrown.set(t);
                        }
                    });
                    blocked.await();
                    Thread.sleep(200);

                    events.close();

                    assertTrue(reader.join(Duration.ofSeconds(2)),
                            "reader must unblock when the SSE stream is closed");
                    assertNotNull(thrown.get(), "reader must observe the close");
                } finally {
                    events.close();
                }
            } finally {
                client.close();
            }
        }
    }
}
