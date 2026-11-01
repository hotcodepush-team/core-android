---
name: create-pr
description: Commits the working tree with a conventional-commit message, pushes the branch and opens a pull request from the repository's template with a Closes # link. Use when a change is finished and needs a pull request. Do not use to review a pull request or to start work on an issue.
---

# Create a pull request

## Prerequisites

- `gh` installed and authenticated.
- An issue that this change resolves, assigned to the author.
- A branch that is not the default branch.

## Steps

1. **Read the change.** `git status` and `git diff` — including staged and unstaged. Anything unrelated to the issue comes out before the commit, not into it.
2. **Find the issue.** Take the number from the branch name (`<name>/issue-<n>`) or ask for it. Without an issue there is no pull request: `Closes #` is a required check.
3. **Write the commit message.**
   - Subject: `<type>(<scope>)!: <imperative subject>`, at most 72 characters. Types: `feat`, `fix`, `docs`, `perf`, `refactor`, `test`, `build`, `ci`, `chore`. `!` marks a breaking change.
   - Body: why, not what. Wrap at 72 characters.
   - Footer: `Closes #<issue>`.
4. **Commit and push the branch.** One commit per concern, and never to the default branch from this skill.
5. **Open the pull request.** `gh pr create` with:
   - the title equal to the commit subject — it becomes the squash commit release-please reads;
   - the body built from `.github/PULL_REQUEST_TEMPLATE.md`: `Closes #<issue>`, a *What and why* section, and the checklist with every item honestly ticked or left open;
   - `--draft` while anything on the checklist is still open.
6. **Report** the pull request URL.

## Rules

- One pull request, one issue, one concern.
- Never force-push a branch someone else may have pulled.
- Never commit a secret, a token, a customer name or a customer identifier — not in code, not in a fixture, not in a message.
