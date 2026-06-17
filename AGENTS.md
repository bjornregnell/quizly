# Quizly Agent Notes

This repository is a minimal quiz web app built as a Scala multi-project. It has a JVM Jetty server, a Scala.js Laminar SPA client, and a shared cross-compiled data model.

## Current Behavior

- The SPA is served at `/quizly/` on the SPA port, default `8096`.
- The API is served on the API port, default `8095`.
- The deployed bjornix URLs are:
  - SPA: `http://bjornix.cs.lth.se:8096/quizly`
  - API summary: `http://bjornix.cs.lth.se:8095/api/quizzes/summary`
- The app asks for a user name and answers to two fixed propositions:
  - `The war will end in 2026`
  - `The current government will remain in power after the election`
- Each answer is a radio choice: `true`, `false`, or `No answer yet`.
- Answers are stored by fixed question id.
- The SPA shows a per-question summary counting true, false, and no-answer-yet responses.
- Stored user records can be loaded or deleted from the SPA.
- Data is in memory only. Restarting the server clears all user records.

## Project Layout

- `build.sbt` defines the multi-project build.
- `common/` is a Scala.js/JVM cross-project containing shared model and JSON codecs.
- `client/` is the Scala.js Laminar SPA.
- `server/` is the JVM Jetty server.
- `client/index.html` is the static HTML shell for the SPA.
- `clean-build-and-run.sh` builds and starts a local one-process Jetty setup from a clean build.
- `rebuild.sh` restarts the local Jetty setup faster for browser testing without `sbt clean` or assembly by default.
- `deploy.sh` builds, copies artifacts to bjornix, and restarts the remote screen session.
- The old single-project sources under `src/main/scala/quizly` have been removed from the active architecture.

## Build Details

- Use sbt 1.x, currently `sbt.version=1.12.12`.
- Always prefer `sbt --client` for commands in this repo.
- Use the repo-scoped `$quizly-verify` Codex skill for compile, build, local runtime, deployment, and verification workflows when it is available.
- Scala version is `3.9.0-RC1`.
- Main dependency versions currently in `build.sbt`:
  - Jetty `12.1.10`
  - Laminar `18.0.0-M5`
  - scala-js-dom `2.8.1`
  - uPickle `4.4.3`
- Useful commands:
  - `sbt --client compile`
  - `sbt --client server/assembly`
  - `sbt --client client/fastLinkJS`

## Scala Style

- Default to non-private `def`s and non-private `val`s.
- Only use `private val`, `private var`, or `private def` when there is mutability and privacy is needed to preserve state integrity of the object, class, or trait.
- Prefer direct style code. Avoid staged effect-system style and similar effect abstraction layers unless they are already required by surrounding code or by a concrete integration.
- Prefer Scala 3 brace-less style with significant indentation for new code and edits.

## Metals MCP

Metals can expose Scala LSP context to AI agents through an HTTP MCP server. Use it when available for Scala-aware navigation, diagnostics, symbols, definitions, and other LSP-style context.

- This repo commits `.vscode/settings.json` with `"metals.startMcpServer": true`, so VS Code Metals should start its MCP server when the workspace is opened.
- Metals writes the current MCP endpoint to `.metals/mcp.json` and logs it in `.metals/metals.log`.
- `.metals/mcp.json` is intentionally not committed because it contains a machine-local, restart-dependent localhost port.
- To connect Codex to the current Metals MCP endpoint, read `.metals/mcp.json` and add/update the matching Codex MCP server, for example:

```bash
codex mcp add quizly-metals --url http://localhost:35963/mcp
```

- If the port changes after restarting Metals, remove and re-add the Codex MCP entry:

```bash
codex mcp remove quizly-metals
codex mcp add quizly-metals --url "$(python3 -c 'import json; print(json.load(open(".metals/mcp.json"))["servers"]["quizly-metals"]["url"])')"
```

- After changing Codex MCP config, restart Codex or start a new Codex session. In the Codex TUI, use `/mcp`; from a shell, use `codex mcp list`.
- If `.metals/mcp.json` does not exist, open the repo in VS Code with the Scala Metals extension enabled, then restart/import Metals.
- Do not commit `.vscode/mcp.json`, `.metals/`, `.bsp/`, or any Codex user config with a hardcoded localhost Metals port. Those are local state.

## Shared Model

The shared model lives in `common/src/main/scala/quizly/common/Quiz.scala`.

- `Quiz` is the authoritative fixed-question catalog.
- `Quiz.Id` is an alias for `Int`.
- `Quiz.Question` is an alias for `String`.
- `Quiz.questions: Map[Quiz.Id, Quiz.Question]` maps question ids to propositions.
- `User(name: String, answers: Map[Quiz.Id, Option[Boolean]])`
- `None` means no answer has been given yet for that question id.
- uPickle `ReadWriter` instances are generated in the companion objects.

