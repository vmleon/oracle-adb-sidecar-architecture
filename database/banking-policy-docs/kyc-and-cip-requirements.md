# KYC and CIP Requirements (P-KYC-01)

This document specifies the bank's Customer Identification Programme (CIP) and Know-Your-Customer (KYC) periodic refresh obligations.

## 1. Purpose

To verify customer identity at onboarding and refresh due-diligence information at risk-tiered intervals, in line with the FinCEN CIP rule and the bank's Customer Due Diligence (CDD) policy.

## 2. Onboarding (CIP)

Every new customer must provide: full legal name, date of birth, residential address, and a government-issued identification number. Documentary verification is required for all foreign nationals.

## 3. KYC refresh cadence

| Risk tier | Refresh interval |
| --------- | ---------------- |
| LOW       | Every 5 years    |
| MEDIUM    | Every 3 years    |
| HIGH      | Every 1 year     |

A KYC document set is considered EXPIRED when the most recent refresh date is older than the tier-appropriate interval.

## 4. Detection rules

### 4.1 Expired KYC

Rule `R-KYC-001` (severity `WARNING`): customers whose KYC documents have lapsed must complete refresh within 30 days.

### 4.2 Refresh overdue (HIGH risk)

Rule `R-KYC-002` (severity `VIOLATION`): HIGH-risk customers with documents older than 3 years must refresh KYC before the next outbound wire is permitted.

### 4.3 Re-verify on suspicious activity

Rule `R-KYC-003` (severity `WARNING`): when AML monitoring (P-AML-01) flags suspicious activity on an account, KYC refresh is initiated automatically as part of Enhanced Due Diligence (EDD).

## 5. EDD triggers

EDD applies whenever:

- A SAR has been filed against the customer in the previous 12 months.
- The customer's risk tier has been escalated within the last 90 days.
- The customer originates or receives wires to/from a jurisdiction on the OFAC blocked-country list.

## 6. Cross-references

- `aml-and-ctr-procedures.md` — Section 3 (rules driving R-KYC-003).
- `ofac-sanctions-screening.md` — EDD jurisdictional overlay.
