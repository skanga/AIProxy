# Common inference implementation

## Contract
- Shared 60-second read-idle deadlines on inference HTTP bodies; OAuth unchanged.
- Read timeout before response commitment => 504; committed streams retain status and terminate with protocol-specific error where possible.
- Safe correlation and metadata-only diagnostics; bounded upstream error reads.
- Explicit generation parameters must be honored or rejected, not silently removed or clamped.
- Missing/null optional generation settings preserve omission; Messages supplies a required token default only at encoding.
- Equal Chat token aliases accepted; conflicting values rejected. Shared scalar validation; capability/profile restrictions remain separate.
- Qualified/replay requests and committed streams never fail over; failover remains opt-in and must preserve explicit settings.

## Milestones
1. [x] RED/GREEN shared transport, timeout mappings, bounded errors, correlation.
2. [x] RED/GREEN parameter intent, validation, Codex strict explicit settings.
3. [x] RED/GREEN capability checks, failover eligibility, discovery alignment.
4. [x] Documentation, full test suite, diff review.

## Verification
Use existing provider contract tests plus loopback HTTP stalled reads and stream tests. Add malformed/conflicting/omitted parameter cases and inspect captured upstream requests. Run focused Maven tests each milestone and `mvn test` at completion. No live upstream calls or credential changes.

## Results
- `mvn test`: 875 tests, zero failures/errors/skips.
- `mvn package -DskipTests`: passed; artifact remains `target/AIProxy-5.3.jar`.
- `git diff --check`: passed (existing line-ending warning only).
- Regression tests cover all translated provider/protocol paths, native Messages relay timeouts, real stalled HTTP reads, explicit parameter validation, omission, supported/unsupported failover candidates, and correlation headers.
- Live providers were not contacted. Model-specific sampling combinations not established by catalog/profile facts remain upstream-validated on primary routes. Normalized Messages translation conservatively rejects sampling controls combined with adaptive thinking; native Messages remains passthrough.

## Compatibility
Codex CLI requests with formerly ignored token caps will now return 400. Explicit reasoning settings will no longer be silently coerced. Model-alias defaults remain supported. Unsupported or unverified capabilities must not be fabricated. Native Messages payload passthrough remains separate from Chat/Responses normalization.
