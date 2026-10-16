package dev.jeefox.smartmeal.exception;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.support.DefaultMessageSourceResolvable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@RestControllerAdvice
public class ApiExceptionHandler {
    @ExceptionHandler(ProductCategoryNotFoundException.class)
    public ProblemDetail handleCategoryNotFound(ProductCategoryNotFoundException ex) {
        log.debug("{}", ex.getMessage());
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getLocalizedMessage());
        detail.setTitle("Категория не найдена");
        return detail;
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail handleArgumentNotValid(MethodArgumentNotValidException exception){
        log.debug("{}", exception.getMessage());
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Проверьте поля запроса");
        detail.setTitle("Ошибка валидации");
        List<ValidationError> fieldErrorList = exception.getBindingResult()
                .getFieldErrors()
                .stream()
                .map(fieldError -> new ValidationError(fieldError.getField(), fieldError.getDefaultMessage())
                ).toList();
        detail.setProperty("errors", fieldErrorList);
        return detail;
    }

    @ExceptionHandler(ProductNotFoundException.class)
    public ProblemDetail handleProductNotFound(ProductNotFoundException exception){
        log.debug("{}",exception.getMessage());
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, exception.getLocalizedMessage());
        detail.setTitle("Продукт не найден");
        return detail;
    }

    @ExceptionHandler(RecipeNotFoundException.class)
    public ProblemDetail handleRecipeNotFound(RecipeNotFoundException exception){
        log.debug("{}",exception.getMessage());
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, exception.getLocalizedMessage());
        detail.setTitle("Рецепт не найден");
        return detail;
    }

    @ExceptionHandler(DuplicateRecipeProductsException.class)
    public ProblemDetail handleDuplicateProducts(DuplicateRecipeProductsException exception){
        log.debug("{}",exception.getMessage());
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, exception.getLocalizedMessage());
        detail.setTitle("Продукты в рецепте дублируются");
        return detail;
    }
}