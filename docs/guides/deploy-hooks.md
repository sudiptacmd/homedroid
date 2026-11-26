# Deploying from CI

**Video:** [Watch the setup on the Homedroid website](https://homedroid.seltiv.com/guides/deploy/)

Every deployment has its own deploy token and GitHub webhook secret. They're shown once, when
the deployment is created or when you make new ones (`POST /api/deploys/<id>/token`); the
phone keeps only a hash of the token. With them, CI can deploy without the dashboard password:

| Endpoint | Auth | Does |
|----------|------|------|
| `POST /hooks/deploy/<id>` | `Authorization: Bearer <token>` | Redeploys: pulls the branch for a Git deployment, re-unpacks the last archive for an uploaded one |
| `POST /hooks/deploy/<id>/artifact` | `Authorization: Bearer <token>` | Uploads a `.zip`, `.tar.gz` or `.tar` (up to 512 MB) as the body and deploys it (uploaded deployments only) |
| `POST /hooks/github/<id>` | `X-Hub-Signature-256` | GitHub push webhook: deploys pushes to the deployment's branch, ignores other branches and tags |

Add `?wait=1` to the first two to get the answer only once the build, the pipeline and (for a
replicated deployment) the rollout to the other phones are done: `200 {"ok":true,"commit":"…"}`,
or `500 {"ok":false,"error":"…"}` so the CI job fails. Without it the answer is
`{"ok":true,"queued":true}` straight away.

Wrong or missing tokens get `401`. After 5 failures from one address, or 50 in all, hooks
answer `429` for 15 minutes (the dashboard login is not affected).

The phone has to be reachable from the CI runner: through the Cloudflare Tunnel module with a
public hostname for the dashboard port (8800), or Tailscale (for example the
`tailscale/github-action` step) for a private address.

## GitHub Actions: redeploy from Git

Save the token as the `HOMEDROID_TOKEN` secret and the phone's address (such as
`https://deploy.example.com`) as `HOMEDROID_URL`.

```yaml
name: Deploy
on:
  push:
    branches: [main]
jobs:
  deploy:
    runs-on: ubuntu-latest
    steps:
      - name: Deploy to the phone
        run: |
          curl --fail-with-body -sS -X POST \
            -H "Authorization: Bearer ${{ secrets.HOMEDROID_TOKEN }}" \
            "${{ secrets.HOMEDROID_URL }}/hooks/deploy/my-app?wait=1"
```

## GitHub Actions: build in CI, upload the result

For a deployment created with `"source": "upload"`. The phone then only installs dependencies
and starts it; set `"runtime": "static"` to serve a built site with Caddy.

```yaml
name: Build and deploy
on:
  push:
    branches: [main]
jobs:
  deploy:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-node@v4
        with:
          node-version: 22
      - run: npm ci && npm run build
      - run: tar -czf site.tar.gz -C dist .
      - name: Upload to the phone
        run: |
          curl --fail-with-body -sS -X POST \
            -H "Authorization: Bearer ${{ secrets.HOMEDROID_TOKEN }}" \
            -H "Content-Type: application/octet-stream" \
            --data-binary @site.tar.gz \
            "${{ secrets.HOMEDROID_URL }}/hooks/deploy/my-app/artifact?wait=1"
```

Send the archive with a length (`--data-binary @file`); chunked uploads aren't accepted. An
archive holding a single top folder (like GitHub's `repo-main/`) is unpacked from inside it.
Only files and folders are unpacked: links are skipped, and paths leaving the project fail the
upload. Executable bits survive in tar files but not in zip files.

## GitLab CI and CircleCI

The same request works anywhere `curl` does:

```yaml
# .gitlab-ci.yml
deploy:
  stage: deploy
  script:
    - 'curl --fail-with-body -sS -X POST -H "Authorization: Bearer $HOMEDROID_TOKEN" "$HOMEDROID_URL/hooks/deploy/my-app?wait=1"'
  rules:
    - if: $CI_COMMIT_BRANCH == "main"
```

```yaml
# .circleci/config.yml
version: 2.1
jobs:
  deploy:
    docker:
      - image: cimg/base:current
    steps:
      - run: curl --fail-with-body -sS -X POST -H "Authorization: Bearer $HOMEDROID_TOKEN" "$HOMEDROID_URL/hooks/deploy/my-app?wait=1"
workflows:
  deploy:
    jobs:
      - deploy:
          filters:
            branches:
              only: main
```

## GitHub webhook (no CI at all)

In the repository: Settings → Webhooks → Add webhook.

- Payload URL: `https://<phone address>/hooks/github/my-app`
- Content type: `application/json` (form-encoded payloads are refused)
- Secret: the deployment's webhook secret
- Events: just the push event

The ping GitHub sends on saving answers `{"ok":true,"pong":true}`.

## Pipelines

A `.homedroid/pipeline.json` in the project (or `pipeline` in the deployment's settings, which
takes precedence) lists steps to run after installing and building, before the new version
starts. A failing step stops the deploy and the previous version keeps running.

```json
{
  "steps": [
    {"name": "Unit tests", "run": "npm test"},
    {"name": "Migrate", "run": "node scripts/migrate.js"}
  ]
}
```

Steps run in `/srv/<id>` inside Alpine with the deployment's environment. The last 20 runs,
with each step's status, time and output, are kept and listed by `GET /api/deploys/<id>/runs`.
