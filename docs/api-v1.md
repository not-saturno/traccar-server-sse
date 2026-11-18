# Rotas da fork em `/api/v1`

Tudo o que esta fork acrescenta à API REST fica em `/api/v1/*`. O resto de `/api/*` é a API original do Traccar,
sem alteração de rota nem de contrato. Assim fica claro o que é da fork e o que vem do upstream, e um merge do
upstream não esbarra nas rotas novas.

Os recursos ficam no pacote `org.traccar.api.resource.v1` (`src/main/java/org/traccar/api/resource/v1/`), que o
Jersey registra junto com os demais (`ResourceConfig.packages` é recursivo).

## Rotas

| Rota | Login | Descrição | Documento |
|---|---|---|---|
| `GET /api/v1/sse` | Sim | Stream SSE de posições, status e logs (administrador: todos os dispositivos) | [sse.md](sse.md) |
| `GET /api/v1/public/devices` | Não (`public.features`) | Dispositivos públicos | [public-access.md](public-access.md) |
| `GET /api/v1/public/positions` | Não | Últimas posições dos dispositivos públicos | [public-access.md](public-access.md) |
| `GET /api/v1/public/stream` | Não | SSE dos dispositivos públicos | [public-access.md](public-access.md) |
| `GET`/`DELETE /api/v1/processing/timings` | Administrador | Tempo de cada etapa do processamento de posições | [processamento.md](processamento.md) |
| `GET /api/v1/reports/emissions/fleet` | Sim | BFF: comparativo mensal da frota | [emissoes.md](emissoes.md) |
| `GET /api/v1/reports/emissions/monthly` | Sim | BFF: relatório mensal de um dispositivo, com resumo e memorial | [emissoes.md](emissoes.md) |
| `GET /api/v1/reports/emissions/instant` | Sim | BFF: relatório por hora e trajeto de um dispositivo | [emissoes.md](emissoes.md) |
| `GET /api/v1/emission-factors` | Sim | Histórico dos parâmetros de emissão e custo | [emissoes.md](emissoes.md) |
| `GET /api/v1/emission-factors/parameters` | Sim | Parâmetros existentes, unidades e valores padrão | [emissoes.md](emissoes.md) |
| `GET /api/v1/emission-factors/effective` | Sim | Valores que valem numa data | [emissoes.md](emissoes.md) |
| `POST`/`PUT`/`DELETE /api/v1/emission-factors[/{id}]` | Administrador | Manutenção do histórico | [emissoes.md](emissoes.md) |

## O que mudou em endpoints do Traccar

Nenhum recurso REST do upstream teve rota ou resposta alterada pela fork. As mudanças da fork em código do
upstream são internas (filtro de segurança para as rotas públicas, `ConnectionManager`, `BufferingManager`,
`DistanceHandler`, `ProcessingHandler`, campo `time` do `LogRecord`) e aparecem só nas rotas novas acima. Por isso não
há cópia de endpoint do Traccar em `/api/v1`: os endpoints que eram da fork (`/api/sse`, `/api/public/*`,
`/api/processing/timings`) foram movidos para `/api/v1`, sem alias nas rotas antigas.

Efeito colateral no WebSocket original (`/api/socket`): o `LogRecord` enviado ali também ganhou o campo `time`.

## Migração (cliente antigo)

| Antes | Agora |
|---|---|
| `/api/sse` | `/api/v1/sse` |
| `/api/public/devices` | `/api/v1/public/devices` |
| `/api/public/positions` | `/api/v1/public/positions` |
| `/api/public/stream` | `/api/v1/public/stream` |
| `/api/processing/timings` | `/api/v1/processing/timings` |

As rotas antigas respondem `404`. O front (submódulo `traccar-web`) já usa as novas. Proxy nginx: o bloco de SSE
(`docker/nginx/traccar.conf.template`) casa `^/api/v1/(sse|public/stream)$`; quem tem uma cópia antiga do template
precisa atualizar, senão o SSE passa pelo bloco genérico com buffer ligado e os eventos chegam atrasados. O Caddy não
precisa de mudança (já entrega `text/event-stream` sem buffer em qualquer rota).
