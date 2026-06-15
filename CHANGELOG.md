# Changelog

All notable changes to NepalPay Spring Boot Starter.
Format: [Keep a Changelog](https://keepachangelog.com/en/1.0.0/)

---

## [0.5.0] — 2026-06-15 💳 Khalti Refund API

### Added

#### Khalti Refund Support
- `KhaltiClient.refundPayment(String transactionId)` — full refund
- `KhaltiClient.refundPayment(String transactionId, Long amountPaisa)` — partial refund
- `KhaltiRefundResponse` — typed refund response record
- `KhaltiPaymentStatus.REFUNDED` — new payment status enum value
- `KhaltiLookupResponse.isRefunded()` — helper for refunded lookup responses

#### Key Technical Details
- Refund API uses `transaction_id`, **not** `pidx`
- `transaction_id` is obtained from `lookupPayment(pidx).transactionId()`
- Full refund sends an empty JSON body: `{}`
- Partial refund sends amount in paisa: `{ "amount": 5000 }`
- Refund endpoint uses Khalti's merchant transaction API path:
  `/api/merchant-transaction/{transaction_id}/refund/`
- This path does **not** include `/api/v2`

#### Tests
- Added full refund tests
- Added partial refund tests
- Added refund request body assertions
- Added refund URL path assertions
- Added 4xx/5xx refund error handling tests
- Added validation tests for null/blank `transactionId`
- Added validation tests for invalid partial refund amounts
- Added `KhaltiPaymentStatus.REFUNDED` tests

#### Consumer Demo
- Added `POST /api/demo/khalti/refund`
- Supports both full and partial refund examples
- Clearly documents that refund requires `transactionId`, not `pidx`

#### Docs
- Updated `docs/khalti.html` with a new `refundPayment()` section
- Updated README with Khalti refund usage example
- Updated supported gateway table for Khalti refund support
---

## [0.4.0] — 2026-06-14 🔵 Fonepay Integration

### Added

#### Fonepay Payment Gateway
- `FonepayClient.buildRedirectParams(FonepayPaymentRequest)` — typed overload
- `FonepayClient.buildRedirectParams(prn, amount, r1, r2)` — direct overload
- `FonepayClient.verifyCallback(FonepayCallbackResponse)` — HMAC-SHA512 verify
- `FonepayPaymentRequest` — record with builder (prn, amount, remarks)
- `FonepayRedirectParams` — signed params record with full `redirectUrl()`
- `FonepayCallbackResponse` — typed callback record with `of()` factory
- `FonepayPaymentStatus` enum — SUCCESS, FAILED, UNKNOWN
- `FonepayVerificationResult` — inner record with `isPaymentSuccessful()`
- `FonepayException` — typed exception extending `NepalPayException`

#### Key Technical Details
- Signature: HMAC-SHA512 output as **lowercase hex** (not Base64 like eSewa)
- Response verification: HMAC-SHA512 output as **UPPERCASE hex**
- Amount: NPR as `double` (not paisa, not BigDecimal)
- Flow: URL redirect GET (not form POST like eSewa, not API-first like Khalti)
- `FonepayClient` does NOT use `RestClient` — no server-to-server calls
- `FonepayClient.java` is **identical** in Boot 3 and Boot 4 starters

#### Tests
- `FonepayPaymentStatusTest` — pure enum unit tests (in core)
- `FonepayClientTest` — HMAC-SHA512 correctness, redirect URL, callback verify
- `NepalPayAutoConfigurationTest` — Fonepay bean wiring tests in both starters

#### Consumer Demo
- `FonepayDemoController` — initiate and callback endpoints with full comments
- `application.yml` — Fonepay configuration section (commented out)

#### Docs
- `docs/fonepay.html` — complete Fonepay integration guide
- All docs pages — Fonepay added to navigation
- `docs/index.html` — Fonepay in gateway grid (v0.4.0)
- `docs/getting-started.html` — Fonepay quickstart + amount units comparison

### Fixed
- `UriComponentsBuilder.fromHttpUrl()` removed in Spring Framework 7
  → replaced with `UriComponentsBuilder.fromUriString()` in `FonepayClient`
  → works in both Spring Framework 6 (Boot 3) and 7 (Boot 4)

---

## [0.3.1] — 2026-06-14

### Fixed
- Added `jitpack.yml` with `jdk: [openjdk21]`
- JitPack was defaulting to Java 8 which cannot compile Java 17 source
- All 4 modules now build on JitPack: core, Boot 3, Boot 4, parent

---

## [0.3.0] — 2026-06-14

### Added
- Multi-module architecture: `nepal-pay-core`, `nepal-pay-spring-boot-3-starter`, `nepal-pay-spring-boot-4-starter`
- Spring Boot 3.2+ support with Jackson 2 (`com.fasterxml.jackson`)
- `examples/consumer-demo/` — complete working demo app
- `docs/` website rebuilt with all gateway pages

### Changed
- All model packages: `io.nepalpay.*` → `io.nepalpay.core.*`
- All exception packages: `io.nepalpay.exception` → `io.nepalpay.core.exception`

---

## [0.2.0] — 2026-06-13

### Added
- ConnectIPS payment gateway — RSA-SHA256 signed form payload
- `ConnectIpsClient` — buildFormPayload + validateTransaction
- `ConnectIpsPaymentRequest` — builder with `amountNPR()` auto-conversion

---

## [0.1.0] — 2026-06-13 🎉 First Release

### Added
- Khalti — initiate + server-side lookup/verify
- eSewa — HMAC-SHA256 form payload + Base64 callback verify + status API
- Spring Boot 4.1.0 auto-configuration
- 51 tests with MockWebServer