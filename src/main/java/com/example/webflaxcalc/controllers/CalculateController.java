package com.example.webflaxcalc.controllers;


import com.example.webflaxcalc.services.CalculationService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

/**
 * REST-контроллер, предоставляет endpoint /api/calculate
 *
 * @Produces text/event-stream — SSE-like streaming of lines. Клиент может читать построчно.
 */
@RestController
@RequestMapping("/api")
public class CalculateController {

    private final CalculationService service;

    public CalculateController(CalculationService service) {
        this.service = service;
    }

    /**
     * GET /api/calculate?count=50&ordered=true
     *
     * @param count количество итераций (default 10)
     * @param ordered true -> ordered output, false -> unordered
     * @return Flux<String> — поток CSV-строк
     */
    @GetMapping(value = "/calculate", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> calculate(
            @RequestParam(name = "count", defaultValue = "10") int count,
            @RequestParam(name = "ordered", defaultValue = "true") boolean ordered) {

        return service.streamCsv(count, ordered);
    }
}
