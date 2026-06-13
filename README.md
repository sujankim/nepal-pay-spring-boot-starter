<div align="center">

<img src="https://img.shields.io/badge/Java-21-orange?style=for-the-badge&logo=openjdk&logoColor=white" alt="Java 21"/>
<img src="https://img.shields.io/badge/Spring%20Boot-4.1.0-6DB33F?style=for-the-badge&logo=springboot&logoColor=white" alt="Spring Boot 4.1.0"/>
<img src="https://img.shields.io/badge/License-MIT-blue?style=for-the-badge" alt="MIT License"/>

<a href="https://jitpack.io/#sujankim/nepal-pay-spring-boot-starter">
  <img src="https://jitpack.io/v/sujankim/nepal-pay-spring-boot-starter.svg?style=for-the-badge"
       alt="JitPack Version"/>
</a>
<a href="https://github.com/sujankim/nepal-pay-spring-boot-starter/stargazers">
  <img src="https://img.shields.io/github/stars/sujankim/nepal-pay-spring-boot-starter?style=for-the-badge" alt="GitHub Stars"/>
</a>

<a href="https://github.com/sujankim/nepal-pay-spring-boot-starter/network/members">
  <img src="https://img.shields.io/github/forks/sujankim/nepal-pay-spring-boot-starter?style=for-the-badge" alt="GitHub Forks"/>
</a>

<img src="https://img.shields.io/badge/Tests-51%20passing-success?style=for-the-badge" alt="51 Tests"/>

# 🇳🇵 NepalPay Spring Boot Starter

**The first production-grade Java library for Nepal payment gateways.**

Integrate Khalti and eSewa into any Spring Boot application
in under 5 minutes — with security baked in.

