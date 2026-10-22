package dev.jeefox.smartmeal.mealplan.generation.period;

import org.hibernate.type.descriptor.WrapperOptions;
import org.hibernate.type.descriptor.java.JavaType;
import org.hibernate.type.format.FormatMapper;
import org.springframework.boot.hibernate.autoconfigure.HibernatePropertiesCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import tools.jackson.databind.ObjectMapper;

/** Use Boot's configured Jackson mapper for JSONB too, including LocalDate and Instant. */
@Configuration
public class PlanningJsonConfiguration {
    @Bean
    HibernatePropertiesCustomizer planningJsonMapper(ObjectMapper mapper) {
        return properties ->
                properties.put(
                        "hibernate.type.json_format_mapper",
                        new FormatMapper() {
                            @Override
                            public <T> T fromString(
                                    CharSequence json, JavaType<T> type, WrapperOptions options) {
                                if (type.getJavaTypeClass() == String.class)
                                    return type.cast(json.toString());
                                return mapper.readValue(
                                        json.toString(),
                                        mapper.getTypeFactory().constructType(type.getJavaType()));
                            }

                            @Override
                            public <T> String toString(
                                    T value, JavaType<T> type, WrapperOptions options) {
                                return value instanceof String text
                                        ? text
                                        : mapper.writeValueAsString(value);
                            }
                        });
    }
}
