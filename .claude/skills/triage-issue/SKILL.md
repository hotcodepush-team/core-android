---
name: triage-issue
description: Applies the organization's label rules to an open issue — exactly one type, a priority for bugs and features, at most one state — and removes state/needs-triage. Use when an issue carries state/needs-triage. Do not use to implement an issue or to decide whether it should be built.
---

# Triage an issue

## Prerequisites

- `gh` installed and authenticated.
- The issue number.

## Steps

1. **Read the issue** with its comments: `gh issue view <n> --comments`.
2. **Apply the rules below** with `gh issue edit <n> --add-label` and `--remove-label`.
3. **Report** the labels set and removed, in one line.

## The rules

- **Every issue starts in `state/needs-triage`.** Triage sets exactly one `type/*`, a `priority/*` for bugs and features, and removes `needs-triage` — or parks the issue in another `state/*`.
- **Exactly one `type/*`** per issue: `type/bug`, `type/feature`, `type/docs`, `type/perf`, `type/refactor`, `type/test`, `type/build`, `type/ci`, `type/chore`, `type/question`.
- **Exactly one `priority/*`** on every triaged bug and feature; other types carry none.
  `priority/high` is the next release, blocking users or costing money; `priority/medium` is one of the next releases; `priority/low` is nice to have and the first candidate for `help-wanted`.
- **At most one `state/*`**, naming the one thing that stops the issue.
  `needs-reply` and `needs-reproduction` disappear when the author answers, and 14 days without an answer closes the issue — both automated.
  `blocked` links the blocker in a comment.
  `duplicate`, `invalid` and `wontfix` are set on closing with "not planned" as the close reason; `duplicate` links the original.
- **`platform/*` and `area/*`** only when the issue is specific to some of them; no label means all.
- **`breaking-change`** marks an issue whose resolution requires a major release.
- **`help-wanted`** means we will not get to it soon and welcome a pull request; **`good-first-issue`** additionally means small, self-contained and with the approach spelled out in the issue, and implies `help-wanted`, so the two are never applied together.
- **Assignment is the go-ahead for a pull request.** A maintainer assigns an issue to whoever should implement it; on `help-wanted` and `good-first-issue` issues a comment starting with `assign me` assigns the commenter right away, on any other issue the bot answers that a maintainer decides. Up to two assignees per issue.
- **`factory`** delegates the implementation to the coding agent; only maintainers can set it, and removing it stops the agent.
  **`state/needs-maintainer`** is the agents' escalation and the one label engineers watch; it disappears when a maintainer answers.

## Rules

- A label the manifests do not define does not exist. Labels change in `hotcodepush-team/.github` under `labels/`, never in a repository's settings.
- Never close an issue during triage. Parking it in a `state/*` is the triage answer; closing is a maintainer's click.
- When the type is not obvious from the issue, ask in a comment and set `state/needs-reply` rather than guessing.
