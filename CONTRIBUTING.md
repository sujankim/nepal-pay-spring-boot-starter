# Contributing to NepalPay Spring Boot Starter

Thank you for your interest! 🇳🇵

**NepalPay** is the first production-grade Java library for Nepal payment gateways.

Contributions of all sizes are welcome—whether you're fixing bugs, improving documentation, adding tests, or implementing new payment gateways.

---

# 🔧 Local Setup

## 1. Fork this repository

Click the **Fork** button at the top-right of the GitHub repository.

---

## 2. Clone your fork

```bash
git clone https://github.com/sujankim/nepal-pay-spring-boot-starter.git
cd nepal-pay-spring-boot-starter
```

---

## 3. Build and run all tests

### Maven

```bash
mvn clean test
```

### Gradle

```bash
./gradlew test
```

---

## 4. Verify all tests pass

Before opening a Pull Request, ensure every test passes successfully.

---

# 📋 How to Contribute

## 🐛 Reporting Bugs

Before opening a new issue:

- Search existing issues first.
- Use the **Bug Report** template.

Please include:

- Java version
- Spring Boot version
- Blocking or Reactive starter
- Error message or stack trace
- Steps to reproduce
- Expected behavior
- Actual behavior

---

## 💡 Requesting Features

Open a new issue using the **Feature Request** template.

Helpful information includes:

- What problem does this solve?
- Why is the feature needed?
- Proposed API or implementation (optional)
- Does it affect the blocking starter, reactive starter, or both?

---

# 🚀 Submitting a Pull Request

## 1. Create a branch

```bash
git checkout -b feature/your-feature-name
```

### Branch naming

| Prefix | Purpose |
|---------|----------|
| `feature/` | New gateway, feature, or module |
| `fix/` | Bug fixes |
| `docs/` | Documentation only |
| `test/` | Tests only |
| `refactor/` | Internal cleanup (no behavior changes) |
| `release/` | Version bumps and release preparation |

---

## 2. Write code and tests

Please follow the coding standards below.

---

## 3. Run all tests

```bash
# Run every module
mvn clean test

# Test individual modules
mvn clean test -pl nepal-pay-core
mvn clean test -pl nepal-pay-spring-boot-3-starter
mvn clean test -pl nepal-pay-spring-boot-4-starter
mvn clean test -pl nepal-pay-spring-boot-reactive-starter
```

All tests must be green. ✅

---

## 4. Commit using Conventional Commits

Examples:

```text
feat: add Fonepay integration
feat(reactive): add EsewaReactiveClient
fix: correct eSewa sandbox URL
fix(reactive): wrap RSA signing in Mono.defer
docs: update ConnectIPS guide
test: add refund test for Khalti
refactor: simplify RSA signing utility
ci: fix checkout action version
```

---

## 5. Push and open a Pull Request

```bash
git push origin feature/your-feature-name
```

Then open a Pull Request against the **main** branch.

---

## 6. Update the changelog

Add your changes under the **[Unreleased]** section in:

```text
CHANGELOG.md
```

---

# 🗺️ Roadmap — Great Places to Contribute

| Priority | Feature | Notes | Status |
|----------|---------|-------|--------|
| 🔴 High | Kotlin code examples | README + gateway docs | Open — Issue #4 |
| 🔴 High | ConnectIPS configurable timeout | `nepalpay.connectips.timeout-seconds` | Open — Issue #8 |
| 🟡 Medium | Webhook support | Receive gateway callbacks | Open |
| 🟡 Medium | eSewa Refund API | `EsewaClient.refundPayment()` | Open |
| 🟢 Low | Reactive consumer demo | WebFlux example application | Open |
| ✅ Done | Spring WebFlux support | Reactive starter | Closed — Issue #6 |
| ✅ Done | Khalti Refund API | Refund support | v0.5.0 |
| ✅ Done | Retry with exponential backoff | Automatic retry | v0.6.0 |
| ✅ Done | Maven Central publishing | Removed JitPack requirement | v1.0.0 |

---

# ✅ Code Standards

