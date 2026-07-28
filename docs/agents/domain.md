# Domain Docs

How engineering skills should consume this repo's domain documentation.

## Before exploring, read these

- `CONTEXT.md` at the repo root
- Relevant ADRs under `docs/adr/`

If these files do not exist, proceed silently. Create them lazily when domain terms or architectural decisions are established.

## File structure

This is a single-context repo:

```
/
├── CONTEXT.md
├── docs/adr/
└── src/
```

## Use the glossary's vocabulary

When naming a domain concept, use the term defined in `CONTEXT.md`. Avoid synonyms that the glossary explicitly rejects.

If a needed concept is missing, consider whether the project language should be updated through domain modeling.

## Flag ADR conflicts

If an output contradicts an existing ADR, surface the conflict explicitly rather than silently overriding it.
