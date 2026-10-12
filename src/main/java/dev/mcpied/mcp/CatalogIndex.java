package dev.mcpied.mcp;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.mcpied.McpiedMod;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.packs.resources.PreparableReloadListener;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.neoforged.fml.event.lifecycle.FMLLoadCompleteEvent;
import net.neoforged.neoforge.event.AddReloadListenerEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

public final class CatalogIndex {
    private static final int BATCH = 2000;
    private static final AtomicInteger GENERATION = new AtomicInteger();
    private static volatile boolean acceptReload;
    private static volatile ItemSnapshot items = ItemSnapshot.EMPTY;
    private static volatile RecipeSnapshot recipes = RecipeSnapshot.EMPTY;

    private CatalogIndex() {
    }

    public static void onLoadComplete(FMLLoadCompleteEvent event) {
        buildItems();
    }

    public static void onServerStarted(ServerStartedEvent event) {
        acceptReload = true;
        buildItems();
        rebuildRecipes(event.getServer());
    }

    public static void onReload(AddReloadListenerEvent event) {
        event.addListener(new ReloadHook());
    }

    static JsonObject searchItems(Args args) {
        ItemSnapshot snapshot = items;
        if (snapshot.entries.isEmpty() && BuiltInRegistries.ITEM.size() > 0) {
            buildItems();
            snapshot = items;
        }
        int limit = clampLimit(args.integer("limit", 20));
        int offset = Math.max(0, args.integer("offset", 0));
        String mod = lower(args.str("mod"));
        String id = lower(args.str("id"));
        String name = lower(args.str("name"));
        List<ItemEntry> base = snapshot.entries;
        if (mod != null) {
            base = snapshot.byMod.getOrDefault(mod, List.of());
        }
        if (id != null && id.indexOf(':') >= 0 && name == null && mod == null) {
            ItemEntry exact = snapshot.byId.get(id);
            if (exact != null) {
                return pageItems(List.of(exact), 1, offset, limit);
            }
        }
        int total = 0;
        JsonArray page = new JsonArray();
        for (ItemEntry entry : base) {
            if (id != null && !entry.id.contains(id)) {
                continue;
            }
            if (name != null && !entry.search.contains(name)) {
                continue;
            }
            if (total >= offset && page.size() < limit) {
                page.add(entry.json());
            }
            total++;
        }
        return pageObject(total, offset, limit, "items", page);
    }

    static JsonObject searchRecipes(Args args) {
        RecipeSnapshot snapshot = recipes;
        if (!snapshot.complete) {
            if (ServerLifecycleHooks.getCurrentServer() == null) {
                throw new McpException("Join a world so recipes can be indexed.");
            }
            throw new McpException("Recipe index is still building. Retry in a moment.");
        }
        int limit = clampLimit(args.integer("limit", 20));
        int offset = Math.max(0, args.integer("offset", 0));
        String mod = lower(args.str("mod"));
        String id = lower(args.str("id"));
        String name = lower(args.str("name"));
        String output = lower(args.str("output"));
        String type = lower(args.str("type"));
        String ingredient = lower(args.str("ingredient"));
        int[] indexed = null;
        if (ingredient != null && ingredient.indexOf(':') >= 0) {
            indexed = snapshot.byIngredient.get(ingredient);
            if (indexed == null) {
                return pageObject(0, offset, limit, "recipes", new JsonArray());
            }
        }
        int total = 0;
        JsonArray page = new JsonArray();
        int count = indexed == null ? snapshot.entries.size() : indexed.length;
        for (int i = 0; i < count; i++) {
            RecipeSnap snap = snapshot.entries.get(indexed == null ? i : indexed[i]);
            if (mod != null && !snap.namespace.equals(mod)) {
                continue;
            }
            if (id != null && !snap.id.contains(id)) {
                continue;
            }
            if (name != null && !snap.search.contains(name)) {
                continue;
            }
            if (output != null && !snap.outputId.contains(output)) {
                continue;
            }
            if (type != null && !snap.type.contains(type)) {
                continue;
            }
            if (ingredient != null && indexed == null && !contains(snap.ingredients, ingredient)) {
                continue;
            }
            if (total >= offset && page.size() < limit) {
                page.add(snap.json());
            }
            total++;
        }
        return pageObject(total, offset, limit, "recipes", page);
    }

