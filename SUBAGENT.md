# SUBAGENT.md - delegation entrypoint

`docs/SUBAGENT.md` is the canonical delegation protocol. Before spawning subagents or parallel tasks, read that file and the relevant active ExecPlan.

Short rules:

- Assign a **bounded outcome**, allowed files, dependencies, acceptance criteria, and expected report.
- Run independent research/tests in parallel; serialize work that edits the same architectural or API-contract files.
- Subagents may implement and validate their scoped tasks, but cannot independently alter accepted ADRs, reveal keys, publish releases or push code.
- An integrator is responsible for cross-task tests, coherent documentation and final readiness decisions.
- If no subagent capability exists, do the same task sequentially; do not pretend delegation took place.

## v0.6 coordination

- For visual/UI work, the maintainer is the design authority. Read
  `docs/design/DESIGN_BRIEF.md`, `docs/specs/v0-6-design-overhaul.md`, and
  the individual screen approval record before delegating implementation.
- Use the project Codex `implementer` role for ordinary scoped code tasks
  and `quality-assurance` for independent high-reasoning review.
- Do not exceed the agreed concurrency or spend budget just to add agents.
