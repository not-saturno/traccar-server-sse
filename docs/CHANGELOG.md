# Changelog da fork

Formato: mais recente primeiro. Datas em AAAA-MM-DD.

## [Não lançado] — branch `feat/public-access`

### Adicionado
- Front: animação da troca de ponto no mapa. O marcador desliza até a nova posição em 1,4 s (ease-out cúbico),
  com salto acima de 1 km e suporte a `prefers-reduced-motion`. Só no cliente: servidor e posições não mudam.
  Ver [suavizacao.md](suavizacao.md).
- Camada de acesso público configurável (`public.enable`, `public.features`, `public.attribute`,
  `public.cacheTtl`) com a anotação `@PublicFeature`. Ver [public-access.md](public-access.md).
- Endpoints sem login: `GET /api/public/devices`, `GET /api/public/positions` e `GET /api/public/stream` (SSE),
  restritos a dispositivos com `public=true` e com campos mínimos.
- `ConnectionManager.addGlobalListener`/`removeGlobalListener`.
- Testes `PublicAccessServiceTest`.
- Pasta `docs/`.
- `docs/osmand-security.md`: levantamento de segurança do protocolo OsmAnd.
- `docs/sse.md`: teste de carga (300 clientes SSE), prós e contras SSE vs WebSocket e recomendação.
- Deploy em produção: `docker/Dockerfile.sse` (front + backend + `jlink`), `docker/compose/traccar-sse.yaml`
  (nginx opcional via `--profile nginx`), `docker/compose/traccar-sse.xml`, template de nginx
  `docker/nginx/traccar.conf.template` (HTTPS, HTTP/2, SSE sem buffer, WebSocket) e `.dockerignore`. Ver
  [deploy.md](deploy.md).
- Deploy com Caddy e certificado automático (emissão e renovação): `docker/caddy/Caddyfile` e
  `docker/compose/traccar-sse-caddy.yaml`. Passa a ser o caminho recomendado em [deploy.md](deploy.md).

### Alterado
- Submódulo `traccar-web` agora aponta para `gps-tracker-front` (branch `main`). Ver [frontend.md](frontend.md).
- Front: login automático com credenciais embutidas removido. Route guard exige login em `/settings/**`. O mapa
  usa os endpoints públicos para visitantes. API configurável via `VITE_API_URL`, com proxy de dev.
- `setup/build-dist.sh` empacota `traccar-web/dist`, e as traduções viraram opcionais.
- Front responsivo: bottom sheet no mapa, abas inferiores em `/settings`, cards em Dispositivos, formulários em
  uma coluna, inputs de 16px no celular, `KeepAlive` no mapa, troca de sessão sem reload, rota inexistente
  redireciona para o mapa. O cadastro ganhou o visual do login. Ver
  [frontend.md](frontend.md#layout-responsivo).
- Front: item da lista de dispositivos redesenhado. Agora tem ícone do ônibus com indicador de status, selo
  Online/Offline, tempo relativo do último sinal ("há 5 min") e aviso "Sem posição" (item desabilitado quando
  não há o que centralizar no mapa).
- Front: `/register` só existe no primeiro acesso (servidor sem usuários, e o primeiro vira administrador) ou
  quando o administrador abre o cadastro (`registration` no servidor), como no traccar-web original. O link
  "Criar conta" segue a mesma regra.

### Corrigido
- Front: no primeiro clique em um dispositivo (ou em qualquer mudança de zoom) o mapa voltava para o centro
  inicial. O `:center` era um array literal no template, recriado a cada renderização. Agora é a constante
  `INITIAL_CENTER`.
- Front: erro `Cannot read properties of null (reading 'parentNode')` ao trocar de rota, causado pela
  transição `out-in` adicionada junto com o `KeepAlive`. A transição foi removida.
- Front: o cadastro gravava categoria `bus`/`ship`, que os relatórios e a edição não reconhecem. As categorias
  agora ficam centralizadas em `src/data/categories.ts`.
- Front: o mapa mostrava todos os dispositivos como Offline até chegar uma posição ao vivo. Agora o status
  inicial vem do servidor, e a lista mostra o horário do último sinal.
- `ConcurrentModificationException` quando um listener (SSE) se removia durante o broadcast no `ConnectionManager`.
- Front: o handler do evento SSE `devices` quebrava por esperar um array.
- Ordem de imports de `SseResource` e `PositionSseListener` (checkstyle).

## 2026-10-29 — branch `chore/sync-upstream-traccar`
- Merge do `traccar/traccar` master (694 commits, até a 6.16.0).
- `SseFeature` passou para `ApiResourceConfig` após o refactor upstream do `WebServer`.

## 2025-12 — SSE inicial (`master`)
- `b0c8d47` `GET /api/sse` + `PositionSseListener`.
- `a45abb5`, `dd023ef` README da fork.
- `721326b` Registro do `SseFeature` no Jersey.
- `66f0976` Correção: o listener se removia no primeiro envio (`null` de sucesso tratado como falha).