    private static void buildItems() {
        List<ItemEntry> entries = new ArrayList<>();
        Map<String, List<ItemEntry>> byMod = new HashMap<>();
        Map<String, ItemEntry> byId = new HashMap<>();
        for (Item item : BuiltInRegistries.ITEM) {
            ResourceLocation id = BuiltInRegistries.ITEM.getKey(item);
            if (id == null) {
                continue;
            }
            String idText = id.toString();
            String name;
            try {
                name = new ItemStack(item).getHoverName().getString();
            } catch (RuntimeException exception) {
                name = id.getPath();
            }
            String search = (idText + " " + name + " " + id.getPath().replace('_', ' ')).toLowerCase(Locale.ROOT);
            ItemEntry entry = new ItemEntry(idText, id.getNamespace(), name, search);
            entries.add(entry);
            byId.put(idText, entry);
            byMod.computeIfAbsent(id.getNamespace(), key -> new ArrayList<>()).add(entry);
        }
        Map<String, List<ItemEntry>> frozenMods = new HashMap<>();
        for (var entry : byMod.entrySet()) {
            frozenMods.put(entry.getKey(), List.copyOf(entry.getValue()));
        }
        items = new ItemSnapshot(List.copyOf(entries), Map.copyOf(frozenMods), Map.copyOf(byId));
        McpiedMod.LOGGER.info("Indexed {} items", entries.size());
    }

    private static void rebuildRecipes(MinecraftServer server) {
        int generation = GENERATION.incrementAndGet();
        List<RecipeHolder<?>> holders = new ArrayList<>(server.getRecipeManager().getRecipes());
        recipes = RecipeSnapshot.building();
        step(server, generation, holders, 0, new ArrayList<>(holders.size()));
    }

    private static void step(
            MinecraftServer server,
            int generation,
            List<RecipeHolder<?>> holders,
            int start,
            List<RecipeSnap> collected
    ) {
        if (generation != GENERATION.get()) {
            return;
        }
        int end = Math.min(start + BATCH, holders.size());
        var access = server.registryAccess();
        for (int i = start; i < end; i++) {
            RecipeHolder<?> holder = holders.get(i);
            collected.add(snap(holder, access));
        }
        if (end < holders.size()) {
            server.execute(() -> step(server, generation, holders, end, collected));
            return;
        }
        if (generation != GENERATION.get()) {
            return;
        }
        Map<String, int[]> byIngredient = ingredientIndex(collected);
        recipes = new RecipeSnapshot(List.copyOf(collected), byIngredient, true);
        McpiedMod.LOGGER.info("Indexed {} recipes", collected.size());
    }

    private static RecipeSnap snap(RecipeHolder<?> holder, net.minecraft.core.HolderLookup.Provider access) {
        Recipe<?> recipe = holder.value();
        ResourceLocation id = holder.id();
        String type = "";
        ResourceLocation typeId = BuiltInRegistries.RECIPE_TYPE.getKey(recipe.getType());
        if (typeId != null) {
            type = typeId.toString();
        }
        String outputId = "minecraft:air";
        String outputName = "";
        int outputCount = 0;
        try {
            ItemStack result = recipe.getResultItem(access);
            if (!result.isEmpty()) {
                ResourceLocation resultId = BuiltInRegistries.ITEM.getKey(result.getItem());
                if (resultId != null) {
                    outputId = resultId.toString();
                }
                outputCount = result.getCount();
                ItemEntry known = items.byId.get(outputId);
                outputName = known == null ? result.getHoverName().getString() : known.name;
            }
        } catch (RuntimeException ignored) {
            outputId = "minecraft:air";
        }
        String[] ingredients = ingredients(recipe);
        String search = (id + " " + outputId + " " + outputName + " " + type).toLowerCase(Locale.ROOT);
        return new RecipeSnap(id.toString(), id.getNamespace(), type, outputId, outputName, outputCount, ingredients, search);
    }