| Area | Standard |
|------|----------|
| Models | Java Records |
| Logging | `@Slf4j` |
| Exceptions | Extend `NepalPayException` |
| Blocking HTTP | `RestClient` |
| Reactive HTTP | `WebClient` |
| Tests | MockWebServer, StepVerifier, JUnit |
| Javadoc | Required for all public APIs |
| Secrets | Never hardcode |

---

# ⚖️ Blocking vs Reactive

When adding features involving HTTP calls:

| Concern | Blocking | Reactive |
|---------|----------|----------|
| HTTP Client | `RestClient` | `WebClient` |
| Return Type | Direct value | `Mono<T>` |
| Error Handling | `throw new XxxException()` | `Mono.error()` |
| Validation | Immediate validation | `Mono.defer()` |
| Retry | `executeWithRetry()` | `Retry.backoff()` |
| Testing | Assertions | `StepVerifier` |

## Reactive Rule

All synchronous work that may throw (validation, RSA signing, hashing, etc.) **must** be wrapped in `Mono.defer()` or `Mono.fromCallable()`.

### ✅ Correct

```java
public Mono<Response> doSomething(String input) {
    return Mono.defer(() -> {
        if (input == null) {
            return Mono.error(new XxxException("input cannot be null"));
        }

        return executeRequest(input);
    });
}
```

### ❌ Incorrect

```java
public Mono<Response> doSomething(String input) {
    if (input == null) {
        throw new XxxException("input cannot be null");
    }

    return executeRequest(input);
}
```

---

# 🧪 Test Standards

Every new gateway should include:

## `*PaymentStatusTest.java`

- Enum unit tests

---

## `*ClientTest.java` (Blocking)

- MockWebServer HTTP tests
- Retry behavior
- Validation
- Error handling

---

## `*ReactiveClientTest.java`

- MockWebServer
- StepVerifier
- Validation emits `Mono.error()`
- Retry behavior
- No HTTP calls on validation failures

---

## Configuration Tests

Verify:

- Missing configuration
- Invalid configuration
- Production vs sandbox behavior

---

## Auto-Configuration Tests

Update:

```
NepalPayAutoConfigurationTest
```

Include:

- Bean wiring
- Happy paths
- Failure paths

---

## Reactive Testing Pattern

### Successful response

```java
StepVerifier.create(
        khaltiReactiveClient.lookupPayment(pidx))
    .expectNextMatches(KhaltiLookupResponse::isPaymentSuccessful)
    .verifyComplete();
```

### Validation error

```java
StepVerifier.create(
        khaltiReactiveClient.lookupPayment(null))
    .expectErrorMatches(error ->
        error instanceof KhaltiException &&
        error.getMessage().contains("pidx cannot be null"))
    .verify();
```

### No HTTP request should occur

```java
assertThat(mockWebServer.getRequestCount())
    .isEqualTo(0);
```

---

## RSA Test Keys

Always generate **2048-bit** RSA keys.

```java
KeyPairGenerator keyGen =
    KeyPairGenerator.getInstance("RSA");

keyGen.initialize(2048);

TEST_PRIVATE_KEY =
    keyGen.generateKeyPair().getPrivate();
```

---

# 📚 Documentation Standards

Every new feature should include:

- Javadoc for all public APIs
- README updates
- Blocking and reactive examples
- Documentation site updates (`docs/`)
- Example application updates (`examples/consumer-demo/`)
- Changelog entry under **[Unreleased]**

---

# 🔐 Security Guidelines

## ❌ Never

- Hardcode API keys
- Commit `.pfx` certificates
- Commit merchant credentials
- Log secrets or tokens
- Trust redirect parameters without server-side verification

---

## ✅ Always

- Use configuration properties
- Add sensitive files to `.gitignore`
- Verify payments server-side
- Wrap reactive crypto/signing inside `Mono.defer()`

---

# 📜 License

By contributing to NepalPay Spring Boot Starter, you agree that your contributions are licensed under the [MIT License](LICENSE).

---

<div align="center">

Built with ❤️ for Nepal's developer community 🇳🇵

Thank you for contributing!

⭐ If NepalPay saved you time, consider giving the repository a star.

</div>