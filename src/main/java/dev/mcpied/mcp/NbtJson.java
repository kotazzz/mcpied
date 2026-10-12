package dev.mcpied.mcp;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.nbt.ByteTag;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.nbt.TagParser;

final class NbtJson {
    private NbtJson() {
    }

    static CompoundTag compound(JsonElement element) {
        if (element == null || element.isJsonNull()) {
            throw new McpException("NBT is empty");
        }
        if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isString()) {
            String text = element.getAsString().trim();
            if (!text.startsWith("{")) {
                throw new McpException("SNBT must be a compound starting with {");
            }
            try {
                return TagParser.parseTag(text);
            } catch (CommandSyntaxException exception) {
                throw new McpException("Bad SNBT: " + exception.getMessage());
            }
        }
        Tag tag = read(element, null, false);
        if (!(tag instanceof CompoundTag compound)) {
            throw new McpException("NBT must be a compound");
        }
        return compound;
    }

    private static Tag read(JsonElement element, String key, boolean forceDouble) {
        if (element == null || element.isJsonNull()) {
            throw new McpException("NBT value is null");
        }
        if (element.isJsonObject()) {
            CompoundTag tag = new CompoundTag();
            for (var entry : element.getAsJsonObject().entrySet()) {
                tag.put(entry.getKey(), read(entry.getValue(), entry.getKey(), false));
            }
            return tag;
        }
        if (element.isJsonArray()) {
            return readList(element.getAsJsonArray(), key);
        }
        if (!element.isJsonPrimitive()) {
            throw new McpException("Unsupported NBT JSON");
        }
        JsonPrimitive primitive = element.getAsJsonPrimitive();
        if (primitive.isString()) {
            return StringTag.valueOf(primitive.getAsString());
        }
        if (primitive.isBoolean()) {
            return ByteTag.valueOf(primitive.getAsBoolean());
        }
        if (primitive.isNumber()) {
            return number(key, primitive.getAsDouble(), forceDouble);
        }
        throw new McpException("Unsupported NBT JSON");
    }

    private static ListTag readList(JsonArray array, String key) {
        boolean asDouble = "Pos".equals(key) || "Motion".equals(key) || "Rotation".equals(key);
        ListTag list = new ListTag();
        for (JsonElement child : array) {
            try {
                list.add(read(child, null, asDouble));
            } catch (IllegalArgumentException exception) {
                throw new McpException("NBT list values must share one type");
            }
        }
        return list;
    }

    private static Tag number(String key, double value, boolean forceDouble) {
        if (forceDouble) {
            return DoubleTag.valueOf(value);
        }
        boolean whole = value == Math.rint(value) && value >= Integer.MIN_VALUE && value <= Integer.MAX_VALUE;
        if ("Slot".equals(key) && whole && value >= Byte.MIN_VALUE && value <= Byte.MAX_VALUE) {
            return ByteTag.valueOf((byte) value);
        }
        if (whole) {
            return IntTag.valueOf((int) value);
        }
        return DoubleTag.valueOf(value);
    }

    static String snbt(Tag tag) {
        String text = tag.toString();
        if (text.length() <= 200_000) {
            return text;
        }
        return text.substring(0, 200_000) + "...<truncated>";
    }

    static boolean truncated(Tag tag) {
        return tag.toString().length() > 200_000;
    }
}
