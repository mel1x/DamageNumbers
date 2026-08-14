package dev.melix.damagenumbers.client.gui;

import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.narration.NarratableEntry;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.MouseButtonEvent;

import java.util.function.DoubleConsumer;

final class FontScrollHandler implements GuiEventListener, NarratableEntry {
    private final int x;
    private final int y;
    private final int width;
    private final int height;
    private final DoubleConsumer wheelCallback;
    private final DoubleConsumer positionCallback;
    private boolean focused;
    private boolean dragging;
    private double pendingPosition;

    FontScrollHandler(int x, int y, int width, int height, DoubleConsumer wheelCallback,
                      DoubleConsumer positionCallback) {
        this.x = x;
        this.y = y;
        this.width = width;
        this.height = height;
        this.wheelCallback = wheelCallback;
        this.positionCallback = positionCallback;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontal, double vertical) {
        if (!isMouseOver(mouseX, mouseY)) {
            return false;
        }
        wheelCallback.accept(Math.abs(horizontal) > 0.001D ? horizontal : vertical);
        return true;
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (event.button() != 0 || !isOverTrack(event.x(), event.y())) {
            return false;
        }
        dragging = true;
        focused = true;
        pendingPosition = position(event.x());
        positionCallback.accept(pendingPosition);
        return true;
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
        if (!dragging || event.button() != 0) {
            return false;
        }
        pendingPosition = position(event.x());
        positionCallback.accept(pendingPosition);
        return true;
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        if (!dragging || event.button() != 0) {
            return false;
        }
        dragging = false;
        positionCallback.accept(pendingPosition);
        return true;
    }

    private boolean isOverTrack(double mouseX, double mouseY) {
        return mouseX >= x && mouseX < x + width && mouseY >= y + height - 6 && mouseY < y + height;
    }

    private double position(double mouseX) {
        return Math.max(0.0D, Math.min(1.0D, (mouseX - x) / Math.max(1.0D, width)));
    }

    @Override
    public boolean isMouseOver(double mouseX, double mouseY) {
        return isOverTrack(mouseX, mouseY);
    }

    @Override public void setFocused(boolean focused) { this.focused = focused; }
    @Override public boolean isFocused() { return focused; }
    @Override public NarrationPriority narrationPriority() { return NarrationPriority.NONE; }
    @Override public void updateNarration(NarrationElementOutput output) { }
}
