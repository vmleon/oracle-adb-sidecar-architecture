# OFAC Sanctions Screening (P-OFAC-01)

This document defines the bank's screening procedures against the U.S. Treasury Office of Foreign Assets Control (OFAC) Specially Designated Nationals (SDN) list and the blocked-country list.

## 1. Purpose

To prevent processing of transactions that involve sanctioned individuals, entities, or jurisdictions, in compliance with OFAC obligations.

## 2. Blocked-country list

The current blocked-country ISO codes (illustrative; consult OFAC for live list): **BY** (Belarus), **IR** (Iran), **KP** (North Korea), **RU** (Russia, partial), **SY** (Syria), and others as updated. Outbound wires, account openings, and beneficial-owner relationships involving these countries require senior officer approval and may be blocked outright.

## 3. Screening points

Rule `R-OFAC-001` (severity `VIOLATION`): a counterparty country code on the blocked list automatically holds the transaction for compliance review.

Rule `R-OFAC-002` (severity `VIOLATION`): all outbound wires must screen against the OFAC SDN list at the time of authorisation. A match results in immediate hold and notification of the compliance officer.

## 4. Screening cadence

- **At onboarding**: every new customer is screened against SDN.
- **At each transaction**: outbound wires and high-value internal transfers are screened in real time.
- **Daily**: all active customers are re-screened against the most recent SDN delta.

## 5. Hit handling

A potential SDN hit triggers:

1. Transaction held; funds not released to the counterparty.
2. Compliance officer reviews within 1 business day.
3. If confirmed, funds are blocked and a report is filed with OFAC within 10 business days.
4. If the hit is a false positive, the transaction is released and the customer record annotated.

## 6. Cross-references

- `wire-transfer-sop.md` — wire-screening protocol.
- `aml-and-ctr-procedures.md` — interaction with structuring and SAR processes.
- `kyc-and-cip-requirements.md` — EDD trigger when blocked-country activity is detected.
