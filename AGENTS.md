AGENTS.md

This repository is primarily developed and maintained by AI coding agents.

The purpose of this file is to give any new agent enough information to enter the repository, understand the current project state, make changes safely, and leave the repository in a state that another agent can continue from later.

Treat the repository itself as the persistent memory of the project. Do not rely on previous chat sessions being available.

⸻

1. Required startup procedure

Before making any non-trivial change, inspect the repository and read:

1. AGENTS.md
2. README.md
3. docs/STATE.md
4. docs/ARCHITECTURE.md
5. docs/DECISIONS.md when the task involves architecture, data models, APIs, dependencies, or previously made design choices
6. Relevant source files and tests
7. Recent Git history when useful for understanding recent work

Recommended Git inspection:

git status
git log --oneline -10

Do not assume documentation is perfectly current. Verify important facts against the actual code.

⸻

2. Project documentation roles

Keep each document focused on its role.

README.md

Purpose:

* Explain what the project is
* Explain its main capabilities
* Explain how to install and run it
* Explain basic usage
* Give a new human or agent a high-level orientation

Do not use README as a development diary.

Update it when user-facing capabilities, setup instructions, commands, dependencies, or major project structure materially change.

⸻

docs/ARCHITECTURE.md

Purpose:

* Describe the current system architecture
* Explain major modules and their responsibilities
* Describe important data flows
* Identify important interfaces and boundaries
* Record architectural constraints that remain true

This document should describe the system as it currently exists, not its historical evolution.

Update it when architecture materially changes.

Keep it concise enough that a new agent can read it quickly.

⸻

docs/STATE.md

This is the primary cross-session handoff document.

Purpose:

* Explain what the project currently does
* State the current development goal
* Record recently completed significant work
* Record unfinished work
* Record known bugs or limitations
* Point to files that are currently important
* Capture short-lived context that another agent may need in the next session

This document should answer:

If another competent coding agent opened this repository with zero chat history, what would it need to know to continue the work correctly?

Update docs/STATE.md after every meaningful development task.

Remove obsolete information instead of endlessly appending history.

Do not turn this file into a changelog.

Recommended structure:

# Current State
## Current goal
...
## Current system state
...
## Recently completed
...
## In progress / next work
...
## Known issues
...
## Important files
...

⸻

docs/DECISIONS.md

Purpose:

Record design decisions that future agents might otherwise reconsider or accidentally reverse.

Examples:

* Why SQLite was chosen
* Why a certain API style is used
* Why a dependency was rejected
* Which component owns a particular piece of state
* Whether the server or client is authoritative
* Important security or compatibility constraints

Only record decisions that are likely to matter again.

Each decision should contain:

* Decision
* Reason
* Consequences, if relevant

Do not record trivial implementation details.

Do not use this file as a chronological work log.

⸻

3. Source of truth hierarchy

When information conflicts, use this priority:

1. Explicit instructions from the user in the current task
2. Actual working code and tests
3. AGENTS.md
4. docs/DECISIONS.md
5. docs/ARCHITECTURE.md
6. docs/STATE.md
7. README.md
8. Comments and older Git history

If documentation disagrees with the implementation, investigate why.

If the implementation is correct and the documentation is stale, update the documentation as part of the task.

Do not blindly follow stale documentation.

⸻

4. Development principles

Prefer simple, maintainable solutions suitable for a small project.

Do not introduce infrastructure, abstractions, dependencies, frameworks, patterns, or configuration unless they solve a concrete current problem.

Prefer:

* clear code over clever code
* small modules over unnecessary abstraction layers
* existing project patterns over introducing new patterns
* root-cause fixes over symptom patches
* deleting obsolete code over preserving unused compatibility
* explicit behavior over hidden magic

Avoid speculative engineering for hypothetical future requirements.

Do not create abstractions solely because they might become useful later.

⸻

5. Before modifying code

For each task:

1. Understand the requested behavior.
2. Locate the relevant code path.
3. Understand the root cause before modifying code when fixing a bug.
4. Check for nearby tests and existing conventions.
5. Determine whether the requested change conflicts with an existing architectural decision.
6. Make the smallest coherent change that solves the problem.

Do not edit unrelated code merely because you noticed possible improvements.

