package dev.mcpied.mcp;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

final class Args {
    private final JsonObject json;

    Args(JsonObject json) {
        this.json = json == null ? new JsonObject() : json;
    }

    boolean has(String key) {
        return json.has(key) && !json.get(key).isJsonNull();
    }

    JsonElement get(String key) {
        return has(key) ? json.get(key) : null;
    }

    String str(String key) {
        JsonElement element = get(key);
        if (element == null) {
            return null;
        }
        if (!element.isJsonPrimitive()) {
            throw new McpException(key + " must be a string");
        }
        String text = element.getAsString().trim();
        return text.isEmpty() ? null : text;
    }

    String required(String key) {
        String text = str(key);
        if (text == null) {
            throw new McpException("Missing " + key);
        }
        return text;
    }

    int integer(String key, int fallback) {
        JsonElement element = get(key);
        if (element == null) {
            return fallback;
        }
        if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) {
            throw new McpException(key + " must be a number");
        }
        return element.getAsInt();
    }

    double decimal(String key, double fallback) {
        JsonElement element = get(key);
        if (element == null) {
            return fallback;
        }
        if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) {
            throw new McpException(key + " must be a number");
        }
        return element.getAsDouble();
    }
}
