# MCPied MC

[Русский](README.ru.md)

NeoForge mod for Minecraft 1.21.1. While a world is running it opens a local [MCP](https://modelcontextprotocol.io) server, so an assistant can look at the world and change it: blocks, entities, items, commands, and the item and recipe index.

The server listens only on `127.0.0.1:47821`. It is not reachable from other machines.

## Requirements

- JDK 21
- Minecraft 1.21.1
- NeoForge 21.1.250

## Run the client

On Windows:

```bat
run-client.bat
```

Or, from the project root:

```bat
gradlew.bat runClient
```

`gradle.properties` points Gradle at a local JDK 21. If that path does not exist on your machine, set `org.gradle.java.home` to your JDK 21 install.

The dev client downloads a small set of performance and content mods into `run/mods` and, when the save exists, opens the `test` world. Another save: `gradlew.bat runClient -Pworld=MyWorld`. Skip quick play: `gradlew.bat runClient -Pworld=none`.

Join the world. The chat shows the server URL: `http://127.0.0.1:47821/mcp`.

## Connect Cursor

In the project MCP config:

```json
{
  "mcpServers": {
    "mcpied": {
      "url": "http://127.0.0.1:47821/mcp"
    }
  }
}
```

The same URL works for any MCP client that speaks Streamable HTTP. The legacy SSE endpoint is `http://127.0.0.1:47821/sse`.

Tools work only while the integrated or dedicated server is running and a player is in the world when a player anchor is required.

## Tools

| Tool | What it does |
| --- | --- |
| `list_players` | Online players: name, uuid, position, dimension, gamemode, health |
| `list_nearby_entities` | Entities in a sphere, radius at most 96 |
| `get_entity_nbt` | Full entity SNBT |
| `edit_entity` | Merge or replace entity NBT |
| `get_block` | Block id, state, and block-entity SNBT |
| `set_block` | Place one block, including states and block-entity NBT |
| `fill` | Fill a box, at most 32768 blocks, no block-entity NBT |
| `set_block_nbt` | Edit a block entity |
| `execute_command` | Run one command as a player or as the server |
| `give_item` | Give an item, with optional NBT |
| `search_items` | Search the item index by mod, id, or name |
| `search_recipes` | Search the recipe index |
| `get_recipe` | One recipe: type, result, ingredients |

`search_items` and `search_recipes` read an index built when the server starts. They do not scan the world.

## Build

```bat
gradlew.bat build
```

The mod jar is written to `build/libs`.

## License

All Rights Reserved.
