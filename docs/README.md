# Documentação da fork traccar-server-sse

Documentação das funcionalidades e alterações desta fork em relação ao
[Traccar upstream](https://github.com/traccar/traccar). Mantenha estes arquivos atualizados a cada mudança.

| Documento | Conteúdo |
|---|---|
| [api-v1.md](api-v1.md) | Rotas da fork em `/api/v1/*` (separadas da API original do Traccar), lista completa e migração |
| [emissoes.md](emissoes.md) | Relatórios de emissão/consumo/custo calculados no servidor (BFF) e histórico dos fatores por dia/mês/ano |
| [public-access.md](public-access.md) | Camada de acesso público: configuração, endpoints sem login, segurança |
| [sse.md](sse.md) | Stream SSE autenticado (`/api/v1/sse`), comparação com o WebSocket, teste de carga e recomendação |
| [processamento.md](processamento.md) | Tempo de processamento das posições: buffer de reordenação, medição por handler (`/api/v1/processing/timings`) |
| [frontend.md](frontend.md) | Submódulo `traccar-web` (gps-tracker-front): rotas, sessão, execução local, build |
| [suavizacao.md](suavizacao.md) | Suavização de localização: algoritmo de referência e o que foi implementado (animação da troca de ponto no front) |
| [tk915.md](tk915.md) | Rastreador TKSTAR TK915 dos ônibus: comandos SMS (manual oficial, gist da comunidade e fornecedor), configuração para este servidor, comandos pelo servidor |
| [osmand-security.md](osmand-security.md) | Segurança dos protocolos dos rastreadores: OsmAnd e Watch (o dos GPS dos ônibus) |
| [desenvolvimento.md](desenvolvimento.md) | Desenvolvimento: modo dev via Docker (hot reload, depurador, portas) ou direto no host |
| [cloudflare.md](cloudflare.md) | Publicar na Cloudflare via Cloudflare Tunnel (produção e dev), limites e ajustes do painel |
| [imagem-docker.md](imagem-docker.md) | Exportar a imagem Docker (tags `<versão>` e `latest`) e rodar no servidor atrás do Caddy, inclusive com certificado para IP (sem domínio) |
| [deploy.md](deploy.md) | Produção: Caddy com HTTPS automático (recomendado) ou nginx, no host ou em Docker, front, verificação |
| [upstream-sync.md](upstream-sync.md) | Como sincronizar com o Traccar upstream e conflitos conhecidos |
| [CHANGELOG.md](CHANGELOG.md) | Histórico de alterações da fork |

## Visão geral

```
                ┌───────────────────── Traccar (esta fork) ──────────────────────┐
GPS (ônibus) ──▶│ protocolos ─▶ ConnectionManager ─┬─▶ /api/socket (WS, login)   │
                │                                  ├─▶ /api/v1/sse (SSE, login)  │
                │                                  └─▶ /api/v1/public/stream     │
                │                                       (SSE, sem login, públicos)│
                │ /api/v1/public/devices, /api/v1/public/positions (sem login)   │
                │ /api/v1/reports/emissions/* (BFF dos relatórios, login)        │
                │ /api/v1/emission-factors (histórico dos parâmetros, login)     │
                │ demais /api/* (Traccar original, login obrigatório)            │
                └────────────────────────────────────────────────────────────────┘
                                   ▲
                    gps-tracker-front (submódulo traccar-web)
                    visitante: mapa + lista de ônibus públicos
                    logado: relatórios, cadastro/edição de dispositivos, conta
```
