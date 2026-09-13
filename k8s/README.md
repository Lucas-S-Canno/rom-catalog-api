# Deploy runbook — ROM Catalog API on K3s

Manifests here are plain YAML + a `kustomization.yaml`. Namespace: `rom-catalog`.

| File | What |
|---|---|
| `namespace.yaml` | the `rom-catalog` namespace (standalone — NOT in kustomization; Fleet/Helm can't adopt an existing ns) |
| `configmap.yaml` | non-secret config (endpoints, issuer, timeouts, `APP_ENV=production`) |
| `secret.example.yaml` | **template** for the Secret — never commit real values |
| `deployment.yaml` | 1 replica, non-root, probes on `/health` + `/health/ready`, resource limits |
| `service.yaml` | ClusterIP `:80` → pod `:8080` |
| `cloudflared.yaml` | Cloudflare Tunnel (API + MinIO public endpoint), no inbound ports |
| `kustomization.yaml` | ties it together (`kubectl apply -k k8s/`) |
| `fleet.yaml` | makes this dir a Fleet bundle (GitOps via Rancher) |
| `infra/` | **optional** in-cluster Postgres + MinIO + a bucket-creation Job (its own `fleet.yaml`) |
| `cloudflare-dns01-issuer.yaml` | **optional**, manual apply — cert-manager `ClusterIssuer` so the storage host also gets a valid cert on the LAN-direct path (see below) |
| `storage-tls-certificate.yaml` | **optional**, manual apply — the actual `Certificate` for `rom-catalog-storage.lucascanno.com.br`, using the issuer above |

## Deploy with Fleet (Rancher GitOps)

Rancher → **Continuous Delivery → Git Repos → Add**:

| Field | Value |
|---|---|
| Repository URL | `https://github.com/Lucas-S-Canno/rom-catalog-api.git` |
| Branch | `main` |
| Paths | `k8s/infra` and `k8s` (two entries) |
| Target | your cluster |

Fleet finds the `fleet.yaml` in each path, runs kustomize, and syncs. **Create the
secrets first** (they are not in git) — Rancher → *Storage → Secrets*, or:

```bash
kubectl -n rom-catalog create secret generic rom-catalog-data-secret \
  --from-literal=POSTGRES_PASSWORD=... --from-literal=MINIO_ROOT_USER=romcatalog \
  --from-literal=MINIO_ROOT_PASSWORD=...
kubectl -n rom-catalog create secret generic rom-catalog-api-secret \
  --from-literal=JWT_SECRET=... --from-literal=DB_URL=jdbc:postgresql://postgres:5432/romcatalog \
  --from-literal=DB_USER=romcatalog --from-literal=DB_PASSWORD=<same as POSTGRES_PASSWORD> \
  --from-literal=MINIO_ACCESS_KEY=romcatalog --from-literal=MINIO_SECRET_KEY=<same as MINIO_ROOT_PASSWORD>
```

The rest below (kubeconfig, `kubectl apply -k`) is the non-Fleet alternative.

## Prerequisites — Postgres + MinIO

The API needs a Postgres and a MinIO reachable from the pod. Three ways:

### Option 1 — deploy them in-cluster with `k8s/infra/` (simplest for a homelab)

```bash
kubectl create namespace rom-catalog

# creds shared by Postgres, MinIO and (below) the app Secret
kubectl -n rom-catalog create secret generic rom-catalog-data-secret \
  --from-literal=POSTGRES_PASSWORD="$(openssl rand -base64 24)" \
  --from-literal=MINIO_ROOT_USER="romcatalog" \
  --from-literal=MINIO_ROOT_PASSWORD="$(openssl rand -base64 24)"

kubectl apply -k k8s/infra/
kubectl -n rom-catalog rollout status deploy/postgres deploy/minio
kubectl -n rom-catalog get job minio-create-bucket   # COMPLETIONS 1/1
```

PVCs use the K3s default `local-path` StorageClass (a hostPath on the node). Bump
`minio-data` in `infra/minio.yaml` to fit your ROM collection.

Then the app config already matches: `MINIO_ENDPOINT=http://minio:9000`,
`DB_URL=jdbc:postgresql://postgres:5432/romcatalog` (same namespace).

### Option 2 — Rancher Apps (Helm)

In Rancher: *Apps → Charts*. Add `https://charts.bitnami.com/bitnami` (or use the
MinIO Operator chart from the partner catalog). Deploy `postgresql` and `minio`
into the `rom-catalog` namespace, then point `MINIO_ENDPOINT` / `DB_URL` at the
service names the chart creates (`kubectl -n rom-catalog get svc`).

### Option 3 — you already run Postgres/MinIO elsewhere (LAN, another VM)

Apply `k8s/infra/external-example.yaml` (edit the IPs): it creates headless
`postgres` / `minio` Services backed by manual `Endpoints` pointing at the LAN
address, so the app config still uses `postgres:5432` / `minio:9000` unchanged.
The `roms` bucket: create it in the MinIO console, or
`mc mb <alias>/roms` from your laptop.

## Prerequisites — image

The image must be pullable by the cluster. CI publishes
`ghcr.io/lucas-s-canno/rom-catalog-api` on pushes to `main` (the CI lowercases
the owner). If you fork/rename, update `kustomization.yaml` (`images[].name`)
and `deployment.yaml`.
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

# 2. Postgres + MinIO: see "Prerequisites" above. If you used k8s/infra/ Option 1,
#    the shared creds live in rom-catalog-data-secret — reuse those values below.

# 3. Create the app Secret (NOT from the committed template).
#    DB_PASSWORD / MINIO_ACCESS_KEY / MINIO_SECRET_KEY must match your Postgres/MinIO.
DATA=$(kubectl -n rom-catalog get secret rom-catalog-data-secret -o json)   # if you used Option 1
kubectl -n rom-catalog create secret generic rom-catalog-api-secret \
  --from-literal=JWT_SECRET="$(openssl rand -base64 48)" \
  --from-literal=DB_URL="jdbc:postgresql://postgres:5432/romcatalog" \
  --from-literal=DB_USER="romcatalog" \
  --from-literal=DB_PASSWORD="$(echo "$DATA" | jq -r '.data.POSTGRES_PASSWORD | @base64d')" \
  --from-literal=MINIO_ACCESS_KEY="$(echo "$DATA" | jq -r '.data.MINIO_ROOT_USER | @base64d')" \
  --from-literal=MINIO_SECRET_KEY="$(echo "$DATA" | jq -r '.data.MINIO_ROOT_PASSWORD | @base64d')"
# (not using Option 1? just pass the literal values you set on your Postgres/MinIO)

# 4. Adjust configmap.yaml only if MinIO is NOT in this namespace
#    (MINIO_ENDPOINT) — MINIO_PUBLIC_ENDPOINT already points at the tunnel host.

# 5. Cloudflare Tunnel credentials (see header of cloudflared.yaml), then set the
#    tunnel id in cloudflared-config. Or, to reuse an existing homelab tunnel,
#    add ingress rules for rom-catalog-api. / rom-catalog-storage.lucascanno.com.br there and drop
#    cloudflared.yaml from kustomization.yaml.

# 6. Apply the app
kubectl apply -k k8s/
kubectl -n rom-catalog rollout status deploy/rom-catalog-api
kubectl -n rom-catalog get pods
```

### Sanity-check the dependencies from inside the cluster

```bash
kubectl -n rom-catalog run netcheck --rm -it --restart=Never --image=postgres:16 -- bash -c '
  pg_isready -h postgres -U romcatalog &&
  curl -sf http://minio:9000/minio/health/ready && echo " minio ok"
'
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

## TLS pro storage host na rede local (upload direto, sem passar pelo túnel)

`POST /admin/roms/presign-upload` (ver `docs/API.md`) só escapa do teto de tamanho de request
da Cloudflare se o `PUT` for feito **sem** passar pela borda da Cloudflare — na prática, isso
significa apontar `rom-catalog-storage.lucascanno.com.br` pra um IP interno (hosts-file, DNS
split-horizon no roteador/Pi-hole etc.) em vez de deixar resolver pro IP público de sempre.

Só que aí quem termina o TLS deixa de ser a Cloudflare (que tem certificado válido) e passa a
ser o Traefik do cluster direto — e o `Ingress` do MinIO (`ingress.yaml`) nunca teve um
certificado configurado (ele foi pensado só pra ser falado em HTTP simples pelo `cloudflared`).
Resultado: o navegador recusa a conexão com `net::ERR_CERT_AUTHORITY_INVALID`.

Fix (uma vez só, fora do Git):

```bash
# 1. Confirme que o cert-manager já está de pé:
kubectl get pods -n cert-manager

# 2. Token da Cloudflare com escopo "Edit zone DNS" SÓ pra zona lucascanno.com.br
#    (Cloudflare dashboard -> My Profile -> API Tokens -> Create Token), depois:
kubectl -n cert-manager create secret generic cloudflare-api-token-secret \
  --from-literal=api-token='<o token>'

# 3. Edite o e-mail em cloudflare-dns01-issuer.yaml, depois:
kubectl apply -f k8s/cloudflare-dns01-issuer.yaml
kubectl apply -f k8s/storage-tls-certificate.yaml
kubectl -n rom-catalog get certificate rom-catalog-storage-tls -w   # espera READY=True
```

Depois disso o `Ingress` (já ajustado pra escutar em `web,websecure` e apontar `tls:` pro
Secret que o `Certificate` acima gera) passa a servir um certificado de verdade também pra
quem chega direto pela LAN — sem precisar trocar nada no painel ou na API.

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
API=https://rom-catalog-api.lucascanno.com.br

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

The download goes straight from `rom-catalog-storage.lucascanno.com.br` (MinIO via its own
Tunnel hostname) to the client, so Cloudflare request-size limits on the API path
do not apply.
