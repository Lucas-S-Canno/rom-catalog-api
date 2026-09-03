# ROM Catalog API

Backend do projeto **ROM Catalog** — um catálogo pessoal de ROMs autorais (Game Boy Advance, Nintendo DS e Nintendo 3DS) que expõe um catálogo, gerencia favoritos e disponibiliza download sob demanda das ROMs armazenadas no meu homelab.

Este projeto tem como objetivo duplo:
1. Resolver um problema real: jogar minhas ROMs autorais no celular sem precisar manter os arquivos todos baixados localmente o tempo todo.
2. Servir como estudo prático de novas tecnologias (Kotlin + Ktor, object storage self-hosted com MinIO, deploy em Kubernetes/K3s).

> Uso estritamente pessoal. Não há distribuição de ROMs de terceiros — apenas conteúdo autoral.

## Contexto do projeto

Este backend é consumido pelo app Android **ROM Catalog App** ([repo separado](#)), que exibe o catálogo, permite favoritar jogos e orquestra o download + abertura das ROMs via emuladores de terceiros (RetroArch, Azahar) instalados no celular. A API **não emula jogos** — ela só gerencia catálogo, metadata, favoritos e o armazenamento/entrega dos arquivos.

## Arquitetura

```
App Android → Ktor API (auth, catálogo, favoritos, presigned URLs) → MinIO (bytes das ROMs)
                        ↓
                  PostgreSQL (metadata: nome, sistema, tamanho, hash, favoritos)
```

- **API**: Kotlin + Ktor, expõe endpoints REST.
- **Metadata**: PostgreSQL, migrations versionadas com Flyway.
- **Armazenamento de arquivos**: MinIO (S3-compatible), a API gera presigned URLs em vez de fazer proxy dos bytes.
- **Deploy**: Kubernetes (K3s) rodando no meu homelab, exposto via Cloudflare Tunnel (sem portas abertas).

## Stack técnica

| Camada | Tecnologia |
|---|---|
| Linguagem | Kotlin |
| Framework web | Ktor |
| Serialização | kotlinx.serialization (JSON) |
| ORM | Exposed |
| Banco de dados | PostgreSQL 16 |
| Migrations | Flyway |
| Object storage | MinIO |
| Orquestração | Kubernetes (K3s) |
| Ingress | Cloudflare Tunnel |

## Endpoints (planejados)

| Método | Rota | Descrição | Status |
|---|---|---|---|
| GET | `/health` | Liveness da API (não toca em dependências) | ✅ |
| GET | `/health/ready` | Readiness — checa Postgres + MinIO; `503` se algum cair. Sem auth | ✅ |
| POST | `/auth/login` | Público — `{ username, password }` → `{ token, role, mustChangeCredentials, … }` | ✅ |
| GET | `/auth/me` | Dados da conta do token | ✅ |
| POST | `/auth/change-credentials` | Troca o próprio login/senha (exige a senha atual); devolve token novo | ✅ |
| GET · POST | `/admin/users` | (admin) Lista / cria contas (login + senha temporários) | ✅ |
| POST | `/admin/users/{id}/reset-password` | (admin) Nova senha temporária | ✅ |
| DELETE | `/admin/users/{id}` | (admin) Remove conta (guarda: último admin / a própria) | ✅ |
| GET | `/roms` | Lista o catálogo — `?system=GBA\|NDS\|3DS`, `?page=`, `?size=` (máx. 200); resposta paginada `{ items, page, size, total }` | ✅ |
| GET | `/roms/{id}` | Detalhe de uma ROM (404 se não existir) | ✅ |
| GET | `/roms/{id}/download` | JSON `{ url, expiresAt, hash, sizeBytes }` com presigned URL do MinIO (404 ROM inexistente, 503 storage indisponível) | ✅ |
| GET | `/favorites` | Lista favoritos com a ROM embutida, mais recentes primeiro | ✅ |
| POST | `/favorites` | Favorita uma ROM — body `{ "romId": "<uuid>" }`; idempotente (201 na criação, 200 se já favoritada) | ✅ |
| DELETE | `/favorites/{romId}` | Remove dos favoritos; idempotente (204) | ✅ |
| GET | `/admin/ping` | (admin) Placeholder que confirma escopo admin | ✅ |
| POST | `/admin/roms` | (admin) Ingestão: `multipart/form-data` (upload) ou `application/json` (objeto já no bucket); dedup por hash → `409` | ✅ |
| PATCH | `/admin/roms/{id}` | (admin) Edita metadata mutável (`name`, `coverUrl`); `404` se não existir | ✅ |
| DELETE | `/admin/roms/{id}` | (admin) Remove registro + objeto no bucket (favoritos em cascata); `404` / `503` | ✅ |

> Todas as respostas de erro usam o envelope `{ "error": { "code", "message" } }`.
> **Autenticação:** todas as rotas exigem `Authorization: Bearer <JWT>` exceto `/health`.
> Token sem/ inválido/expirado → `401`; token válido mas sem escopo `admin` numa rota `/admin/…` → `403`. Ver [Autenticação](#autenticação).

## Modelo de dados

Schema versionado por migrations Flyway em `src/main/resources/db/migration`. Colunas em inglês (decisão de padronização — ver `.spec/`).

**Tabela `roms`**
- `id` UUID (PK, gerado pela API)
- `name` text
- `system` varchar(8) — CHECK `IN ('GBA','NDS','3DS')`
- `size_bytes` bigint — CHECK `>= 0`
- `hash` text — UNIQUE (sha256 hex; dedup + verificação de integridade)
- `storage_key` text (chave do objeto no MinIO)
- `cover_url` text NULL (opcional)
- `created_at` timestamptz

**Tabela `favorites`**
- `id` UUID (PK)
- `rom_id` UUID — FK → `roms(id)` `ON DELETE CASCADE`, UNIQUE
- `created_at` timestamptz

## Rodando o projeto localmente

### Pré-requisitos

- JDK 21
- Docker + Docker Compose (Docker Engine oficial — evite a versão via Snap, causa problemas de permissão e volumes)
- IntelliJ IDEA (recomendado)

### Subindo a infraestrutura local

Este repo inclui um `docker-compose.yml` que sobe Postgres e MinIO localmente, para desenvolvimento sem depender do homelab:

```bash
docker compose up -d
```

Isso disponibiliza:
- PostgreSQL em `localhost:5432`
- MinIO em `localhost:9000` (API) e `localhost:9001` (console web, login `minioadmin` / `minioadmin`)

Se a porta 5432 já estiver em uso na sua máquina (ex.: um serviço nativo do PostgreSQL), suba com outra porta de host e ajuste o `DB_URL`:

```bash
POSTGRES_HOST_PORT=5433 docker compose up -d
# DB_URL=jdbc:postgresql://localhost:5433/romcatalog
```

### Variáveis de ambiente

Crie um arquivo `.env` (não versionado) na raiz do projeto:

Veja `.env.example` para a lista completa e comentada. Resumo:

```
DB_URL=jdbc:postgresql://localhost:5432/romcatalog
DB_USER=romcatalog
DB_PASSWORD=romcatalog
MINIO_ENDPOINT=http://localhost:9000          # controle (statObject, putObject)
MINIO_PUBLIC_ENDPOINT=http://localhost:9000   # usado para ASSINAR as URLs de download
MINIO_ACCESS_KEY=minioadmin
MINIO_SECRET_KEY=minioadmin
MINIO_BUCKET=roms
MINIO_REGION=us-east-1
DOWNLOAD_URL_TTL_SECONDS=900
JWT_SECRET=            # defina! sem isso usa um default inseguro conhecido
JWT_ISSUER=rom-catalog-api
JWT_AUDIENCE=rom-catalog-app
JWT_REALM=rom-catalog
DB_CONNECTION_TIMEOUT_MS=10000
STORAGE_TIMEOUT_MS=10000
CORS_ALLOWED_ORIGINS=                         # vazio/ausente = sem CORS (padrão). CSV de origens, ou '*' (só dev)
```

Sem `.env`, os defaults acima já valem (batem com o `docker-compose.yml`).

### Rodando a API

```bash
./gradlew run
```

A API deve subir em `http://localhost:8080`. Valide com:

```bash
curl http://localhost:8080/health
```

### Testes

```bash
./gradlew test        # suíte completa (unit + integração/rota com Testcontainers) — precisa de Docker
./gradlew testUnit    # só os testes rápidos, sem Testcontainers
```

`./gradlew test` é o gate: nenhuma etapa fecha sem ele verde. Os testes de integração sobem Postgres e MinIO via Testcontainers automaticamente (não usam o `docker-compose.yml`).

## Autenticação

Contas com **login/senha** e dois papéis: `admin` e `user`. `POST /auth/login` devolve um **JWT HS256** (TTL `JWT_TTL_HOURS`, default 7 dias) que vai em `Authorization: Bearer <token>` em tudo, exceto `/health`, `/health/ready` e `/auth/login`. Rotas `/admin/…` exigem papel `admin`. Senhas são **BCrypt** no banco.

```bash
TOKEN=$(curl -s -H 'Content-Type: application/json' \
  -d '{"username":"lucas","password":"..."}' http://localhost:8080/auth/login | jq -r .token)
curl -H "Authorization: Bearer $TOKEN" http://localhost:8080/roms
```

**Primeiro admin:** na primeira subida, se não houver admin, um é criado a partir de `ADMIN_USERNAME` / `ADMIN_BOOTSTRAP_PASSWORD` (env → Secret, nunca no git). Idempotente. Depois é só usar o painel `POST /admin/users` para criar contas de amigos (login + senha temporários; o app força a troca no primeiro acesso via `mustChangeCredentials` + `POST /auth/change-credentials`).

**Break-glass:** `./gradlew -q issueToken --args="--scope admin --ttl-days 365"` emite um token sem conta no banco — para recuperar acesso se você se trancar pra fora.

Contrato completo dos endpoints de auth/contas em [`docs/API.md`](docs/API.md).

| Situação | Resposta |
|---|---|
| Sem header / token malformado / assinatura inválida / expirado | `401 UNAUTHORIZED` |
| Login errado | `401 INVALID_CREDENTIALS` |
| Token `user` numa rota `/admin/…` | `403 FORBIDDEN` |

**Config** (Secret): `JWT_SECRET` (obrigatório), `ADMIN_USERNAME`, `ADMIN_BOOTSTRAP_PASSWORD`. **ConfigMap:** `JWT_ISSUER`, `JWT_AUDIENCE`, `JWT_REALM`, `JWT_TTL_HOURS`, `BCRYPT_COST`, `CORS_ALLOWED_ORIGINS`.

## Ingestão

Duas formas de popular o catálogo. Ambas passam pelo mesmo `IngestionService` (dedup por sha256).

### `POST /admin/roms` (escopo admin)

- **`multipart/form-data`** — parte `file` obrigatória; campos opcionais `name`, `system` (`GBA`\|`NDS`\|`3DS`), `coverUrl`. A API calcula sha256 + tamanho em streaming, sobe o objeto em `{system}/{hash}.{ext}` e cria o registro. Sem o campo `system`, deduz pela extensão do arquivo.
- **`application/json`** — `{ name, system, hash, sizeBytes, storageKey, coverUrl? }` para um objeto **já** no bucket. A API baixa o objeto, confere `hash`/`sizeBytes` e então registra.

| Resultado | Código |
|---|---|
| Criado | `201` + `RomDto` |
| Já existe um ROM com esse hash | `409` + `RomDto` do existente (nada é escrito) |
| `system` inválido / extensão não reconhecida sem `system` / falta a parte `file` | `400` |
| Objeto ausente no bucket / `hash` ou `sizeBytes` não conferem (modo JSON) | `422` |

### Script de varredura

Vai direto no Postgres + MinIO (não precisa de servidor nem token). Idempotente: pula arquivos cujo hash já está no catálogo; ignora ocultos e extensões desconhecidas.

```bash
./gradlew -q ingest --args="--dir /caminho/para/roms"
./gradlew -q ingest --args="--dir /caminho/para/roms --dry-run"
```

Relatório final: adicionados / pulados / erros.

## Observabilidade

- **Liveness** `GET /health` — nunca toca em dependência; serve para `livenessProbe` no K8s.
- **Readiness** `GET /health/ready` — roda `SELECT 1` no Postgres e um probe de bucket no MinIO (cada um com timeout de ~2 s), em paralelo. `200` só quando ambos estão `UP`; senão `503` com o detalhe por check:

  ```json
  { "status": "DOWN", "checks": { "db": { "status": "UP" }, "storage": { "status": "DOWN", "detail": "..." } } }
  ```

- **Logs** em JSON (encoder nativo do Logback), um objeto por linha, com o campo MDC `requestId`. Nos testes um `logback-test.xml` troca para texto legível.
- **Request id**: o plugin `CallId` lê `X-Request-Id` (ou gera um UUID), coloca no MDC e devolve no header da resposta.
- **Erros**: todas as respostas de erro usam o envelope `{ "error": { "code", "message" } }`. Exceção não tratada → `500 INTERNAL_ERROR` com mensagem genérica; o stacktrace vai **só** para o log.
- **Timeouts** explícitos: HikariCP (`DB_CONNECTION_TIMEOUT_MS`) e cliente HTTP do MinIO (`STORAGE_TIMEOUT_MS`).

## Container

`Dockerfile` multi-stage (build com Gradle+JDK 21 → runtime JRE 21, usuário não-root, `readOnlyRootFilesystem` no K8s). A imagem roda `java -jar app.jar` (fat jar via `buildFatJar`); Flyway aplica as migrations no boot.

```bash
docker build -t rom-catalog-api:local .
bash scripts/smoke.sh rom-catalog-api:local   # sobe Postgres+MinIO+imagem e checa os contratos
```

`APP_ENV=production` (padrão na imagem) faz o processo **recusar subir** se `JWT_SECRET`, `DB_*` ou `MINIO_*` ainda estiverem com valores de desenvolvimento — a mensagem lista tudo o que falta.

## Deploy (K3s)

Manifests + runbook em [`k8s/`](k8s/README.md): `namespace`, `configmap`, `secret.example`, `deployment` (1 réplica, probes em `/health` e `/health/ready`, limites de recurso), `service` (ClusterIP) e `cloudflared` (Tunnel expondo `rom-catalog-api.lucascanno.com.br` e `rom-catalog-storage.lucascanno.com.br` — decisão D-02, sem abrir portas).

```bash
# imagem publicada pelo CI em ghcr.io/<owner>/rom-catalog-api no merge para main
kubectl -n rom-catalog create secret generic rom-catalog-api-secret --from-literal=JWT_SECRET=... [...]
kubectl apply -k k8s/
kubectl -n rom-catalog rollout status deploy/rom-catalog-api
```

Rollback: `kubectl -n rom-catalog rollout undo deploy/rom-catalog-api`. Migrations rodam no boot (seguro com 1 réplica + `strategy: Recreate`); ver `k8s/README.md` para escalar.

O download de ROMs grandes (3DS, >1 GB) vai direto de `rom-catalog-storage.lucascanno.com.br` (MinIO) para o cliente via presigned URL — **não** passa pelo caminho da API no Tunnel, então limites de tamanho de request da Cloudflare no lado da API não se aplicam. Checklist de aceitação manual pós-deploy em `k8s/README.md`.

## Roadmap

- [x] Fase 0 — Decisões de arquitetura e stack
- [x] Fase 1 — Backend (plano detalhado em `.spec/02-plano-desenvolvimento.md`)
  - [x] Etapa 0 — Bootstrap: servidor Ktor, `/health`, CI
  - [x] Etapa 1 — Persistência: Postgres + Flyway + Exposed, repositórios
  - [x] Etapa 2 — Catálogo: `GET /roms`, `GET /roms/{id}`
  - [x] Etapa 3 — Download: `GET /roms/{id}/download` (presigned URL)
  - [x] Etapa 4 — Favoritos: `GET/POST/DELETE /favorites`
  - [x] Etapa 5 — Autenticação: JWT HS256, escopo admin, task `issueToken`
  - [x] Etapa 6 — Ingestão: `POST /admin/roms` (multipart + JSON) + task `ingest`
  - [x] Etapa 7 — Observabilidade: `/health/ready`, logs JSON, request-id, timeouts
  - [x] Etapa 8 — Containerização: Dockerfile, manifests K8s, CI (build + smoke + publish), runbook
  - [x] Etapa 9 — Contas & login: `POST /auth/login`, papéis admin/user, BCrypt, painel `/admin/users`, bootstrap do admin por env
- [ ] Fase 2 — Integração com o app Android
- [ ] Fase 3 — Polimento (cache, expiração, sincronização de favoritos, refresh token)

## Notas de estudo

Este projeto é usado como espaço de aprendizado para:
- Ktor como alternativa ao Spring Boot
- Object storage self-hosted (MinIO) e o padrão de presigned URLs
- Deploy de novos workloads no cluster K3s pessoal