[Getting Started](#-getting-started) •
[Khalti](#-khalti-integration) •
[eSewa](#-esewa-integration) •
[Configuration](#-configuration-reference) •
[Contributing](#-contributing)

</div>

---

## 🔥 Why NepalPay?

Nepal's digital payment ecosystem is booming — but every Java developer
was solving the same problem from scratch, every single project.

| Without NepalPay | With NepalPay |
|---|---|
| 500+ lines of boilerplate per gateway | 3 lines to integrate |
| Manual JSON construction + HTTP calls | Type-safe Java records |
| Redirect-trust vulnerability (common bug!) | Server-side verification enforced |
| Secret key exposure risk | Secure by design — keys never leave server |
| Breaks when gateway API updates | Update one library — all projects fixed |
| Copy-paste from blog posts | Tested, documented, production-grade |

```java
// Without NepalPay — what developers write today (just for Khalti initiation):
HttpClient client = HttpClient.newHttpClient();
String json = """
    {
        "return_url": "%s", "website_url": "%s",
        "amount": %d, "purchase_order_id": "%s",
        "purchase_order_name": "%s"
    }
    """.formatted(returnUrl, websiteUrl, amount, orderId, name);
HttpRequest request = HttpRequest.newBuilder()
    .uri(URI.create("https://dev.khalti.com/api/v2/epayment/initiate/"))
    .header("Authorization", "Key " + secretKey)
    .header("Content-Type", "application/json")
    .POST(HttpRequest.BodyPublishers.ofString(json))
    .build();
// ... parse response manually, handle errors manually
// ... then do the SAME for eSewa with HMAC signatures
// ... then do it ALL AGAIN for your next project

// ─────────────────────────────────────────────────────

// With NepalPay — same result:
var response = khaltiClient.initiatePayment(
    KhaltiInitiateRequest.builder()
        .amount(10000L)
        .purchaseOrderId("ORD-001")
        .purchaseOrderName("Pro Plan")
        .build()
);
```

---

## 📦 Getting Started

### Prerequisites

- Java 21+
- Spring Boot 4.1.0+
- Maven or Gradle

### Installation

**Step 1 — Add JitPack repository to your `pom.xml`:**

```xml
<repositories>
    <repository>
        <id>jitpack.io</id>
        <url>https://jitpack.io</url>
    </repository>
</repositories>
```

**Step 2 — Add the dependency:**

```xml
<dependency>
    <groupId>com.github.sujankim</groupId>
    <artifactId>nepal-pay-spring-boot-starter</artifactId>
    <version>v0.1.0</version>
</dependency>
```

**Step 3 — Configure in `application.yml`:**

```yaml
nepalpay:
  khalti:
    secret-key: ${KHALTI_SECRET_KEY}
    return-url:  ${KHALTI_RETURN_URL}
    website-url: ${YOUR_WEBSITE_URL}
    sandbox: true                      # false for production

  esewa:
    secret-key:   ${ESEWA_SECRET_KEY}
    product-code: ${ESEWA_PRODUCT_CODE}
    success-url:  ${ESEWA_SUCCESS_URL}
    failure-url:  ${ESEWA_FAILURE_URL}
    sandbox: true                      # false for production
```

**Step 4 — Inject and use:**

```java
@Service
@RequiredArgsConstructor
public class PaymentService {

    private final KhaltiClient khaltiClient; // ← auto-injected, no config needed
    private final EsewaClient  esewaClient;  // ← auto-injected, no config needed
}
```

That's it. No `@EnableNepalPay`, no `@Bean` method, no config class.  
Spring Boot auto-configures everything when it sees your keys in `application.yml`.

---

## 💳 Khalti Integration

### How Khalti Works

```
Your Backend ──POST──▶ Khalti API ──▶ returns { pidx, payment_url }
                                                      │
                                                      ▼
                                            Redirect user to payment_url
                                                      │
                                              User pays on Khalti
                                                      │
                                    Khalti redirects to your return_url
                                                      │
                              ⚠️  DO NOT trust this redirect alone!
                                                      │
                                                      ▼
Your Backend ──POST──▶ Khalti Lookup API (pidx) ──▶ { status: "Completed" }
                                                      │
                                              ✅ NOW mark as paid
```

> **Security Rule:** Always call `lookupPayment(pidx)` after receiving the redirect.
> The redirect URL can be faked. The lookup API cannot.

### Step 1 — Initiate Payment

```java
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/payment/khalti")
public class KhaltiPaymentController {

    private final KhaltiClient khaltiClient;
    private final OrderRepository orderRepository;

    @PostMapping("/initiate")
    public ResponseEntity<Map<String, String>> initiatePayment(
            @RequestBody PaymentInitiateRequest request) {

        KhaltiInitiateResponse response = khaltiClient.initiatePayment(
            KhaltiInitiateRequest.builder()
                .amount(request.amountNPR() * 100L)   // ⚠️ NPR → paisa (×100)
                .purchaseOrderId(request.orderId())
                .purchaseOrderName(request.productName())
                // Optional: attach customer info for Khalti dashboard
                .customerInfo(
                    request.customerName(),
                    request.customerEmail(),
                    request.customerPhone()
                )
                .build()
        );

        // ✅ IMPORTANT: Store pidx in your database NOW
        // You need it to verify the payment after redirect
        orderRepository.updatePidx(request.orderId(), response.pidx());

        return ResponseEntity.ok(Map.of(
            "pidx",        response.pidx(),
            "payment_url", response.paymentUrl(),  // redirect user here
            "expires_in",  String.valueOf(response.expiresIn())
        ));
    }
}
```

### Step 2 — Handle Callback + Verify

```java
    @GetMapping("/callback")
    public ResponseEntity<String> handleCallback(
            @RequestParam String pidx,
            @RequestParam String status,
            @RequestParam String purchase_order_id) {

        // ⚠️ NEVER trust the redirect parameters directly
        // The user could type any URL — always verify server-side

        // ✅ CORRECT: Call Khalti lookup API to verify
        KhaltiLookupResponse lookup = khaltiClient.lookupPayment(pidx);

        if (!lookup.isPaymentSuccessful()) {
            // Payment failed, canceled, or expired
            return ResponseEntity.badRequest()
                .body("Payment not completed. Status: " + lookup.status());
        }

        // ✅ Optional but recommended: verify amount was not tampered
        Order order = orderRepository.findByPidx(pidx);
        if (!lookup.isAmountValid(order.getAmountPaisa())) {
            // Amount mismatch — potential tampering attempt!
            log.error("Amount mismatch for pidx={}", pidx);
            return ResponseEntity.badRequest().body("Payment amount mismatch");
        }

        // ✅ Safe to mark order as paid
        orderRepository.markAsPaid(
            purchase_order_id,
            lookup.transactionId()
        );

        return ResponseEntity.ok("Payment successful!");
    }
```

### Khalti Amount — Important Note

```java
// ⚠️ Khalti uses PAISA (not NPR)
// NPR 1 = 100 paisa
// NPR 100 → send 10000
// Minimum: NPR 10 = 1000 paisa

long amountPaisa = amountNPR * 100L;
```

### Khalti Payment Statuses

| Status | Meaning | Action |
|--------|---------|--------|
| `COMPLETED` | ✅ Payment confirmed | Mark order as paid |
| `PENDING` | ⏳ Still processing | Poll again later |
| `USER_CANCELED` | ❌ User backed out | Offer retry |
| `CANCELED` | ❌ Canceled | Offer retry |
| `EXPIRED` | ❌ Link expired (60 min) | Generate new payment |
| `FAILED` | ❌ Payment failed | Offer retry |
| `UNKNOWN` | ⚠️ Unexpected | Contact Khalti support |

```java
KhaltiLookupResponse lookup = khaltiClient.lookupPayment(pidx);

KhaltiPaymentStatus status = lookup.paymentStatus();

if (status.isSuccess()) {
    // mark paid
} else if (status.isTerminalFailure()) {
    // CANCELED, USER_CANCELED, EXPIRED, FAILED
    // stop retrying — ask user to start over
} else if (status == KhaltiPaymentStatus.PENDING) {
    // retry lookup after a delay
}
```

---

## 💸 eSewa Integration

### How eSewa Works

eSewa uses a **form-submission model** — unlike Khalti which is API-first.
Your backend generates a signed form payload, returns it to the frontend,
and the frontend POSTs directly to eSewa.

```
Your Backend ──────▶ builds signed EsewaFormPayload
                              │
                    returns to frontend
                              │
              Frontend POSTs form to eSewa URL
                              │
                    User pays on eSewa
                              │
        eSewa redirects to your success_url?data=BASE64_JSON
                              │
              ⚠️  DO NOT trust this redirect alone!
                              │
                              ▼
        Your Backend decodes Base64 → verifies HMAC signature
                              │
        Your Backend calls eSewa Status API for confirmation
                              │
                    ✅ NOW mark as paid
```

> **Security Rule 1:** Always verify the HMAC signature on the callback.
> A tampered signature means someone modified the response.
>
> **Security Rule 2:** Always call the status API after signature verification.
> The signature check alone is not enough.

### Step 1 — Build Form Payload

```java
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/payment/esewa")
public class EsewaPaymentController {

    private final EsewaClient esewaClient;
    private final OrderRepository orderRepository;

    @PostMapping("/initiate")
    public ResponseEntity<EsewaFormPayload> initiatePayment(
            @RequestBody PaymentInitiateRequest request) {

        // ✅ Generate a unique transaction UUID — store this in your DB!
        String transactionUuid = EsewaClient.generateTransactionUuid();

        // Save to DB before building payload
        orderRepository.save(Order.builder()
            .id(request.orderId())
            .transactionUuid(transactionUuid)   // ← IMPORTANT: save this!
            .amountNPR(request.amountNPR())
            .build()
        );

        // ⚠️ eSewa uses NPR directly — NOT paisa like Khalti
        EsewaFormPayload payload = esewaClient.buildFormPayload(
            request.amountNPR(),    // BigDecimal — NPR 100 → send new BigDecimal("100.00")
            transactionUuid
        );

        // With tax and charges (optional):
        // EsewaFormPayload payload = esewaClient.buildFormPayload(
        //     request.amountNPR(),           // base amount
        //     new BigDecimal("13.00"),        // 13% tax
        //     transactionUuid,
        //     BigDecimal.ZERO,               // service charge
        //     BigDecimal.ZERO                // delivery charge
        // );

        // Return the payload to frontend
        // Frontend will POST this as a form to payload.formActionUrl()
        return ResponseEntity.ok(payload);
    }
}
```

### Step 2 — Frontend Form Submission

The frontend receives the `EsewaFormPayload` and submits it as an HTML form.

**Angular example:**

```typescript
initiateEsewaPayment(payload: EsewaFormPayload): void {
    const form = document.createElement('form');
    form.method = 'POST';
    form.action = payload.form_action_url; // sandbox or production URL

    // Add all payload fields as hidden inputs
    const fields = [
        'amount', 'tax_amount', 'total_amount', 'transaction_uuid',
        'product_code', 'product_service_charge', 'product_delivery_charge',
        'success_url', 'failure_url', 'signed_field_names', 'signature'
    ];

    fields.forEach(field => {
        const input = document.createElement('input');
        input.type = 'hidden';
        input.name = field;
        input.value = (payload as any)[field];
        form.appendChild(input);
    });

    document.body.appendChild(form);
    form.submit(); // ← Browser POSTs directly to eSewa
}
```

**Plain HTML example:**

```html
<form method="POST" action="{{ payload.form_action_url }}">
    <input type="hidden" name="amount"                   value="{{ payload.amount }}"/>
    <input type="hidden" name="tax_amount"               value="{{ payload.tax_amount }}"/>
    <input type="hidden" name="total_amount"             value="{{ payload.total_amount }}"/>
    <input type="hidden" name="transaction_uuid"         value="{{ payload.transaction_uuid }}"/>
    <input type="hidden" name="product_code"             value="{{ payload.product_code }}"/>
    <input type="hidden" name="product_service_charge"   value="{{ payload.product_service_charge }}"/>
    <input type="hidden" name="product_delivery_charge"  value="{{ payload.product_delivery_charge }}"/>
    <input type="hidden" name="success_url"              value="{{ payload.success_url }}"/>
    <input type="hidden" name="failure_url"              value="{{ payload.failure_url }}"/>
    <input type="hidden" name="signed_field_names"       value="{{ payload.signed_field_names }}"/>
    <input type="hidden" name="signature"                value="{{ payload.signature }}"/>
    <button type="submit">Pay with eSewa</button>
</form>
```

### Step 3 — Handle Callback + Verify

```java
    @GetMapping("/callback")
    public ResponseEntity<String> handleCallback(
            @RequestParam String data) {  // ← eSewa sends Base64 encoded data

        // verifyCallback() does THREE things automatically:
        // 1. Decodes Base64 → EsewaCallbackData
        // 2. Verifies HMAC-SHA256 signature (tamper protection!)
        // 3. Calls eSewa status API for final confirmation
        EsewaClient.EsewaVerificationResult result =
                esewaClient.verifyCallback(data);

        if (!result.isPaymentSuccessful()) {
            return ResponseEntity.badRequest()
                .body("Payment not confirmed. Status: "
                    + result.statusResponse().status());
        }

        // ✅ Payment confirmed — safe to mark as paid
        String uuid = result.callbackData().transactionUuid();
        orderRepository.markAsPaid(uuid, result.statusResponse().refId());

        return ResponseEntity.ok("Payment successful!");
    }
```

### eSewa Amount — Important Note

```java
// ⚠️ eSewa uses NPR directly (NOT paisa like Khalti)
// NPR 100 → send new BigDecimal("100.00")
// Always use 2 decimal places

BigDecimal amount = new BigDecimal("100.00");   // ✅ correct
BigDecimal amount = new BigDecimal("100");       // ✅ also works
BigDecimal amount = new BigDecimal("100.1");     // ✅ also works (rounded to 100.10)
```

### eSewa Payment Statuses

| Status | Meaning | Action |
|--------|---------|--------|
| `COMPLETE` | ✅ Payment confirmed | Mark order as paid |
| `INCOMPLETE` | ❌ Not completed | Do not mark as paid |
| `UNKNOWN` | ⚠️ Unexpected value | Log and investigate |

```java
EsewaClient.EsewaVerificationResult result = esewaClient.verifyCallback(data);

if (result.callbackData().paymentStatus().isSuccess()) {
    // COMPLETE — safe to mark paid
}
```

---

## ⚙️ Configuration Reference

All properties are under the `nepalpay.*` prefix.

### Khalti Properties (`nepalpay.khalti.*`)

| Property | Required | Default | Description |
|----------|----------|---------|-------------|
| `secret-key` | ✅ Yes | — | Khalti secret key. Enables `KhaltiClient` bean when present. |
| `return-url` | ✅ Yes | — | URL Khalti redirects to after payment. |
| `website-url` | ✅ Yes | — | Your merchant website URL. |
| `sandbox` | No | `true` | `true` = sandbox (dev.khalti.com) / `false` = production. |
| `timeout-seconds` | No | `10` | HTTP timeout for Khalti API calls. |

### eSewa Properties (`nepalpay.esewa.*`)

| Property | Required | Default | Description |
|----------|----------|---------|-------------|
| `secret-key` | ✅ Yes | — | eSewa HMAC secret key. Enables `EsewaClient` bean when present. |
| `product-code` | ✅ Yes | — | Merchant code. Sandbox: `EPAYTEST`. Production: your code. |
| `success-url` | ✅ Yes | — | URL eSewa redirects to on success. |
| `failure-url` | ✅ Yes | — | URL eSewa redirects to on failure. |
| `sandbox` | No | `true` | `true` = sandbox / `false` = production. |
| `timeout-seconds` | No | `10` | HTTP timeout for eSewa status API calls. |

### Full Example `application.yml`

```yaml
# ── Sandbox (development) ──────────────────────────────────────────────────
nepalpay:
  khalti:
    secret-key:   ${KHALTI_SECRET_KEY}          # from Khalti merchant dashboard
    return-url:   https://yourapp.com/api/payment/khalti/callback
    website-url:  https://yourapp.com
    sandbox: true

  esewa:
    secret-key:   ${ESEWA_SECRET_KEY}           # sandbox: 8gBm/:&EnhH.1/q
    product-code: ${ESEWA_PRODUCT_CODE}         # sandbox: EPAYTEST
    success-url:  https://yourapp.com/api/payment/esewa/callback
    failure-url:  https://yourapp.com/payment/failed
    sandbox: true
```

```yaml
# ── Production ──────────────────────────────────────────────────────────────
nepalpay:
  khalti:
    secret-key:   ${KHALTI_SECRET_KEY}
    return-url:   https://yourapp.com/api/payment/khalti/callback
    website-url:  https://yourapp.com
    sandbox: false                              # ← production mode

  esewa:
    secret-key:   ${ESEWA_SECRET_KEY}
    product-code: ${ESEWA_PRODUCT_CODE}         # ⚠️ must be your real merchant code!
    success-url:  https://yourapp.com/api/payment/esewa/callback
    failure-url:  https://yourapp.com/payment/failed
    sandbox: false                              # ← production mode
```

### Environment Variables (Recommended)

Never hardcode secrets. Use environment variables:

```bash
# .env (local development — never commit this file!)
KHALTI_SECRET_KEY=test_secret_key_abc123
KHALTI_RETURN_URL=http://localhost:8080/api/payment/khalti/callback

ESEWA_SECRET_KEY=8gBm/:&EnhH.1/q
ESEWA_PRODUCT_CODE=EPAYTEST
ESEWA_SUCCESS_URL=http://localhost:8080/api/payment/esewa/callback
ESEWA_FAILURE_URL=http://localhost:4200/payment/failed
```

---

## 🧪 Sandbox Testing Credentials

### Khalti Sandbox
```
Dashboard: https://test-admin.khalti.com
Test wallet: any Khalti sandbox account
Secret key format: test_secret_key_xxxxxxxxxxxxxxxx
```

### eSewa Sandbox

| Credential | Value |
|-----------|-------|
| eSewa ID | `9806800001` / `9806800002` / `9806800003` |
| Password | `Nepal@123` |
| MPIN | `1122` |
| Token | `123456` |
| Secret Key | `8gBm/:&EnhH.1/q` |
| Product Code | `EPAYTEST` |

---

## 🔧 Advanced Usage

### Use Only One Gateway

NepalPay uses `@ConditionalOnProperty` — beans are ONLY created when
the corresponding `secret-key` is present. Configure only what you need:

```yaml
# Only Khalti — no EsewaClient bean created at all
nepalpay:
  khalti:
    secret-key: ${KHALTI_SECRET_KEY}
    return-url:  ${KHALTI_RETURN_URL}
    website-url: ${YOUR_WEBSITE_URL}
```

### Override With Custom Implementation

Use `@ConditionalOnMissingBean` — define your own bean and NepalPay's
auto-configuration steps aside automatically:

```java
@Configuration
public class MyPaymentConfig {

    @Bean
    public KhaltiClient myCustomKhaltiClient(
            NepalPayProperties properties,
            RestClient.Builder builder) {

        // Your custom implementation or configuration
        return new KhaltiClient(
            properties.khalti(),
            builder,
            "https://my-custom-proxy.com/khalti"
        );
    }
}
```

### Per-Request URL Override

If you need different `returnUrl` per payment (e.g. per order type):

```java
KhaltiInitiateResponse response = khaltiClient.initiatePayment(
    KhaltiInitiateRequest.builder()
        .amount(10000L)
        .purchaseOrderId("ORD-001")
        .purchaseOrderName("Premium Plan")
        .returnUrl("https://yourapp.com/premium/callback")  // ← overrides property
        .build()
);
```

### Poll eSewa Status Directly

When you need to check status without a callback (e.g. cron job for pending orders):

```java
EsewaStatusResponse status = esewaClient.checkStatus(
    "your-transaction-uuid",   // stored in DB from buildFormPayload()
    "100.00"                   // original total amount
);

if (status.isPaymentSuccessful()) {
    // mark as paid
}
```

---

## 🔐 Security Best Practices

### ✅ DO

```java
// ✅ Always verify server-side after Khalti redirect
KhaltiLookupResponse lookup = khaltiClient.lookupPayment(pidx);
if (lookup.isPaymentSuccessful()) { /* mark paid */ }

// ✅ Always use verifyCallback() for eSewa (does all 3 checks)
EsewaVerificationResult result = esewaClient.verifyCallback(encodedData);
if (result.isPaymentSuccessful()) { /* mark paid */ }

// ✅ Verify amount matches your order
if (!lookup.isAmountValid(expectedAmountPaisa)) {
    throw new SecurityException("Amount mismatch — possible tampering!");
}

// ✅ Store pidx/transactionUuid BEFORE redirecting user
orderRepository.updatePidx(orderId, response.pidx());

// ✅ Use environment variables for secrets
secret-key: ${KHALTI_SECRET_KEY}    // from OS env / Docker secret / Vault
```

### ❌ NEVER DO

```java
// ❌ NEVER trust redirect parameters alone
if (request.getParam("status").equals("Completed")) {
    markAsPaid(); // WRONG — can be faked!
}

// ❌ NEVER hardcode secrets
secret-key: live_secret_key_abc123  // exposed in git history!

// ❌ NEVER put secrets in frontend code
// The secret key must ONLY exist on your backend server

// ❌ NEVER skip amount verification
// Always call lookup.isAmountValid(expectedAmount)
```

---

## 🏗️ Architecture

### Auto-Configuration Flow

```
Your Spring Boot App starts
          │
          ▼
Spring Boot reads META-INF/spring/
org.springframework.boot.autoconfigure.AutoConfiguration.imports
          │
          ▼
NepalPayAutoConfiguration loads
          │
    ┌─────┴──────┐
    ▼            ▼
nepalpay.khalti  nepalpay.esewa
.secret-key      .secret-key
present?         present?
    │                │
    ▼                ▼
KhaltiClient     EsewaClient
bean created     bean created
    │                │
    ▼                ▼
@Autowired / constructor injection works everywhere
```

### Library Package Structure

```
io.nepalpay/
├── autoconfigure/
│   └── NepalPayAutoConfiguration  ← Spring Boot auto-config entry point
├── config/
│   └── NepalPayProperties         ← @ConfigurationProperties (records)
├── exception/
│   └── NepalPayException          ← Base exception
├── khalti/
│   ├── KhaltiClient               ← Main class to inject and use
│   ├── exception/
│   │   └── KhaltiException        ← Khalti-specific errors
│   └── model/
│       ├── KhaltiInitiateRequest  ← Input (has builder)
│       ├── KhaltiInitiateResponse ← Output from initiate API
│       ├── KhaltiLookupResponse   ← Output from lookup API
│       └── KhaltiPaymentStatus    ← Enum (COMPLETED, CANCELED, etc.)
└── esewa/
    ├── EsewaClient                ← Main class to inject and use
    ├── exception/
    │   └── EsewaException         ← eSewa-specific errors
    └── model/
        ├── EsewaFormPayload       ← Signed form data for frontend
        ├── EsewaCallbackData      ← Decoded Base64 callback
        ├── EsewaStatusResponse    ← Output from status API
        └── EsewaPaymentStatus     ← Enum (COMPLETE, INCOMPLETE, etc.)
```

---

## ❗ Error Handling

All exceptions extend `NepalPayException` — catch all gateway errors in one place:

```java
// Catch everything NepalPay throws
try {
    var response = khaltiClient.initiatePayment(request);
} catch (KhaltiException e) {
    // Khalti-specific error
    log.error("Khalti error | status={} | body={}",
        e.httpStatus(), e.responseBody());
    // return user-friendly error response
} catch (NepalPayException e) {
    // Any other NepalPay error
    log.error("Payment error: {}", e.getMessage());
}

// Catch specific eSewa errors
try {
    var result = esewaClient.verifyCallback(data);
} catch (EsewaException e) {
    if (e.getMessage().contains("signature verification FAILED")) {
        // ⚠️ Potential tampering attempt — alert your team!
        securityAuditService.logTamperingAttempt(data);
    }
}
```

### Exception Details

| Exception | When Thrown | Fields |
|-----------|-------------|--------|
| `KhaltiException` | Khalti API errors, validation failures | `httpStatus()`, `responseBody()` |
| `EsewaException` | eSewa errors, signature mismatch, decode failure | `httpStatus()`, `responseBody()` |
| `NepalPayException` | Base class for all above | `getMessage()` |

---

## 🛠️ Tech Stack

| Technology | Version | Purpose |
|-----------|---------|---------|
| Java | 21 | Language |
| Spring Boot | 4.1.0 | Framework + auto-configuration |
| Jackson 3 | `tools.jackson` | JSON serialization |
| SLF4J | Boot-managed | Logging facade |
| Lombok | Boot-managed | Boilerplate reduction |
| MockWebServer | 4.12.0 | Testing (simulates gateway APIs) |

---

## 🗺️ Roadmap

| Status | Feature |
|--------|---------|
| ✅ Done | Khalti ePay v2 (initiate + lookup) |
| ✅ Done | eSewa ePay v2 (form payload + verify + status) |
| 🔲 Planned | ConnectIPS integration |
| 🔲 Planned | Fonepay / QR payment support |
| 🔲 Planned | Khalti refund API |
| 🔲 Planned | Retry logic with exponential backoff |
| 🔲 Planned | Spring WebFlux (reactive) support |
| 🔲 Planned | Maven Central publishing |

Want to contribute? See [CONTRIBUTING.md](CONTRIBUTING.md)!

---

## 🤝 Contributing

Contributions are very welcome! Nepal's developer community deserves
first-class open-source tooling. 🇳🇵

See [CONTRIBUTING.md](CONTRIBUTING.md) for:
- Local setup guide
- Code standards
- How to add a new gateway
- PR process

---

## 📜 License

MIT License — see [LICENSE](LICENSE) for full text.

Free to use in personal and commercial projects.

---

## 👤 Author

**Sujan Lamichhane**

- 🌐 Website: [sujanlamichhane.com.np](https://sujanlamichhane.com.np)
- 🐙 GitHub: [@sujankim](https://github.com/sujankim)

---

<div align="center">

Built with ❤️ for Nepal's developer community 🇳🇵

**If this library saved you time, give it a ⭐ on GitHub!**

</div>