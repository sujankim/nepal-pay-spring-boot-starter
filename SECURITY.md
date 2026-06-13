# Security Policy

## Supported Versions

| Version | Supported |
|---------|-----------|
| 0.1.x   | ✅ Yes     |

## Reporting a Vulnerability

**Please do NOT report security vulnerabilities via public GitHub Issues.**

If you discover a security vulnerability in NepalPay, please report it privately:

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
2. **Forced verification** — `lookupPayment()` and `verifyCallback()` enforce
   server-side confirmation; redirect parameters are never trusted alone
3. **HMAC verification** — eSewa callback signatures are always verified
   before marking any payment as successful
4. **Amount validation** — `isAmountValid()` lets you detect tampered amounts
5. **No logging of secrets** — secret keys are never logged at any level