# Contributing to NepalPay Spring Boot Starter

Thank you for your interest! 🇳🇵

**NepalPay** is the first production-grade Java library for Nepal payment gateways. Contributions of all sizes are welcome, whether it's fixing bugs, improving documentation, adding tests, or implementing new payment gateways.

---

## 🔧 Local Setup

### 1. Fork this repository on GitHub

Click the **Fork** button at the top-right of the repository page.

### 2. Clone your fork

```bash
git clone https://github.com/YOUR_USERNAME/nepal-pay-spring-boot-starter.git
cd nepal-pay-spring-boot-starter
```

### 3. Build and run all tests

#### Maven

```bash
mvn clean test
```

#### Gradle

```bash
./gradlew test
```

### 4. All tests must pass before submitting a PR

---

# 📋 How to Contribute

## 🐛 Reporting Bugs

Before opening a new issue:

1. Search existing issues first.
2. Open a new issue using the **Bug Report** template.
3. Include:

- Java version
- Spring Boot version
- Error message or stack trace
- Steps to reproduce
- Expected behavior
- Actual behavior

---

## 💡 Requesting Features

1. Open a new issue using the **Feature Request** template.
2. Explain:

- What problem does it solve?
- Why is the feature needed?
- Proposed API or implementation (optional)

---

## 🚀 Submitting a Pull Request

### 1. Create a branch

```bash
git checkout -b feature/your-feature-name
```

### 2. Write code and tests

Please follow the code standards below.

### 3. Run all tests

#### Maven

```bash
mvn clean test
```

#### Gradle

```bash
./gradlew test
```

All tests must be green ✅

### 4. Commit using Conventional Commits

Examples:

```text
feat: add Fonepay integration
fix: correct eSewa sandbox URL
docs: update ConnectIPS guide
test: add refund test for Khalti
refactor: simplify RSA signing utility
```

### 5. Push and open a Pull Request

```bash
git push origin feature/your-feature-name
```

Then open a PR against the `main` branch.

### 6. Update the Changelog

Add your changes under the `[Unreleased]` section in:

```text
CHANGELOG.md
```

---

# 🗺️ Roadmap — Best Places to Contribute

| Priority | Feature | Notes |
|----------|----------|--------|
| 🔴 High | Fonepay integration | QR + bank transfer |
| 🔴 High | Khalti refund API | `KhaltiClient.refundPayment()` |
| 🟡 Medium | Retry with backoff | Auto-retry on transient errors |
| 🟡 Medium | Spring WebFlux support | Reactive/non-blocking clients |
| 🟢 Low | Webhook support | Receive push notifications |
| 🟢 Low | Maven Central publishing | Removes JitPack requirement |

---

# ✅ Code Standards

```text
Models        → Java records (not classes)
Logging       → @Slf4j (not manual Logger)
Exceptions    → Extend NepalPayException
HTTP Client   → RestClient (Spring Boot 4)
Tests         → MockWebServer for HTTP, plain JUnit for logic
Javadoc       → Required on all public methods and classes
Secrets       → Never hardcode — always use properties
```

---

# 🧪 Test Standards

Every new gateway must include:

- `*PaymentStatusTest.java`
  - Pure enum unit tests

- `*ClientTest.java`
  - MockWebServer HTTP tests

- Configuration guard tests
  - Missing configuration
  - Invalid configuration
  - Production vs sandbox behavior

- Updated `NepalPayAutoConfigurationTest.java`
  - Bean wiring tests
  - Auto-configuration verification

---

# 📚 Documentation Standards

New features should include:

- Javadoc for all public APIs
- README updates (if applicable)
- Documentation site updates (`docs/`)
- Example usage in `examples/consumer-demo/`
- Changelog entry

---

# 🔐 Security Guidelines

Never:

- Hardcode API keys
- Commit `.pfx` certificates
- Commit merchant credentials
- Log secrets or tokens

Always:

- Use configuration properties
- Add sensitive files to `.gitignore`
- Follow server-side payment verification rules

---

# 📜 License

By contributing to NepalPay Spring Boot Starter, you agree that your contributions will be licensed under the **MIT License**.

---

<div align="center">

Built with ❤️ for Nepal's developer community 🇳🇵

Thank you for contributing!

⭐ If you find NepalPay useful, consider starring the repository.

</div>
