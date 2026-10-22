package dev.jeefox.smartmeal.recipe.service;

import dev.jeefox.smartmeal.exception.InvalidRecipeImageException;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

@Component
public class RecipeImageProcessor {
    static final long MAX_UPLOAD_BYTES = 10L * 1024 * 1024;
    static final long MAX_PIXELS = 24_000_000L;
    static final int MAX_SIDE = 1600;

    public ProcessedImage process(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new InvalidRecipeImageException("Выберите непустой файл изображения");
        }
        if (file.getSize() > MAX_UPLOAD_BYTES) {
            throw new InvalidRecipeImageException("Размер изображения не должен превышать 10 МБ");
        }

        try {
            byte[] source = file.getBytes();
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(source));
            if (image == null) {
                throw new InvalidRecipeImageException("Поддерживаются изображения JPEG и PNG");
            }
            if ((long) image.getWidth() * image.getHeight() > MAX_PIXELS) {
                throw new InvalidRecipeImageException("Разрешение изображения слишком большое");
            }

            double scale = Math.min(1.0, (double) MAX_SIDE / Math.max(image.getWidth(), image.getHeight()));
            int width = Math.max(1, (int) Math.round(image.getWidth() * scale));
            int height = Math.max(1, (int) Math.round(image.getHeight() * scale));
            BufferedImage normalized = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
            Graphics2D graphics = normalized.createGraphics();
            try {
                graphics.setColor(Color.WHITE);
                graphics.fillRect(0, 0, width, height);
                graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                        RenderingHints.VALUE_INTERPOLATION_BICUBIC);
                graphics.setRenderingHint(RenderingHints.KEY_RENDERING,
                        RenderingHints.VALUE_RENDER_QUALITY);
                graphics.drawImage(image, 0, 0, width, height, null);
            } finally {
                graphics.dispose();
            }

            ByteArrayOutputStream output = new ByteArrayOutputStream();
            if (!ImageIO.write(normalized, "jpeg", output)) {
                throw new InvalidRecipeImageException("Не удалось преобразовать изображение в JPEG");
            }
            return new ProcessedImage(output.toByteArray(), "image/jpeg", width, height);
        } catch (InvalidRecipeImageException exception) {
            throw exception;
        } catch (IOException exception) {
            throw new InvalidRecipeImageException("Не удалось прочитать изображение");
        }
    }

    public record ProcessedImage(byte[] content, String contentType, int width, int height) {
    }
}
