---
name: review-pr
description: Reviews an open pull request against the criteria in .github/copilot-instructions.md and posts the findings as a pull request review. Use when a pull request needs a review. Do not use to write the change itself or to merge it.
---

# Review a pull request

## Prerequisites

- `gh` installed and authenticated.
- The pull request number or URL.

## Steps

1. **Read the criteria** in `.github/copilot-instructions.md`. They are the review, and this skill adds nothing to them.
2. **Read the intent** before the diff: the linked issue, and the plan or task comment on it. A diff can only be judged against what it was supposed to do.
3. **Read the diff.** `gh pr diff <n>`. Read the whole file around a change where the change alone does not say whether it is correct.
4. **Check the state.** `gh pr checks <n>` — a failing check is a finding.
5. **Judge each criterion** in turn, and for each finding name the file, the line and the criterion it misses.
6. **Post the review** with `gh pr review <n> --comment` and inline comments on the lines. Never approve and never request changes on a pull request authored by the same account.
7. **Report** the findings as a short list, worst first.

## Rules

- Say what is wrong and why it is wrong, never how good the pull request is.
- A finding is actionable or it is not a finding.
- An unrelated change is a finding even when the change is an improvement.
- Silence on everything that is fine; a review is not a summary of the diff.
