# Documentação da fork traccar-server-sse

Documentação das funcionalidades e alterações desta fork em relação ao
[Traccar upstream](https://github.com/traccar/traccar). Mantenha estes arquivos atualizados a cada mudança.

| Documento | Conteúdo |
|---|---|
| [public-access.md](public-access.md) | Camada de acesso público: configuração, endpoints sem login, segurança |
| [sse.md](sse.md) | Stream SSE autenticado (`/api/sse`), comparação com o WebSocket, teste de carga e recomendação |
| [frontend.md](frontend.md) | Submódulo `traccar-web` (gps-tracker-front): rotas, sessão, execução local, build |
| [suavizacao.md](suavizacao.md) | Suavização de localização: algoritmo de referência e o que foi implementado (animação da troca de ponto no front) |
| [osmand-security.md](osmand-security.md) | Segurança dos protocolos dos rastreadores: OsmAnd e Watch (o dos GPS dos ônibus) |
| [desenvolvimento.md](desenvolvimento.md) | Desenvolvimento: modo dev via Docker (hot reload, depurador, portas) ou direto no host |
| [cloudflare.md](cloudflare.md) | Publicar na Cloudflare via Cloudflare Tunnel (produção e dev), limites e ajustes do painel |
| [deploy.md](deploy.md) | Produção: Caddy com HTTPS automático (recomendado) ou nginx, no host ou em Docker, front, verificação |
| [upstream-sync.md](upstream-sync.md) | Como sincronizar com o Traccar upstream e conflitos conhecidos |
| [CHANGELOG.md](CHANGELOG.md) | Histórico de alterações da fork |

## Visão geral

```
                ┌──────────────── Traccar (esta fork) ────────────────┐
GPS (ônibus) ──▶│ protocolos ─▶ ConnectionManager ─┬─▶ /api/socket (WS, login)  │
                │                                  ├─▶ /api/sse    (SSE, login) │
                │                                  └─▶ /api/public/stream (SSE, │
                │                                       sem login, só públicos) │
                │ /api/public/devices, /api/public/positions (sem login)        │
                │ demais /api/*  (login obrigatório)                            │
                └───────────────────────────────────────────────────────────────┘
                                   ▲
                    gps-tracker-front (submódulo traccar-web)
                    visitante: mapa + lista de ônibus públicos
                    logado: relatórios, cadastro/edição de dispositivos, conta
```
