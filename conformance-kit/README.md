# RPE conformance kit (C1)

Language-neutral test vectors for the RPE v1 header set, and a runner for each implementation. An implementation
conforms when it passes every vector. `rpe-go` and `rpe-java` both pass all of them.

| File | What it checks | Cases |
|---|---|---|
| `vectors/context-decode.json` | `X-RPE-Context` decoding: field values, canonical re-encoding, error code (and rule) of invalid input | 46 |
| `vectors/context-encode.json` | Canonical encoding from field values | 5 |
| `vectors/context-transition.json` | Context guard R-T01..R-T06 for a parent → child step | 15 |
| `vectors/context-child.json` | Child derivation (ttl, rb, pid) and R-T03 | 6 |
| `vectors/budget-split.json` | Retry-budget split across fan-out children (R-F05) | 7 |
| `vectors/result.json` | `X-RPE-Result` decoding, defaults and canonical encoding | 10 |
| `vectors/hop.json` | `X-RPE-Hop` decoding and canonical encoding | 7 |
| `vectors/traceparent.json` | Trace binding (trace-id of a W3C traceparent) | 7 |
| `vectors/retry-gate.json` | Retry gate R-R02: allowed or refused, and the reason, in a fixed order | 16 |
| `vectors/trail.json` | `X-RPE-Trail` decoding (verbose v1 entries) and canonical encoding | 25 |
| `vectors/trail-merge.json` | Response-side trail merge: re-indexing, bounds, registry mismatch (R-P01..R-P03) | 6 |

150 cases in total (103 at the first release, 47 added for PP1 on 20 Oct 2026). The specification is `spec/RPE-v1.md`.

Errors are compared by **code** (for example `OUT_OF_RANGE`) and **rule** (for example `R-T02`), never by message text.

## Running

```bash
cd conformance-kit/go && go test ./...      # rpe-go
cd libs/rpe-java && ./mvnw -q test          # rpe-java (ConformanceTest + DifferentialTest)
```

`make test-unit` runs both.

The protocol service runs the same cases live for the Admin Panel (Protocol Tools → Conformance): rpe-go in process
(`conformance.RunAll`) and rpe-java in its own container (`services/protocol-service/java`).

## Differential testing

`go run ./cmd/difffuzz -n 5000 -seed 1 ../generated/differential.json` writes a deterministic corpus of mutated
headers (deleted, inserted, replaced and truncated characters, duplicated and swapped members) together with the
verdict of the Go reference: the canonical re-encoding, or the error code and rule. `rpe-java`'s `DifferentialTest`
replays the corpus and fails on any divergence. Change the seed (`RPE_FUZZ_SEED=7 make test-unit`) to explore
different inputs.

## Adding vectors

`make test-unit` also checks that the committed vectors are exactly what the generator produces
(`conformance-vectors-current`), so a change to the reference library that would change a vector cannot go unnoticed.

Vectors are generated once from the reference implementation by `go run ./cmd/genvectors ../vectors`, reviewed and
committed. Add a case to `cmd/genvectors/main.go`, regenerate, review the diff, and run both runners. A vector change
is a spec change: note it in the spec's change log.
