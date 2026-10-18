package dev.jeefox.smartmeal.nutrition;

import dev.jeefox.smartmeal.user.entity.Sex;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Objects;

@Component
public class RestingEnergyCalculator {

    public BigDecimal calculate(
            BigDecimal weightKg,
            Integer heightCm,
            Short age,
            Sex sex){

        Objects.requireNonNull(weightKg, "Вес не должен быть null");
        Objects.requireNonNull(heightCm, "Рост не должен быть null");
        Objects.requireNonNull(age, "Возраст не должен быть null");
        Objects.requireNonNull(sex, "Пол не может быть null");

        if (weightKg.signum() <= 0) {
            throw new IllegalArgumentException("Вес должен быть строго больше 0 кг.");
        }
        if (heightCm <= 0) {
            throw new IllegalArgumentException("Рост должен быть строго больше 0 см.");
        }
        if (age < 18) {
            throw new IllegalArgumentException("Возраст должен быть больше или равен 18 лет.");
        }

        BigDecimal height = BigDecimal.valueOf(heightCm);
        BigDecimal ageBD = BigDecimal.valueOf(age);

        // 10 × вес
        BigDecimal weightPart = BigDecimal.TEN.multiply(weightKg);

        // 6,25 × рост
        BigDecimal heightPart = new BigDecimal("6.25").multiply(height);

        // 5 × возраст
        // Используем константу "5", так как стандартного BigDecimal.FIVE в Java нет
        BigDecimal agePart = new BigDecimal("5").multiply(ageBD);

        // Поправка в зависимости от пола
        BigDecimal correction = (sex == Sex.MALE) ? new BigDecimal("5") : new BigDecimal("-161");

        // Итоговая формула: weightPart + heightPart - agePart + correction
        return weightPart
                .add(heightPart)
                .subtract(agePart)
                .add(correction);
    }
}
