package dev.melix.damagenumbers.client.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.server.packs.repository.PackRepository;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.tinyfd.TinyFileDialogs;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.function.Consumer;

public final class CustomFontManager {
    private static final Logger LOGGER = LoggerFactory.getLogger("damage-numbers/fonts");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String PACK_FOLDER = "damage-numbers-custom-fonts";
    private static final Path METADATA_PATH = FabricLoader.getInstance().getConfigDir()
            .resolve("damage-numbers-fonts.json");
    private static final Path PACK_PATH = FabricLoader.getInstance().getGameDir()
            .resolve("resourcepacks").resolve(PACK_FOLDER);
    private static boolean startupPackChecked;

    private CustomFontManager() {
    }

    public static void initialize(Minecraft minecraft) {
        if (fonts().isEmpty()) {
            return;
        }
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (!startupPackChecked && client.options != null) {
                startupPackChecked = true;
                enablePack(client, true, null);
            }
        });
    }

    public static synchronized List<CustomFont> fonts() {
        if (!Files.isRegularFile(METADATA_PATH)) {
            return List.of();
        }
        List<CustomFont> fonts = new ArrayList<>();
        try (Reader reader = Files.newBufferedReader(METADATA_PATH, StandardCharsets.UTF_8)) {
            JsonElement root = JsonParser.parseReader(reader);
            JsonArray array = root.isJsonArray() ? root.getAsJsonArray() : new JsonArray();
            for (JsonElement element : array) {
                if (!element.isJsonObject()) {
                    continue;
                }
                JsonObject object = element.getAsJsonObject();
                String id = object.has("id") ? object.get("id").getAsString() : "";
                String name = object.has("name") ? object.get("name").getAsString() : "";
                String extension = object.has("extension") ? object.get("extension").getAsString() : ".ttf";
                if (!id.isBlank() && !name.isBlank()) {
                    fonts.add(new CustomFont(id, name, extension));
                }
            }
        } catch (Exception exception) {
            LOGGER.warn("Could not read custom font metadata", exception);
        }
        return List.copyOf(fonts);
    }

    public static void openImportDialog(Minecraft minecraft, Consumer<CustomFont> onImported) {
        Thread picker = new Thread(() -> {
            String selected;
            try (MemoryStack stack = MemoryStack.stackPush()) {
                PointerBuffer filters = stack.mallocPointer(2);
                filters.put(stack.UTF8("*.ttf"));
                filters.put(stack.UTF8("*.otf"));
                filters.flip();
                selected = TinyFileDialogs.tinyfd_openFileDialog(
                        "Add custom font", "", filters, "TTF / OTF fonts", false);
            }
            if (selected == null) {
                return;
            }
            try {
                CustomFont imported = importFont(Path.of(selected));
                minecraft.execute(() -> enablePack(minecraft, true,
                        () -> onImported.accept(imported)));
            } catch (Exception exception) {
                LOGGER.warn("Could not import custom font {}", selected, exception);
            }
        }, "DamageNumbers font picker");
        picker.setDaemon(true);
        picker.start();
    }

    public static synchronized void deleteFont(Minecraft minecraft, String fontId, Runnable onDeleted) {
        CustomFont font = fonts().stream().filter(candidate -> candidate.id().equals(fontId))
                .findFirst().orElse(null);
        if (font == null) {
            if (onDeleted != null) {
                onDeleted.run();
            }
            return;
        }
        try {
            Files.deleteIfExists(PACK_PATH.resolve("assets/damage-numbers/font/custom")
                    .resolve(font.id() + font.extension()));
            Files.deleteIfExists(PACK_PATH.resolve("assets/damage-numbers/font/custom_" + font.id() + ".json"));
            List<CustomFont> remaining = new ArrayList<>(fonts());
            remaining.removeIf(candidate -> candidate.id().equals(font.id()));
            saveMetadata(remaining);
            enablePack(minecraft, true, onDeleted);
        } catch (IOException exception) {
            LOGGER.warn("Could not delete custom font {}", font.id(), exception);
        }
    }

    private static synchronized CustomFont importFont(Path source) throws IOException {
        if (!Files.isRegularFile(source)) {
            throw new IOException("Font file does not exist: " + source);
        }
        String fileName = source.getFileName().toString();
        int dot = fileName.lastIndexOf('.');
        String extension = dot >= 0 ? fileName.substring(dot).toLowerCase(Locale.ROOT) : "";
        if (!extension.equals(".ttf") && !extension.equals(".otf")) {
            throw new IOException("Only TTF and OTF fonts are supported");
        }
        byte[] header;
        try (var input = Files.newInputStream(source)) {
            header = input.readNBytes(4);
        }
        if (header.length < 4 || header[0] == 'O' && header[1] == 'T'
                && header[2] == 'T' && header[3] == 'O') {
            throw new IOException("CFF-based OpenType fonts are not supported by Minecraft");
        }
        String displayName = dot > 0 ? fileName.substring(0, dot) : fileName;
        String slug = displayName.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "_").replaceAll("^_+|_+$", "");
        if (slug.isEmpty()) {
            slug = "font";
        }
        if (slug.length() > 24) {
            slug = slug.substring(0, 24);
        }
        String id = slug + "_" + UUID.randomUUID().toString().substring(0, 8);
        CustomFont font = new CustomFont(id, displayName, extension);

        ensurePackStructure();
        Path fontDirectory = PACK_PATH.resolve("assets/damage-numbers/font/custom");
        Files.createDirectories(fontDirectory);
        Files.copy(source, fontDirectory.resolve(id + extension), StandardCopyOption.REPLACE_EXISTING);
        String definition = """
                {
                  "providers": [
                    {
                      "type": "ttf",
                      "file": "damage-numbers:custom/%s%s",
                      "shift": [0.0, 1.0],
                      "size": 11.0,
                      "oversample": 16.0
                    }
                  ]
                }
                """.formatted(id, extension);
        Files.writeString(PACK_PATH.resolve("assets/damage-numbers/font/custom_" + id + ".json"),
                definition, StandardCharsets.UTF_8);

        List<CustomFont> fonts = new ArrayList<>(fonts());
        fonts.add(font);
        saveMetadata(fonts);
        return font;
    }

    private static void ensurePackStructure() throws IOException {
        Files.createDirectories(PACK_PATH.resolve("assets/damage-numbers/font"));
        String metadata = """
                {
                  "pack": {
                    "pack_format": 15,
                    "supported_formats": {
                      "min_inclusive": 15,
                      "max_inclusive": 999
                    },
                    "description": "DamageNumbers custom fonts"
                  }
                }
                """;
        Files.writeString(PACK_PATH.resolve("pack.mcmeta"), metadata, StandardCharsets.UTF_8);
    }

    private static void saveMetadata(List<CustomFont> fonts) throws IOException {
        Files.createDirectories(METADATA_PATH.getParent());
        JsonArray array = new JsonArray();
        for (CustomFont font : fonts) {
            JsonObject object = new JsonObject();
            object.addProperty("id", font.id());
            object.addProperty("name", font.name());
            object.addProperty("extension", font.extension());
            array.add(object);
        }
        try (Writer writer = Files.newBufferedWriter(METADATA_PATH, StandardCharsets.UTF_8)) {
            GSON.toJson(array, writer);
        }
    }

    private static void enablePack(Minecraft minecraft, boolean reload, Runnable afterReload) {
        try {
            PackRepository repository = minecraft.getResourcePackRepository();
            repository.reload();
            String packId = repository.getAvailableIds().stream()
                    .filter(id -> id.contains(PACK_FOLDER))
                    .findFirst().orElse(null);
            if (packId == null) {
                LOGGER.warn("Custom font resource pack was not discovered at {}", PACK_PATH);
                return;
            }
            boolean selectionChanged = false;
            if (!repository.getSelectedIds().contains(packId)) {
                repository.addPack(packId);
                minecraft.options.updateResourcePacks(repository);
                minecraft.options.save();
                selectionChanged = true;
            }
            if (reload && (selectionChanged || afterReload != null)) {
                minecraft.reloadResourcePacks().whenComplete((unused, error) -> minecraft.execute(() -> {
                    if (error != null) {
                        LOGGER.warn("Could not reload custom font resource pack", error);
                    } else if (afterReload != null) {
                        afterReload.run();
                    }
                }));
            } else if (afterReload != null) {
                afterReload.run();
            }
        } catch (Exception exception) {
            LOGGER.warn("Could not enable custom font resource pack", exception);
        }
    }

    public record CustomFont(String id, String name, String extension) {
    }
}
