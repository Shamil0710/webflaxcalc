package com.example.webflaxcalc.services;


import com.example.webflaxcalc.configs.ConfigJson;
import com.example.webflaxcalc.executions.FunctionExecutor;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * CalculationService — формирует Flux<String> с CSV-строками.
 *
 * Поведение:
 *  - ordered = true:
 *      Для каждой итерации i запускаются обе функции параллельно.
 *      Выводится строка только когда обе функции завершены.
 *      Формат (успех): <i>,<res1>,<time1>,<buf1>,<res2>,<time2>,<buf2>
 *      Формат (ошибка): <i>,<fnNumber>,error: <msg>
 *
 *  - ordered = false:
 *      Каждая завершившаяся функция отправляется сразу:
 *      Формат (успех): <i>,<fnNumber>,<result>,<time>
 *      Формат (ошибка): <i>,<fnNumber>,error: <msg>
 *
 * Реализация:
 *  - Для тайминга запуска итераций используется delayElement(interval).
 *  - Блокирующие вызовы (ScriptEngine, ProcessBuilder) выполняются на boundedElastic scheduler.
 */
@Service
public class CalculationService {

    private final ConfigJson config;
    private final FunctionExecutor executor;

    /**
     * Счётчики количества "начатых, но еще не выведенных" результатов
     * для функций 1 и 2 — используются для вычисления 'buffer' value.
     */
    private final AtomicInteger unpaired1 = new AtomicInteger(0);
    private final AtomicInteger unpaired2 = new AtomicInteger(0);

    public CalculationService(ConfigJson config) {
        this.config = config;
        this.executor = new FunctionExecutor();
    }

    /**
     * Возвращает Flux строк (CSV) согласно параметрам.
     *
     * @param count количество итераций
     * @param ordered режим упорядоченного вывода
     * @return Flux<String> — поток CSV-строк
     */
    public Flux<String> streamCsv(int count, boolean ordered) {
        int intervalMs = Math.max(1, config.getInterval());

        if (ordered) {
            // Конкатенируем итерации последовательно, каждая итерация стартует через interval
            return Flux.range(1, count)
                    .concatMap(i -> Mono.just(i)
                            .delayElement(Duration.ofMillis(intervalMs))
                            .flatMap(this::processIterationOrdered)
                    );
        } else {
            // В unordered режиме итерации также стартуют через interval, но результаты выводятся по мере готовности
            return Flux.range(1, count)
                    .concatMap(i -> Mono.just(i)
                            .delayElement(Duration.ofMillis(intervalMs))
                            .flatMapMany(this::processIterationUnordered)
                    );
        }
    }

    /**
     * Обработка одной итерации в ordered-режиме:
     * запустить обе функции параллельно и дождаться обеих, затем вернуть одну CSV-строку.
     */
    private Mono<String> processIterationOrdered(int iteration) {
        // Запуск функции 1
        Mono<FunctionResult> m1 = Mono.fromCallable(() -> {
                    // пометим, что результат функции 1 "в пути"
                    unpaired1.incrementAndGet();
                    FunctionExecutor.ExecutionResult r = detectAndRun(config.getFunction1(), iteration);
                    if (r.ok) {
                        return new FunctionResult(iteration, 1, r.value, r.timeMs, null);
                    } else {
                        return new FunctionResult(iteration, 1, Double.NaN, -1, r.error);
                    }
                })
                .subscribeOn(Schedulers.boundedElastic());

        // Запуск функции 2
        Mono<FunctionResult> m2 = Mono.fromCallable(() -> {
                    unpaired2.incrementAndGet();
                    FunctionExecutor.ExecutionResult r = detectAndRun(config.getFunction2(), iteration);
                    if (r.ok) {
                        return new FunctionResult(iteration, 2, r.value, r.timeMs, null);
                    } else {
                        return new FunctionResult(iteration, 2, Double.NaN, -1, r.error);
                    }
                })
                .subscribeOn(Schedulers.boundedElastic());

        // zip — дождёмся обеих
        return Mono.zip(m1, m2)
                .map(tuple -> {
                    FunctionResult r1 = tuple.getT1();
                    FunctionResult r2 = tuple.getT2();

                    // читаем значения буферов до уменьшения (сколько было "в пути" перед выводом).
                    int beforeBuf1 = unpaired1.get();
                    int beforeBuf2 = unpaired2.get();

                    // уменьшаем счётчики — эти элементы сейчас выводятся
                    unpaired1.decrementAndGet();
                    unpaired2.decrementAndGet();

                    // Если одна из функций вернула ошибку — по заданию отдадим строку ошибки
                    if (r1.error != null) {
                        // Формат ошибки (как в задании): "<iter>,<fnNumber>,error: <msg>"
                        return String.format("%d,%d,error: %s", iteration, r1.functionNo, sanitize(r1.error));
                    } else if (r2.error != null) {
                        return String.format("%d,%d,error: %s", iteration, r2.functionNo, sanitize(r2.error));
                    } else {
                        // Обычная успешная строка (7 полей)
                        return String.format(Locale.ROOT, "%d,%.6f,%d,%d,%.6f,%d,%d",
                                iteration,
                                r1.value, r1.timeMs, Math.max(0, beforeBuf1 - 1),
                                r2.value, r2.timeMs, Math.max(0, beforeBuf2 - 1)
                        );
                    }
                });
    }

