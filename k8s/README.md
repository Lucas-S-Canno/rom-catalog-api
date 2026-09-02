# Deploy runbook — ROM Catalog API on K3s

Manifests here are plain YAML + a `kustomization.yaml`. Namespace: `rom-catalog`.

| File | What |
|---|---|
| `namespace.yaml` | the `rom-catalog` namespace |
| `configmap.yaml` | non-secret config (endpoints, issuer, timeouts, `APP_ENV=production`) |
| `secret.example.yaml` | **template** for the Secret — never commit real values |
| `deployment.yaml` | 1 replica, non-root, probes on `/health` + `/health/ready`, resource limits |
| `service.yaml` | ClusterIP `:80` → pod `:8080` |
| `cloudflared.yaml` | Cloudflare Tunnel (API + MinIO public endpoint), no inbound ports |
| `kustomization.yaml` | ties it together (`kubectl apply -k k8s/`) |

## Prerequisites

- A reachable Postgres and MinIO in the cluster (referenced by the ConfigMap/Secret).
  Adjust `MINIO_ENDPOINT` / `DB_URL` to your actual service DNS names.
- The image pushed to a registry the cluster can pull. CI publishes
  `ghcr.io/lucas-s-canno/rom-catalog-api` on pushes to `main` (the CI lowercases
  the owner). If you fork/rename, update `kustomization.yaml` (`images[].name`)
  and `deployment.yaml`.

## First deploy

```bash
kubectl create namespace rom-catalog

# 1. Image access. CI already pushes ghcr.io/lucas-s-canno/rom-catalog-api on
#    merge to main. The package is PRIVATE by default — pick one:
#
#  a) make it public (no REST endpoint for this — use the web UI):
#     https://github.com/users/Lucas-S-Canno/packages/container/rom-catalog-api/settings
#     -> Danger Zone -> Change visibility -> Public
#
#  b) or keep it private and give the namespace a pull secret (deployment.yaml
#     already references `ghcr-pull`); PAT needs the read:packages scope:
kubectl -n rom-catalog create secret docker-registry ghcr-pull \
  --docker-server=ghcr.io \
  --docker-username=Lucas-S-Canno \
  --docker-password='<PAT with read:packages>'

# 2. Create the real Secret (NOT from the committed template)
kubectl -n rom-catalog create secret generic rom-catalog-api-secret \
  --from-literal=JWT_SECRET="$(openssl rand -base64 48)" \
  --from-literal=DB_URL="jdbc:postgresql://<pg-host>:5432/romcatalog" \
  --from-literal=DB_USER="romcatalog" \
  --from-literal=DB_PASSWORD="<pw>" \
  --from-literal=MINIO_ACCESS_KEY="<key>" \
  --from-literal=MINIO_SECRET_KEY="<secret>"

# 3. Point the config at your real infra: MINIO_ENDPOINT / MINIO_PUBLIC_ENDPOINT
#    in configmap.yaml and DB_URL in the Secret above. Create the `roms` bucket.

# 4. Cloudflare Tunnel credentials (see header of cloudflared.yaml), then set the
#    tunnel id in cloudflared-config. Or, to reuse an existing homelab tunnel,
#    add ingress rules for api./storage.lucascanno.com.br there and drop
#    cloudflared.yaml from kustomization.yaml.

# 5. Apply everything
kubectl apply -k k8s/

# 6. Watch it come up
kubectl -n rom-catalog rollout status deploy/rom-catalog-api
kubectl -n rom-catalog get pods
```

`APP_ENV=production` makes the process **refuse to start** if `JWT_SECRET`, `DB_*`
or `MINIO_*` are still dev defaults — the pod will CrashLoopBackOff with the reason
in its logs. That is intentional.

## Updating

CI builds and pushes `:latest` (and `:<sha>`) on merge to `main`. Roll it out:

```bash
kubectl -n rom-catalog set image deploy/rom-catalog-api api=ghcr.io/lucas-s-canno/rom-catalog-api:<sha>
kubectl -n rom-catalog rollout status deploy/rom-catalog-api
```

## Rollback

```bash
kubectl -n rom-catalog rollout undo deploy/rom-catalog-api
# or to a specific revision:
kubectl -n rom-catalog rollout history deploy/rom-catalog-api
kubectl -n rom-catalog rollout undo deploy/rom-catalog-api --to-revision=<n>
```

## Logs & debugging

```bash
kubectl -n rom-catalog logs deploy/rom-catalog-api -f          # JSON, one object per line
kubectl -n rom-catalog logs deploy/rom-catalog-api --previous  # last crash
kubectl -n rom-catalog describe pod -l app.kubernetes.io/name=rom-catalog-api
kubectl -n rom-catalog port-forward svc/rom-catalog-api 8080:80
curl -s localhost:8080/health/ready | jq
```

## Database migrations

Flyway runs on **boot**, inside the app process. Safe with `replicas: 1` and
`strategy: Recreate` (old pod stops before the new one starts). To scale past one
replica, move migrations to an `initContainer` or a pre-deploy `Job` running
`java -cp /app/app.jar org.flywaydb.core.Flyway ...` (or a dedicated migrate main)
and set `replicas: N` + `RollingUpdate`.

## Manual acceptance test (post-deploy)

From outside the home network (phone or another machine):

```bash
TOKEN="<paste an admin token: ./gradlew -q issueToken --args='--scope admin'>"
API=https://api.lucascanno.com.br

curl -s $API/health                                   # 200 {"status":"UP"}
curl -s $API/health/ready | jq                        # 200, db + storage UP
curl -s -o /dev/null -w '%{http_code}\n' $API/roms    # 401
curl -s -H "Authorization: Bearer $TOKEN" $API/roms | jq

# Large-ROM path (3DS, > 1 GB) — the risk called out in the project README:
ID="<a rom id from /roms>"
curl -s -H "Authorization: Bearer $TOKEN" $API/roms/$ID/download | jq   # -> { url, expiresAt, hash, sizeBytes }
URL="<url from the response>"
curl -L -o rom.bin "$URL"                             # streams MinIO -> client, NOT through the API/Tunnel API path
sha256sum rom.bin                                     # must equal the "hash" field
```

The download goes straight from `storage.lucascanno.com.br` (MinIO via its own
Tunnel hostname) to the client, so Cloudflare request-size limits on the API path
do not apply.