## Server Architecture

The server entry point is `quizly.server.QuizServer`.

- It starts two Jetty connectors:
  - API connector: arg `--api-port`, then env `API_PORT`, then env `PORT`, else `8095`
  - SPA/static connector: arg `--spa-port`, then env `SPA_PORT`, else `8096`
- Static files are read from arg `--static-dir`, then env `STATIC_DIR`, or by default from the directory beside the running jar/classpath.
- Static routes:
  - `GET /quizly` redirects to `/quizly/`
  - `GET /quizly/` and `/quizly/index.html` serve `index.html`
  - `GET /assets/main.js` serves `main.js`
- API routes:
  - `GET /api/quizzes` returns all user records.
  - `GET /api/quizzes/summary` returns per-question counts.
  - `GET /api/quizzes/{name}` returns one user record.
  - `POST /api/quizzes` creates or replaces one user record from a JSON `User`.
  - `POST /api/quizzes/delete?name=...` deletes one user record.
- The server uses `ConcurrentHashMap[String, User]`.
- The internal key is the trimmed user name, so one name has one answer map for all fixed questions.
- The tiny router handles `GET`, `POST`, and `OPTIONS`; `HEAD` is not implemented.
- CORS is currently permissive with `Access-Control-Allow-Origin: *`.

## Client Architecture

The SPA entry point is `quizly.client.QuizClient`.

- It is a Laminar app mounted into `<main id="app"></main>`.
- It computes the API base from the current browser scheme/host and hardcodes API port `8095`.
- It stores UI state in Laminar `Var`s:
  - name
  - answers by question id
  - stored users
  - summary
  - message
- It communicates with the API using `fetch`.
- JSON is encoded/decoded with uPickle and the shared model from `common`.

## Local Run Workflow

Use:

```bash
./clean-build-and-run.sh
```

The script:

- Runs `sbt --client clean`.
- Runs `sbt --client server/assembly`.
- Runs `sbt --client client/fastLinkJS`.
- Copies `client/index.html` to `server/target/scala-3.9.0-RC1/index.html`.
- Copies generated `main.js` to `server/target/scala-3.9.0-RC1/main.js`.
- Starts `java -jar server/target/scala-3.9.0-RC1/quizly.jar` with `--api-port`, `--spa-port`, and `--static-dir`.

For faster local browser testing after edits, use:

```bash
./rebuild.sh
```

The script:

- Stops a running local Quizly server on the configured API/SPA ports.
- Runs `sbt --client client/fastLinkJS` and `sbt --client server/writeServerClasspath` without `sbt clean`.
- Copies the SPA shell and generated client JavaScript beside the server jar.
- Starts `java -cp ... quizly.server.QuizServer` and keeps it running in the foreground.
- Stores runtime state in `tmp/quizly-pid` and `tmp/quizly.log`.
- Prints the SPA URL for browser testing.

To rebuild and run the assembled jar instead:

```bash
QUIZLY_REBUILD_MODE=assembly ./rebuild.sh
```

To start it in the background instead:

```bash
QUIZLY_BACKGROUND=1 ./rebuild.sh
```

Local browser URL:

```text
http://localhost:8096/quizly
```

Useful local verification:

```bash
curl http://localhost:8095/api/quizzes/summary
curl http://localhost:8096/quizly/
curl http://localhost:8096/assets/main.js
```

## Deploy Workflow

Use:

```bash
./deploy.sh
```

The script:

- Runs `sbt --client server/assembly`.
- Runs `sbt --client client/fastLinkJS`.
- Copies `quizly.jar`, `client/index.html`, generated `main.js`, and `main.js.map` if present to `bjornix:/home/bjornr/quizly/`.
- Restarts the remote `screen` session named `quizly`.
- Starts the server with `--api-port`, `--spa-port`, and `--static-dir`.

Override ports like this:

```bash
API_PORT=9001 SPA_PORT=9002 ./deploy.sh
```

The deploy script reads local `API_PORT` and `SPA_PORT` overrides and passes them to the remote server as program arguments.

Remote screen basics:

```bash
ssh bjornix screen -ls
ssh bjornix screen -S quizly -X quit
```

## Important Notes

- Do not reintroduce a Python static file server; Jetty serves the SPA static files.
- The jar does not currently embed `index.html` or `main.js`; they are deployed beside the jar and read from `STATIC_DIR`.
- If the SPA HTML loads but the app does not start, check that `/assets/main.js` returns `200 OK`.
- If API calls fail from the SPA, remember that the client currently assumes the API is on the same host at port `8095`.
- Remote deployment has been verified with Jetty responding on bjornix ports `8095` and `8096`.
