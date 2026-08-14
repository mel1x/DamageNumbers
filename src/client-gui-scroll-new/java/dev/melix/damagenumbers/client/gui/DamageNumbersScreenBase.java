package dev.melix.damagenumbers.client.gui;

import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

/**
 * Routes raw mouse input to the screen before the widget list sees it. The configuration screen
 * paints and hit-tests its own controls, so it needs the pointer events in a version independent
 * shape; every Minecraft flavour supplies its own subclass with the matching signatures.
 */
abstract class DamageNumbersScreenBase extends Screen {
    protected DamageNumbersScreenBase(Component title) {
        super(title);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontal, double vertical) {
        return handleMouseScroll(mouseX, mouseY, horizontal, vertical)
                || super.mouseScrolled(mouseX, mouseY, horizontal, vertical);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        return handleMouseClick(event.x(), event.y(), event.button())
                || super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
        return handleMouseDrag(event.x(), event.y(), event.button())
                || super.mouseDragged(event, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        return handleMouseRelease(event.x(), event.y(), event.button()) || super.mouseReleased(event);
    }

    /** Drops keyboard focus so a click on a painted control closes any open text field. */
    protected final void clearScreenFocus() {
        setFocused((GuiEventListener) null);
    }

    protected abstract boolean handleMouseScroll(double mouseX, double mouseY, double horizontal, double vertical);

    protected abstract boolean handleMouseClick(double mouseX, double mouseY, int button);

    protected abstract boolean handleMouseDrag(double mouseX, double mouseY, int button);

    protected abstract boolean handleMouseRelease(double mouseX, double mouseY, int button);
}
