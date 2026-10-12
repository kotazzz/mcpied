package dev.mcpied.mcp;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.mcpied.McpiedMod;

final class McpTools {
    private static final JsonArray TOOLS = buildTools();

    private McpTools() {
    }

    static JsonArray list() {
        return TOOLS;
    }

    static JsonObject call(String name, JsonObject arguments) {
        Args args = new Args(arguments);
        try {
            JsonObject data = switch (name) {
                case "list_players" -> WorldBridge.listPlayers();
                case "list_nearby_entities" -> WorldBridge.nearby(args);
                case "get_entity_nbt" -> WorldBridge.entityNbt(args);
                case "edit_entity" -> WorldBridge.editEntity(args);
                case "get_block" -> WorldBridge.getBlock(args);
                case "set_block" -> WorldBridge.setBlock(args);
                case "fill" -> WorldBridge.fill(args);
                case "set_block_nbt" -> WorldBridge.setBlockNbt(args);
                case "execute_command" -> WorldBridge.command(args);
                case "give_item" -> WorldBridge.give(args);
                case "search_items" -> CatalogIndex.searchItems(args);
                case "search_recipes" -> CatalogIndex.searchRecipes(args);
                case "get_recipe" -> WorldBridge.recipe(args);
                default -> throw new McpException("Unknown tool: " + name);
            };
            McpiedMod.LOGGER.info("MCP tool {} ok", name);
            return content(data, false);
        } catch (McpException exception) {
            McpiedMod.LOGGER.info("MCP tool {} failed: {}", name, exception.getMessage());
            return content(text(exception.getMessage()), true);
        } catch (RuntimeException exception) {
            McpiedMod.LOGGER.error("MCP tool {} crashed", name, exception);
            String message = exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
            return content(text(message), true);
        }
    }

    private static JsonObject content(JsonObject data, boolean error) {
        return content(data.toString(), error);
    }

    private static JsonObject content(String text, boolean error) {
        JsonObject result = new JsonObject();
        JsonArray content = new JsonArray();
        JsonObject part = new JsonObject();
        part.addProperty("type", "text");
        part.addProperty("text", text);
        content.add(part);
        result.add("content", content);
        result.addProperty("isError", error);
        return result;
    }

    private static String text(String message) {
        return message == null || message.isBlank() ? "Unknown error" : message;
    }

