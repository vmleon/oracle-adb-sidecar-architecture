# AML and CTR Procedures (P-AML-01, P-CTR-01)

This document defines the bank's anti-money-laundering (AML) and Currency Transaction Reporting (CTR) procedures. It governs detection, escalation, and filing for transactions that meet or exceed Bank Secrecy Act (BSA) thresholds.

## 1. Purpose

The bank is required under the Bank Secrecy Act to file a Currency Transaction Report (CTR) for any cash transaction equal to or greater than $10,000. This document also covers structuring patterns — multiple sub-threshold transactions designed to evade CTR reporting — which trigger Suspicious Activity Report (SAR) review under policy P-SAR-01.

## 2. Scope

Applies to all customer-facing branches, ATM channels, electronic deposit channels, and wire-transfer counters. AML monitoring extends to outbound wire transfers irrespective of cash basis.

## 3. Detection rules

### 3.1 Single-transaction CTR threshold

Rule `R-AML-001` (severity `VIOLATION`): a single cash transaction of $10,000 or more triggers an automatic CTR filing within 15 calendar days.

### 3.2 Aggregated cash deposits

Rule `R-AML-002` (severity `VIOLATION`): when the sum of cash deposits to a single customer's accounts exceeds $40,000 within any 30-day rolling window, a CTR aggregation review is required and a SAR must be considered. Branch managers are notified by the AML monitoring system the same business day.

### 3.3 Structuring under threshold

Rule `R-AML-005` (severity `VIOLATION`): three or more transactions where the amount is in the band [$9,000, $10,000) within a 7-day rolling window indicates structuring. The account is flagged for SAR review and outbound wire activity is held pending compliance officer approval.

## 4. SAR filing window

Suspicious Activity Reports must be filed within 30 calendar days of the date of initial detection. A 30-day extension is permitted if no suspect has been identified, not to exceed 60 days from initial detection.

## 5. Cross-references

- See `kyc-and-cip-requirements.md` for KYC refresh triggers driven by AML detections.
- See `ofac-sanctions-screening.md` for jurisdictional risk overlays on AML monitoring.
- See `wire-transfer-sop.md` for hold-and-release procedures during open AML reviews.