    /**
     * Обработка одной итерации в unordered-режиме.
     * Запускаем две параллельные задачи, каждая возвращает свою строку,
     * и возвращаем Flux из результатов (они будут приходить по мере готовности).
     */
    private Flux<String> processIterationUnordered(int iteration) {
        Mono<String> a = Mono.fromCallable(() -> {
                    unpaired1.incrementAndGet();
                    FunctionExecutor.ExecutionResult r = detectAndRun(config.getFunction1(), iteration);
                    if (r.ok) {
                        return String.format(Locale.ROOT, "%d,1,%.6f,%d", iteration, r.value, r.timeMs);
                    } else {
                        return String.format("%d,1,error: %s", iteration, sanitize(r.error));
                    }
                })
                .subscribeOn(Schedulers.boundedElastic())
                .doFinally(sig -> unpaired1.decrementAndGet());

        Mono<String> b = Mono.fromCallable(() -> {
                    unpaired2.incrementAndGet();
                    FunctionExecutor.ExecutionResult r = detectAndRun(config.getFunction2(), iteration);
                    if (r.ok) {
                        return String.format(Locale.ROOT, "%d,2,%.6f,%d", iteration, r.value, r.timeMs);
                    } else {
                        return String.format("%d,2,error: %s", iteration, sanitize(r.error));
                    }
                })
                .subscribeOn(Schedulers.boundedElastic())
                .doFinally(sig -> unpaired2.decrementAndGet());

        // Merge — результаты выходят в порядке готовности
        return Flux.merge(a, b);
    }

    /**
     * Простая эвристика определения языка функции:
     * если строка содержит явные JS-конструкции, трактуем как JS, иначе Python.
     */
    private FunctionExecutor.ExecutionResult detectAndRun(String funcText, int x) {
        String t = funcText == null ? "" : funcText.trim();
        if (t.startsWith("function") || t.contains("return") || t.contains("=>")) {
            return executor.executeJs(funcText, x);
        } else {
            return executor.executePython(funcText, x);
        }
    }

    /** Sanitize error messages to avoid newlines in CSV output. */
    private String sanitize(String s) {
        if (s == null) return "";
        return s.replaceAll("[\\r\\n]+", " ");
    }

    /** Вспомогательная структура для результатов функций */
    private static class FunctionResult {
        final int iteration;
        final int functionNo;
        final double value;
        final long timeMs;
        final String error;

        FunctionResult(int iteration, int functionNo, double value, long timeMs, String error) {
            this.iteration = iteration;
            this.functionNo = functionNo;
            this.value = value;
            this.timeMs = timeMs;
            this.error = error;
        }
    }
}
