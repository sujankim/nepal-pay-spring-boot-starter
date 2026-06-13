# NepalPay Consumer Demo

A minimal Spring Boot 4 application demonstrating **NepalPay Spring Boot Starter** integration for **Khalti**, **eSewa**, and **ConnectIPS** payments.

---

## Prerequisites

* Java 21+
* Maven 3.x
* A Khalti or eSewa sandbox account

---

## Run

### 1. Set Your Khalti Sandbox Secret Key

```bash
# Linux/macOS
export KHALTI_SECRET_KEY=test_secret_key_your_key_here
```

```powershell
# Windows PowerShell
$env:KHALTI_SECRET_KEY="test_secret_key_your_key_here"
```

### 2. Start the Application

```bash
mvn spring-boot:run
```

The application will start at:

```text
http://localhost:8080
```

---

# API Endpoints

## Health Check

Confirms that NepalPay beans are auto-configured correctly.

```http
GET http://localhost:8080/api/demo/health
```

---

# Khalti

## Initiate Payment

```bash
curl -X POST http://localhost:8080/api/demo/khalti/initiate \
  -H "Content-Type: application/json" \
  -d '{
        "orderId": "ORD-001",
        "amountNPR": 100,
        "productName": "Pro Plan"
      }'
```

### Response

Returns:

* `pidx`
* `payment_url`

Redirect the customer to `payment_url` to complete the payment.

After payment, Khalti redirects back to:

```text
/api/demo/khalti/callback?pidx=xxx
```

You can then verify the payment using:

```java
khaltiClient.lookupPayment(pidx);
```

---

# eSewa

## Initiate Payment

```bash
curl -X POST http://localhost:8080/api/demo/esewa/initiate \
  -H "Content-Type: application/json" \
  -d '{
        "orderId": "ORD-001",
        "amountNPR": 100,
        "productName": "Pro Plan"
      }'
```

### Response

Returns a signed payload object.

Your frontend must submit all returned fields as an HTML form to:

```text
payload.form_action_url
```

After payment, eSewa redirects back to:

```text
/api/demo/esewa/callback?data=BASE64
```

You can then verify the callback using:

```java
esewaClient.verifyCallback(data);
```

---

# ConnectIPS

ConnectIPS requires merchant registration with NCHL.

Uncomment the `connectips` section inside:

```text
src/main/resources/application.yml
```

Then provide your merchant credentials.

After payment, ConnectIPS redirects back to:

```text
/api/demo/connectips/callback?txnId=xxx
```

You can verify the transaction using:

```java
connectIpsClient.validateTransaction();
```

---

# Payment Gateway Differences

| Feature             | Khalti                | eSewa                  | ConnectIPS              |
| ------------------- | --------------------- | ---------------------- | ----------------------- |
| Amount Unit         | Paisa (`NPR × 100`)   | NPR directly           | Paisa (`NPR × 100`)     |
| Payment Flow        | API-first             | HTML Form Submit       | HTML Form Submit        |
| Callback            | `?pidx=xxx`           | `?data=BASE64`         | `?txnId=xxx`            |
| Verification Method | `lookupPayment(pidx)` | `verifyCallback(data)` | `validateTransaction()` |

---

## Example Flow

### Khalti

```text
Frontend
    ↓
Initiate Payment
    ↓
Receive payment_url
    ↓
Redirect User
    ↓
Khalti Payment Page
    ↓
Callback (?pidx=xxx)
    ↓
lookupPayment(pidx)
```

### eSewa

```text
Frontend
    ↓
Initiate Payment
    ↓
Receive Signed Payload
    ↓
HTML Form Submit
    ↓
eSewa Payment Page
    ↓
Callback (?data=BASE64)
    ↓
verifyCallback(data)
```

### ConnectIPS

```text
Frontend
    ↓
Initiate Payment
    ↓
HTML Form Submit
    ↓
ConnectIPS Payment Page
    ↓
Callback (?txnId=xxx)
    ↓
validateTransaction()
```
