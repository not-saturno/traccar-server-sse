# Desenvolvimento

Duas formas de rodar o projeto para desenvolver: **Docker** (nada para instalar além do Docker) ou **direto no
host** (JDK 21 + Node 22).

## Docker (modo desenvolvedor)

Arquivos: `docker/compose/traccar-sse-dev.yaml` e `docker/compose/traccar-sse-dev.xml`.

| Serviço | O que roda | Endereço |
|---|---|---|
| `front` | Vite com **hot reload**, montando `traccar-web/` do host | http://localhost:5173 |
| `traccar` | Backend compilado do código atual (`docker/Dockerfile.sse`), H2 de desenvolvimento, só o protocolo OsmAnd | http://localhost:8082 |
| depurador | JDWP do backend, para conectar a IDE | `localhost:5005` |
| OsmAnd | Para simular rastreadores | `localhost:5055` |

### Pré-requisitos (Windows + WSL)

1. **Docker Desktop aberto** (o ícone da baleia ativo).
2. **Integração com o WSL ligada:** Docker Desktop → *Settings* → *Resources* → *WSL integration* → marque a
   distro usada (ex.: Ubuntu) → *Apply & restart*. Sem isso, o WSL mostra
   `The command 'docker' could not be found in this WSL 2 distro`.
3. Submódulo do front baixado: `git submodule update --init traccar-web`.

### Subir

Na raiz do repositório:

```bash
docker compose -f docker/compose/traccar-sse-dev.yaml up --build
```

- A primeira subida demora (download das imagens, build do front e do backend, `npm ci`). As seguintes usam
  cache.
- Abra http://localhost:5173. Num banco novo, o login leva direto ao cadastro do **primeiro usuário**, que vira
  administrador.
- `Ctrl+C` para parar. Para rodar em segundo plano, use `up --build -d` e veja os logs com
  `docker compose -f docker/compose/traccar-sse-dev.yaml logs -f`.

### No dia a dia

| Mudança | O que fazer |
|---|---|
| Front (`traccar-web/src`) | Nada: o Vite recarrega sozinho |
| `traccar-web/package.json` / `package-lock.json` | Reinicie o `front` (`docker compose -f docker/compose/traccar-sse-dev.yaml restart front`). O `npm ci` só roda quando o lock muda |
| Backend (Java), `schema/`, `templates/` | `docker compose -f docker/compose/traccar-sse-dev.yaml up --build traccar` |
| Configuração (`traccar-sse-dev.xml`) | `docker compose -f docker/compose/traccar-sse-dev.yaml restart traccar` |
| Zerar o banco | `docker compose -f docker/compose/traccar-sse-dev.yaml down -v` (apaga os volumes de dados e do `node_modules`) |

### Portas

Se algo já usa as portas (por exemplo, um Traccar local na 8082), troque só o lado do host:

```bash
TRACCAR_PORT=18082 OSMAND_PORT=15055 DEBUG_PORT=15005 FRONT_PORT=5180 \
  docker compose -f docker/compose/traccar-sse-dev.yaml up --build
```

Ou fixe os valores em `docker/compose/.env`. O front fala com o backend pela rede interna do compose
(`traccar:8082`), então mudar `TRACCAR_PORT` não afeta o front.

### Depurar o backend

O backend sobe com o agente JDWP em `5005`, sem esperar a IDE (`suspend=n`).

- **IntelliJ:** *Run* → *Edit Configurations* → *+* → *Remote JVM Debug*, host `localhost`, porta `5005`.
- **VS Code** (extensão *Debugger for Java*), em `.vscode/launch.json`:

  ```json
  { "type": "java", "name": "Traccar (Docker)", "request": "attach", "hostName": "localhost", "port": 5005 }
  ```

O runtime da imagem é gerado pelo `jlink` e só tem os módulos necessários. O compose de dev passa
`EXTRA_MODULES=",jdk.jdwp.agent"` para o `Dockerfile.sse`; sem esse módulo, a JVM recusa o agente
(`Could not find agent library jdwp`). A imagem de produção não inclui o módulo.

### Simular um ônibus

1. Logado como administrador, crie um dispositivo com identificador `dev1` e o atributo `public` = `true`.
2. Envie posições pelo OsmAnd:

   ```bash
   curl "http://localhost:5055/?id=dev1&lat=-1.4750&lon=-48.4500&timestamp=$(date +%s)"
   ```

3. A posição aparece no mapa em ~3 s (`server.buffering.threshold` padrão do Traccar). Abra o mapa numa aba
   anônima para ver a visão pública.

### Testar rotas públicas com curl

As rotas `/api/public/*` recusam requisição sem origem (`403`, ver
[public-access.md](public-access.md#validação-das-requisições)). Mande a origem do front:

```bash
curl -H 'Origin: http://localhost:5173' http://localhost:5173/api/public/devices
```

### Hot reload não funciona?

Alguns bind mounts do Docker Desktop no WSL não avisam o container quando um arquivo muda. Ligue o polling:

```bash
VITE_USE_POLLING=true docker compose -f docker/compose/traccar-sse-dev.yaml up front
```

Fica mais lento e usa mais CPU, por isso vem desligado. O código do projeto deve estar no sistema de arquivos
do WSL (ex.: `~/projetos/...`), não em `/mnt/c/...`, que é bem mais lento e não avisa alterações.

### Validação (2026-11-05)

O Docker não estava disponível na máquina onde isto foi escrito (Docker Desktop desligado), então o
`docker compose up` **ainda não foi executado**. Foi validado sem Docker:

| Item | Como | Resultado |
|---|---|---|
| YAML do compose | Parser `yaml` | Válido; serviços `traccar` e `front` |
| Depurador no runtime `jlink` | `jlink` com e sem `jdk.jdwp.agent` | Sem o módulo a JVM não inicia; com o módulo, `Listening for transport dt_socket` e handshake JDWP ok |
| Backend com a config de dev | Jar com o runtime `jlink` de dev e `traccar-sse-dev.xml` | Sobe sem erros, só o OsmAnd aberto |
| Front pelo proxy | Vite com `VITE_PROXY_TARGET` apontando para o backend | Mapa carregou o ônibus público, sem falhas de API; direto no backend sem origem: `403` |
| Instalação do front | Simulação do comando do container | `npm ci` só na primeira vez e quando o lock muda |
| Polling | `VITE_USE_POLLING=true` | Vite sobe normalmente |

## Direto no host

Sem Docker. Ver [frontend.md](frontend.md#rodando-localmente): `./gradlew assemble`,
`java -jar target/tracker-server.jar debug.xml` e `npm run dev` em `traccar-web/`.
