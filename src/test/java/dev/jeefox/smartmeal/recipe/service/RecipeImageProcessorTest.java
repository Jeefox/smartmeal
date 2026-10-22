package dev.jeefox.smartmeal.recipe.service;

import dev.jeefox.smartmeal.exception.InvalidRecipeImageException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RecipeImageProcessorTest {
    private final RecipeImageProcessor processor = new RecipeImageProcessor();

    @Test
    void normalizesPngAndLimitsLongestSide() throws Exception {
        BufferedImage source = new BufferedImage(3200, 1200, BufferedImage.TYPE_INT_ARGB);
        source.setRGB(0, 0, Color.RED.getRGB());
        ByteArrayOutputStream input = new ByteArrayOutputStream();
        ImageIO.write(source, "png", input);

        var result = processor.process(new MockMultipartFile(
                "file", "cover.png", "image/png", input.toByteArray()));

        assertEquals("image/jpeg", result.contentType());
        assertEquals(1600, result.width());
        assertEquals(600, result.height());
        assertTrue(result.content().length > 0);
        BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(result.content()));
        assertNotNull(decoded);
        assertEquals(BufferedImage.TYPE_3BYTE_BGR, decoded.getType());
    }

    @Test
    void rejectsEmptyAndNonImageFiles() {
        assertThrows(InvalidRecipeImageException.class,
                () -> processor.process(new MockMultipartFile("file", new byte[0])));
        assertThrows(InvalidRecipeImageException.class,
                () -> processor.process(new MockMultipartFile("file", "note.txt", "text/plain", "text".getBytes())));
    }

    @Test
    void rejectsFilesOverTenMegabytesBeforeDecoding() {
        byte[] oversized = new byte[(int) RecipeImageProcessor.MAX_UPLOAD_BYTES + 1];
        assertThrows(InvalidRecipeImageException.class,
                () -> processor.process(new MockMultipartFile("file", "huge.png", "image/png", oversized)));
    }
}
