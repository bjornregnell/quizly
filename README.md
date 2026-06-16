# quizly

* A quiz web app using Scala, Jetty and Laminar. 

* The app demonstrates a simple direct-style app with a single page web app that use the server api for persistence across clients.

* For more information, see AGENTS.md

## Develop with agents

This repo includes `AGENTS.md` with architecture notes and agent-specific workflow hints. Read it before starting a new clean-context agent session.

For Scala-aware help, use the Metals MCP server from the VS Code Scala Metals extension.

### Start Metals MCP

1. Open this repo in VS Code with the Scala Metals extension installed.
2. The committed `.vscode/settings.json` enables `"metals.startMcpServer": true`.
3. Wait for Metals to start/import the build.
4. Check the generated `.metals/mcp.json` file for the current MCP URL.

Do not commit generated local MCP files such as `.metals/mcp.json` or `.vscode/mcp.json`; their localhost ports can change when Metals restarts.

### Codex

Add the current Metals MCP URL to Codex:

```bash
codex mcp add quizly-metals --url http://localhost:35963/mcp
```

Then restart Codex or start a new Codex session. In Codex, `/mcp` or `codex mcp list` should show the Metals server.

### Claude

Add the current Metals MCP URL to Claude as an HTTP MCP server named `quizly-metals`. The exact UI depends on which Claude surface you use, but the values should be:

```text
Name: quizly-metals
Transport/type: HTTP
URL: http://localhost:35963/mcp
```

Restart Claude or reconnect MCP tools after changing the configuration. Confirm that the `quizly-metals` MCP server is listed before expecting Scala-aware context.

### What Agents Gain

With Metals MCP connected, agents can use Scala LSP-style context such as diagnostics, symbol lookup, definitions, references, inferred types, and code actions when available. This complements, but does not replace, normal checks such as:

```bash
sbt --client compile
sbt --client server/assembly
sbt --client client/fastLinkJS
```

### Repo Skill

This repo also includes a Codex skill at `.agents/skills/quizly-verify`. It should trigger for build, compile, verification, runtime, and deployment work in this repo.

Codex discovers repo skills automatically when started inside the repository. You can also invoke it explicitly:

```text
$quizly-verify
```

To add another repo-scoped skill, create a folder under `.agents/skills/<skill-name>/` with a required `SKILL.md` file containing `name` and `description` frontmatter. Keep repo skills focused on reusable workflows rather than one-off commands.
