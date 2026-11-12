# Exportar a imagem Docker e rodar por compose (atrás do Caddy)

Gera a imagem na máquina de build e leva para o servidor como **arquivo**, sem registry e sem compilar no
servidor. No servidor, o Traccar roda atrás do **Caddy**, que cuida do HTTPS.

| Arquivo | Papel |
|---|---|
| `setup/docker-export.sh` | Gera a imagem, aplica as tags `<versão>` e `latest` e exporta para `dist/docker/` |
| `docker/compose/traccar-sse-image.yaml` | Compose do servidor: Traccar (a partir da imagem carregada, sem `build`) + Caddy |
| `docker/caddy/Caddyfile` | Proxy e HTTPS. Atende domínio ou **IP sem domínio** (`TLS_MODE`) |

## Tags

Cada exportação aplica **duas tags** na mesma imagem:

| Tag | Significado |
|---|---|
| `<versão>` | Fixa. Ex.: `6.16.0-4b0a5d2` |
| `latest` | **Sempre a última gerada.** Cada nova exportação move a `latest` para a imagem nova |

A versão padrão é `<versão do build.gradle>-<commit>`, com `-dirty` quando há alterações não commitadas (o
`6.16.0` do `build.gradle` é a versão do Traccar oficial e não muda a cada build da fork). Para uma versão
própria: `VERSION=1.2.0 setup/docker-export.sh`.

A versão e o commit ficam gravados na imagem:

```bash
docker inspect traccar-sse:latest --format '{{index .Config.Labels "org.opencontainers.image.version"}} {{index .Config.Labels "org.opencontainers.image.revision"}}'
```

## Na máquina de build

Requisitos: Docker rodando e o submódulo do front baixado (`git submodule update --init traccar-web`).

```bash
setup/docker-export.sh
```

Gera `dist/docker/`, com a mesma estrutura do repositório (o compose usa `../caddy/Caddyfile`):

```
dist/docker/
├── images/
│   ├── traccar-sse-<versão>.tar.gz (+ .sha256)   imagem com as duas tags
│   └── traccar-sse-latest.tar.gz   (+ .sha256)   substituído a cada exportação (hard link, sem espaço extra)
├── compose/
│   ├── traccar-sse-image.yaml                     compose do servidor
│   ├── traccar-sse.xml                            configuração do Traccar
│   └── .env.example                               SERVER_NAME, ACME_EMAIL, TLS_MODE, TRACCAR_SSE_TAG
└── caddy/
    └── Caddyfile
```

Opções (variáveis de ambiente):

| Variável | Padrão | Uso |
|---|---|---|
| `VERSION` | `<build.gradle>-<commit>[-dirty]` | Tag da versão. `latest` não é aceito, porque é adicionada sempre |
| `IMAGE` | `traccar-sse` | Nome da imagem |
| `OUTPUT_DIR` | `dist/docker` | Pasta de saída |
| `PLATFORM` | arquitetura desta máquina | `linux/arm64` para servidor ARM |
| `SKIP_BUILD` | `false` | `true` exporta a `traccar-sse:latest` já existente, sem compilar de novo |

> A imagem precisa ser da **mesma arquitetura do servidor**. Gerada num PC x86 e levada para um servidor ARM,
> ela não roda: use `PLATFORM=linux/arm64`.

## No servidor

1. Copie a pasta inteira:

   ```bash
   scp -r dist/docker usuario@servidor:~/traccar
   ```

2. Confira e carregue a imagem:

   ```bash
   cd ~/traccar
   (cd images && sha256sum -c traccar-sse-latest.tar.gz.sha256)
   docker load -i images/traccar-sse-latest.tar.gz      # "Loaded image: traccar-sse:<versão>" e ":latest"
   ```

