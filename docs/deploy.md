# Deploy em produção (Caddy ou nginx, host ou Docker)

O Traccar serve o front (`web/`), a API, os streams SSE e o WebSocket numa porta só (`8082`). Em produção,
essa porta fica fechada para fora, e um proxy reverso na frente cuida do HTTPS, do HTTP/2 e dos ajustes que o SSE
exige. **Recomendado: Caddy em Docker**, que emite e renova o certificado sozinho. O nginx continua documentado
para quem já tem um nginx no servidor.

```
navegador / app ──HTTPS:443──▶ Caddy/nginx ──HTTP──▶ Traccar :8082 ─┬─ /            front (web/)
                                       │                      ├─ /api/*       REST
                                       │                      ├─ /api/sse, /api/public/stream  SSE
                                       │                      ├─ /api/socket  WebSocket
                                       │                      └─ POST /       OsmAnd via HTTPS
rastreador OsmAnd ──:5055 (opcional, ver osmand-security.md)──▶ Traccar :5055
GPS dos ônibus (Watch) ──TCP :5093──────────────────────────────▶ Traccar :5093
```

Arquivos:

| Arquivo | Uso |
|---|---|
| `docker/caddy/Caddyfile` | Configuração do Caddy (HTTPS automático) |
| `docker/compose/traccar-sse-caddy.yaml` | Compose de produção com Caddy: Traccar + Caddy, certificado automático |
| `docker/nginx/traccar.conf.template` | Configuração do nginx, a mesma para nginx no host ou em container |
| `docker/Dockerfile.sse` | Imagem da fork: compila o front e o backend e monta o Java com `jlink` |
| `docker/compose/traccar-sse.yaml` | Compose de produção com nginx. O nginx entra com `--profile nginx` |
| `docker/compose/traccar-sse.xml` | `traccar.xml` de produção usado pelos dois compose |
| `.dockerignore` | Limita o contexto do build ao que a imagem precisa |
| `setup/build-dist.sh` | Pacote `.tar.gz` com Java embutido, para rodar no host sem Docker (`setup/build-dist.sh --help`) |

> Publicar pela Cloudflare (sem abrir portas, HTTPS da Cloudflare): ver [cloudflare.md](cloudflare.md).

## Escolha do cenário

| Cenário | Traccar | Proxy | Quando usar |
|---|---|---|---|
| **Caddy (recomendado)** | Docker | Caddy (Docker) | Padrão. Certificado emitido e renovado sozinho, um comando para subir |
| A | Host (pacote + systemd) | nginx no host | Servidor dedicado, sem Docker |
| B | Docker | nginx no host | Já existe um nginx no servidor atendendo outros sites |
| C | Docker | nginx (Docker) | Quem prefere nginx em container. Certificado com certbot |

Nos cenários com nginx, ele usa sempre o mesmo template. Só muda o endereço do Traccar (`TRACCAR_UPSTREAM`): `127.0.0.1:8082`
quando o nginx está no host e `traccar:8082` quando está no compose.

## Recomendado: Caddy com certificado automático

