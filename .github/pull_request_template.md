Describe the observed problem and the resulting behavior. Name the affected workflow phase or event.

Validation evidence:

- Commands and actual results (including skipped integration tests).
- Failure/replay scenario covered and what persisted after restart.
- Any schema migration and backward-compatibility implications.

Review questions:

- Could a retry reserve/charge/apply an event twice?
- Is the business update atomic with its outbox/consumer receipt?
- What happens if the dependency times out after performing the effect?
- Can money, stock, event ordering, or authentication invariants be violated?
- Are reported metrics reproducible from committed scripts and raw observations?

Do not replace missing measurements with estimated achievements.
