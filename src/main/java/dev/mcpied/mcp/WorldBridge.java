package dev.mcpied.mcp;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.DoubleSupplier;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

final class WorldBridge {
    private static final int MAX_FILL = 32768;
    private static final int NEIGHBOR_LIMIT = 512;
    private static final ThreadLocal<Boolean> ON_SERVER = ThreadLocal.withInitial(() -> false);

    private WorldBridge() {
    }

    static JsonObject listPlayers() {
        return onServer(server -> {
            JsonArray players = new JsonArray();
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                players.add(playerJson(player));
            }
            JsonObject result = new JsonObject();
            result.add("players", players);
            return result;
        });
    }

    static JsonObject nearby(Args args) {
        return onServer(server -> {
            ServerPlayer player = playerFor(server, args, args.get("x"), args.get("y"), args.get("z"));
            double x;
            double y;
            double z;
            if (args.has("x") || args.has("y") || args.has("z")) {
                x = exact(args.get("x"), "x", player, player == null ? null : player::getX);
                y = exact(args.get("y"), "y", player, player == null ? null : player::getY);
                z = exact(args.get("z"), "z", player, player == null ? null : player::getZ);
            } else if (player != null) {
                x = player.getX();
                y = player.getY();
                z = player.getZ();
            } else {
                throw new McpException("Pass a player or x, y, z");
            }
            double radius = args.decimal("radius", 16);
            if (radius <= 0 || radius > 96) {
                throw new McpException("radius must be greater than 0 and at most 96");
            }
            String type = args.str("type");
            String typeQuery = type == null ? null : type.toLowerCase(Locale.ROOT);
            int limit = Math.max(1, Math.min(100, args.integer("limit", 20)));
            ServerLevel level = dimension(server, args, player);
            AABB box = new AABB(x - radius, y - radius, z - radius, x + radius, y + radius, z + radius);
            List<Entity> found = level.getEntities((Entity) null, box, entity -> true);
            if (found.size() > 5000) {
                throw new McpException("Too many entities (" + found.size() + "). Reduce radius.");
            }
            double radiusSq = radius * radius;
            List<Entity> matched = new ArrayList<>();
            for (Entity entity : found) {
                if (entity.distanceToSqr(x, y, z) > radiusSq) {
                    continue;
                }
                if (typeQuery != null && !entityId(entity).contains(typeQuery)) {
                    continue;
                }
                matched.add(entity);
            }
            matched.sort(Comparator.comparingDouble(entity -> entity.distanceToSqr(x, y, z)));
            JsonArray entities = new JsonArray();
            int shown = Math.min(limit, matched.size());
            for (int i = 0; i < shown; i++) {
                entities.add(entityJson(matched.get(i), x, y, z));
            }
            JsonObject result = new JsonObject();
            result.addProperty("dimension", level.dimension().location().toString());
            result.addProperty("total", matched.size());
            result.add("entities", entities);
            return result;
        });
    }

    static JsonObject entityNbt(Args args) {
        return onServer(server -> {
            Entity entity = findEntity(server, args.required("uuid"));
            CompoundTag tag = new CompoundTag();
            entity.saveWithoutId(tag);
            JsonObject result = entityJson(entity, entity.getX(), entity.getY(), entity.getZ());
            result.addProperty("nbt", NbtJson.snbt(tag));
            result.addProperty("nbtTruncated", NbtJson.truncated(tag));
            return result;
        });
    }

    static JsonObject editEntity(Args args) {
        return onServer(server -> {
            Entity entity = findEntity(server, args.required("uuid"));
            CompoundTag current = new CompoundTag();
            entity.saveWithoutId(current);
            CompoundTag patch = NbtJson.compound(args.get("nbt"));
            String mode = args.str("mode");
            boolean replace = mode != null && mode.equalsIgnoreCase("replace");
            if (replace) {
                copyIfMissing(current, patch, "UUID");
                copyIfMissing(current, patch, "Pos");
                current = patch;
            } else {
                current.merge(patch);
            }
            JsonElement remove = args.get("remove");
            if (remove != null && remove.isJsonArray()) {
                for (JsonElement key : remove.getAsJsonArray()) {
                    String name = key.getAsString();
                    if (name.equals("UUID") || name.equals("Pos") || name.equals("id")) {
                        throw new McpException("Refusing to remove " + name);
                    }
                    current.remove(name);
                }
            }
            entity.load(current);
            JsonObject result = new JsonObject();
            result.addProperty("uuid", entity.getStringUUID());
            result.addProperty("type", entityId(entity));
            result.addProperty("applied", replace ? "replace" : "merge");
            return result;
        });
    }

    static JsonObject getBlock(Args args) {
        return onServer(server -> {
            ServerPlayer player = playerFor(server, args, args.get("x"), args.get("y"), args.get("z"));
            ServerLevel level = dimension(server, args, player);
            BlockPos pos = blockPos(args, player, "x", "y", "z");
            checkHeight(level, pos.getY());
            return blockJson(level, pos);
        });
    }

    static JsonObject setBlock(Args args) {
        return onServer(server -> {
            ServerPlayer player = playerFor(server, args, args.get("x"), args.get("y"), args.get("z"));
            ServerLevel level = dimension(server, args, player);
            BlockPos pos = blockPos(args, player, "x", "y", "z");
            checkHeight(level, pos.getY());
            BlockStateParser.BlockResult parsed = parseBlock(server, args.required("block"));
            level.setBlock(pos, parsed.blockState(), Block.UPDATE_ALL);
            boolean wroteNbt = false;
            if (parsed.nbt() != null) {
                BlockEntity blockEntity = level.getBlockEntity(pos);
                if (blockEntity == null) {
                    throw new McpException("Block was placed but it has no block entity for the NBT");
                }
                CompoundTag tag = parsed.nbt().copy();
                tag.putInt("x", pos.getX());
                tag.putInt("y", pos.getY());
                tag.putInt("z", pos.getZ());
                blockEntity.loadWithComponents(tag, server.registryAccess());
                blockEntity.setChanged();
                level.sendBlockUpdated(pos, parsed.blockState(), parsed.blockState(), Block.UPDATE_ALL);
                wroteNbt = true;
            }
            JsonObject result = blockJson(level, pos);
            result.addProperty("nbtApplied", wroteNbt);
            return result;
        });
    }

    static JsonObject fill(Args args) {
        return onServer(server -> {
            ServerPlayer player = playerFor(
                    server,
                    args,
                    args.get("x1"),
                    args.get("y1"),
                    args.get("z1"),
                    args.get("x2"),
                    args.get("y2"),
                    args.get("z2")
            );
            ServerLevel level = dimension(server, args, player);
            int x1 = blockCoord(args.get("x1"), "x1", player, player == null ? null : player::getX);
            int y1 = blockCoord(args.get("y1"), "y1", player, player == null ? null : player::getY);
            int z1 = blockCoord(args.get("z1"), "z1", player, player == null ? null : player::getZ);
            int x2 = blockCoord(args.get("x2"), "x2", player, player == null ? null : player::getX);
            int y2 = blockCoord(args.get("y2"), "y2", player, player == null ? null : player::getY);
            int z2 = blockCoord(args.get("z2"), "z2", player, player == null ? null : player::getZ);
            checkHeight(level, Math.min(y1, y2));
            checkHeight(level, Math.max(y1, y2));
            long volume = volume(x1, y1, z1, x2, y2, z2);
            if (volume > MAX_FILL) {
                throw new McpException("Region is " + volume + " blocks. Split it into parts of at most " + MAX_FILL + ".");
            }
            String block = args.required("block");
            if (block.indexOf('{') >= 0) {
                throw new McpException("fill places a plain block. Use set_block for block entity NBT.");
            }
            BlockState state = parseBlock(server, block).blockState();
            boolean neighbors = volume <= NEIGHBOR_LIMIT;
            int flags = neighbors ? Block.UPDATE_ALL : Block.UPDATE_CLIENTS;
            int placed = 0;
            BlockPos min = new BlockPos(Math.min(x1, x2), Math.min(y1, y2), Math.min(z1, z2));
            BlockPos max = new BlockPos(Math.max(x1, x2), Math.max(y1, y2), Math.max(z1, z2));
            for (BlockPos pos : BlockPos.betweenClosed(min, max)) {
                if (level.getBlockState(pos) == state) {
                    continue;
                }
                level.setBlock(pos, state, flags);
                placed++;
            }
            JsonObject result = new JsonObject();
            result.addProperty("placed", placed);
            result.addProperty("volume", volume);
            result.addProperty("block", BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString());
            result.addProperty("neighborsUpdated", neighbors);
            result.addProperty("dimension", level.dimension().location().toString());
            return result;
        });
    }

    static JsonObject setBlockNbt(Args args) {
        return onServer(server -> {
            ServerPlayer player = playerFor(server, args, args.get("x"), args.get("y"), args.get("z"));
            ServerLevel level = dimension(server, args, player);
            BlockPos pos = blockPos(args, player, "x", "y", "z");
            BlockEntity blockEntity = level.getBlockEntity(pos);
            if (blockEntity == null) {
                throw new McpException("No block entity at " + pos.getX() + " " + pos.getY() + " " + pos.getZ());
            }
            CompoundTag patch = NbtJson.compound(args.get("nbt"));
            String mode = args.str("mode");
            boolean replace = mode != null && mode.equalsIgnoreCase("replace");
            if (replace) {
                blockEntity.loadCustomOnly(patch, server.registryAccess());
            } else {
                CompoundTag current = blockEntity.saveCustomOnly(server.registryAccess());
                current.merge(patch);
                blockEntity.loadCustomOnly(current, server.registryAccess());
            }
            blockEntity.setChanged();
            BlockState state = level.getBlockState(pos);
            level.sendBlockUpdated(pos, state, state, Block.UPDATE_ALL);
            JsonObject result = blockJson(level, pos);
            result.addProperty("applied", replace ? "replace" : "merge");
            return result;
        });
    }

    static JsonObject command(Args args) {
        return onServer(server -> {
            String text = args.required("command").trim();
            if (text.indexOf('\n') >= 0 || text.indexOf('\r') >= 0) {
                throw new McpException("One command per call");
            }
            if (text.length() > 16000) {
                throw new McpException("Command is too long");
            }
            if (!text.startsWith("/")) {
                text = "/" + text;
            }
            String as = args.str("as");
            if (as == null) {
                as = "player";
            }
            CapturingSource capturing = new CapturingSource();
            boolean[] ok = {true};
            int[] code = {0};
            CommandSourceStack stack;
            if (as.equalsIgnoreCase("server")) {
                ServerLevel overworld = server.overworld();
                stack = server.createCommandSourceStack()
                        .withLevel(overworld)
                        .withPosition(Vec3.atCenterOf(overworld.getSharedSpawnPos()))
                        .withSource(capturing)
                        .withPermission(4)
                        .withCallback((success, result) -> {
                            ok[0] = success;
                            code[0] = result;
                        });
            } else if (as.equalsIgnoreCase("player")) {
                ServerPlayer player = findPlayer(server, args.str("player"));
                stack = player.createCommandSourceStack()
                        .withSource(capturing)
                        .withPermission(4)
                        .withCallback((success, result) -> {
                            ok[0] = success;
                            code[0] = result;
                        });
            } else {
                throw new McpException("as must be player or server");
            }
            server.getCommands().performPrefixedCommand(stack, text);
            JsonObject result = new JsonObject();
            result.addProperty("success", ok[0]);
            result.addProperty("result", code[0]);
            result.addProperty("as", as.toLowerCase(Locale.ROOT));
            JsonArray messages = new JsonArray();
            int budget = 16000;
            int lines = 0;
            for (String line : capturing.lines) {
                if (lines >= 40 || budget <= 0) {
                    messages.add("...<truncated>");
                    break;
                }
                String clipped = line.length() > 500 ? line.substring(0, 500) : line;
                messages.add(clipped);
                budget -= clipped.length();
                lines++;
            }
            result.add("messages", messages);
            return result;
        });
    }

    static JsonObject give(Args args) {
        return onServer(server -> {
            ServerPlayer player = findPlayer(server, args.str("player"));
            String item = args.required("item").trim();
            CompoundTag tag;
            if (item.startsWith("{")) {
                tag = NbtJson.compound(new com.google.gson.JsonPrimitive(item));
            } else {
                tag = new CompoundTag();
                tag.putString("id", item);
            }
            if (args.has("nbt")) {
                tag.merge(NbtJson.compound(args.get("nbt")));
            }
            if (!item.startsWith("{")) {
                tag.putString("id", item);
            }
            int count = args.integer("count", tag.contains("count") ? tag.getInt("count") : 1);
            if (count < 1 || count > 10000) {
                throw new McpException("count must be from 1 to 10000");
            }
            tag.putInt("count", 1);
            ItemStack template = ItemStack.parse(server.registryAccess(), tag)
                    .orElseThrow(() -> new McpException("Unknown item or bad components"));
            if (template.isEmpty()) {
                throw new McpException("Item stack is empty");
            }
            int max = Math.max(1, template.getMaxStackSize());
            int left = count;
            int stored = 0;
            int dropped = 0;
            while (left > 0) {
                int amount = Math.min(max, left);
                ItemStack stack = template.copyWithCount(amount);
                player.getInventory().add(stack);
                int remainder = stack.getCount();
                stored += amount - remainder;
                if (remainder > 0) {
                    dropped += remainder;
                    player.drop(stack, false);
                }
                left -= amount;
            }
            player.inventoryMenu.broadcastChanges();
            player.containerMenu.broadcastChanges();
            JsonObject result = new JsonObject();
            result.addProperty("player", player.getScoreboardName());
            result.addProperty("item", BuiltInRegistries.ITEM.getKey(template.getItem()).toString());
            result.addProperty("count", count);
            result.addProperty("stored", stored);
            result.addProperty("dropped", dropped);
            return result;
        });
    }

    static JsonObject recipe(Args args) {
        return onServer(server -> {
            String idText = args.required("id");
            ResourceLocation id = ResourceLocation.tryParse(idText);
            if (id == null) {
                throw new McpException("Bad recipe id: " + idText);
            }
            var holder = server.getRecipeManager().byKey(id)
                    .orElseThrow(() -> new McpException("Unknown recipe: " + id));
            var recipe = holder.value();
            JsonObject result = new JsonObject();
            result.addProperty("id", holder.id().toString());
            result.addProperty("mod", holder.id().getNamespace());
            ResourceLocation typeId = BuiltInRegistries.RECIPE_TYPE.getKey(recipe.getType());
            result.addProperty("type", typeId == null ? "" : typeId.toString());
            result.addProperty("group", recipe.getGroup());
            ItemStack output = ItemStack.EMPTY;
            try {
                output = recipe.getResultItem(server.registryAccess());
            } catch (RuntimeException ignored) {
                output = ItemStack.EMPTY;
            }
            JsonObject resultItem = new JsonObject();
            if (output.isEmpty()) {
                resultItem.addProperty("id", "minecraft:air");
                resultItem.addProperty("count", 0);
            } else {
                ResourceLocation outputId = BuiltInRegistries.ITEM.getKey(output.getItem());
                resultItem.addProperty("id", outputId == null ? "minecraft:air" : outputId.toString());
                resultItem.addProperty("count", output.getCount());
                resultItem.addProperty("name", output.getHoverName().getString());
                Tag saved = output.save(server.registryAccess());
                resultItem.addProperty("snbt", NbtJson.snbt(saved));
            }
            result.add("result", resultItem);
            JsonArray ingredients = new JsonArray();
            try {
                for (var ingredient : recipe.getIngredients()) {
                    JsonObject slot = new JsonObject();
                    JsonArray choices = new JsonArray();
                    ItemStack[] stacks;
                    try {
                        stacks = ingredient.getItems();
                    } catch (RuntimeException exception) {
                        stacks = new ItemStack[0];
                    }
                    int shown = 0;
                    for (ItemStack stack : stacks) {
                        if (stack.isEmpty()) {
                            continue;
                        }
                        if (shown >= 16) {
                            break;
                        }
                        ResourceLocation choice = BuiltInRegistries.ITEM.getKey(stack.getItem());
                        if (choice != null) {
                            choices.add(choice.toString());
                        }
                        shown++;
                    }
                    slot.add("items", choices);
                    slot.addProperty("truncated", stacks.length > 16);
                    ingredients.add(slot);
                }
            } catch (RuntimeException exception) {
                result.addProperty("ingredientsError", exception.getMessage());
            }
            result.add("ingredients", ingredients);
            return result;
        });
    }

    private static <T> T onServer(java.util.function.Function<MinecraftServer, T> task) {
        if (Boolean.TRUE.equals(ON_SERVER.get())) {
            return task.apply(requireServer());
        }
        MinecraftServer server = requireServer();
        CompletableFuture<T> future = new CompletableFuture<>();
        server.execute(() -> {
            ON_SERVER.set(true);
            try {
                future.complete(task.apply(server));
            } catch (Throwable throwable) {
                future.completeExceptionally(throwable);
            } finally {
                ON_SERVER.remove();
            }
        });
        try {
            return future.get(20, TimeUnit.SECONDS);
        } catch (TimeoutException exception) {
            throw new McpException("The server thread did not answer within 20 seconds");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new McpException("Interrupted while waiting for the server thread");
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof McpException mcpException) {
                throw mcpException;
            }
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new McpException(cause == null ? exception.getMessage() : cause.getMessage());
        }
    }

    private static MinecraftServer requireServer() {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) {
            throw new McpException("World is not loaded. Join a world first.");
        }
        return server;
    }

    private static ServerPlayer playerFor(MinecraftServer server, Args args, JsonElement... coords) {
        boolean relative = false;
        for (JsonElement element : coords) {
            if (element != null && element.isJsonPrimitive() && element.getAsJsonPrimitive().isString()
                    && element.getAsString().trim().startsWith("~")) {
                relative = true;
                break;
            }
        }
        if (!args.has("player") && !relative) {
            return null;
        }
        return findPlayer(server, args.str("player"));
    }

    private static ServerPlayer findPlayer(MinecraftServer server, String query) {
        List<ServerPlayer> players = server.getPlayerList().getPlayers();
        if (query == null || query.isBlank()) {
            if (players.size() == 1) {
                return players.get(0);
            }
            if (players.isEmpty()) {
                throw new McpException("No players are online");
            }
            throw new McpException("Several players are online. Pass player. Online: " + names(players));
        }
        try {
            UUID uuid = UUID.fromString(query);
            for (ServerPlayer player : players) {
                if (player.getUUID().equals(uuid)) {
                    return player;
                }
            }
        } catch (IllegalArgumentException ignored) {
            // Name lookup below.
        }
        for (ServerPlayer player : players) {
            if (player.getScoreboardName().equals(query)) {
                return player;
            }
        }
        for (ServerPlayer player : players) {
            if (player.getScoreboardName().equalsIgnoreCase(query)) {
                return player;
            }
        }
        throw new McpException("Player not found: " + query + ". Online: " + names(players));
    }

    private static String names(List<ServerPlayer> players) {
        List<String> names = new ArrayList<>();
        for (ServerPlayer player : players) {
            names.add(player.getScoreboardName());
        }
        return String.join(", ", names);
    }

    private static ServerLevel dimension(MinecraftServer server, Args args, ServerPlayer player) {
        String name = args.str("dimension");
        if (name == null) {
            return player == null ? server.overworld() : player.serverLevel();
        }
        ResourceLocation id = ResourceLocation.tryParse(name);
        if (id == null) {
            throw new McpException("Bad dimension: " + name);
        }
        ServerLevel level = server.getLevel(ResourceKey.create(Registries.DIMENSION, id));
        if (level == null) {
            throw new McpException("Unknown dimension: " + name);
        }
        return level;
    }

    private static Entity findEntity(MinecraftServer server, String query) {
        try {
            UUID uuid = UUID.fromString(query);
            for (ServerLevel level : server.getAllLevels()) {
                Entity entity = level.getEntity(uuid);
                if (entity != null) {
                    return entity;
                }
            }
        } catch (IllegalArgumentException ignored) {
            // Fall through to a player name.
        }
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (player.getScoreboardName().equalsIgnoreCase(query) || player.getStringUUID().equalsIgnoreCase(query)) {
                return player;
            }
        }
        throw new McpException("Entity not found: " + query);
    }

    private static BlockPos blockPos(Args args, ServerPlayer player, String x, String y, String z) {
        return new BlockPos(
                blockCoord(args.get(x), x, player, player == null ? null : player::getX),
                blockCoord(args.get(y), y, player, player == null ? null : player::getY),
                blockCoord(args.get(z), z, player, player == null ? null : player::getZ)
        );
    }

    private static int blockCoord(JsonElement element, String name, ServerPlayer player, DoubleSupplier origin) {
        return Mth.floor(exact(element, name, player, origin));
    }

    private static double exact(JsonElement element, String name, ServerPlayer player, DoubleSupplier origin) {
        if (element == null || element.isJsonNull()) {
            throw new McpException("Missing " + name);
        }
        if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isNumber()) {
            return element.getAsDouble();
        }
        String text = element.getAsString().trim();
        try {
            if (text.startsWith("~")) {
                if (player == null || origin == null) {
                    throw new McpException("Relative coordinate " + name + " needs a player");
                }
                String rest = text.substring(1).trim();
                double delta = rest.isEmpty() ? 0 : Double.parseDouble(rest);
                return origin.getAsDouble() + delta;
            }
            return Double.parseDouble(text);
        } catch (NumberFormatException exception) {
            throw new McpException("Bad coordinate " + name + ": " + text);
        }
    }

    private static void checkHeight(ServerLevel level, int y) {
        if (y < level.getMinBuildHeight() || y >= level.getMaxBuildHeight()) {
            throw new McpException("Y " + y + " is outside " + level.getMinBuildHeight() + ".." + (level.getMaxBuildHeight() - 1));
        }
    }

    private static long volume(int x1, int y1, int z1, int x2, int y2, int z2) {
        long dx = Math.abs((long) x2 - x1) + 1;
        long dy = Math.abs((long) y2 - y1) + 1;
        long dz = Math.abs((long) z2 - z1) + 1;
        return dx * dy * dz;
    }

    private static BlockStateParser.BlockResult parseBlock(MinecraftServer server, String text) {
        try {
            return BlockStateParser.parseForBlock(server.registryAccess().lookupOrThrow(Registries.BLOCK), text, true);
        } catch (CommandSyntaxException exception) {
            throw new McpException("Bad block: " + exception.getMessage());
        }
    }

    private static void copyIfMissing(CompoundTag from, CompoundTag to, String key) {
        if (!to.contains(key) && from.contains(key)) {
            Tag value = from.get(key);
            if (value != null) {
                to.put(key, value.copy());
            }
        }
    }

    private static JsonObject blockJson(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        ResourceLocation id = BuiltInRegistries.BLOCK.getKey(state.getBlock());
        JsonObject result = new JsonObject();
        result.addProperty("x", pos.getX());
        result.addProperty("y", pos.getY());
        result.addProperty("z", pos.getZ());
        result.addProperty("dimension", level.dimension().location().toString());
        result.addProperty("block", id == null ? "minecraft:air" : id.toString());
        result.addProperty("state", BlockStateParser.serialize(state));
        BlockEntity blockEntity = level.getBlockEntity(pos);
        if (blockEntity != null) {
            CompoundTag tag = blockEntity.saveWithId(level.registryAccess());
            result.addProperty("nbt", NbtJson.snbt(tag));
            result.addProperty("nbtTruncated", NbtJson.truncated(tag));
        }
        return result;
    }

    private static JsonObject playerJson(ServerPlayer player) {
        JsonObject object = entityJson(player, player.getX(), player.getY(), player.getZ());
        object.addProperty("gamemode", player.gameMode.getGameModeForPlayer().getName());
        object.addProperty("health", round3(player.getHealth()));
        return object;
    }

    private static JsonObject entityJson(Entity entity, double originX, double originY, double originZ) {
        JsonObject object = new JsonObject();
        object.addProperty("uuid", entity.getStringUUID());
        object.addProperty("type", entityId(entity));
        object.addProperty("name", entity.getName().getString());
        if (entity.hasCustomName()) {
            object.addProperty("customName", entity.getCustomName().getString());
        }
        object.addProperty("x", round3(entity.getX()));
        object.addProperty("y", round3(entity.getY()));
        object.addProperty("z", round3(entity.getZ()));
        object.addProperty("distance", round3(Math.sqrt(entity.distanceToSqr(originX, originY, originZ))));
        if (entity.level() instanceof ServerLevel level) {
            object.addProperty("dimension", level.dimension().location().toString());
        }
        if (entity instanceof LivingEntity living) {
            object.addProperty("health", round3(living.getHealth()));
        }
        return object;
    }

    private static String entityId(Entity entity) {
        ResourceLocation id = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType());
        return id == null ? "minecraft:unknown" : id.toString();
    }

    private static double round3(double value) {
        return Math.round(value * 1000.0) / 1000.0;
    }

    private static final class CapturingSource implements CommandSource {
        private final List<String> lines = new ArrayList<>();

        @Override
        public void sendSystemMessage(Component message) {
            lines.add(message.getString());
        }

        @Override
        public boolean acceptsSuccess() {
            return true;
        }

        @Override
        public boolean acceptsFailure() {
            return true;
        }

        @Override
        public boolean shouldInformAdmins() {
            return false;
        }
    }
}
