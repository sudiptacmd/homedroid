# Test report — 2026-09-30

## Environment and method

The repository contained no test suite or test dependencies. The initial command
`./gradlew testDebugUnitTest` stopped before running Gradle because Java was absent.
No Android SDK or emulator was available.

Added a standalone JVM regression harness under `tests/host/`. It compiles the
unchanged production files `Oci`, `Tar`, `Config`, `Supervisor`, `Deploys`, `Http`,
`Dashboard`, `Apps`, and `Paths`. Executed with Temurin JDK 17.0.20.1 and Kotlin 2.2.0.
Android 14 JSON classes come from Robolectric's published Android framework artifact;
other platform dependencies use explicit test doubles. See [README](README.md) for
setup, coverage, and limitations. No application code was changed.

Command used:

```sh
JAVA_HOME=/tmp/lindroid-test-tools/jdk-17.0.20.1+1 \
HOST_TEST_TOOLS=/tmp/lindroid-test-tools tests/host/run.sh
```

## Results

**73 tests: 61 passed, 12 failed.** Application source revision: `b390df9`.

Rechecked the test branch against current `main`. The original run on `96efbaf`
had 59 passes and the same 12 failures across 71 tests. This run also covers
HTTP 429/503 retries added on `main`; the redirect-loop assertion now allows the
current bounded request budget of 12. No production fixes are included.

| Requested area | Passed | Failed |
| --- | ---: | ---: |
| OCI reference parsing | 7 | 5 |
| Digest verification | 4 | 2 |
| Tar path traversal protection | 8 | 0 |
| Whiteout handling | 3 | 2 |
| Registry authentication | 9 | 1 |
| Configuration parsing | 7 | 0 |
| Supervisor restart/backoff | 3 | 1 |
| Deployment configuration | 12 | 0 |
| HTTP authentication/sessions | 8 | 1 |

Full assertions and observed failures: [results.txt](results.txt).
The runner exits with status 1 because the regression assertions reproduce defects.

## Findings

1. **OCI references: tag plus digest is parsed incorrectly.**
   `ghcr.io/org/app:v1@sha256:<digest>` leaves `:v1` in the repository component.
   Empty references, empty tags, empty repository paths, and malformed digests
   are also accepted by the parser. These account for five failed assertions.
   Source: [Oci.kt](../../app/src/main/java/dev/homedroid/Oci.kt), `parse`, lines 86–108.
   Expected syntax follows the [Distribution reference grammar](https://github.com/distribution/reference/blob/main/reference.go).

2. **Digest verification only covers layers.**
   Corrupt layer content and bytes appended after the tar end marker are rejected.
   However, a config blob that does not match its declared digest is accepted, as
   is a manifest returned for a digest-pinned reference with a mismatched digest.
   Source: [Oci.kt](../../app/src/main/java/dev/homedroid/Oci.kt), `fetchJson`, lines 111–112.

3. **Opaque whiteouts preserve content from previous layers.**
   `Tar` retains its `added` set across `extract` calls. `Oci.pull` reuses that
   extractor for all layers, so `d/old` survives a later `d/.wh..wh..opq` marker.
   Source: [Tar.kt](../../app/src/main/java/dev/homedroid/Tar.kt), lines 22–25 and `clearDir`.

4. **Ordinary whiteouts can delete additions in their own layer.**
   A replacement file followed by its whiteout is deleted. Whiteouts must affect
   only lower layers under the [OCI layer specification](https://github.com/opencontainers/image-spec/blob/main/layer.md#whiteouts).
   Source: [Tar.kt](../../app/src/main/java/dev/homedroid/Tar.kt), line 59.

5. **Registry token realms with existing queries produce malformed URLs.**
   `https://auth.test/token?existing=1` becomes
   `...?existing=1?scope=repository:app:pull`. It needs a query separator and proper
   query parameter construction. Ordinary token challenges, cached tokens,
   `access_token`, and redirect token stripping pass.
   Source: [Oci.kt](../../app/src/main/java/dev/homedroid/Oci.kt), `token`.

6. **Manual restart does not wake a daemon during backoff.**
   Calling `restart()` during the 4-second backoff does not cause a new attempt
   within 600 ms. It sets a flag and destroys the last process without waking the
   sleeping thread. Normal exponential backoff and stopping during sleep pass.
   Source: [Supervisor.kt](../../app/src/main/java/dev/homedroid/Supervisor.kt), lines 53–57 and 97.

7. **HTTP authentication accepts the password without a Bearer scheme.**
   `Authorization: <correct-password>` authenticates because `removePrefix`
   leaves strings without `Bearer ` unchanged. This is an auth scheme validation
   defect; the correct password is still required. Login, session cookies,
   logout invalidation, eviction, and the loopback HTTP test pass.
   Source: [Dashboard.kt](../../app/src/main/java/dev/homedroid/Dashboard.kt), lines 94–97.

## Remaining integration coverage

These results do not validate Android startup/lifecycle, process-group termination,
restarts of successfully running daemons, real SharedPreferences disk persistence,
live registry/TLS interoperability, or actual deployment builds. Supervisor tests
exercise real backoff sleeps through the spawn-failure path because the host lacks
`/system/bin/setsid`; the stable-interval case uses a simulated monotonic clock.
Tests neither run Alpine nor install/launch services. No device/emulator tests or
Gradle/Android build completed.
