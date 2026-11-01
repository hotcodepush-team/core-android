---
name: validate-issue
description: Runs the assessment turn on an untriaged issue — fit, workaround and quick win for a feature, a reproduction attempt for a bug — and ends in a type with a priority, a comment, or an escalation to a maintainer. Use before an issue is implemented. Do not use to implement it or to triage an already-triaged issue.
---

# Validate an issue

The assessment turn: validate first, implement maybe.
It runs on an issue that still carries `state/needs-triage` — including the ones filed with the Idea form, which carry `idea` as well.

## Prerequisites

- `gh` installed and authenticated.
- The issue number.

## Steps

1. **Read the issue** with its comments, and the issues it links.
2. **Check what already exists** — the checkout, the documentation, the open and closed issues. An issue that is already tracked, already shipped or already answered ends here with a comment.
3. **Take the path that fits the issue.**

### A feature, a refactoring thought or an idea

- **Fit.** Does it belong in this product at all?
- **Workaround.** Is there a documented way to get the same result today? Then the comment is the answer, and triage sets the type and priority.
- **Quick win.** Small, self-contained, no open design question? Then it hands into implementation with the recommendation stated.
- **Anything else** — out of scope, too complicated, or any case where the answer is not certain — sets `state/needs-maintainer` and stops. Scope is a maintainer's call, never this turn's.

### A bug

- **Reproduce it.** Follow the reporter's steps against the current version.
- **Unreproducible** parks the issue in `state/needs-reproduction` with a comment saying exactly what was tried and what was seen instead. The 14-day close handles the rest.
- **Reproduced** hands into implementation, with the finding — the cause where it is already visible — in the comment.

4. **Write one comment** with the verdict: what was checked, what was found, what happens next. Every open question carries a recommended answer and a one-line why.
5. **Set the labels** the verdict implies, following the `triage-issue` rules.
6. **Report** the verdict in one line.

## Rules

- Escalate rather than guess. `state/needs-maintainer` costs a maintainer one read; a wrong verdict costs a contributor a week.
- Cite only URLs seen during this turn, and never promise a feature.
- `idea` stays on the issue as provenance even after the real type is found.
- A hopeless idea earns a close recommendation, never a close: the click stays human.
