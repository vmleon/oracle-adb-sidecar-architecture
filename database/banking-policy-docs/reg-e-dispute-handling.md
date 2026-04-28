# Reg E Dispute Handling (P-REGE-01)

This document defines the bank's procedure for handling consumer-initiated disputes of unauthorised electronic funds transfers under Regulation E (12 CFR §1005).

## 1. Purpose

To ensure timely investigation, provisional credit, and resolution of unauthorised electronic transfer disputes within Reg E timelines.

## 2. Dispute window

Rule `R-REGE-002`: Reg E disputes must be initiated by the consumer **within 60 days** of the statement date on which the unauthorised transfer first appears. Disputes outside this window may be considered as a courtesy but are not subject to Reg E protections.

## 3. Provisional credit

Rule `R-REGE-001`: when an investigation cannot be completed within 10 business days of receiving the dispute, the bank must issue provisional credit for the disputed amount within those 10 business days. Provisional credit is reversible if the investigation finds no error.

## 4. Investigation timelines

- **10 business days** — initial investigation deadline.
- **45 calendar days** — extended investigation window when provisional credit has been issued.
- **90 calendar days** — maximum extension applicable to point-of-sale or international transfer disputes.

## 5. Common dispute scenarios

### 5.1 Duplicate post

Two or more identical card-present transactions from the same merchant within a short window (typically under 5 minutes) at the same amount strongly suggest a duplicate authorisation. Such cases are resolved by reversing the duplicate and applying provisional credit immediately.

### 5.2 Unauthorised transfer

A consumer reports a transfer they did not initiate. Investigation includes IP address review, device fingerprinting, geolocation, and contact with the merchant. If proven unauthorised, the bank credits the full amount and reissues the affected card.

### 5.3 Incorrect amount

A merchant authorised an amount different from the agreed price. The bank obtains the original receipt and contacts the merchant for adjustment.

## 6. Documentation

A dispute file must contain: original dispute statement, date received, investigation timeline, provisional credit ledger entry (if any), final resolution, and consumer notification.

## 7. Cross-references

- `wire-transfer-sop.md` — note that wires are _not_ covered by Reg E (UCC 4A timelines instead).
