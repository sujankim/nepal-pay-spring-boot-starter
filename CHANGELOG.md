# Changelog

All notable changes to NepalPay Spring Boot Starter.
Format: [Keep a Changelog](https://keepachangelog.com/en/1.0.0/)

## [Unreleased]

### Fixed
- `RetryProperties.jitter()` now uses `ThreadLocalRandom` instead of
  `Math.random()` for better performance under concurrent retry
  execution across multiple gateway clients
---

## [1.0.0] — 2026-06-16 🚀 First Maven Central Release

### Changed
- Published to **Maven Central** — no JitPack repository block needed
- GroupId: `io.github.sujankim` (auto-verified via GitHub login)
- Add `flatten-maven-plugin 1.6.0` to `nepal-pay-core`
  for clean consumer POM generation
- Add `central-publishing-maven-plugin 0.6.0` to all modules
- Add `maven-gpg-plugin 3.2.7` release profile to all modules
- Spring Boot 3 parent: `3.5.0` → `3.5.15` (security patch fixes)
- All Javadoc `&` → `&amp;` in `FonepayCallbackResponse.java`
- GitHub Actions: `checkout@v4` → `v5`, `setup-java@v4` → `v5`

### Install (Maven Central — no repository block needed)
```xml
<!-- Spring Boot 3.2+ -->
<dependency>
    <groupId>io.github.sujankim</groupId>
    <artifactId>nepal-pay-spring-boot-3-starter</artifactId>
    <version>1.0.0</version>
</dependency>

<!-- Spring Boot 4.x -->
<dependency>
    <groupId>io.github.sujankim</groupId>
    <artifactId>nepal-pay-spring-boot-4-starter</artifactId>
    <version>1.0.0</version>
</dependency>
```
---

## [0.6.0] — 2026-06-16 🔁 Retry with Exponential Backoff

### Added

#### `RetryProperties` in `nepal-pay-core`
- New `io.nepalpay.core.retry.RetryProperties` Java record — zero Spring dependency
- `RetryProperties.DEFAULT` constant — retry disabled (safe default)
- `RetryProperties.DISABLED` constant — internal use
- `RetryProperties.isActive()` — guards against `enabled=true, max-attempts=0`
- `RetryProperties.nextDelay(currentDelayMs)` — exponential backoff calculation
- `RetryProperties.jitter(delayMs)` — ±10% random offset (thundering herd prevention)
- `RetryProperties.summary()` — human-readable log string
- 21 new tests in `RetryPropertiesTest`

#### New `retry:` config block (Khalti, eSewa, ConnectIPS)
- `nepalpay.khalti.retry.*` — applies to `initiatePayment()`, `lookupPayment()`, `refundPayment()`
- `nepalpay.esewa.retry.*` — applies to `checkStatus()` (inside `verifyCallback()`)
- `nepalpay.connectips.retry.*` — applies to `validateTransaction()`
- `FonepayProperties` unchanged — Fonepay makes no HTTP calls, retry N/A
- `retryOrDefault()` helper on each properties record — never returns null

#### Client retry integration
- `KhaltiClient` — `executeWithRetry()` wraps all three HTTP methods
- `EsewaClient` — `executeWithRetry()` wraps `checkStatus()`
- `ConnectIpsClient` — `executeWithRetry()` wraps `executeValidateRequest()`
- Two new `ConnectIpsClient` constructors accepting `RetryProperties`

#### Technical details
- Retry **disabled by default** — must explicitly set `enabled: true`
- Retries: `httpStatus=0` (network error) + `httpStatus >= 500` (server error)
- Never retries: `httpStatus 400-499` (client errors won't fix themselves)
- Exponential backoff: `delay × multiplier`, capped at `max-delay-ms`
- Jitter: ±10% random offset on every sleep to prevent thundering herd
- `Thread.sleep()` is interruptible — restores interrupt flag correctly

#### Tests (50 new across Boot 3 + Boot 4)
- `KhaltiClientTest` — 12 retry tests: success after retry, exhausted retries, 4xx not retried
- `EsewaClientTest` — 7 retry tests: includes `verifyCallback` benefits via `checkStatus`
- `ConnectIpsClientTest` — 6 retry tests
- All retry tests use `0ms` delay for instant execution
- `getRequestCount()` assertions verify retry actually occurred

#### Docs + Demo
- `docs/` — complete redesign with new CSS design system
- `docs/configuration.html` — new Retry Configuration section
- `README.md` — retry config example + gateway retry table
- `examples/consumer-demo/application.yml` — retry config comments added

---

## [0.5.0] — 2026-06-16 💳 Khalti Refund API

### Added

#### Khalti Refund Support
- `KhaltiClient.refundPayment(String transactionId)` — full refund
- `KhaltiClient.refundPayment(String transactionId, Long amountPaisa)` — partial refund
- `KhaltiRefundResponse` — typed refund response record in `nepal-pay-core`
- `KhaltiPaymentStatus.REFUNDED` — new enum value (8 total now)
- `KhaltiPaymentStatus.isRefunded()` — dedicated helper method
- `KhaltiLookupResponse.isRefunded()` — helper for refunded lookup responses

#### Technical details
- Refund uses `transaction_id` from `lookupPayment()` — **not** `pidx`
- Full refund request body: `{}`
- Partial refund request body: `{"amount": 5000}` (amount in paisa)
- Refund endpoint: `/api/merchant-transaction/{transaction_id}/refund/`
- This path has **no `/api/v2`** — handled via separate `baseDomain` field
- `KhaltiClient` now stores `baseDomain` alongside `baseUrl`
- Three constructor chain: `public prod → public test → private core`
- `baseDomain()` utility method exposed for testing

#### Tests (12 new per starter)
- Full refund success — asserts `isRefundSuccessful()` = true
- Full refund body is `{}` — not partial body
- Partial refund body contains `amount` in paisa
- Correct path: `/api/merchant-transaction/{id}/refund/`
- 400/401/500 error handling
- Null/blank `transactionId` validation
- Zero/negative `amountPaisa` validation
- `KhaltiPaymentStatus.REFUNDED` enum tests (5 new in core)

#### Consumer Demo + Docs
- `POST /api/demo/khalti/refund` endpoint added
- `docs/khalti.html` — Refund section added
- `README.md` — Refund example added

---

## [0.4.0] — 2026-06-14 🔵 Fonepay Integration

### Added

#### Fonepay Payment Gateway
- `FonepayClient.buildRedirectParams(FonepayPaymentRequest)` — typed overload
- `FonepayClient.buildRedirectParams(prn, amount, r1, r2)` — direct overload
- `FonepayClient.verifyCallback(FonepayCallbackResponse)` — HMAC-SHA512 verify
- `FonepayPaymentRequest` — record with builder
- `FonepayRedirectParams` — signed params record with full `redirectUrl()`
- `FonepayCallbackResponse` — typed callback record with `of()` factory
- `FonepayPaymentStatus` enum — SUCCESS, FAILED, UNKNOWN
- `FonepayVerificationResult` — inner record with `isPaymentSuccessful()`
- `FonepayException` — typed exception

#### Technical details
- Signature: HMAC-SHA512 output as **lowercase hex**
- Response verification: HMAC-SHA512 output as **UPPERCASE hex** (DV comparison)
- Amount: NPR as `double` (not paisa, not BigDecimal)
- Flow: URL redirect GET (no form POST, no API-first)
- `FonepayClient` does **NOT** use `RestClient` — no server-to-server calls
- `UriComponentsBuilder.fromUriString()` used (not `fromHttpUrl()` — removed in Spring 7)

### Fixed
- `UriComponentsBuilder.fromHttpUrl()` replaced with `fromUriString()` for Spring Boot 4 compatibility

---

## [0.3.1] — 2026-06-14

### Fixed
- Added `jitpack.yml` with `jdk: [openjdk21]`
- JitPack defaulted to Java 8 which cannot compile Java 17+ source
- All 4 modules now build successfully on JitPack

---

## [0.3.0] — 2026-06-14

### Added
- Multi-module architecture: `nepal-pay-core`, `nepal-pay-spring-boot-3-starter`, `nepal-pay-spring-boot-4-starter`
- Spring Boot 3.2+ support with Jackson 2 (`com.fasterxml.jackson`)
- `examples/consumer-demo/` — complete working demo application
- `docs/` — full documentation website

### Changed
- Model packages: `io.nepalpay.*` → `io.nepalpay.core.*`
- Exception packages: `io.nepalpay.exception` → `io.nepalpay.core.exception`

---

## [0.2.0] — 2026-06-13

### Added
- ConnectIPS payment gateway — RSA-SHA256 signed form payload
- `ConnectIpsClient` — `buildFormPayload()` + `validateTransaction()`
- `ConnectIpsPaymentRequest` — builder with `amountNPR()` auto-conversion
- `ConnectIpsValidateResponse` — typed response
- `ConnectIpsException` — typed exception

---

## [0.1.0] — 2026-06-13 🎉 First Release

### Added
- Khalti — API-first initiate + server-side lookup/verify
- eSewa — HMAC-SHA256 form payload + Base64 callback verify + status API
- Spring Boot 4.1.0 auto-configuration
- `@ConditionalOnMissingBean` on all beans
- 51 tests with MockWebServer