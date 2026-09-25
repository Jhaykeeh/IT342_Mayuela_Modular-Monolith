# LegacySupply Integration Notes

This document records how the shop module's anti-corruption layer (`edu.cit.mayuela.supplier`) talks to the external LegacySupply system, and what was learned from live probing of `https://legacysupply.onrender.com/api/v1`.

## 1. System mapping

The shop speaks in products and units; LegacySupply speaks in supplier SKUs, whole cases, and numeric status codes. The adapter owns that translation.

| Shop product | LegacySupply SupplierSku | Description | PackSize (units/case) |
|---|---|---|---|
| P100 | NRQ-3766 | WIRELESS MOUSE 2.4GHZ | 20 |
| P200 | NRQ-2742 | KEYBOARD MECH TKL | 6 |
| P300 | NRQ-7645 | USB HUB 4-PORT | 10 |

The mapping lives in `application.properties` (`legacysupply.catalog.*`) and is loaded by `LegacySupplyProperties` / `SupplierCatalog`.

## 2. Requests and the session

- Every request carries the header `X-LS-Session` with a session token obtained from `POST /auth/token`.
- The token request is XML: `<AuthRequest><ClientId>..</ClientId><ApiKey>..</ApiKey></AuthRequest>`. The API key is never committed; it comes from the `LS_API_KEY` environment variable.
- A token is valid for roughly **4 minutes**. It is never announced by LegacySupply; the lifetime had to be measured (see below).
- When LegacySupply rejects a request with `E-AUTH-03` (invalid session) or `E-AUTH-07` (expired session), the adapter discards the cached token, signs in again, and retries the same request once with the fresh session.

### Measured session lifetime
From the probing logs:

- 11:39:21 request with token `P6dcHjvGH8JcGAMCpuV5_hLsiJCH8Zc_4ihAsVl4Lxt8` → HTTP 401 `E-AUTH-07` (expired).
- 11:39:22 fresh sign-in → new token issued with `IssuedAt 2026-09-25T03:39:22.685Z`.
- That new token was accepted for polls at t+~1s..t+~50s ... and was still accepted at t+~3m54s (03:43:16, HTTP 200).
- The same run measured the very next sign-in-driven poll cycle hitting `E-AUTH-07` between t+~4m00s and t+~4m33s.

Conclusion: **session lifetime ≈ 4 minutes** (observed accept at ≈3m54s, expiry detected at ≈4m–4m33s). The adapter relies on this implicitly: it never assumes a fixed lifetime, it only reacts to `E-AUTH-03`/`E-AUTH-07` by re-signing-in and retrying.

## 3. Qty and Uom

LegacySupply prices and acknowledges orders per **case** (`Uom=CS`). The adapter therefore converts every reorder to whole cases:

- units needed = `OrderService.REORDER_UNITS` = 30
- cases = `ceil(units / PackSize)`, at least 1, capped at 99

Worked examples from this run:

| Product | Units needed | PackSize | Cases sent | PO |
|---|---|---|---|---|
| P200 | 30 | 6 | ceil(30/6) = 5 | PO-100220 (5 CS of NRQ-2742) |
| P300 | 30 | 10 | ceil(30/10) = 3 | PO-100218 (3 CS of NRQ-7645) |
| P100 | 30 | 20 | ceil(30/20) = 2 | PO-100227 (2 CS of NRQ-3766) |

On delivery the inventory listener restocks `cases × PackSize` units, which for P100 (2×20=40) is intentionally more than the 30 that were requested — you cannot order a fractional case, so the purchaser over-buys up to the next whole case.

## 4. Idempotency and safe replays

- Every order request carries a `BuyerRef` (≤40 chars) and an `X-Request-Id` (≤80 chars). Both are the same value for a reorder (`RO-<uuid>`).
- The adapter stores these on the `supplier_orders` row at creation time and reuses them on every retry.
- During an outage, if LegacySupply actually created the PO but the response was lost (HTTP 503 `E-SYS-50` or a timeout), the retried request is an **idempotent replay**: LegacySupply returns the existing order instead of creating a second one.
- Evidence: 2 chaos events hit order POSTs during this run (11:36:00 `E-SYS-50` on PO-100228, 11:36:06 timeout on PO-100229) — both resolved as "idempotent replay", so the `/verify` page recorded **0 duplicates, 2 safe replays**.

