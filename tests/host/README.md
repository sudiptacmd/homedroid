# Host regression tests

Runs the actual Kotlin sources for the nine requested areas without modifying production
code or requiring an Android SDK. Requires Linux, JDK 17+, Python 3, and network access
for the initial dependency download. Production dependencies are unchanged.

```sh
python3 tests/host/setup.py /tmp/lindroid-test-tools
# Set JAVA_HOME if java is not on PATH.
HOST_TEST_TOOLS=/tmp/lindroid-test-tools tests/host/run.sh
```

The suite takes about 2.5 minutes: the supervisor cap check waits through real delays
of 1, 2, 4, 8, 16, 32, and 60 seconds. It exits nonzero for any failed assertion and
writes individual results to `tests/host/results.txt`. Known failures remain failures.

## Scope

- **OCI references:** default registry/library/tag, explicit registry/port/digest,
  tag plus digest, and malformed references.
- **Digests:** full `Oci.pull` with valid tar/gzip layers, corruption, trailing bytes,
  previous-destination preservation, and corrupt config/manifest rejection.
- **Tar traversal:** temporary filesystem, real symlinks, parent traversal, absolute
  paths, hard links, symlink cycles, and truncated data.
- **Whiteouts:** ordinary and opaque markers, reused extractor across layers,
  current-layer additions, and non-OCI behavior.
- **Registry authentication:** in-process `HttpURLConnection` doubles exercise actual
  request logic: challenge, token/access_token, cache, redirects, 401, loop limit,
  and realms with query parameters. No live registry or TLS requests occur in tests.
- **Configuration:** real catalog and image JSON parsing; settings defaults and
  round trips through an in-memory SharedPreferences double.
- **Supervisor:** actual spawn-failure loop, restart counters, exponential delay/cap,
  stop while backing off, and manual restart while backing off. The stable-interval
  case uses a simulated Android clock; sleep durations are unchanged.
- **Deployments:** validation, JSON persistence, defaults, secret redaction, build
  environment, and generated static-server spec.
- **HTTP sessions:** actual Dashboard handlers and a loopback socket test through
  Http: password checks, cookies, logout, eviction, and dashboard recreation.

## Limits

Android Context, preferences, filesystem calls, and clock are represented by the
small doubles in `stubs/`. JSON comes from Android 14's implementation distributed
in Robolectric's `android-all` artifact. No Robolectric runner is involved.
Unrelated Android service/Alpine actions are stubbed and not exercised.

The supervisor tests intentionally rely on the absence of `/system/bin/setsid` to
trigger startup failures. Successful daemon startup, running-process restart,
process-group signals, and Android lifecycle behavior require device/emulator tests.
Settings persistence here does not test Android disk writes or process death.
Deployment tests do not clone repositories, install packages, or run builds.
The pull tests do not exercise Android `/system/bin/rm` cleanup or existing-image
replacement after a successful pull. Passing host tests do not establish complete
Android integration coverage.

The whiteout assertions follow the [OCI layer specification](https://github.com/opencontainers/image-spec/blob/main/layer.md#whiteouts):
markers affect lower layers only and must preserve entries added in the same layer.
Reference expectations follow the [Distribution reference grammar](https://github.com/distribution/reference/blob/main/reference.go).
