<div align="center">

<img src="https://img.shields.io/badge/Java-17%2B-orange?style=for-the-badge&logo=openjdk"/>
<img src="https://img.shields.io/badge/Spring%20Boot-3.2%2B%20%7C%204.x-6DB33F?style=for-the-badge&logo=springboot"/>
<img src="https://img.shields.io/badge/License-MIT-blue?style=for-the-badge"/>
<img src="https://img.shields.io/badge/JitPack-v0.6.0-brightgreen?style=for-the-badge"/>
<img src="https://img.shields.io/badge/Tests-350%2B%20passing-success?style=for-the-badge"/>

# 🇳🇵 NepalPay Spring Boot Starter

**The first production-grade Java library for Nepal payment gateways.**

Khalti · eSewa · ConnectIPS · Fonepay

Works with **Spring Boot 3.2+** and **Spring Boot 4.x**.

[Getting Started](#-getting-started) •
[Khalti](#-khalti) •
[eSewa](#-esewa) •
[Fonepay](#-fonepay) •
[ConnectIPS](#-connectips) •
[Retry](#-retry-with-exponential-backoff)

</div>

---

# 🚀 Getting Started

## Step 1 — Add JitPack Repository

### Maven (`pom.xml`)

```xml
<repositories>
    <repository>
        <id>jitpack.io</id>
        <url>https://jitpack.io</url>
    </repository>
</repositories>
````

### Gradle Groovy (`settings.gradle`)

```groovy
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
        maven { url 'https://jitpack.io' }
    }
}
```

### Gradle Kotlin (`settings.gradle.kts`)

```kotlin
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}
```

---

## Step 2 — Add Dependency

| Spring Boot | Java | Artifact                          |
| ----------- | ---- | --------------------------------- |
| 3.2+        | 17+  | `nepal-pay-spring-boot-3-starter` |
| 4.x         | 21+  | `nepal-pay-spring-boot-4-starter` |

### Maven — Spring Boot 3.2+

```xml
<dependency>
    <groupId>com.github.sujankim.nepal-pay-spring-boot-starter</groupId>
    <artifactId>nepal-pay-spring-boot-3-starter</artifactId>
    <version>v0.6.0</version>
</dependency>
```

### Maven — Spring Boot 4.x

```xml
<dependency>
    <groupId>com.github.sujankim.nepal-pay-spring-boot-starter</groupId>
    <artifactId>nepal-pay-spring-boot-4-starter</artifactId>
    <version>v0.6.0</version>
</dependency>
```

### Gradle Groovy — Spring Boot 3.2+

```groovy
implementation 'com.github.sujankim.nepal-pay-spring-boot-starter:nepal-pay-spring-boot-3-starter:v0.6.0'
```

### Gradle Groovy — Spring Boot 4.x

```groovy
implementation 'com.github.sujankim.nepal-pay-spring-boot-starter:nepal-pay-spring-boot-4-starter:v0.6.0'
```

### Gradle Kotlin — Spring Boot 4.x

```kotlin
implementation("com.github.sujankim.nepal-pay-spring-boot-starter:nepal-pay-spring-boot-4-starter:v0.6.0")
```

> The public API is identical in both starters. Only the internal Jackson version differs (Jackson 2 for Boot 3, Jackson 3 for Boot 4).

---

## Step 3 — Configure

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

  fonepay:
    merchant-code: ${FONEPAY_MERCHANT_CODE}
    secret-key: ${FONEPAY_SECRET_KEY}
    return-url: ${FONEPAY_RETURN_URL}
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

## Step 4 — Inject and Use

```java
@Service
@RequiredArgsConstructor
public class PaymentService {

    private final KhaltiClient khaltiClient;
    private final EsewaClient esewaClient;
    private final FonepayClient fonepayClient;
    private final ConnectIpsClient connectIpsClient;
}
```

> ✅ Zero `@Bean`
>
> ✅ Zero `@EnableNepalPay`
>
> ✅ Zero configuration classes

---

# 💳 Khalti

```java
// 1. Initiate — amount in PAISA (NPR × 100)
KhaltiInitiateResponse res = khaltiClient.initiatePayment(
    KhaltiInitiateRequest.builder()
        .amount(10000L)
        .purchaseOrderId("ORD-001")
        .purchaseOrderName("Pro Plan")
        .build()
);

// Save res.pidx() to DB
// Redirect user to res.paymentUrl()

// 2. Verify after callback
KhaltiLookupResponse lookup = khaltiClient.lookupPayment(pidx);

if (lookup.isPaymentSuccessful()) {
    // mark as paid
}

if (!lookup.isAmountValid(expectedPaisa)) {
    // tampered
}

// 3. Refund
KhaltiRefundResponse refund =
    khaltiClient.refundPayment(lookup.transactionId());

// Partial refund
KhaltiRefundResponse partial =
    khaltiClient.refundPayment(
        lookup.transactionId(),
        5000L
    );

if (refund.isRefundSuccessful()) {
    // mark as refunded
}
```

> ⚠️ Refund uses `transactionId`, not `pidx`.

---

# 💸 eSewa

```java
String uuid = EsewaClient.generateTransactionUuid();

EsewaFormPayload payload =
    esewaClient.buildFormPayload(
        new BigDecimal("100.00"),
        uuid
    );

EsewaClient.EsewaVerificationResult result =
    esewaClient.verifyCallback(data);

if (result.isPaymentSuccessful()) {
    // mark as paid
}
```

---

# 🔵 Fonepay

```java
String prn = "FP-" + orderId;

FonepayRedirectParams params =
    fonepayClient.buildRedirectParams(
        FonepayPaymentRequest.builder()
            .prn(prn)
            .amount(100.0)
            .remarks1("Pro Plan")
            .build()
    );

FonepayCallbackResponse callback =
    FonepayCallbackResponse.of(
        prn,
        pid,
        ps,
        rc,
        uid,
        bc,
        ini,
        pAmt,
        rAmt,
        dv
    );

FonepayClient.FonepayVerificationResult result =
    fonepayClient.verifyCallback(callback);

if (result.isPaymentSuccessful()) {
    // mark as paid
}
```

---

# 🏦 ConnectIPS

```java
ConnectIpsFormPayload payload =
    connectIpsClient.buildFormPayload(
        ConnectIpsPaymentRequest.builder()
            .txnId("TXN-" + orderId)
            .amountNPR(100L)
            .referenceId(orderId)
            .build()
    );

ConnectIpsValidateResponse res =
    connectIpsClient.validateTransaction(
        txnId,
        referenceId,
        txnAmtPaisa
    );

if (res.isPaymentSuccessful()) {
    // mark as paid
}
```

---

# 🔁 Retry with Exponential Backoff

```yaml
nepalpay:
  khalti:
    retry:
      enabled: true
      max-attempts: 3
      initial-delay-ms: 500
      multiplier: 2.0
      max-delay-ms: 5000
```

| Gateway    | Retry Applies To                                      |
| ---------- | ----------------------------------------------------- |
| Khalti     | ✅ initiatePayment(), lookupPayment(), refundPayment() |
| eSewa      | ✅ checkStatus()                                       |
| ConnectIPS | ✅ validateTransaction()                               |
| Fonepay    | ❌ No HTTP calls                                       |

**Retries:** 5xx errors and network timeouts

**No Retries:** 4xx errors and signature failures

---

# ⚠️ Amount Units

| Gateway    | Unit  | Java Type    | Example                    |
| ---------- | ----- | ------------ | -------------------------- |
| Khalti     | Paisa | `long`       | `10000L`                   |
| eSewa      | NPR   | `BigDecimal` | `new BigDecimal("100.00")` |
| Fonepay    | NPR   | `double`     | `100.0`                    |
| ConnectIPS | Paisa | `long`       | `amountNPR(100L)`          |

---

# 🔐 Security

```java
khaltiClient.lookupPayment(pidx).isPaymentSuccessful();
esewaClient.verifyCallback(data).isPaymentSuccessful();
fonepayClient.verifyCallback(callback).isPaymentSuccessful();
connectIpsClient
    .validateTransaction(txnId, refId, amt)
    .isPaymentSuccessful();
```

```java
orderRepo.savePidx(orderId, response.pidx());
orderRepo.saveUuid(orderId, uuid);
orderRepo.savePrn(orderId, prn);
orderRepo.saveTxnId(orderId, txnId);
```

❌ Never trust redirect URL parameters alone.

❌ Never hardcode secret keys.

❌ Never put keys in frontend code.

❌ Never commit `CREDITOR.pfx` to Git.

---

# 🗺️ Supported Gateways

| Gateway    | Status   | Notes                                   |
| ---------- | -------- | --------------------------------------- |
| Khalti     | ✅ v0.5.0 | Initiate, lookup, full + partial refund |
| eSewa      | ✅ v0.1.0 | Self-service sandbox                    |
| ConnectIPS | ✅ v0.2.0 | NCHL merchant registration required     |
| Fonepay    | ✅ v0.4.0 | Integration via bank/Fonepay            |

---

# 🏗️ Module Structure

```text
nepal-pay-spring-boot-starter/
├── nepal-pay-core/
│   └── io.nepalpay.core.*
├── nepal-pay-spring-boot-3-starter/
│   └── io.nepalpay.*
└── nepal-pay-spring-boot-4-starter/
    └── io.nepalpay.*
```

---

# 🤝 Contributing

See [CONTRIBUTING.md](Contributing.md) for setup, code standards, and how to add a new gateway.

Open issues:

* #4 Kotlin code examples
* #6 Spring WebFlux support

---

# 📜 License

MIT — see [LICENSE](LICENSE).

---

# 👤 Author

**Sujan Lamichhane**

- [sujanlamichhane.com.np](https://sujanlamichhane.com.np)
- [dev.to](https://dev.to/sujankim)
- [@sujankim](https://github.com/sujankim)

---

<div align="center">

📖 [Full Documentation](https://sujankim.github.io/nepal-pay-spring-boot-starter/index.html)

Built with ❤️ for Nepal's developer community 🇳🇵

If NepalPay saved you time, give it a ⭐!

</div>
