package dev.melix.damagenumbers.client.gui;

import org.joml.Matrix3x2fStack;
import dev.melix.damagenumbers.client.config.DamageNumbersConfig;
import dev.melix.damagenumbers.client.config.DamageNumbersConfig.ColorMode;
import dev.melix.damagenumbers.client.config.DamageNumbersConfig.ColorPaint;
import dev.melix.damagenumbers.client.config.DamageNumbersConfig.DamageRange;
import dev.melix.damagenumbers.client.config.DamageNumbersConfig.FontChoice;
import dev.melix.damagenumbers.client.config.DamageNumbersConfig.SavedPreset;
import dev.melix.damagenumbers.client.config.DamageNumbersConfig.Snapshot;
import dev.melix.damagenumbers.client.config.DamageNumbersConfig.AppearanceAnimation;
import dev.melix.damagenumbers.client.config.DamageNumbersConfig.AppearanceAnimationScope;
import dev.melix.damagenumbers.client.config.CustomFontManager;
import dev.melix.damagenumbers.client.config.CustomFontManager.CustomFont;
import dev.melix.damagenumbers.client.config.PresetLibrary;
import dev.melix.damagenumbers.client.render.AppearanceAnimator;
import dev.melix.damagenumbers.client.render.FontStyleResolver;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;

public final class DamageNumbersConfigScreen extends DamageNumbersScreenBase {
    private static final int BACKGROUND = 0xFF0E1014;
    private static final int PANEL = 0xFF171A20;
    private static final int PANEL_ALT = 0xFF1E222A;
    private static final int BORDER = 0xFF555B66;
    private static final int ACCENT = 0xFF58A6FF;
    private static final int TEXT = 0xFFF4F6F8;
    private static final int MUTED = 0xFFA6ADB8;
    private static final int RANGE_TOP = 43;
    private static final int BLUR_DIRECTIONS = 8;
    private static final int PAGE_CONTENT_TOP = 74;
    private static final int CUSTOMIZATION_OFFSET = 40;
    private static final int[] COLOR_PALETTE = {
            0xFFFFFFFF, 0xFFFFD84D, 0xFFFF8A3D, 0xFFFF3B30, 0xFFFF3158,
            0xFFB967FF, 0xFF4D8DFF, 0xFF4DE8FF, 0xFF55F29A, 0xFF20242A, 0xFF000000
    };

    private final Screen parent;
    private final DamageNumbersConfig config = DamageNumbersConfig.get();
    private final List<PresetCard> presetCards = new ArrayList<>();
    private final List<PresetAction> presetActions = new ArrayList<>();
    private final List<FontCard> fontCards = new ArrayList<>();
    private final List<ColorControl> colorControls = new ArrayList<>();
    private final List<PreviewNumber> previewNumbers = new ArrayList<>();
    private final List<RangeSegment> rangeSegments = new ArrayList<>();
    private RangeDeleteControl rangeDeleteControl;
    private Page page = Page.PRESETS;
    private boolean rebuilding;
    private long lastPreviewSpawnNanos;
    private double presetScroll;
    private double presetMaxScroll;
    private double customizationScroll;
    private double customizationMaxScroll;
    private double fontScroll;
    private double fontMaxScroll;
    private boolean advancedSettingsExpanded;

