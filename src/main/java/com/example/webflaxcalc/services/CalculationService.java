package com.example.webflaxcalc.services;

import reactor.core.publisher.Flux;

public interface CalculationService {
    Flux<String> streamCsv(int count, boolean ordered);
}
