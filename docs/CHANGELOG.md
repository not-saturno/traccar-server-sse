# Changelog da fork

Formato: mais recente primeiro. Datas em AAAA-MM-DD.

## [Não lançado] — branch `feat/public-access`

### Adicionado
- Front: troca da própria senha em **Conta** (pede a senha atual) e salvamento de nome e e-mail; antes o botão
  só fazia `console.log`. Os painéis sem função ("Preferências", "Localização", "Deletar conta") foram removidos.
- Front: na tela de **Usuários**, o administrador vincula e desvincula dispositivos de usuários comuns (vale na
  hora, inclusive no mapa ao vivo) e redefine a senha de outros usuários.
- Front: detalhes completos do dispositivo no mapa para o administrador (bateria, velocidade, direção, satélites,
  sinal GSM, horários do GPS/aparelho/servidor, identificação e demais atributos), no popup do marcador e ao
  clicar no ônibus da lista. Usuário comum e visitante continuam vendo o resumo.
- `docs/tk915.md`: comandos SMS do rastreador TKSTAR TK915 (manual oficial 2G/4G, gist da comunidade com comentários
  e comandos do fornecedor, separados por fonte),
  passo a passo para apontá-lo a este servidor (protocolo `watch`, porta 5093), resposta de posição, luzes,
  comandos pelo servidor e problemas comuns.
- Front: tela de usuários para o administrador (`/settings/users`): lista, cadastro (nome, e-mail, senha,
  administrador) e exclusão; item "Usuários" no menu só para administrador. O cadastro envia `deviceLimit: -1`
  (padrão do autocadastro), senão o usuário criado não poderia cadastrar dispositivos.
- Front: no relatório, "Análise de Instante" e o mapa do trajeto viraram cards separados, em largura total.
  No celular, o mapa ficava com altura 0 (`h-full` com os cards empilhados) e não aparecia.
- Exportação da imagem Docker: `setup/docker-export.sh` gera a imagem com as tags `<versão>` (padrão
  `<build.gradle>-<commit>[-dirty]`) e `latest` (sempre a última), exporta para `dist/docker/` com checksum e
  inclui o compose `docker/compose/traccar-sse-image.yaml` (roda a partir da imagem, atrás do Caddy).
  `Dockerfile.sse` grava versão e commit em labels. Ver [imagem-docker.md](imagem-docker.md).
- Caddy: `TLS_MODE` no `Caddyfile` e nos composes, com `acme` (domínio, padrão), `ip` (IP público sem domínio,
  certificado Let's Encrypt `shortlived` de ~6 dias) e `internal` (IP privado, CA própria do Caddy).
- Protocolo Watch (GPS dos ônibus, TCP 5093): porta publicada nos composes e no `Dockerfile.sse`;
  `protocols.enable=watch,osmand` nas configs de produção e dev; simulador `tools/watch-device.py`. Na Cloudflare
  o Watch vai direto ao IP do servidor (TCP não passa pelo túnel). Ver [cloudflare.md](cloudflare.md) e
  [osmand-security.md](osmand-security.md#protocolo-watch).
- Publicação via Cloudflare Tunnel: `docker/compose/traccar-sse-cloudflare.yaml` (produção, sem portas
  publicadas) e perfil `tunnel` no compose de dev. Front: `VITE_ALLOWED_HOSTS`. `docker/compose/.env` (token) no
  `.gitignore`. Ver [cloudflare.md](cloudflare.md).
- Modo desenvolvedor via Docker (`docker/compose/traccar-sse-dev.yaml`): front no Vite com hot reload, backend
  compilado do código atual com depurador JDWP na 5005, H2 de desenvolvimento e portas configuráveis
  (`TRACCAR_PORT`, `OSMAND_PORT`, `DEBUG_PORT`, `FRONT_PORT`). `Dockerfile.sse` ganhou o `ARG EXTRA_MODULES`
  (a imagem de produção não muda). Ver [desenvolvimento.md](desenvolvimento.md).
- Validação das requisições às rotas públicas sem login (`PublicAccessGuard`): só a origem do próprio front
  (ou `public.allowedOrigins`/`web.origin`) e apps com chave (`public.apiKeys`); sem origem = `403`; limite por
  IP de requisições (`public.rateLimit`, padrão 600/min) e de streams (`public.maxStreams`, padrão 50).
  Usuários logados não são afetados. Ver [public-access.md](public-access.md#validação-das-requisições).
- Front: parar de seguir um ônibus no mapa. Antes, depois de clicar num ônibus o mapa o seguia para sempre. Agora
  arrastar o mapa, tocar no ✕ do aviso "Seguindo NOME" ou clicar de novo no ônibus para de seguir.
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
- Caddy: `ERR_SSL_PROTOCOL_ERROR` ao abrir `https://<IP>` com `TLS_MODE=ip`/`internal` no Docker. Sem SNI (que
  navegadores não enviam para IP), o Caddy escolhia o certificado pelo IP do container. Acrescentado
  `default_sni {$SERVER_NAME}` no `Caddyfile`.
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
