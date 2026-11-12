# SSE autenticado (`/api/sse`)

Stream Server-Sent Events com as atualizações em tempo real dos dispositivos **do usuário logado** (para o
administrador, de **todos** os dispositivos; ver [abaixo](#administrador-todos-os-dispositivos)). É o
equivalente SSE do WebSocket nativo `/api/socket`. Para visitantes sem login, veja o stream público em
[public-access.md](public-access.md).

## Uso

```js
const source = new EventSource('/api/sse', { withCredentials: true }) // precisa do cookie de sessão
source.addEventListener('positions', (e) => console.log(JSON.parse(e.data)))
```

Também aceita `Authorization: Bearer <token>`, mas o `EventSource` do navegador não envia headers. Nesse caso use
um cliente SSE que suporte headers.

## Eventos

| Evento | Payload (um objeto por evento) | Quando |
|---|---|---|
| `positions` | `Position` completo do Traccar | Ao conectar (última posição de cada dispositivo) e a cada nova posição |
| `devices` | `Device` completo | Mudança de status ou dados do dispositivo |
| `events` | `Event` (alarme, ignição, geocerca...) | Eventos do usuário |
| `logs` | `LogRecord` | Logs brutos de protocolo (ver observação) |
| `: keepalive` | comentário | Periodicamente, para manter a conexão |

> Diferente do WebSocket, que só envia `logs` quando o cliente pede (`{"logs": true}`), o SSE autenticado envia
> logs sempre. Os clientes devem ignorar o evento se não usarem.

### Administrador: todos os dispositivos

No Traccar, o listener de cada usuário só recebe os dispositivos vinculados a ele (`tc_user_device`), inclusive
para administrador. O `/api/socket` nativo continua assim. No `/api/sse` desta fork, o administrador recebe:

| Evento | Origem | Alcance |
|---|---|---|
| `positions`, `devices`, keepalive | listener global (`ConnectionManager.addGlobalListener`) | Todos os dispositivos, inclusive os criados depois da conexão |
| `positions` ao conectar | `Condition.LatestPositions` sem filtro de usuário | Última posição de cada dispositivo |
| `events`, `logs` | listener do usuário (`addListener(userId)`) | Como antes: só os do próprio administrador |

Os eventos ficam no listener do usuário porque o `ConnectionManager` só os entrega por usuário. Como o listener do
usuário ignora posições e status, não há posição duplicada para dispositivos vinculados ao administrador.
Ao desconectar, os dois listeners são removidos.

## Comparação com o WebSocket nativo

| | WebSocket `/api/socket` | SSE `/api/sse` |
|---|---|---|
| Envelope | `{"positions":[...]}`, com o tipo na chave e sempre array | `event: positions` + `data: {...}`, objeto único |
| Snapshot inicial | um envio com todas as posições | um evento por posição |
| Logs | opt-in | sempre |
| Keepalive | `{}` | comentário SSE |
| Direção | bidirecional | servidor para cliente |

Os modelos (`Position`, `Device`, `Event`, `LogRecord`) e o serializador Jackson são os mesmos.

### Os streams só repetem dados de outros endpoints?

Sim. SSE e WebSocket não criam dado novo: eles **empurram** em tempo real o que também está disponível via REST,
evitando que o cliente consulte o servidor de tempos em tempos (polling).

| Evento | Equivalente REST |
|---|---|
| `positions` | `GET /api/positions` (última posição) |
| `devices` (status online/offline) | `GET /api/devices` |
| `events` | `GET /api/reports/events` |
| `logs` | **Nenhum**: logs brutos de protocolo só existem nos streams |

O mesmo vale para o stream público: `/api/public/stream` = `/api/public/devices` + `/api/public/positions`, só
que por push. O polling seria pior, porque cada visitante consultaria o banco a cada X segundos, enquanto o
push só envia algo quando um ônibus manda posição.

## Desempenho: SSE vs WebSocket

### Teste de carga (2026-10-29)

Feito na máquina de desenvolvimento (WSL2, JDK 21, H2) com o servidor desta fork, medindo via `jstack`, `ss` e
`jcmd`:

| Cenário | Conexões abertas | Threads do Jetty (`qtp`) | Heap da JVM |
|---|---|---|---|
| Servidor parado (sem clientes) | 0 | 12 | — |
| 20 clientes em `/api/public/stream` | 20 | 30, **todas ociosas** (nenhuma presa no Jersey) | — |
| 300 clientes em `/api/public/stream` | 300 | 35 | ~50 MB (JVM inteira) |

Conclusão: **o SSE não prende uma thread por cliente**. As conexões ficam abertas sem ocupar o pool do Jetty,
então centenas de visitantes simultâneos cabem com folga.

Limitações do teste:
- Mede principalmente conexões **ociosas** (o despejo inicial mais keepalive), não um fluxo contínuo de posições
  para todos os clientes.
- O WebSocket (`/api/socket`) **não** foi medido nas mesmas condições. A comparação abaixo é técnica, não medida.
- Máquina de desenvolvimento, não o servidor de produção.

### Prós e contras

| Aspecto | SSE | WebSocket |
|---|---|---|
| Custo por cliente no servidor | Uma conexão + JSON serializado por cliente | Igual |
| Bytes extras por mensagem | ~25 (`event: positions\ndata: `) | 2 a 14 (frame) |
| Reconexão | **Automática** no navegador (`EventSource`) | Precisa ser escrita no front |
| Autenticação no navegador | Só cookie (o `EventSource` não envia headers) | Cookie |
| Limite de conexões do navegador | **6 por domínio em HTTP/1.1**, somando todas as abas. Não existe com HTTP/2 | Não se aplica |
| Proxy reverso (nginx) | `proxy_buffering off` | Headers `Upgrade`/`Connection` |
| Direção | Servidor para cliente | Bidirecional |
| Manutenção | Código da fork (`SseResource`, `PositionSseListener`, `PublicResource`) | Mantido pelo Traccar oficial |
| Acesso sem login | `/api/public/stream` | Não existe (o `/api/socket` exige sessão) |

A diferença de bytes por mensagem é irrelevante perto do JSON. O que pesa mais é o **tamanho do payload**: um
`Position` completo tem ~500 a 800 bytes (atributos de protocolo), e o `PublicPosition` do stream público tem
~150. Essa redução já existe, independente do transporte.

### Recomendação

- **Visitantes (mapa público): SSE.** O mapa só recebe dados, tem reconexão automática e não exige um
  servidor WebSocket público novo. Se o limite de 6 conexões do HTTP/1.1 incomodar (usuário com muitas abas),
  a solução é **HTTP/2 no nginx**, não trocar de protocolo.
- **Usuários logados:** os dois servem. O único argumento a favor do `/api/socket` é manutenção, já que é código
  do Traccar oficial. O desempenho não decide.

### Quando a escala crescer

Com milhares de visitantes, o próximo gargalo provável é a **serialização repetida**: hoje cada listener converte
a mesma posição em JSON para o seu cliente. A otimização seria serializar uma vez por posição e enviar o mesmo
texto a todos os clientes do stream público (ex.: `SseBroadcaster` do Jersey). Hoje não é necessário.

Outro ponto: a lista de dispositivos públicos é recarregada do banco a cada `public.cacheTtl` dentro do
broadcast do `ConnectionManager` (ver [public-access.md](public-access.md#limitações-conhecidas)).

## Implementação

| Arquivo | Papel |
|---|---|
| `src/main/java/org/traccar/api/resource/SseResource.java` | Endpoint `GET /api/sse` (caminho do administrador em `subscribeAdministrator`) |
| `src/main/java/org/traccar/sse/PositionSseListener.java` | `ConnectionManager.UpdateListener` que escreve no `SseEventSink` e se remove quando o cliente desconecta |
| `src/main/java/org/traccar/api/ApiResourceConfig.java` | Registra o `SseFeature` do Jersey |
| `build.gradle` | Dependência `org.glassfish.jersey.media:jersey-media-sse` |

O `ConnectionManager` itera sobre cópias das listas de listeners. Assim, um listener SSE pode se remover durante
o broadcast sem disparar `ConcurrentModificationException` (problema que existia antes dessa correção).
