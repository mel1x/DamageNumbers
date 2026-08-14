package dev.melix.damagenumbers.client.gui;

import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.narration.NarratableEntry;
import net.minecraft.client.gui.narration.NarrationElementOutput;

import java.util.function.DoubleConsumer;

final class PresetScrollHandler implements GuiEventListener, NarratableEntry {
    private final int x;
    private final int y;
    private final int width;
    private final int height;
    private final DoubleConsumer wheelCallback;
    private final DoubleConsumer positionCallback;
    private boolean focused;
    private boolean dragging;
    private double pendingPosition;

    PresetScrollHandler(int x, int y, int width, int height, DoubleConsumer wheelCallback,
                        DoubleConsumer positionCallback) {
        this.x = x;
        this.y = y;
        this.width = width;
        this.height = height;
        this.wheelCallback = wheelCallback;
        this.positionCallback = positionCallback;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        if (!isMouseOver(mouseX, mouseY)) {
            return false;
        }
        wheelCallback.accept(delta);
        return true;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button != 0 || !isOverTrack(mouseX, mouseY)) {
            return false;
        }
        dragging = true;
        focused = true;
        pendingPosition = position(mouseY);
        positionCallback.accept(pendingPosition);
        return true;
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (!dragging || button != 0) {
            return false;
        }
        pendingPosition = position(mouseY);
        positionCallback.accept(pendingPosition);
        return true;
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (!dragging || button != 0) {
            return false;
        }
        dragging = false;
        positionCallback.accept(pendingPosition);
        return true;
    }

    private boolean isOverTrack(double mouseX, double mouseY) {
        return mouseX >= x + width - 6 && mouseX < x + width && mouseY >= y && mouseY < y + height;
    }

    private double position(double mouseY) {
        return Math.max(0.0D, Math.min(1.0D, (mouseY - y) / Math.max(1.0D, height)));
    }

    @Override
    public boolean isMouseOver(double mouseX, double mouseY) {
        return isOverTrack(mouseX, mouseY);
    }

    @Override
    public void setFocused(boolean focused) {
        this.focused = focused;
    }

    @Override
    public boolean isFocused() {
        return focused;
    }

    @Override
    public NarrationPriority narrationPriority() {
        return NarrationPriority.NONE;
    }

    @Override
    public void updateNarration(NarrationElementOutput output) {
    }
}
