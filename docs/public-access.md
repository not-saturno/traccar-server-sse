# Camada de acesso público

Permite expor funcionalidades **somente leitura** do servidor sem login, por exemplo o mapa dos ônibus para
visitantes, e manter todo o resto protegido.

## Princípios

- **Desligado por padrão.** Sem configuração, todo endpoint público responde `404`.
- **Allowlist por funcionalidade.** Cada endpoint público declara uma funcionalidade (`@PublicFeature("nome")`), e
  só as listadas em `public.features` ficam acessíveis.
- **Allowlist por dispositivo.** Só aparecem dispositivos com o atributo `public = true` que não estejam
  desabilitados.
- **Campos mínimos.** Os endpoints públicos usam DTOs próprios (`PublicDevice`, `PublicPosition`). `uniqueId`
  (IMEI), telefone, contato, atributos customizados, atributos de protocolo e IP nunca são expostos.
- **Sem escrita.** Nenhum endpoint público altera dados. Cadastro, edição e relatórios continuam exigindo login.

## Configuração (`conf/traccar.xml`)

```xml
<!-- liga a camada pública -->
<entry key='public.enable'>true</entry>

<!-- funcionalidades liberadas (padrão: todas) -->
<entry key='public.features'>devices,positions,stream</entry>

<!-- atributo do dispositivo que o torna público (padrão: public) -->
<entry key='public.attribute'>public</entry>

<!-- cache da lista de dispositivos públicos, em segundos (padrão: 30) -->
<entry key='public.cacheTtl'>30</entry>

<!-- se o front estiver em OUTRO domínio, libere a origem (CORS) -->
<entry key='web.origin'>https://onibus.exemplo.ufpa.br</entry>
```

| Chave | Tipo | Padrão | Descrição |
|---|---|---|---|
| `public.enable` | boolean | `false` | Interruptor geral |
| `public.features` | lista | `devices,positions,stream` | Funcionalidades liberadas, separadas por vírgula |
| `public.attribute` | string | `public` | Atributo booleano que marca o dispositivo como público |
| `public.cacheTtl` | int (s) | `30` | Validade do cache da lista de dispositivos públicos |

Alterações exigem reiniciar o servidor.

## Marcando um dispositivo como público

No dispositivo, adicione o atributo `public` com valor `true`, pela interface ou pela API:

```bash
curl -u admin:senha -X PUT -H 'Content-Type: application/json' \
  https://servidor/api/devices/12 \
  -d '{"id":12,"name":"Circular 01","uniqueId":"123456","attributes":{"public":true}}'
```

A mudança aparece em até `public.cacheTtl` segundos.

## Endpoints

| Funcionalidade | Método e rota | Resposta |
|---|---|---|
| `devices` | `GET /api/public/devices` | `[{id, name, category, status, lastUpdate}]`, ordenado por nome |
| `positions` | `GET /api/public/positions` | `[{deviceId, latitude, longitude, speed, course, fixTime}]`, última posição de cada dispositivo público |
| `stream` | `GET /api/public/stream` | SSE (`text/event-stream`), detalhado abaixo |

`speed` vem em nós (padrão do Traccar) e `course` em graus.

### `GET /api/public/stream`

Ao conectar, envia a última posição de cada dispositivo público e depois as atualizações em tempo real.

```
event: positions
data: {"deviceId":3,"latitude":-1.4748,"longitude":-48.4456,"speed":12.3,"course":90.0,"fixTime":"2026-10-29T12:00:00.000+00:00"}

event: devices
data: {"id":3,"name":"Circular 01","category":"bus","status":"online","lastUpdate":"..."}

: keepalive
```

- Um objeto por evento, não um array.
- Eventos de alarme (`events`) e `logs` **nunca** são enviados no stream público.
- No navegador, use `new EventSource('/api/public/stream')`, sem `withCredentials`.

## Comportamento de segurança

| Situação | Resposta |
|---|---|
| `public.enable=false`, ou funcionalidade fora de `public.features` | `404 Not Found`, mesmo para usuários logados |
| Funcionalidade liberada, sem login | `200`, só com dispositivos públicos |
| Funcionalidade liberada, com login | `200`, mesma resposta (o endpoint público não usa as permissões do usuário) |
| Qualquer outro endpoint `/api/*` sem login | `401`, comportamento original do Traccar |

## Como funciona (implementação)

| Peça | Arquivo | Papel |
|---|---|---|
| `@PublicFeature` | `src/main/java/org/traccar/api/security/PublicFeature.java` | Marca um método de recurso como funcionalidade pública |
| `SecurityRequestFilter` | `src/main/java/org/traccar/api/security/SecurityRequestFilter.java` | Responde 404 se a funcionalidade estiver desligada e dispensa login se estiver ligada |
| `PublicAccessService` | `src/main/java/org/traccar/publicaccess/PublicAccessService.java` | Lê a configuração e mantém o cache dos dispositivos públicos |
| `PublicResource` | `src/main/java/org/traccar/api/resource/PublicResource.java` | Endpoints `/api/public/*` |
| `PublicSseListener` | `src/main/java/org/traccar/publicaccess/PublicSseListener.java` | Filtra as atualizações do stream público |
| `ConnectionManager` (alterado) | `src/main/java/org/traccar/session/ConnectionManager.java` | `addGlobalListener`/`removeGlobalListener`: recebem atualizações de todos os dispositivos, e o listener filtra |
| Chaves de config | `src/main/java/org/traccar/config/Keys.java` | `PUBLIC_ENABLE`, `PUBLIC_FEATURES`, `PUBLIC_ATTRIBUTE`, `PUBLIC_CACHE_TTL` |
| Testes | `src/test/java/org/traccar/publicaccess/PublicAccessServiceTest.java` | Padrão desligado, lista de funcionalidades, filtro de dispositivos |

### Adicionando uma nova funcionalidade pública

1. Crie um método em `PublicResource`, ou em outro recurso que **não** dependa de `getUserId()`.
2. Anote com `@PublicFeature("nome")`.
3. Exponha só dispositivos de `PublicAccessService.getDevices()` e retorne DTOs com campos mínimos.
4. Documente aqui e adicione `nome` em `public.features` onde for usar.

> Não anote endpoints existentes do Traccar (ex.: `/api/devices`) com `@PublicFeature`. Eles assumem um usuário
> logado e aplicariam permissões de forma errada.

## Limitações conhecidas

- A lista de dispositivos públicos fica em cache por `public.cacheTtl`. O `status` em `/api/public/devices` pode
  ficar defasado até esse tempo, enquanto o stream envia mudanças de status na hora.
- O recarregamento do cache pode acontecer dentro do broadcast do `ConnectionManager` (consulta ao banco a cada
  `cacheTtl`). Para frotas pequenas isso é irrelevante. Se virar gargalo, troque por um refresh agendado.
- Não há rate limiting nos endpoints públicos. Se for exposto na internet, considere limitar no proxy reverso
  (nginx etc.).