    public DamageNumbersConfigScreen(Screen parent) {
        super(Component.translatable("damage_numbers.screen.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        buildWidgets();
    }

    private void buildWidgets() {
        presetCards.clear();
        presetActions.clear();
        fontCards.clear();
        colorControls.clear();
        rangeSegments.clear();
        rangeDeleteControl = null;
        addSidebarButton(Page.PRESETS, 48);
        addSidebarButton(Page.CUSTOMIZATION, 76);
        addSidebarButton(Page.MOD_SETTINGS, 104);
        addRenderableWidget(FlatButton.create(Component.translatable("gui.done"), button -> onClose())
                .bounds(16, height - 34, sidebarWidth() - 20, 22).build());
        buildDamageRangeWidgets();

        switch (page) {
            case PRESETS -> buildPresetWidgets();
            case CUSTOMIZATION -> buildCustomizationWidgets();
            case MOD_SETTINGS -> buildModSettingsWidgets();
        }
    }

    private void buildDamageRangeWidgets() {
        List<DamageRange> ranges = config.damageRanges();
        int active = config.activeDamageRangeIndex();
        int x = contentX();
        int width = contentWidth() - 12;
        int controlsWidth = active > 0 ? 108 : 22;
        int trackWidth = Math.max(60, width - controlsWidth - 4);
        int segmentWidth = Math.max(1, trackWidth / ranges.size());
        for (int index = 0; index < ranges.size(); index++) {
            int segmentX = x + index * segmentWidth;
            int right = index == ranges.size() - 1 ? x + trackWidth : segmentX + segmentWidth;
            int rangeIndex = index;
            rangeSegments.add(new RangeSegment(rangeIndex, segmentX, RANGE_TOP, right - segmentX, 22));
            addRenderableWidget(FlatButton.create(Component.empty(), ignored -> {
                config.selectDamageRange(rangeIndex);
                rebuildPageWidgets();
            }).bounds(segmentX, RANGE_TOP, right - segmentX, 22).transparent().build());
        }
        int controlX = x + trackWidth + 4;
        if (active > 0) {
            EditBox minimum = new EditBox(font, controlX, RANGE_TOP, 56, 22,
                    Component.translatable("damage_numbers.ranges.start"));
            minimum.setMaxLength(12);
            minimum.setValue(formatRangeValue(ranges.get(active).minimumDamage()));
            minimum.setResponder(this::applyDamageRangeInput);
            addRenderableWidget(minimum);
            rangeDeleteControl = new RangeDeleteControl(controlX + 60, RANGE_TOP, 22, 22);
            addRenderableWidget(FlatButton.create(Component.empty(), ignored -> {
                config.removeDamageRange(config.activeDamageRangeIndex());
                config.save();
                rebuildPageWidgets();
            }).bounds(controlX + 60, RANGE_TOP, 22, 22).transparent().build());
            controlX += 86;
        }
        addRenderableWidget(FlatButton.create(Component.literal("+"), ignored -> {
            config.addDamageRangeAfter(config.activeDamageRangeIndex());
            config.save();
            rebuildPageWidgets();
        }).bounds(controlX, RANGE_TOP, 22, 22).build());
    }

    private void addSidebarButton(Page target, int y) {
        addRenderableWidget(FlatButton.create(Component.translatable(target.translationKey), button -> {
            if (page != target) {
                page = target;
                rebuildPageWidgets();
            }
        }).bounds(16, y, sidebarWidth() - 20, 22).build());
    }

    private void buildPresetWidgets() {
        int x = contentX();
        int width = contentWidth();
        addRenderableWidget(FlatButton.create(Component.translatable("damage_numbers.presets.save"), button ->
                        ScreenNavigator.open(minecraft, new SavePresetScreen(this)))
                .bounds(x + width - 124, 16, 112, 22).build());

        List<PresetCard> cards = new ArrayList<>();
        for (PresetLibrary.BuiltInPreset preset : PresetLibrary.builtIns()) {
            cards.add(new PresetCard(preset.id(), Component.translatable(preset.translationKey()), preset.style(),
                    0, 0, 0, 0, false));
        }
        for (SavedPreset preset : config.savedPresets()) {
            cards.add(new PresetCard(preset.id(), Component.literal(preset.name()), preset.style(),
                    0, 0, 0, 0, true));
        }

        int columns = cards.size() <= 4 ? 2 : Math.max(2, Math.min(3, width / 105));
        int gap = 6;
        int cardWidth = (width - gap * (columns - 1)) / columns;
        int top = PAGE_CONTENT_TOP;
        int bottom = height - 14;
        int cardHeight = 76;
        int rows = Math.max(1, (cards.size() + columns - 1) / columns);
        int contentHeight = rows * cardHeight + Math.max(0, rows - 1) * gap;
        presetMaxScroll = Math.max(0, contentHeight - (bottom - top));
        presetScroll = Math.max(0.0D, Math.min(presetMaxScroll, presetScroll));
        addWidget(new PresetScrollHandler(x, top, width, bottom - top, this::scrollPresets,
                this::scrollPresetsTo));
        for (int index = 0; index < cards.size(); index++) {
            int cardX = x + index % columns * (cardWidth + gap);
            int cardY = top + index / columns * (cardHeight + gap) - (int) Math.round(presetScroll);
            PresetCard original = cards.get(index);
            PresetCard card = new PresetCard(original.id(), original.name(), original.style(), cardX, cardY,
                    cardWidth, cardHeight, original.userOwned());
            presetCards.add(card);
            if (card.userOwned()) {
                int actionY = cardY + 4;
                int deleteX = cardX + cardWidth - 18;
                int editX = deleteX - 16;
                if (actionY >= top && actionY + 14 <= bottom) {
                    addRenderableWidget(FlatButton.create(Component.empty(), button -> {
                                ScreenNavigator.open(minecraft, new DeletePresetScreen(this, card.id(),
                                        card.name().getString()));
                            }).bounds(deleteX, actionY, 14, 14).build());
                    addRenderableWidget(FlatButton.create(Component.empty(), button -> ScreenNavigator.open(minecraft,
                                    new SavePresetScreen(this, card.id(), card.name().getString())))
                            .bounds(editX, actionY, 14, 14).build());
                    presetActions.add(new PresetAction(PresetActionType.EDIT, editX, actionY, 14, card.id()));
                    presetActions.add(new PresetAction(PresetActionType.DELETE, deleteX, actionY, 14, card.id()));
                }
            }
            int clippedTop = Math.max(top, cardY);
            int clippedBottom = Math.min(bottom, cardY + cardHeight);
            if (clippedBottom > clippedTop) {
                addRenderableWidget(FlatButton.create(Component.empty(), button -> {
                    config.applyPreset(card.id(), card.style());
                    rebuildPageWidgets();
                }).bounds(cardX, clippedTop, cardWidth, clippedBottom - clippedTop).build());
            }
        }
    }

    private void buildCustomizationWidgets() {
        previewNumbers.clear();
        lastPreviewSpawnNanos = 0L;
        int x = contentX();
        int width = contentWidth();
        int viewportTop = PAGE_CONTENT_TOP;
        int viewportBottom = height - 12;
        int formWidth = width - 12;
        int contentBottom = (advancedSettingsExpanded ? 475 : 409) + CUSTOMIZATION_OFFSET;
        customizationMaxScroll = Math.max(0, contentBottom - viewportBottom);
        customizationScroll = Math.max(0.0D, Math.min(customizationMaxScroll, customizationScroll));
        int fontGap = 4;
        int fontWidth = Math.max(88, Math.min(118, (formWidth - fontGap * 3) / 4));
        List<FontCard> cards = new ArrayList<>();
        for (FontChoice font : FontChoice.values()) {
            if (font.isBuiltIn()) {
                cards.add(new FontCard(font, null, fontName(font), false, 0, 0, fontWidth, 38));
            }
        }
        for (CustomFont customFont : CustomFontManager.fonts()) {
            cards.add(new FontCard(FontChoice.CUSTOM, customFont.id(), Component.literal(customFont.name()),
                    false, 0, 0, fontWidth, 38));
        }
        cards.add(new FontCard(FontChoice.CUSTOM, null,
                Component.translatable("damage_numbers.font.add_custom"), true, 0, 0, fontWidth, 38));
        int fontContentWidth = cards.size() * fontWidth + Math.max(0, cards.size() - 1) * fontGap;
        fontMaxScroll = Math.max(0, fontContentWidth - formWidth);
        fontScroll = Math.max(0.0D, Math.min(fontMaxScroll, fontScroll));
        int fontY = customizationY(100);
        addWidget(new FontScrollHandler(x, fontY, formWidth, 44, this::scrollFonts, this::scrollFontsTo));
        addWidget(new PresetScrollHandler(x, viewportTop, width, viewportBottom - viewportTop,
                this::scrollCustomization, this::scrollCustomizationTo));
        for (int index = 0; index < cards.size(); index++) {
            FontCard source = cards.get(index);
            int cardX = x + index * (fontWidth + fontGap) - (int) Math.round(fontScroll);
            FontCard card = new FontCard(source.font(), source.customFontId(), source.name(), source.addCard(),
                    cardX, fontY, fontWidth, 38);
            fontCards.add(card);
            int clippedLeft = Math.max(x, cardX);
            int clippedRight = Math.min(x + formWidth, cardX + fontWidth);
            if (clippedRight > clippedLeft && fullyVisible(fontY, 38, viewportTop, viewportBottom)) {
                int deleteX = cardX + fontWidth - 17;
                if (card.font() == FontChoice.CUSTOM && !card.addCard()
                        && deleteX >= x && deleteX + 14 <= x + formWidth) {
                    addRenderableWidget(FlatButton.create(Component.empty(), button -> ScreenNavigator.open(minecraft,
                                    new DeleteFontScreen(this, card.customFontId(), card.name().getString())))
                            .bounds(deleteX, fontY + 2, 14, 14).transparent().build());
                }
                addRenderableWidget(FlatButton.create(Component.empty(), button -> selectFontCard(card))
                        .bounds(clippedLeft, fontY, clippedRight - clippedLeft, 38).build());
            }
        }

        Snapshot style = config.snapshot();
        addScrollingToggle(x, customizationY(145), formWidth, style.scaleWithDamage(),
                () -> mutate(() -> config.setScaleWithDamage(!config.snapshot().scaleWithDamage())),
                viewportTop, viewportBottom);
        addDecimalInput(x + formWidth - 88, customizationY(167), 86, 18, Float.toString(style.scale()),
                this::applyScaleInput, viewportTop, viewportBottom);
        addScrollingAction(x, customizationY(189), formWidth, () -> mutate(this::cycleAnimationScope), viewportTop,
                viewportBottom);
        addScrollingAction(x, customizationY(211), formWidth, () -> mutate(this::cycleAnimation), viewportTop,
                viewportBottom);
        addIntegerInput(x + formWidth - 88, customizationY(233), 86, 18,
                Long.toString(style.appearanceAnimationMillis()), this::applyAnimationDurationInput,
                viewportTop, viewportBottom);
        addScrollingAction(x, customizationY(255), formWidth, () -> mutate(this::toggleFillMode), viewportTop,
                viewportBottom);
        addColorControl(x, customizationY(277), formWidth, ColorPickerScreen.Target.FILL_FIRST, true,
                viewportTop, viewportBottom);
        addColorControl(x, customizationY(299), formWidth, ColorPickerScreen.Target.FILL_SECOND,
                style.fill().mode() == ColorMode.GRADIENT, viewportTop, viewportBottom);
        addColorControl(x, customizationY(321), formWidth, ColorPickerScreen.Target.UNDERLAY, true,
                viewportTop, viewportBottom);
        addDecimalInput(x + formWidth - 88, customizationY(343), 86, 18, Float.toString(style.borderWidth()),
                this::applyBorderInput, viewportTop, viewportBottom);
        int sliderY = customizationY(365);
        if (fullyVisible(sliderY, 18, viewportTop, viewportBottom)) {
            addRenderableWidget(new FlatAngleSlider(x + formWidth - 126, sliderY + 1, 94, 16,
                    style.gradientAngleDegrees(), angle -> {
                        config.setGradientAngleDegrees((float) angle);
                        config.save();
            }));
        }
        addAdvancedToggle(x, customizationY(387), formWidth, () -> {
            advancedSettingsExpanded = !advancedSettingsExpanded;
            rebuildPageWidgets();
        }, viewportTop, viewportBottom);
        if (advancedSettingsExpanded) {
            addIntegerInput(x + formWidth - 88, customizationY(409), 86, 18,
                    Long.toString(style.fadeOutTimeMillis()), this::applyFadeInput, viewportTop, viewportBottom);
            addDecimalInput(x + formWidth - 88, customizationY(431), 86, 18,
                    Float.toString(style.minimumSpawnRadius()), this::applyMinimumRadiusInput,
                    viewportTop, viewportBottom);
            addDecimalInput(x + formWidth - 88, customizationY(453), 86, 18,
                    Float.toString(style.maximumSpawnRadius()), this::applyMaximumRadiusInput,
                    viewportTop, viewportBottom);
        }
    }

    private void buildModSettingsWidgets() {
        int x = contentX() + 12;
        int right = contentX() + contentWidth() - 12;
        addRenderableWidget(FlatButton.create(enabledText(), button -> mutateFunctional(() ->
                        config.setEnabled(!config.isEnabled())))
                .bounds(right - 104, 50 + CUSTOMIZATION_OFFSET, 104, 20).build());
        addRenderableWidget(FlatButton.create(showAllDamageText(), button -> mutateFunctional(() ->
                        config.setShowAllDamageSources(!config.showAllDamageSources())))
                .bounds(right - 104, 104 + CUSTOMIZATION_OFFSET, 104, 20).build());
        EditBox minimumDamage = new EditBox(font, right - 86, 158 + CUSTOMIZATION_OFFSET, 86, 18, Component.empty());
        minimumDamage.setMaxLength(12);
        minimumDamage.setValue(Float.toString(config.minimumDamage()));
        minimumDamage.setResponder(this::applyMinimumDamageInput);
        addRenderableWidget(minimumDamage);
    }

    private void addAdvancedToggle(int x, int y, int width, Runnable action,
                                   int viewportTop, int viewportBottom) {
        if (fullyVisible(y, 18, viewportTop, viewportBottom)) {
            addRenderableWidget(FlatButton.create(Component.empty(), button -> action.run())
                    .bounds(x, y, width, 18).transparent().build());
        }
    }

    private void addScrollingToggle(int x, int y, int width, boolean enabled, Runnable action,
                                    int viewportTop, int viewportBottom) {
        if (fullyVisible(y, 18, viewportTop, viewportBottom)) {
            addRenderableWidget(FlatButton.create(Component.translatable(enabled
                                    ? "damage_numbers.common.enabled" : "damage_numbers.common.disabled"),
                            button -> action.run())
                    .bounds(x + width - 88, y, 86, 18).build());
        }
    }

    private void addScrollingAction(int x, int y, int width, Runnable action, int viewportTop, int viewportBottom) {
        if (fullyVisible(y, 18, viewportTop, viewportBottom)) {
            addRenderableWidget(FlatButton.create(Component.literal(">"), button -> action.run())
                    .bounds(x + width - 36, y, 34, 18).build());
        }
    }

    private void addColorControl(int x, int y, int width, ColorPickerScreen.Target target, boolean active,
                                 int viewportTop, int viewportBottom) {
        int swatchX = x + width - 88;
        int swatchWidth = 62;
        int buttonX = x + width - 22;
        if (fullyVisible(y, 18, viewportTop, viewportBottom)) {
            FlatButton swatch = addRenderableWidget(FlatButton.create(Component.empty(), ignored ->
                            openColorPicker(target)).bounds(swatchX, y, swatchWidth, 18).build());
            FlatButton pipette = addRenderableWidget(FlatButton.create(Component.empty(), ignored ->
                            openColorPicker(target)).bounds(buttonX, y, 20, 18).build());
            swatch.active = active;
            pipette.active = active;
        }
        colorControls.add(new ColorControl(target, swatchX, y, swatchWidth, 18, buttonX, y, active));
    }

    private void addDecimalInput(int x, int y, int width, int height, String value,
                                 java.util.function.Consumer<String> responder, int viewportTop,
                                 int viewportBottom) {
        if (!fullyVisible(y, height, viewportTop, viewportBottom)) {
            return;
        }
        EditBox box = new EditBox(font, x, y, width, height, Component.empty());
        box.setMaxLength(24);
        box.setValue(value);
        box.setResponder(responder);
        addRenderableWidget(box);
    }

    private void addIntegerInput(int x, int y, int width, int height, String value,
                                 java.util.function.Consumer<String> responder, int viewportTop,
                                 int viewportBottom) {
        if (!fullyVisible(y, height, viewportTop, viewportBottom)) {
            return;
        }
        EditBox box = new EditBox(font, x, y, width, height, Component.empty());
        box.setMaxLength(19);
        box.setValue(value);
        box.setResponder(responder);
        addRenderableWidget(box);
    }

    private void applyScaleInput(String text) {
        try {
            float value = Float.parseFloat(text.replace(',', '.'));
            if (value > 0.001F && Float.isFinite(value)) {
                config.setScale(value);
                config.save();
            }
        } catch (NumberFormatException ignored) {
        }
    }

    private void applyDamageRangeInput(String text) {
        try {
            float value = Float.parseFloat(text.replace(',', '.'));
            if (config.setDamageRangeMinimum(config.activeDamageRangeIndex(), value)) {
                config.save();
            }
        } catch (NumberFormatException ignored) {
        }
    }

    private void applyFadeInput(String text) {
        try {
            config.setFadeOutTimeMillis(Long.parseLong(text));
            config.save();
        } catch (NumberFormatException ignored) {
        }
    }

    private void applyAnimationDurationInput(String text) {
        try {
            config.setAppearanceAnimationMillis(Long.parseLong(text));
            config.save();
        } catch (NumberFormatException ignored) {
        }
    }

    private void applyBorderInput(String text) {
        try {
            float value = Float.parseFloat(text.replace(',', '.'));
            if (value >= 0.0F && Float.isFinite(value)) {
                config.setBorderWidth(value);
                config.save();
            }
        } catch (NumberFormatException ignored) {
        }
    }

    private void applyMinimumDamageInput(String text) {
        try {
            float value = Float.parseFloat(text.replace(',', '.'));
            if (value >= 0.0F && Float.isFinite(value)) {
                config.setMinimumDamage(Math.min(2_048.0F, value));
                config.save();
            }
        } catch (NumberFormatException ignored) {
        }
    }

    private void applyMinimumRadiusInput(String text) {
        try {
            float value = Float.parseFloat(text.replace(',', '.'));
            if (value >= 0.0F && Float.isFinite(value)) {
                Snapshot style = config.snapshot();
                config.setSpawnRadiusRange(value, Math.max(value, style.maximumSpawnRadius()));
                config.save();
            }
        } catch (NumberFormatException ignored) {
        }
    }

    private void applyMaximumRadiusInput(String text) {
        try {
            float value = Float.parseFloat(text.replace(',', '.'));
            Snapshot style = config.snapshot();
            if (value >= style.minimumSpawnRadius() && Float.isFinite(value)) {
                config.setSpawnRadiusRange(style.minimumSpawnRadius(), value);
                config.save();
            }
        } catch (NumberFormatException ignored) {
        }
    }

    private void scrollPresets(double delta) {
        double next = Math.max(0.0D, Math.min(presetMaxScroll, presetScroll - delta * 38.0D));
        if (Math.abs(next - presetScroll) > 0.01D) {
            presetScroll = next;
            rebuildPageWidgets();
        }
    }

    private void scrollPresetsTo(double position) {
        presetScroll = Math.max(0.0D, Math.min(presetMaxScroll, position * presetMaxScroll));
        rebuildPageWidgets();
    }

    private void scrollCustomization(double delta) {
        double next = Math.max(0.0D, Math.min(customizationMaxScroll,
                customizationScroll - delta * 38.0D));
        if (Math.abs(next - customizationScroll) > 0.01D) {
            customizationScroll = next;
            rebuildPageWidgets();
        }
    }

    private void scrollCustomizationTo(double position) {
        customizationScroll = Math.max(0.0D,
                Math.min(customizationMaxScroll, position * customizationMaxScroll));
        rebuildPageWidgets();
    }

    private void scrollFonts(double delta) {
        double next = Math.max(0.0D, Math.min(fontMaxScroll, fontScroll - delta * 42.0D));
        if (Math.abs(next - fontScroll) > 0.01D) {
            fontScroll = next;
            rebuildPageWidgets();
        }
    }

    private void scrollFontsTo(double position) {
        fontScroll = Math.max(0.0D, Math.min(fontMaxScroll, position * fontMaxScroll));
        rebuildPageWidgets();
    }

    @Override
    protected boolean handleMouseScroll(double mouseX, double mouseY, double horizontal, double vertical) {
        if (page == Page.PRESETS && inside(mouseX, mouseY, contentX(), PAGE_CONTENT_TOP,
                contentWidth(), height - 14 - PAGE_CONTENT_TOP)) {
            scrollPresets(vertical);
            return presetMaxScroll > 0.0D;
        }
        if (page != Page.CUSTOMIZATION) {
            return false;
        }
        int fontY = customizationY(100);
        if (inside(mouseX, mouseY, contentX(), fontY, customizationFormWidth(), 44)) {
            scrollFonts(Math.abs(horizontal) > 0.001D ? horizontal : vertical);
            return fontMaxScroll > 0.0D;
        }
        if (inside(mouseX, mouseY, contentX(), PAGE_CONTENT_TOP,
                contentWidth(), height - 12 - PAGE_CONTENT_TOP)) {
            scrollCustomization(vertical);
            return customizationMaxScroll > 0.0D;
        }
        return false;
    }

    private void selectFontCard(FontCard card) {
        if (card.addCard()) {
            CustomFontManager.openImportDialog(minecraft, imported -> {
                config.setCustomFont(imported.id());
                config.save();
                rebuildPageWidgets();
            });
        } else if (card.font() == FontChoice.CUSTOM) {
            mutate(() -> config.setCustomFont(card.customFontId()));
        } else {
            mutate(() -> config.setFont(card.font()));
        }
    }

    private int customizationY(int baseY) {
        return baseY + CUSTOMIZATION_OFFSET - (int) Math.round(customizationScroll);
    }

    private static boolean fullyVisible(int y, int height, int viewportTop, int viewportBottom) {
        return y >= viewportTop && y + height <= viewportBottom;
    }

    private void mutate(Runnable mutation) {
        mutation.run();
        config.save();
        rebuildPageWidgets();
    }

    private void mutateFunctional(Runnable mutation) {
        mutation.run();
        config.save();
        rebuildPageWidgets();
    }

    private void rebuildPageWidgets() {
        if (rebuilding) {
            return;
        }
        rebuilding = true;
        clearWidgets();
        buildWidgets();
        rebuilding = false;
    }

    private void openColorPicker(ColorPickerScreen.Target target) {
        ScreenNavigator.open(minecraft, new ColorPickerScreen(this, target));
    }

    private void cycleAnimation() {
        AppearanceAnimation[] values = AppearanceAnimation.values();
        AppearanceAnimation current = config.snapshot().appearanceAnimation();
        config.setAppearanceAnimation(values[(current.ordinal() + 1) % values.length]);
    }

    private void cycleAnimationScope() {
        AppearanceAnimationScope[] values = AppearanceAnimationScope.values();
        AppearanceAnimationScope current = config.snapshot().appearanceAnimationScope();
        config.setAppearanceAnimationScope(values[(current.ordinal() + 1) % values.length]);
    }

    private void toggleFillMode() {
        ColorPaint current = config.snapshot().fill();
        if (current.mode() == ColorMode.GRADIENT) {
            config.setFill(ColorPaint.solid(current.firstArgb()));
        } else {
            config.setFill(ColorPaint.gradient(current.firstArgb(), nextPaletteColor(current.firstArgb())));
        }
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
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fill(0, 0, width, height, BACKGROUND);
        graphics.fill(8, 8, sidebarWidth(), height - 8, PANEL);
        graphics.fill(contentX() - 6, 8, width - 8, height - 8, PANEL);
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
        graphics.text(font, Component.empty().append(title).append(" 1.0"), 16, 18, TEXT, false);
        graphics.text(font, "by mel1x", 16, 30, MUTED, false);
        graphics.text(font, Component.translatable(page.translationKey), contentX(), 20, TEXT, false);
        renderSidebarSelection(graphics);
        renderDamageRanges(graphics, mouseX, mouseY);

        switch (page) {
            case PRESETS -> renderPresets(graphics, mouseX, mouseY);
            case CUSTOMIZATION -> renderCustomization(graphics, mouseX, mouseY);
            case MOD_SETTINGS -> renderModSettings(graphics);
        }
    }

    private void renderDamageRanges(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        graphics.text(font, Component.translatable("damage_numbers.ranges.title"), contentX(), 31,
                MUTED, false);
        List<DamageRange> ranges = config.damageRanges();
        int active = config.activeDamageRangeIndex();
        for (RangeSegment segment : rangeSegments) {
            DamageRange range = ranges.get(segment.index());
            boolean hovered = inside(mouseX, mouseY, segment.x(), segment.y(), segment.width(), segment.height());
            int surface = blend(PANEL_ALT, range.style().fill().colorAt(0.5F), hovered ? 0.34F : 0.22F);
            graphics.fill(segment.x(), segment.y(), segment.x() + segment.width(), segment.y() + segment.height(),
                    surface);
            drawBorder(graphics, segment.x(), segment.y(), segment.width(), segment.height(),
                    segment.index() == active ? ACCENT : BORDER, segment.index() == active ? 2 : 1);
            float next = segment.index() + 1 < ranges.size()
                    ? ranges.get(segment.index() + 1).minimumDamage() : Float.POSITIVE_INFINITY;
            graphics.centeredText(font, fitCardLabel(Component.literal(rangeLabel(range.minimumDamage(), next)),
                            segment.width() - 8),
                    segment.x() + segment.width() / 2, segment.y() + 7, TEXT);
        }
        if (rangeDeleteControl != null) {
            boolean hovered = inside(mouseX, mouseY, rangeDeleteControl.x(), rangeDeleteControl.y(),
                    rangeDeleteControl.width(), rangeDeleteControl.height());
            graphics.fill(rangeDeleteControl.x(), rangeDeleteControl.y(),
                    rangeDeleteControl.x() + rangeDeleteControl.width(),
                    rangeDeleteControl.y() + rangeDeleteControl.height(), hovered ? 0xFF343B46 : PANEL_ALT);
            drawBorder(graphics, rangeDeleteControl.x(), rangeDeleteControl.y(), rangeDeleteControl.width(),
                    rangeDeleteControl.height(), hovered ? 0xFFFF7B72 : BORDER, 1);
            drawTrashIcon(graphics, rangeDeleteControl.x() + 4, rangeDeleteControl.y() + 4, 0xFFFF7B72);
        }
    }

    private void renderSidebarSelection(GuiGraphicsExtractor graphics) {
        int y = switch (page) {
            case PRESETS -> 48;
            case CUSTOMIZATION -> 76;
            case MOD_SETTINGS -> 104;
        };
        drawBorder(graphics, 14, y - 2, sidebarWidth() - 16, 26, ACCENT, 2);
    }

    private void renderPresets(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        if (presetCards.isEmpty()) {
            graphics.text(font, Component.translatable("damage_numbers.presets.empty"), contentX(), 64,
                    MUTED, false);
            return;
        }
        String selected = config.selectedPresetId();
        int viewportTop = PAGE_CONTENT_TOP;
        int viewportBottom = height - 14;
        graphics.enableScissor(contentX(), viewportTop, contentX() + contentWidth(), viewportBottom);
        for (PresetCard card : presetCards) {
            boolean hovered = inside(mouseX, mouseY, card.x(), card.y(), card.width(), card.height());
            int border = card.id().equals(selected) ? ACCENT : BORDER;
            graphics.fill(card.x(), card.y(), card.x() + card.width(), card.y() + card.height(),
                    hovered ? 0xFF262B34 : PANEL_ALT);
            drawBorder(graphics, card.x(), card.y(), card.width(), card.height(), border, 2);
            int nameTop = card.y() + card.height() * 4 / 5;
            graphics.fill(card.x() + 2, nameTop, card.x() + card.width() - 2, card.y() + card.height() - 2,
                    0xFF14171C);
            graphics.centeredText(font, card.name(), card.x() + card.width() / 2, nameTop + 2, TEXT);
            drawNumberPreview(graphics, card.style(), card.x() + card.width() / 2,
                    card.y() + (nameTop - card.y()) / 2 + 2, 2.1F);
        }
        for (PresetAction action : presetActions) {
            boolean hovered = inside(mouseX, mouseY, action.x(), action.y(), action.size(), action.size());
            graphics.fill(action.x(), action.y(), action.x() + action.size(), action.y() + action.size(),
                    hovered ? 0xFF343B46 : 0xFF20252D);
            drawBorder(graphics, action.x(), action.y(), action.size(), action.size(),
                    hovered ? ACCENT : BORDER, 1);
            if (action.type() == PresetActionType.EDIT) {
                drawEditIcon(graphics, action.x(), action.y(), TEXT);
            } else {
                drawTrashIcon(graphics, action.x(), action.y(), 0xFFFF7B72);
            }
        }
        graphics.disableScissor();
        if (presetMaxScroll > 0.0D) {
            int trackX = contentX() + contentWidth() - 3;
            int trackHeight = viewportBottom - viewportTop;
            int thumbHeight = Math.max(18, (int) Math.round(trackHeight * trackHeight
                    / (trackHeight + presetMaxScroll)));
            int thumbY = viewportTop + (int) Math.round((trackHeight - thumbHeight)
                    * (presetScroll / presetMaxScroll));
            graphics.fill(trackX, viewportTop, trackX + 2, viewportBottom, 0xFF292E37);
            graphics.fill(trackX, thumbY, trackX + 2, thumbY + thumbHeight, ACCENT);
        }
    }

    private void renderCustomization(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        Snapshot style = config.snapshot();
        int formWidth = customizationFormWidth();
        int viewportTop = PAGE_CONTENT_TOP;
        int viewportBottom = height - 12;
        int previewTop = customizationY(34);
        graphics.enableScissor(contentX(), viewportTop, contentX() + formWidth, viewportBottom);
        graphics.fill(contentX(), previewTop, contentX() + formWidth, previewTop + 60, 0xFF11141A);
        drawBorder(graphics, contentX(), previewTop, formWidth, 60, BORDER, 1);
        renderAnimatedPreview(graphics, style, previewTop, viewportTop, viewportBottom);
        for (FontCard card : fontCards) {
            if (card.x() + card.width() <= contentX() || card.x() >= contentX() + formWidth) {
                continue;
            }
            boolean hovered = inside(mouseX, mouseY, card.x(), card.y(), card.width(), card.height());
            graphics.fill(card.x(), card.y(), card.x() + card.width(), card.y() + card.height(),
                    hovered ? 0xFF282E38 : PANEL_ALT);
            boolean selected = !card.addCard() && card.font() == style.font()
                    && Objects.equals(card.customFontId(), style.customFontId());
            drawBorder(graphics, card.x(), card.y(), card.width(), card.height(),
                    selected ? ACCENT : BORDER, 2);
            if (card.addCard()) {
                int centerX = card.x() + card.width() / 2;
                graphics.fill(centerX - 5, card.y() + 13, centerX + 6, card.y() + 15, TEXT);
                graphics.fill(centerX - 1, card.y() + 9, centerX + 2, card.y() + 20, TEXT);
            } else {
                Component preview = FontStyleResolver.component("123", card.font(), card.customFontId());
                graphics.centeredText(font, preview, card.x() + card.width() / 2, card.y() + 7, TEXT);
            }
            if (card.font() == FontChoice.CUSTOM && !card.addCard()) {
                drawTrashIcon(graphics, card.x() + card.width() - 17, card.y() + 2, 0xFFFF7B72);
            }
            graphics.centeredText(font, fitCardLabel(card.name(), card.width() - 8),
                    card.x() + card.width() / 2, card.y() + 25, MUTED);
        }
        drawFontScrollbar(graphics, contentX(), customizationY(140), formWidth);

        int left = contentX();
        drawInputLabel(graphics, left, customizationY(145),
                Component.translatable("damage_numbers.customization.scale_with_damage"));
        drawInputLabel(graphics, left, customizationY(167), Component.translatable("damage_numbers.customization.scale"));
        drawSetting(graphics, left, customizationY(189),
                Component.translatable("damage_numbers.customization.animation_scope"),
                animationScopeName(style.appearanceAnimationScope()).getString());
        drawSetting(graphics, left, customizationY(211), Component.translatable("damage_numbers.customization.animation"),
                animationName(style.appearanceAnimation()).getString());
        drawInputLabel(graphics, left, customizationY(233),
                Component.translatable("damage_numbers.customization.animation_duration"));
        drawSetting(graphics, left, customizationY(255), Component.translatable("damage_numbers.customization.fill_mode"),
                Component.translatable("damage_numbers.color_mode." + style.fill().mode().name().toLowerCase(Locale.ROOT)).getString());
        drawInputLabel(graphics, left, customizationY(277), Component.translatable("damage_numbers.customization.color_first"));
        drawInputLabel(graphics, left, customizationY(299), Component.translatable("damage_numbers.customization.color_second"));
        drawInputLabel(graphics, left, customizationY(321), Component.translatable("damage_numbers.customization.underlay"));
        drawInputLabel(graphics, left, customizationY(343), Component.translatable("damage_numbers.customization.border_width"));
        int angleY = customizationY(365);
        drawInputLabel(graphics, left, angleY, Component.translatable("damage_numbers.customization.gradient_angle"));
        drawAngleDial(graphics, left + formWidth - 148, angleY + 2, style.gradientAngleDegrees());
        graphics.text(font, Math.round(style.gradientAngleDegrees()) + "\u00B0", left + formWidth - 29,
                angleY + 5, MUTED, false);
        Component advancedLabel = Component.literal(advancedSettingsExpanded ? "▼ " : "▶ ")
                .append(Component.translatable("damage_numbers.customization.advanced"));
        graphics.text(font, advancedLabel, left, customizationY(387) + 5, MUTED, false);
        if (advancedSettingsExpanded) {
            drawInputLabel(graphics, left, customizationY(409),
                    Component.translatable("damage_numbers.customization.lifetime"));
            drawInputLabel(graphics, left, customizationY(431),
                    Component.translatable("damage_numbers.customization.minimum_radius"));
            drawInputLabel(graphics, left, customizationY(453),
                    Component.translatable("damage_numbers.customization.maximum_radius"));
        }
        renderColorControls(graphics, style, mouseX, mouseY);
        graphics.disableScissor();
        drawScrollbar(graphics, customizationScroll, customizationMaxScroll, viewportTop, viewportBottom);
    }

    private void renderColorControls(GuiGraphicsExtractor graphics, Snapshot style, int mouseX, int mouseY) {
        for (ColorControl control : colorControls) {
            int color = switch (control.target()) {
                case FILL_FIRST -> style.fill().firstArgb();
                case FILL_SECOND -> style.fill().secondArgb();
                case UNDERLAY -> style.border().firstArgb();
            };
            graphics.fill(control.x(), control.y(), control.x() + control.width(), control.y() + control.height(),
                    control.active() ? color : 0xFF30343B);
            drawBorder(graphics, control.x(), control.y(), control.width(), control.height(), BORDER, 1);
            int icon = control.active() ? 0xFFDCE7F4 : 0xFF69717C;
            drawPipetteIcon(graphics, control.buttonX(), control.buttonY(), icon);
            if (control.active() && inside(mouseX, mouseY, control.x(), control.y(),
                    control.width(), control.height())) {
                drawBorder(graphics, control.x(), control.y(), control.width(), control.height(), ACCENT, 1);
            }
            if (inside(mouseX, mouseY, control.buttonX(), control.buttonY(), 20, 18) && control.active()) {
                drawBorder(graphics, control.buttonX(), control.buttonY(), 20, 18, ACCENT, 1);
            }
        }
    }

    private static void drawPipetteIcon(GuiGraphicsExtractor graphics, int x, int y, int color) {
        graphics.fill(x + 12, y + 3, x + 15, y + 6, color);
        graphics.fill(x + 10, y + 5, x + 13, y + 9, color);
        graphics.fill(x + 7, y + 8, x + 11, y + 11, color);
        graphics.fill(x + 5, y + 11, x + 8, y + 14, color);
        graphics.fill(x + 4, y + 13, x + 6, y + 16, color);
    }

    private static void drawEditIcon(GuiGraphicsExtractor graphics, int x, int y, int color) {
        graphics.fill(x + 8, y + 3, x + 11, y + 5, color);
        graphics.fill(x + 7, y + 4, x + 10, y + 7, color);
        graphics.fill(x + 5, y + 6, x + 8, y + 9, color);
        graphics.fill(x + 3, y + 8, x + 6, y + 11, color);
        graphics.fill(x + 3, y + 10, x + 5, y + 12, color);
    }

    private static void drawTrashIcon(GuiGraphicsExtractor graphics, int x, int y, int color) {
        graphics.fill(x + 4, y + 3, x + 10, y + 4, color);
        graphics.fill(x + 5, y + 2, x + 9, y + 3, color);
        graphics.fill(x + 4, y + 5, x + 10, y + 6, color);
        graphics.fill(x + 5, y + 6, x + 6, y + 11, color);
        graphics.fill(x + 8, y + 6, x + 9, y + 11, color);
        graphics.fill(x + 5, y + 11, x + 9, y + 12, color);
    }

    private void drawScrollbar(GuiGraphicsExtractor graphics, double scroll, double maximum, int top, int bottom) {
        if (maximum <= 0.0D) {
            return;
        }
        int trackX = contentX() + contentWidth() - 3;
        int trackHeight = bottom - top;
        int thumbHeight = Math.max(18, (int) Math.round(trackHeight * trackHeight / (trackHeight + maximum)));
        int thumbY = top + (int) Math.round((trackHeight - thumbHeight) * (scroll / maximum));
        graphics.fill(trackX, top, trackX + 2, bottom, 0xFF292E37);
        graphics.fill(trackX, thumbY, trackX + 2, thumbY + thumbHeight, ACCENT);
    }

    private void drawInputLabel(GuiGraphicsExtractor graphics, int x, int y, Component label) {
        graphics.text(font, label, x, y + 5, TEXT, false);
    }

    private void renderModSettings(GuiGraphicsExtractor graphics) {
        int x = contentX() + 12;
        graphics.text(font, Component.translatable("damage_numbers.mod_settings.state"), x, 54 + CUSTOMIZATION_OFFSET, TEXT, false);
        graphics.text(font, Component.translatable("damage_numbers.mod_settings.state_description"), x, 68 + CUSTOMIZATION_OFFSET,
                MUTED, false);
        graphics.text(font, Component.translatable("damage_numbers.mod_settings.show_all_damage"), x, 108 + CUSTOMIZATION_OFFSET,
                TEXT, false);
        graphics.text(font, Component.translatable("damage_numbers.mod_settings.show_all_damage_description"),
                x, 122 + CUSTOMIZATION_OFFSET, MUTED, false);
        graphics.text(font, Component.translatable("damage_numbers.mod_settings.minimum_damage"), x, 162 + CUSTOMIZATION_OFFSET,
                TEXT, false);
        graphics.text(font, Component.translatable("damage_numbers.mod_settings.minimum_damage_description"),
                x, 176 + CUSTOMIZATION_OFFSET, MUTED, false);
    }

    private void drawSetting(GuiGraphicsExtractor graphics, int x, int y, Component label, String value) {
        graphics.text(font, label, x, y + 5, TEXT, false);
        int valueRight = x + customizationFormWidth() - 50;
        graphics.text(font, value, Math.max(x + 122, valueRight - font.width(value)), y + 5, MUTED, false);
    }

    private void drawNumberPreview(GuiGraphicsExtractor graphics, Snapshot style, float centerX, float centerY, float scale) {
        drawNumberPreview(graphics, style, "8", centerX, centerY, scale, 1.0F);
    }

    private void drawNumberPreview(GuiGraphicsExtractor graphics, Snapshot style, String value, float centerX, float centerY,
                                   float scale, float alpha) {
        Matrix3x2fStack pose = graphics.pose();
        pose.pushMatrix();
        pose.translate(centerX, centerY);
        pose.scale(scale, scale);
        Component number = FontStyleResolver.component(value, style.font(), style.customFontId());
        int textX = -font.width(number) / 2;
        int textY = -font.lineHeight / 2;
        int underlay = withAlpha(style.border().colorAt(0.5F), alpha);
        float borderWidth = style.borderWidth();
        if (borderWidth > 0.0F) {
            int rings = Math.max(1, Math.min(8, (int) Math.ceil(borderWidth * 2.0F)));
            for (int ring = 1; ring <= rings; ring++) {
                float radius = borderWidth * ring / rings;
                int directions = Math.min(32, 8 + (ring - 1) * 4);
                for (int direction = 0; direction < directions; direction++) {
                    double angle = Math.PI * 2.0D * direction / directions;
                    drawPreviewText(graphics, pose, number, textX, textY,
                            (float) Math.cos(angle) * radius, (float) Math.sin(angle) * radius, underlay);
                }
            }
        }
        drawPreviewText(graphics, pose, number, textX, textY, 0.0F, 0.0F, underlay);

        drawGradientPreview(graphics, number, textX, textY, style, alpha);
        pose.popMatrix();
    }

    private void drawGradientPreview(GuiGraphicsExtractor graphics, Component number, int textX, int textY,
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
            graphics.text(font, number, textX, textY, color, false);
            graphics.disableScissor();
        }
    }

    private void drawAnimatedNumberPreview(GuiGraphicsExtractor graphics, Snapshot style, String value,
                                           float centerX, float centerY, float scale, float ageSeconds,
                                           float baseAlpha) {
        Matrix3x2fStack pose = graphics.pose();
        pose.pushMatrix();
        pose.translate(centerX, centerY);
        pose.scale(scale, scale);
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
        pose.popMatrix();
    }

    /**
     * Draws one glyph under its animation transform. The ring or blur offset is applied outside the
     * transform so every copy stays a rigid offset of the animated glyph.
     */
    private void drawPreviewGlyph(GuiGraphicsExtractor graphics, Matrix3x2fStack pose, Component glyph,
                                  int glyphX, int glyphWidth, int textY,
                                  AppearanceAnimator.Transform transform, float offsetX, float offsetY,
                                  int color) {
        pose.pushMatrix();
        pose.translate(offsetX, offsetY);
        if (!transform.identity()) {
            float anchorX = transform.anchorWholeNumber() ? 0.0F : glyphX + glyphWidth * 0.5F;
            float anchorY = textY + font.lineHeight * 0.5F;
            pose.translate(anchorX, anchorY + transform.offsetY());
            if (transform.rotationDegrees() != 0.0F) {
                pose.rotate((float) Math.toRadians(transform.rotationDegrees()));
            }
            pose.scale(transform.scaleX(), transform.scaleY());
            pose.translate(-anchorX, -anchorY);
        }
        graphics.text(font, glyph, glyphX, textY, color, false);
        pose.popMatrix();
    }

    private void renderAnimatedPreview(GuiGraphicsExtractor graphics, Snapshot style, int previewTop, int viewportTop,
                                       int viewportBottom) {
        long now = System.nanoTime();
        long lifetime = millisToNanos(style.fadeOutTimeMillis());
        long spawnInterval = 520_000_000L;
        if (lastPreviewSpawnNanos == 0L || now - lastPreviewSpawnNanos >= spawnInterval) {
            spawnPreviewNumber(now, previewTop);
            lastPreviewSpawnNanos = now;
        }
        previewNumbers.removeIf(number -> lifetime == 0L || now - number.createdAtNanos() >= lifetime);

        int clipTop = Math.max(viewportTop, previewTop + 1);
        int clipBottom = Math.min(viewportBottom, previewTop + 59);
        if (clipBottom <= clipTop) {
            return;
        }
        graphics.enableScissor(contentX() + 1, clipTop,
                contentX() + customizationFormWidth() - 1, clipBottom);
        for (PreviewNumber number : previewNumbers) {
            float ageSeconds = (now - number.createdAtNanos()) / 1_000_000_000.0F;
            float remainingSeconds = (lifetime - (now - number.createdAtNanos())) / 1_000_000_000.0F;
            float alpha = AppearanceAnimator.fadeOutAlpha(remainingSeconds);
            if (alpha > 0.015F) {
                float configuredScale = Math.min(50.0F, 1.45F * style.scale() / 0.04F);
                if (style.scaleWithDamage()) {
                    configuredScale *= 1.0F + Math.min(0.75F,
                            (float) Math.log1p(number.damage()) * 0.18F);
                }
                drawAnimatedNumberPreview(graphics, style, number.text(), number.x(), number.y(),
                        configuredScale, ageSeconds, alpha);
            }
        }
        graphics.disableScissor();
    }

    private void spawnPreviewNumber(long now, int previewTop) {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        double angle = random.nextDouble(0.0D, Math.PI);
        Snapshot style = config.snapshot();
        double minimumRadius = style.minimumSpawnRadius() * 70.0D;
        double maximumRadius = Math.max(minimumRadius, style.maximumSpawnRadius() * 70.0D);
        double radius = maximumRadius <= minimumRadius ? minimumRadius
                : random.nextDouble(minimumRadius, maximumRadius);
        int centerX = contentX() + customizationFormWidth() / 2;
        int centerY = previewTop + 48;
        int damage = random.nextInt(1, 21);
        previewNumbers.add(new PreviewNumber(
                Integer.toString(damage), damage,
                Math.round(centerX + (float) Math.cos(angle) * (float) radius),
                Math.round(centerY - (float) Math.sin(angle) * (float) radius),
                now
        ));
        while (previewNumbers.size() > 6) {
            previewNumbers.remove(0);
        }
    }

    private static long millisToNanos(long millis) {
        return millis > Long.MAX_VALUE / 1_000_000L ? Long.MAX_VALUE : millis * 1_000_000L;
    }

    private static int withAlpha(int argb, float alpha) {
        int sourceAlpha = argb >>> 24;
        int resultAlpha = Math.max(4, Math.min(255, Math.round(sourceAlpha * alpha)));
        return resultAlpha << 24 | argb & 0x00FFFFFF;
    }

    private void drawPreviewText(GuiGraphicsExtractor graphics, Matrix3x2fStack pose, Component number, int x, int y,
                                 float offsetX, float offsetY, int color) {
        pose.pushMatrix();
        pose.translate(offsetX, offsetY);
        graphics.text(font, number, x, y, color, false);
        pose.popMatrix();
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

    private Component enabledText() {
        return Component.translatable(config.isEnabled()
                ? "damage_numbers.mod_settings.enabled" : "damage_numbers.mod_settings.disabled");
    }

    private Component showAllDamageText() {
        return Component.translatable(config.showAllDamageSources()
                ? "damage_numbers.common.enabled" : "damage_numbers.common.disabled");
    }

    private static void drawAngleDial(GuiGraphicsExtractor graphics, int x, int y, float angleDegrees) {
        int centerX = x + 7;
        int centerY = y + 7;
        for (int dy = -7; dy <= 7; dy++) {
            int halfWidth = (int) Math.floor(Math.sqrt(49 - dy * dy));
            graphics.fill(centerX - halfWidth, centerY + dy,
                    centerX + halfWidth + 1, centerY + dy + 1, MUTED);
        }
        for (int dy = -6; dy <= 6; dy++) {
            int halfWidth = (int) Math.floor(Math.sqrt(36 - dy * dy));
            graphics.fill(centerX - halfWidth, centerY + dy,
                    centerX + halfWidth + 1, centerY + dy + 1, BACKGROUND);
        }
        double direction = Math.toRadians(angleDegrees);
        for (int step = 0; step <= 5; step++) {
            int px = centerX + (int) Math.round(Math.cos(direction) * step);
            int py = centerY + (int) Math.round(Math.sin(direction) * step);
            graphics.fill(px, py, px + 1, py + 1, 0xFFF4F6F8);
        }
    }

    private void drawFontScrollbar(GuiGraphicsExtractor graphics, int x, int y, int width) {
        if (fontMaxScroll <= 0.0D) {
            return;
        }
        int thumbWidth = Math.max(18, (int) Math.round(width * width / (width + fontMaxScroll)));
        int thumbX = x + (int) Math.round((width - thumbWidth) * (fontScroll / fontMaxScroll));
        graphics.fill(x, y, x + width, y + 2, 0xFF292E37);
        graphics.fill(thumbX, y, thumbX + thumbWidth, y + 2, ACCENT);
    }

    private static Component fontName(FontChoice font) {
        return Component.translatable("damage_numbers.font." + font.name().toLowerCase(Locale.ROOT));
    }

    private Component fitCardLabel(Component label, int maxWidth) {
        String value = label.getString();
        if (font.width(value) <= maxWidth) {
            return Component.literal(value);
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

    private static Component animationName(AppearanceAnimation animation) {
        return Component.translatable("damage_numbers.animation." + animation.name().toLowerCase(Locale.ROOT));
    }

    private static Component animationScopeName(AppearanceAnimationScope scope) {
        return Component.translatable("damage_numbers.animation_scope." + scope.name().toLowerCase(Locale.ROOT));
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

    private static int blend(int background, int foreground, float amount) {
        float t = Math.max(0.0F, Math.min(1.0F, amount));
        int r = Math.round((background >> 16 & 0xFF) * (1.0F - t) + (foreground >> 16 & 0xFF) * t);
        int g = Math.round((background >> 8 & 0xFF) * (1.0F - t) + (foreground >> 8 & 0xFF) * t);
        int b = Math.round((background & 0xFF) * (1.0F - t) + (foreground & 0xFF) * t);
        return 0xFF000000 | r << 16 | g << 8 | b;
    }

    private static boolean inside(double mouseX, double mouseY, int x, int y, int width, int height) {
        return mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + height;
    }

    private static void drawBorder(GuiGraphicsExtractor graphics, int x, int y, int width, int height, int color,
                                   int thickness) {
        graphics.fill(x, y, x + width, y + thickness, color);
        graphics.fill(x, y + height - thickness, x + width, y + height, color);
        graphics.fill(x, y, x + thickness, y + height, color);
        graphics.fill(x + width - thickness, y, x + width, y + height, color);
    }

    private int sidebarWidth() {
        return Math.max(108, Math.min(158, width / 4));
    }

    private int contentX() {
        return sidebarWidth() + 16;
    }

    private int contentWidth() {
        return Math.max(200, width - contentX() - 8);
    }

    private int customizationFormWidth() {
        return contentWidth() - 12;
    }

    @Override
    public void onClose() {
        config.save();
        ScreenNavigator.open(minecraft, parent);
    }

    private enum Page {
        PRESETS("damage_numbers.tab.presets"),
        CUSTOMIZATION("damage_numbers.tab.customization"),
        MOD_SETTINGS("damage_numbers.tab.mod_settings");

        private final String translationKey;

        Page(String translationKey) {
            this.translationKey = translationKey;
        }
    }

    private record PresetCard(String id, Component name, Snapshot style, int x, int y, int width, int height,
                              boolean userOwned) {
    }

    private enum PresetActionType {
        EDIT,
        DELETE
    }

    private record PresetAction(PresetActionType type, int x, int y, int size, String presetId) {
    }

    private record FontCard(FontChoice font, String customFontId, Component name, boolean addCard,
                            int x, int y, int width, int height) {
    }

    private record ColorControl(ColorPickerScreen.Target target, int x, int y, int width, int height,
                                int buttonX, int buttonY, boolean active) {
    }

    private record PreviewNumber(String text, float damage, float x, float y, long createdAtNanos) {
    }

    private record RangeSegment(int index, int x, int y, int width, int height) {
    }

    private record RangeDeleteControl(int x, int y, int width, int height) {
    }
}
