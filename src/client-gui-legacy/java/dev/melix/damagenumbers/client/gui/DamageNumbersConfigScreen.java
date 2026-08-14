package dev.melix.damagenumbers.client.gui;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.melix.damagenumbers.client.config.CustomFontManager;
import dev.melix.damagenumbers.client.config.CustomFontManager.CustomFont;
import dev.melix.damagenumbers.client.config.DamageNumbersConfig;
import dev.melix.damagenumbers.client.config.DamageNumbersConfig.AppearanceAnimation;
import dev.melix.damagenumbers.client.config.DamageNumbersConfig.AppearanceAnimationScope;
import dev.melix.damagenumbers.client.config.DamageNumbersConfig.ColorMode;
import dev.melix.damagenumbers.client.config.DamageNumbersConfig.ColorPaint;
import dev.melix.damagenumbers.client.config.DamageNumbersConfig.DamageRange;
import dev.melix.damagenumbers.client.config.DamageNumbersConfig.FontChoice;
import dev.melix.damagenumbers.client.config.DamageNumbersConfig.SavedPreset;
import dev.melix.damagenumbers.client.config.DamageNumbersConfig.Snapshot;
import dev.melix.damagenumbers.client.config.PresetLibrary;
import dev.melix.damagenumbers.client.render.AppearanceAnimator;
import dev.melix.damagenumbers.client.render.FontStyleResolver;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.joml.Quaternionf;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.BooleanSupplier;
import java.util.function.DoubleConsumer;
import java.util.function.DoubleSupplier;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;

/**
 * The mod configuration screen. Every control is painted and hit-tested by the screen itself, so
 * the only real widgets are the text fields; that keeps the layout, the scrolling and the hover
 * animations under one clock instead of spreading them over vanilla widget state.
 */
public final class DamageNumbersConfigScreen extends DamageNumbersScreenBase {
    private static final int BACKDROP = 0xFF0A0C11;
    private static final int PANEL = 0xFF12151B;
    private static final int SURFACE = 0xFF181D25;
    private static final int SURFACE_HOVER = 0xFF1F2531;
    private static final int SURFACE_ACTIVE = 0xFF252D3B;
    private static final int OUTLINE = 0xFF252B36;
    private static final int OUTLINE_SOFT = 0xFF1D222B;
    private static final int TRACK = 0xFF2B323F;
    private static final int ACCENT = 0xFF58A6FF;
    private static final int ACCENT_DEEP = 0xFF2E63A8;
    private static final int TEXT = 0xFFF1F4F9;
    private static final int MUTED = 0xFF98A2B1;
    private static final int FAINT = 0xFF616A78;
    private static final int DANGER = 0xFFFF7B72;
    private static final int SHADOW = 0xFF070910;

    private static final int ROW_GAP = 5;
    private static final int PADDING = 11;
    private static final int GUTTER = 12;
    private static final int HEADER_STRIP_TOP = 44;
    private static final int STRIP_HEIGHT = 24;
    private static final int PREVIEW_OUTLINE_DIRECTIONS = 8;
    private static final int FONT_CARD_WIDTH = 92;
    private static final int FONT_CARD_HEIGHT = 42;
    private static final int PRESET_CARD_HEIGHT = 80;
    private static final int POPUP_ROW_HEIGHT = 18;
    private static final int POPUP_MAX_ROWS = 7;
    private static final double SCROLL_SMOOTH_TIME = 0.15D;
    private static final float HOVER_RATE = 11.0F;
    private static final int BLUR_DIRECTIONS = 8;
    private static final long PREVIEW_SPAWN_INTERVAL_NANOS = 520_000_000L;

    private static final boolean TRANSFORMED_SCISSOR = FabricLoader.getInstance()
            .getModContainer("minecraft")
            .map(container -> {
                String version = container.getMetadata().getVersion().getFriendlyString();
                return version.startsWith("1.21.4") || version.startsWith("1.21.5");
            })
            .orElse(false);
    private static final int[] COLOR_PALETTE = {
            0xFFFFFFFF, 0xFFFFD84D, 0xFFFF8A3D, 0xFFFF3B30, 0xFFFF3158,
            0xFFB967FF, 0xFF4D8DFF, 0xFF4DE8FF, 0xFF55F29A, 0xFF20242A, 0xFF000000
    };

    private final Screen parent;
    private final DamageNumbersConfig config = DamageNumbersConfig.get();
    private final List<Row> rows = new ArrayList<>();
    private final List<EditBox> textFields = new ArrayList<>();
    private final List<PreviewNumber> previewNumbers = new ArrayList<>();
    private final List<RangeSegment> rangeSegments = new ArrayList<>();
    private final float[] navHover = new float[Page.values().length];

    private Page page = Page.PRESETS;
    private float navIndicator;
    private float doneHover;
    private float actionHover;
    private float addRangeHover;
    private float removeRangeHover;
    private float scrollbarHover;

    private final Smoothed scroll = new Smoothed();
    private double scrollGoal;
    private double scrollMax;
    private int scrollBase;
    private float scrollFraction;
    private final Smoothed fontScroll = new Smoothed();
    private double fontScrollGoal;
    private double fontScrollMax;
    private double popupScroll;
    private double popupScrollGoal;
    private double popupScrollMax;

    private SelectRow openSelect;
    private float popupOpen;
    private Dragging dragging = Dragging.NONE;
    private SliderRow draggedSlider;
    private double scrollbarGrab;

    private Snapshot cachedStyle;
    private int headerFieldCount;
    private boolean advancedExpanded;
    private boolean rebuilding;
    private boolean dirty;
    private long lastFrameNanos;
    private long lastPreviewSpawnNanos;
    private float previewAngleCursor;
    private float frameDelta = 1.0F / 60.0F;

    public DamageNumbersConfigScreen(Screen parent) {
        super(Component.translatable("damage_numbers.screen.title"));
        this.parent = parent;
    }

    // ------------------------------------------------------------------ build

    @Override
    protected void init() {
        rebuild();
    }

    private void rebuild() {
        if (rebuilding) {
            return;
        }
        rebuilding = true;
        closeSelect();
        clearWidgets();
        textFields.clear();
        rows.clear();
        rangeSegments.clear();
        buildRangeStrip();
        // Everything registered past this point lives inside the scrolling viewport.
        headerFieldCount = textFields.size();
        switch (page) {
            case PRESETS -> buildPresetRows();
            case CUSTOMIZATION -> buildCustomizationRows();
            case MOD_SETTINGS -> buildModSettingsRows();
        }
        layoutRows();
        rebuilding = false;
    }

    private void addRow(Row row) {
        rows.add(row);
    }

    private void layoutRows() {
        int y = 0;
        for (Row row : rows) {
            row.layout();
            row.top = y;
            y += row.height + ROW_GAP;
        }
        scrollMax = Math.max(0.0D, y - ROW_GAP - viewportHeight());
        scrollGoal = clamp(scrollGoal, 0.0D, scrollMax);
        scroll.set(clamp(scroll.value(), 0.0D, scrollMax));
    }

    private void buildRangeStrip() {
        if (!showsRangeStrip()) {
            return;
        }
        List<DamageRange> ranges = config.damageRanges();
        int active = config.activeDamageRangeIndex();
        int left = contentX();
        int right = contentRight();
        int controls = active > 0 ? 24 + 4 + 58 + 4 + 24 : 24;
        int trackWidth = Math.max(60, right - left - controls - 6);
        int segmentWidth = Math.max(1, trackWidth / ranges.size());
        for (int index = 0; index < ranges.size(); index++) {
            int segmentLeft = left + index * segmentWidth;
            int segmentRight = index == ranges.size() - 1 ? left + trackWidth : segmentLeft + segmentWidth;
            rangeSegments.add(new RangeSegment(index, segmentLeft, segmentRight - segmentLeft));
        }
        if (active > 0) {
            EditBox start = textField(left + trackWidth + 6 + 24 + 4, HEADER_STRIP_TOP, 58, STRIP_HEIGHT,
                    formatRangeValue(ranges.get(active).minimumDamage()), true,
                    Component.translatable("damage_numbers.ranges.start"));
            start.setResponder(this::applyDamageRangeInput);
        }
    }

    private void buildPresetRows() {
        List<PresetCard> builtIn = new ArrayList<>();
        for (PresetLibrary.BuiltInPreset preset : PresetLibrary.builtIns()) {
            builtIn.add(new PresetCard(preset.id(), Component.translatable(preset.translationKey()),
                    preset.style(), false));
        }
        List<PresetCard> custom = new ArrayList<>();
        for (SavedPreset preset : config.savedPresets()) {
            custom.add(new PresetCard(preset.id(), Component.literal(preset.name()), preset.style(), true));
        }
        addRow(new SectionRow(Component.translatable("damage_numbers.presets.built_in"), false));
        addPresetGrid(builtIn);
        addRow(new SectionRow(Component.translatable("damage_numbers.presets.custom"), true));
        if (custom.isEmpty()) {
            addRow(new HintRow(Component.translatable("damage_numbers.presets.empty")));
        } else {
            addPresetGrid(custom);
        }
    }

    private void addPresetGrid(List<PresetCard> cards) {
        int width = rowWidth();
        int columns = Math.max(2, Math.min(4, (width + 6) / 124));
        for (int index = 0; index < cards.size(); index += columns) {
            addRow(new PresetGridRow(cards.subList(index, Math.min(cards.size(), index + columns)), columns));
        }
    }

    private void buildCustomizationRows() {
        addRow(new SectionRow(Component.translatable("damage_numbers.section.font"), false));
        addRow(new FontStripRow());
        addRow(new SectionRow(Component.translatable("damage_numbers.section.appearance"), true));
        // Shown in thousandths so the field takes a plain integer: 32 on screen is 0.032 in the file.
        addRow(new SliderRow(Component.translatable("damage_numbers.customization.scale"), null,
                4.0D, 120.0D, 1.0D, 0,
                () -> style().scale() * 1_000.0D,
                value -> config.setScale((float) clamp(value / 1_000.0D, 0.002D, 4.0D))));
        addRow(new ToggleRow(Component.translatable("damage_numbers.customization.scale_with_damage"), null,
                () -> style().scaleWithDamage(),
                () -> config.setScaleWithDamage(!style().scaleWithDamage())));
        addRow(new SliderRow(Component.translatable("damage_numbers.customization.border_width"), null,
                0.0D, 4.0D, 0.05D, 2,
                () -> style().borderWidth(),
                value -> config.setBorderWidth((float) Math.max(0.0D, value))));
        addRow(new SectionRow(Component.translatable("damage_numbers.section.colors"), true));
        addRow(new SelectRow(Component.translatable("damage_numbers.customization.fill_mode"), null,
                List.of(colorModeName(ColorMode.SOLID), colorModeName(ColorMode.GRADIENT)),
                () -> style().fill().mode().ordinal(), this::applyFillMode));
        addRow(new ColorRow(Component.translatable("damage_numbers.customization.color_first"),
                ColorPickerScreen.Target.FILL_FIRST, () -> true,
                () -> style().fill().firstArgb()));
        addRow(new ColorRow(Component.translatable("damage_numbers.customization.color_second"),
                ColorPickerScreen.Target.FILL_SECOND, this::isGradient,
                () -> style().fill().secondArgb()));
        addRow(new ColorRow(Component.translatable("damage_numbers.customization.underlay"),
                ColorPickerScreen.Target.UNDERLAY, () -> true,
                () -> style().border().firstArgb()));
        SliderRow angle = new SliderRow(Component.translatable("damage_numbers.customization.gradient_angle"),
                null, 0.0D, 360.0D, 1.0D, 0,
                () -> style().gradientAngleDegrees(),
                value -> config.setGradientAngleDegrees((float) value));
        angle.dial = true;
        angle.enabled = this::isGradient;
        addRow(angle);
        addRow(new SectionRow(Component.translatable("damage_numbers.section.animation"), true));
        addRow(new SelectRow(Component.translatable("damage_numbers.customization.animation"), null,
                animationOptions(), () -> style().appearanceAnimation().ordinal(),
                index -> config.setAppearanceAnimation(AppearanceAnimation.values()[index])));
        addRow(new SelectRow(Component.translatable("damage_numbers.customization.animation_scope"), null,
                animationScopeOptions(), () -> style().appearanceAnimationScope().ordinal(),
                index -> config.setAppearanceAnimationScope(AppearanceAnimationScope.values()[index])));
        addRow(new SliderRow(Component.translatable("damage_numbers.customization.animation_duration"), null,
                0.0D, 2_000.0D, 10.0D, 0,
                () -> style().appearanceAnimationMillis(),
                value -> config.setAppearanceAnimationMillis(Math.max(0L, Math.round(value)))));
        addRow(new ExpanderRow(Component.translatable("damage_numbers.customization.advanced")));
        if (advancedExpanded) {
            addRow(new SliderRow(Component.translatable("damage_numbers.customization.lifetime"), null,
                    100.0D, 6_000.0D, 50.0D, 0,
                    () -> style().fadeOutTimeMillis(),
                    value -> config.setFadeOutTimeMillis(Math.max(0L, Math.round(value)))));
            addRow(new SliderRow(Component.translatable("damage_numbers.customization.minimum_radius"), null,
                    0.0D, 1.5D, 0.01D, 2,
                    () -> style().minimumSpawnRadius(), this::applyMinimumRadius));
            addRow(new SliderRow(Component.translatable("damage_numbers.customization.maximum_radius"), null,
                    0.0D, 1.5D, 0.01D, 2,
                    () -> style().maximumSpawnRadius(), this::applyMaximumRadius));
        }
    }

