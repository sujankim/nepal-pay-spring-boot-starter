<div align="center">

<img src="https://img.shields.io/badge/Java-17%2B-orange?style=for-the-badge&logo=openjdk"/>
<img src="https://img.shields.io/badge/Spring%20Boot-3.2%2B%20%7C%204.x-6DB33F?style=for-the-badge&logo=springboot"/>
<img src="https://img.shields.io/badge/WebFlux-Reactive-6DB33F?style=for-the-badge&logo=spring"/>
<img src="https://img.shields.io/maven-central/v/io.github.sujankim/nepal-pay-spring-boot-3-starter?style=for-the-badge&label=Maven%20Central"/>
<img src="https://img.shields.io/badge/License-MIT-blue?style=for-the-badge"/>
<img src="https://img.shields.io/badge/Tests-400%2B%20passing-success?style=for-the-badge"/>

# 🇳🇵 NepalPay Spring Boot Starter

**The first production-grade Java library for Nepal payment gateways.**

**Khalti · eSewa · ConnectIPS · Fonepay**

Works with **Spring Boot 3.2+**, **Spring Boot 4.x**, and **Spring WebFlux**.

[Getting Started](#-getting-started) •
[Khalti](#-khalti) •
[eSewa](#-esewa) •
[Fonepay](#-fonepay) •
[ConnectIPS](#-connectips) •
[Reactive](#-reactive-webclient) •
[Retry](#-retry-with-exponential-backoff)

</div>

---

# 🚀 Getting Started

## No repository block needed — available on Maven Central

## Step 1 — Choose Your Starter

| Use Case | Starter Artifact | Spring Boot | Java |
|---|---|---|---|
| Blocking (RestClient) | `nepal-pay-spring-boot-3-starter` | 3.2+ | 17+ |
| Blocking (RestClient) | `nepal-pay-spring-boot-4-starter` | 4.x | 21+ |
| **Reactive (WebClient)** | **`nepal-pay-spring-boot-reactive-starter`** | **3.2+** | **17+** |

> ✅ Blocking and reactive starters share the same `nepalpay.*` configuration.
> Simply change the artifact name to migrate from blocking to reactive.

---

## Step 2 — Add Dependency

### Maven — Spring Boot 3.2+ (Blocking)

```xml
<dependency>
    <groupId>io.github.sujankim</groupId>
    <artifactId>nepal-pay-spring-boot-3-starter</artifactId>
    <version>1.1.1</version>
</dependency>
```

### Maven — Spring Boot 4.x (Blocking)

```xml
<dependency>
    <groupId>io.github.sujankim</groupId>
    <artifactId>nepal-pay-spring-boot-4-starter</artifactId>
    <version>1.1.1</version>
</dependency>
```

### Maven — Spring WebFlux (Reactive)

```xml
<dependency>
    <groupId>io.github.sujankim</groupId>
    <artifactId>nepal-pay-spring-boot-reactive-starter</artifactId>
    <version>1.1.1</version>
</dependency>
```

### Gradle (Groovy)

#### Blocking

```groovy
implementation 'io.github.sujankim:nepal-pay-spring-boot-3-starter:1.1.1'
```

#### Reactive

```groovy
implementation 'io.github.sujankim:nepal-pay-spring-boot-reactive-starter:1.1.1'
```

### Gradle (Kotlin)

```kotlin
implementation("io.github.sujankim:nepal-pay-spring-boot-reactive-starter:1.1.1")
```

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

> ✅ The exact same YAML works for both blocking and reactive starters.

---

## Step 4 — Inject and Use

### Blocking

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

### Reactive

```java
@Service
@RequiredArgsConstructor
public class ReactivePaymentService {

    private final KhaltiReactiveClient khaltiReactiveClient;
    private final EsewaReactiveClient esewaReactiveClient;
    private final ConnectIpsReactiveClient connectIpsReactiveClient;

    // FonepayClient from blocking starter
    private final FonepayClient fonepayClient;

}
```

✅ Zero `@Bean`

✅ Zero `@EnableNepalPay`

✅ Zero configuration classes

---

# ⚡ Reactive (WebClient)

The reactive starter returns `Mono<>` responses using Spring WebFlux `WebClient`.

Perfect for reactive controllers, functional routers, and event-driven applications.

---

## Khalti — Reactive

```java
// Initiate payment
khaltiReactiveClient.initiatePayment(
    KhaltiInitiateRequest.builder()
        .amount(10000L)
        .purchaseOrderId("ORD-001")
        .purchaseOrderName("Pro Plan")
        .build()
)
.doOnNext(res -> orderRepo.savePidx(orderId, res.pidx()))
.map(KhaltiInitiateResponse::paymentUrl);

// Verify payment
khaltiReactiveClient.lookupPayment(pidx)
    .filter(KhaltiLookupResponse::isPaymentSuccessful)
    .flatMap(lookup -> orderRepo.markPaid(orderId, lookup.transactionId()));

// Full refund
khaltiReactiveClient.refundPayment(transactionId)
    .filter(KhaltiRefundResponse::isRefundSuccessful)
    .flatMap(r -> orderRepo.markRefunded(orderId));

// Partial refund
khaltiReactiveClient.refundPayment(transactionId, 5000L);
```

---

## eSewa — Reactive

```java
String uuid = EsewaReactiveClient.generateTransactionUuid();

EsewaFormPayload payload =
    esewaReactiveClient.buildFormPayload(
        new BigDecimal("100.00"),
        uuid
    );

esewaReactiveClient.verifyCallback(data)
    .filter(EsewaReactiveClient.EsewaVerificationResult::isPaymentSuccessful)
    .flatMap(result ->
        orderRepo.markPaid(result.callbackData().transactionUuid()));

esewaReactiveClient.checkStatus(uuid, "100.00")
    .filter(EsewaStatusResponse::isPaymentSuccessful)
    .flatMap(res -> orderRepo.markPaid(uuid));
```

---

## ConnectIPS — Reactive

```java
ConnectIpsFormPayload payload =
    connectIpsReactiveClient.buildFormPayload(
        ConnectIpsPaymentRequest.builder()
            .txnId("TXN-" + orderId)
            .amountNPR(100L)
            .referenceId(orderId)
            .build()
    );

connectIpsReactiveClient.validateTransaction(
        txnId,
        referenceId,
        txnAmtPaisa
    )
    .filter(ConnectIpsValidateResponse::isPaymentSuccessful)
    .flatMap(res -> orderRepo.markPaid(referenceId));
```

---

## Fonepay in Reactive Applications

Fonepay performs **no server-side HTTP calls**, so simply use the blocking client.

```java
FonepayRedirectParams params =
    fonepayClient.buildRedirectParams(
        FonepayPaymentRequest.builder()
            .prn("FP-" + orderId)
            .amount(100.0)
            .remarks1("Pro Plan")
            .build()
    );
```

---

## Reactive Retry

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

```java
khaltiReactiveClient
    .initiatePayment(request)
    .onErrorResume(KhaltiException.class, Mono::error);
```

Retry is automatically applied using Reactor's `Retry.backoff()`.

Retries:

- ✅ 5xx server errors
- ✅ Network failures

Never retries:

- ❌ 4xx client errors

---

# 💳 Khalti

```java
KhaltiInitiateResponse response =
    khaltiClient.initiatePayment(
        KhaltiInitiateRequest.builder()
            .amount(10000L)
            .purchaseOrderId("ORD-001")
            .purchaseOrderName("Pro Plan")
            .build()
    );

KhaltiLookupResponse lookup =
    khaltiClient.lookupPayment(pidx);

if (lookup.isPaymentSuccessful()) {
    // mark paid
}

if (!lookup.isAmountValid(expectedPaisa)) {
    // reject payment
}

KhaltiRefundResponse refund =
    khaltiClient.refundPayment(lookup.transactionId());

KhaltiRefundResponse partial =
    khaltiClient.refundPayment(
        lookup.transactionId(),
        5000L
    );
```

> ⚠️ Refunds require the **transactionId**, not the `pidx`.

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
    // mark paid
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
    // mark paid
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

ConnectIpsValidateResponse response =
    connectIpsClient.validateTransaction(
        txnId,
        referenceId,
        txnAmtPaisa
    );

if (response.isPaymentSuccessful()) {
    // mark paid
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

| Gateway | Blocking | Reactive |
|----------|----------|----------|
| Khalti | ✅ | ✅ |
| eSewa | ✅ | ✅ |
| ConnectIPS | ✅ | ✅ |
| Fonepay | ❌ | ❌ |

Retries:

- ✅ Network failures
- ✅ HTTP 5xx

No retries:

- ❌ HTTP 4xx
- ❌ Signature validation failures

---

# ⚠️ Amount Units

| Gateway | Unit | Java Type | Example |
|----------|------|-----------|---------|
| Khalti | Paisa | `long` | `10000L` |
| eSewa | NPR | `BigDecimal` | `new BigDecimal("100.00")` |
| Fonepay | NPR | `double` | `100.0` |
| ConnectIPS | Paisa | `long` | `amountNPR(100L)` |

---

# 🔐 Security

```java
// Blocking
khaltiClient.lookupPayment(pidx).isPaymentSuccessful();
esewaClient.verifyCallback(data).isPaymentSuccessful();
fonepayClient.verifyCallback(callback).isPaymentSuccessful();
connectIpsClient.validateTransaction(txnId, refId, amount).isPaymentSuccessful();

// Reactive
khaltiReactiveClient.lookupPayment(pidx)
    .filter(KhaltiLookupResponse::isPaymentSuccessful);

esewaReactiveClient.verifyCallback(data)
    .filter(EsewaReactiveClient.EsewaVerificationResult::isPaymentSuccessful);

connectIpsReactiveClient.validateTransaction(txnId, refId, amount)
    .filter(ConnectIpsValidateResponse::isPaymentSuccessful);
```

Always persist payment identifiers **before** redirecting users.

```java
orderRepo.savePidx(orderId, response.pidx());
orderRepo.saveUuid(orderId, uuid);
orderRepo.savePrn(orderId, prn);
orderRepo.saveTxnId(orderId, txnId);
```

Never:

- ❌ Trust redirect query parameters
- ❌ Hardcode secret keys
- ❌ Store secrets in frontend code
- ❌ Commit your `.pfx` certificate to Git

---

# 🗺️ Supported Gateways

| Gateway | Status | Blocking | Reactive | Notes |
|----------|--------|----------|----------|------|
| Khalti | ✅ v0.5.0 | ✅ | ✅ v1.1.1 | Initiate, lookup, refunds |
| eSewa | ✅ v0.1.0 | ✅ | ✅ v1.1.1 | Sandbox support |
| ConnectIPS | ✅ v0.2.0 | ✅ | ✅ v1.1.1 | NCHL merchant required |
| Fonepay | ✅ v0.4.0 | ✅ | ✅ (blocking client) | Redirect only |

---

# 🏗️ Module Structure

```text
nepal-pay-spring-boot-starter/
├── nepal-pay-core/
│   └── io.nepalpay.core.*          — Models, exceptions, retry (no Spring)
├── nepal-pay-spring-boot-3-starter/
│   └── io.nepalpay.*               — Blocking, RestClient, Jackson 2
├── nepal-pay-spring-boot-4-starter/
│   └── io.nepalpay.*               — Blocking, RestClient, Jackson 3
└── nepal-pay-spring-boot-reactive-starter/
    └── io.nepalpay.reactive.*      — Reactive, WebClient, Mono<>
```

---

# 🤝 Contributing

See **CONTRIBUTING.md** for:

- Local setup
- Coding standards
- Adding new gateways

Open issues:

- #4 Kotlin examples
- #8 ConnectIPS configurable timeout

---

# 📜 License

MIT — see [**LICENSE**](LICENSE).

---

# 👤 Author

**Sujan Lamichhane**

- https://sujanlamichhane.com.np
- https://dev.to/sujankim
- https://github.com/sujankim

---

<div align="center">

# [📖 Full Documentation](https://sujankim.github.io/nepal-pay-spring-boot-starter/index.html)

Built with ❤️ for Nepal's developer community 🇳🇵

**If NepalPay saved you time, please give it a ⭐ on GitHub!**

</div>