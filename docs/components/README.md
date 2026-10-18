# RPE Component Guides (J26-SE-336)

There is one self-contained A-to-Z guide per student. Each guide contains:
- the full proposal commitments (a checklist; nothing may be removed);
- the groomed, defensible novelty and how to defend it;
- the detailed design;
- contracts;
- a reuse guide for the legacy code in `_research/old_shits/` (local only, see below);
- a phased implementation plan;
- the evaluation plan;
- the Admin Console tab spec;
- the viva/demo script;
- the Definition of Done;
- the **shared ecosystem reference** (Appendix A1–A9, identical in all four files).

| Component | Student | Guide |
|---|---|---|
| C1 Protocol & Specification Layer | Ruwanmali K.H (IT23151642) | [C1-Protocol-Ruwanmali.md](C1-Protocol-Ruwanmali.md) |
| C2 Service Interceptor Layer | R.R.D Pinto (IT23319806) | [C2-Interceptor-Pinto.md](C2-Interceptor-Pinto.md) |
| C3 Kubernetes Enforcement Layer | Kalhara D.M.R (IT23139626) | [C3-K8s-Enforcement-Kalhara.md](C3-K8s-Enforcement-Kalhara.md); current status and plan: [C3-Status-and-Plan.md](C3-Status-and-Plan.md) |
| C4 Resilience Intelligence Layer | R.M.S.B. Rathnayake (IT23289420) | [C4-Intelligence-Rathnayake.md](C4-Intelligence-Rathnayake.md) |

## Working with Claude Code

From the repository root:

```
claude "Read docs/components/<your-file>.md completely before doing anything. Then <task>."
```

Each guide's §0 lists example tasks and the rules Claude must follow for that component.

## Keeping the guides in sync

- The **Appendix** (shared contracts, naming, repo layout, demo app, console, toolchain, hard rules, related work) is identical in all four files. A change to it must be agreed by the team and copied into all four files, with the contract version bumped.
- Each student updates **§2 Status** in their own file after finishing a phase.
- The guides are committed. Shared contracts are at **v0.2**: the v0.1 target design plus "Implemented today" notes (16 Oct 2026).
- Background documents (all in `_research/`, which is gitignored and local only; share it with teammates outside git; this includes the proposal PDFs and the legacy code in `old_shits/` that the guides reference): `RPE_Groomed_Scope.md` (team summary), `RPE_Components.md` (the original overview, superseded where it conflicts with the proposals) and `RPE-M1-Scope-Ruwanmali.md` (C1's scope notes, integrated into the C1 guide).