3. Configure:
   - `compose/.env` (a partir de `compose/.env.example`): `SERVER_NAME`, `ACME_EMAIL` e `TLS_MODE`. Ver
     [Certificado sem domínio](#certificado-sem-domínio-só-ip) se ainda não houver domínio.
   - `compose/traccar-sse.xml`: `web.url` com o mesmo endereço do `SERVER_NAME` (ex.: `https://200.10.20.30`).

4. Suba:

   ```bash
   docker compose -f compose/traccar-sse-image.yaml up -d
   docker compose -f compose/traccar-sse-image.yaml logs -f caddy     # acompanhe a emissão do certificado
   ```

Portas abertas no servidor:

| Porta | Para quê |
|---|---|
| 80/TCP | Validação do certificado e redirecionamento para HTTPS (Caddy) |
| 443/TCP e 443/UDP | Site, API, SSE e WebSocket (Caddy; UDP = HTTP/3, opcional) |
| 5093/TCP | Protocolo Watch dos GPS (direto no Traccar, não passa pelo Caddy) |

O Traccar fica em `127.0.0.1:8082` só para checagens locais. O acesso público é pelo Caddy.

## Certificado sem domínio (só IP)

O `TLS_MODE` escolhe como o Caddy obtém o certificado:

| `TLS_MODE` | Quando usar | Certificado | Navegador |
|---|---|---|---|
| `acme` (padrão) | Já há domínio apontando para o servidor | Let's Encrypt/ZeroSSL, 90 dias | Confia |
| `ip` | **IP público**, sem domínio | Let's Encrypt, perfil `shortlived`, **~6 dias** | Confia |
| `internal` | IP privado (rede interna), ou portas 80/443 inacessíveis pela internet | CA própria do Caddy | **Avisa** ("conexão não é privada") |

### IP público: `TLS_MODE=ip`

```bash
# compose/.env
SERVER_NAME=200.10.20.30
ACME_EMAIL=voce@ufpa.br
TLS_MODE=ip
```

- O Let's Encrypt só emite certificado para IP no perfil **`shortlived`**, com validade de **~6 dias (160 h)**.
  O Caddy renova sozinho bem antes de vencer; o volume `caddy-data` precisa ser mantido.
- A validação é feita pela internet nas portas **80 (HTTP-01) ou 443 (TLS-ALPN-01)**: elas precisam estar
  abertas e chegando a este servidor. A validação por DNS não serve para IP.
- O Caddyfile usa o Let's Encrypt explicitamente (`issuer acme { profile shortlived }`): com um perfil definido,
  todas as CAs configuradas precisam suportá-lo, então não há fallback para o ZeroSSL.
- A seleção de perfil no Caddy está marcada como **experimental** na documentação dele.
- Quando o domínio chegar: `SERVER_NAME=onibus.seudominio`, `TLS_MODE=acme`, ajuste o `web.url` e rode
  `docker compose -f compose/traccar-sse-image.yaml up -d`.

Se o certificado não sair (o log do Caddy mostra erro de ACME), as causas comuns são a porta 80/443 fechada no
firewall ou no roteador do campus, ou o IP configurado não ser o IP público da máquina.

#### `ERR_SSL_PROTOCOL_ERROR` ao abrir `https://<IP>`

A porta 443 responde, mas a negociação TLS falha. Para IP, o navegador não envia o nome do servidor (SNI), porque
o TLS proíbe IP no SNI. Sem SNI, o Caddy escolhe o certificado pelo IP local da conexão, e dentro do Docker esse IP
é o do container (`172.x`): nenhum certificado combina e a conexão é recusada. O `Caddyfile` resolve isso com
`default_sni {$SERVER_NAME}`. Se a sua cópia no servidor é anterior a essa correção, acrescente no bloco global:

```
{
	email {$ACME_EMAIL}
	default_sni {$SERVER_NAME}
}
```

E reinicie o Caddy: `docker compose -f compose/traccar-sse-image.yaml restart caddy`.

Se continuar, verifique:

```bash
docker compose -f compose/traccar-sse-image.yaml ps                  # traccar e caddy "running"
docker compose -f compose/traccar-sse-image.yaml exec caddy printenv SERVER_NAME TLS_MODE
docker compose -f compose/traccar-sse-image.yaml logs caddy --tail 80  # "certificate obtained successfully" ou o erro de ACME
echo | openssl s_client -connect <IP>:443 2>&1 | head -20             # de outra máquina: qual certificado (ou erro) a 443 entrega
```

- `SERVER_NAME` precisa ser **exatamente** o IP que você digita no navegador.
- Enquanto o Caddy ainda está obtendo o certificado (primeiro minuto), a mesma mensagem pode aparecer. Espere o
  `certificate obtained successfully` no log.
- Se o log mostrar erro de ACME/perfil, use `TLS_MODE=internal` para confirmar que o resto funciona (o navegador
  avisa, mas abre) e me mande o trecho do log.

### IP privado ou sem acesso pela internet: `TLS_MODE=internal`

Nenhuma CA pública valida um IP que a internet não alcança. O Caddy usa a própria CA: o HTTPS funciona, mas o
navegador avisa que a conexão não é confiável. Para tirar o aviso numa máquina, importe nela a CA raiz do Caddy:

```bash
docker compose -f compose/traccar-sse-image.yaml cp caddy:/data/caddy/pki/authorities/local/root.crt ./caddy-root.crt
```

E instale `caddy-root.crt` como autoridade confiável no sistema ou no navegador. Serve para testes e uso interno,
não para o público geral.

### Validar o Caddyfile antes de subir

Com a imagem oficial do Caddy, sem iniciar nada (troque os valores):

```bash
docker run --rm -e SERVER_NAME=200.10.20.30 -e ACME_EMAIL=voce@ufpa.br -e TLS_MODE=ip \
  -v "$PWD/caddy/Caddyfile:/etc/caddy/Caddyfile:ro" caddy:2-alpine caddy adapt --config /etc/caddy/Caddyfile --pretty
```

No modo `ip`, a saída deve conter `"profile": "shortlived"` e o diretório do Let's Encrypt.

## Atualizar

Na máquina de build, `setup/docker-export.sh`. No servidor, copie `images/traccar-sse-latest.tar.gz` e:

```bash
docker load -i images/traccar-sse-latest.tar.gz
docker compose -f compose/traccar-sse-image.yaml up -d    # recria o Traccar com a nova latest
```

## Fixar ou voltar uma versão

Em `compose/.env`:

```bash
TRACCAR_SSE_TAG=6.16.0-4b0a5d2
```

E `docker compose -f compose/traccar-sse-image.yaml up -d`. A versão precisa ter sido carregada antes
(`docker load` do arquivo dela). Para ver as que estão no servidor: `docker images traccar-sse`. Para apagar uma
antiga: `docker image rm traccar-sse:<versão>`.

## Validação (2026-11-11)

O Docker não estava rodando na máquina onde isto foi escrito, e o download do binário do Caddy foi bloqueado
pela política do ambiente. Por isso, o build, o `docker save`/`docker load`, o Caddy e a emissão do certificado
**ainda não foram executados**. Rode o comando de [validação do Caddyfile](#validar-o-caddyfile-antes-de-subir)
antes da primeira subida.

| Item | Como | Resultado |
|---|---|---|
| Perfil `shortlived` disponível | Diretório ACME de produção do Let's Encrypt (`meta.profiles`) | Listado, ao lado de `classic` e `tlsserver` |
| Regras do certificado por IP | Documentação do Let's Encrypt (perfis e tipos de desafio) e do Caddy (`tls`, automatic HTTPS) | `shortlived` = único perfil com IP, ~6 dias; HTTP-01/TLS-ALPN-01 sim, DNS-01 não; Caddy usa CA interna para IP por padrão |
| Script de exportação | `docker` simulado | Comandos de build/tag/save corretos; estrutura `images/`, `compose/`, `caddy/`; `latest` substituída; checksums ok |
| Caminhos relativos do compose na pasta exportada | Resolvidos a partir de `compose/` | `./traccar-sse.xml` e `../caddy/Caddyfile` existem |
| YAML dos composes com Caddy | Parser `yaml` | Válidos, com os volumes do Caddy declarados |
