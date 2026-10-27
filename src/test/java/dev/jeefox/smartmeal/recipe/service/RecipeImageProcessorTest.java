package dev.jeefox.smartmeal.recipe.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.jeefox.smartmeal.exception.InvalidRecipeImageException;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

import javax.imageio.ImageIO;

class RecipeImageProcessorTest {
    private final RecipeImageProcessor processor = new RecipeImageProcessor();

    @Test
    void normalizesPngAndLimitsLongestSide() throws Exception {
        BufferedImage source = new BufferedImage(3200, 1200, BufferedImage.TYPE_INT_ARGB);
        source.setRGB(0, 0, Color.RED.getRGB());
        ByteArrayOutputStream input = new ByteArrayOutputStream();
        ImageIO.write(source, "png", input);

        var result =
                processor.process(
                        new MockMultipartFile(
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
        assertThrows(
                InvalidRecipeImageException.class,
                () -> processor.process(new MockMultipartFile("file", new byte[0])));
        assertThrows(
                InvalidRecipeImageException.class,
                () ->
                        processor.process(
                                new MockMultipartFile(
                                        "file", "note.txt", "text/plain", "text".getBytes())));
    }

    @Test
    void rejectsFilesOverTenMegabytesBeforeDecoding() {
        byte[] oversized = new byte[(int) RecipeImageProcessor.MAX_UPLOAD_BYTES + 1];
        assertThrows(
                InvalidRecipeImageException.class,
                () ->
                        processor.process(
                                new MockMultipartFile("file", "huge.png", "image/png", oversized)));
    }

    @org.junit.jupiter.api.Test
    void rejectsCompressedLargeImageBeforeDecodingRaster() throws Exception {
        var bytes = new java.io.ByteArrayOutputStream();
        var out = new java.io.DataOutputStream(bytes);
        out.writeLong(0x89504e470d0a1a0aL);
        var headerBytes = new java.io.ByteArrayOutputStream();
        var header = new java.io.DataOutputStream(headerBytes);
        header.writeInt(6000);
        header.writeInt(5000);
        header.write(new byte[] {8, 2, 0, 0, 0});
        pngChunk(out, "IHDR", headerBytes.toByteArray());
        var compressed = new java.io.ByteArrayOutputStream();
        try (var deflater = new java.util.zip.DeflaterOutputStream(compressed)) {
            byte[] row = new byte[18001];
            for (int i = 0; i < 5000; i++) deflater.write(row);
        }
        pngChunk(out, "IDAT", compressed.toByteArray());
        pngChunk(out, "IEND", new byte[0]);
        var file =
                new org.springframework.mock.web.MockMultipartFile(
                        "file", "large.png", "image/png", bytes.toByteArray());
        var exception =
                org.junit.jupiter.api.Assertions.assertThrows(
                        dev.jeefox.smartmeal.exception.InvalidRecipeImageException.class,
                        () -> new RecipeImageProcessor().process(file));
        org.junit.jupiter.api.Assertions.assertEquals(
                "Разрешение изображения слишком большое", exception.getMessage());
    }

    private static void pngChunk(java.io.DataOutputStream out, String name, byte[] data)
            throws Exception {
        byte[] kind = name.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        var crc = new java.util.zip.CRC32();
        crc.update(kind);
        crc.update(data);
        out.writeInt(data.length);
        out.write(kind);
        out.write(data);
        out.writeInt((int) crc.getValue());
    }
}
