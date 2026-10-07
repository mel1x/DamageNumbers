package dev.melix.damagenumbers.client.config;

import org.lwjgl.sdl.SDLDialog;
import org.lwjgl.sdl.SDL_DialogFileCallback;
import org.lwjgl.sdl.SDL_DialogFileFilter;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

import java.util.function.Consumer;

/** Minecraft 26.3 ships SDL instead of tinyfd. */
final class FontFilePicker {
    private FontFilePicker() {
    }

    static void pick(Consumer<String> onSelected) {
        SDL_DialogFileCallback[] callback = new SDL_DialogFileCallback[1];
        callback[0] = SDL_DialogFileCallback.create((userdata, fileList, filter) -> {
            try {
                // fileList is null on error and starts with a null entry when cancelled.
                if (fileList != 0L) {
                    long file = MemoryUtil.memGetAddress(fileList);
                    if (file != 0L) {
                        onSelected.accept(MemoryUtil.memUTF8(file));
                    }
                }
            } finally {
                callback[0].free();
            }
        });
        try (MemoryStack stack = MemoryStack.stackPush()) {
            SDL_DialogFileFilter.Buffer filters = SDL_DialogFileFilter.calloc(1, stack);
            filters.get(0).name(stack.UTF8("TTF / OTF fonts")).pattern(stack.UTF8("ttf;otf"));
            SDLDialog.SDL_ShowOpenFileDialog(callback[0], 0L, 0L, filters, (CharSequence) null, false);
        }
    }
}