    private static String[] ingredients(Recipe<?> recipe) {
        Set<String> ids = new LinkedHashSet<>();
        try {
            for (Ingredient ingredient : recipe.getIngredients()) {
                ItemStack[] stacks;
                try {
                    stacks = ingredient.getItems();
                } catch (RuntimeException exception) {
                    continue;
                }
                int taken = 0;
                for (ItemStack stack : stacks) {
                    if (stack.isEmpty()) {
                        continue;
                    }
                    ResourceLocation id = BuiltInRegistries.ITEM.getKey(stack.getItem());
                    if (id != null) {
                        ids.add(id.toString());
                    }
                    taken++;
                    if (taken >= 8 || ids.size() >= 24) {
                        return ids.toArray(String[]::new);
                    }
                }
            }
        } catch (RuntimeException ignored) {
            return new String[0];
        }
        return ids.toArray(String[]::new);
    }

    private static Map<String, int[]> ingredientIndex(List<RecipeSnap> snaps) {
        Map<String, List<Integer>> lists = new HashMap<>();
        for (int i = 0; i < snaps.size(); i++) {
            for (String item : snaps.get(i).ingredients) {
                lists.computeIfAbsent(item, key -> new ArrayList<>()).add(i);
            }
        }
        Map<String, int[]> frozen = new HashMap<>(lists.size());
        for (var entry : lists.entrySet()) {
            int[] values = new int[entry.getValue().size()];
            for (int i = 0; i < values.length; i++) {
                values[i] = entry.getValue().get(i);
            }
            frozen.put(entry.getKey(), values);
        }
        return Map.copyOf(frozen);
    }

    private static boolean contains(String[] values, String needle) {
        for (String value : values) {
            if (value.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    private static int clampLimit(int limit) {
        return Math.max(1, Math.min(100, limit));
    }

    private static String lower(String text) {
        return text == null ? null : text.toLowerCase(Locale.ROOT);
    }

    private static JsonObject pageItems(List<ItemEntry> matches, int total, int offset, int limit) {
        JsonArray page = new JsonArray();
        for (int i = offset; i < matches.size() && page.size() < limit; i++) {
            page.add(matches.get(i).json());
        }
        return pageObject(total, offset, limit, "items", page);
    }

    private static JsonObject pageObject(int total, int offset, int limit, String field, JsonArray page) {
        JsonObject result = new JsonObject();
        result.addProperty("total", total);
        result.addProperty("offset", offset);
        result.addProperty("limit", limit);
        result.add(field, page);
        return result;
    }

    private record ItemEntry(String id, String namespace, String name, String search) {
        JsonObject json() {
            JsonObject object = new JsonObject();
            object.addProperty("id", id);
            object.addProperty("mod", namespace);
            object.addProperty("name", name);
            return object;
        }
    }

    private record ItemSnapshot(List<ItemEntry> entries, Map<String, List<ItemEntry>> byMod, Map<String, ItemEntry> byId) {
        static final ItemSnapshot EMPTY = new ItemSnapshot(List.of(), Map.of(), Map.of());
    }

    private record RecipeSnap(
            String id,
            String namespace,
            String type,
            String outputId,
            String outputName,
            int outputCount,
            String[] ingredients,
            String search
    ) {
        JsonObject json() {
            JsonObject object = new JsonObject();
            object.addProperty("id", id);
            object.addProperty("mod", namespace);
            object.addProperty("type", type);
            object.addProperty("output", outputId);
            object.addProperty("outputName", outputName);
            object.addProperty("outputCount", outputCount);
            return object;
        }
    }

    private record RecipeSnapshot(List<RecipeSnap> entries, Map<String, int[]> byIngredient, boolean complete) {
        static final RecipeSnapshot EMPTY = new RecipeSnapshot(List.of(), Map.of(), false);

        static RecipeSnapshot building() {
            return new RecipeSnapshot(List.of(), Map.of(), false);
        }
    }

    private static final class ReloadHook implements PreparableReloadListener {
        @Override
        public CompletableFuture<Void> reload(
                PreparationBarrier barrier,
                ResourceManager manager,
                ProfilerFiller prep,
                ProfilerFiller reload,
                Executor background,
                Executor game
        ) {
            return barrier.wait(null).thenRunAsync(() -> {
                if (!acceptReload) {
                    return;
                }
                var server = ServerLifecycleHooks.getCurrentServer();
                if (server != null) {
                    rebuildRecipes(server);
                }
            }, game);
        }
    }
}
