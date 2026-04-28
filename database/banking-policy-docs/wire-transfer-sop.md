# Wire Transfer SOP (P-WIRE-01)

This document is the standard operating procedure for wire transfer authorisation, screening, hold, and release.

## 1. Purpose

To define limits, jurisdictional reviews, and the hold-and-release procedure for outbound and inbound wire transfers.

## 2. Outbound wire limits

| Wire type             | Limit                       | Approval required         |
| --------------------- | --------------------------- | ------------------------- |
| Domestic, single      | $25,000 self-service        | None below threshold      |
| Domestic, single      | > $25,000                   | Branch manager            |
| International, single | > $10,000 (Rule R-WIRE-001) | Senior compliance officer |
| Same-day aggregate    | > $50,000 (Rule R-WIRE-002) | Hold-and-release review   |

## 3. Mandatory screening

Every outbound wire must:

1. Pass OFAC SDN screening (Rule R-OFAC-002, see `ofac-sanctions-screening.md`).
2. Pass blocked-country screening (Rule R-OFAC-001).
3. Pass AML aggregation review (Rule R-AML-002 from `aml-and-ctr-procedures.md`).

A failure on any screen places the wire on **hold** until reviewed.

## 4. Hold-and-release procedure

When a wire is held:

1. The compliance officer is notified within 30 minutes.
2. The customer is contacted to confirm intent (when permitted by the underlying alert).
3. If approved, the wire is released the same business day where possible.
4. If declined, funds are returned to the originating account and the customer is notified in writing within 1 business day.

## 5. International wires

International wires require:

- Beneficiary full legal name, complete address, beneficiary bank SWIFT/BIC, and account number.
- Purpose-of-payment narrative (mandatory for amounts > $10,000).
- Senior officer approval for amounts > $10,000 (Rule R-WIRE-001).

Wires to blocked-country jurisdictions are blocked outright and not subject to release.

## 6. Cross-references

- `aml-and-ctr-procedures.md` — cash-aggregation interaction.
- `kyc-and-cip-requirements.md` — KYC refresh on HIGH-risk wire activity.
- `reg-e-dispute-handling.md` — note: wires are not covered by Reg E (UCC 4A timelines).
