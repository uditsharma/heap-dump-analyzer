# heap-dump-analyzer

A skill for analyzing Java heap dumps. Sets up Eclipse MAT + a custom `heap-oql` CLI plugin from scratch, then walks through structured heap analysis.

Works with any AI coding agent that can run shell commands — Claude Code, Codex, or others.

## What it does

- Installs Eclipse MAT if not present
- Installs Java 17+ via sdkman if needed
- Builds and deploys the `heap-oql` plugin (OQL queries, histograms, instance lists, field inspection — all as pipeable TSV)
- Auto-sizes MAT's heap based on dump file size
- Guides through a phased analysis workflow: reports, class histograms, instance deep-dives, OQL queries, memory sizing

## Install

**Claude Code:**
```bash
claude install-skill gh:uditsharma/heap-dump-analyzer
```

**Codex:**
```bash
# Per-user (works across all repos)
git clone git@github.com:uditsharma/heap-dump-analyzer.git ~/.agents/skills/heap-dump-analyzer

# Or per-repo
git clone git@github.com:uditsharma/heap-dump-analyzer.git .agents/skills/heap-dump-analyzer
```

Codex discovers skills automatically from `~/.agents/skills/`, `$REPO_ROOT/.agents/skills/`, or `.agents/skills/` in the current directory. Follows the [agentskills.io](https://agentskills.io/specification) specification.

**Gemini CLI:**
```bash
gemini skills install https://github.com/uditsharma/heap-dump-analyzer.git
```

**Other agents:**
Copy `SKILL.md` into your agent's instructions or context.

## Usage

Once installed, just give your agent a heap dump file and ask it to analyze:

```
> analyze the heap dump at /path/to/dump.hprof
> what's using the most memory in this heap dump?
> how much memory per entry does this cache use?
> run an OQL query to find all HashMap instances over 1MB retained
```

The skill activates automatically when the agent detects a heap analysis task.

## Requirements

- Java 17+ (skill will guide you through sdkman install if needed)
- ~100MB disk for Eclipse MAT download

## What is heap-oql?

`heap-oql` is a custom Eclipse MAT plugin that exposes MAT's analysis engine as a CLI tool. MAT's built-in `ParseHeapDump.sh` doesn't support OQL queries or programmatic inspection. This plugin registers as an OSGi application inside MAT's runtime, giving it full access to `SnapshotFactory`, the OQL engine, and all MAT APIs.

**Modes:**
- `heap-oql <dump> oql "SELECT OBJECTS ..."` — run OQL queries
- `heap-oql <dump> histogram <pattern>` — class-level memory breakdown
- `heap-oql <dump> instances <class>` — per-instance retained sizes
- `heap-oql <dump> fields <class>` — field values for every instance

All output is TSV on stdout, pipe to `sort`, `awk`, `column -t`, etc.
