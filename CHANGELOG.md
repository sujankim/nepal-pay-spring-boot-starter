# Changelog

All notable changes to **NepalPay Spring Boot Starter** will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/), and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

---

## [0.3.0] - 2026-06-14

### Added

#### Multi-Module Architecture

* `nepal-pay-core` — pure Java 17 module with zero Spring dependencies

    * All models (records), exceptions, and status enums live here
    * Compatible with Spring Boot 3.x, Spring Boot 4.x, and plain Java 17+
    * Package: `io.nepalpay.core.*`

* `nepal-pay-spring-boot-3-starter` — Spring Boot 3.2+ support

    * Uses Jackson 2 (`com.fasterxml.jackson.databind.ObjectMapper`)
    * Java 17 minimum

* `nepal-pay-spring-boot-4-starter` — Spring Boot 4.x support

    * Uses Jackson 3 (`tools.jackson.databind.json.JsonMapper`)
    * Java 21 minimum

#### Consumer Demo

* `examples/consumer-demo/` — complete working Spring Boot 4 demo application

    * `HealthController` — confirms auto-configuration
    * `KhaltiDemoController` — initiate + callback with full comments
    * `EsewaDemoController` — initiate + callback + failure handler
    * `ConnectIpsDemoController` — initiate + callback
    * `examples/consumer-demo/README.md` — usage guide with `curl` examples

### Changed

* All model packages moved from `io.nepalpay.*` to `io.nepalpay.core.*`
* All exception packages moved from `io.nepalpay.exception` to `io.nepalpay.core.exception`
* Parent `pom.xml` changed from a Spring Boot parent to a plain Maven parent
* CI workflow updated to build modules in the correct dependency order

### Migration from v0.2.0

Change your dependency:

#### Spring Boot 3.2+

```xml
<dependency>
    <groupId>com.github.sujankim.nepal-pay-spring-boot-starter</groupId>
    <artifactId>nepal-pay-spring-boot-3-starter</artifactId>
    <version>v0.3.0</version>
</dependency>
```

#### Spring Boot 4.x

```xml
<dependency>
    <groupId>com.github.sujankim.nepal-pay-spring-boot-starter</groupId>
    <artifactId>nepal-pay-spring-boot-4-starter</artifactId>
    <version>v0.3.0</version>
</dependency>
```

Update your imports:

**Before (v0.2.0)**

```java
import io.nepalpay.khalti.model.KhaltiInitiateRequest;
import io.nepalpay.exception.KhaltiException;
```

**After (v0.3.0)**

```java
import io.nepalpay.core.khalti.model.KhaltiInitiateRequest;
import io.nepalpay.core.exception.KhaltiException;
```

---

## [0.2.0] - 2026-06-13

### Added

* ConnectIPS payment gateway (RSA-SHA256 signed)
* `ConnectIpsClient` — `buildFormPayload()` and `validateTransaction()`
* `ConnectIpsPaymentRequest` — builder with `amountNPR()` auto-conversion
* Full ConnectIPS documentation page

---

## [0.1.0] - 2026-06-13

### Added

* Khalti payment gateway — initiate + lookup
* eSewa payment gateway — form payload + verify + status API
* Spring Boot 4.1.0 auto-configuration
* 51 tests with MockWebServer
