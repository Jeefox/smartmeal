package dev.jeefox.smartmeal.recipe.controller;

import com.jayway.jsonpath.JsonPath;
import dev.jeefox.smartmeal.recipe.entity.Recipe;
import dev.jeefox.smartmeal.recipe.repository.RecipeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@Transactional
class RecipeImageIntegrationTest {
    @Autowired private WebApplicationContext context;
    @Autowired private RecipeRepository recipeRepository;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @Test
    void uploadsStoresExposesAndDownloadsCover() throws Exception {
        Recipe recipe = recipeRepository.saveAndFlush(new Recipe(
                "Рецепт с фото", "Описание", "Приготовить", 10, 1));
        MockMultipartFile file = new MockMultipartFile(
                "file", "cover.png", MediaType.IMAGE_PNG_VALUE, png(80, 40));

        String body = mvc.perform(multipart("/api/recipes/{recipeId}/images", recipe.getId())
                        .file(file).param("altText", "Готовое блюдо").with(user("test")).with(csrf()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.altText").value("Готовое блюдо"))
                .andExpect(jsonPath("$.width").value(80))
                .andExpect(jsonPath("$.height").value(40))
                .andReturn().getResponse().getContentAsString();
        String imageUrl = JsonPath.read(body, "$.url");

        mvc.perform(get(imageUrl).with(user("test")))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.IMAGE_JPEG));
        mvc.perform(get("/api/recipes/{id}", recipe.getId()).with(user("test")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.coverImageUrl").value(imageUrl));
    }

    @Test
    void replacingCoverChangesUrlAndServesNewImage() throws Exception {
        Recipe recipe = recipeRepository.saveAndFlush(new Recipe(
                "Обновление обложки", null, "Приготовить", 10, 1));
        String firstBody = mvc.perform(multipart("/api/recipes/{recipeId}/images", recipe.getId())
                        .file(new MockMultipartFile("file", "first.png", MediaType.IMAGE_PNG_VALUE, png(80, 40)))
                        .with(user("test")).with(csrf()))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String firstUrl = JsonPath.read(firstBody, "$.url");
        String secondBody = mvc.perform(multipart("/api/recipes/{recipeId}/images", recipe.getId())
                        .file(new MockMultipartFile("file", "second.png", MediaType.IMAGE_PNG_VALUE, png(120, 60)))
                        .with(user("test")).with(csrf()))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String secondUrl = JsonPath.read(secondBody, "$.url");

        assertEquals((String) JsonPath.read(firstBody, "$.id"), (String) JsonPath.read(secondBody, "$.id"));
        assertNotEquals(firstUrl, secondUrl);
        byte[] downloaded = mvc.perform(get(secondUrl).with(user("test")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();
        BufferedImage image = ImageIO.read(new java.io.ByteArrayInputStream(downloaded));
        assertEquals(120, image.getWidth());
        assertEquals(60, image.getHeight());
        for (int i = 0; i < 2; i++) {
            mvc.perform(get("/api/recipes/{id}", recipe.getId()).with(user("test")))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.coverImageUrl").value(secondUrl));
        }
    }

    @Test
    void rejectsNonImageAndRequiresCsrf() throws Exception {
        Recipe recipe = recipeRepository.saveAndFlush(new Recipe(
                "Рецепт без фото", null, "Приготовить", 10, 1));
        MockMultipartFile text = new MockMultipartFile(
                "file", "note.txt", MediaType.TEXT_PLAIN_VALUE, "not an image".getBytes());

        mvc.perform(multipart("/api/recipes/{recipeId}/images", recipe.getId())
                        .file(text).with(user("test")).with(csrf()))
                .andExpect(status().isBadRequest());
        mvc.perform(multipart("/api/recipes/{recipeId}/images", recipe.getId())
                        .file(text).with(user("test")))
                .andExpect(status().isForbidden());
    }

    private static byte[] png(int width, int height) throws Exception {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        image.setRGB(0, 0, Color.ORANGE.getRGB());
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(image, "png", output);
        return output.toByteArray();
    }
}
