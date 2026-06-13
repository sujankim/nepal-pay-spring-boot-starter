# Changelog

All notable changes to **NepalPay Spring Boot Starter** will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/), and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

---

## [Unreleased]

### Planned

* ConnectIPS integration
* Fonepay integration
* Khalti refund support
* Retry logic with exponential backoff
* Webhook support
* Spring WebFlux support

---

## [0.1.0] - 2026-06-13 🎉 First Release

### Added

#### Khalti Payment Gateway

* `KhaltiClient.initiatePayment()` — Server-side payment initiation
* `KhaltiClient.lookupPayment()` — Server-side payment verification (always call after callback)
* `KhaltiPaymentStatus` enum with all official statuses:

    * `COMPLETED`
    * `PENDING`
    * `INITIATED`
    * `EXPIRED`
    * `USER_CANCELED`
    * `REFUNDED`
* Sandbox and production mode switching via `nepalpay.khalti.sandbox`
* Zero-boilerplate auto-configuration using `@ConditionalOnProperty`

#### eSewa Payment Gateway

* `EsewaClient.buildFormPayload()` — HMAC-SHA256 signed form payload generation
* `EsewaClient.verifyCallback()` — Base64 decoding, signature verification, and status verification
* `EsewaClient.checkStatus()` — Direct status API polling
* `EsewaClient.generateTransactionUuid()` — eSewa-compatible UUID generation
* `EsewaVerificationResult` record
* `EsewaPaymentStatus` enum:

    * `COMPLETE`
    * `INCOMPLETE`
    * `UNKNOWN`
* Sandbox and production mode switching via `nepalpay.esewa.sandbox`

#### Auto-Configuration

* `NepalPayAutoConfiguration` for Spring Boot 4.1.0
* `NepalPayProperties` using type-safe `@ConfigurationProperties`
* IDE auto-completion via `spring-configuration-metadata.json`
* `@ConditionalOnMissingBean` support for custom bean overrides

#### Testing

* 51 automated tests covering:

    * Happy paths
    * Error paths
    * Security validation
* `KhaltiClientTest` using MockWebServer
* `EsewaClientTest` with HMAC signature verification and MockWebServer
* `NepalPayAutoConfigurationTest` using `ApplicationContextRunner`

### Technical Stack

* Java 21
* Spring Boot 4.1.0
* Jackson 3 (`tools.jackson`)
* SLF4J (Logging Facade)
* MockWebServer 4.12.0
* Maven
* JUnit 5
* AssertJ
* Mockito

### Security

* Server-side payment verification enforced for Khalti
* HMAC-SHA256 signature verification for eSewa callbacks
* No real payment credentials used in automated tests

---

## Release Notes

### v0.1.0 Highlights

✅ First production-grade Java library for Nepal payment gateways

✅ Spring Boot Starter with auto-configuration

✅ Khalti integration

✅ eSewa integration

✅ Type-safe configuration properties

✅ Comprehensive automated testing

✅ Java 21 and Spring Boot 4.1 support

🇳🇵 Built for the Nepal Java ecosystem.
