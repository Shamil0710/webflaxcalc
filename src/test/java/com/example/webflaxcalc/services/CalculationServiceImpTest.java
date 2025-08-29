package com.example.webflaxcalc.services;

import com.example.webflaxcalc.configs.ConfigJson;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;


/**
 * Unit тесты для сервиса CalculationServiceImp
 */
class CalculationServiceImpTest {

    private ConfigJson config;

    @BeforeEach
    void setUp() {
        config = new ConfigJson();
        config.setInterval(1);
        config.setFunction1("x + 1");
        config.setFunction2("x * 2");
    }

    @Test
    void testOrderedModeSuccess() {
        CalculationService service = new CalculationServiceImp(config);

        StepVerifier.create(service.streamCsv(2, true))
                .expectNextMatches(s -> s.matches("^1,\\d+\\.\\d+,-?\\d+,\\d+,\\d+\\.\\d+,-?\\d+,\\d+$"))
                .expectNextMatches(s -> s.matches("^2,\\d+\\.\\d+,-?\\d+,\\d+,\\d+\\.\\d+,-?\\d+,\\d+$"))
                .verifyComplete();
    }

    @Test
    void testUnorderedModeSuccess() {
        CalculationService service = new CalculationServiceImp(config);

        StepVerifier.create(service.streamCsv(1, false))
                .expectNextMatches(s -> s.matches("^1,1,\\d+\\.\\d+,-?\\d+$") || s.matches("^1,2,\\d+\\.\\d+,-?\\d+$"))
                .expectNextMatches(s -> s.matches("^1,1,\\d+\\.\\d+,-?\\d+$") || s.matches("^1,2,\\d+\\.\\d+,-?\\d+$"))
                .verifyComplete();
    }

    @Test
    void testErrorSanitization() {
        ConfigJson badConfig = new ConfigJson();
        badConfig.setInterval(1);
        badConfig.setFunction1("throw new Error('bad\\nmessage')"); // JS ошибка с переносом
        badConfig.setFunction2("x * 2");

        CalculationService service = new CalculationServiceImp(badConfig);

        StepVerifier.create(service.streamCsv(1, true))
                .expectNextMatches(s -> s.contains("error:") && !s.contains("\n"))
                .verifyComplete();
    }

}