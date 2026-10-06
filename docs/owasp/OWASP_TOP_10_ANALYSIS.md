# OWASP Top 10 — Security Analysis of the Notification Service API

## 1. The reference list used

The current edition is the **OWASP Top 10:2025**, released on 6 November 2025 (it replaces the
2021 edition). The ten categories are:

| # | Category | Note vs. 2021 |
|---|---|---|
| A01:2025 | Broken Access Control | Still #1; **SSRF was absorbed into this category** |
| A02:2025 | Security Misconfiguration | Up from #5 — biggest riser |
| A03:2025 | Software Supply Chain Failures | Renamed/expanded from "Vulnerable and Outdated Components" |
| A04:2025 | Cryptographic Failures | Down from #2 |
| A05:2025 | Injection | Down from #3 |
| A06:2025 | Insecure Design | — |
| A07:2025 | Authentication Failures | Renamed from "Identification and Authentication Failures" |
| A08:2025 | Software or Data Integrity Failures | — |
| A09:2025 | Logging & Alerting Failures | Renamed |
| A10:2025 | Mishandling of Exceptional Conditions | **New category** |

The 2025 edition deliberately shifted toward naming **root causes** rather than symptoms, which
is why SSRF is no longer its own entry and why exception handling became a first-class category.

---

## 2. Executive summary — the three selected risks

Findings:

| Pick | Category | The question it answers | Current state in this service |
|---|---|---|---|
| **#1** | **A07:2025 — Authentication Failures** | *Who is calling?* | Nobody verifies it — `X-Client-Id` is self-asserted |
| **#2** | **A01:2025 — Broken Access Control** | *What may this caller touch?* | Scoping is implemented correctly, but it trusts answer #1 |
| **#3** | **A02:2025 — Security Misconfiguration** | *What else is exposed besides the API?* | Actuator + Swagger UI are open and unauthenticated |

They are ordered as a chain on purpose: **A07 is the root cause, A01 is the impact, and A02 is
the extra attack surface that makes both easier to exploit.** Fixing A07 largely neutralises A01,
which is the single most valuable point to make when presenting this analysis.

---

## 3. Finding #1 — A07:2025 Authentication Failures

### 3.1 What the category means, in one sentence

The application cannot prove that callers are who they claim to be — it accepts an *identity*
instead of verifying a *credential*.

### 3.2 Why this API is affected

The service has **no Spring Security on the classpath at all**. The caller's identity is taken
verbatim from a plain HTTP header:

```java
// adapter/in/web/impl/NotificationEventControllerImpl.java
private static final String CLIENT_ID_HEADER = "X-Client-Id";

@GetMapping
public ResponseEntity<...> listNotificationEvents(
        @RequestHeader(CLIENT_ID_HEADER) String clientId, ...)
```

### 3.3 How it is exploited

Trivially — the attacker only needs to guess or enumerate a client identifier. There is no
brute-force cost, because there is nothing to brute-force:

```bash
curl https://api.example.com/notification_events -H 'X-Client-Id: CLIENT001'
```

Client identifiers in this domain are low-entropy, predictable, and appear in webhook traffic,
support tickets and logs — so they are not a secret in any meaningful sense.

### 3.4 Mitigation

**Primary measure — make the principal come from a verified token, not a header.**

Also use Spring Security with OAuth2, JWT 

## 4. Finding #2 — A01:2025 Broken Access Control

### 4.1 What the category means, in one sentence

A caller can reach data or actions that belong to someone else — still the #1 risk in the 2025
list.

### 4.2 Why this API is affected

Any user could send a different client-id.


### 4.4 Mitigation

**For the tenant-isolation half**

   Fix Finding #1 — derive `clientId` from the verified JWT claim. This removes the
   vulnerability at its root; the existing repository-level scoping then becomes genuinely
   enforcing.

---

## 5. Finding #3 — A02:2025 Security Misconfiguration

### 5.1 What the category means, in one sentence

The code may be fine, but the deployment ships with doors open that nobody intended to publish —
the biggest riser in the 2025 list.

### 5.2 Why this API is affected

Actuator endpoints are exposed and unauthenticated.


### 5.3 Mitigation

| Item | Measure |
|---|---|
| Actuator | Expose only `health` publicly with `show-details: when-authorized`; move the rest to a separate management port (`management.server.port`) reachable only from the cluster, and require an authority: `.requestMatchers(EndpointRequest.toAnyEndpoint()).hasRole("OPS")` using `EndpointRequest` from Actuator's Spring Security integration |
| Swagger UI | Disable in production (`springdoc.swagger-ui.enabled: false`, `springdoc.api-docs.enabled: false`) via a Spring profile, or place it behind the same authentication as the API |

---