    private void buildModSettingsRows() {
        addRow(new SectionRow(Component.translatable("damage_numbers.section.general"), false));
        addRow(new ToggleRow(Component.translatable("damage_numbers.mod_settings.state"),
                Component.translatable("damage_numbers.mod_settings.state_description"),
                config::isEnabled, () -> config.setEnabled(!config.isEnabled())));
        addRow(new ToggleRow(Component.translatable("damage_numbers.mod_settings.show_all_damage"),
                Component.translatable("damage_numbers.mod_settings.show_all_damage_description"),
                config::showAllDamageSources,
                () -> config.setShowAllDamageSources(!config.showAllDamageSources())));
        addRow(new SectionRow(Component.translatable("damage_numbers.section.filters"), true));
        addRow(new FieldRow(Component.translatable("damage_numbers.mod_settings.minimum_damage"),
                Component.translatable("damage_numbers.mod_settings.minimum_damage_description"),
                Float.toString(config.minimumDamage()), true, this::applyMinimumDamageInput));
    }

    private List<Component> animationOptions() {
        List<Component> options = new ArrayList<>();
        for (AppearanceAnimation animation : AppearanceAnimation.values()) {
            options.add(animationName(animation));
        }
        return options;
    }

    private List<Component> animationScopeOptions() {
        List<Component> options = new ArrayList<>();
        for (AppearanceAnimationScope scope : AppearanceAnimationScope.values()) {
            options.add(animationScopeName(scope));
        }
        return options;
    }

    private EditBox textField(int x, int y, int width, int height, String value, boolean decimal,
                              Component narration) {
        EditBox box = new EditBox(font, x + 6, y + (height - 8) / 2, width - 12, 10, narration);
        box.setMaxLength(24);
        applyFilter(box, decimal);
        box.setBordered(false);
        box.setTextColor(0xFFEDF1F7);
        box.setValue(value);
        addWidget(box);
        textFields.add(box);
        return box;
    }

    /** The 26+ text field dropped the filter hook, so the transformation keeps this in one place. */
    private static void applyFilter(EditBox box, boolean decimal) {
        box.setFilter(text -> text.matches(decimal ? "[0-9]*([.,][0-9]*)?" : "[0-9]*"));
    }

    private void openScreen(Screen screen) {
        flush();
        minecraft.setScreen(screen);
    }

    // ------------------------------------------------------------------ render

