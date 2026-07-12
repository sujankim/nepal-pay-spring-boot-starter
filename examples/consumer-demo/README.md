# NepalPay Consumer Demo

A minimal Spring Boot 4 application demonstrating **NepalPay v1.2.1** —

**Khalti, eSewa, ConnectIPS, and Fonepay payments with blocking and reactive (WebFlux) support, refund, and retry.**

---

## Prerequisites

- Java 21+
- Maven 3.x
- A Khalti sandbox secret key (`test-admin.khalti.com`)

---

# Run

## 1. Set Khalti Sandbox Key

### Linux/macOS

```bash
export KHALTI_SECRET_KEY=test_secret_key_your_key_here
```

### Windows PowerShell

```powershell
$env:KHALTI_SECRET_KEY="test_secret_key_your_key_here"
```

---

## 2. Start the Application

```bash
mvn spring-boot:run
```

Application starts at:

```text
http://localhost:8080
```

---

# API Endpoints

## Health

```http
GET /api/demo/health
```

Confirms NepalPay beans are auto-configured and shows the current mode (**SANDBOX** or **PRODUCTION**) for each configured gateway.

---

# 💳 Khalti — Blocking

## Initiate Payment

```bash
curl -X POST http://localhost:8080/api/demo/khalti/initiate \
  -H "Content-Type: application/json" \
  -d '{"orderId":"ORD-001","amountNPR":100,"productName":"Pro Plan"}'
```

Returns `pidx` and `payment_url`. Redirect the user to `payment_url`.

### Callback (Khalti Redirects Here)

```http
GET /api/demo/khalti/callback?pidx=xxx
```

Calls `lookupPayment(pidx)` server-side. **Never trust redirect parameters alone.**

### Full Refund

```bash
curl -X POST http://localhost:8080/api/demo/khalti/refund \
  -H "Content-Type: application/json" \
  -d '{"transactionId":"GFq9DrfGSZQKjsj"}'
```

### Partial Refund

```bash
curl -X POST http://localhost:8080/api/demo/khalti/refund \
  -H "Content-Type: application/json" \
  -d '{"transactionId":"GFq9DrfGSZQKjsj","amountPaisa":5000}'
```

> ⚠️ Refund uses `transactionId` returned by `lookupPayment()`, **not** `pidx`.

---

# 💸 eSewa — Blocking

## Initiate Payment

```bash
curl -X POST http://localhost:8080/api/demo/esewa/initiate \
  -H "Content-Type: application/json" \
  -d '{"orderId":"ORD-001","amountNPR":100,"productName":"Pro Plan"}'
```

Returns a signed form payload. Your frontend should POST all fields to `form_action_url`.

### Callback

```http
GET /api/demo/esewa/callback?data=BASE64
```

Calls `verifyCallback(data)` which:

- Decodes callback data
- Verifies HMAC signature
- Calls the eSewa Status API

### Failure

```http
GET /api/demo/esewa/failed
```

---

# 🔵 Fonepay — Blocking

## Initiate Payment

```bash
curl -X POST http://localhost:8080/api/demo/fonepay/initiate \
  -H "Content-Type: application/json" \
  -d '{"orderId":"ORD-001","amountNPR":100,"productName":"Pro Plan"}'
```

Returns `redirect_url`.

Frontend example:

```javascript
window.location.href = redirect_url;
```

### Callback

```http
GET /api/demo/fonepay/callback?PRN=xxx&PID=xxx&PS=success&DV=xxx...
```

---

# 🏦 ConnectIPS — Blocking

> Enable by uncommenting the `connectips:` configuration block in `application.yml`.

## Initiate Payment

```bash
curl -X POST http://localhost:8080/api/demo/connectips/initiate \
  -H "Content-Type: application/json" \
  -d '{"orderId":"ORD-001","amountNPR":100}'
```

---

# ⚡ Reactive (WebFlux) Endpoints

These endpoints use the reactive starter internally (`KhaltiReactiveClient`, `EsewaReactiveClient`, etc.) and demonstrate fully non-blocking `Mono<>` pipelines.

## Khalti — Reactive Initiate

```bash
curl -X POST http://localhost:8080/api/demo/reactive/khalti/initiate \
  -H "Content-Type: application/json" \
  -d '{"orderId":"ORD-R01","amountNPR":100,"productName":"Pro Plan"}'
```

Returns `pidx` and `payment_url` from a reactive pipeline.

### Khalti — Reactive Lookup

```http
GET /api/demo/reactive/khalti/lookup?pidx=xxx
```

Calls `lookupPayment(pidx)` reactively.

### Khalti — Reactive Refund

```bash
curl -X POST http://localhost:8080/api/demo/reactive/khalti/refund \
  -H "Content-Type: application/json" \
  -d '{"transactionId":"GFq9DrfGSZQKjsj"}'
```

### eSewa — Reactive Check Status

```bash
curl -X GET \
"http://localhost:8080/api/demo/reactive/esewa/status?uuid=xxx&amount=100.00"
```

Calls `checkStatus(uuid, amount)` reactively.

---

# Gateway Differences

| Feature | Khalti | eSewa | Fonepay | ConnectIPS |
|---------|---------|--------|----------|------------|
| Amount | Paisa (×100) | NPR (`BigDecimal`) | NPR (`double`) | Paisa (×100) |
| Flow | API POST | Form POST | URL Redirect | Form POST |
| Signature | API Key Header | HMAC-SHA256 Base64 | HMAC-SHA512 Hex | RSA-SHA256 (`.pfx`) |
| Verify | `lookupPayment()` | `verifyCallback()` | `verifyCallback()` | `validateTransaction()` |
| Retry | ✅ | ✅ | ❌ N/A | ✅ |
| Refund | ✅ | ❌ | ❌ | ❌ |
| Reactive | ✅ | ✅ | Uses blocking client | ✅ |

---

# 🔁 Enabling Retry

Uncomment the `retry:` block in `application.yml`:

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

Retry is **disabled by default** (opt-in).

It works identically for both **blocking** and **reactive** clients.