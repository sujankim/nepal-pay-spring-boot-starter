# Security Policy

## Supported Versions

| Version | Supported  |
|---------|------------|
| 1.1.x   | ✅ Yes      |
| 1.0.x   | ✅ Yes      |
| 0.x.x   | ❌ No       |

## Reporting a Vulnerability

**Please do NOT report security vulnerabilities via public GitHub Issues.**

If you discover a security vulnerability in NepalPay, please report it
privately:

📧 **Email:** sujan.officals@email.com
🔒 **Subject:** `[SECURITY] NepalPay vulnerability report`

Please include:
- Description of the vulnerability
- Steps to reproduce
- Potential impact
- Suggested fix (if any)

## What We Consider a Security Issue

- Secret key exposure in logs or responses
- Signature verification bypass
- Amount tampering vulnerabilities
- Insecure HTTP client configuration
- Dependency with known CVE affecting payment flow

## Response Timeline

- **Acknowledgement:** within 48 hours
- **Assessment:** within 7 days
- **Fix + patch release:** within 30 days for critical issues

## Security Design Decisions

NepalPay is designed with these security principles:

1. **Server-side only** — secret keys never leave your backend
2. **Forced verification** — `lookupPayment()`, `verifyCallback()`, and
   `validateTransaction()` enforce server-side confirmation; redirect
   parameters are never trusted alone
3. **HMAC verification** — eSewa and Fonepay callback signatures are
   always verified before marking any payment as successful
4. **Constant-time comparison** — eSewa reactive client uses
   `MessageDigest.isEqual()` to prevent timing attacks
5. **Amount validation** — `isAmountValid()` lets you detect tampered
   amounts on Khalti
6. **No logging of secrets** — secret keys are never logged at any level
7. **Reactive contract** — all signing and validation in reactive clients
   is wrapped in `Mono.defer()` so exceptions are always emitted as
   reactive error signals

## Recent Security Fixes

### v1.1.1 — 2026-07-09
- **Removed** `FonepayCallbackResponse.isPaymentSuccessful()` — this
  public method allowed skipping HMAC-SHA512 signature verification
  entirely. The only safe path is now `FonepayClient.verifyCallback()`.

### v1.1.0 — 2026-07-07
- **Added** constant-time HMAC comparison in `EsewaReactiveClient`
  using `MessageDigest.isEqual()` — prevents timing attacks during
  signature verification.
- **Fixed** `ConnectIpsReactiveClient.validateTransaction()` — RSA
  signing now inside `Mono.defer()` so all errors emit as reactive
  signals, never thrown outside the pipeline.