# cloud-itonami-isco-1345

Open Business Blueprint for **ISCO-08 1345**: Education Managers — an ISCO
**Wave 1 (design & governance)** occupation per ADR-2607121000. This
is the THIRD wave-1 blueprint batch: management/professional work is
cognitive, **no robotics gate** — eligible for actor implementation
now.

**Maturity: `:implemented`** — EducationManagersAdvisor ⊣
EducationManagersGovernor as a langgraph StateGraph
(`intake → advise → govern → decide → commit/hold`, human-approval
interrupt), modeled on cloud-itonami-isco-4311's bookkeeping actor.
14 tests / 30 assertions green.

The academic HARD invariants — arithmetic and interval containment,
no partial credit:

1. **Credit-hour arithmetic** — the proposed completed-credits sum
   must be ≥ the program's registered required-credits.
2. **Accreditation window** — the proposed as-of day must fall inside
   the program's registered accreditation window (interval
   containment) — an expired accreditation certificate is void.

Also HARD: unregistered/foreign program, unregistered organization,
non-`:propose` effect. Escalations (always human sign-off):
`:approve-credit-waiver` (granting credit outside the normal sum), low
confidence (< 0.6).

AGPL-3.0-or-later, forkable by any qualified operator. Part of the
[cloud-itonami](https://itonami.cloud) open business fleet.
