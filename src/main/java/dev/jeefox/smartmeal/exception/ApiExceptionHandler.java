package dev.jeefox.smartmeal.exception;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.List;

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

    @ExceptionHandler(UserProfileNotFoundException.class)
    public ProblemDetail handleUserProfileNotFound(UserProfileNotFoundException exception){
        log.debug("{}",exception.getMessage());
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, exception.getLocalizedMessage());
        detail.setTitle("Профиль пользователя не найден");
        return detail;
    }

    @ExceptionHandler(MealPlanNotFoundException.class)
    public ProblemDetail handleMealPlanNotFound(MealPlanNotFoundException exception){
        log.debug("{}",exception.getMessage());
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, exception.getLocalizedMessage());
        detail.setTitle("Меню на эту дату не найдено");
        return detail;
    }

    @ExceptionHandler(DuplicateRecipeProductsException.class)
    public ProblemDetail handleDuplicateProducts(DuplicateRecipeProductsException exception){
        log.debug("{}",exception.getMessage());
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, exception.getLocalizedMessage());
        detail.setTitle("Продукты в рецепте дублируются");
        return detail;
    }

    @ExceptionHandler(EmailAlreadyExistsException.class)
    public ProblemDetail handleEmailAlreadyExists(EmailAlreadyExistsException exception){
        log.debug("{}", exception.getMessage());
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, exception.getLocalizedMessage());
        detail.setTitle("Пользователь с таким email уже зарегистрирован");
        return detail;
    }

    @ExceptionHandler(MealPlanAlreadyExistsException.class)
    public ProblemDetail handleMealPlanAlreadyExists(MealPlanAlreadyExistsException exception){
        log.debug("{}", exception.getMessage());
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, exception.getLocalizedMessage());
        detail.setTitle("Меню на эту дату уже есть");
        return detail;
    }
    @ExceptionHandler(ProfileIncompleteException.class)
    public ProblemDetail handleProfileIncomplete(
            ProfileIncompleteException exception) {

        ProblemDetail detail = ProblemDetail.forStatusAndDetail(
                HttpStatus.CONFLICT,
                exception.getMessage()
        );
        detail.setTitle("Анкета не заполнена");
        return detail;
    }
    @ExceptionHandler(MealPlanNotEditableException.class)
    public ProblemDetail handleMealPlanNotEditable(MealPlanNotEditableException exception) {
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, exception.getMessage());
        detail.setTitle("Меню недоступно для редактирования");
        return detail;
    }

    @ExceptionHandler(MealPlanEntryNotFoundException.class)
    public ProblemDetail handleMealPlanEntryNotFound(MealPlanEntryNotFoundException exception) {
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, exception.getMessage());
        detail.setTitle("Блюдо меню не найдено");
        return detail;
    }

    @ExceptionHandler(MenuEntryNotExistsException.class)
    public ProblemDetail handleMenuEntryNotExists(MenuEntryNotExistsException exception) {
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(
                HttpStatus.CONFLICT, exception.getMessage());
        detail.setTitle("Меню пустое");
        return detail;
    }

    @ExceptionHandler(DailyCalorieTargetNotSetException.class)
    public ProblemDetail handleDailyCalorieTargetNotSet(DailyCalorieTargetNotSetException exception) {
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(
                HttpStatus.CONFLICT, exception.getMessage());
        detail.setTitle("Суточная цель не задана");
        return detail;
    }

    @ExceptionHandler(InvalidShoppingListPeriodException.class)
    public ProblemDetail handleInvalidShoppingListPeriod(InvalidShoppingListPeriodException exception) {
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST, exception.getMessage());
        detail.setTitle("Некорректный период списка покупок");
        return detail;
    }

    @ExceptionHandler(InvalidStoreFilterException.class)
    public ProblemDetail handleInvalidStoreFilter(InvalidStoreFilterException exception) {
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST, exception.getMessage());
        detail.setTitle("Некорректные параметры поиска магазинов");
        return detail;
    }

    @ExceptionHandler(StoreNotFoundException.class)
    public ProblemDetail handleStoreNotFound(StoreNotFoundException exception) {
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(
                HttpStatus.NOT_FOUND, exception.getMessage());
        detail.setTitle("Магазин не найден");
        return detail;
    }

    @ExceptionHandler(StoreProductNotFoundException.class)
    public ProblemDetail handleStoreProductNotFound(StoreProductNotFoundException exception) {
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(
                HttpStatus.NOT_FOUND, exception.getMessage());
        detail.setTitle("Товар магазина не найден");
        return detail;
    }

    @ExceptionHandler(PriceObservationNotFoundException.class)
    public ProblemDetail handlePriceObservationNotFound(PriceObservationNotFoundException exception) {
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(
                HttpStatus.NOT_FOUND, exception.getMessage());
        detail.setTitle("Цена ещё не добавлена");
        return detail;
    }

    @ExceptionHandler(StoreInactiveException.class)
    public ProblemDetail handleStoreInactive(StoreInactiveException exception) {
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(
                HttpStatus.CONFLICT, exception.getMessage());
        detail.setTitle("Магазин неактивен");
        return detail;
    }
    @ExceptionHandler(InvalidShoppingComparisonException.class)
    public ProblemDetail handleInvalidShoppingComparison(InvalidShoppingComparisonException exception) {
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST, exception.getMessage());
        detail.setTitle("Некорректные параметры сравнения магазинов");
        return detail;
    }
    @ExceptionHandler(PriceObservationEntryNotFoundException.class)
    public ProblemDetail handlePriceObservationEntryNotFound(PriceObservationEntryNotFoundException exception) {
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, exception.getMessage());
        detail.setTitle("Запись цены не найдена");
        return detail;
    }
    @ExceptionHandler(MenuGenerationUnavailableException.class)
    public ProblemDetail handleMenuGenerationUnavailable(MenuGenerationUnavailableException exception) {
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, exception.getMessage());
        detail.setTitle("Не удалось подобрать меню");
        return detail;
    }
    @ExceptionHandler(InvalidMealPlanSelectionException.class)
    public ProblemDetail handleInvalidMealPlanSelection(InvalidMealPlanSelectionException exception) {
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, exception.getMessage());
        detail.setTitle("Некорректный выбор блюд");
        return detail;
    }

    @ExceptionHandler(StaleMealPlanPreviewException.class)
    public ProblemDetail handleStaleMealPlanPreview(StaleMealPlanPreviewException exception) {
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, exception.getMessage());
        detail.setTitle("Предпросмотр устарел");
        return detail;
    }
    @ExceptionHandler(InvalidPriceEntryException.class)
    public ProblemDetail handleInvalidPriceEntry(InvalidPriceEntryException exception) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, exception.getMessage());
    }

    @ExceptionHandler(PriceEntryConflictException.class)
    public ProblemDetail handlePriceEntryConflict(PriceEntryConflictException exception) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, exception.getMessage());
    }
}
