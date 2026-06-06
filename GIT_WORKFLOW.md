# Git Workflow for AI Agents

> Kimi / Claude / any AI: follow this exactly. No extra thinking needed.

---

## Step 1 — Stage Changes

```bash
# Source code (always include when changed)
git add src/

# Build config
git add pom.xml

# Plugin descriptor (if changed)
git add plugin.yml

# Compiled artifact (optional but recommended after mvn package)
git add target/

# Docs / config files
git add *.md *.yml *.yaml
```

**Shortcut — stage everything git-tracked + new src files:**
```bash
git add src/ pom.xml plugin.yml target/ *.md *.yml *.yaml 2>/dev/null || true
```

---

## Step 2 — Commit with Changelog

Format:
```
<type>: <short summary (imperative, 72 chars max)>

CHANGELOG:
- <what a server owner would notice>
- <what a server owner would notice>
```

**Types:**
| Type | When |
|------|------|
| `feat` | New feature, command, or mechanic |
| `fix` | Bug or crash fix |
| `perf` | Performance / memory improvement |
| `refactor` | Code change, no behavior change |
| `config` | pom.xml / plugin.yml / version bump |
| `docs` | Markdown / comments only |

**Examples:**
```
feat: add per-tier ore distribution stats GUI
fix: prevent NPE when chunk loads before world
perf: cache biome lookups per column, reduce DB hits 40%
config: bump to 2.1.0, update Paper API to 1.21.4
```

**Commit command:**
```bash
git commit -m "feat: your summary here

CHANGELOG:
- First notable change
- Second notable change"
```

---

## Step 3 — Push

```bash
git push origin main
```

---

## Rules
1. One commit per logical change. Do not batch unrelated changes.
2. Changelog bullets = what the server owner notices (not internal details).
3. Never stage: .zip, .args, apache-maven-*, IDE files. .gitignore handles these.
4. If mvn package was run, stage target/ so the latest build is in the repo.

---

## Visibility
Repo is private by default.
To make it public: GitHub > repo > Settings > Danger Zone > Change visibility > Public
This does NOT rewrite history, change branches, or affect future pushes.
