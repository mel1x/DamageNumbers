package dev.melix.damagenumbers.client.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public final class DamageNumbersConfig {
    private static final Logger LOGGER = LoggerFactory.getLogger("damage-numbers/config");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH = FabricLoader.getInstance().getConfigDir().resolve("damage-numbers.json");
    private static final DamageNumbersConfig INSTANCE = new DamageNumbersConfig();

    private boolean enabled = true;
    private boolean showAllDamageSources;
    private float minimumDamage = 0.0F;
    private float scale = 0.04F;
    private boolean scaleWithDamage;
    private long fadeOutTimeMillis = 1_250L;
    private SplashAnimation splashAnimation = SplashAnimation.POP;
    private FontChoice font = FontChoice.MINECRAFT;
    private String customFontId;
    private ColorPaint fill = ColorPaint.gradient(0xFFFFC857, 0xFFE84936);
    private ColorPaint border = ColorPaint.solid(0xFF160D0A);
    private float borderWidth = 1.0F;
    private float gradientAngleDegrees = 90.0F;
    private float minimumSpawnRadius = 0.19F;
    private float maximumSpawnRadius = 0.29F;
    private String selectedPresetId = "builtin:default";
    private final List<SavedPreset> savedPresets = new ArrayList<>();
    private final List<DamageRange> damageRanges = new ArrayList<>();
    private int activeDamageRangeIndex;

    private DamageNumbersConfig() {
        damageRanges.add(new DamageRange(0.0F, snapshot()));
    }

    public static DamageNumbersConfig get() {
        return INSTANCE;
    }

    public synchronized void load() {
        if (!Files.isRegularFile(CONFIG_PATH)) {
            save();
            return;
        }
        try (Reader reader = Files.newBufferedReader(CONFIG_PATH, StandardCharsets.UTF_8)) {
            JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
            enabled = booleanValue(root, "enabled", enabled);
            showAllDamageSources = booleanValue(root, "showAllDamageSources", showAllDamageSources);
            minimumDamage = boundedFloat(root, "minimumDamage", minimumDamage, 0.0F, 2_048.0F);
            if (minimumDamage < 0.01F) {
                minimumDamage = 0.0F;
            }
            applyStyle(readStyle(root, snapshot()), false);
            readDamageRanges(root);
            activeDamageRangeIndex = Math.max(0, Math.min(damageRanges.size() - 1,
                    intValue(root, "activeDamageRangeIndex", 0)));
            applyStyle(damageRanges.get(activeDamageRangeIndex).style(), false);
            selectedPresetId = nullableStringValue(root, "selectedPresetId", selectedPresetId);
            savedPresets.clear();
            JsonArray presets = root.has("savedPresets") && root.get("savedPresets").isJsonArray()
                    ? root.getAsJsonArray("savedPresets") : new JsonArray();
            for (JsonElement element : presets) {
                if (!element.isJsonObject()) {
                    continue;
                }
                JsonObject preset = element.getAsJsonObject();
                String id = stringValue(preset, "id", "user:" + UUID.randomUUID());
                String name = stringValue(preset, "name", "Preset").trim();
                if (!name.isEmpty()) {
                    savedPresets.add(new SavedPreset(id, name, readStyle(preset, snapshot())));
                }
            }
        } catch (Exception exception) {
            LOGGER.warn("Could not load {}. Using the last valid in-memory configuration.", CONFIG_PATH, exception);
        }
    }

    public synchronized void save() {
        JsonObject root = writeStyle(snapshot());
        root.addProperty("enabled", enabled);
        root.addProperty("showAllDamageSources", showAllDamageSources);
        root.addProperty("minimumDamage", minimumDamage);
        if (selectedPresetId != null) {
            root.addProperty("selectedPresetId", selectedPresetId);
        } else {
            root.add("selectedPresetId", JsonNull.INSTANCE);
        }
        JsonArray presets = new JsonArray();
        for (SavedPreset preset : savedPresets) {
            JsonObject value = writeStyle(preset.style());
            value.addProperty("id", preset.id());
            value.addProperty("name", preset.name());
            presets.add(value);
        }
        root.add("savedPresets", presets);
        JsonArray ranges = new JsonArray();
        for (DamageRange range : damageRanges) {
            JsonObject value = writeStyle(range.style());
            value.addProperty("minimumDamage", range.minimumDamage());
            ranges.add(value);
        }
        root.add("damageRanges", ranges);
        root.addProperty("activeDamageRangeIndex", activeDamageRangeIndex);

        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            try (Writer writer = Files.newBufferedWriter(CONFIG_PATH, StandardCharsets.UTF_8)) {
                GSON.toJson(root, writer);
            }
        } catch (IOException exception) {
            LOGGER.warn("Could not save {}", CONFIG_PATH, exception);
        }
    }

    public synchronized Snapshot snapshot() {
        return new Snapshot(scale, scaleWithDamage, fadeOutTimeMillis, splashAnimation, font, customFontId,
                fill, border,
                borderWidth, gradientAngleDegrees, minimumSpawnRadius, maximumSpawnRadius);
    }

    public synchronized Snapshot styleForDamage(float damage) {
        Snapshot result = damageRanges.get(0).style();
        for (DamageRange range : damageRanges) {
            if (damage < range.minimumDamage()) {
                break;
            }
            result = range.style();
        }
        return result;
    }

    public synchronized List<DamageRange> damageRanges() {
        return List.copyOf(damageRanges);
    }

    public synchronized int activeDamageRangeIndex() {
        return activeDamageRangeIndex;
    }

    public synchronized void selectDamageRange(int index) {
        if (index < 0 || index >= damageRanges.size()) {
            throw new IllegalArgumentException("damage range index is out of bounds");
        }
        activeDamageRangeIndex = index;
        applyStyle(damageRanges.get(index).style(), false);
        selectedPresetId = null;
    }

    public synchronized int addDamageRangeAfter(int index) {
        if (index < 0 || index >= damageRanges.size()) {
            throw new IllegalArgumentException("damage range index is out of bounds");
        }
        float minimum = damageRanges.get(index).minimumDamage();
        float next = index + 1 < damageRanges.size()
                ? damageRanges.get(index + 1).minimumDamage() : Float.POSITIVE_INFINITY;
        float newMinimum = Float.isFinite(next) ? minimum + (next - minimum) * 0.5F : minimum + 21.0F;
        if (!Float.isFinite(newMinimum) || newMinimum <= minimum || newMinimum >= next) {
            return activeDamageRangeIndex;
        }
        damageRanges.add(index + 1, new DamageRange(newMinimum, damageRanges.get(index).style()));
        activeDamageRangeIndex = index + 1;
        applyStyle(damageRanges.get(activeDamageRangeIndex).style(), false);
        selectedPresetId = null;
        return activeDamageRangeIndex;
    }

    public synchronized void removeDamageRange(int index) {
        if (index <= 0 || index >= damageRanges.size()) {
            return;
        }
        damageRanges.remove(index);
        activeDamageRangeIndex = Math.min(index - 1, damageRanges.size() - 1);
        applyStyle(damageRanges.get(activeDamageRangeIndex).style(), false);
        selectedPresetId = null;
    }

    public synchronized boolean setDamageRangeMinimum(int index, float minimumDamage) {
        if (index <= 0 || index >= damageRanges.size() || !Float.isFinite(minimumDamage)) {
            return false;
        }
        float previous = damageRanges.get(index - 1).minimumDamage();
        float next = index + 1 < damageRanges.size()
                ? damageRanges.get(index + 1).minimumDamage() : Float.POSITIVE_INFINITY;
        if (minimumDamage <= previous || minimumDamage >= next) {
            return false;
        }
        DamageRange current = damageRanges.get(index);
        damageRanges.set(index, new DamageRange(minimumDamage, current.style()));
        return true;
    }

    public synchronized boolean isEnabled() {
        return enabled;
    }

    public synchronized void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public synchronized boolean showAllDamageSources() {
        return showAllDamageSources;
    }

    public synchronized void setShowAllDamageSources(boolean showAllDamageSources) {
        this.showAllDamageSources = showAllDamageSources;
    }

    public synchronized float minimumDamage() {
        return minimumDamage;
    }

    public synchronized void setMinimumDamage(float minimumDamage) {
        this.minimumDamage = clamp(minimumDamage, 0.0F, 2_048.0F, "minimumDamage");
    }

    public synchronized void setScale(float scale) {
        if (!Float.isFinite(scale) || scale <= 0.001F) {
            throw new IllegalArgumentException("scale must be finite and greater than 0.001");
        }
        this.scale = scale;
        updateActiveDamageRange();
        selectedPresetId = null;
    }

    public synchronized void setScaleWithDamage(boolean scaleWithDamage) {
        this.scaleWithDamage = scaleWithDamage;
        updateActiveDamageRange();
        selectedPresetId = null;
    }

    public synchronized void setFadeOutTimeMillis(long fadeOutTimeMillis) {
        if (fadeOutTimeMillis < 0L) {
            throw new IllegalArgumentException("fadeOutTimeMillis must be non-negative");
        }
        this.fadeOutTimeMillis = fadeOutTimeMillis;
        updateActiveDamageRange();
        selectedPresetId = null;
    }

    public synchronized void setSplashAnimation(SplashAnimation splashAnimation) {
        this.splashAnimation = Objects.requireNonNull(splashAnimation, "splashAnimation");
        updateActiveDamageRange();
        selectedPresetId = null;
    }

    public synchronized void setFont(FontChoice font) {
        this.font = Objects.requireNonNull(font, "font");
        this.customFontId = null;
        updateActiveDamageRange();
        selectedPresetId = null;
    }

    public synchronized void setCustomFont(String customFontId) {
        String normalizedId = Objects.requireNonNull(customFontId, "customFontId").trim();
        if (normalizedId.isEmpty()) {
            throw new IllegalArgumentException("customFontId cannot be empty");
        }
        this.font = FontChoice.CUSTOM;
        this.customFontId = normalizedId;
        updateActiveDamageRange();
        selectedPresetId = null;
    }

    public synchronized void removeCustomFont(String customFontId) {
        if (font == FontChoice.CUSTOM && Objects.equals(this.customFontId, customFontId)) {
            font = FontChoice.MINECRAFT;
            this.customFontId = null;
            selectedPresetId = null;
        }
        for (int index = 0; index < damageRanges.size(); index++) {
            DamageRange range = damageRanges.get(index);
            Snapshot style = range.style();
            if (style.font() == FontChoice.CUSTOM && Objects.equals(style.customFontId(), customFontId)) {
                damageRanges.set(index, new DamageRange(range.minimumDamage(), withDefaultFont(style)));
            }
        }
        applyStyle(damageRanges.get(activeDamageRangeIndex).style(), false);
        for (int index = 0; index < savedPresets.size(); index++) {
            SavedPreset preset = savedPresets.get(index);
            Snapshot style = preset.style();
            if (style.font() == FontChoice.CUSTOM && Objects.equals(style.customFontId(), customFontId)) {
                savedPresets.set(index, new SavedPreset(preset.id(), preset.name(), withDefaultFont(style)));
            }
        }
        save();
    }

    public synchronized void setFill(ColorPaint fill) {
        this.fill = Objects.requireNonNull(fill, "fill");
        updateActiveDamageRange();
        selectedPresetId = null;
    }

    public synchronized void setUnderlayColor(int argb) {
        this.border = ColorPaint.solid(argb);
        updateActiveDamageRange();
        selectedPresetId = null;
    }

    public synchronized void setBorderWidth(float borderWidth) {
        if (!Float.isFinite(borderWidth) || borderWidth < 0.0F) {
            throw new IllegalArgumentException("borderWidth must be finite and non-negative");
        }
        this.borderWidth = borderWidth;
        updateActiveDamageRange();
        selectedPresetId = null;
    }

    // 0 = horizontal, 90 = vertical.
    public synchronized void setGradientAngleDegrees(float gradientAngleDegrees) {
        if (!Float.isFinite(gradientAngleDegrees)) {
            throw new IllegalArgumentException("gradientAngleDegrees must be finite");
        }
        this.gradientAngleDegrees = normalizeAngle(gradientAngleDegrees);
        updateActiveDamageRange();
        selectedPresetId = null;
    }

    public synchronized void setSpawnRadiusRange(float minimum, float maximum) {
        if (!Float.isFinite(minimum) || !Float.isFinite(maximum) || minimum < 0.0F || maximum < minimum) {
            throw new IllegalArgumentException("spawn radius range must be finite, non-negative and ordered");
        }
        minimumSpawnRadius = minimum;
        maximumSpawnRadius = maximum;
        updateActiveDamageRange();
        selectedPresetId = null;
    }

    public synchronized String selectedPresetId() {
        return selectedPresetId;
    }

    public synchronized List<SavedPreset> savedPresets() {
        return List.copyOf(savedPresets);
    }

    public synchronized Snapshot resolvePreset(String name) {
        String query = Objects.requireNonNull(name, "name").trim();
        for (SavedPreset preset : savedPresets) {
            if (preset.id().equalsIgnoreCase(query) || preset.name().equalsIgnoreCase(query)) {
                return preset.style();
            }
        }
        for (PresetLibrary.BuiltInPreset preset : PresetLibrary.builtIns()) {
            String shortName = preset.id().substring(preset.id().indexOf(':') + 1);
            if (preset.id().equalsIgnoreCase(query) || shortName.equalsIgnoreCase(query)) {
                return preset.style();
            }
        }
        return snapshot();
    }

    public synchronized void applyPreset(String id, Snapshot style) {
        applyStyle(Objects.requireNonNull(style, "style"), false);
        updateActiveDamageRange();
        selectedPresetId = Objects.requireNonNull(id, "id");
        save();
    }

    public synchronized SavedPreset savePreset(String name) {
        String normalizedName = Objects.requireNonNull(name, "name").trim();
        if (normalizedName.isEmpty()) {
            throw new IllegalArgumentException("Preset name cannot be empty");
        }
        if (normalizedName.length() > 32) {
            normalizedName = normalizedName.substring(0, 32);
        }
        SavedPreset preset = new SavedPreset("user:" + UUID.randomUUID(), normalizedName, snapshot());
        savedPresets.add(preset);
        selectedPresetId = preset.id();
        save();
        return preset;
    }

    public synchronized void renamePreset(String id, String name) {
        String normalizedName = Objects.requireNonNull(name, "name").trim();
        if (normalizedName.isEmpty()) {
            throw new IllegalArgumentException("Preset name cannot be empty");
        }
        if (normalizedName.length() > 32) {
            normalizedName = normalizedName.substring(0, 32);
        }
        for (int index = 0; index < savedPresets.size(); index++) {
            SavedPreset preset = savedPresets.get(index);
            if (preset.id().equals(id)) {
                savedPresets.set(index, new SavedPreset(preset.id(), normalizedName, preset.style()));
                save();
                return;
            }
        }
    }

    public synchronized void deletePreset(String id) {
        if (savedPresets.removeIf(preset -> preset.id().equals(id))) {
            if (Objects.equals(selectedPresetId, id)) {
                selectedPresetId = null;
            }
            save();
        }
    }

    private void applyStyle(Snapshot style, boolean markCustom) {
        scale = Float.isFinite(style.scale()) && style.scale() > 0.001F ? style.scale() : 0.04F;
        scaleWithDamage = style.scaleWithDamage();
        fadeOutTimeMillis = Math.max(0L, style.fadeOutTimeMillis());
        splashAnimation = Objects.requireNonNull(style.splashAnimation(), "splashAnimation");
        font = Objects.requireNonNull(style.font(), "font");
        customFontId = font == FontChoice.CUSTOM ? style.customFontId() : null;
        fill = Objects.requireNonNull(style.fill(), "fill");
        border = Objects.requireNonNull(style.border(), "border");
        borderWidth = Float.isFinite(style.borderWidth()) && style.borderWidth() >= 0.0F
                ? style.borderWidth() : 1.0F;
        gradientAngleDegrees = normalizeAngle(style.gradientAngleDegrees());
        minimumSpawnRadius = Float.isFinite(style.minimumSpawnRadius()) && style.minimumSpawnRadius() >= 0.0F
                ? style.minimumSpawnRadius() : 0.19F;
        maximumSpawnRadius = Float.isFinite(style.maximumSpawnRadius())
                && style.maximumSpawnRadius() >= minimumSpawnRadius ? style.maximumSpawnRadius() : 0.29F;
        if (markCustom) {
            selectedPresetId = null;
        }
    }

    private void updateActiveDamageRange() {
        if (!damageRanges.isEmpty() && activeDamageRangeIndex >= 0
                && activeDamageRangeIndex < damageRanges.size()) {
            DamageRange current = damageRanges.get(activeDamageRangeIndex);
            damageRanges.set(activeDamageRangeIndex, new DamageRange(current.minimumDamage(), snapshot()));
        }
    }

    private void readDamageRanges(JsonObject root) {
        JsonArray values = root.has("damageRanges") && root.get("damageRanges").isJsonArray()
                ? root.getAsJsonArray("damageRanges") : new JsonArray();
        List<DamageRange> loaded = new ArrayList<>();
        Snapshot fallback = snapshot();
        for (JsonElement element : values) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject value = element.getAsJsonObject();
            float minimum = nonNegativeFloat(value, "minimumDamage", Float.NaN);
            if (!Float.isFinite(minimum)) {
                continue;
            }
            loaded.add(new DamageRange(minimum, readStyle(value, fallback)));
        }
        loaded.sort((left, right) -> Float.compare(left.minimumDamage(), right.minimumDamage()));
        damageRanges.clear();
        for (DamageRange range : loaded) {
            if (damageRanges.isEmpty()) {
                damageRanges.add(new DamageRange(0.0F, range.style()));
            } else if (range.minimumDamage() > damageRanges.get(damageRanges.size() - 1).minimumDamage()) {
                damageRanges.add(range);
            }
        }
        if (damageRanges.isEmpty()) {
            damageRanges.add(new DamageRange(0.0F, fallback));
        }
    }

    private static JsonObject writeStyle(Snapshot style) {
        JsonObject object = new JsonObject();
        object.addProperty("scale", style.scale());
        object.addProperty("scaleWithDamage", style.scaleWithDamage());
        object.addProperty("fadeOutTimeMillis", style.fadeOutTimeMillis());
        object.addProperty("splashAnimation", style.splashAnimation().name());
        object.addProperty("font", style.font().name());
        if (style.customFontId() != null) {
            object.addProperty("customFontId", style.customFontId());
        }
        object.add("fill", writePaint(style.fill()));
        object.add("border", writePaint(style.border()));
        object.addProperty("borderWidth", style.borderWidth());
        object.addProperty("gradientAngleDegrees", style.gradientAngleDegrees());
        object.addProperty("minimumSpawnRadius", style.minimumSpawnRadius());
        object.addProperty("maximumSpawnRadius", style.maximumSpawnRadius());
        return object;
    }

    private static Snapshot readStyle(JsonObject object, Snapshot fallback) {
        return new Snapshot(
                positiveScale(object, "scale", fallback.scale()),
                booleanValue(object, "scaleWithDamage", fallback.scaleWithDamage()),
                nonNegativeLong(object, "fadeOutTimeMillis", fallback.fadeOutTimeMillis()),
                enumValue(object, "splashAnimation", SplashAnimation.class, fallback.splashAnimation()),
                fontValue(object, fallback.font()),
                nullableStringValue(object, "customFontId", fallback.customFontId()),
                readPaint(object.get("fill"), fallback.fill()),
                readPaint(object.get("border"), fallback.border()),
                nonNegativeFloat(object, "borderWidth", fallback.borderWidth()),
                normalizeAngle(floatValue(object, "gradientAngleDegrees", fallback.gradientAngleDegrees())),
                nonNegativeFloat(object, "minimumSpawnRadius", fallback.minimumSpawnRadius()),
                orderedMaximumRadius(object, fallback)
        );
    }

    private static float orderedMaximumRadius(JsonObject object, Snapshot fallback) {
        float minimum = nonNegativeFloat(object, "minimumSpawnRadius", fallback.minimumSpawnRadius());
        float maximum = nonNegativeFloat(object, "maximumSpawnRadius", fallback.maximumSpawnRadius());
        return Math.max(minimum, maximum);
    }

    private static Snapshot withDefaultFont(Snapshot style) {
        return new Snapshot(style.scale(), style.scaleWithDamage(), style.fadeOutTimeMillis(),
                style.splashAnimation(), FontChoice.MINECRAFT, null, style.fill(),
                style.border(), style.borderWidth(), style.gradientAngleDegrees(), style.minimumSpawnRadius(),
                style.maximumSpawnRadius());
    }

    private static JsonObject writePaint(ColorPaint paint) {
        JsonObject object = new JsonObject();
        object.addProperty("mode", paint.mode().name());
        object.addProperty("firstArgb", paint.firstArgb());
        object.addProperty("secondArgb", paint.secondArgb());
        return object;
    }

    private static ColorPaint readPaint(JsonElement element, ColorPaint fallback) {
        if (element == null || !element.isJsonObject()) {
            return fallback;
        }
        JsonObject object = element.getAsJsonObject();
        ColorMode mode = enumValue(object, "mode", ColorMode.class, fallback.mode());
        int first = intValue(object, "firstArgb", fallback.firstArgb());
        int second = intValue(object, "secondArgb", fallback.secondArgb());
        return new ColorPaint(mode, first, second);
    }

    private static boolean booleanValue(JsonObject object, String key, boolean fallback) {
        try {
            return object.has(key) ? object.get(key).getAsBoolean() : fallback;
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }

    private static String stringValue(JsonObject object, String key, String fallback) {
        try {
            return object.has(key) ? object.get(key).getAsString() : fallback;
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }

    private static String nullableStringValue(JsonObject object, String key, String fallback) {
        if (!object.has(key)) {
            return fallback;
        }
        if (object.get(key).isJsonNull()) {
            return null;
        }
        return stringValue(object, key, fallback);
    }

    private static int intValue(JsonObject object, String key, int fallback) {
        try {
            return object.has(key) ? object.get(key).getAsInt() : fallback;
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }

    private static long longValue(JsonObject object, String key, long fallback) {
        try {
            return object.has(key) ? object.get(key).getAsLong() : fallback;
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }

    private static float floatValue(JsonObject object, String key, float fallback) {
        try {
            float value = object.has(key) ? object.get(key).getAsFloat() : fallback;
            return Float.isFinite(value) ? value : fallback;
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }

    private static float boundedFloat(JsonObject object, String key, float fallback, float min, float max) {
        return Math.max(min, Math.min(max, floatValue(object, key, fallback)));
    }

    private static long nonNegativeLong(JsonObject object, String key, long fallback) {
        return Math.max(0L, longValue(object, key, fallback));
    }

    private static float positiveScale(JsonObject object, String key, float fallback) {
        float value = floatValue(object, key, fallback);
        return Float.isFinite(value) && value > 0.001F ? value : fallback;
    }

    private static float nonNegativeFloat(JsonObject object, String key, float fallback) {
        float value = floatValue(object, key, fallback);
        return Float.isFinite(value) && value >= 0.0F ? value : fallback;
    }

    private static <E extends Enum<E>> E enumValue(JsonObject object, String key, Class<E> type, E fallback) {
        try {
            return object.has(key) ? Enum.valueOf(type, object.get(key).getAsString()) : fallback;
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }

    private static FontChoice fontValue(JsonObject object, FontChoice fallback) {
        String value = stringValue(object, "font", fallback.name());
        return switch (value) {
            case "GENSHIN_IMPACT" -> FontChoice.FANTASY;
            case "ZENLESS_ZONE_ZERO" -> FontChoice.STREET;
            default -> {
                try {
                    yield FontChoice.valueOf(value);
                } catch (IllegalArgumentException ignored) {
                    yield fallback;
                }
            }
        };
    }

    private static float normalizeAngle(float angle) {
        return ((angle % 360.0F) + 360.0F) % 360.0F;
    }

    private static float clamp(float value, float min, float max, String name) {
        if (!Float.isFinite(value) || value < min || value > max) {
            throw new IllegalArgumentException(name + " must be between " + min + " and " + max);
        }
        return value;
    }

    public enum FontChoice {
        MINECRAFT("damage-numbers:minecraft_smooth"),
        GEIST("damage-numbers:geist"),
        SANS_SERIF("damage-numbers:sans_serif"),
        POPPINS("damage-numbers:poppins"),
        FANTASY("damage-numbers:fantasy"),
        STREET("damage-numbers:street"),
        CUSTOM(null);

        private final String resourceId;

        FontChoice(String resourceId) {
            this.resourceId = resourceId;
        }

        public String resourceId() {
            return resourceId;
        }

        public String resourceId(String customFontId) {
            return this == CUSTOM && customFontId != null
                    ? "damage-numbers:custom_" + customFontId : resourceId;
        }

        public boolean isBuiltIn() {
            return this != CUSTOM;
        }
    }

    public enum SplashAnimation {
        POP,
        BOUNCE,
        RISE,
        NONE
    }

    public enum ColorMode {
        SOLID,
        GRADIENT
    }

    public record ColorPaint(ColorMode mode, int firstArgb, int secondArgb) {
        public ColorPaint {
            Objects.requireNonNull(mode, "mode");
            if (mode == ColorMode.SOLID) {
                secondArgb = firstArgb;
            }
        }

        public static ColorPaint solid(int argb) {
            return new ColorPaint(ColorMode.SOLID, argb, argb);
        }

        public static ColorPaint gradient(int startArgb, int endArgb) {
            return new ColorPaint(ColorMode.GRADIENT, startArgb, endArgb);
        }

        public int colorAt(float progress) {
            if (mode == ColorMode.SOLID) {
                return firstArgb;
            }
            float t = Math.max(0.0F, Math.min(1.0F, progress));
            int a = lerp(firstArgb >>> 24, secondArgb >>> 24, t);
            int r = lerp(firstArgb >> 16 & 0xFF, secondArgb >> 16 & 0xFF, t);
            int g = lerp(firstArgb >> 8 & 0xFF, secondArgb >> 8 & 0xFF, t);
            int b = lerp(firstArgb & 0xFF, secondArgb & 0xFF, t);
            return a << 24 | r << 16 | g << 8 | b;
        }

        private static int lerp(int from, int to, float progress) {
            return Math.round(from + (to - from) * progress);
        }
    }

    public record Snapshot(
            float scale,
            boolean scaleWithDamage,
            long fadeOutTimeMillis,
            SplashAnimation splashAnimation,
            FontChoice font,
            String customFontId,
            ColorPaint fill,
            ColorPaint border,
            float borderWidth,
            float gradientAngleDegrees,
            float minimumSpawnRadius,
            float maximumSpawnRadius
    ) {
    }

    public record SavedPreset(String id, String name, Snapshot style) {
        public SavedPreset {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(style, "style");
        }
    }

    public record DamageRange(float minimumDamage, Snapshot style) {
        public DamageRange {
            if (!Float.isFinite(minimumDamage) || minimumDamage < 0.0F) {
                throw new IllegalArgumentException("minimumDamage must be finite and non-negative");
            }
            Objects.requireNonNull(style, "style");
        }
    }
}
