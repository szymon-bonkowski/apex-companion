# SUBAGENT.md - delegation entrypoint

`docs/SUBAGENT.md` is the canonical delegation protocol. Before spawning subagents or parallel tasks, read that file and the relevant active ExecPlan.

Short rules:

- Assign a **bounded outcome**, allowed files, dependencies, acceptance criteria, and expected report.
- Run independent research/tests in parallel; serialize work that edits the same architectural or API-contract files.
- Subagents may implement and validate their scoped tasks, but cannot independently alter accepted ADRs, reveal keys, publish releases or push code.
- An integrator is responsible for cross-task tests, coherent documentation and final readiness decisions.
- If no subagent capability exists, do the same task sequentially; do not pretend delegation took place.
