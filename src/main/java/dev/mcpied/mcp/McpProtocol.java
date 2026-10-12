package dev.mcpied.mcp;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.UUID;

final class McpProtocol {
    private McpProtocol() {
    }

    record Reply(int status, JsonObject body, String sessionId) {
        boolean accepted() {
            return status == 202;
        }
    }

    static Reply handle(String body) {
        JsonElement parsed;
        try {
            parsed = JsonParser.parseString(body);
        } catch (RuntimeException exception) {
            return new Reply(200, error(null, -32700, "Parse error"), null);
        }
        if (!parsed.isJsonObject()) {
            return new Reply(200, error(null, -32600, "Invalid request"), null);
        }
        JsonObject request = parsed.getAsJsonObject();
        JsonElement id = request.get("id");
        String method = request.has("method") && request.get("method").isJsonPrimitive()
                ? request.get("method").getAsString()
                : "";
        if (method.isEmpty()) {
            return new Reply(200, error(id, -32600, "Invalid request"), null);
        }
        boolean notification = !request.has("id") || method.startsWith("notifications/");
        try {
            if ("initialize".equals(method)) {
                return new Reply(200, initialize(request, id), UUID.randomUUID().toString());
            }
            if (notification) {
                return new Reply(202, null, null);
            }
            JsonObject result = switch (method) {
                case "ping" -> new JsonObject();
                case "tools/list" -> tools();
                case "tools/call" -> call(request);
                case "resources/list" -> emptyList("resources");
                case "prompts/list" -> emptyList("prompts");
                case "logging/setLevel" -> new JsonObject();
                default -> null;
            };
            if (result == null) {
                return new Reply(200, error(id, -32601, "Method not found: " + method), null);
            }
            return new Reply(200, ok(id, result), null);
        } catch (RuntimeException exception) {
            String message = exception.getMessage() == null ? "Internal error" : exception.getMessage();
            return new Reply(200, error(id, -32603, message), null);
        }
    }

    private static JsonObject initialize(JsonObject request, JsonElement id) {
        String version = "2024-11-05";
        JsonElement params = request.get("params");
        if (params != null && params.isJsonObject()) {
            JsonElement clientVersion = params.getAsJsonObject().get("protocolVersion");
            if (clientVersion != null && clientVersion.isJsonPrimitive()) {
                version = clientVersion.getAsString();
            }
        }
        JsonObject tools = new JsonObject();
        tools.addProperty("listChanged", false);
        JsonObject capabilities = new JsonObject();
        capabilities.add("tools", tools);
        JsonObject info = new JsonObject();
        info.addProperty("name", "mcpied");
        info.addProperty("version", "1.0.0");
        JsonObject result = new JsonObject();
        result.addProperty("protocolVersion", version);
        result.add("capabilities", capabilities);
        result.add("serverInfo", info);
        result.addProperty("instructions", "Bridge to the running Minecraft world. Search items and recipes with filters and a small limit. give_item, fill, set_block and set_block_nbt change the world. execute_command runs one command as the player (~ and @s) or as the server.");
        return ok(id, result);
    }

    private static JsonObject tools() {
        JsonObject result = new JsonObject();
        result.add("tools", McpTools.list());
        return result;
    }

    private static JsonObject call(JsonObject request) {
        JsonObject params = request.has("params") && request.get("params").isJsonObject()
                ? request.getAsJsonObject("params")
                : new JsonObject();
        if (!params.has("name") || !params.get("name").isJsonPrimitive()) {
            throw new McpException("tools/call needs a name");
        }
        JsonObject arguments = new JsonObject();
        if (params.has("arguments") && params.get("arguments").isJsonObject()) {
            arguments = params.getAsJsonObject("arguments");
        }
        return McpTools.call(params.get("name").getAsString(), arguments);
    }

    private static JsonObject emptyList(String field) {
        JsonObject result = new JsonObject();
        result.add(field, new com.google.gson.JsonArray());
        return result;
    }

    private static JsonObject ok(JsonElement id, JsonObject result) {
        JsonObject response = new JsonObject();
        response.addProperty("jsonrpc", "2.0");
        response.add("id", id == null ? com.google.gson.JsonNull.INSTANCE : id);
        response.add("result", result);
        return response;
    }

    private static JsonObject error(JsonElement id, int code, String message) {
        JsonObject body = new JsonObject();
        body.addProperty("code", code);
        body.addProperty("message", message == null ? "Error" : message);
        JsonObject response = new JsonObject();
        response.addProperty("jsonrpc", "2.0");
        response.add("id", id == null ? com.google.gson.JsonNull.INSTANCE : id);
        response.add("error", body);
        return response;
    }
}
