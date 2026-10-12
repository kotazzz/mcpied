package dev.mcpied.mcp;

import com.google.gson.Gson;
import dev.mcpied.McpiedMod;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;

public final class McpServer {
    static final int PORT = 47821;
    private static final Gson GSON = new Gson();
    private static final AtomicBoolean STARTED = new AtomicBoolean();
    private static final Map<String, SseSession> SESSIONS = new ConcurrentHashMap<>();
    private static volatile ServerSocket socket;
    private static volatile ExecutorService handlers;

    private McpServer() {
    }

    public static void start() {
        if (!STARTED.compareAndSet(false, true)) {
            return;
        }
        handlers = Executors.newVirtualThreadPerTaskExecutor();
        Thread thread = new Thread(McpServer::acceptLoop, "mcpied-mcp");
        thread.setDaemon(true);
        thread.start();
        Runtime.getRuntime().addShutdownHook(new Thread(McpServer::stop, "mcpied-mcp-stop"));
    }

    static String url() {
        return "http://127.0.0.1:" + PORT + "/mcp";
    }

    public static void onPlayerLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player && socket != null && !socket.isClosed()) {
            player.sendSystemMessage(Component.translatable("mcpied.mcp.started", url()));
        }
    }

    static void stop() {
        ServerSocket current = socket;
        socket = null;
        if (current != null) {
            try {
                current.close();
            } catch (IOException ignored) {
                // Already closed.
            }
        }
        for (SseSession session : SESSIONS.values()) {
            session.close();
        }
        SESSIONS.clear();
        ExecutorService pool = handlers;
        handlers = null;
        if (pool != null) {
            pool.shutdownNow();
        }
    }

    private static void acceptLoop() {
        try (ServerSocket server = new ServerSocket()) {
            server.setReuseAddress(true);
            server.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), PORT));
            socket = server;
            McpiedMod.LOGGER.info("MCPied MCP listening on {}", url());
            while (!server.isClosed()) {
                Socket client = server.accept();
                ExecutorService pool = handlers;
                if (pool == null) {
                    client.close();
                    break;
                }
                pool.execute(() -> handle(client));
            }
        } catch (SocketException exception) {
            if (socket != null) {
                McpiedMod.LOGGER.warn("MCPied MCP stopped: {}", exception.getMessage());
            }
        } catch (IOException exception) {
            STARTED.set(false);
            McpiedMod.LOGGER.error("MCPied MCP failed to bind 127.0.0.1:{}: {}", PORT, exception.getMessage());
        }
    }

    private static void handle(Socket client) {
        try (client) {
            client.setSoTimeout(15000);
            InputStream input = client.getInputStream();
            byte[] headerBytes = readHeaders(input);
            String header = new String(headerBytes, StandardCharsets.UTF_8);
            int split = header.indexOf("\r\n\r\n");
            if (split < 0) {
                return;
            }
            String head = header.substring(0, split);
            String[] lines = head.split("\r\n");
            if (lines.length == 0) {
                return;
            }
            String[] request = lines[0].split(" ");
            if (request.length < 2) {
                writeText(client, 400, "Bad request");
                return;
            }
            String method = request[0];
            String target = request[1];
            int query = target.indexOf('?');
            String path = query >= 0 ? target.substring(0, query) : target;
            String queryText = query >= 0 ? target.substring(query + 1) : "";
            if (path.endsWith("/") && path.length() > 1) {
                path = path.substring(0, path.length() - 1);
            }
            Map<String, String> headers = headers(lines);
            int length = 0;
            String lengthText = headers.get("content-length");
            if (lengthText != null) {
                length = Integer.parseInt(lengthText.trim());
            }
            if (length < 0 || length > 2_000_000) {
                writeText(client, 413, "Body too large");
                return;
            }
            byte[] body = input.readNBytes(length);
            if ("GET".equals(method) && "/sse".equals(path)) {
                serveSse(client);
                return;
            }
            if ("POST".equals(method) && "/message".equals(path)) {
                String sessionId = queryParam(queryText, "sessionId");
                McpProtocol.Reply reply = McpProtocol.handle(new String(body, StandardCharsets.UTF_8));
                if (!reply.accepted() && reply.body() != null) {
                    SseSession session = SESSIONS.get(sessionId);
                    if (session != null) {
                        session.send(GSON.toJson(reply.body()));
                    }
                }
                writeBytes(client, 202, new byte[0], "text/plain; charset=utf-8", null);
                return;
            }
            if ("POST".equals(method) && "/mcp".equals(path)) {
                McpProtocol.Reply reply = McpProtocol.handle(new String(body, StandardCharsets.UTF_8));
                if (reply.accepted()) {
                    writeBytes(client, 202, new byte[0], "application/json; charset=utf-8", null);
                    return;
                }
                byte[] payload = GSON.toJson(reply.body()).getBytes(StandardCharsets.UTF_8);
                writeBytes(client, 200, payload, "application/json; charset=utf-8", reply.sessionId());
                return;
            }
            if ("DELETE".equals(method) && "/mcp".equals(path)) {
                writeBytes(client, 200, new byte[0], "application/json; charset=utf-8", null);
                return;
            }
            if ("GET".equals(method) && "/mcp".equals(path)) {
                writeText(client, 405, "POST JSON-RPC to this URL");
                return;
            }
            writeText(client, 404, "Not found");
        } catch (Exception exception) {
            McpiedMod.LOGGER.debug("MCP connection closed: {}", exception.getMessage());
        }
    }

    private static void serveSse(Socket client) throws IOException {
        client.setSoTimeout(0);
        String id = java.util.UUID.randomUUID().toString();
        OutputStream output = client.getOutputStream();
        String endpoint = "http://127.0.0.1:" + PORT + "/message?sessionId=" + id;
        String head = "HTTP/1.1 200 OK\r\n"
                + "Content-Type: text/event-stream; charset=utf-8\r\n"
                + "Cache-Control: no-cache\r\n"
                + "Connection: keep-alive\r\n"
                + "\r\n"
                + "event: endpoint\n"
                + "data: " + endpoint + "\n\n";
        output.write(head.getBytes(StandardCharsets.UTF_8));
        output.flush();
        SseSession session = new SseSession(output);
        SESSIONS.put(id, session);
        try {
            while (!client.isClosed()) {
                Thread.sleep(15000);
                session.comment("ping");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        } finally {
            SESSIONS.remove(id, session);
        }
    }

    private static byte[] readHeaders(InputStream input) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int matched = 0;
        while (out.size() < 16384) {
            int value = input.read();
            if (value < 0) {
                break;
            }
            out.write(value);
            matched = value == "\r\n\r\n".charAt(matched) ? matched + 1 : (value == '\r' ? 1 : 0);
            if (matched == 4) {
                return out.toByteArray();
            }
        }
        throw new IOException("HTTP headers are incomplete");
    }

    private static Map<String, String> headers(String[] lines) {
        Map<String, String> headers = new java.util.HashMap<>();
        for (int i = 1; i < lines.length; i++) {
            int colon = lines[i].indexOf(':');
            if (colon > 0) {
                headers.put(lines[i].substring(0, colon).trim().toLowerCase(Locale.ROOT), lines[i].substring(colon + 1).trim());
            }
        }
        return headers;
    }

    private static String queryParam(String query, String key) {
        for (String part : query.split("&")) {
            int eq = part.indexOf('=');
            if (eq > 0 && part.substring(0, eq).equals(key)) {
                return part.substring(eq + 1);
            }
        }
        return "";
    }

    private static void writeText(Socket client, int status, String text) throws IOException {
        writeBytes(client, status, text.getBytes(StandardCharsets.UTF_8), "text/plain; charset=utf-8", null);
    }

    private static void writeBytes(Socket client, int status, byte[] body, String type, String session) throws IOException {
        StringBuilder head = new StringBuilder();
        head.append("HTTP/1.1 ").append(status).append(' ').append(reason(status)).append("\r\n");
        head.append("Content-Type: ").append(type).append("\r\n");
        head.append("Content-Length: ").append(body.length).append("\r\n");
        head.append("Connection: close\r\n");
        if (session != null) {
            head.append("Mcp-Session-Id: ").append(session).append("\r\n");
        }
        head.append("\r\n");
        OutputStream output = client.getOutputStream();
        output.write(head.toString().getBytes(StandardCharsets.UTF_8));
        output.write(body);
        output.flush();
    }

    private static String reason(int status) {
        return switch (status) {
            case 200 -> "OK";
            case 202 -> "Accepted";
            case 400 -> "Bad Request";
            case 404 -> "Not Found";
            case 405 -> "Method Not Allowed";
            case 413 -> "Payload Too Large";
            default -> "OK";
        };
    }

    private static final class SseSession {
        private final OutputStream output;

        private SseSession(OutputStream output) {
            this.output = output;
        }

        void send(String json) throws IOException {
            synchronized (output) {
                output.write(("event: message\ndata: " + json.replace("\n", "\ndata: ") + "\n\n").getBytes(StandardCharsets.UTF_8));
                output.flush();
            }
        }

        void comment(String text) throws IOException {
            synchronized (output) {
                output.write((": " + text + "\n\n").getBytes(StandardCharsets.UTF_8));
                output.flush();
            }
        }

        void close() {
            try {
                output.close();
            } catch (IOException ignored) {
                // Already closed.
            }
        }
    }
}
