package com.example.webflaxcalc.configs;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.InputStream;

/**
 * Конфигурация Spring: грузим config.json из ресурсов при старте.
 * Если файла нет — бросаем исключение, чтобы разработчик увидел ошибку.
 */
@Configuration
public class AppConfig {

    /**
     * Загружает config.json из classpath (src/main/resources).
     * @param mapper Jackson ObjectMapper (spring-boot предоставляет его)
     * @return десериализованный ConfigJson
     * @throws Exception если файл не найден или невалиден
     */
    @Bean
    public ConfigJson configJson(ObjectMapper mapper) throws Exception {
        InputStream is = getClass().getClassLoader().getResourceAsStream("config.json");

        if (is == null) {
            throw new IllegalStateException("config.json не найден");
        }

        return mapper.readValue(is, ConfigJson.class);
    }
}
