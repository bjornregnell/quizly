---
name: quizly-verify
description: Build and verify the Quizly Scala multi-project. Use when changing Scala, Scala.js, sbt, Jetty server, Laminar client, shared common model, deployment scripts, README/AGENTS workflow docs, or when the user asks to compile, test, run, verify, deploy, or diagnose build/runtime errors in this repo.
---

# Quizly Verify

Use this skill to choose the right Quizly verification workflow. Keep checks proportional to the change, but prefer proving behavior with commands over guessing.

## Workflow

1. Read `AGENTS.md` first if you do not already have fresh repo context.
2. If Metals MCP is available, use it for Scala diagnostics, definitions, references, symbols, hover/inferred-type context, and code actions. If Metals MCP is unavailable, continue with normal file reads and sbt checks.
3. Always use `sbt --client` for sbt commands in this repo.
4. For ordinary Scala or build changes, run:

```bash
sbt --client compile
```

5. For server, deployment, static-file, or packaging changes, also run:

```bash
sbt --client server/assembly
```

6. For client, Laminar, Scala.js, or shared JS model changes, also run:

```bash
sbt --client client/fastLinkJS
```

7. For shell script edits, run syntax checks:

```bash
bash -n clean-build-and-run.sh
bash -n deploy.sh
```

8. For local runtime verification, use `./clean-build-and-run.sh` when appropriate. It starts the server and serves the SPA. Verify:

```bash
curl http://localhost:8095/api/quizzes/summary
curl http://localhost:8096/quizly/
curl http://localhost:8096/assets/main.js
```

9. For deployment verification after `./deploy.sh`, check:

```bash
curl http://bjornix.cs.lth.se:8095/api/quizzes/summary
curl http://bjornix.cs.lth.se:8096/quizly/
curl http://bjornix.cs.lth.se:8096/assets/main.js
```

## Notes

- Do not use plain `sbt` unless the user explicitly asks; use `sbt --client`.
- Do not reintroduce a Python static file server. Jetty serves static files from `STATIC_DIR`.
- Do not commit generated local MCP files such as `.metals/mcp.json` or `.vscode/mcp.json`.
- If a command cannot run because of sandbox or network restrictions, request scoped approval and rerun the same command.
- Report which checks were run and any checks that could not be run.
