# Contributing to NepalPay Spring Boot Starter

Thank you for your interest in contributing to **NepalPay Spring Boot Starter**! 🇳🇵

> The first production-grade Java library for Nepal payment gateways, including Khalti, eSewa, ConnectIPS, and Fonepay.

We welcome contributions of all sizes, including:

* 🐛 Bug fixes
* ✨ New features
* 📚 Documentation improvements
* 🧪 Tests and examples
* 💡 Suggestions and ideas

---

# 🚀 Local Setup

## 1. Fork the Repository

Fork this repository on GitHub.

## 2. Clone Your Fork

```bash
git clone https://github.com/YOUR_USERNAME/nepal-pay-spring-boot-starter.git
cd nepal-pay-spring-boot-starter
```

## 3. Build the Project

```bash
mvn clean install
```

## 4. Run Tests

```bash
mvn clean test
```

✅ All tests must pass before submitting a Pull Request.

---

# 📋 How to Contribute

## 🐛 Reporting Bugs

Please open an issue using the following format:

**Title**

```text
[BUG] Short description
```

**Include the following information:**

* Java version
* Spring Boot version
* Operating system
* Error message or stack trace
* Steps to reproduce
* Expected behavior
* Actual behavior

---

## ✨ Requesting Features

Please open an issue using the following format:

**Title**

```text
[FEATURE] Short description
```

Include:

* Problem statement
* Proposed solution
* Example use case
* Relevant payment gateway documentation (if applicable)

---

# 🔀 Submitting a Pull Request

## 1. Create a Branch

```bash
git checkout -b feature/your-feature-name
```

Examples:

```bash
git checkout -b feature/connectips-support
git checkout -b fix/khalti-refund-bug
git checkout -b docs/improve-readme
```

---

## 2. Write Code and Tests

All new features should include:

* Unit tests
* Integration tests (when applicable)
* Documentation updates

---

## 3. Run the Test Suite

```bash
mvn clean test
```

All tests must be green ✅

---

## 4. Commit Your Changes

Examples:

```bash
git commit -m "feat: add ConnectIPS integration"
git commit -m "fix: handle Khalti timeout exception"
git commit -m "docs: improve README examples"
```

We follow the Conventional Commits specification:

* `feat:`
* `fix:`
* `docs:`
* `refactor:`
* `test:`
* `chore:`

---

## 5. Push Your Branch

```bash
git push origin feature/your-feature-name
```

---

## 6. Open a Pull Request

Open a Pull Request against the `main` branch.

Please include:

* What changed
* Why it changed
* Screenshots (if applicable)
* Related issue number

Example:

```text
Closes #42
```

---

# 🗺️ Roadmap — What to Contribute

| Priority  | Feature                 | Description                              |
| --------- | ----------------------- | ---------------------------------------- |
| 🔴 High   | ConnectIPS Integration  | Bank-linked payments                     |
| 🔴 High   | Fonepay Integration     | QR-based payments                        |
| 🟡 Medium | Refund Support (Khalti) | `KhaltiClient.refundPayment()`           |
| 🟡 Medium | Retry Logic             | Auto-retry on transient errors           |
| 🟢 Low    | Webhook Support         | Receive push notifications from gateways |
| 🟢 Low    | Spring WebFlux Support  | Reactive/non-blocking clients            |

---

# ✅ Code Standards

## Java

* Use **Java 21** features where appropriate.
* Prefer **Java Records** for DTOs and models.
* Use **constructor injection**.
* Keep APIs immutable whenever possible.

## Logging

Use Lombok:

```java
@Slf4j
```

Avoid:

```java
private static final Logger log = LoggerFactory.getLogger(...);
```

## Documentation

* All public classes must have Javadoc.
* All public methods must have Javadoc.
* Public APIs should include usage examples whenever possible.

## Testing

* All new features must include tests.
* Prefer `MockWebServer` for gateway API testing.
* Do not use real payment credentials in tests.
* Tests must not depend on internet access.

## Package Structure

Please follow the existing package structure:

```text
io.nepalpay
├── autoconfigure
├── config
├── common
├── khalti
├── esewa
├── connectips
├── fonepay
└── exception
```

---

# 🔐 Security

Please do **not** open public issues for security vulnerabilities.

Instead, contact:

**Sujan Lamichhane**

📧 [sujan.officals@email.com](mailto:sujan.officals@email.com)

---

# 📜 License

By contributing to this project, you agree that your contributions will be licensed under the **[MIT License](LICENSE)**.

---

# ❤️ Thank You

Every contribution, no matter how small, helps improve the Java ecosystem for Nepal's digital payment infrastructure.

**Happy Coding! 🇳🇵**