If unrelated issues are discovered, record important ones in docs/STATE.md under known issues if they are worth preserving.

⸻

6. Testing and verification

After modifying code:

1. Run the most relevant tests.
2. Run broader tests when the change affects shared behavior.
3. Run linting, type checking, formatting, or build checks if the project provides them.
4. Verify the actual behavior when feasible.

Do not claim tests passed unless they were actually run.

If verification cannot be completed, clearly record what was and was not verified.

Whenever a bug is fixed, add or update a regression test when practical.

⸻

7. Dependency policy

Do not add a new dependency when the task can be solved reasonably with the standard library or existing dependencies.

Before adding a dependency, consider:

* Is it actively needed?
* Does the project already have equivalent functionality?
* Is the added complexity justified?
* Will this increase long-term maintenance burden?

Remove dependencies that become genuinely unused.

⸻

8. Documentation maintenance

Documentation maintenance is part of implementation, not a separate optional task.

After completing a meaningful task, evaluate whether any of these need updating:

* README.md
* docs/ARCHITECTURE.md
* docs/STATE.md
* docs/DECISIONS.md
* AGENTS.md

Do not update files unnecessarily.

Update AGENTS.md only when:

* repository-wide working rules change
* important commands change
* documentation structure changes
* agents repeatedly make the same avoidable mistake
* a durable instruction should apply to future tasks

Do not put temporary project status into AGENTS.md.

Update STATE.md when:

* meaningful work was completed
* current goals changed
* unfinished work remains
* a meaningful bug or limitation was discovered
* another session would benefit from knowing something

Update ARCHITECTURE.md when:

* modules or responsibilities change
* major data flow changes
* important interfaces change
* system boundaries change

Update DECISIONS.md when:

* a non-obvious design decision is made
* alternatives were considered and future agents might reopen the question
* an important constraint needs to survive across sessions

⸻

9. Keep documentation compact

Persistent context has a cost.

Do not allow documentation to accumulate indefinitely.

Regularly:

* remove stale state
* remove completed temporary tasks
* consolidate duplicate explanations
* update descriptions instead of appending corrections
* delete obsolete decisions when they are no longer relevant
* keep only information useful to future work

Documentation should optimize for fast context recovery, not historical completeness.

Git history is the historical record.

⸻

10. Git as project memory

Use Git history when useful for understanding why code exists or what changed recently.

Write commit changes so they are logically coherent.

Do not use documentation as a substitute for Git history.

STATE.md should summarize current relevant context, while Git records detailed historical changes.

Never rewrite or destroy user work unless explicitly instructed.

Before making substantial changes, inspect git status to avoid overwriting unrelated work.

⸻

11. Handling unfinished work

Do not leave hidden assumptions in the chat context.

If a task cannot be fully completed, make the repository self-explanatory before stopping.

Record in docs/STATE.md:

* what was completed
* what remains
* what is currently blocking progress
* relevant files
* any important hypotheses or findings
* the most sensible next step

Another agent should be able to continue without access to the previous conversation.

⸻

12. End-of-task procedure

Before considering a meaningful coding task complete:

1. Review the resulting diff.
2. Remove accidental or unrelated changes.
3. Run appropriate verification.
4. Update relevant documentation.
5. Update docs/STATE.md.
6. Confirm that another agent could understand the resulting repository without this chat history.

The final task summary should state:

* what changed
* why
* what was verified
* any remaining limitations or follow-up work

Do not merely describe files changed; describe resulting behavior.

⸻

13. Autonomous maintenance

Agents are authorized to make small maintenance changes necessary to keep the repository healthy while completing the requested task, including:

* updating stale project documentation
* adding or adjusting relevant tests
* removing code made obsolete by the requested change
* correcting clearly stale comments
* maintaining docs/STATE.md

Do not expand the scope into unrelated refactoring or feature development without a concrete reason.

⸻

14. Rule for future agents

Assume every session may be the last session with access to its conversation history.

Any information necessary for correctly continuing the project later must exist in the repository, not only in the conversation.

The repository should always be left in a state where a new competent agent can answer:

* What is this project?
* How do I run it?
* How is it structured?
* What are the important design constraints?
* What is currently being worked on?
* What problems are known?
* Where should I start for the next task?

If those questions cannot be answered quickly from the repository, improve the project documentation before finishing the task.