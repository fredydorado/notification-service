# General Development Guidelines

This document defines the core development standards for the project to ensure consistency, quality, and maintainability.

## 1. Testing

* **Integration Testing:**
  - Do not use MockMvc for integration testing.
  - Deploy the Spring context when the integration test is been executed.
  - Use SpringBootTest.WebEnvironment.RANDOM_PORT.
  - Use `TestRestTemplate` instead of `RestTemplate` to call APIs. In Spring Boot 4, `TestRestTemplate` lives in the `spring-boot-resttestclient` artifact (test scope), package `org.springframework.boot.resttestclient` — the Boot 3 package `org.springframework.boot.test.web.client` no longer exists.

[//]: # (  - Create an abstract class to centralize all common configuration associated to integration testing. All integration test classes should extend from it. Name this class as "Abstract${ServiceName}IntTest".)
  - When the integration tests are been executed, use Testcontainers to test Kafka and Postgres integration. 

## 2. OpenApi documentation
  - Include OpenApi documentation for all endpoints defined in the controller classes.
  - The OpenApi documentation should be located in an interface where all endpoints of a controller are defined (e.g., `UserController`).
  - The implementation of a controller interface should be located in a package called "impl" located in the same directory of the controller interface (e.g., `UserControllerImpl`).


