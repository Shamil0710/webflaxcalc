package com.example.webflaxcalc.executions;


import javax.script.Invocable;
import javax.script.ScriptEngine;
import javax.script.ScriptEngineManager;
import javax.script.ScriptException;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

import jakarta.annotation.PreDestroy;

/**
 * FunctionExecutor — отвечает за выполнение функций, заданных как строки
 * на JavaScript или Python. Это небезопасный компонент: он выполняет произвольный код.
 *
 * Поддерживаемые сценарии:
 *  - JavaScript: текст вида "function(x) { return x * x; }" или "x => x*x"
 *    Выполняется через ScriptEngine (например, Nashorn). В реализации мы создаём
 *    новый ScriptEngine на каждый вызов (чтобы не делить state между потоками).
 *
 *  - Python: текст вида "def f(x): return ..." или "lambda x: x + 1"
 *    Выполняется через внешний интерпретатор python3 / python (ProcessBuilder).
 *
 * Принципы:
 *  - Есть общий таймаут (TIMEOUT_MS). По истечении таймаута возвращаем ошибку.
 *  - Для Python используем пул потоков, чтобы не блокировать главный поток приложения.
 *  - Для JS создаём engine внутри вызывающего потока,
 *    чтобы избежать проблем конкурентного доступа и глобальных Bindings.
 *
 * Поля:
 *  - pythonPool — ExecutorService для запусков Python.
 *  - jsExecutor — ExecutorService для выполнения JS callables с контролем таймаута.
 */
public class FunctionExecutor {

    /** Максимальное время выполнения функции в миллисекундах. */
    public static final long TIMEOUT_MS = 2000;

    /** Пул потоков, в котором выполняются задачи Python (каждая задача запускает процесс python). */
    private final ExecutorService pythonPool;

    /**
     * Пул для выполнения JS-вызовов с возможностью контролировать таймаут.
     * Мы используем отдельный пул, чтобы не создавать поток для каждого таймаута.
     */
    private final ExecutorService jsExecutor;

    /** Флаг, чтобы при уничтожении корректно очистить ресурсы только один раз. */
    private final AtomicBoolean closed = new AtomicBoolean(false);

