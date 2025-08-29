package com.example.webflaxcalc.services;

import reactor.core.publisher.Flux;

/**
 * Интерфейс для более четкой структуры
 */
public interface CalculationService {
    Flux<String> streamCsv(int count, boolean ordered);
}
