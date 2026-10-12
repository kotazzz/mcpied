package dev.mcpied;

import com.mojang.logging.LogUtils;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import dev.mcpied.mcp.CatalogIndex;
import dev.mcpied.mcp.McpServer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import org.slf4j.Logger;

@Mod(McpiedMod.MOD_ID)
public class McpiedMod {
    public static final String MOD_ID = "mcpied";
    public static final Logger LOGGER = LogUtils.getLogger();

    public McpiedMod(IEventBus modEventBus, ModContainer modContainer) {
        NeoForge.EVENT_BUS.addListener(McpiedMod::registerCommands);
        NeoForge.EVENT_BUS.addListener(McpServer::onPlayerLogin);
        NeoForge.EVENT_BUS.addListener(CatalogIndex::onServerStarted);
        NeoForge.EVENT_BUS.addListener(CatalogIndex::onReload);
        modEventBus.addListener(CatalogIndex::onLoadComplete);
        McpServer.start();
        LOGGER.info("MCPied MC loaded");
    }

    private static void registerCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(
                Commands.literal("test")
                        .executes(context -> {
                            context.getSource().sendSuccess(
                                    () -> Component.translatable("commands.mcpied.test.success"),
                                    false
                            );
                            return 1;
                        })
        );
    }
}
