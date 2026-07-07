# Changelog

All notable changes to NepalPay Spring Boot Starter.

Format: [Keep a Changelog](https://keepachangelog.com/en/1.0.0/)

---

# [v1.1.0] - 2026-07-07

## Added

### 🚀 `nepal-pay-spring-boot-reactive-starter` — New Module

A brand-new Spring WebFlux reactive starter for non-blocking payment gateway integration.

Returns `Mono<>` responses using `WebClient`.

### Why Reactive?

Projects built on Spring WebFlux cannot use the blocking `RestClient` clients in a fully non-blocking pipeline.

This module is a drop-in reactive alternative:

- Same configuration
- Same request/response models
- Same exceptions
- Same YAML configuration
- Reactive `Mono<>` API

---

### KhaltiReactiveClient

- `Mono<KhaltiInitiateResponse> initiatePayment(KhaltiInitiateRequest)`
- `Mono<KhaltiLookupResponse> lookupPayment(String pidx)`
- `Mono<KhaltiRefundResponse> refundPayment(String transactionId)`
- `Mono<KhaltiRefundResponse> refundPayment(String transactionId, Long amountPaisa)`
- Validation wrapped inside `Mono.defer()`
- Retry using `Retry.backoff()`
- Retries network failures and HTTP 5xx
- Never retries HTTP 4xx

---

### EsewaReactiveClient

- `EsewaFormPayload buildFormPayload(BigDecimal, String)` *(synchronous)*
- `Mono<EsewaVerificationResult> verifyCallback(String encodedData)`
- `Mono<EsewaStatusResponse> checkStatus(String uuid, String amount)`
- `MessageDigest.isEqual()` for constant-time HMAC verification
- Validation prevents negative charge components (`tax`, `service`, `delivery`)
- Retry via `Retry.backoff()`

---

### ConnectIpsReactiveClient

- `ConnectIpsFormPayload buildFormPayload(...)`
- `Mono<ConnectIpsValidateResponse> validateTransaction(String, String, long)`
- `Mono<ConnectIpsValidateResponse> validateTransactionWithToken(...)`
- RSA signing wrapped in `Mono.defer()`
- `ConnectIpsException` always emitted as a reactive error signal
- `loadPrivateKey()` now searches all aliases and selects the first `isKeyEntry()`
- Input validation before RSA signing
- Null request guard for `buildFormPayload(ConnectIpsPaymentRequest)`

---

### NepalPayReactiveAutoConfiguration

- `@ConditionalOnClass(WebClient.class)`
- `@ConditionalOnMissingBean` on all beans
- `@ConditionalOnProperty` per gateway
- Fonepay reuses the blocking `FonepayClient` (no HTTP requests)

---

### Configuration

Same `application.yml`.

```yaml
# Spring Boot 3.2+ (Java 17+)

<dependency>
    <groupId>io.github.sujankim</groupId>
    <artifactId>nepal-pay-spring-boot-reactive-starter</artifactId>
    <version>1.1.0</version>
</dependency>

nepalpay:
  khalti:
    secret-key: ${KHALTI_SECRET_KEY}
    return-url: ${KHALTI_RETURN_URL}
    sandbox: true

    retry:
      enabled: true
      max-attempts: 3
      initial-delay-ms: 500
      multiplier: 2.0
      max-delay-ms: 5000
```

---

### Tests (50+ New)

#### KhaltiReactiveClientTest

- 22 StepVerifier tests

#### EsewaReactiveClientTest

- 20 StepVerifier tests

#### ConnectIpsReactiveClientTest

- 24 StepVerifier tests
- `buildFormPayload()` validation
- `validateTransaction()` reactive validation
- Zero HTTP-call validation tests
- RSA key upgraded from **512-bit → 2048-bit**

#### NepalPayReactiveAutoConfigurationTest

- 12 tests
- Happy-path ConnectIPS bean creation
- Programmatically generated PKCS12 using `@TempDir`

---

## Fixed

### Reactive Starter (CodeRabbit Review)

- Wrapped RSA signing and validation inside `Mono.defer()` so `ConnectIpsException` becomes a proper reactive error signal.
- Removed fully-qualified `NepalPayProperties.KhaltiProperties` import in tests.
- Added missing ConnectIPS happy-path auto-configuration test.

---

### CI Pipeline

- Fixed invalid `actions/checkout@v7.0.0` → `@v4`
- Fixed incorrectly nested `test-reactive` workflow
- Reactive starter now runs in CI
- Standardized:
  - `actions/checkout@v4`
  - `actions/setup-java@v4`

---

## Install (Maven Central)

### Spring Boot 3.2+ — Reactive

```xml
<dependency>
    <groupId>io.github.sujankim</groupId>
    <artifactId>nepal-pay-spring-boot-reactive-starter</artifactId>
    <version>1.1.0</version>
</dependency>
```

### Spring Boot 3.2+ — Blocking

```xml
<dependency>
    <groupId>io.github.sujankim</groupId>
    <artifactId>nepal-pay-spring-boot-3-starter</artifactId>
    <version>1.1.0</version>
</dependency>
```

### Spring Boot 4.x — Blocking

```xml
<dependency>
    <groupId>io.github.sujankim</groupId>
    <artifactId>nepal-pay-spring-boot-4-starter</artifactId>
    <version>1.1.0</version>
</dependency>
```

---

# [v1.0.1] - 2026-06-22

## Fixed

- `RetryProperties.jitter()` now uses `ThreadLocalRandom`
- All gateway clients now honor `timeout-seconds`
- Removed duplicate `spring-boot-autoconfigure:3.5.4`
- Retry loops refactored (`sleepForRetry()`)
- `EsewaClient` now uses a singleton `ObjectMapper`
- `ConnectIpsClient` caches `PrivateKey` at startup (fail-fast configuration)

## Added

- `ConnectIpsClient` default timeout of **30 seconds**

## Issues

- #7 — Make ConnectIPS timeout configurable

---

# [v1.0.0] - 2026-06-16 🚀 First Maven Central Release

## Changed

- Published to Maven Central
- GroupId changed to `io.github.sujankim`
- Added `flatten-maven-plugin`
- Added `central-publishing-maven-plugin`
- Added `maven-gpg-plugin`
- Spring Boot parent updated to **3.5.15**
- Fixed Javadoc HTML escaping
- GitHub Actions updated to v5

## Install

### Spring Boot 3.2+

```xml
<dependency>
    <groupId>io.github.sujankim</groupId>
    <artifactId>nepal-pay-spring-boot-3-starter</artifactId>
    <version>1.0.0</version>
</dependency>
```

### Spring Boot 4.x

```xml
<dependency>
    <groupId>io.github.sujankim</groupId>
    <artifactId>nepal-pay-spring-boot-4-starter</artifactId>
    <version>1.0.0</version>
</dependency>
```

---

# [0.6.0] - 2026-06-16 🔁 Retry with Exponential Backoff

## Added

### RetryProperties

- New `RetryProperties` record
- `DEFAULT`
- `DISABLED`
- `isActive()`
- `nextDelay()`
- `jitter()`
- `summary()`

21 new unit tests.

### Retry Configuration

- `nepalpay.khalti.retry.*`
- `nepalpay.esewa.retry.*`
- `nepalpay.connectips.retry.*`

### Client Integration

- Khalti retry support
- eSewa retry support
- ConnectIPS retry support

### Technical Details

- Retry disabled by default
- Retries network failures + HTTP 5xx
- Never retries HTTP 4xx
- Exponential backoff
- ±10% jitter
- Interrupt-safe sleeping

### Tests

- KhaltiClientTest — 12
- EsewaClientTest — 7
- ConnectIpsClientTest — 6

---

# [0.5.0] - 2026-06-16 💳 Khalti Refund API

## Added

### Refund Support

- Full refund
- Partial refund
- `KhaltiRefundResponse`
- `KhaltiPaymentStatus.REFUNDED`
- `isRefunded()` helpers

### Technical Details

- Uses `transaction_id`
- Supports partial refund amounts
- Dedicated refund endpoint

---

# [0.4.0] - 2026-06-14 🔵 Fonepay Integration

## Added

- `buildRedirectParams()`
- `verifyCallback()`
- `FonepayPaymentStatus`
- `FonepayVerificationResult`
- HMAC-SHA512 verification

## Fixed

- Spring Boot 4 compatibility using `fromUriString()`

---

# [0.3.1] - 2026-06-14

## Fixed

- Added `jitpack.yml`
- Fixed Java 8 build issue on JitPack

---

# [0.3.0] - 2026-06-14

## Added

- Multi-module architecture
- Spring Boot 3 starter
- Consumer demo
- Documentation website

## Changed

- Package refactoring to `io.nepalpay.core.*`

---

# [0.2.0] - 2026-06-13

## Added

### ConnectIPS Integration

- RSA-SHA256 signing
- `buildFormPayload()`
- `validateTransaction()`

---

# [0.1.0] - 2026-06-13 🎉 First Release

## Added

- Khalti payment support
- eSewa payment support
- Spring Boot 4 auto-configuration
- `@ConditionalOnMissingBean`
- 51 MockWebServer tests