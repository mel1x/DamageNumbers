package dev.melix.damagenumbers.client.config;

import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.tinyfd.TinyFileDialogs;

import java.util.function.Consumer;

final class FontFilePicker {
    private FontFilePicker() {
    }

    static void pick(Consumer<String> onSelected) {
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
            if (selected != null) {
                onSelected.accept(selected);
            }
        }, "DamageNumbers font picker");
        picker.setDaemon(true);
        picker.start();
    }
}
