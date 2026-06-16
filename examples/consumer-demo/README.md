# NepalPay Consumer Demo

A minimal Spring Boot 4 application demonstrating **NepalPay v0.6.0** —

**Khalti, eSewa, ConnectIPS, and Fonepay payments with refund and retry support.**

---

## Prerequisites

* Java 21+
* Maven 3.x
* A Khalti sandbox secret key (`test-admin.khalti.com`)

---

## Run

### 1. Set Khalti Sandbox Key

#### Linux/macOS

```bash
export KHALTI_SECRET_KEY=test_secret_key_your_key_here
```

#### Windows PowerShell

```powershell
$env:KHALTI_SECRET_KEY="test_secret_key_your_key_here"
```

### 2. Start the Application

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

Confirms NepalPay beans are auto-configured and shows the current mode:

* `SANDBOX`
* `PRODUCTION`

---

# 💳 Khalti

## Initiate Payment

```bash
curl -X POST http://localhost:8080/api/demo/khalti/initiate \
  -H "Content-Type: application/json" \
  -d '{"orderId":"ORD-001","amountNPR":100,"productName":"Pro Plan"}'
```

Returns:

* `pidx`
* `payment_url`

Redirect the user to `payment_url`.

---

## Callback (Khalti Redirects Here)

```http
GET /api/demo/khalti/callback?pidx=xxx
```

Calls `lookupPayment(pidx)` server-side.

> Never trust redirect parameters alone.

---

## Full Refund

```bash
curl -X POST http://localhost:8080/api/demo/khalti/refund \
  -H "Content-Type: application/json" \
  -d '{"transactionId":"GFq9DrfGSZQKjsj"}'
```

---

## Partial Refund

```bash
curl -X POST http://localhost:8080/api/demo/khalti/refund \
  -H "Content-Type: application/json" \
  -d '{"transactionId":"GFq9DrfGSZQKjsj","amountPaisa":5000}'
```

> ⚠️ Refund uses `transactionId` from `lookupPayment()` — not `pidx`.

---

# 💸 eSewa

## Initiate Payment

```bash
curl -X POST http://localhost:8080/api/demo/esewa/initiate \
  -H "Content-Type: application/json" \
  -d '{"orderId":"ORD-001","amountNPR":100,"productName":"Pro Plan"}'
```

Returns a signed form payload.

The frontend should POST all fields to `form_action_url`.

---

## Callback (eSewa Redirects Here)

```http
GET /api/demo/esewa/callback?data=BASE64
```

Calls:

```java
verifyCallback(data)
```

which performs:

1. Base64 decode
2. HMAC verification
3. Status API verification

---

## Failure (eSewa Redirects Here on Cancel)

```http
GET /api/demo/esewa/failed
```

---

# 🔵 Fonepay

## Initiate Payment

```bash
curl -X POST http://localhost:8080/api/demo/fonepay/initiate \
  -H "Content-Type: application/json" \
  -d '{"orderId":"ORD-001","amountNPR":100,"productName":"Pro Plan"}'
```

Returns:

```text
redirect_url
```

Frontend:

```javascript
window.location.href = redirect_url;
```

---

## Callback (Fonepay Redirects Here)

```http
GET /api/demo/fonepay/callback?PRN=xxx&PID=xxx&PS=success&DV=xxx...
```

Calls:

```java
verifyCallback()
```

which:

* Re-computes HMAC-SHA512
* Verifies the DV signature

---

# 🏦 ConnectIPS

Enable ConnectIPS by uncommenting the `connectips:` block in `application.yml`.

## Initiate Payment

```bash
curl -X POST http://localhost:8080/api/demo/connectips/initiate \
  -H "Content-Type: application/json" \
  -d '{"orderId":"ORD-001","amountNPR":100}'
```

---

# Gateway Differences

| Feature   | Khalti            | eSewa              | Fonepay            | ConnectIPS              |
| --------- | ----------------- | ------------------ | ------------------ | ----------------------- |
| Amount    | Paisa (×100)      | NPR (BigDecimal)   | NPR (double)       | Paisa (×100)            |
| Flow      | API POST          | Form POST          | URL Redirect       | Form POST               |
| Signature | API Key Header    | HMAC-SHA256 Base64 | HMAC-SHA512 Hex    | RSA-SHA256 (.pfx)       |
| Verify    | `lookupPayment()` | `verifyCallback()` | `verifyCallback()` | `validateTransaction()` |
| Retry     | ✅                 | ✅                  | ❌ N/A              | ✅                       |
| Refund    | ✅                 | ❌                  | ❌                  | ❌                       |

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

> Retry is disabled by default — opt-in only.
