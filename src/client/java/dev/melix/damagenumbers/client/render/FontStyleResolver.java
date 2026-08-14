package dev.melix.damagenumbers.client.render;

import dev.melix.damagenumbers.client.config.DamageNumbersConfig.FontChoice;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

public final class FontStyleResolver {
    private static final FontFactory FACTORY = createFactory();

    private FontStyleResolver() {
    }

    public static Component component(String text, FontChoice font, String customFontId) {
        MutableComponent component = Component.literal(text);
        String resourceId = font.resourceId(customFontId);
        Style style = component.getStyle();
        if (resourceId != null) {
            try {
                style = FACTORY.apply(style, resourceId);
            } catch (ReflectiveOperationException exception) {
                throw new IllegalStateException("Cannot apply font " + resourceId, exception);
            }
        }
        component.setStyle(style);
        return component;
    }

    private static FontFactory createFactory() {
        try {
            Class<?> resourceLocation = Class.forName("net.minecraft.resources.ResourceLocation");
            Method parse = resourceLocation.getMethod("tryParse", String.class);
            Method withFont = Style.class.getMethod("withFont", resourceLocation);
            return (style, id) -> (Style) withFont.invoke(style, parse.invoke(null, id));
        } catch (ClassNotFoundException ignored) {
            // Try the new API below.
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Cannot initialize legacy font support", exception);
        }

        try {
            Class<?> identifier = Class.forName("net.minecraft.resources.Identifier");
            Method parse = identifier.getMethod("tryParse", String.class);
            Class<?> description = Class.forName("net.minecraft.network.chat.FontDescription");
            Class<?> resource = Class.forName("net.minecraft.network.chat.FontDescription$Resource");
            Constructor<?> constructor = resource.getConstructor(identifier);
            Method withFont = Style.class.getMethod("withFont", description);
            return (style, id) -> {
                Object fontId = constructor.newInstance(parse.invoke(null, id));
                return (Style) withFont.invoke(style, fontId);
            };
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Cannot initialize font support", exception);
        }
    }

    @FunctionalInterface
    private interface FontFactory {
        Style apply(Style style, String id) throws ReflectiveOperationException;
    }
}
