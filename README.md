<div align="center">

<img src="https://img.shields.io/badge/Java-17%2B-orange?style=for-the-badge&logo=openjdk"/>
<img src="https://img.shields.io/badge/Spring%20Boot-3.2%2B%20%7C%204.x-6DB33F?style=for-the-badge&logo=springboot"/>
<img src="https://img.shields.io/badge/License-MIT-blue?style=for-the-badge"/>
<img src="https://img.shields.io/badge/JitPack-v0.4.0-brightgreen?style=for-the-badge"/>
<img src="https://img.shields.io/badge/Tests-80%2B%20passing-success?style=for-the-badge"/>

# 🇳🇵 NepalPay Spring Boot Starter

**The first production-grade Java library for Nepal payment gateways.**

Khalti · eSewa · ConnectIPS · Fonepay

Works with **Spring Boot 3.2+** and **Spring Boot 4.x**.

[Getting Started](#-getting-started) •
[Khalti](#-khalti) •
[eSewa](#-esewa) •
[Fonepay](#-fonepay) •
[ConnectIPS](#-connectips) •
[📖 Full Docs](https://sujankim.github.io/nepal-pay-spring-boot-starter/)

</div>

---

## 🚀 Getting Started

### Step 1 — Add JitPack repository

**Maven** (`pom.xml`):
```xml
<repositories>
    <repository>
        <id>jitpack.io</id>
        <url>https://jitpack.io</url>
    </repository>
</repositories>
```

**Gradle Groovy** (`settings.gradle`):
```groovy
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
        maven { url 'https://jitpack.io' }
    }
}
```

**Gradle Kotlin** (`settings.gradle.kts`):
```kotlin
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}
```

### Step 2 — Add dependency for your Spring Boot version

| Your Spring Boot | Java | Artifact |
|---|---|---|
| 3.2+ | 17+ | `nepal-pay-spring-boot-3-starter` |
| 4.x | 21+ | `nepal-pay-spring-boot-4-starter` |

**Maven — Spring Boot 3.2+:**
```xml
<dependency>
    <groupId>com.github.sujankim.nepal-pay-spring-boot-starter</groupId>
    <artifactId>nepal-pay-spring-boot-3-starter</artifactId>
    <version>v0.4.0</version>
</dependency>
```

**Maven — Spring Boot 4.x:**
```xml
<dependency>
    <groupId>com.github.sujankim.nepal-pay-spring-boot-starter</groupId>
    <artifactId>nepal-pay-spring-boot-4-starter</artifactId>
    <version>v0.4.0</version>
</dependency>
```

**Gradle Groovy — Spring Boot 3.2+:**
```groovy
implementation 'com.github.sujankim.nepal-pay-spring-boot-starter:nepal-pay-spring-boot-3-starter:v0.4.0'
```

**Gradle Groovy — Spring Boot 4.x:**
```groovy
implementation 'com.github.sujankim.nepal-pay-spring-boot-starter:nepal-pay-spring-boot-4-starter:v0.4.0'
```

**Gradle Kotlin — Spring Boot 4.x:**
```kotlin
implementation("com.github.sujankim.nepal-pay-spring-boot-starter:nepal-pay-spring-boot-4-starter:v0.4.0")
```

> The public API is **identical** in both starters.
> Only the internal Jackson version differs.

### Step 3 — Configure

```yaml
nepalpay:
  khalti:
    secret-key: ${KHALTI_SECRET_KEY}
    return-url:  ${KHALTI_RETURN_URL}
    website-url: ${YOUR_WEBSITE_URL}
    sandbox: true

  esewa:
    secret-key:   ${ESEWA_SECRET_KEY}
    product-code: ${ESEWA_PRODUCT_CODE}
    success-url:  ${ESEWA_SUCCESS_URL}
    failure-url:  ${ESEWA_FAILURE_URL}
    sandbox: true

  fonepay:
    merchant-code: ${FONEPAY_MERCHANT_CODE}
    secret-key:    ${FONEPAY_SECRET_KEY}
    return-url:    ${FONEPAY_RETURN_URL}
    sandbox: true
```

### Step 4 — Inject and use

```java
@Service
@RequiredArgsConstructor
public class PaymentService {

    private final KhaltiClient     khaltiClient;     // ← auto-injected
    private final EsewaClient      esewaClient;      // ← auto-injected
    private final FonepayClient    fonepayClient;    // ← auto-injected
    private final ConnectIpsClient connectIpsClient; // ← auto-injected
}
```

Zero `@Bean`. Zero `@EnableNepalPay`. Zero config class.

---

## 💳 Khalti

```java
// 1. Initiate — amount in PAISA (NPR × 100)
KhaltiInitiateResponse res = khaltiClient.initiatePayment(
    KhaltiInitiateRequest.builder()
        .amount(10000L)              // NPR 100 = 10000 paisa
        .purchaseOrderId("ORD-001")
        .purchaseOrderName("Pro Plan")
        .build()
);
// Save res.pidx() to DB, then redirect user to res.paymentUrl()

// 2. Verify after callback — ALWAYS do this
KhaltiLookupResponse lookup = khaltiClient.lookupPayment(pidx);
if (lookup.isPaymentSuccessful()) { /* mark as paid */ }
if (!lookup.isAmountValid(expectedPaisa)) { /* tampered! */ }
```

---

## 💸 eSewa

```java
// 1. Build signed form payload — amount in NPR as BigDecimal
String uuid = EsewaClient.generateTransactionUuid();
// Save uuid to DB, then return payload to frontend
EsewaFormPayload payload = esewaClient.buildFormPayload(
    new BigDecimal("100.00"),
    uuid
);
// Frontend POSTs form fields to payload.formActionUrl()

// 2. Verify after callback — ALWAYS do this
// verifyCallback() = Base64 decode + HMAC verify + status API
EsewaClient.EsewaVerificationResult result =
    esewaClient.verifyCallback(data);
if (result.isPaymentSuccessful()) { /* mark as paid */ }
```

---

## 🔵 Fonepay

```java
// 1. Build signed redirect URL — amount in NPR as double
String prn = "FP-" + orderId;
// Save prn to DB, then redirect user to redirectUrl
FonepayRedirectParams params = fonepayClient.buildRedirectParams(
    FonepayPaymentRequest.builder()
        .prn(prn)
        .amount(100.0)               // NPR 100 = 100.0 (not paisa!)
        .remarks1("Pro Plan")
        .build()
);
// Frontend: window.location.href = params.redirectUrl()

// 2. Verify after callback — ALWAYS do this
// verifyCallback() = re-compute HMAC-SHA512 + verify DV signature
FonepayCallbackResponse callback =
    FonepayCallbackResponse.of(prn, pid, ps, rc, uid, bc, ini, pAmt, rAmt, dv);
FonepayClient.FonepayVerificationResult result =
    fonepayClient.verifyCallback(callback);
if (result.isPaymentSuccessful()) { /* mark as paid */ }
```

---

## 🏦 ConnectIPS

> **Merchant registration required.** Contact connectips@nchl.com.np.

```java
// 1. Build RSA-signed form payload
ConnectIpsFormPayload payload = connectIpsClient.buildFormPayload(
    ConnectIpsPaymentRequest.builder()
        .txnId("TXN-" + orderId)
        .amountNPR(100L)             // auto-converts to paisa
        .referenceId(orderId)
        .build()
);
// Frontend POSTs form to payload.formActionUrl()

// 2. Validate after callback — ALWAYS do this
ConnectIpsValidateResponse res =
    connectIpsClient.validateTransaction(txnId, referenceId, txnAmtPaisa);
if (res.isPaymentSuccessful()) { /* mark as paid */ }
```

---

## ⚠️ Amount Units

| Gateway | Unit | Java type | Example |
|---------|------|-----------|---------|
| Khalti | Paisa | `long` | `10000L` for NPR 100 |
| eSewa | NPR | `BigDecimal` | `new BigDecimal("100.00")` |
| Fonepay | NPR | `double` | `100.0` |
| ConnectIPS | Paisa | `long` (via `amountNPR()`) | `amountNPR(100L)` |

---

## ⚙️ Configuration Reference

### Khalti (`nepalpay.khalti.*`)

| Property | Required | Default | Description |
|---|:---:|:---:|---|
| `secret-key` | ✅ | — | Creates `KhaltiClient` bean when present |
| `return-url` | ✅ | — | Khalti redirect URL after payment |
| `website-url` | ✅ | — | Your merchant website URL |
| `sandbox` | — | `true` | `true` = sandbox · `false` = production |

### eSewa (`nepalpay.esewa.*`)

| Property | Required | Default | Description |
|---|:---:|:---:|---|
| `secret-key` | ✅ | — | Creates `EsewaClient` bean when present |
| `product-code` | ✅ | — | Sandbox: `EPAYTEST` · Production: your code |
| `success-url` | ✅ | — | Redirect on success |
| `failure-url` | ✅ | — | Redirect on failure |
| `sandbox` | — | `true` | `true` = sandbox · `false` = production |

### Fonepay (`nepalpay.fonepay.*`)

| Property | Required | Default | Description |
|---|:---:|:---:|---|
| `merchant-code` | ✅ | — | Your Fonepay PID |
| `secret-key` | ✅ | — | Creates `FonepayClient` bean when present |
| `return-url` | ✅ | — | Fonepay redirect URL after payment |
| `sandbox` | — | `true` | `true` = dev.fonepay.com · `false` = fonepay.com |

### ConnectIPS (`nepalpay.connectips.*`)

| Property | Required | Default | Description |
|---|:---:|:---:|---|
| `merchant-id` | ✅ | — | NCHL merchant ID |
| `app-id` | ✅ | — | Application ID from NCHL |
| `app-name` | ✅ | — | Application name |
| `app-password` | ✅ | — | Application password |
| `pfx-path` | ✅ | — | Path to CREDITOR.pfx |
| `pfx-password` | ✅ | — | Password for .pfx |
| `sandbox` | — | `true` | `true` = UAT · `false` = production |

---

## 🔐 Security

```java
// ✅ ALWAYS verify server-side after redirect
khaltiClient.lookupPayment(pidx).isPaymentSuccessful()
esewaClient.verifyCallback(data).isPaymentSuccessful()
fonepayClient.verifyCallback(callback).isPaymentSuccessful()
connectIpsClient.validateTransaction(txnId, refId, amt).isPaymentSuccessful()

// ✅ ALWAYS store identifiers BEFORE redirecting the user
orderRepo.savePidx(orderId, response.pidx());    // Khalti
orderRepo.saveUuid(orderId, uuid);               // eSewa
orderRepo.savePrn(orderId, prn);                 // Fonepay
orderRepo.saveTxnId(orderId, txnId);             // ConnectIPS

// ❌ NEVER trust redirect URL parameters alone
// ❌ NEVER hardcode secret keys
// ❌ NEVER put keys in frontend code
// ❌ NEVER commit CREDITOR.pfx to Git
```

---

## 🗺️ Supported Gateways

| Gateway | Status | Notes |
|---------|:---:|---|
| Khalti | ✅ v0.1.0 | Self-service sandbox |
| eSewa | ✅ v0.1.0 | Self-service sandbox |
| ConnectIPS | ✅ v0.2.0 | NCHL merchant registration required |
| Fonepay | ✅ v0.4.0 | Integration via bank/Fonepay |

---

## 🏗️ Module Structure

```
nepal-pay-spring-boot-starter/
├── nepal-pay-core/                     ← Pure Java 17. Zero Spring.
│   └── io.nepalpay.core.*              ← All models, exceptions, enums
├── nepal-pay-spring-boot-3-starter/    ← Spring Boot 3.2+, Java 17
│   └── io.nepalpay.*                   ← Clients + auto-config (Jackson 2)
└── nepal-pay-spring-boot-4-starter/    ← Spring Boot 4.x, Java 21
    └── io.nepalpay.*                   ← Clients + auto-config (Jackson 3)
```

---

## 🤝 Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md) for setup, code standards,
and how to add a new gateway.

**Priority contributions:**
- Refund API for Khalti
- Retry logic with backoff
- Spring WebFlux support
- Maven Central publishing

---

## 📜 License

MIT — see [LICENSE](LICENSE).

---

## 👤 Author

**Sujan Lamichhane** 
- [sujanlamichhane.com.np](https://sujanlamichhane.com.np)
- [dev.to](https://dev.to/sujankim)
- [@sujankim](https://github.com/sujankim)

---

<div align="center">

**📖 [Full Documentation](https://sujankim.github.io/nepal-pay-spring-boot-starter/)**

Built with ❤️ for Nepal's developer community 🇳🇵

**If NepalPay saved you time, give it a ⭐!**

</div>