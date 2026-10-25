# Specifications

These documents define the stable, script-facing compatibility contracts for
Hydraulic's `url` and `run` commands. An independent implementation can conform
without using the same HTTP client, cache library, archive library, JavaScript
engine, progress renderer, or on-disk layout.

| Specification                                 | Applies to                 | Covers                                                                                                                                  |
|-----------------------------------------------|----------------------------|-----------------------------------------------------------------------------------------------------------------------------------------|
| [`url` command](url.md)                       | `url` CLI                  | Operands, stdin, named inputs, options, output, diagnostics, and exit statuses.                                                         |
| [`run` command](run.md)                       | `run` CLI and run packages | Locators, JavaScript host functions, composition, launch plans, permissions, dry runs, and package timestamps.                          |
| [Shared URL resolution](shared-resolution.md) | Both commands              | URL normalization, HTTP and caching, proxies, executable permissions, macOS quarantine, archives, free-space checks, and SHA-256 locks. |

Read each command's specification together with the shared resolution contract.
Requirements using **must**, **must not**, **should**, and **may** are normative;
examples and implementation notes are informative.
