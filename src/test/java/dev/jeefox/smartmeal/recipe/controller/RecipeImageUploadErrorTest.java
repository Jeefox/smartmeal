package dev.jeefox.smartmeal.recipe.controller;

import dev.jeefox.smartmeal.exception.ApiExceptionHandler;
import dev.jeefox.smartmeal.recipe.service.RecipeImageService;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class RecipeImageUploadErrorTest {
    @Test
    void returnsProblemDetailWhenUploadLimitIsExceeded() throws Exception {
        RecipeImageService service = mock(RecipeImageService.class);
        when(service.uploadCover(any(UUID.class), any(), isNull()))
                .thenThrow(new MaxUploadSizeExceededException(10L * 1024 * 1024));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new RecipeImageController(service))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();

        mvc.perform(multipart("/api/recipes/{recipeId}/images", UUID.randomUUID())
                        .file(new MockMultipartFile("file", "cover.png", MediaType.IMAGE_PNG_VALUE, new byte[]{1})))
                .andExpect(status().is(413))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(413))
                .andExpect(jsonPath("$.title").value("Файл слишком большой"))
                .andExpect(jsonPath("$.detail").value(
                        "Превышен допустимый размер загрузки. Уменьшите размер файла и повторите попытку."));
    }
}
