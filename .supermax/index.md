# Project Knowledge Index

Purpose: route Agents to the smallest relevant project-knowledge area. Read only the selected next-hop index.

| Area | Read when | Entry |
| --- | --- | --- |
| Inbox | Capturing or reviewing uncurated project feedback and notes | [[inbox/index]] |
| Specifications | Reading or maintaining behavior and change specifications | [[specs/index]] |
| Wiki | Retrieving curated, reusable project knowledge | [[wiki/index]] |
| Attachments | Locating durable shared files without a more specific owner | [[attachments/index]] |
| Canvases | Locating Obsidian Canvas artifacts | [[canvases/index]] |

Workflow-owned zones:

- `tasks/`: TaskAdmin storage; load `task-admin` before task operations.
- `drafts/`: temporary, Git-ignored drafts; promote explicitly before treating as durable knowledge.
- `translate-cache/`: generated, Git-ignored translation/cache content.

Validation: each maintained category above owns an `index.md` and `log.md`; parent indexes route only to direct children or directly owned files.