    private static JsonArray buildTools() {
        JsonArray tools = new JsonArray();
        tools.add(tool(
                "list_players",
                "Online players: uuid, name, position, dimension, gamemode and health.",
                props(),
                new String[0]
        ));
        tools.add(tool(
                "list_nearby_entities",
                "Entities inside a sphere. Center on a player or on x, y, z. type matches the entity id, for example minecraft:zombie or zombie. radius is at most 96.",
                props(
                        "player", str("Player name or uuid. Used when coordinates are omitted or relative."),
                        "x", coord("Center X. Number, or a string like ~ or ~-2."),
                        "y", coord("Center Y."),
                        "z", coord("Center Z."),
                        "radius", num("Sphere radius. Default 16, max 96."),
                        "type", str("Entity id filter."),
                        "dimension", str("Dimension id. Default is the player's dimension, or minecraft:overworld."),
                        "limit", num("Max results. Default 20, max 100.")
                ),
                new String[0]
        ));
        tools.add(tool(
                "get_entity_nbt",
                "Full entity SNBT. uuid may also be a player name.",
                props("uuid", str("Entity uuid from list_nearby_entities or list_players, or a player name.")),
                "uuid"
        ));
        tools.add(tool(
                "edit_entity",
                "Writes entity data. mode merge folds SNBT into the current data. mode replace loads the supplied compound and keeps UUID and Pos unless you set them. remove deletes top-level keys before load. Do not remove UUID, Pos or id.",
                props(
                        "uuid", str("Entity uuid or player name."),
                        "nbt", nbt("SNBT compound string, or a JSON object."),
                        "mode", str("merge (default) or replace."),
                        "remove", strs("Top-level NBT keys to delete.")
                ),
                "uuid", "nbt"
        ));
        tools.add(tool(
                "get_block",
                "Block id, block state and block-entity SNBT at one coordinate.",
                props(
                        "x", coord("Absolute number or relative string (~, ~-1). Relative needs a player."),
                        "y", coord("Y coordinate."),
                        "z", coord("Z coordinate."),
                        "player", str("Anchor for relative coordinates."),
                        "dimension", str("Dimension id. Defaults to the player dimension or overworld.")
                ),
                "x", "y", "z"
        ));
        tools.add(tool(
                "set_block",
                "Places one block. block examples: minecraft:stone, minecraft:oak_stairs[facing=east,half=bottom], minecraft:chest[facing=north]{Items:[{Slot:0b,id:\"minecraft:stone\",count:64}]}. Relative coordinates need a player.",
                props(
                        "x", coord("X."),
                        "y", coord("Y."),
                        "z", coord("Z."),
                        "block", str("Block id, optional states, optional SNBT."),
                        "player", str("Anchor for ~ coordinates."),
                        "dimension", str("Dimension id.")
                ),
                "x", "y", "z", "block"
        ));
        tools.add(tool(
                "fill",
                "Fills a solid box, for example a stone platform. At most 32768 blocks. Regions larger than 512 blocks skip neighbor updates. No block-entity NBT; use set_block for that. Split bigger builds into several calls.",
                props(
                        "x1", coord("First corner X."),
                        "y1", coord("First corner Y."),
                        "z1", coord("First corner Z."),
                        "x2", coord("Second corner X."),
                        "y2", coord("Second corner Y."),
                        "z2", coord("Second corner Z."),
                        "block", str("Plain block id or state, for example minecraft:stone."),
                        "player", str("Anchor for ~ coordinates."),
                        "dimension", str("Dimension id.")
                ),
                "x1", "y1", "z1", "x2", "y2", "z2", "block"
        ));
        tools.add(tool(
                "set_block_nbt",
                "Edits a block entity. Clear a chest with nbt {Items:[]} . Put enchanted armor in slot 0 with nbt {Items:[{Slot:0b,id:\"minecraft:diamond_chestplate\",count:1,components:{\"minecraft:enchantments\":{levels:{\"minecraft:protection\":4}}}}]}. nbt is an SNBT string or a JSON object. In SNBT, Slot is a byte (0b).",
                props(
                        "x", coord("X."),
                        "y", coord("Y."),
                        "z", coord("Z."),
                        "nbt", nbt("Compound to merge or replace."),
                        "mode", str("merge (default) or replace."),
                        "player", str("Anchor for ~ coordinates."),
                        "dimension", str("Dimension id.")
                ),
                "x", "y", "z", "nbt"
        ));
        tools.add(tool(
                "execute_command",
                "Runs one Minecraft command. as=player uses that player's position, so ~ and @s work. as=server runs as the console at overworld spawn; name the player instead of @s. Slash is optional. Examples: give Dev minecraft:diamond 64, kill @e[type=minecraft:zombie,distance=..32], fill ~-3 ~-1 ~-3 ~3 ~-1 ~3 minecraft:stone, data remove block 10 64 10 Items.",
                props(
                        "command", str("One command."),
                        "as", str("player (default) or server."),
                        "player", str("Which player when as=player. Optional if only one player is online.")
                ),
                "command"
        ));
        tools.add(tool(
                "give_item",
                "Gives items to a player. item is an id, or a full SNBT stack starting with {. Optional nbt merges into the stack. Enchanted armor: item minecraft:diamond_chestplate and nbt {components:{\"minecraft:enchantments\":{levels:{\"minecraft:protection\":4,\"minecraft:unbreaking\":3}}}}.",
                props(
                        "player", str("Player name or uuid. Optional if only one player is online."),
                        "item", str("Item id or full SNBT stack."),
                        "count", num("Stack count from 1 to 10000. Default 1."),
                        "nbt", nbt("Extra SNBT or JSON merged into the stack, usually components.")
                ),
                "item"
        ));
        tools.add(tool(
                "search_items",
                "Indexed item search for large modpacks. Filters are combined and the result is paginated. mod is the namespace, such as create or minecraft. id matches the full id. name matches the display name and the id path. limit default 20, max 100. Does not scan the world.",
                props(
                        "mod", str("Namespace / mod id."),
                        "id", str("Full or partial item id."),
                        "name", str("Display name or id path."),
                        "limit", num("Page size, max 100."),
                        "offset", num("How many matches to skip.")
                ),
                new String[0]
        ));
        tools.add(tool(
                "search_recipes",
                "Indexed recipe search. Use mod, id, type (minecraft:crafting, minecraft:smelting), output item id, ingredient item id, or output name. A full ingredient id is an instant lookup. limit max 100. Call get_recipe for ingredients and result SNBT. Does not walk every recipe on each call.",
                props(
                        "mod", str("Namespace of the recipe id."),
                        "id", str("Full or partial recipe id."),
                        "type", str("Recipe type id."),
                        "output", str("Result item id."),
                        "ingredient", str("Input item id. Prefer a full id."),
                        "name", str("Result display name."),
                        "limit", num("Page size, max 100."),
                        "offset", num("How many matches to skip.")
                ),
                new String[0]
        ));
        tools.add(tool(
                "get_recipe",
                "One recipe by id: type, result SNBT and ingredient item ids.",
                props("id", str("Recipe id, for example minecraft:stone_sword or create:crafting/andesite_alloy.")),
                "id"
        ));
        return tools;
    }

    private static JsonObject tool(String name, String description, JsonObject properties, String... required) {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        schema.add("properties", properties);
        if (required.length > 0) {
            JsonArray array = new JsonArray();
            for (String key : required) {
                array.add(key);
            }
            schema.add("required", array);
        }
        JsonObject tool = new JsonObject();
        tool.addProperty("name", name);
        tool.addProperty("description", description);
        tool.add("inputSchema", schema);
        return tool;
    }

    private static JsonObject props(Object... pairs) {
        JsonObject object = new JsonObject();
        for (int i = 0; i < pairs.length; i += 2) {
            object.add((String) pairs[i], (JsonObject) pairs[i + 1]);
        }
        return object;
    }

    private static JsonObject str(String description) {
        return prop("string", description);
    }

    private static JsonObject num(String description) {
        return prop("number", description);
    }

    private static JsonObject strs(String description) {
        JsonObject object = prop("array", description);
        JsonObject items = new JsonObject();
        items.addProperty("type", "string");
        object.add("items", items);
        return object;
    }

    private static JsonObject nbt(String description) {
        JsonObject object = new JsonObject();
        JsonArray types = new JsonArray();
        types.add("string");
        types.add("object");
        object.add("type", types);
        object.addProperty("description", description);
        return object;
    }

    private static JsonObject coord(String description) {
        JsonObject object = new JsonObject();
        JsonArray types = new JsonArray();
        types.add("number");
        types.add("integer");
        types.add("string");
        object.add("type", types);
        object.addProperty("description", description);
        return object;
    }

    private static JsonObject prop(String type, String description) {
        JsonObject object = new JsonObject();
        object.addProperty("type", type);
        object.addProperty("description", description);
        return object;
    }
}