    /**
     * The vanilla background pass is deliberately skipped: it renders the menu panorama and the
     * blur post chain every frame, and the opaque backdrop below hides all of it anyway.
     */
    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        frameDelta = nextFrameDelta();
        cachedStyle = null;
        advanceScroll();
        graphics.fill(0, 0, width, height, BACKDROP);
        boolean blocked = openSelect != null;
        int pointerX = blocked ? Integer.MIN_VALUE : mouseX;
        int pointerY = blocked ? Integer.MIN_VALUE : mouseY;
        renderSidebar(graphics, pointerX, pointerY);
        renderHeader(graphics, pointerX, pointerY);
        renderContent(graphics, pointerX, pointerY, partialTick);
        renderTextFields(graphics, mouseX, mouseY, partialTick, 0, headerFieldCount);
        renderPopup(graphics, mouseX, mouseY);
    }

    /**
     * Text fields are the only vanilla widgets left, and they draw on top of their painted frame.
     * The scrolled ones are drawn from inside the scroll transform so their text cannot drift a
     * pixel away from the frame painted around them.
     */
    private void renderTextFields(GuiGraphics graphics, int mouseX, int mouseY, float partialTick,
                                  int from, int to) {
        if (openSelect != null) {
            return;
        }
        for (int index = from; index < to; index++) {
            EditBox box = textFields.get(index);
            if (box.visible) {
                box.render(graphics, mouseX, mouseY, partialTick);
            }
        }
    }

    /**
     * Older versions flush every glyph in one batch at the end of the frame, so text painted before
     * the popup would still land on top of it. Lifting the layer keeps the popup opaque there;
     * 1.21.6+ draws strictly in submission order and needs no offset.
     */
    private void pushPopupLayer(GuiGraphics graphics) {
        PoseStack pose = graphics.pose();
        pose.pushPose();
        pose.translate(0.0F, 0.0F, 300.0F);
    }

    private void popPopupLayer(GuiGraphics graphics) {
        graphics.pose().popPose();
    }

    private void renderSidebar(GuiGraphics graphics, int mouseX, int mouseY) {
        int right = sidebarRight();
        roundPanel(graphics, 8, 8, right - 8, height - 16, 8, PANEL, OUTLINE_SOFT);
        graphics.drawString(font, Component.empty().append(title).append(" 1.1"), 20, 21, TEXT, false);
        graphics.drawString(font, "by mel1x", 20, 33, FAINT, false);
        graphics.fill(20, 46, right - 20, 47, OUTLINE);

        Page[] pages = Page.values();
        int target = page.ordinal();
        navIndicator = approach(navIndicator, target, 16.0F, frameDelta);
        int indicatorY = Math.round(navY(0) + navIndicator * 30);
        roundRect(graphics, 16, indicatorY, right - 32, 26, 6, 0xFF1B2431);
        roundRect(graphics, 16, indicatorY + 5, 3, 16, 1, ACCENT);
        for (int index = 0; index < pages.length; index++) {
            int y = navY(index);
            boolean hovered = inside(mouseX, mouseY, 16, y, right - 32, 26);
            navHover[index] = approach(navHover[index], hovered ? 1.0F : 0.0F, 14.0F, frameDelta);
            boolean selected = index == target;
            if (navHover[index] > 0.01F && !selected) {
                roundRect(graphics, 16, y, right - 32, 26, 6, blend(PANEL, SURFACE_HOVER, navHover[index]));
            }
            int color = selected ? TEXT : blend(MUTED, TEXT, navHover[index]);
            drawNavIcon(graphics, pages[index], 27, y + 13, color);
            graphics.drawString(font, fitLabel(Component.translatable(pages[index].translationKey),
                    right - 32 - 30), 38, y + 9, color, false);
        }
        int doneY = height - 38;
        int previewBottom = doneY - 10;
        int previewTop = navY(Page.values().length - 1) + 38;
        int previewHeight = Math.min(74, previewBottom - previewTop);
        if (previewHeight >= 42) {
            renderPreviewPanel(graphics, 16, previewBottom - previewHeight, right - 32, previewHeight);
        }
        boolean doneHovered = inside(mouseX, mouseY, 18, doneY, right - 36, 24);
        doneHover = approach(doneHover, doneHovered ? 1.0F : 0.0F, 14.0F, frameDelta);
        roundRect(graphics, 18, doneY, right - 36, 24, 6, blend(ACCENT_DEEP, ACCENT, doneHover));
        graphics.drawCenteredString(font, Component.translatable("gui.done"), (right - 18) / 2 + 9,
                doneY + 8, 0xFFFFFFFF);
    }

    private void renderHeader(GuiGraphics graphics, int mouseX, int mouseY) {
        int left = contentX();
        graphics.drawString(font, Component.translatable(page.translationKey), left, 18, TEXT, false);
        graphics.drawString(font, fitLabel(Component.translatable(page.descriptionKey),
                headerTextWidth()), left, 30, FAINT, false);
        if (page == Page.PRESETS) {
            int width = Math.min(112, contentWidth() / 2);
            int x = contentRight() - width;
            boolean hovered = inside(mouseX, mouseY, x, 16, width, 22);
            actionHover = approach(actionHover, hovered ? 1.0F : 0.0F, 14.0F, frameDelta);
            roundPanel(graphics, x, 16, width, 22, 6, blend(SURFACE, SURFACE_ACTIVE, actionHover),
                    blend(OUTLINE, ACCENT, actionHover));
            graphics.drawCenteredString(font, fitLabel(
                            Component.translatable("damage_numbers.presets.save"), width - 12),
                    x + width / 2, 23, TEXT);
        }
        if (showsRangeStrip()) {
            renderRangeStrip(graphics, mouseX, mouseY);
        }
    }

    private void renderRangeStrip(GuiGraphics graphics, int mouseX, int mouseY) {
        List<DamageRange> ranges = config.damageRanges();
        int active = config.activeDamageRangeIndex();
        if (!rangeSegments.isEmpty()) {
            RangeSegment last = rangeSegments.get(rangeSegments.size() - 1);
            roundPanel(graphics, contentX(), HEADER_STRIP_TOP, last.x() + last.width() - contentX(),
                    STRIP_HEIGHT, 0, SURFACE, OUTLINE);
        }
        for (RangeSegment segment : rangeSegments) {
            DamageRange range = ranges.get(segment.index());
            boolean hovered = inside(mouseX, mouseY, segment.x(), HEADER_STRIP_TOP, segment.width(),
                    STRIP_HEIGHT);
            boolean selected = segment.index() == active;
            int tint = range.style().fill().colorAt(0.5F);
            if (selected) {
                graphics.fill(segment.x() + 1, HEADER_STRIP_TOP + 1, segment.x() + segment.width() - 1,
                        HEADER_STRIP_TOP + STRIP_HEIGHT - 1, blend(SURFACE_ACTIVE, tint, 0.28F));
                roundOutline(graphics, segment.x() + 1, HEADER_STRIP_TOP + 1, segment.width() - 2,
                        STRIP_HEIGHT - 2, 0, ACCENT);
            } else if (hovered) {
                graphics.fill(segment.x() + 1, HEADER_STRIP_TOP + 1, segment.x() + segment.width() - 1,
                        HEADER_STRIP_TOP + STRIP_HEIGHT - 1, blend(SURFACE_HOVER, tint, 0.18F));
            }
            if (segment.index() > 0 && !selected) {
                graphics.fill(segment.x(), HEADER_STRIP_TOP + 6, segment.x() + 1,
                        HEADER_STRIP_TOP + STRIP_HEIGHT - 6, OUTLINE);
            }
            float next = segment.index() + 1 < ranges.size()
                    ? ranges.get(segment.index() + 1).minimumDamage() : Float.POSITIVE_INFINITY;
            graphics.drawCenteredString(font, fitLabel(Component.literal(
                            rangeLabel(range.minimumDamage(), next)), segment.width() - 8),
                    segment.x() + segment.width() / 2, HEADER_STRIP_TOP + 8, selected ? TEXT : MUTED);
        }
        if (active > 0) {
            int removeX = rangeRemoveX();
            boolean hovered = inside(mouseX, mouseY, removeX, HEADER_STRIP_TOP, 24, STRIP_HEIGHT);
            removeRangeHover = approach(removeRangeHover, hovered ? 1.0F : 0.0F, 14.0F, frameDelta);
            roundPanel(graphics, removeX, HEADER_STRIP_TOP, 24, STRIP_HEIGHT, 0,
                    blend(SURFACE, SURFACE_HOVER, removeRangeHover),
                    blend(OUTLINE, DANGER, removeRangeHover));
            drawTrashIcon(graphics, removeX + 5, HEADER_STRIP_TOP + 6, DANGER);
            int fieldX = removeX + 28;
            roundPanel(graphics, fieldX, HEADER_STRIP_TOP, 58, STRIP_HEIGHT, 0, 0xFF10131A, OUTLINE);
        }
        int addX = rangeAddX();
        boolean addHovered = inside(mouseX, mouseY, addX, HEADER_STRIP_TOP, 24, STRIP_HEIGHT);
        addRangeHover = approach(addRangeHover, addHovered ? 1.0F : 0.0F, 14.0F, frameDelta);
        roundPanel(graphics, addX, HEADER_STRIP_TOP, 24, STRIP_HEIGHT, 0,
                blend(SURFACE, SURFACE_ACTIVE, addRangeHover), blend(OUTLINE, ACCENT, addRangeHover));
        int plusX = addX + 12;
        int plusY = HEADER_STRIP_TOP + STRIP_HEIGHT / 2;
        graphics.fill(plusX - 4, plusY - 1, plusX + 5, plusY, TEXT);
        graphics.fill(plusX, plusY - 5, plusX + 1, plusY + 4, TEXT);
    }

    private void renderContent(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        int top = contentTop();
        int bottom = contentBottom();
        boolean pointerInside = inside(mouseX, mouseY, contentX(), top, contentWidth(), bottom - top);
        graphics.enableScissor(contentX(), top, contentRight(), bottom);
        PoseStack pose = graphics.pose();
        pose.pushPose();
        pose.translate(0.0F, -scrollFraction, 0.0F);
        for (Row row : rows) {
            int screenTop = top + row.top - scrollBase;
            boolean visible = screenTop < bottom && screenTop + row.height > top;
            row.updateWidgets(screenTop, visible);
            if (!visible) {
                row.tick(false);
                continue;
            }
            row.tick(pointerInside && row.contains(mouseX, mouseY, screenTop));
            row.render(graphics, screenTop, mouseX, mouseY);
        }
        renderTextFields(graphics, mouseX, mouseY, partialTick, headerFieldCount, textFields.size());
        pose.popPose();
        graphics.disableScissor();
        renderScrollbar(graphics, mouseX, mouseY);
    }

    private void renderScrollbar(GuiGraphics graphics, int mouseX, int mouseY) {
        if (scrollMax <= 0.0D) {
            return;
        }
        int trackX = contentRight() - 5;
        int top = contentTop();
        int trackHeight = viewportHeight();
        boolean hovered = inside(mouseX, mouseY, trackX - 3, top, 11, trackHeight)
                || dragging == Dragging.SCROLLBAR;
        scrollbarHover = approach(scrollbarHover, hovered ? 1.0F : 0.0F, 14.0F, frameDelta);
        roundRect(graphics, trackX, top, 4, trackHeight, 2, 0xFF171B23);
        int thumbHeight = thumbHeight();
        int thumbY = top + (int) Math.round((trackHeight - thumbHeight) * (scroll.value() / scrollMax));
        roundRect(graphics, trackX, thumbY, 4, thumbHeight, 2, blend(TRACK, ACCENT, scrollbarHover));
    }

    private void renderPopup(GuiGraphics graphics, int mouseX, int mouseY) {
        popupOpen = approach(popupOpen, openSelect == null ? 0.0F : 1.0F, 22.0F, frameDelta);
        if (openSelect == null || popupOpen < 0.02F) {
            return;
        }
        pushPopupLayer(graphics);
        // The sidebar keeps the live preview readable, so the veil stops at its edge.
        graphics.fill(sidebarRight(), 0, width, height, 0xB4000000);
        int[] bounds = popupBounds();
        int x = bounds[0];
        int y = bounds[1];
        int popupWidth = bounds[2];
        int fullHeight = bounds[3];
        int popupHeight = Math.max(POPUP_ROW_HEIGHT, Math.round(fullHeight * ease(popupOpen)));
        roundRect(graphics, x - 1, y + 1, popupWidth + 2, popupHeight + 2, 7, SHADOW);
        roundPanel(graphics, x, y, popupWidth, popupHeight, 6, SURFACE, ACCENT);
        popupScrollGoal = clamp(popupScrollGoal, 0.0D, popupScrollMax);
        popupScroll = approach(popupScroll, popupScrollGoal, 20.0D, frameDelta);
        graphics.enableScissor(x + 1, y + 1, x + popupWidth - 1, y + popupHeight - 1);
        List<Component> options = openSelect.options;
        int selected = openSelect.selected.getAsInt();
        for (int index = 0; index < options.size(); index++) {
            int rowY = y + 4 + index * POPUP_ROW_HEIGHT - (int) Math.round(popupScroll);
            if (rowY + POPUP_ROW_HEIGHT < y || rowY > y + popupHeight) {
                continue;
            }
            boolean hovered = inside(mouseX, mouseY, x + 3, rowY, popupWidth - 6, POPUP_ROW_HEIGHT)
                    && mouseY >= y && mouseY < y + popupHeight;
            if (hovered) {
                roundRect(graphics, x + 3, rowY, popupWidth - 6, POPUP_ROW_HEIGHT, 4, SURFACE_HOVER);
            }
            if (index == selected) {
                roundRect(graphics, x + 6, rowY + POPUP_ROW_HEIGHT / 2 - 2, 4, 4, 2, ACCENT);
            }
            graphics.drawString(font, fitLabel(options.get(index), popupWidth - 26), x + 15,
                    rowY + 5, index == selected ? TEXT : MUTED, false);
        }
        graphics.disableScissor();
        if (popupScrollMax > 0.0D) {
            int trackHeight = popupHeight - 8;
            int thumb = Math.max(14, Math.round(trackHeight * trackHeight / (trackHeight
                    + (float) popupScrollMax)));
            int thumbY = y + 4 + (int) Math.round((trackHeight - thumb) * (popupScroll / popupScrollMax));
            roundRect(graphics, x + popupWidth - 5, thumbY, 3, thumb, 1, TRACK);
        }
        popPopupLayer(graphics);
    }

    private int[] popupBounds() {
        int controlX = openSelect.controlLeft();
        int controlWidth = openSelect.controlWidth();
        int controlTop = openSelect.controlTop(rowScreenTop(openSelect));
        int popupWidth = Math.max(controlWidth, 96);
        int rowsShown = Math.min(POPUP_MAX_ROWS, openSelect.options.size());
        int fullHeight = rowsShown * POPUP_ROW_HEIGHT + 8;
        popupScrollMax = Math.max(0.0D, openSelect.options.size() * POPUP_ROW_HEIGHT + 8 - fullHeight);
        int y = controlTop + openSelect.controlHeight() + 4;
        if (y + fullHeight > height - 8) {
            y = Math.max(8, controlTop - fullHeight - 4);
        }
        int x = Math.min(controlX, width - popupWidth - 8);
        return new int[]{Math.max(8, x), y, popupWidth, fullHeight};
    }

    // ------------------------------------------------------------------ input

    @Override
    protected boolean handleMouseScroll(double mouseX, double mouseY, double horizontal, double vertical) {
        if (openSelect != null) {
            if (popupScrollMax > 0.0D) {
                popupScrollGoal = clamp(popupScrollGoal - vertical * 24.0D, 0.0D, popupScrollMax);
            }
            return true;
        }
        FontStripRow strip = fontStrip();
        if (strip != null && strip.contains(mouseX, mouseY, rowScreenTop(strip))
                && insideViewport(mouseY) && fontScrollMax > 0.0D) {
            double delta = Math.abs(horizontal) > 0.001D ? horizontal : vertical;
            fontScrollGoal = clamp(fontScrollGoal - delta * 54.0D, 0.0D, fontScrollMax);
            return true;
        }
        if (inside(mouseX, mouseY, contentX(), contentTop(), contentWidth(), viewportHeight())
                && scrollMax > 0.0D) {
            scrollGoal = clamp(scrollGoal - vertical * 48.0D, 0.0D, scrollMax);
            return true;
        }
        return false;
    }

    @Override
    protected boolean handleMouseClick(double mouseX, double mouseY, int button) {
        if (openSelect != null) {
            handlePopupClick(mouseX, mouseY, button);
            return true;
        }
        if (button != 0) {
            return false;
        }
        if (overTextField(mouseX, mouseY)) {
            return false;
        }
        clearScreenFocus();
        if (clickSidebar(mouseX, mouseY) || clickHeader(mouseX, mouseY) || clickScrollbar(mouseX, mouseY)) {
            return true;
        }
        if (!insideViewport(mouseY) || mouseX < contentX() || mouseX > contentRight()) {
            return false;
        }
        // A row may rebuild the page from its own click handler, so iterate over a snapshot.
        for (Row row : new ArrayList<>(rows)) {
            int screenTop = rowScreenTop(row);
            if (screenTop + row.height <= contentTop() || screenTop >= contentBottom()) {
                continue;
            }
            if (row.contains(mouseX, mouseY, screenTop) && row.click(mouseX, mouseY, screenTop)) {
                return true;
            }
        }
        return false;
    }

    private void handlePopupClick(double mouseX, double mouseY, int button) {
        int[] bounds = popupBounds();
        if (button == 0 && inside(mouseX, mouseY, bounds[0], bounds[1], bounds[2], bounds[3])) {
            int index = (int) Math.floor((mouseY - bounds[1] - 4 + popupScroll) / POPUP_ROW_HEIGHT);
            if (index >= 0 && index < openSelect.options.size()) {
                openSelect.apply.accept(index);
                markDirty();
                flush();
                closeSelect();
                rebuild();
                return;
            }
        }
        closeSelect();
    }

    private boolean clickSidebar(double mouseX, double mouseY) {
        int right = sidebarRight();
        if (inside(mouseX, mouseY, 18, height - 38, right - 36, 24)) {
            onClose();
            return true;
        }
        for (Page candidate : Page.values()) {
            if (inside(mouseX, mouseY, 16, navY(candidate.ordinal()), right - 32, 26)) {
                if (page != candidate) {
                    flush();
                    page = candidate;
                    scroll.set(0.0D);
                    scrollGoal = 0.0D;
                    rebuild();
                }
                return true;
            }
        }
        return false;
    }

    private boolean clickHeader(double mouseX, double mouseY) {
        if (page == Page.PRESETS) {
            int width = Math.min(112, contentWidth() / 2);
            if (inside(mouseX, mouseY, contentRight() - width, 16, width, 22)) {
                openScreen(new SavePresetScreen(this));
                return true;
            }
        }
        if (!showsRangeStrip()) {
            return false;
        }
        if (inside(mouseX, mouseY, rangeAddX(), HEADER_STRIP_TOP, 24, STRIP_HEIGHT)) {
            config.addDamageRangeAfter(config.activeDamageRangeIndex());
            markDirty();
            flush();
            rebuild();
            return true;
        }
        if (config.activeDamageRangeIndex() > 0
                && inside(mouseX, mouseY, rangeRemoveX(), HEADER_STRIP_TOP, 24, STRIP_HEIGHT)) {
            config.removeDamageRange(config.activeDamageRangeIndex());
            markDirty();
            flush();
            rebuild();
            return true;
        }
        for (RangeSegment segment : rangeSegments) {
            if (inside(mouseX, mouseY, segment.x(), HEADER_STRIP_TOP, segment.width(), STRIP_HEIGHT)) {
                config.selectDamageRange(segment.index());
                markDirty();
                rebuild();
                return true;
            }
        }
        return false;
    }

    private boolean clickScrollbar(double mouseX, double mouseY) {
        if (scrollMax <= 0.0D) {
            return false;
        }
        int trackX = contentRight() - 5;
        if (!inside(mouseX, mouseY, trackX - 3, contentTop(), 11, viewportHeight())) {
            return false;
        }
        int thumbHeight = thumbHeight();
        int thumbY = contentTop() + (int) Math.round((viewportHeight() - thumbHeight)
                * (scroll.value() / scrollMax));
        dragging = Dragging.SCROLLBAR;
        if (mouseY >= thumbY && mouseY < thumbY + thumbHeight) {
            scrollbarGrab = mouseY - thumbY;
        } else {
            scrollbarGrab = thumbHeight / 2.0D;
            dragScrollbar(mouseY);
        }
        return true;
    }

    @Override
    protected boolean handleMouseDrag(double mouseX, double mouseY, int button) {
        switch (dragging) {
            case SLIDER -> {
                if (draggedSlider != null) {
                    draggedSlider.dragTo(mouseX);
                }
                return true;
            }
            case SCROLLBAR -> {
                dragScrollbar(mouseY);
                return true;
            }
            default -> {
                return false;
            }
        }
    }

    @Override
    protected boolean handleMouseRelease(double mouseX, double mouseY, int button) {
        if (dragging == Dragging.NONE) {
            return false;
        }
        dragging = Dragging.NONE;
        draggedSlider = null;
        flush();
        return true;
    }

    private void dragScrollbar(double mouseY) {
        int thumbHeight = thumbHeight();
        double travel = Math.max(1, viewportHeight() - thumbHeight);
        double position = (mouseY - contentTop() - scrollbarGrab) / travel;
        scrollGoal = clamp(position * scrollMax, 0.0D, scrollMax);
        scroll.set(scrollGoal);
    }

    private boolean overTextField(double mouseX, double mouseY) {
        for (EditBox box : textFields) {
            if (box.visible && inside(mouseX, mouseY, box.getX() - 6, box.getY() - 6,
                    box.getWidth() + 12, box.getHeight() + 12)) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ animation helpers

    private float nextFrameDelta() {
        long now = System.nanoTime();
        if (lastFrameNanos == 0L) {
            lastFrameNanos = now;
            return 1.0F / 60.0F;
        }
        float delta = (now - lastFrameNanos) / 1_000_000_000.0F;
        lastFrameNanos = now;
        return Math.max(0.0F, Math.min(0.1F, delta));
    }

    private void advanceScroll() {
        scroll.advance(scrollGoal, SCROLL_SMOOTH_TIME, frameDelta);
        fontScroll.advance(fontScrollGoal, SCROLL_SMOOTH_TIME, frameDelta);
        // Landing the offset on a whole physical pixel keeps the glyphs crisp while still letting
        // the content move by less than one GUI pixel per frame.
        double guiScale = Math.max(1.0D, minecraft.getWindow().getGuiScale());
        double aligned = Math.round(scroll.value() * guiScale) / guiScale;
        scrollBase = (int) Math.floor(aligned);
        scrollFraction = (float) (aligned - scrollBase);
    }

    /**
     * A critically damped spring. Exponential smoothing covers a third of the distance on the first
     * frame and then crawls, which reads as a dropped frame rate; this ramps the speed up instead.
     */
    private static final class Smoothed {
        private double value;
        private double velocity;

        double advance(double target, double smoothTime, float delta) {
            if (delta <= 0.0F) {
                return value;
            }
            double omega = 2.0D / Math.max(0.0001D, smoothTime);
            double x = omega * delta;
            double decay = 1.0D / (1.0D + x + 0.48D * x * x + 0.235D * x * x * x);
            double change = value - target;
            double temp = (velocity + omega * change) * delta;
            velocity = (velocity - omega * temp) * decay;
            value = target + (change + temp) * decay;
            if (Math.abs(target - value) < 0.05D && Math.abs(velocity) < 0.6D) {
                value = target;
                velocity = 0.0D;
            }
            return value;
        }

        void set(double newValue) {
            value = newValue;
            velocity = 0.0D;
        }

        double value() {
            return value;
        }
    }

    private static float approach(float current, float target, float rate, float delta) {
        float next = current + (target - current) * (1.0F - (float) Math.exp(-rate * delta));
        return Math.abs(target - next) < 0.003F ? target : next;
    }

    private static double approach(double current, double target, double rate, float delta) {
        double next = current + (target - current) * (1.0D - Math.exp(-rate * delta));
        return Math.abs(target - next) < 0.08D ? target : next;
    }

    private static float ease(float progress) {
        float clamped = Math.max(0.0F, Math.min(1.0F, progress));
        return 1.0F - (1.0F - clamped) * (1.0F - clamped);
    }

    // ------------------------------------------------------------------ geometry

    private int sidebarRight() {
        return Math.max(126, Math.min(178, width / 4));
    }

    private int contentX() {
        return sidebarRight() + 14;
    }

    private int contentRight() {
        return Math.max(contentX() + 160, width - 12);
    }

    private int contentWidth() {
        return contentRight() - contentX();
    }

    private int rowWidth() {
        return contentWidth() - GUTTER;
    }

    private int contentTop() {
        return showsRangeStrip() ? HEADER_STRIP_TOP + STRIP_HEIGHT + 10 : HEADER_STRIP_TOP + 4;
    }

    private int contentBottom() {
        return height - 12;
    }

    private int viewportHeight() {
        return Math.max(1, contentBottom() - contentTop());
    }

    private int headerTextWidth() {
        return page == Page.PRESETS ? contentWidth() - 120 : contentWidth();
    }

    private int thumbHeight() {
        int height = viewportHeight();
        return Math.max(24, (int) Math.round(height * (double) height / (height + scrollMax)));
    }

    private int navY(int index) {
        return 56 + index * 30;
    }

    private int rangeTrackWidth() {
        if (rangeSegments.isEmpty()) {
            return 0;
        }
        RangeSegment last = rangeSegments.get(rangeSegments.size() - 1);
        return last.x() + last.width() - contentX();
    }

    private int rangeRemoveX() {
        return contentX() + rangeTrackWidth() + 6;
    }

    private int rangeAddX() {
        return config.activeDamageRangeIndex() > 0 ? rangeRemoveX() + 28 + 58 + 4
                : contentX() + rangeTrackWidth() + 6;
    }

    private boolean showsRangeStrip() {
        return page != Page.MOD_SETTINGS;
    }

    private boolean insideViewport(double mouseY) {
        return mouseY >= contentTop() && mouseY < contentBottom();
    }

    private int rowScreenTop(Row row) {
        return contentTop() + row.top - scrollBase;
    }

    private FontStripRow fontStrip() {
        for (Row row : rows) {
            if (row instanceof FontStripRow strip) {
                return strip;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ config plumbing

    /**
     * The style is read by every row on every frame, so it is fetched once per frame instead of
     * paying for a synchronized copy per lookup. Mutations drop the cache through {@link #markDirty}.
     */
    private Snapshot style() {
        if (cachedStyle == null) {
            cachedStyle = config.snapshot();
        }
        return cachedStyle;
    }

    private void markDirty() {
        cachedStyle = null;
        dirty = true;
    }

    private void flush() {
        if (dirty) {
            dirty = false;
            config.save();
        }
    }

    private boolean isGradient() {
        return style().fill().mode() == ColorMode.GRADIENT;
    }

    private void applyFillMode(int index) {
        ColorPaint current = config.snapshot().fill();
        if (index == ColorMode.SOLID.ordinal()) {
            config.setFill(ColorPaint.solid(current.firstArgb()));
        } else {
            int second = current.secondArgb() != current.firstArgb()
                    ? current.secondArgb() : nextPaletteColor(current.firstArgb());
            config.setFill(ColorPaint.gradient(current.firstArgb(), second));
        }
    }

    private void applyMinimumRadius(double value) {
        float minimum = (float) Math.max(0.0D, value);
        config.setSpawnRadiusRange(minimum, Math.max(minimum, config.snapshot().maximumSpawnRadius()));
    }

    private void applyMaximumRadius(double value) {
        float maximum = (float) Math.max(0.0D, value);
        config.setSpawnRadiusRange(Math.min(maximum, config.snapshot().minimumSpawnRadius()), maximum);
    }

    private void applyDamageRangeInput(String text) {
        try {
            float value = Float.parseFloat(text.replace(',', '.'));
            if (config.setDamageRangeMinimum(config.activeDamageRangeIndex(), value)) {
                markDirty();
                flush();
            }
        } catch (NumberFormatException ignored) {
        }
    }

    private void applyMinimumDamageInput(String text) {
        try {
            float value = Float.parseFloat(text.replace(',', '.'));
            if (value >= 0.0F && Float.isFinite(value)) {
                config.setMinimumDamage(Math.min(2_048.0F, value));
                markDirty();
                flush();
            }
        } catch (NumberFormatException ignored) {
        }
    }

    private void selectFont(FontCard card) {
        if (card.addCard()) {
            CustomFontManager.openImportDialog(minecraft, imported -> {
                config.setCustomFont(imported.id());
                config.save();
                rebuild();
            });
            return;
        }
        if (card.font() == FontChoice.CUSTOM) {
            config.setCustomFont(card.customFontId());
        } else {
            config.setFont(card.font());
        }
        markDirty();
        flush();
    }

    private void openSelect(SelectRow row) {
        openSelect = row;
        popupOpen = 0.0F;
        popupScroll = 0.0D;
        popupScrollGoal = 0.0D;
        int selected = row.selected.getAsInt();
        if (selected >= POPUP_MAX_ROWS) {
            popupScrollGoal = (selected - POPUP_MAX_ROWS + 1) * POPUP_ROW_HEIGHT;
            popupScroll = popupScrollGoal;
        }
    }

    private void closeSelect() {
        openSelect = null;
        popupScroll = 0.0D;
        popupScrollGoal = 0.0D;
    }

    private static int nextPaletteColor(int current) {
        for (int index = 0; index < COLOR_PALETTE.length; index++) {
            if ((COLOR_PALETTE[index] & 0x00FFFFFF) == (current & 0x00FFFFFF)) {
                return COLOR_PALETTE[(index + 1) % COLOR_PALETTE.length];
            }
        }
        return COLOR_PALETTE[0];
    }

    @Override
    public void onClose() {
        if (openSelect != null) {
            closeSelect();
            return;
        }
        dirty = true;
        flush();
        minecraft.setScreen(parent);
    }

    // ------------------------------------------------------------------ rows

    private abstract class Row {
        int top;
        int height = 30;
        float hover;

        void layout() {
        }

        void tick(boolean hovered) {
            hover = approach(hover, hovered ? 1.0F : 0.0F, HOVER_RATE, frameDelta);
        }

        void updateWidgets(int screenTop, boolean visible) {
        }

        boolean contains(double mouseX, double mouseY, int screenTop) {
            return inside(mouseX, mouseY, contentX(), screenTop, rowWidth(), height);
        }

        boolean click(double mouseX, double mouseY, int screenTop) {
            return false;
        }

        abstract void render(GuiGraphics graphics, int screenTop, int mouseX, int mouseY);
    }

    private final class SectionRow extends Row {
        private final Component title;
        private final boolean spaced;

        SectionRow(Component title, boolean spaced) {
            this.title = title;
            this.spaced = spaced;
        }

        @Override
        void layout() {
            height = spaced ? 26 : 14;
        }

        @Override
        boolean contains(double mouseX, double mouseY, int screenTop) {
            return false;
        }

        @Override
        void render(GuiGraphics graphics, int screenTop, int mouseX, int mouseY) {
            int baseline = screenTop + height - 12;
            graphics.drawString(font, title, contentX() + 2, baseline, MUTED, false);
            int textEnd = contentX() + 8 + font.width(title);
            graphics.fill(textEnd, baseline + 3, contentX() + rowWidth(), baseline + 4, OUTLINE_SOFT);
        }
    }

    private final class HintRow extends Row {
        private final Component message;

        HintRow(Component message) {
            this.message = message;
        }

        @Override
        void layout() {
            height = 32;
        }

        @Override
        boolean contains(double mouseX, double mouseY, int screenTop) {
            return false;
        }

        @Override
        void render(GuiGraphics graphics, int screenTop, int mouseX, int mouseY) {
            roundPanel(graphics, contentX(), screenTop, rowWidth(), height, 6, PANEL, OUTLINE_SOFT);
            graphics.drawString(font, fitLabel(message, rowWidth() - 24), contentX() + 12,
                    screenTop + height / 2 - 4, FAINT, false);
        }
    }

    private final class ExpanderRow extends Row {
        private final Component title;

        ExpanderRow(Component title) {
            this.title = title;
        }

        @Override
        void layout() {
            height = 26;
        }

        @Override
        boolean click(double mouseX, double mouseY, int screenTop) {
            advancedExpanded = !advancedExpanded;
            rebuild();
            return true;
        }

        @Override
        void render(GuiGraphics graphics, int screenTop, int mouseX, int mouseY) {
            int color = blend(MUTED, TEXT, hover);
            int arrowX = contentX() + 6;
            int arrowY = screenTop + height / 2;
            if (advancedExpanded) {
                for (int step = 0; step < 4; step++) {
                    graphics.fill(arrowX + step, arrowY - 2 + step, arrowX + 7 - step,
                            arrowY - 1 + step, color);
                }
            } else {
                for (int step = 0; step < 4; step++) {
                    graphics.fill(arrowX + 1 + step, arrowY - 4 + step, arrowX + 2 + step,
                            arrowY + 4 - step, color);
                }
            }
            graphics.drawString(font, title, contentX() + 18, screenTop + height / 2 - 4, color, false);
        }
    }

    /** Shared frame for a labelled setting: the label block on the left, one control on the right. */
    private abstract class SettingRow extends Row {
        final Component label;
        final Component description;
        BooleanSupplier enabled = () -> true;
        boolean compact;

        SettingRow(Component label, Component description) {
            this.label = label;
            this.description = description;
        }

        abstract int preferredControlWidth();

        int controlHeight() {
            return 20;
        }

        @Override
        void layout() {
            int available = rowWidth() - PADDING * 2 - preferredControlWidth() - 12;
            compact = available < 88;
            height = description == null ? 30 : 37;
            if (compact) {
                height += controlHeight() + 6;
            }
        }

        int controlWidth() {
            return compact ? Math.min(preferredControlWidth(), rowWidth() - PADDING * 2)
                    : preferredControlWidth();
        }

        int controlLeft() {
            return compact ? contentX() + PADDING
                    : contentX() + rowWidth() - PADDING - controlWidth();
        }

        int controlTop(int screenTop) {
            return compact ? screenTop + height - 8 - controlHeight()
                    : screenTop + (height - controlHeight()) / 2;
        }

        boolean isEnabled() {
            return enabled.getAsBoolean();
        }

        @Override
        void render(GuiGraphics graphics, int screenTop, int mouseX, int mouseY) {
            boolean active = isEnabled();
            roundPanel(graphics, contentX(), screenTop, rowWidth(), height, 6,
                    blend(SURFACE, SURFACE_HOVER, active ? hover : 0.0F),
                    blend(OUTLINE_SOFT, OUTLINE, active ? hover : 0.0F));
            int labelWidth = compact ? rowWidth() - PADDING * 2
                    : rowWidth() - PADDING * 2 - controlWidth() - 12;
            int labelY = description == null && !compact ? screenTop + height / 2 - 4 : screenTop + 8;
            graphics.drawString(font, fitLabel(label, labelWidth), contentX() + PADDING, labelY,
                    active ? TEXT : FAINT, false);
            if (description != null) {
                graphics.drawString(font, fitLabel(description, labelWidth), contentX() + PADDING,
                        labelY + 12, active ? MUTED : FAINT, false);
            }
            renderControl(graphics, controlLeft(), controlTop(screenTop), controlWidth(), active,
                    mouseX, mouseY);
        }

        abstract void renderControl(GuiGraphics graphics, int x, int y, int width, boolean active,
                                    int mouseX, int mouseY);
    }

    private final class ToggleRow extends SettingRow {
        private final BooleanSupplier getter;
        private final Runnable toggle;
        private float knob;

        ToggleRow(Component label, Component description, BooleanSupplier getter, Runnable toggle) {
            super(label, description);
            this.getter = getter;
            this.toggle = toggle;
            this.knob = getter.getAsBoolean() ? 1.0F : 0.0F;
        }

        @Override
        int preferredControlWidth() {
            return 26;
        }

        @Override
        int controlHeight() {
            return 14;
        }

        @Override
        boolean click(double mouseX, double mouseY, int screenTop) {
            if (!isEnabled()) {
                return false;
            }
            toggle.run();
            markDirty();
            flush();
            return true;
        }

        @Override
        void renderControl(GuiGraphics graphics, int x, int y, int width, boolean active,
                           int mouseX, int mouseY) {
            boolean on = getter.getAsBoolean();
            knob = approach(knob, on ? 1.0F : 0.0F, 18.0F, frameDelta);
            float eased = ease(knob);
            int height = controlHeight();
            int track = active ? blend(blend(TRACK, ACCENT, eased), 0xFFFFFFFF, hover * 0.08F)
                    : 0xFF20242C;
            cutRect(graphics, x, y, width, height, 4, track);
            int knobSize = height - 6;
            int knobX = x + 3 + Math.round((width - 6 - knobSize) * eased);
            cutRect(graphics, knobX, y + 3, knobSize, knobSize, 2,
                    active ? 0xFFFFFFFF : 0xFF6E7683);
        }
    }

    private final class SelectRow extends SettingRow {
        private final List<Component> options;
        private final IntSupplier selected;
        private final IntConsumer apply;

        SelectRow(Component label, Component description, List<Component> options, IntSupplier selected,
                  IntConsumer apply) {
            super(label, description);
            this.options = options;
            this.selected = selected;
            this.apply = apply;
        }

        @Override
        int preferredControlWidth() {
            int longest = 60;
            for (Component option : options) {
                longest = Math.max(longest, font.width(option));
            }
            return Math.min(150, longest + 34);
        }

        @Override
        boolean click(double mouseX, double mouseY, int screenTop) {
            if (!isEnabled()) {
                return false;
            }
            openSelect(this);
            return true;
        }

        @Override
        void renderControl(GuiGraphics graphics, int x, int y, int width, boolean active,
                           int mouseX, int mouseY) {
            int height = controlHeight();
            boolean open = openSelect == this;
            roundPanel(graphics, x, y, width, height, 5,
                    active ? blend(0xFF11151C, SURFACE_ACTIVE, hover) : 0xFF15181E,
                    open ? ACCENT : blend(OUTLINE, ACCENT, active ? hover : 0.0F));
            int index = Math.max(0, Math.min(options.size() - 1, selected.getAsInt()));
            graphics.drawString(font, fitLabel(options.get(index), width - 26), x + 8, y + 6,
                    active ? TEXT : FAINT, false);
            int arrowX = x + width - 14;
            int arrowY = y + height / 2 - 1;
            int color = active ? blend(MUTED, TEXT, hover) : FAINT;
            for (int step = 0; step < 3; step++) {
                graphics.fill(arrowX + step, arrowY + step, arrowX + 6 - step, arrowY + 1 + step, color);
            }
        }
    }

    private final class SliderRow extends SettingRow {
        private final double minimum;
        private final double maximum;
        private final double step;
        private final int decimals;
        private final DoubleSupplier getter;
        private final DoubleConsumer setter;
        private final EditBox field;
        boolean dial;
        private float knob;
        private boolean syncing;

        SliderRow(Component label, Component description, double minimum, double maximum, double step,
                  int decimals, DoubleSupplier getter, DoubleConsumer setter) {
            super(label, description);
            this.minimum = minimum;
            this.maximum = maximum;
            this.step = step;
            this.decimals = decimals;
            this.getter = getter;
            this.setter = setter;
            this.field = textField(0, -100, fieldWidth(), 18, format(getter.getAsDouble()), decimals > 0,
                    label);
            this.field.setResponder(this::readField);
            this.knob = 0.0F;
        }

        private int fieldWidth() {
            return decimals >= 3 ? 52 : 46;
        }

        @Override
        int preferredControlWidth() {
            return (dial ? 20 : 0) + 104 + 6 + fieldWidth();
        }

        private double value() {
            return getter.getAsDouble();
        }

        private double progress() {
            return clamp((value() - minimum) / Math.max(1.0E-6D, maximum - minimum), 0.0D, 1.0D);
        }

        private int trackLeft() {
            return controlLeft() + (dial ? 20 : 0);
        }

        private int trackWidth() {
            return Math.max(24, controlWidth() - (dial ? 20 : 0) - 6 - fieldWidth());
        }

        @Override
        boolean click(double mouseX, double mouseY, int screenTop) {
            if (!isEnabled()) {
                return false;
            }
            int top = controlTop(screenTop);
            if (!inside(mouseX, mouseY, trackLeft() - 4, top, trackWidth() + 8, controlHeight())) {
                return false;
            }
            dragging = Dragging.SLIDER;
            draggedSlider = this;
            dragTo(mouseX);
            return true;
        }

        void dragTo(double mouseX) {
            double position = clamp((mouseX - trackLeft() - 4) / Math.max(1, trackWidth() - 8),
                    0.0D, 1.0D);
            double raw = minimum + position * (maximum - minimum);
            double snapped = clamp(Math.round(raw / step) * step, minimum, maximum);
            setter.accept(snapped);
            markDirty();
            syncField();
        }

        private void syncField() {
            syncing = true;
            field.setValue(format(value()));
            syncing = false;
        }

        private void readField(String text) {
            if (syncing) {
                return;
            }
            try {
                double parsed = Double.parseDouble(text.replace(',', '.'));
                if (Double.isFinite(parsed)) {
                    setter.accept(parsed);
                    markDirty();
                    flush();
                }
            } catch (NumberFormatException ignored) {
            }
        }

        private String format(double value) {
            return formatValue(value, decimals);
        }

        @Override
        void updateWidgets(int screenTop, boolean visible) {
            int top = controlTop(screenTop);
            field.setX(controlLeft() + controlWidth() - fieldWidth() + 6);
            field.setY(top + 5);
            field.visible = visible && isEnabled()
                    && top + controlHeight() <= contentBottom() && top >= contentTop();
            field.setEditable(isEnabled());
            if (!field.isFocused() && !syncing && !format(value()).equals(field.getValue())) {
                syncField();
            }
        }

        @Override
        void renderControl(GuiGraphics graphics, int x, int y, int width, boolean active,
                           int mouseX, int mouseY) {
            int height = controlHeight();
            int trackX = trackLeft();
            int trackWidth = trackWidth();
            int centerY = y + height / 2;
            boolean held = dragging == Dragging.SLIDER && draggedSlider == this;
            boolean pointerNear = active && inside(mouseX, mouseY, trackX - 4, y, trackWidth + 8, height);
            knob = approach(knob, held || pointerNear ? 1.0F : 0.0F, 16.0F, frameDelta);
            if (dial) {
                drawAngleDial(graphics, x, centerY - 7, (float) value(), active);
            }
            roundRect(graphics, trackX, centerY - 2, trackWidth, 4, 2, active ? TRACK : 0xFF20242C);
            float progress = (float) progress();
            int filled = Math.round((trackWidth - 8) * progress);
            if (filled > 0) {
                roundRect(graphics, trackX, centerY - 2, filled + 4, 4, 2,
                        active ? ACCENT : 0xFF3A4250);
            }
            float knobX = trackX + 4 + (trackWidth - 8) * progress;
            float radius = 4.5F + knob * 1.5F;
            if (active) {
                circle(graphics, knobX, centerY, radius + 2.0F, 0x5558A6FF);
            }
            circle(graphics, knobX, centerY, radius, active ? 0xFFFFFFFF : 0xFF6E7683);
            int fieldX = controlLeft() + controlWidth() - fieldWidth();
            roundPanel(graphics, fieldX, y, fieldWidth(), height, 5, 0xFF10131A,
                    field.isFocused() ? ACCENT : OUTLINE);
        }
    }

    private final class FieldRow extends SettingRow {
        private final EditBox field;

        FieldRow(Component label, Component description, String value, boolean decimal,
                 java.util.function.Consumer<String> responder) {
            super(label, description);
            this.field = textField(0, -100, 88, 20, value, decimal, label);
            this.field.setResponder(responder);
        }

        @Override
        int preferredControlWidth() {
            return 88;
        }

        @Override
        void updateWidgets(int screenTop, boolean visible) {
            int top = controlTop(screenTop);
            field.setX(controlLeft() + 6);
            field.setY(top + 6);
            field.visible = visible && top + controlHeight() <= contentBottom() && top >= contentTop();
        }

        @Override
        void renderControl(GuiGraphics graphics, int x, int y, int width, boolean active,
                           int mouseX, int mouseY) {
            roundPanel(graphics, x, y, width, controlHeight(), 5, 0xFF10131A,
                    field.isFocused() ? ACCENT : OUTLINE);
        }
    }

    private final class ColorRow extends SettingRow {
        private final ColorPickerScreen.Target target;
        private final IntSupplier color;

        ColorRow(Component label, ColorPickerScreen.Target target, BooleanSupplier enabled,
                 IntSupplier color) {
            super(label, null);
            this.target = target;
            this.color = color;
            this.enabled = enabled;
        }

        @Override
        int preferredControlWidth() {
            return 104;
        }

        @Override
        boolean click(double mouseX, double mouseY, int screenTop) {
            if (!isEnabled()) {
                return false;
            }
            openScreen(new ColorPickerScreen(DamageNumbersConfigScreen.this, target));
            return true;
        }

        @Override
        void renderControl(GuiGraphics graphics, int x, int y, int width, boolean active,
                           int mouseX, int mouseY) {
            int height = controlHeight();
            int swatchWidth = width - 26;
            roundPanel(graphics, x, y, swatchWidth, height, 5, active ? color.getAsInt() : 0xFF2A2F38,
                    active ? blend(OUTLINE, 0xFFFFFFFF, hover * 0.5F) : OUTLINE);
            roundPanel(graphics, x + swatchWidth + 6, y, 20, height, 5,
                    blend(0xFF11151C, SURFACE_ACTIVE, active ? hover : 0.0F),
                    active ? blend(OUTLINE, ACCENT, hover) : OUTLINE);
            drawPipetteIcon(graphics, x + swatchWidth + 6, y + 1, active ? TEXT : FAINT);
        }
    }

    private final class FontStripRow extends Row {
        private final List<FontCard> cards = new ArrayList<>();

        FontStripRow() {
            for (FontChoice choice : FontChoice.values()) {
                if (choice.isBuiltIn()) {
                    cards.add(new FontCard(choice, null, fontName(choice), false));
                }
            }
            for (CustomFont custom : CustomFontManager.fonts()) {
                cards.add(new FontCard(FontChoice.CUSTOM, custom.id(), Component.literal(custom.name()),
                        false));
            }
            cards.add(new FontCard(FontChoice.CUSTOM, null,
                    Component.translatable("damage_numbers.font.add_custom"), true));
        }

        @Override
        void layout() {
            height = FONT_CARD_HEIGHT + 6;
            int content = cards.size() * (FONT_CARD_WIDTH + 6) - 6;
            fontScrollMax = Math.max(0.0D, content - rowWidth() + 8);
            fontScrollGoal = clamp(fontScrollGoal, 0.0D, fontScrollMax);
            fontScroll.set(clamp(fontScroll.value(), 0.0D, fontScrollMax));
        }

        private int cardX(int index) {
            return contentX() + 4 + index * (FONT_CARD_WIDTH + 6)
                    - (int) Math.round(fontScroll.value());
        }

        @Override
        boolean click(double mouseX, double mouseY, int screenTop) {
            for (int index = 0; index < cards.size(); index++) {
                FontCard card = cards.get(index);
                int x = cardX(index);
                if (!inside(mouseX, mouseY, x, screenTop, FONT_CARD_WIDTH, FONT_CARD_HEIGHT)) {
                    continue;
                }
                boolean removable = card.font() == FontChoice.CUSTOM && !card.addCard();
                if (removable && inside(mouseX, mouseY, x + FONT_CARD_WIDTH - 18, screenTop + 3, 15, 15)) {
                    openScreen(new DeleteFontScreen(DamageNumbersConfigScreen.this, card.customFontId(),
                            card.name().getString()));
                    return true;
                }
                selectFont(card);
                return true;
            }
            return false;
        }

        @Override
        void render(GuiGraphics graphics, int screenTop, int mouseX, int mouseY) {
            Snapshot style = style();
            graphics.enableScissor(contentX(), Math.max(contentTop(), screenTop),
                    contentX() + rowWidth(), Math.min(contentBottom(), screenTop + height));
            for (int index = 0; index < cards.size(); index++) {
                FontCard card = cards.get(index);
                int x = cardX(index);
                if (x + FONT_CARD_WIDTH < contentX() || x > contentX() + rowWidth()) {
                    continue;
                }
                boolean hovered = inside(mouseX, mouseY, x, screenTop, FONT_CARD_WIDTH, FONT_CARD_HEIGHT);
                boolean selected = !card.addCard() && card.font() == style.font()
                        && Objects.equals(card.customFontId(), style.customFontId());
                roundPanel(graphics, x, screenTop, FONT_CARD_WIDTH, FONT_CARD_HEIGHT, 6,
                        hovered ? SURFACE_HOVER : SURFACE, selected ? ACCENT : OUTLINE_SOFT);
                if (card.addCard()) {
                    int centerX = x + FONT_CARD_WIDTH / 2;
                    int centerY = screenTop + 15;
                    graphics.fill(centerX - 5, centerY - 1, centerX + 6, centerY + 1, TEXT);
                    graphics.fill(centerX - 1, centerY - 6, centerX + 1, centerY + 5, TEXT);
                } else {
                    graphics.drawCenteredString(font, FontStyleResolver.component("123", card.font(),
                                    card.customFontId()), x + FONT_CARD_WIDTH / 2, screenTop + 9,
                            selected ? TEXT : MUTED);
                    if (card.font() == FontChoice.CUSTOM) {
                        drawTrashIcon(graphics, x + FONT_CARD_WIDTH - 16, screenTop + 5,
                                hovered ? DANGER : FAINT);
                    }
                }
                graphics.drawCenteredString(font, fitLabel(card.name(), FONT_CARD_WIDTH - 10),
                        x + FONT_CARD_WIDTH / 2, screenTop + FONT_CARD_HEIGHT - 13,
                        selected ? ACCENT : FAINT);
            }
            graphics.disableScissor();
            if (fontScrollMax > 0.0D) {
                int trackY = screenTop + FONT_CARD_HEIGHT + 2;
                int width = rowWidth();
                int thumb = Math.max(24, (int) Math.round(width * (double) width
                        / (width + fontScrollMax)));
                int thumbX = contentX() + (int) Math.round((width - thumb) * (fontScroll.value() / fontScrollMax));
                roundRect(graphics, contentX(), trackY, width, 3, 1, 0xFF171B23);
                roundRect(graphics, thumbX, trackY, thumb, 3, 1, TRACK);
            }
        }
    }

    private final class PresetGridRow extends Row {
        private final List<PresetCard> cards;
        private final int columns;
        private final float[] hovers;

        PresetGridRow(List<PresetCard> cards, int columns) {
            this.cards = new ArrayList<>(cards);
            this.columns = columns;
            this.hovers = new float[cards.size()];
        }

        @Override
        void layout() {
            height = PRESET_CARD_HEIGHT;
        }

        private int cardWidth() {
            return (rowWidth() - 6 * (columns - 1)) / columns;
        }

        private int cardX(int index) {
            return contentX() + index * (cardWidth() + 6);
        }

        @Override
        boolean click(double mouseX, double mouseY, int screenTop) {
            int cardWidth = cardWidth();
            for (int index = 0; index < cards.size(); index++) {
                PresetCard card = cards.get(index);
                int x = cardX(index);
                if (!inside(mouseX, mouseY, x, screenTop, cardWidth, height)) {
                    continue;
                }
                if (card.userOwned()) {
                    if (inside(mouseX, mouseY, x + cardWidth - 18, screenTop + 4, 15, 15)) {
                        openScreen(new DeletePresetScreen(DamageNumbersConfigScreen.this, card.id(),
                                card.name().getString()));
                        return true;
                    }
                    if (inside(mouseX, mouseY, x + cardWidth - 35, screenTop + 4, 15, 15)) {
                        openScreen(new SavePresetScreen(DamageNumbersConfigScreen.this, card.id(),
                                card.name().getString()));
                        return true;
                    }
                }
                config.applyPreset(card.id(), card.style());
                markDirty();
                flush();
                rebuild();
                return true;
            }
            return false;
        }

        /**
         * Hover is a pure colour transition: a pixel lift only has two or three whole-pixel steps
         * to travel, which reads as a stutter rather than as motion.
         */
        @Override
        void render(GuiGraphics graphics, int screenTop, int mouseX, int mouseY) {
            String selected = config.selectedPresetId();
            int cardWidth = cardWidth();
            for (int index = 0; index < cards.size(); index++) {
                PresetCard card = cards.get(index);
                int x = cardX(index);
                boolean hovered = inside(mouseX, mouseY, x, screenTop, cardWidth, height);
                hovers[index] = approach(hovers[index], hovered ? 1.0F : 0.0F, HOVER_RATE, frameDelta);
                float glow = ease(hovers[index]);
                boolean active = card.id().equals(selected);
                roundPanel(graphics, x, screenTop, cardWidth, height, 6,
                        blend(SURFACE, SURFACE_ACTIVE, glow),
                        active ? ACCENT : blend(OUTLINE_SOFT, ACCENT, glow * 0.7F));
                if (active) {
                    roundOutline(graphics, x, screenTop, cardWidth, height, 6, ACCENT);
                }
                int nameTop = screenTop + height - 20;
                roundRect(graphics, x + 2, nameTop, cardWidth - 4, 18, 4,
                        blend(0xFF10131A, 0xFF171C25, glow));
                graphics.drawCenteredString(font, fitLabel(card.name(), cardWidth - 12),
                        x + cardWidth / 2, nameTop + 5, active ? TEXT : blend(MUTED, TEXT, glow));
                drawNumberPreview(graphics, card.style(), x + cardWidth / 2.0F,
                        screenTop + (nameTop - screenTop) / 2.0F + 2.0F, 2.1F);
                if (card.userOwned() && hovers[index] > 0.02F) {
                    drawCardAction(graphics, x + cardWidth - 35, screenTop + 4, mouseX, mouseY, false);
                    drawCardAction(graphics, x + cardWidth - 18, screenTop + 4, mouseX, mouseY, true);
                }
            }
        }

        private void drawCardAction(GuiGraphics graphics, int x, int y, int mouseX, int mouseY,
                                    boolean remove) {
            boolean hovered = inside(mouseX, mouseY, x, y, 15, 15);
            roundPanel(graphics, x, y, 15, 15, 4, hovered ? SURFACE_ACTIVE : 0xFF141820,
                    hovered ? (remove ? DANGER : ACCENT) : OUTLINE);
            if (remove) {
                drawTrashIcon(graphics, x + 1, y + 2, DANGER);
            } else {
                drawEditIcon(graphics, x + 1, y + 2, TEXT);
            }
        }
    }

    // ------------------------------------------------------------------ painting primitives

    private static void roundRect(GuiGraphics graphics, int x, int y, int width, int height, int radius,
                                  int color) {
        shapedRect(graphics, x, y, width, height, radius, false, color);
    }

    /** Same as {@link #roundRect} but the corners are cut off at 45 degrees instead of curved. */
    private static void cutRect(GuiGraphics graphics, int x, int y, int width, int height, int cut,
                                int color) {
        shapedRect(graphics, x, y, width, height, cut, true, color);
    }

    private static void shapedRect(GuiGraphics graphics, int x, int y, int width, int height, int corner,
                                   boolean chamfer, int color) {
        if (width <= 0 || height <= 0) {
            return;
        }
        int limit = Math.max(0, Math.min(corner, Math.min(width, height) / 2));
        if (limit == 0) {
            graphics.fill(x, y, x + width, y + height, color);
            return;
        }
        graphics.fill(x, y + limit, x + width, y + height - limit, color);
        // Neighbouring corner rows often share an inset; merging the runs keeps the draw count low.
        int start = 0;
        for (int row = 1; row <= limit; row++) {
            if (row < limit && cornerInset(row, limit, chamfer) == cornerInset(start, limit, chamfer)) {
                continue;
            }
            int inset = cornerInset(start, limit, chamfer);
            graphics.fill(x + inset, y + start, x + width - inset, y + row, color);
            graphics.fill(x + inset, y + height - row, x + width - inset, y + height - start, color);
            start = row;
        }
    }

    private static int cornerInset(int row, int corner, boolean chamfer) {
        if (chamfer) {
            return corner - 1 - row;
        }
        double offset = corner - row - 0.5D;
        return corner - (int) Math.round(Math.sqrt(Math.max(0.0D, corner * corner - offset * offset)));
    }

    private static void roundPanel(GuiGraphics graphics, int x, int y, int width, int height, int radius,
                                   int fill, int border) {
        roundRect(graphics, x, y, width, height, radius, border);
        roundRect(graphics, x + 1, y + 1, width - 2, height - 2, Math.max(0, radius - 1), fill);
    }

    private static void roundOutline(GuiGraphics graphics, int x, int y, int width, int height,
                                     int radius, int color) {
        int limit = Math.max(0, Math.min(radius, Math.min(width, height) / 2));
        graphics.fill(x + limit, y, x + width - limit, y + 1, color);
        graphics.fill(x + limit, y + height - 1, x + width - limit, y + height, color);
        graphics.fill(x, y + limit, x + 1, y + height - limit, color);
        graphics.fill(x + width - 1, y + limit, x + width, y + height - limit, color);
        for (int row = 0; row < limit; row++) {
            int inset = cornerInset(row, limit, false);
            graphics.fill(x + inset, y + row, x + inset + 1, y + row + 1, color);
            graphics.fill(x + width - inset - 1, y + row, x + width - inset, y + row + 1, color);
            graphics.fill(x + inset, y + height - row - 1, x + inset + 1, y + height - row, color);
            graphics.fill(x + width - inset - 1, y + height - row - 1, x + width - inset,
                    y + height - row, color);
        }
    }

    private static void circle(GuiGraphics graphics, float centerX, float centerY, float radius,
                               int color) {
        int top = Math.round(centerY - radius);
        int bottom = Math.round(centerY + radius);
        int runTop = top;
        int runLeft = Integer.MIN_VALUE;
        int runRight = Integer.MIN_VALUE;
        for (int y = top; y <= bottom; y++) {
            int left = 0;
            int right = 0;
            if (y < bottom) {
                double offset = y + 0.5D - centerY;
                double half = Math.sqrt(Math.max(0.0D, radius * radius - offset * offset));
                left = (int) Math.round(centerX - half);
                right = (int) Math.round(centerX + half);
            }
            if (left == runLeft && right == runRight && y < bottom) {
                continue;
            }
            if (runRight > runLeft && runLeft != Integer.MIN_VALUE) {
                graphics.fill(runLeft, runTop, runRight, y, color);
            }
            runTop = y;
            runLeft = left;
            runRight = right;
        }
    }

    private void drawNavIcon(GuiGraphics graphics, Page target, int centerX, int centerY, int color) {
        switch (target) {
            case PRESETS -> {
                graphics.fill(centerX - 5, centerY - 5, centerX - 1, centerY - 1, color);
                graphics.fill(centerX + 1, centerY - 5, centerX + 5, centerY - 1, color);
                graphics.fill(centerX - 5, centerY + 1, centerX - 1, centerY + 5, color);
                graphics.fill(centerX + 1, centerY + 1, centerX + 5, centerY + 5, color);
            }
            case CUSTOMIZATION -> {
                graphics.fill(centerX - 5, centerY - 4, centerX + 5, centerY - 3, color);
                graphics.fill(centerX - 5, centerY + 3, centerX + 5, centerY + 4, color);
                graphics.fill(centerX - 2, centerY - 6, centerX, centerY - 1, color);
                graphics.fill(centerX + 1, centerY + 1, centerX + 3, centerY + 6, color);
            }
            case MOD_SETTINGS -> {
                for (int row = -1; row <= 1; row++) {
                    int rowY = centerY + row * 5 - 1;
                    graphics.fill(centerX - 6, rowY, centerX - 3, rowY + 3, color);
                    graphics.fill(centerX - 1, rowY + 1, centerX + 6, rowY + 2, color);
                }
            }
        }
    }

    private static void drawPipetteIcon(GuiGraphics graphics, int x, int y, int color) {
        graphics.fill(x + 12, y + 3, x + 15, y + 6, color);
        graphics.fill(x + 10, y + 5, x + 13, y + 9, color);
        graphics.fill(x + 7, y + 8, x + 11, y + 11, color);
        graphics.fill(x + 5, y + 11, x + 8, y + 14, color);
        graphics.fill(x + 4, y + 13, x + 6, y + 16, color);
    }

    private static void drawEditIcon(GuiGraphics graphics, int x, int y, int color) {
        graphics.fill(x + 8, y + 3, x + 11, y + 5, color);
        graphics.fill(x + 7, y + 4, x + 10, y + 7, color);
        graphics.fill(x + 5, y + 6, x + 8, y + 9, color);
        graphics.fill(x + 3, y + 8, x + 6, y + 11, color);
        graphics.fill(x + 3, y + 10, x + 5, y + 12, color);
    }

    private static void drawTrashIcon(GuiGraphics graphics, int x, int y, int color) {
        graphics.fill(x + 4, y + 3, x + 10, y + 4, color);
        graphics.fill(x + 5, y + 2, x + 9, y + 3, color);
        graphics.fill(x + 4, y + 5, x + 10, y + 6, color);
        graphics.fill(x + 5, y + 6, x + 6, y + 11, color);
        graphics.fill(x + 8, y + 6, x + 9, y + 11, color);
        graphics.fill(x + 5, y + 11, x + 9, y + 12, color);
    }

    private static void drawAngleDial(GuiGraphics graphics, int x, int y, float angleDegrees,
                                      boolean active) {
        float centerX = x + 7.0F;
        float centerY = y + 7.0F;
        circle(graphics, centerX, centerY, 7.0F, active ? TRACK : 0xFF20242C);
        circle(graphics, centerX, centerY, 5.5F, 0xFF11151C);
        double direction = Math.toRadians(angleDegrees);
        for (int step = 0; step <= 5; step++) {
            int px = Math.round(centerX + (float) Math.cos(direction) * step);
            int py = Math.round(centerY + (float) Math.sin(direction) * step);
            graphics.fill(px, py, px + 1, py + 1, active ? ACCENT : FAINT);
        }
    }

    // ------------------------------------------------------------------ live preview

    private void renderPreviewPanel(GuiGraphics graphics, int x, int y, int width, int height) {
        roundPanel(graphics, x, y, width, height, 7, 0xFF0E1118, OUTLINE_SOFT);
        Snapshot style = style();
        long now = System.nanoTime();
        long lifetime = millisToNanos(style.fadeOutTimeMillis());
        int centerX = x + width / 2;
        int centerY = y + height - 14;
        float spread = width * 1.15F;
        float reach = Math.min(width * 0.42F, height - 20.0F);
        if (lastPreviewSpawnNanos == 0L || now - lastPreviewSpawnNanos >= PREVIEW_SPAWN_INTERVAL_NANOS) {
            spawnPreviewNumber(now, style, spread, reach);
            lastPreviewSpawnNanos = now;
        }
        previewNumbers.removeIf(number -> lifetime == 0L || now - number.createdAtNanos() >= lifetime);
        graphics.enableScissor(x + 1, y + 1, x + width - 1, y + height - 1);
        for (PreviewNumber number : previewNumbers) {
            float ageSeconds = (now - number.createdAtNanos()) / 1_000_000_000.0F;
            float remainingSeconds = (lifetime - (now - number.createdAtNanos())) / 1_000_000_000.0F;
            float alpha = AppearanceAnimator.fadeOutAlpha(remainingSeconds);
            if (alpha > 0.015F) {
                float configuredScale = Math.min(50.0F, 2.61F * style.scale() / 0.04F);
                if (style.scaleWithDamage()) {
                    configuredScale *= 1.0F + Math.min(0.75F,
                            (float) Math.log1p(number.damage()) * 0.18F);
                }
                drawAnimatedNumberPreview(graphics, style, number.text(), centerX + number.offsetX(),
                        centerY + number.offsetY(), configuredScale, ageSeconds, alpha);
            }
        }
        graphics.disableScissor();
    }

    private void spawnPreviewNumber(long now, Snapshot style, float spread, float reach) {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        // Walking the arc by the golden angle keeps consecutive numbers from landing on each other,
        // which a purely random direction does often over such a short span.
        previewAngleCursor = (previewAngleCursor + 0.618034F) % 1.0F;
        double angle = Math.PI * previewAngleCursor + random.nextDouble(-0.08D, 0.08D);
        double minimumRadius = Math.min(reach, style.minimumSpawnRadius() * spread);
        double maximumRadius = Math.max(minimumRadius, Math.min(reach,
                style.maximumSpawnRadius() * spread));
        double radius = maximumRadius <= minimumRadius ? minimumRadius
                : random.nextDouble(minimumRadius, maximumRadius);
        int damage = random.nextInt(1, 21);
        previewNumbers.add(new PreviewNumber(
                Integer.toString(damage), damage,
                (float) (Math.cos(angle) * radius),
                (float) (-Math.sin(angle) * radius),
                now
        ));
        while (previewNumbers.size() > 6) {
            previewNumbers.remove(0);
        }
    }

    private void drawNumberPreview(GuiGraphics graphics, Snapshot style, float centerX, float centerY,
                                   float scale) {
        drawNumberPreview(graphics, style, "8", centerX, centerY, scale, 1.0F);
    }

    private void drawNumberPreview(GuiGraphics graphics, Snapshot style, String value, float centerX,
                                   float centerY, float scale, float alpha) {
        PoseStack pose = graphics.pose();
        pose.pushPose();
        pose.translate(centerX, centerY, 0.0F);
        pose.scale(scale, scale, 1.0F);
        Component number = FontStyleResolver.component(value, style.font(), style.customFontId());
        int textX = -font.width(number) / 2;
        int textY = -font.lineHeight / 2;
        int underlay = withAlpha(style.border().colorAt(0.5F), alpha);
        float borderWidth = style.borderWidth();
        if (borderWidth > 0.0F) {
            // A single ring is enough on a thumbnail, and a whole preset grid is redrawn every frame.
            for (int direction = 0; direction < PREVIEW_OUTLINE_DIRECTIONS; direction++) {
                double angle = Math.PI * 2.0D * direction / PREVIEW_OUTLINE_DIRECTIONS;
                drawPreviewText(graphics, pose, number, textX, textY,
                        (float) Math.cos(angle) * borderWidth, (float) Math.sin(angle) * borderWidth,
                        underlay);
            }
        }
        drawPreviewText(graphics, pose, number, textX, textY, 0.0F, 0.0F, underlay);

        if (TRANSFORMED_SCISSOR) {
            drawGradientPreview(graphics, number, textX, textY, style, alpha);
        } else {
            drawGlyphGradientPreview(graphics, pose, value, textX, textY, style, alpha);
        }
        pose.popPose();
    }

    private void drawGradientPreview(GuiGraphics graphics, Component number, int textX, int textY,
                                     Snapshot style, float alpha) {
        int textWidth = Math.max(1, font.width(number));
        int textHeight = Math.max(1, font.lineHeight);
        double radians = Math.toRadians(style.gradientAngleDegrees());
        boolean rows = Math.abs(Math.sin(radians)) >= Math.abs(Math.cos(radians));
        int strips = rows ? textHeight : textWidth;
        for (int strip = 0; strip < strips; strip++) {
            float normalizedX = rows ? 0.5F : (strip + 0.5F) / textWidth;
            float normalizedY = rows ? (strip + 0.5F) / textHeight : 0.5F;
            int color = withAlpha(style.fill().colorAt(gradientProgress(normalizedX, normalizedY,
                    style.gradientAngleDegrees())), alpha);
            int left = rows ? textX : textX + strip;
            int top = rows ? textY + strip : textY;
            int right = rows ? textX + textWidth : left + 2;
            int bottom = rows ? top + 2 : textY + textHeight;
            graphics.enableScissor(left, top, right, bottom);
            graphics.drawString(font, number, textX, textY, color, false);
            graphics.disableScissor();
        }
    }

    private void drawGlyphGradientPreview(GuiGraphics graphics, PoseStack pose, String value, int textX,
                                          int textY, Snapshot style, float alpha) {
        int textWidth = Math.max(1, font.width(FontStyleResolver.component(value, style.font(),
                style.customFontId())));
        int advance = 0;
        for (int index = 0; index < value.length();) {
            int codePoint = value.codePointAt(index);
            Component glyph = FontStyleResolver.component(new String(Character.toChars(codePoint)),
                    style.font(), style.customFontId());
            int glyphWidth = font.width(glyph);
            int color = withAlpha(style.fill().colorAt(gradientProgress(
                    (advance + glyphWidth * 0.5F) / textWidth, 0.5F, style.gradientAngleDegrees())), alpha);
            drawPreviewText(graphics, pose, glyph, textX + advance, textY, 0.0F, 0.0F, color);
            advance += glyphWidth;
            index += Character.charCount(codePoint);
        }
    }

    private void drawAnimatedNumberPreview(GuiGraphics graphics, Snapshot style, String value,
                                           float centerX, float centerY, float scale, float ageSeconds,
                                           float baseAlpha) {
        PoseStack pose = graphics.pose();
        pose.pushPose();
        pose.translate(centerX, centerY, 0.0F);
        pose.scale(scale, scale, 1.0F);
        Component number = FontStyleResolver.component(value, style.font(), style.customFontId());
        int textWidth = Math.max(1, font.width(number));
        int textX = -textWidth / 2;
        int textY = -font.lineHeight / 2;
        AppearanceAnimator.Transform[] transforms = AppearanceAnimator.glyphs(style.appearanceAnimation(),
                style.appearanceAnimationScope(), value.codePointCount(0, value.length()), ageSeconds,
                style.appearanceAnimationMillis());
        int advance = 0;
        int glyphIndex = 0;
        for (int offset = 0; offset < value.length(); glyphIndex++) {
            int codePoint = value.codePointAt(offset);
            Component glyph = FontStyleResolver.component(new String(Character.toChars(codePoint)),
                    style.font(), style.customFontId());
            int glyphWidth = font.width(glyph);
            int glyphX = textX + advance;
            AppearanceAnimator.Transform transform = transforms[glyphIndex];
            float alpha = baseAlpha * transform.alpha();
            int fill = style.fill().colorAt(gradientProgress(
                    (advance + glyphWidth * 0.5F) / textWidth, 0.5F, style.gradientAngleDegrees()));
            if (transform.blurred()) {
                int blur = withAlpha(fill, baseAlpha * transform.blurAlpha());
                for (int direction = 0; direction < BLUR_DIRECTIONS; direction++) {
                    double angle = Math.PI * 2.0D * direction / BLUR_DIRECTIONS;
                    drawPreviewGlyph(graphics, pose, glyph, glyphX, glyphWidth, textY, transform,
                            (float) Math.cos(angle) * transform.blurRadius(),
                            (float) Math.sin(angle) * transform.blurRadius(), blur);
                }
            }
            if (alpha > 0.015F) {
                int underlay = withAlpha(style.border().colorAt(0.5F), alpha);
                float borderWidth = style.borderWidth();
                if (borderWidth > 0.0F) {
                    int rings = Math.max(1, Math.min(8, (int) Math.ceil(borderWidth * 2.0F)));
                    for (int ring = 1; ring <= rings; ring++) {
                        float radius = borderWidth * ring / rings;
                        int directions = Math.min(32, 8 + (ring - 1) * 4);
                        for (int direction = 0; direction < directions; direction++) {
                            double angle = Math.PI * 2.0D * direction / directions;
                            drawPreviewGlyph(graphics, pose, glyph, glyphX, glyphWidth, textY, transform,
                                    (float) Math.cos(angle) * radius, (float) Math.sin(angle) * radius,
                                    underlay);
                        }
                    }
                }
                drawPreviewGlyph(graphics, pose, glyph, glyphX, glyphWidth, textY, transform,
                        0.0F, 0.0F, underlay);
                drawPreviewGlyph(graphics, pose, glyph, glyphX, glyphWidth, textY, transform,
                        0.0F, 0.0F, withAlpha(fill, alpha));
            }
            advance += glyphWidth;
            offset += Character.charCount(codePoint);
        }
        pose.popPose();
    }

    /**
     * Draws one glyph under its animation transform. The ring or blur offset is applied outside the
     * transform so every copy stays a rigid offset of the animated glyph.
     */
    private void drawPreviewGlyph(GuiGraphics graphics, PoseStack pose, Component glyph, int glyphX,
                                  int glyphWidth, int textY, AppearanceAnimator.Transform transform,
                                  float offsetX, float offsetY, int color) {
        pose.pushPose();
        pose.translate(offsetX, offsetY, 0.0F);
        if (!transform.identity()) {
            float anchorX = transform.anchorWholeNumber() ? 0.0F : glyphX + glyphWidth * 0.5F;
            float anchorY = textY + font.lineHeight * 0.5F;
            pose.translate(anchorX, anchorY + transform.offsetY(), 0.0F);
            if (transform.rotationDegrees() != 0.0F) {
                pose.mulPose(new Quaternionf()
                        .rotationZ((float) Math.toRadians(transform.rotationDegrees())));
            }
            pose.scale(transform.scaleX(), transform.scaleY(), 1.0F);
            pose.translate(-anchorX, -anchorY, 0.0F);
        }
        graphics.drawString(font, glyph, glyphX, textY, color, false);
        pose.popPose();
    }

    private void drawPreviewText(GuiGraphics graphics, PoseStack pose, Component number, int x, int y,
                                 float offsetX, float offsetY, int color) {
        pose.pushPose();
        pose.translate(offsetX, offsetY, 0.0F);
        graphics.drawString(font, number, x, y, color, false);
        pose.popPose();
    }

    // ------------------------------------------------------------------ small helpers

    private static long millisToNanos(long millis) {
        return millis > Long.MAX_VALUE / 1_000_000L ? Long.MAX_VALUE : millis * 1_000_000L;
    }

    private static int withAlpha(int argb, float alpha) {
        int sourceAlpha = argb >>> 24;
        int resultAlpha = Math.max(4, Math.min(255, Math.round(sourceAlpha * alpha)));
        return resultAlpha << 24 | argb & 0x00FFFFFF;
    }

    private static float gradientProgress(float normalizedX, float normalizedY, float angleDegrees) {
        double radians = Math.toRadians(angleDegrees);
        float directionX = (float) Math.cos(radians);
        float directionY = (float) Math.sin(radians);
        float minimum = Math.min(0.0F, directionX) + Math.min(0.0F, directionY);
        float maximum = Math.max(0.0F, directionX) + Math.max(0.0F, directionY);
        return (normalizedX * directionX + normalizedY * directionY - minimum)
                / Math.max(0.001F, maximum - minimum);
    }

    private static double clamp(double value, double minimum, double maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private static boolean inside(double mouseX, double mouseY, int x, int y, int width, int height) {
        return mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + height;
    }

    private static int blend(int background, int foreground, float amount) {
        float t = Math.max(0.0F, Math.min(1.0F, amount));
        int a = Math.round((background >>> 24) * (1.0F - t) + (foreground >>> 24) * t);
        int r = Math.round((background >> 16 & 0xFF) * (1.0F - t) + (foreground >> 16 & 0xFF) * t);
        int g = Math.round((background >> 8 & 0xFF) * (1.0F - t) + (foreground >> 8 & 0xFF) * t);
        int b = Math.round((background & 0xFF) * (1.0F - t) + (foreground & 0xFF) * t);
        return a << 24 | r << 16 | g << 8 | b;
    }

    private static String formatValue(double value, int decimals) {
        if (decimals <= 0) {
            return Long.toString(Math.round(value));
        }
        return String.format(Locale.ROOT, "%." + decimals + "f", value);
    }

    private Component fitLabel(Component label, int maxWidth) {
        String value = label.getString();
        if (font.width(value) <= maxWidth) {
            return label;
        }
        String suffix = "…";
        int codePoints = value.codePointCount(0, value.length());
        while (codePoints > 0) {
            int end = value.offsetByCodePoints(0, --codePoints);
            String candidate = value.substring(0, end) + suffix;
            if (font.width(candidate) <= maxWidth) {
                return Component.literal(candidate);
            }
        }
        return Component.literal(suffix);
    }

    private static Component fontName(FontChoice font) {
        return Component.translatable("damage_numbers.font." + font.name().toLowerCase(Locale.ROOT));
    }

    private static Component animationName(AppearanceAnimation animation) {
        return Component.translatable("damage_numbers.animation." + animation.name().toLowerCase(Locale.ROOT));
    }

    private static Component animationScopeName(AppearanceAnimationScope scope) {
        return Component.translatable("damage_numbers.animation_scope."
                + scope.name().toLowerCase(Locale.ROOT));
    }

    private static Component colorModeName(ColorMode mode) {
        return Component.translatable("damage_numbers.color_mode." + mode.name().toLowerCase(Locale.ROOT));
    }

    private static String rangeLabel(float minimum, float nextMinimum) {
        String start = formatRangeValue(minimum);
        if (!Float.isFinite(nextMinimum)) {
            return start + "-∞";
        }
        if (isWhole(minimum) && isWhole(nextMinimum) && nextMinimum - minimum >= 1.0F) {
            return start + "-" + formatRangeValue(nextMinimum - 1.0F);
        }
        return start + "-<" + formatRangeValue(nextMinimum);
    }

    private static String formatRangeValue(float value) {
        return isWhole(value) ? Integer.toString(Math.round(value))
                : String.format(Locale.ROOT, "%.2f", value).replaceAll("0+$", "").replaceAll("\\.$", "");
    }

    private static boolean isWhole(float value) {
        return Math.abs(value - Math.round(value)) < 0.0001F;
    }

    private enum Page {
        PRESETS("damage_numbers.tab.presets", "damage_numbers.tab.presets_description"),
        CUSTOMIZATION("damage_numbers.tab.customization", "damage_numbers.tab.customization_description"),
        MOD_SETTINGS("damage_numbers.tab.mod_settings", "damage_numbers.tab.mod_settings_description");

        private final String translationKey;
        private final String descriptionKey;

        Page(String translationKey, String descriptionKey) {
            this.translationKey = translationKey;
            this.descriptionKey = descriptionKey;
        }
    }

    private enum Dragging {
        NONE,
        SLIDER,
        SCROLLBAR
    }

    private record PresetCard(String id, Component name, Snapshot style, boolean userOwned) {
    }

    private record FontCard(FontChoice font, String customFontId, Component name, boolean addCard) {
    }

    private record PreviewNumber(String text, float damage, float offsetX, float offsetY,
                                 long createdAtNanos) {
    }

    private record RangeSegment(int index, int x, int width) {
    }
}
