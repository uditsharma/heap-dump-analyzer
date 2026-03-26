# heap-dump-analyzer

A Claude Code skill for analyzing Java heap dumps. Sets up Eclipse MAT + a custom `heap-oql` CLI plugin from scratch, then walks through structured heap analysis.

## What it does

- Installs Eclipse MAT if not present
- Installs Java 17+ via sdkman if needed
- Builds and deploys the `heap-oql` plugin (OQL queries, histograms, instance lists, field inspection — all as pipeable TSV)
- Auto-sizes MAT's heap based on dump file size
- Guides through a phased analysis workflow: reports, class histograms, instance deep-dives, OQL queries, memory sizing

## Install

```bash
claude install-skill gh:uditsharma/heap-dump-analyzer
```

## Usage

Once installed, just give Claude Code a heap dump file and ask it to analyze:

```
> analyze the heap dump at /path/to/dump.hprof
> what's using the most memory in this heap dump?
> how much memory per entry does this cache use?
> run an OQL query to find all HashMap instances over 1MB retained
```

The skill activates automatically when Claude detects a heap analysis task.

## Requirements

- [Claude Code](https://claude.ai/claude-code) CLI
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
