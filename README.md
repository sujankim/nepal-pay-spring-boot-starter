<div align="center">

<img src="https://img.shields.io/badge/Java-17%2B-orange?style=for-the-badge&logo=openjdk"/>
<img src="https://img.shields.io/badge/Spring%20Boot-3.2%2B%20%7C%204.x-6DB33F?style=for-the-badge&logo=springboot"/>
<img src="https://img.shields.io/badge/License-MIT-blue?style=for-the-badge"/>
<img src="https://img.shields.io/badge/JitPack-v0.3.0-brightgreen?style=for-the-badge"/>

# 🇳🇵 NepalPay Spring Boot Starter

**The first production-grade Java library for Nepal payment gateways.**

Supports Khalti, eSewa, and ConnectIPS.  
Works with **Spring Boot 3.2+ and Spring Boot 4.x**.

[Getting Started](#-getting-started) •
[Khalti](#-khalti) •
[eSewa](#-esewa) •
[ConnectIPS](#-connectips) •
[Docs](https://sujankim.github.io/nepal-pay-spring-boot-starter/) •
[Contributing](#-contributing)

</div>

---

## 📦 Getting Started

### Requirements

| Version                 | Java     | Spring Boot      |
| ----------------------- | -------- | ---------------- |
| NepalPay Boot 3 Starter | Java 17+ | Spring Boot 3.2+ |
| NepalPay Boot 4 Starter | Java 21+ | Spring Boot 4.x  |

---

# Step 1 — Add JitPack Repository

## Maven

```xml
<repositories>
    <repository>
        <id>jitpack.io</id>
        <url>https://jitpack.io</url>
    </repository>
</repositories>
```

## Gradle (Groovy)

```gradle
repositories {
    mavenCentral()
    maven { url 'https://jitpack.io' }
}
```

## Gradle (Kotlin DSL)

```kotlin
repositories {
    mavenCentral()
    maven(url = "https://jitpack.io")
}
```

---

# Step 2 — Install NepalPay

## Spring Boot 3.2+ (Java 17+)

### Maven

```xml
<dependency>
    <groupId>com.github.sujankim.nepal-pay-spring-boot-starter</groupId>
    <artifactId>nepal-pay-spring-boot-3-starter</artifactId>
    <version>v0.3.0</version>
</dependency>
```

### Gradle (Groovy)

```gradle
dependencies {
    implementation 'com.github.sujankim.nepal-pay-spring-boot-starter:nepal-pay-spring-boot-3-starter:v0.3.0'
}
```

### Gradle (Kotlin DSL)

```kotlin
dependencies {
    implementation("com.github.sujankim.nepal-pay-spring-boot-starter:nepal-pay-spring-boot-3-starter:v0.3.0")
}
```

---

## Spring Boot 4.x (Java 21+)

### Maven

```xml
<dependency>
    <groupId>com.github.sujankim.nepal-pay-spring-boot-starter</groupId>
    <artifactId>nepal-pay-spring-boot-4-starter</artifactId>
    <version>v0.3.0</version>
</dependency>
```

### Gradle (Groovy)

```gradle
dependencies {
    implementation 'com.github.sujankim.nepal-pay-spring-boot-starter:nepal-pay-spring-boot-4-starter:v0.3.0'
}
```

### Gradle (Kotlin DSL)

```kotlin
dependencies {
    implementation("com.github.sujankim.nepal-pay-spring-boot-starter:nepal-pay-spring-boot-4-starter:v0.3.0")
}
```

---

## Which Starter Should I Use?

| Your Stack                  | Dependency                        |
| --------------------------- | --------------------------------- |
| Spring Boot 3.2+ + Java 17+ | `nepal-pay-spring-boot-3-starter` |
| Spring Boot 4.x + Java 21+  | `nepal-pay-spring-boot-4-starter` |

The public API is identical in both starters.

Internally:

* **Boot 3 Starter** → Jackson 2 (`ObjectMapper`)
* **Boot 4 Starter** → Jackson 3 (`JsonMapper`)

Simply choose the starter that matches your Spring Boot version.

---

# Step 3 — Configure `application.yml`

```yaml
nepalpay:
  khalti:
    secret-key: ${KHALTI_SECRET_KEY}
    return-url: ${KHALTI_RETURN_URL}
    website-url: ${YOUR_WEBSITE_URL}
    sandbox: true

  esewa:
    secret-key: ${ESEWA_SECRET_KEY}
    product-code: ${ESEWA_PRODUCT_CODE}
    success-url: ${ESEWA_SUCCESS_URL}
    failure-url: ${ESEWA_FAILURE_URL}
    sandbox: true

  connectips:
    merchant-id: ${CONNECTIPS_MERCHANT_ID}
    app-id: ${CONNECTIPS_APP_ID}
    app-name: ${CONNECTIPS_APP_NAME}
    app-password: ${CONNECTIPS_APP_PASSWORD}
    pfx-path: ${CONNECTIPS_PFX_PATH}
    pfx-password: ${CONNECTIPS_PFX_PASSWORD}
    sandbox: true
```

---

# Step 4 — Inject and Use

```java
@Service
@RequiredArgsConstructor
public class PaymentService {

    private final KhaltiClient khaltiClient;
    private final EsewaClient esewaClient;
    private final ConnectIpsClient connectIpsClient;
}
```

✅ Zero `@Bean`
✅ Zero `@EnableNepalPay`
✅ Zero configuration classes
✅ Auto-configured via Spring Boot Starter


✅ Zero `@Bean`  
✅ Zero `@EnableNepalPay`  
✅ Zero configuration classes

---

# 💳 Khalti

## 1. Initiate Payment

```java
KhaltiInitiateResponse response = khaltiClient.initiatePayment(
    KhaltiInitiateRequest.builder()
        .amount(10000L) // NPR 100 in paisa (NPR × 100)
        .purchaseOrderId("ORD-001")
        .purchaseOrderName("Pro Plan")
        .build()
);

// Store response.pidx() in DB
// Redirect user to response.paymentUrl()
```

## 2. Verify After Callback

```java
KhaltiLookupResponse lookup =
        khaltiClient.lookupPayment(pidx);

if (lookup.isPaymentSuccessful()) {
    // mark as paid
}

if (!lookup.isAmountValid(expectedPaisa)) {
    // amount tampered!
}
```

---

# 💸 eSewa

## 1. Build Signed Form Payload

```java
String uuid = EsewaClient.generateTransactionUuid();

// Store uuid in DB before returning
EsewaFormPayload payload =
        esewaClient.buildFormPayload(
            new BigDecimal("100.00"),
            uuid
        );

// Return payload to frontend
// Frontend POSTs form to payload.formActionUrl()
```

## 2. Verify After Callback

```java
EsewaClient.EsewaVerificationResult result =
        esewaClient.verifyCallback(data);

if (result.isPaymentSuccessful()) {
    // mark as paid
}
```

Performs:

- Base64 decode
- HMAC verification
- Status API verification

---

# 🏦 ConnectIPS

> Merchant registration required.
> Contact: `connectips@nchl.com.np`

## 1. Build RSA-Signed Form Payload

```java
ConnectIpsFormPayload payload =
        connectIpsClient.buildFormPayload(
            ConnectIpsPaymentRequest.builder()
                .txnId("TXN-" + orderId)
                .amountNPR(100L)
                .referenceId(orderId)
                .remarks("Order payment")
                .build()
        );

// Frontend POSTs form to payload.formActionUrl()
```

`amountNPR()` automatically converts NPR → paisa.

## 2. Validate After Callback

```java
ConnectIpsValidateResponse response =
        connectIpsClient.validateTransaction(
            txnId,
            referenceId,
            txnAmtPaisa
        );

if (response.isPaymentSuccessful()) {
    // mark as paid
}
```

---

# ⚙️ Configuration Reference

## Khalti (`nepalpay.khalti.*`)

| Property | Required | Default | Description |
|----------|-----------|----------|--------------|
| secret-key | ✅ | — | Creates `KhaltiClient` bean |
| return-url | ✅ | — | Redirect URL after payment |
| website-url | ✅ | — | Merchant website URL |
| sandbox | ❌ | true | Sandbox or Production |

---

## eSewa (`nepalpay.esewa.*`)

| Property | Required | Default | Description |
|----------|-----------|----------|--------------|
| secret-key | ✅ | — | Creates `EsewaClient` bean |
| product-code | ✅ | — | `EPAYTEST` or production code |
| success-url | ✅ | — | Redirect on success |
| failure-url | ✅ | — | Redirect on failure |
| sandbox | ❌ | true | Sandbox or Production |

---

## ConnectIPS (`nepalpay.connectips.*`)

| Property | Required | Default | Description |
|----------|-----------|----------|--------------|
| merchant-id | ✅ | — | NCHL merchant ID |
| app-id | ✅ | — | Application ID |
| app-name | ✅ | — | Application name |
| app-password | ✅ | — | Basic Auth password |
| pfx-path | ✅ | — | Path to `CREDITOR.pfx` |
| pfx-password | ✅ | — | PFX password |
| sandbox | ❌ | true | UAT or Production |

---

# 🧪 Sandbox Credentials

## eSewa Sandbox

| Field | Value |
|-------|--------|
| eSewa ID | 9806800001 |
| Password | Nepal@123 |
| MPIN | 1122 |
| Token | 123456 |
| Secret Key | 8gBm/:&EnhH.1/q |
| Product Code | EPAYTEST |

## Khalti Sandbox

Get your test secret key from:

https://test-admin.khalti.com

---

# 🔐 Security Rules

```java
// ✅ ALWAYS verify server-side
khaltiClient.lookupPayment(pidx)
            .isPaymentSuccessful();

esewaClient.verifyCallback(data)
           .isPaymentSuccessful();

connectIpsClient
        .validateTransaction(txnId, refId, amt)
        .isPaymentSuccessful();

// ✅ ALWAYS store identifiers before redirect
orderRepo.savePidx(orderId, response.pidx());
orderRepo.saveUuid(orderId, uuid);
orderRepo.saveTxnId(orderId, txnId);

// ❌ NEVER trust redirect parameters
// ❌ NEVER commit secrets or .pfx files
// ❌ NEVER expose secret keys to frontend
```

---

# 🗺️ Module Structure

```text
nepal-pay-spring-boot-starter/
├── nepal-pay-core/
│   └── io.nepalpay.core.*
│
├── nepal-pay-spring-boot-3-starter/
│   └── io.nepalpay.*
│
└── nepal-pay-spring-boot-4-starter/
    └── io.nepalpay.*
```

---

# 🗺️ Roadmap

| Feature | Status |
|---------|---------|
| Khalti | ✅ v0.1.0 |
| eSewa | ✅ v0.1.0 |
| ConnectIPS | ✅ v0.2.0 |
| Spring Boot 3.2+ Support | ✅ v0.3.0 |
| Fonepay | 🔲 Planned |
| Khalti Refund API | 🔲 Planned |
| Maven Central | 🔲 Planned |

---

# 🤝 Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md).

---

# 📜 License

MIT — see [LICENSE](LICENSE).

---

# 👤 Author

**Sujan Lamichhane**

🌐 https://sujanlamichhane.com.np

---

<div align="center">

## 📖 Full Documentation

https://sujankim.github.io/nepal-pay-spring-boot-starter/

Built with ❤️ for Nepal's developer community 🇳🇵

⭐ Give it a star if it saved you time!

</div>
