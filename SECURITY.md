# Security policy

## Secrets and permissions

Store `AUTH_SECRET` and `CLIENT_ID` as secrets in the GitHub Environment named `DNS`. The scheduled workflow exposes
them only to the final DnsConf process. Use provider credentials scoped to one account and only the permissions needed
to edit DNS Gateway rules, lists, deny entries, and rewrites.

## Safe operation

- Keep `ALLOW_CLEAR` unset or `false` unless an intentional clear is required.
- Use manual `dry_run` before applying a new source or configuration.
- Keep `DNSCONF_OWNER_ID` stable so DnsConf can distinguish its objects from unrelated account objects.
- Only public HTTPS list and DoH endpoints are accepted.
- Review failed workflow logs promptly. API, authentication, rollback, timeout, and pagination failures return a
  non-zero exit code.

## Reporting a vulnerability

Do not publish credentials or account identifiers in an issue. Revoke any exposed provider token immediately, then
open a private GitHub security advisory for this repository with reproduction steps and affected versions.
