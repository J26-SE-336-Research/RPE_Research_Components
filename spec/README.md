# RPE protocol specification (C1)

**Owner:** C1 Ruwanmali. **Status:** draft v0.9 (PP1, 20 October 2026).

[`RPE-v1.md`](RPE-v1.md): the RFC-style specification of the RPE v1 header set (`X-RPE-Context`, `X-RPE-Hop`,
`X-RPE-Trail`, `X-RPE-Result`) with rule IDs (R-T, R-D, R-R, R-C, R-F, R-B, R-P, R-V, R-S), signal-mode deadline
semantics, the retry gate, the in-band trail, error and status codes, and the conformance requirements.

Every rule is backed by the shared conformance cases in `conformance-kit/vectors`. v1.0 follows after the wire-format
(E2), trail-encoding (E6) and clock-skew (E7) studies.
