# Calculator Project — WebFlux API + Vaadin UI (2 services)

**Микроархитектура из двух приложений:**
- **webflaxcalc** — реактивный сервис на Spring WebFlux, отдаёт поток CSV.
- **vaadinui** — UI на Vaadin (Servlet stack), подключается к бэкенду и отображает результаты.

---

## ✨ Возможности

- Реактивный endpoint `GET /api/calculate` (SSE/стрим).
- Два режима вывода: `ordered=true/false`.
- Отдельное Vaadin-приложение для визуализации.
- Готовность к контейнеризации (Docker/Docker Compose).
- Простая конфигурация URL бэкенда через ENV или `application.properties`.

---

## 🧱 Технологии

- **Java 17+** (совместимо с более новыми JDK).
- **Spring Boot 3.5.x**:
    - `calculator-service`: `spring-boot-starter-webflux` (Netty).
    - `calculator-ui`: `vaadin-spring-boot-starter` + `spring-boot-starter-web` (Servlet).
- **Vaadin 24.x** (Flow).
- **Maven 3+**.