O Caddy emite o certificado (Let's Encrypt, com ZeroSSL de reserva) no primeiro acesso e renova sozinho cerca de
30 dias antes do vencimento. Não precisa de certbot, cron nem hooks. SSE e WebSocket passam sem configuração
especial: o Caddy envia `text/event-stream` na hora e faz o upgrade de WebSocket por conta própria.

O acme-companion também automatiza o certificado, mas exige três peças (nginx-proxy, acme-companion e o
`docker.sock` montado) e os ajustes de SSE em arquivos `vhost.d` separados. O Caddy faz o mesmo com um container e
um `Caddyfile` de 20 linhas.

**Pré-requisitos**

- DNS (registro A/AAAA) do domínio apontando para o servidor **antes** de subir. Sem isso, a emissão falha.
- Portas 80 e 443/TCP abertas para a internet (o Let's Encrypt valida por elas). A 443/UDP é opcional, para HTTP/3.
- Nada mais escutando nas portas 80 e 443 do servidor.

**Subir**

```bash
git submodule update --init traccar-web

cat > docker/compose/.env <<'EOF'
SERVER_NAME=onibus.exemplo.ufpa.br
ACME_EMAIL=ti@exemplo.ufpa.br
EOF

# ajuste web.url em docker/compose/traccar-sse.xml para https://<SERVER_NAME>
docker compose -f docker/compose/traccar-sse-caddy.yaml up -d --build
docker compose -f docker/compose/traccar-sse-caddy.yaml logs -f caddy
```

No log, procure `certificate obtained successfully`. A partir daí `https://<SERVER_NAME>` já responde, e o HTTP
redireciona para HTTPS (308).

**O que o `Caddyfile` faz**

| Trecho | Motivo |
|---|---|
| `{$SERVER_NAME} { ... }` | HTTPS automático: certificado, renovação, redirecionamento de HTTP, HTTP/2 e HTTP/3 |
| `email {$ACME_EMAIL}` | Conta ACME. O Let's Encrypt avisa por e-mail se a renovação falhar |
| `reverse_proxy traccar:8082` | Front, API, SSE, WebSocket e OsmAnd via HTTPS (`POST /`) |
| Sem `encode` | O Traccar já comprime. Comprimir o SSE no proxy atrasaria os eventos |
| `header_up -CF-Connecting-IP` e `header_up X-Real-IP {remote_host}` | O Traccar lê esses headers antes do `X-Forwarded-For`. Sem isso, o cliente falsificaria o próprio IP |
| `header_down Set-Cookie ... Secure` | O Traccar não marca o cookie de sessão como `Secure` atrás de proxy TLS |
| `request_body max_size 10MB` | Upload de imagens de dispositivo |

**Renovação e volumes**

- Os certificados e a conta ACME ficam no volume `traccar-sse_caddy-data`. **Não apague esse volume.** Sem ele, o
  Caddy pede um certificado novo a cada recriação e pode bater no limite do Let's Encrypt (5 certificados iguais
  por semana).
- A renovação roda dentro do Caddy, sem nada agendado no host. Para conferir as datas:
  `echo | openssl s_client -connect <SERVER_NAME>:443 -servername <SERVER_NAME> 2>/dev/null | openssl x509 -noout -dates`.
- Para testar sem gastar o limite, adicione temporariamente no bloco global do `Caddyfile`:
  `acme_ca https://acme-staging-v02.api.letsencrypt.org/directory`. O certificado de staging não é confiável no
  navegador. Remova a linha e apague o volume `caddy-data` antes de ir para produção.
- Depois de editar o `Caddyfile`, rode
  `docker compose -f docker/compose/traccar-sse-caddy.yaml up -d --force-recreate caddy`.

**Caddy no host, em vez de container**

Instale o pacote `caddy` da distribuição e use o mesmo `Caddyfile` em `/etc/caddy/Caddyfile`, trocando
`traccar:8082` por `127.0.0.1:8082` e os `{$SERVER_NAME}`/`{$ACME_EMAIL}` pelos valores reais. O Traccar fica
no host (cenário A) ou no compose do nginx sem o profile (`traccar-sse.yaml`, que publica a 8082 só em
`127.0.0.1`).

O `traccar-sse-caddy.yaml` e o `traccar-sse.yaml` usam o mesmo nome de projeto e os mesmos volumes do Traccar.
Dá para trocar de nginx para Caddy sem perder o banco, mas não rode os dois ao mesmo tempo.

## Frontend

**Padrão (recomendado): o Traccar serve o front.** O `npm run build` do `traccar-web` vira a pasta `web/` do
pacote ou da imagem, e o nginx só repassa tudo. Front e API ficam no mesmo domínio, então:

- o cookie de sessão (`JSESSIONID`) funciona sem CORS nem `SameSite=None`;
- o `EventSource` do mapa envia o cookie para `/api/sse`;
- o Traccar já faz o fallback de SPA (rotas do vue-router devolvem `index.html`);
- o `VITE_API_URL` fica no padrão `/api`, sem rebuild por ambiente.

**Alternativa: o nginx serve os arquivos estáticos.** Útil para cache agressivo de `assets/` ou para trocar o
front sem reiniciar o Traccar. Copie `traccar-web/dist` para o servidor e troque o `location /` do template por:

```nginx
root /var/www/traccar-web;

location /assets/ {
    expires 1y;
    add_header Cache-Control "public, immutable";
}

location /api/ {
    proxy_pass http://traccar;
}

location / {
    try_files $uri $uri/ /index.html;
}
```

Os `location` de SSE e WebSocket do template continuam iguais. Nesse modo, o `POST /` do OsmAnd via HTTPS para de
chegar ao Traccar. Os rastreadores precisam usar a porta 5055.

**Domínio separado para o front** (ex.: front na Vercel, API em outro domínio): não recomendado. Exige
`VITE_API_URL` absoluto no build, `web.origin` com o domínio do front e `web.sameSiteCookie=None`. Mesmo assim,
navegadores que bloqueiam cookies de terceiros (Safari, Firefox estrito) quebram o login e o `/api/sse`.

## Configuração do Traccar

Entradas de `traccar.xml` relevantes atrás do proxy. O compose usa `docker/compose/traccar-sse.xml`.

| Chave | Valor | Por quê |
|---|---|---|
| `web.url` | `https://onibus.exemplo.ufpa.br` (sem `/` no fim) | Links em notificações e relatórios, OpenID |
| `web.address` | `127.0.0.1` | Só no cenário A: a porta 8082 escuta apenas localmente. No Docker, quem limita é o `ports` do compose |
| `public.enable`, `public.features` | `true`, `devices,positions,stream` | Mapa público para visitantes (ver [public-access.md](public-access.md)) |
| `public.allowedOrigins`, `public.apiKeys`, `public.rateLimit`, `public.maxStreams` | comentadas (padrões) | Rotas públicas só aceitam a origem do próprio front, com limite por IP. Ver [public-access.md](public-access.md#validação-das-requisições) |
| `database.registerUnknown` | `false` | Não cadastrar automaticamente rastreadores desconhecidos |
| `database.*` | H2 (padrão) ou MySQL/PostgreSQL | O H2 em arquivo serve para poucos ônibus. Para volume maior, use MySQL ou PostgreSQL (exemplo em `docker/compose/traccar-mysql.yaml`) |

`web.origin` e `web.sameSiteCookie` não são necessários quando front e API estão no mesmo domínio.

## nginx: o que o template faz

| Trecho | Motivo |
|---|---|
| `listen 443 ssl http2` | HTTP/2 elimina o limite de 6 conexões SSE por domínio do HTTP/1.1 (ver [sse.md](sse.md)) |
| `location ~ ^/api/(sse\|public/stream)$` com `proxy_buffering off`, `gzip off` | Sem isso o nginx segura os eventos no buffer e o mapa só atualiza em blocos |
| `proxy_read_timeout 1h` no SSE | O Traccar manda keepalive a cada 55 s. O padrão do nginx (60 s) fica no limite e derruba conexões |
| `location = /api/socket` com `Upgrade`/`Connection` | WebSocket do Traccar (apps oficiais) |
| `X-Real-IP`, `X-Forwarded-For` | O Traccar registra o IP real do cliente nos logs |
| `CF-Connecting-IP ""` | O Traccar lê esse header antes dos outros. Sem limpar, qualquer cliente falsificaria o próprio IP |
| `client_max_body_size 10m` | Upload de imagens de dispositivo |
| `proxy_cookie_flags JSESSIONID secure` | O Traccar não marca o cookie de sessão como `Secure` atrás de proxy TLS. O nginx marca |
| Porta 80 | Redireciona para HTTPS e atende o desafio do Let's Encrypt em `/.well-known/acme-challenge/` |

O template usa `listen ... http2`, que funciona do nginx 1.22 (Debian 12) em diante. No 1.25.1+ o nginx avisa
que a forma nova é `http2 on;`, mas aceita.

## Cenário A: tudo no host

1. Gere o pacote com o front:

```bash
git submodule update --init traccar-web
(cd traccar-web && npm ci && npm run build)
setup/build-dist.sh linux-x64
```

2. Instale em `/opt/traccar` como serviço do systemd. O pacote já traz o `traccar.service`:

```bash
sudo mkdir -p /opt/traccar
sudo tar -xzf dist/traccar-<versão>-linux-x64.tar.gz -C /opt/traccar --strip-components=1
sudo cp /opt/traccar/traccar.service /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl enable --now traccar
```

   Em `/opt/traccar/conf/traccar.xml`, adicione as chaves da seção anterior, inclusive `web.address=127.0.0.1`,
   e rode `sudo systemctl restart traccar`.

3. Instale o nginx e o certificado (seção [Certificado TLS](#certificado-tls)), depois gere a configuração:

```bash
sudo apt install nginx gettext-base
SERVER_NAME=onibus.exemplo.ufpa.br TRACCAR_UPSTREAM=127.0.0.1:8082 \
  envsubst '${SERVER_NAME} ${TRACCAR_UPSTREAM}' < docker/nginx/traccar.conf.template \
  | sudo tee /etc/nginx/sites-available/traccar
sudo ln -s /etc/nginx/sites-available/traccar /etc/nginx/sites-enabled/traccar
sudo rm -f /etc/nginx/sites-enabled/default
sudo nginx -t && sudo systemctl reload nginx
```

A lista `'${SERVER_NAME} ${TRACCAR_UPSTREAM}'` no `envsubst` é obrigatória. Sem ela, ele apaga as variáveis do
próprio nginx (`$host`, `$remote_addr`...).

## Cenário B: Traccar no Docker, nginx no host

1. Ajuste `docker/compose/traccar-sse.xml` (no mínimo `web.url`).
2. Suba o Traccar:

```bash
git submodule update --init traccar-web
docker compose -f docker/compose/traccar-sse.yaml up -d --build
```

   O compose publica a 8082 só em `127.0.0.1`, então ela não fica exposta.

3. Configure o nginx do host como no cenário A, passo 3, com `TRACCAR_UPSTREAM=127.0.0.1:8082`.

## Cenário C: tudo no Docker

1. Ajuste `docker/compose/traccar-sse.xml` e crie `docker/compose/.env`:

```bash
echo 'SERVER_NAME=onibus.exemplo.ufpa.br' > docker/compose/.env
```

2. Emita o certificado no host (seção [Certificado TLS](#certificado-tls)). O container monta
   `/etc/letsencrypt` e `/var/www/certbot` do host.

3. Suba tudo:

```bash
docker compose -f docker/compose/traccar-sse.yaml --profile nginx up -d --build
```

   A imagem `nginx:stable-alpine` gera `/etc/nginx/conf.d/default.conf` a partir do template ao iniciar, com
   `TRACCAR_UPSTREAM=traccar:8082`. Sem `SERVER_NAME`, o nginx falha procurando `/etc/letsencrypt/live//`.

   Depois de editar o template, recrie o container (`up -d --force-recreate nginx`). Um `restart` não basta: o
   arquivo é montado direto e o editor troca o arquivo no disco.

## Certificado TLS (só nginx)

Com o Caddy, nada desta seção é necessário. No nginx, o template espera o certificado em `/etc/letsencrypt/live/<SERVER_NAME>/`. O nginx não sobe sem ele, então emita
antes de ativar a configuração. O jeito mais simples é o `certbot` no modo `standalone`, parando o nginx só
durante a emissão e cada renovação (alguns segundos a cada ~60 dias):

```bash
sudo apt install certbot

# nginx no host (cenários A e B)
sudo certbot certonly --standalone -d onibus.exemplo.ufpa.br \
  --pre-hook "systemctl stop nginx" --post-hook "systemctl start nginx"

# nginx no compose (cenário C)
sudo certbot certonly --standalone -d onibus.exemplo.ufpa.br \
  --pre-hook "docker compose -f /caminho/do/repo/docker/compose/traccar-sse.yaml --profile nginx stop nginx" \
  --post-hook "docker compose -f /caminho/do/repo/docker/compose/traccar-sse.yaml --profile nginx start nginx"
```

Os hooks ficam gravados e o timer do certbot renova sozinho. Teste com `sudo certbot renew --dry-run`. Para
renovar sem parar o nginx, emita com `--webroot -w /var/www/certbot`: o template já atende esse caminho na porta 80.

## Firewall

| Porta | Aberta para fora? |
|---|---|
| 80, 443/TCP | Sim (Caddy ou nginx) |
| 443/UDP | Opcional, HTTP/3 do Caddy |
| 8082 | Não. Só `127.0.0.1` ou a rede do compose |
| 5093/TCP | **Sim**: protocolo Watch dos GPS dos ônibus (TCP puro, não passa por Caddy/nginx/Cloudflare) |
| 5055 | Só se os rastreadores enviarem direto. Com OsmAnd via HTTPS (`POST https://dominio/`), feche |
| 5000–5500 (outros protocolos) | Só as portas dos protocolos usados, liberadas no compose (`ports`) e no firewall |

Os protocolos binários (GT06, Teltonika etc.) são TCP/UDP puros e não passam pelo nginx HTTP.

## Verificação

```bash
D=https://onibus.exemplo.ufpa.br

curl -sI $D/ | head -1                           # 200, front
curl -sI --http2 $D/ | head -1                   # HTTP/2 200
curl -sI http://onibus.exemplo.ufpa.br/ | head -1 # 301 para https
curl -N $D/api/public/stream                     # eventos de ônibus públicos chegando um a um
curl -N -u email:senha $D/api/sse                # stream autenticado
curl -X POST "$D/?id=<uniqueId>&lat=-1.47&lon=-48.45"  # OsmAnd via HTTPS, deve responder 200
```

No navegador, a aba Network deve mostrar `/api/public/stream` (ou `/api/sse`) com tipo `eventsource`, protocolo
`h2` e eventos chegando na aba **EventStream** sem atraso.

### Testado (2026-10-30)

Caddy local (`traccar-sse-caddy.yaml` com a imagem `docker/Dockerfile.sse` e `caddy:2-alpine`, `SERVER_NAME=localhost`, que usa a CA interna do Caddy em vez do Let's Encrypt):

| Teste | Resultado |
|---|---|
| Front em `/` e rota do SPA | 200, `text/html`, HTTP/2 |
| Porta 80 | 308 para HTTPS |
| Primeiro usuário, dispositivo público, OsmAnd via HTTPS | 200, posição chegou em `/api/public/stream` e `/api/sse` |
| Atraso do evento SSE | 3,09 s, o mesmo de quando o cliente conecta direto no Traccar (o atraso vem do servidor, não do proxy) |
| Conexão SSE aberta por 130 s | Continuou aberta, com 2 keepalives |
| `/api/socket` com cookie de sessão | 101 Switching Protocols |
| Cookie de sessão | `HttpOnly; Secure` |
| Login com `CF-Connecting-IP`, `X-Real-IP` e `X-Forwarded-For` falsos | O Traccar registrou o IP real da conexão |

A emissão real pelo Let's Encrypt não foi testada, porque exige um domínio público apontando para a máquina.

nginx, cenário C local (imagem `docker/Dockerfile.sse` + `nginx:stable-alpine` 1.30, certificado autoassinado):

| Teste | Resultado |
|---|---|
| Front em `/` e rota do SPA (`/settings/devices`) | 200, `text/html`, HTTP/2 |
| Porta 80 | 301 para HTTPS |
| Primeiro usuário + dispositivo público pela API via nginx | 200 |
| OsmAnd via HTTPS (`POST /`) | 200, posição chegou nos dois streams |
| `/api/public/stream` e `/api/sse` com `Accept-Encoding: gzip` | Eventos chegam no mesmo instante que direto no Traccar (o nginx não adiciona atraso) |
| Conexão SSE aberta por 130 s | Continuou aberta, com 2 keepalives |
| `/api/socket` com cookie de sessão | 101 Switching Protocols |
| Cookie de sessão | `HttpOnly; Secure` |
| Login com `CF-Connecting-IP`, `X-Real-IP` e `X-Forwarded-For` falsos | O Traccar registrou o IP real da conexão |
| Config gerada com `envsubst` para nginx no host | `nginx -t` ok |

Os cenários A e B usam o mesmo template e a mesma imagem/pacote, mas não foram executados num servidor real.

## Atualização e backup

Docker (Caddy e cenários B e C):

```bash
git pull && git submodule update --init traccar-web
docker compose -f docker/compose/traccar-sse-caddy.yaml up -d --build          # Caddy
docker compose -f docker/compose/traccar-sse.yaml [--profile nginx] up -d --build   # nginx
```

O banco H2 e os logs ficam nos volumes `traccar-sse_traccar-data` e `traccar-sse_traccar-logs`. Backup do banco
(com o Traccar parado, para o H2 não ficar inconsistente):

```bash
docker compose -f docker/compose/traccar-sse.yaml stop traccar
docker run --rm -v traccar-sse_traccar-data:/data -v "$PWD":/backup alpine \
  tar czf /backup/traccar-data-$(date +%F).tgz -C /data .
docker compose -f docker/compose/traccar-sse.yaml start traccar
```

No host (cenário A), gere o pacote novo e extraia por cima, preservando a configuração. O banco fica em
`/opt/traccar/data`:

```bash
sudo systemctl stop traccar
sudo tar -czf ~/traccar-backup-$(date +%F).tgz -C /opt/traccar conf data
sudo tar -xzf dist/traccar-<versão>-linux-x64.tar.gz -C /opt/traccar --strip-components=1 \
  --exclude='*/conf/traccar.xml'
sudo systemctl start traccar
```

## Checklist de produção

- [ ] HTTPS ativo e porta 80 redirecionando
- [ ] Porta 8082 inacessível de fora (`curl http://<ip-publico>:8082` deve falhar)
- [ ] `web.url` com o domínio público
- [ ] Primeiro acesso feito: o primeiro usuário vira administrador. Não deixe o servidor novo exposto sem criar
      esse usuário
- [ ] Senha da conta que estava embutida no front antigo trocada (ver [frontend.md](frontend.md))
- [ ] Itens de [osmand-security.md](osmand-security.md#checklist-de-produção)
- [ ] Backup do banco agendado

## Problemas comuns

| Sintoma | Causa | Solução |
|---|---|---|
| Mapa só atualiza em blocos ou depois de muito tempo | Buffer ou gzip no SSE | Confira o `location` de SSE (`proxy_buffering off`, `gzip off`) e se não há outro proxy/CDN no caminho bufferizando |
| Stream cai a cada ~1 min | `proxy_read_timeout` padrão (60 s) | Use o template (1 h) |
| `502 Bad Gateway` | Traccar fora do ar ou `TRACCAR_UPSTREAM` errado | `docker compose logs traccar` / `journalctl -u traccar`. No host, o upstream é `127.0.0.1:8082` |
| nginx não sobe: `cannot load certificate` | Certificado ainda não emitido | Emita antes (seção [Certificado TLS](#certificado-tls)) |
| Variáveis `$host` vazias na config gerada | `envsubst` sem a lista de variáveis | Rode com `'${SERVER_NAME} ${TRACCAR_UPSTREAM}'` |
| Login funciona mas o mapa logado não recebe posições | Front em outro domínio, cookie não vai para `/api/sse` | Sirva front e API no mesmo domínio |
| Caddy não emite o certificado (`challenge failed`, `no such host`) | DNS ainda não aponta para o servidor, ou portas 80/443 fechadas no firewall | Confira `dig +short <SERVER_NAME>` e o firewall. O Caddy tenta de novo sozinho |
| Caddy: `too many certificates already issued` | Limite do Let's Encrypt, geralmente por apagar o volume `caddy-data` | Aguarde a janela de 7 dias. Use o staging nos testes |
| Mais de 6 abas e o mapa trava | HTTP/1.1 | Confirme `http2` no `listen 443` |
| `/api/socket` responde 200 em vez de 101 | WebSocket sem cookie de sessão (Basic auth não vale para o socket) | Faça login em `/api/session` antes. O front usa o SSE, não o socket |
| Aviso `listen ... http2 is deprecated` | nginx 1.25.1+ | Pode ignorar, ou troque por `listen 443 ssl;` + `http2 on;` se o nginx for 1.25.1+ |
