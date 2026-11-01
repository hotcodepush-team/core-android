# Code review criteria

Review every pull request against these criteria and nothing else.
Report a finding only where a criterion is missed; say nothing about what is fine.

| Criterion                 | What to check                                                                                                                                                                                                  |
| ------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Linked issue              | For an author without write access: the body links an issue with `Closes #`, `Fixes #` or `Resolves #`, and the diff implements that issue. Bots and maintainers are exempt, as in the contributions workflow. |
| Conventional-commit title | `<type>(<scope>)!: <subject>`, the type one of `feat`, `fix`, `docs`, `perf`, `refactor`, `test`, `build`, `ci`, `chore`. The title becomes the squash commit release-please reads.                            |
| Tests                     | Behavior that changed has a test that would fail without the change; a rule of the device protocol has its case in the protocol's `fixtures/`. A test that asserts nothing is worse than no test.              |
| Docs                      | Anything a consumer can observe is documented where it is documented today — the README or the docs site.                                                                                                      |
| No unrelated changes      | Everything in the diff serves the linked issue. A drive-by improvement is a finding, however good it is.                                                                                                       |
| No secrets                | No token, key, password, connection string or credential, in code, in a fixture, in a test or in a comment.                                                                                                    |
| No customer data          | No customer name, email address, organization, app id or log line. Fixtures and examples use invented data.                                                                                                    |
| Clean code                | As defined in `CLAUDE.md`: names that reveal intent, small functions doing one thing, no duplication, no speculative abstraction, no comment that a better name would replace.                                 |

## How to phrase a finding

- Name the file, the line and the criterion.
- Say what is wrong and why, then what would be right.
- One finding per comment.