    public FunctionExecutor() {
        // 4 потокa для одновременных вызовов Python (настраиваемо)
        this.pythonPool = Executors.newFixedThreadPool(4, r -> {
            Thread t = new Thread(r, "python-exec-worker");
            t.setDaemon(true);
            return t;
        });

        // Cached pool для JS callables (может расти при нагрузке)
        this.jsExecutor = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "js-exec-worker");
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * Выполнить JavaScript-функцию. Внутри используется ScriptEngine (Nashorn),
     * создающийся локально для каждой задачи — это простая, безопасная (по части threading)
     * стратегия для тестового задания.
     *
     * @param funcText текст функции на JS (например, "function(x){ return x*x; }")
     * @param x входной int-аргумент
     * @return ExecutionResult (ok=true + value + timeMs) или (ok=false + error)
     */
    public ExecutionResult executeJs(String funcText, int x) {
        // Callable, который инстанцирует ScriptEngine и выполняет функцию
        Callable<ExecutionResult> task = () -> {
            // создаём новый движок на каждый вызов — это защищает от глобальных state-коллизий
            ScriptEngine engine = new ScriptEngineManager().getEngineByName("nashorn");
            if (engine == null) {
                return ExecutionResult.error("No JS engine available (nashorn missing)");
            }

            try {
                Instant start = Instant.now();
                // объявляем функцию в контексте движка
                // безопасно делать eval() на user-provided код — НО: потенциально небезопасно в проде
                engine.eval("var __userFunc = " + funcText + ";");
                Invocable inv = (Invocable) engine;
                Object result = inv.invokeFunction("__userFunc", x);
                Instant end = Instant.now();
                double val = toDouble(result);
                return ExecutionResult.ok(val, Duration.between(start, end).toMillis());
            } catch (ScriptException se) {
                return ExecutionResult.error("JS error: " + se.getMessage());
            } catch (NoSuchMethodException nsme) {
                return ExecutionResult.error("JS invocation error: " + nsme.getMessage());
            } catch (Throwable t) {
                return ExecutionResult.error("JS exception: " + t.toString());
            }
        };

        Future<ExecutionResult> future = jsExecutor.submit(task);
        try {
            return future.get(TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (TimeoutException te) {
            future.cancel(true);
            return ExecutionResult.error(String.format(Locale.ROOT, "JS timeout > %d ms", TIMEOUT_MS));
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return ExecutionResult.error("JS interrupted");
        } catch (ExecutionException ee) {
            return ExecutionResult.error("JS execution failed: " + ee.getCause());
        }
    }

    /**
     * Выполнить Python-функцию.
     * Мы запускаем внешний процесс python3 (или python) с -c '...' и читаем stdout.
     *
     * Поддерживаем два формата функции:
     *  - "def f(x): ..." — тогда мы вызываем f(x) внутри скрипта
     *  - "lambda x: ..." — создаём f = <lambda> и печатаем f(x)
     *
     * @param funcText текст Python-функции
     * @param x входной int-аргумент
     * @return ExecutionResult
     */
    public ExecutionResult executePython(String funcText, int x) {
        Future<ExecutionResult> f = pythonPool.submit(() -> runPythonProcess(funcText, x));
        try {
            return f.get(TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (TimeoutException te) {
            f.cancel(true);
            return ExecutionResult.error(String.format(Locale.ROOT, "Python timeout > %d ms", TIMEOUT_MS));
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return ExecutionResult.error("Python interrupted");
        } catch (ExecutionException ee) {
            return ExecutionResult.error("Python execution failed: " + ee.getCause());
        }
    }

    /**
     * Внутренняя реализация запуска python-процесса.
     */
    private ExecutionResult runPythonProcess(String funcText, int x) {
        try {
            Instant start = Instant.now();

            // Формируем скрипт, который печатает результат функции в stdout
            StringBuilder script = new StringBuilder();
            if (funcText.trim().startsWith("def ")) {
                script.append(funcText).append("\n");
                script.append("print(f(").append(x).append("))\n");
            } else {
                // предполагаем lambda или выражение
                script.append("f = ").append(funcText).append("\n");
                script.append("print(f(").append(x).append("))\n");
            }

            // сначала пробуем python3, затем python
            Process process;
            try {
                process = new ProcessBuilder("python3", "-u", "-c", script.toString()).start();
            } catch (IOException ex) {
                process = new ProcessBuilder("python", "-u", "-c", script.toString()).start();
            }

            // читаем stdout и stderr асинхронно
            ByteArrayOutputStream baosOut = new ByteArrayOutputStream();
            ByteArrayOutputStream baosErr = new ByteArrayOutputStream();

            Thread outThread = streamTo(process.getInputStream(), baosOut);
            Thread errThread = streamTo(process.getErrorStream(), baosErr);

            int exitCode = process.waitFor();
            outThread.join();
            errThread.join();

            String stdout = baosOut.toString(StandardCharsets.UTF_8).trim();
            String stderr = baosErr.toString(StandardCharsets.UTF_8).trim();

            if (exitCode != 0 || !stderr.isEmpty()) {
                String errMsg = stderr.isEmpty() ? ("Python exited with code " + exitCode) : stderr;
                return ExecutionResult.error("Python error: " + errMsg);
            }

            double val = Double.parseDouble(stdout);
            Instant end = Instant.now();
            return ExecutionResult.ok(val, Duration.between(start, end).toMillis());
        } catch (Exception e) {
            return ExecutionResult.error("Python run failed: " + e.toString());
        }
    }

    /**
     * Копирует InputStream в ByteArrayOutputStream в отдельном потоке.
     * Возвращает поток, который запущен и читает данные.
     */
    private Thread streamTo(InputStream in, ByteArrayOutputStream baos) {
        Thread t = new Thread(() -> {
            try (InputStream is = in; ByteArrayOutputStream os = baos) {
                byte[] buf = new byte[1024];
                int r;
                while ((r = is.read(buf)) != -1) {
                    os.write(buf, 0, r);
                }
            } catch (IOException ignored) {
            }
        }, "proc-stream-reader");
        t.setDaemon(true);
        t.start();
        return t;
    }

    /** Преобразует объект (Number или String) в double */
    private double toDouble(Object o) {
        if (o instanceof Number) return ((Number) o).doubleValue();
        return Double.parseDouble(o.toString());
    }

    /** Корректное завершение пулов при остановке приложения */
    @PreDestroy
    public void destroy() {
        if (closed.compareAndSet(false, true)) {
            jsExecutor.shutdownNow();
            pythonPool.shutdownNow();
        }
    }

    /**
     * Результат выполнения функции: либо ok + value + timeMs, либо error.
     */
    public static class ExecutionResult {
        public final boolean ok;
        public final double value;    // meaningful only if ok==true
        public final long timeMs;     // измеренное время выполнения
        public final String error;    // non-null only if ok==false

        private ExecutionResult(boolean ok, double value, long timeMs, String error) {
            this.ok = ok;
            this.value = value;
            this.timeMs = timeMs;
            this.error = error;
        }

        public static ExecutionResult ok(double value, long timeMs) {
            return new ExecutionResult(true, value, timeMs, null);
        }

        public static ExecutionResult error(String error) {
            return new ExecutionResult(false, Double.NaN, -1, error);
        }
    }
}
