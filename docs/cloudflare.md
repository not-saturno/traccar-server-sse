# Publicar na Cloudflare

## Como funciona

A Cloudflare **não executa** o Traccar: ele é um servidor Java com banco de dados e conexões longas (SSE), e
Workers/Pages são para JavaScript e sites estáticos. O caminho é o **Cloudflare Tunnel**:

```
visitante ──HTTPS──▶ Cloudflare ──túnel (saída)──▶ cloudflared ──HTTP──▶ Traccar (sua máquina/VM/servidor)
```

- O Traccar roda numa máquina sua (VM, servidor do campus ou PC) com Docker.
- O `cloudflared` abre uma conexão **de saída** para a Cloudflare. Não é preciso abrir porta no firewall nem ter
  IP público.
- HTTPS, certificado e proteção ficam na Cloudflare. O túnel é gratuito.

Pré-requisitos: conta na Cloudflare e um **domínio gerenciado por ela** (os nameservers do domínio apontando
para a Cloudflare).

## Limites da Cloudflare que importam aqui

| Limite | Efeito | Situação neste projeto |
|---|---|---|
| **Quick Tunnels** (`trycloudflare.com`, sem conta) **não suportam SSE** ([doc](https://developers.cloudflare.com/cloudflare-one/networks/connectors/cloudflare-tunnel/do-more-with-tunnels/trycloudflare/)) | O mapa carrega, mas não atualiza em tempo real | Use sempre um **túnel nomeado**, inclusive no dev |
| Resposta sem dados por **125 s** fecha a conexão (erro 524) | Um stream SSE parado cairia | O Traccar manda keepalive a cada 55 s, então os streams não caem |
| Túnel só transporta **HTTP(S)** | Rastreadores com protocolo TCP próprio não passam | **Os GPS deste projeto usam o protocolo Watch (TCP 5093): vão direto ao IP do servidor**, fora do túnel (ver [Rastreadores Watch](#rastreadores-watch-tcp-5093)). O OsmAnd é HTTP e passa |

## Produção

Arquivo: `docker/compose/traccar-sse-cloudflare.yaml` (Traccar + `cloudflared`). Usa o mesmo projeto e os
mesmos volumes do `deploy.md` (`traccar-sse`), então o banco é aproveitado.

### 1. Criar o túnel

1. Painel da Cloudflare → **Zero Trust** → **Networks** → **Tunnels** → **Create a tunnel** →
   **Cloudflared**.
2. Dê um nome (ex.: `traccar-prod`).
3. Na tela de instalação, copie o **token**: o texto longo depois de `--token` no comando mostrado.
4. Em **Public hostnames**, adicione:

| Hostname | Service | Para quê |
|---|---|---|
| `onibus.seudominio.com` | `HTTP` → `traccar:8082` | Front, API, SSE e WebSocket |
| `gps.seudominio.com` (opcional) | `HTTP` → `traccar:5055` | Rastreadores OsmAnd por HTTPS (ver abaixo) |

`traccar` é o nome do serviço no compose: o `cloudflared` alcança o Traccar pela rede interna do Docker.

### 2. Configurar e subir

No `docker/compose/.env` (não versione esse arquivo, ele guarda o token):

```bash
TUNNEL_TOKEN=eyJhIjoi...
```

Em `docker/compose/traccar-sse.xml`, ajuste a URL pública:

```xml
<entry key='web.url'>https://onibus.seudominio.com</entry>
```

Suba:

```bash
docker compose -f docker/compose/traccar-sse-cloudflare.yaml up -d --build
docker compose -f docker/compose/traccar-sse-cloudflare.yaml logs -f cloudflared   # "Registered tunnel connection"
```

Abra `https://onibus.seudominio.com`. Num banco novo, o login leva ao cadastro do primeiro usuário
(administrador).

### Rastreadores Watch (TCP 5093)

Os GPS dos ônibus usam o protocolo **Watch**: TCP puro na porta **5093**, com a conexão aberta o tempo todo. O
servidor responde ao rastreador e pode enviar comandos a ele (SOS, intervalo de posição, reboot). Isso **não
passa pela Cloudflare**:

- O túnel transporta só HTTP, e o rastreador não roda `cloudflared`.
- O proxy da Cloudflare (nuvem laranja) só repassa portas HTTP. TCP em porta arbitrária exige o **Spectrum**,
  pago (na prática, plano Enterprise).

Então o site vai pelo túnel e os GPS vão **direto** ao servidor:

```
visitante ──HTTPS──▶ Cloudflare ──túnel──▶ cloudflared ──▶ Traccar :8082
GPS (Watch) ──TCP :5093───────────────────────────────────▶ Traccar :5093  (IP público do servidor)
```

O que é preciso:

1. **IP público fixo** na máquina do Traccar, ou redirecionamento da porta 5093 no roteador/firewall do campus.
   Sem IP público (NAT da operadora, por exemplo), use uma VM na nuvem.
2. **Porta 5093/TCP liberada** no firewall da máquina. O `traccar-sse-cloudflare.yaml` já publica a porta.
3. Opcional: um registro DNS `gps.seudominio.com` do tipo **A** apontando para o IP do servidor, com o proxy
   **desligado ("DNS only", nuvem cinza)**. Com o proxy ligado, a porta 5093 não funciona. Também dá para usar o
   IP direto no rastreador.
4. No rastreador, configure o servidor `gps.seudominio.com` (ou o IP) na porta `5093`. Em muitos relógios/GPS
   desse padrão isso é feito por SMS (ex.: `pw,123456,ip,<IP-ou-domínio>,5093#`), mas o comando e a senha
   variam por fabricante: confira o manual.
5. No Traccar, cadastre o dispositivo com o **identificador do rastreador** (normalmente 10 dígitos, derivado do
   IMEI). Ele aparece na primeira linha de cada mensagem (`[3G*9031853319*...]`). Também aparece no log do
   servidor como `Unknown device - <id>` quando o rastreador conecta antes de ser cadastrado.

O IP do servidor fica conhecido (pelo DNS ou pela configuração dos rastreadores), mas o site continua protegido:
a porta web **não** é publicada, e só a 5093 fica aberta. Sobre a segurança do protocolo, ver
[osmand-security.md](osmand-security.md#protocolo-watch).

### Rastreadores OsmAnd (opcional)

Duas opções, ambas por HTTPS e sem abrir portas:

- **Hostname próprio** (`gps.seudominio.com` → `traccar:5055`): configure o rastreador ou o app Traccar Client
  com `https://gps.seudominio.com`. Aceita `GET` e `POST`.
- **Mesmo domínio do front:** `https://onibus.seudominio.com`. O Traccar repassa `POST /` ao OsmAnd. Só
  funciona com `POST`.

Rastreador que só fala HTTP (sem TLS) precisa que **Always Use HTTPS** esteja desligado para o hostname dele.
Rastreador com protocolo TCP próprio não passa pelo túnel: descomente a porta no compose e libere-a no firewall
da máquina. Segurança do OsmAnd: [osmand-security.md](osmand-security.md).

### Ajustes no painel da Cloudflare

| Configuração | Recomendação | Por quê |
|---|---|---|
| **Bot Fight Mode** / desafios do WAF | Desligado, ou com regra para pular `/api/*` e o hostname do GPS | Desafios (captcha/JS) quebram chamadas da API, o SSE e os rastreadores |
| **Rocket Loader** | Desligado | Reescreve os scripts da página e pode quebrar o front (Vue) |
| Cache | Padrão (sem "Cache Everything") | O padrão não guarda HTML nem API. Os arquivos do front têm hash no nome e podem ser cacheados |
| SSL/TLS | Padrão | O túnel já é criptografado. O Traccar recebe HTTP dentro da máquina |

### Segurança

- O compose de produção **não publica a porta web** no host. Só o túnel chega no Traccar, então ninguém consegue
  forjar o `CF-Connecting-IP` (IP real do visitante) conectando direto.
- A validação das rotas públicas funciona atrás da Cloudflare sem configuração: o `cloudflared` preserva o
  `Host`, e o limite por IP usa o `CF-Connecting-IP` (ver [public-access.md](public-access.md#validação-das-requisições)).
- Se o Traccar também estiver exposto de outra forma (porta aberta, outro proxy), remova ou sobrescreva o
  `CF-Connecting-IP` nesse caminho, como o Caddy do [deploy.md](deploy.md) já faz.

### Sem Docker

Com o Traccar instalado direto na máquina (porta 8082):

```bash
sudo cloudflared service install <TOKEN>
```

E, no painel, aponte o hostname para `http://localhost:8082`.

## Desenvolvimento (abrir o dev para outras pessoas ou para o celular)

Use um **túnel nomeado separado** (ex.: `traccar-dev`) e um hostname de dev, como
`dev-onibus.seudominio.com` → `HTTP` → `front:5173`. Quick Tunnels não servem, porque não têm SSE.

```bash
TUNNEL_TOKEN=<token do túnel de dev> VITE_ALLOWED_HOSTS=dev-onibus.seudominio.com \
  docker compose -f docker/compose/traccar-sse-dev.yaml --profile tunnel up --build
```

- `--profile tunnel` liga o serviço `tunnel` (`cloudflared`) do compose de dev. Sem o perfil, o dev local funciona
  como antes ([desenvolvimento.md](desenvolvimento.md)).
- `VITE_ALLOWED_HOSTS` é obrigatório: sem ele o Vite responde **"Blocked request"** para o hostname do túnel.
- Quem acessa pelo túnel usa o front do Vite, com o backend atrás do proxy dele. As rotas públicas aceitam a
  origem automaticamente.
- O **hot reload** é garantido em `localhost:5173`. Pelo túnel, a página pode não recarregar sozinha (o canal
  do HMR fica na porta do dev server); recarregue manualmente.
- **Cuidado:** o dev fica acessível na internet, com banco de teste e depurador. A porta do depurador não passa
  pelo túnel (só `front:5173`), mas não publique dados reais. Desligue o túnel quando não estiver usando.

Se `FRONT_PORT` for diferente de 5173, aponte o hostname para `front:<FRONT_PORT>`.

## Validação (2026-11-11)

O Docker Desktop estava desligado e não há conta Cloudflare configurada nesta máquina, então os túneis
**ainda não foram executados de ponta a ponta**. Foi validado:

| Item | Resultado |
|---|---|
| Limites da Cloudflare | Conferidos na documentação oficial: Quick Tunnels sem SSE; timeout de 125 s (erro 524) |
| YAML de `traccar-sse-cloudflare.yaml` e do perfil `tunnel` | Válidos; produção sem nenhuma porta publicada |
| `VITE_ALLOWED_HOSTS` | Sem ele, o host do túnel recebe `403`; com ele, `200`; outros hosts continuam bloqueados |
| Backend com os cabeçalhos do `cloudflared` (`Host`, `CF-Connecting-IP`, `X-Forwarded-Proto`) | Front no domínio: `200`; outro site ou sem origem: `403`; limite por IP real: `429` só para o IP que excedeu; OsmAnd via `POST /`: chega ao decoder |
