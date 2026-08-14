package dev.melix.damagenumbers.client.render;

import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.melix.damagenumbers.client.config.DamageNumbersConfig.ColorPaint;
import net.minecraft.client.renderer.MultiBufferSource;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

final class GradientMultiBufferSource {
    private GradientMultiBufferSource() {
    }

    static MultiBufferSource wrap(
            MultiBufferSource delegate,
            ColorPaint paint,
            float angleDegrees,
            float left,
            float top,
            float right,
            float bottom,
            float alpha
    ) {
        return renderType -> {
            // Atlas reloads can swap the buffer.
            VertexConsumer original = delegate.getBuffer(renderType);
            return createProxy(original, paint, angleDegrees, left, top, right, bottom, alpha);
        };
    }

    static VertexConsumer wrap(
            VertexConsumer delegate,
            ColorPaint paint,
            float angleDegrees,
            float left,
            float top,
            float right,
            float bottom,
            float alpha
    ) {
        return createProxy(delegate, paint, angleDegrees, left, top, right, bottom, alpha);
    }

    private static VertexConsumer createProxy(
            VertexConsumer delegate,
            ColorPaint paint,
            float angleDegrees,
            float left,
            float top,
            float right,
            float bottom,
            float alpha
    ) {
        InvocationHandler handler = new GradientVertexHandler(
                delegate, paint, angleDegrees, left, top, right, bottom, alpha
        );
        return (VertexConsumer) Proxy.newProxyInstance(
                VertexConsumer.class.getClassLoader(),
                new Class<?>[]{VertexConsumer.class},
                handler
        );
    }

    private static final class GradientVertexHandler implements InvocationHandler {
        private final VertexConsumer delegate;
        private final ColorPaint paint;
        private final float left;
        private final float top;
        private final float width;
        private final float height;
        private final float directionX;
        private final float directionY;
        private final float projectionMin;
        private final float projectionRange;
        private final float alpha;
        private float vertexX;
        private float vertexY;

        private GradientVertexHandler(
                VertexConsumer delegate,
                ColorPaint paint,
                float angleDegrees,
                float left,
                float top,
                float right,
                float bottom,
                float alpha
        ) {
            this.delegate = delegate;
            this.paint = paint;
            this.left = left;
            this.top = top;
            this.width = Math.max(0.001F, right - left);
            this.height = Math.max(0.001F, bottom - top);
            double radians = Math.toRadians(angleDegrees);
            this.directionX = (float) Math.cos(radians);
            this.directionY = (float) Math.sin(radians);
            this.projectionMin = Math.min(0.0F, directionX) + Math.min(0.0F, directionY);
            float projectionMax = Math.max(0.0F, directionX) + Math.max(0.0F, directionY);
            this.projectionRange = Math.max(0.001F, projectionMax - projectionMin);
            this.alpha = alpha;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] arguments) throws Throwable {
            String name = method.getName();
            Object[] args = arguments == null ? new Object[0] : arguments;

            if ((name.equals("addVertex") || name.equals("vertex") || name.equals("addVertexWith2DPose"))) {
                capturePosition(args);
                replacePackedVertexColor(name, args);
            } else if (name.equals("setColor") || name.equals("color")) {
                replaceColor(method, args);
            }

            Object result;
            try {
                result = method.invoke(delegate, args);
            } catch (InvocationTargetException exception) {
                throw exception.getCause();
            }

            if (VertexConsumer.class.isAssignableFrom(method.getReturnType())) {
                return proxy;
            }
            return result;
        }

        private void capturePosition(Object[] args) {
            if (args.length >= 3 && args[0] instanceof Number && args[1] instanceof Number) {
                vertexX = ((Number) args[0]).floatValue();
                vertexY = ((Number) args[1]).floatValue();
            } else if (args.length >= 3 && args[1] instanceof Number && args[2] instanceof Number) {
                vertexX = ((Number) args[1]).floatValue();
                vertexY = ((Number) args[2]).floatValue();
            }
        }

        private void replaceColor(Method method, Object[] args) {
            int argb = gradientColor();

            if (args.length == 1) {
                args[0] = argb;
            } else if (args.length >= 4) {
                if (method.getParameterTypes()[0] == float.class) {
                    args[0] = (argb >> 16 & 0xFF) / 255.0F;
                    args[1] = (argb >> 8 & 0xFF) / 255.0F;
                    args[2] = (argb & 0xFF) / 255.0F;
                    args[3] = (argb >>> 24) / 255.0F;
                } else {
                    args[0] = argb >> 16 & 0xFF;
                    args[1] = argb >> 8 & 0xFF;
                    args[2] = argb & 0xFF;
                    args[3] = argb >>> 24;
                }
            }
        }

        private void replacePackedVertexColor(String methodName, Object[] args) {
            if (methodName.equals("addVertex") && args.length >= 11 && args[3] instanceof Number) {
                args[3] = gradientColor();
            }
        }

        private int gradientColor() {
            float normalizedX = (vertexX - left) / width;
            float normalizedY = (vertexY - top) / height;
            float projection = normalizedX * directionX + normalizedY * directionY;
            float progress = (projection - projectionMin) / projectionRange;
            return multiplyAlpha(paint.colorAt(progress), alpha);
        }

        private static int multiplyAlpha(int argb, float alpha) {
            int resultAlpha = Math.max(0, Math.min(255, Math.round((argb >>> 24) * alpha)));
            return resultAlpha << 24 | argb & 0x00FFFFFF;
        }
    }
}
