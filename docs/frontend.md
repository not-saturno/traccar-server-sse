# Frontend: `traccar-web` (gps-tracker-front)

O submódulo `traccar-web` aponta para
[not-saturno/gps-tracker-front](https://github.com/not-saturno/gps-tracker-front) (branch `main`), não mais para
o `traccar/traccar-web` upstream. Stack: Vue 3, Vite, PrimeVue, Leaflet e Pinia.

```ini
# .gitmodules
[submodule "traccar-web"]
	path = traccar-web
	url = ../gps-tracker-front.git   # relativo ao origin: SSH local, HTTPS na CI
	branch = main
```

Clonar com o submódulo:

```bash
git clone --recurse-submodules git@github.com:not-saturno/traccar-server-sse.git
# ou, num clone existente:
git submodule update --init --remote traccar-web
```

## Acesso

| Rota | Sem login | Logado |
|---|---|---|
| `/` (mapa + lista de ônibus) | Só dispositivos públicos, via `/api/public/*` | Dispositivos da conta, via `/api/devices` + `/api/sse`. Administrador: todos (ver abaixo) |
| `/login` | Sim (vai para `/register` se o servidor não tem usuários) | Redireciona para `/` |
| `/register` | Só no primeiro acesso ou com cadastro aberto (ver abaixo) | Idem |
| `/settings/**` (relatórios, novo, dispositivos, editar, conta) | Redireciona para `/login?redirect=...` | Sim |
| `/settings/users` (usuários) | Redireciona para `/login` | **Só administrador**: cadastrar, excluir, vincular dispositivos a usuários comuns e redefinir senhas. Os demais voltam para `/settings/reports` |
| `/settings/logs` (logs) | Redireciona para `/login` | Mensagens brutas dos rastreadores em tempo real (ver [abaixo](#logs-em-tempo-real-settingslogs)) |
| `/settings/account` (conta) | Redireciona para `/login` | Nome, e-mail e troca da própria senha (pede a senha atual) |

- Menu do mapa: o visitante vê **MAPA** e **ENTRAR**. O usuário logado vê também **RELATÓRIOS**, **CONFIGURAR**,
  **CONTA** e **SAIR**.
- A sessão é o cookie `JSESSIONID` do Traccar (`POST /api/session`). O store `src/stores/session.ts` restaura a
  sessão uma vez por carregamento (`GET /api/session`).
- Um `401` em rota protegida limpa a sessão e manda para o login.
- Erro de login aparece no próprio formulário, com os campos em vermelho: campos vazios, "E-mail ou senha
  incorretos" (`401`), usuário desativado ou conta expirada (`400` com o texto da exceção do Traccar) e sem conexão.
  Antes o login chamava um toast, mas a página não monta `<Toast />`, e nada aparecia.
- O backend continua sendo a barreira real: o guard do front só melhora a UX.

### Administrador vê todos os dispositivos

No Traccar, `GET /api/devices` só devolve os dispositivos vinculados ao usuário, mesmo para administrador (quem
cria um dispositivo é vinculado a ele). Por isso o administrador não via os ônibus cadastrados por outros usuários.
Agora:

- o front usa `src/api/devices.ts`: para administrador, `GET /devices?all=true` no mapa, em Dispositivos, nos
  Relatórios e na edição (`/settings/<uniqueId>`, que procura na lista completa, porque a busca por `uniqueId` da
  API ignora o `all`);
- o `/api/sse` do administrador recebe posições e status de todos os dispositivos, inclusive dos criados depois
  que ele abriu o mapa (ver [sse.md](sse.md#administrador-todos-os-dispositivos)).

Usuário comum continua vendo só os dispositivos vinculados. Para dar acesso a um ônibus, vincule em **Usuários**.

### Cadastro (`/register`)

Segue o traccar-web original e usa `GET /api/server` (store `src/stores/server.ts`):

| Estado do servidor | `/register` | Link "Criar conta" no login |
|---|---|---|
| `newServer: true` (nenhum usuário) | Liberado. `/login` redireciona para cá, com o texto "Configuração inicial": o primeiro usuário vira **administrador** | Oculto (não há com quem logar) |
| Já tem usuário e `registration: false` | Redireciona para `/login` | Oculto |
| Já tem usuário e `registration: true` (aberto pelo administrador) | Liberado | Visível |

- A regra que vale de verdade é a do backend. Com o cadastro fechado, `POST /api/users` responde `400` com
  `Registration disabled`. O front mostra "Cadastro desabilitado" e volta para o login.
- Depois do cadastro inicial o front recarrega `/api/server`, então `/register` fica bloqueado na hora, sem
  precisar recarregar a página.
- Para abrir ou fechar o cadastro, altere `registration` do servidor (`PUT /api/server`, como administrador).

### Layout responsivo

Ponto de quebra: `md` (768px).

| Tela | Celular | Desktop |
|---|---|---|
| Mapa (`/`) | Mapa em tela cheia. A lista abre como bottom sheet (60% da altura) pelo botão **Ônibus (n)** ou pelo menu, e fecha no X ou ao tocar no mapa. Entrar, configurações e sair ficam na barra superior | Painel lateral com a lista e atalhos |
| `/settings/*` | Barra superior (voltar ao mapa + seção atual) e abas fixas embaixo: Relatórios, Novo, Dispositivos, Conta | Menu lateral (`w-64`) |
| Dispositivos | Cards com editar/excluir | Tabela paginada |
| Formulários | Uma coluna, botões em largura total e empilhados | Duas colunas onde couber |

Ajustes gerais:
- Inputs com 16px no celular, para o iOS não dar zoom ao focar (`src/style.css`).
- `touch-action: manipulation`: o toque responde sem esperar o duplo toque.
- Alturas em `dvh`, que respeitam as barras do navegador mobile, e `safe-area-inset-bottom` (iPhone).
- O mapa fica em cache (`KeepAlive` em `App.vue`). Voltar das configurações não recria o mapa nem reconecta o stream.
- Login e logout trocam a fonte de dados do mapa (conta ↔ pública) sem recarregar a página.
- Sem transição animada entre rotas: `<Transition mode="out-in">` junto com `KeepAlive` e os redirecionamentos
  do guard gerava `Cannot read properties of null (reading 'parentNode')` ao trocar de rota.
- Rota inexistente redireciona para o mapa.

### Status online no mapa (`src/pages/index.vue`)

| Momento | Regra |
|---|---|
| Carregamento | Online se o servidor informa `status: online` **e** o `lastUpdate` tem até 2 min. Senão, Offline com "último sinal hh:mm" |
| Despejo inicial do stream | Só posiciona o marcador e não altera o status |
| Posição ao vivo | Online, com último sinal = agora |
| Evento `devices` com status ≠ `online` | Offline na hora (o rastreador desconectou ou estourou o `status.timeout` do servidor) |
| Sem posição há mais de 2 min | Offline (checagem a cada 10s) |

Na lista, cada item mostra o selo Online/Offline e o tempo desde o último sinal ("agora", "há 5 min",
"há 2 h" ou a data). O tempo é atualizado a cada 10s. Dispositivo sem posição aparece como "Sem posição" e não é
clicável.

### Detalhes do dispositivo (administrador)

Logado como **administrador**, o popup do ônibus no mapa mostra os dados completos (componente
`traccar-web/src/components/DeviceDetails.vue`). Clicar no ônibus da lista também abre esse popup, depois que o mapa
termina de centralizar.

| Grupo | Dados |
|---|---|
| Posição | Velocidade (km/h), direção (graus e ponto cardeal), origem (GPS ou sem GPS), coordenadas, altitude, precisão, satélites, em movimento, distância total, endereço |
| Aparelho | Bateria (%), sinal GSM, ignição, alarme, passos (só se maior que zero) |
| Horários | Hora do GPS, hora do aparelho, recebida pelo servidor, último contato (com "há N min") |
| Identificação | Identificador, status no servidor, protocolo, categoria, modelo, telefone, contato |
| Outros dados | Qualquer outro atributo que o protocolo enviar |

Só aparece o que o rastreador envia. Com o TK915 (protocolo `watch`) vêm bateria, sinal GSM, satélites,
velocidade, direção, altitude e GPS válido; o Traccar acrescenta os horários, a distância e o movimento. Os dados
vêm do stream autenticado (`/api/sse`) e de `GET /api/devices`. **Usuário comum e visitante** continuam vendo só o
resumo (nome e horário do último sinal), e o stream público não envia esses dados.

### Logs em tempo real (`/settings/logs`)

Tela **Logs** do menu de configurações (`traccar-web/src/pages/settings/logs.vue`). Mostra cada mensagem que um
rastreador manda ao servidor, como chegou (texto do protocolo ou hexadecimal), útil para conferir se um aparelho
novo está falando com o servidor, com qual identificador e o que o servidor entendeu da mensagem.

- Fonte: eventos `logs`, `positions` e `devices` do `/api/sse` (a tela abre a própria conexão e fecha ao sair). Não
  há endpoint REST: só aparece o que chegar com a tela aberta, e nada fica salvo.
- Alcance: usuário comum vê os logs dos dispositivos vinculados a ele; administrador, os de **todos** (ver
  [sse.md](sse.md#administrador-todos-os-dispositivos)).
- Duas visualizações (botões **Lista** e **Detalhes**; a escolha fica salva no navegador, `localStorage`
  `logs.view`):
  - **Lista** (padrão): uma linha por mensagem, nas cores das outras telas. A mais recente entra embaixo e a lista
    sobe, como num terminal, sem imitar um. Cada linha: hora, nome do dispositivo, identificador, protocolo, tipo
    da mensagem, IP e, à direita, as coordenadas da posição gerada (quando houver); a mensagem bruta embaixo. A
    linha nova pisca em verde-claro (desligado com `prefers-reduced-motion`). Acompanha o fim sozinha; se você
    rolar para cima, ela para e aparece o botão "N NOVAS" para voltar. Clicar numa linha abre os detalhes num
    diálogo.
  - **Detalhes**: um cartão por mensagem, mais recente primeiro, já com os detalhes abertos (50 por vez, com
    "Mostrar mais").
- Detalhes (`traccar-web/src/components/LogDetails.vue`), no mesmo formato do popup do administrador no mapa:
  - **Mensagem**: a mensagem bruta (com copiar), hora em que o servidor a leu (com milissegundos), protocolo, tipo
    (no `watch`: `LK` heartbeat, `UD` posição, `AL` alarme etc.), IP de origem, identificador, formato (texto ou
    hexadecimal) e tamanho em bytes;
  - abaixo, o **`DeviceDetails`** do dispositivo (Posição, Aparelho, Horários, Identificação, Outros dados) com a
    **posição gerada por aquela mensagem**, não a última do dispositivo. Mensagem que não gerou posição mostra o
    aviso. Aqui os detalhes aparecem para qualquer usuário logado, porque ele só recebe logs dos próprios
    dispositivos.
- Associação mensagem → posição: o servidor processa a posição depois do log, às vezes segundos e outras
  mensagens depois, então a ordem de chegada não serve. O `LogRecord` desta fork traz `time` (quando o servidor
  leu a mensagem), e a posição decodificada dela recebe `serverTime` logo em seguida, na mesma leitura. A tela liga
  cada posição à última mensagem do dispositivo com `time <= serverTime` (até 2 s antes). Mensagem com várias
  posições fica com a primeira.
- Rastreador **não cadastrado** aparece com o selo "NÃO CADASTRADO" (laranja no terminal) e o botão
  **Cadastrar**, que abre `/settings/new` com o identificador preenchido (`?uniqueId=`). Só aparece se o servidor
  tiver `web.showUnknownDevices=true`, e o Traccar envia só a primeira mensagem dessa conexão.
- Filtro por dispositivo (todos, não cadastrados ou um específico) e busca no conteúdo, identificador, IP ou nome.
- **Pausar** congela a lista e acumula o que chegar (o botão mostra quantos); **Continuar** junta tudo. **Limpar**
  apaga a lista. A tela guarda no máximo 1000 registros.
- O indicador mostra "Ao vivo", "Reconectando" ou "Pausado". O `EventSource` reconecta sozinho, mas o que chegar
  no intervalo se perde.

### Seguir um ônibus

Clicar num ônibus da lista centraliza o mapa nele e passa a **segui-lo**: cada posição nova recentraliza o
mapa. Um aviso "Seguindo NOME ✕" aparece no topo. Para parar de seguir:
- **arraste o mapa** (o gesto indica que você quer olhar outro lugar);
- toque no **✕** do aviso;
- ou clique de novo no mesmo ônibus da lista.

### Animação da troca de ponto

A cada posição ao vivo, o marcador desliza da posição atual até a nova em 1,4 s (ease-out cúbico), em linha
reta. O ônibus anda livre no mapa, sem trajeto. O primeiro lote do stream posiciona direto, distâncias acima de
1 km saltam, e `prefers-reduced-motion` desliga a animação. Código em `traccar-web/src/utils/markerAnimator.ts`.
Detalhes em [suavizacao.md](suavizacao.md#implementação-nesta-fork-traccar-server-sse).

As posições chegam ao front cerca de **3 s** depois de o rastreador enviar. É o `server.buffering.threshold`
padrão do Traccar (3000 ms), que segura cada posição para reordenar as que chegam fora de ordem. Com `0` o atraso
some, mas posições fora de ordem deixam de ser corrigidas.

### Categorias de dispositivo

Definidas em `src/data/categories.ts` e usadas pelo cadastro, pela edição e pelos relatórios:
`electricBus` (padrão) e `gasBus`. Os relatórios usam fatores de diesel para `gasBus` e tratam qualquer outra
categoria como elétrica. Um valor antigo (ex.: `bus`, `ship`) aparece na edição como "Outra (valor)", em vez de
campo vazio.

### Removido: login automático com credenciais embutidas

Antes, `src/api/auth.ts` fazia login automático com e-mail e senha fixos no código, que iam para o bundle JS
público. Isso foi removido. **A senha dessa conta deve ser trocada**, porque continua no histórico do git e em
qualquer build já publicado.

## Configuração (`.env.local`)

Veja `traccar-web/.env.example`.

| Variável | Padrão | Uso |
|---|---|---|
| `VITE_API_URL` | `/api` | Base da API. Use uma URL absoluta só se o front ficar em outro domínio, e então configure `web.origin` no servidor |
| `VITE_PROXY_TARGET` | `http://localhost:8082` | Traccar usado pelo proxy do `npm run dev` |

## Rodando localmente

Com Docker (front com hot reload + backend + depurador): ver [desenvolvimento.md](desenvolvimento.md#docker-modo-desenvolvedor).
Sem Docker:

```bash
# 1. servidor (na raiz deste repo). O debug.xml já tem public.enable=true e web.path=./traccar-web/dist
./gradlew assemble
java -jar target/tracker-server.jar debug.xml

# 2. front com hot reload (proxy /api -> localhost:8082)
cd traccar-web
npm ci
npm run dev        # http://localhost:5173
```

## Build e empacotamento

`npm run build` gera `traccar-web/dist`. O Traccar serve o front a partir de `web.path` (`./web`) e já faz o
fallback de SPA (rotas sem extensão com `Accept: text/html` retornam `index.html`). Então o history mode do
vue-router funciona sem configuração extra.

`setup/build-dist.sh` copia `traccar-web/dist` para `web/` no pacote.

> As traduções de notificação (`templates/translations`) vinham do `traccar-web` upstream e não existem no novo
> front. Sem elas, os textos de notificação (e-mail/SMS) ficam sem os rótulos traduzidos. Se as notificações forem
> usadas, copie `src/resources/l10n` do `traccar/traccar-web` para `templates/translations`.

## Documentação do próprio front

O repositório do front tem a documentação de desenvolvimento em `traccar-web/docs/`:

- `desenvolvimento.md`: estrutura, rotas, sessão, API, mapa, relatórios, convenções e armadilhas;
- `configuracao.md`: variáveis de ambiente, configuração do servidor e constantes do código;
- `dependencias.md`: dependências, política de licenças (PrimeVue fixado na 4.x, MIT) e restrições de versão.

## Pendências conhecidas do front

- Pendências estão em `traccar-web/docs/desenvolvimento.md`.