The verify page's "latest requests" shows exactly this:
```
11:36:00  POST /api/v1/purchase-orders   503   E-SYS-50   po created
11:36:00  POST /api/v1/purchase-orders   200   (no code) idempotent replay
```

## 5. Observed error codes and how the adapter handles them

| Code | HTTP | Observed cause | Adapter action |
|---|---|---|---|
| E-AUTH-01 | 401 | Wrong API key | Log, surface as failure |
| E-AUTH-02 | 401 | No session header | Internal bug guard |
| E-AUTH-03 | 401 | Bogus session token | Re-sign-in, retry once |
| E-AUTH-07 | 401 | Expired session token | Re-sign-in, retry once |
| E-FMT-01 | 415 | JSON body sent to XML API | Treat as fatal input error |
| E-FMT-02 | 400 | Malformed XML | Fatal input error |
| E-REF-05 | 400 | BuyerRef longer than 40 chars | Fatal input error |
| E-SKU-02 | 422 | Unknown SupplierSku | Fatal; catalog misconfig |
| E-QTY-11 | 422 | Qty 0 or ≥100 | Fatal; prevented by case calc (1..99) |
| E-IDEM-04 | 409 | Same X-Request-Id, different content | Fatal; request ids are stable per reorder |
| E-PO-04 | 404 | Purchase order not found | Log; do not flag as duplicate |
| E-QRY-06 | 400 | Missing query parameter | Fatal input error |
| E-RATE-03 | 429 | Request quota exceeded | Back off and retry later |
| E-SYS-50 | 503 | Processing error (may have created PO) | Retryable → retry with same X-Request-Id |
| E-SYS-99 | 503 | Service unavailable at boot | Retryable → retry with same X-Request-Id |
| E-GEN-00 | 404 | Undocumented route (GET /catalog) | Not used by the adapter |

Retryable failures (`E-SYS-50`, `E-SYS-99`, timeouts, `E-RATE-03`) keep the reorder in `PENDING` and are re-sent by the scheduled job. Replays always reuse the stored `X-Request-Id`, so they cannot create a second purchase order.

## 6. Status code mapping and terminal states

LegacySupply advances a PO across status codes over a few minutes:

| Code | Meaning | Shop status |
|---|---|---|
| 10 | Accepted | ACCEPTED |
| 20 | Picking | PICKING |
| 30 | Shipped | SHIPPED |
| 40 | Delivered | DELIVERED (restock + notification) |
| 90 | Cancelled (undocumented, discovered live) | UNKNOWN |
| anything else | Unknown | UNKNOWN |

- `40` triggers `SupplierOrderDeliveredEvent`: inventory restocks and a delivery notification is created in the same transaction.
- `90` (cancelled) is **not in the documentation**. The adapter does not guess; it maps any undocumented code to `UNKNOWN`, records the raw code in `failure`, and never restocks. Because `UNKNOWN` is not an "open" status, the low-stock sweeper can place a replacement reorder — exactly what happened when PO-100219 ended at `90` and PO-100220 was placed afterwards. The `/verify` page confirmed the system "noticed a cancelled order".

## 7. Polling and rate limits

- `OrderStatusPollJob` polls open purchase orders every minute, but per order only if it has not been polled in the last 60s.
- The run used 35 status-check requests with **0 rate-limited**; the adapter stays within the quota by design.

## 8. Resilience summary

1. Timeouts (3s) and `E-SYS-50`/`E-SYS-99`/`E-RATE-03` are retried with backoff.
2. Orders that fail to send stay `PENDING`; `PendingOrderSenderJob` retries them every minute.
3. Every retry reuses the stored `X-Request-Id` and first looks the `BuyerRef` up at LegacySupply, adopting an existing PO instead of posting a second one.
4. A partial unique index `uq_supplier_orders_open_product` on open `supplier_orders(product_id)` guarantees at most one open reorder per product even under concurrency or restart.
5. Delivery status and the resulting restock commit in the same transaction.