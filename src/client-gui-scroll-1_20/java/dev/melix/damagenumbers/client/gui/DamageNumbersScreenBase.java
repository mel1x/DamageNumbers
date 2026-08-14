package dev.melix.damagenumbers.client.gui;

import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
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
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        return handleMouseScroll(mouseX, mouseY, 0.0D, delta) || super.mouseScrolled(mouseX, mouseY, delta);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        return handleMouseClick(mouseX, mouseY, button) || super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        return handleMouseDrag(mouseX, mouseY, button)
                || super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        return handleMouseRelease(mouseX, mouseY, button) || super.mouseReleased(mouseX, mouseY, button);
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
