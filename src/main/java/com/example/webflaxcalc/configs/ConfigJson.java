package com.example.webflaxcalc.configs;

/**
 * POJO, представляющий конфигурацию сервиса.
 * Поля:
 *  - function1: строка с JS- или Python-функцией (принимает int, возвращает float/double)
 *  - function2: вторая функция
 *  - interval: интервал между итерациями в миллисекундах
 *
 * Jackson использует стандартные геттеры/сеттеры для десериализации.
 */
public class ConfigJson {

    private String function1;
    private String function2;
    private int interval;

    public String getFunction1() {
        return function1;
    }

    public void setFunction1(String function1) {
        this.function1 = function1;
    }

    public String getFunction2() {
        return function2;
    }

    public void setFunction2(String function2) {
        this.function2 = function2;
    }

    public int getInterval() {
        return interval;
    }

    public void setInterval(int interval) {
        this.interval = interval;
    }
}
