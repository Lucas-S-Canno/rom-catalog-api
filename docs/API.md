# ROM Catalog API — Referência

Contrato completo da API, para servir de base ao app Android.

- **Local:** `http://localhost:8080`
- **Homelab:** `https://rom-catalog-api.lucascanno.com.br`
- Storage (só download direto, ver [Fluxo de download](#fluxo-de-download)): `https://rom-catalog-storage.lucascanno.com.br`

Todas as respostas são JSON (`Content-Type: application/json`), exceto o corpo de um download (bytes crus, servido pelo MinIO).

---

## Autenticação

JWT **HS256**, header `Authorization: Bearer <token>`.

- **Todas as rotas exigem token, exceto `GET /health` e `GET /health/ready`.**
- Rotas `/admin/*` exigem um token com escopo `admin`. Token de escopo `user` nessas rotas → `403`.
- `admin` implica `user` (token admin funciona em qualquer rota).

O token é emitido manualmente (não há login):

```bash
./gradlew -q issueToken --args="--scope user --ttl-days 3650"
./gradlew -q issueToken --args="--scope admin"
# ou, dentro do container:
kubectl -n rom-catalog exec deploy/rom-catalog-api -- \
  java -cp /app/app.jar com.lucascanno.romcatalog.auth.TokenIssuerCliKt --scope user
```

Claims do token: `iss=rom-catalog-api`, `aud=rom-catalog-app`, `sub`, `scope` (`user`|`admin`), `iat`, `exp`.

**No app:** guarde o token em armazenamento seguro (EncryptedSharedPreferences / DataStore). Em qualquer `401`, trate como "token inválido ou expirado" e peça um novo (colar manualmente). Uso pessoal, um usuário — não há refresh.

---

## Formato de erro

Toda resposta de erro (4xx/5xx) usa o mesmo envelope:

```json
{ "error": { "code": "ROM_NOT_FOUND", "message": "ROM 'a9de…' not found" } }
```

| HTTP | `code` | Quando |
|---|---|---|
| 400 | `INVALID_QUERY_PARAM` | `page`/`size` não-inteiro ou negativo |
| 400 | `INVALID_PATH_PARAM` | `{id}` / `{romId}` não é UUID |
| 400 | `INVALID_SYSTEM` | `?system=` ou campo `system` fora de `GBA`/`NDS`/`3DS` |
| 400 | `INVALID_BODY` | JSON malformado, ou `romId` do body não é UUID |
| 400 | `UNKNOWN_SYSTEM` | upload sem campo `system` e extensão não reconhecida |
| 400 | `MISSING_FILE` | `multipart` sem a parte `file` |
| 401 | `UNAUTHORIZED` | sem token / token inválido / expirado / issuer errado |
| 403 | `FORBIDDEN` | token `user` numa rota `/admin/*` |
| 404 | `ROM_NOT_FOUND` | ROM inexistente |
| 404 | `NOT_FOUND` | rota inexistente |
| 409 | — | (ingestão) já existe ROM com esse `hash`; corpo é o `RomDto` existente |
| 415 | `UNSUPPORTED_MEDIA_TYPE` | `POST /admin/roms` sem `Content-Type` multipart/json; `POST /favorites` sem corpo |
| 422 | `OBJECT_NOT_FOUND` | (ingestão JSON) `storageKey` não existe no bucket |
| 422 | `HASH_MISMATCH` / `SIZE_MISMATCH` | (ingestão JSON) `hash`/`sizeBytes` não batem com o objeto |
| 503 | `STORAGE_UNAVAILABLE` | MinIO fora do ar, ou objeto da ROM sumiu do bucket |
| 500 | `INTERNAL_ERROR` | erro não tratado (stacktrace só no log) |

---

## Endpoints

### Health

#### `GET /health` — liveness · sem auth

```
200 { "status": "UP" }
```

#### `GET /health/ready` — readiness · sem auth

Checa Postgres + MinIO. `200` se ambos OK, `503` se algum falhar.

```json
{
  "status": "UP",
  "checks": {
    "db":      { "status": "UP" },
    "storage": { "status": "UP" }
  }
}
```

Em falha: `status: "DOWN"` e o check ruim vem com `"detail": "..."`.

---

### Catálogo

#### `GET /roms` — lista o catálogo · auth `user`

Query params (todos opcionais):

| Param | Tipo | Default | Notas |
|---|---|---|---|
| `system` | `GBA` \| `NDS` \| `3DS` | — | filtro; case-insensitive na entrada |
| `page` | int ≥ 0 | `0` | 0-based |
| `size` | int 1..200 | `50` | acima de 200 é limitado a 200 |

Resposta `200` — página:

```json
{
  "items": [
    {
      "id": "a9dec84d-1234-…",
      "name": "Pokemon Emerald",
      "system": "GBA",
      "sizeBytes": 16777216,
      "hash": "a9dec84dfe7f62ab…",
      "coverUrl": null,
      "createdAt": "2026-09-02T13:09:56.200618Z"
    }
  ],
  "page": 0,
  "size": 50,
  "total": 1
}
```

- `total` = contagem total (após o filtro), não o tamanho da página.
- Ordenação estável por `createdAt, id` (mesma ordem entre páginas).
- `coverUrl` é omitido do JSON quando `null` (não vem a chave). Trate como nullable.

**Paginação no app:** peça `page=0`, depois `page=1`… até `items.length < size` **ou** `page*size + items.length >= total`.

#### `GET /roms/{id}` — detalhe · auth `user`

`{id}` é o UUID.

```
200 → RomDto
400 INVALID_PATH_PARAM  (id não é UUID)
404 ROM_NOT_FOUND
```

#### `GET /roms/{id}/download` — gera URL de download · auth `user`

```json
200
{
  "url": "https://rom-catalog-storage.lucascanno.com.br/roms/GBA/a9dec84d….gba?X-Amz-Algorithm=…&X-Amz-Signature=…",
  "expiresAt": "2026-09-02T13:24:58.485763Z",
  "hash": "a9dec84dfe7f62ab…",
  "sizeBytes": 16777216
}
```

```
404 ROM_NOT_FOUND        (ROM não existe)
503 STORAGE_UNAVAILABLE  (MinIO fora do ar, ou o objeto sumiu do bucket)
```

A `url` é uma **presigned URL** temporária (TTL padrão 15 min). Ver [Fluxo de download](#fluxo-de-download).

---

### Favoritos

#### `GET /favorites` — lista favoritos · auth `user`

```json
200
[
  {
    "romId": "a9dec84d-…",
    "createdAt": "2026-09-02T14:00:00Z",
    "rom": { …RomDto… }
  }
]
```

Ordenado do mais recente pro mais antigo. Cada item traz o `RomDto` completo embutido (não precisa fazer `GET /roms/{id}` depois).

#### `POST /favorites` — favorita uma ROM · auth `user`

`Content-Type: application/json`

```json
{ "romId": "a9dec84d-1234-…" }
```

```
201 → FavoriteDto   (criado agora)
200 → FavoriteDto   (já era favorito — idempotente)
400 INVALID_BODY     (JSON ruim ou romId não-UUID)
404 ROM_NOT_FOUND    (romId não existe no catálogo)
415                  (sem Content-Type / sem corpo)
```

#### `DELETE /favorites/{romId}` — remove dos favoritos · auth `user`

```
204   sempre que o path é válido (idempotente — remover algo que não era favorito também dá 204)
400 INVALID_PATH_PARAM  (romId não é UUID)
```

**Favoritos no app:** não há sync incremental — busque a lista inteira em `GET /favorites`. Toggle otimista local + `POST`/`DELETE`; em erro, re-busque a lista (servidor é a fonte da verdade).

---

### Admin

#### `GET /admin/ping` — confirma escopo admin · auth `admin`

```
200 { "scope": "admin", "status": "ok" }
403 FORBIDDEN   (token user)
```

#### `POST /admin/roms` — ingestão de ROM · auth `admin`

Dois modos, pelo `Content-Type`:

**a) `multipart/form-data`** — o servidor recebe os bytes, calcula sha256 + tamanho, sobe no MinIO e registra.

| Parte | Obrigatória | Notas |
|---|---|---|
| `file` | sim | o arquivo da ROM |
| `name` | não | default = nome do arquivo sem extensão |
| `system` | não | `GBA`/`NDS`/`3DS`; sem ela, deduz pela extensão (`.gba`/`.nds`/`.3ds`/`.cia`) |
| `coverUrl` | não | URL da capa |

**b) `application/json`** — para um objeto **já** no bucket:

```json
{
  "name": "Pokemon Emerald",
  "system": "GBA",
  "hash": "a9dec84dfe7f62ab…",
  "sizeBytes": 16777216,
  "storageKey": "GBA/a9dec84d….gba",
  "coverUrl": null
}
```

Respostas (ambos os modos):

```
201 → RomDto            (criado)
409 → RomDto            (já existe ROM com esse hash — nada é escrito; corpo = o existente)
400 INVALID_SYSTEM | UNKNOWN_SYSTEM | MISSING_FILE
422 OBJECT_NOT_FOUND | HASH_MISMATCH | SIZE_MISMATCH   (modo JSON)
```

> Na prática a ingestão é feita pela LAN (script `./gradlew ingest` ou upload direto) — o app provavelmente **não** precisa disso. Documentado por completude.

---

## Modelo de dados

### `RomDto`

| Campo | Tipo | Notas |
|---|---|---|
| `id` | string (UUID) | |
| `name` | string | |
| `system` | `"GBA"` \| `"NDS"` \| `"3DS"` | valores exatos nas respostas |
| `sizeBytes` | long | tamanho do arquivo |
| `hash` | string | sha256 hex minúsculo — use pra verificar integridade do download |
| `coverUrl` | string \| ausente | omitido quando não há capa |
| `createdAt` | string (ISO-8601) | ex.: `2026-09-02T13:09:56.200618Z` |

### `FavoriteDto`

```
{ romId: string(UUID), createdAt: string(ISO), rom: RomDto }
```

### `DownloadResponse`

```
{ url: string, expiresAt: string(ISO), hash: string, sizeBytes: long }
```

### `PageDto<T>`

```
{ items: T[], page: int, size: int, total: long }
```

Datas são instantes ISO-8601 em UTC (`Z`). No Android: `java.time.Instant.parse(...)` ou `kotlinx.datetime.Instant.parse(...)`.

---

## Fluxo de download

A API **não faz proxy dos bytes**. O download é em 2 passos:

1. **`GET /roms/{id}/download`** (com `Authorization`) → `{ url, expiresAt, hash, sizeBytes }`.
2. **`GET <url>`** — requisição HTTP **simples**, **sem** header `Authorization` (a autorização está na query string assinada). Faça streaming direto pra um arquivo (OkHttp `response.body.byteStream()` / `source()`), mostrando progresso com base em `sizeBytes`.
3. Ao terminar, calcule o **SHA-256** do arquivo e compare com `hash` (hex minúsculo). Se não bater, descarte e refaça.

Regras:

- A `url` **expira** (`expiresAt`, ~15 min). Se o download não começou ou foi interrompido depois disso, o MinIO responde `403` — **refaça o passo 1** pra pegar uma URL nova (não precisa reautenticar na API).
- ROMs de 3DS podem passar de 1 GB — use `WorkManager` + download retomável (Range requests; o MinIO suporta `Range`).
- O host da `url` é `rom-catalog-storage.lucascanno.com.br` (MinIO via Cloudflare Tunnel). Downloads não têm limite de tamanho pelo Tunnel.

---

## Notas para o app Android

- **Cliente HTTP:** Retrofit + OkHttp, ou Ktor Client. Convertaer JSON com kotlinx.serialization ou Moshi.
- **Interceptor de auth:** adiciona `Authorization: Bearer <token>` em tudo, **menos** nas chamadas à presigned URL de download.
- **Base URL:** configurável (local vs homelab). O app de produção usa `https://rom-catalog-api.lucascanno.com.br`.
- **401:** limpa o estado de "logado", leva pra tela de colar token.
- **`system`:** enum no app com 3 valores; mapeie 1:1 com as strings `GBA`/`NDS`/`3DS`.
- **Cache local (Room):** guarde o `RomDto` e o hash do que já foi baixado; use `hash` como chave de "esse arquivo local corresponde a essa ROM".
- **Favoritos:** `GET /favorites` na inicialização; toggles com `POST`/`DELETE` idempotentes; re-sincronize a lista após um erro.
- **`GET /health/ready`:** útil pra uma tela de diagnóstico ("servidor OK / DB / storage").
- **Abertura no emulador:** depois de baixado, `Intent` explícito pro RetroArch/Azahar apontando pro arquivo (fora do escopo da API).

### Exemplos rápidos (curl)

```bash
API=https://rom-catalog-api.lucascanno.com.br
TOKEN=<seu token user>

curl -s $API/health/ready | jq
curl -s -H "Authorization: Bearer $TOKEN" "$API/roms?system=GBA&page=0&size=20" | jq
curl -s -H "Authorization: Bearer $TOKEN" $API/roms/<id> | jq

# download
DL=$(curl -s -H "Authorization: Bearer $TOKEN" $API/roms/<id>/download)
echo "$DL" | jq
URL=$(echo "$DL" | jq -r .url)
curl -L -o rom.bin "$URL"
sha256sum rom.bin        # deve bater com  $(echo "$DL" | jq -r .hash)

# favoritos
curl -s -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"romId":"<id>"}' $API/favorites | jq
curl -s -H "Authorization: Bearer $TOKEN" $API/favorites | jq
curl -s -X DELETE -H "Authorization: Bearer $TOKEN" $API/favorites/<id> -o /dev/null -w '%{http_code}\n'
```
