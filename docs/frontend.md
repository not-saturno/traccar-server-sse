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
| `/` (mapa + lista de ônibus) | Só dispositivos públicos, via `/api/public/*` | Dispositivos da conta, via `/api/devices` + `/api/sse` |
| `/login` | Sim (vai para `/register` se o servidor não tem usuários) | Redireciona para `/` |
| `/register` | Só no primeiro acesso ou com cadastro aberto (ver abaixo) | Idem |
| `/settings/**` (relatórios, novo, dispositivos, editar, conta) | Redireciona para `/login?redirect=...` | Sim |

- Menu do mapa: o visitante vê **MAPA** e **ENTRAR**. O usuário logado vê também **RELATÓRIOS**, **CONFIGURAR**,
  **CONTA** e **SAIR**.
- A sessão é o cookie `JSESSIONID` do Traccar (`POST /api/session`). O store `src/stores/session.ts` restaura a
  sessão uma vez por carregamento (`GET /api/session`).
- Um `401` em rota protegida limpa a sessão e manda para o login.
- O backend continua sendo a barreira real: o guard do front só melhora a UX.

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

## Pendências conhecidas do front

- `vue-tsc` acusa ~53 erros de tipo já existentes em `settings/reports.vue`, `settings/new.vue` e
  `settings/[trackerId].vue` (tipos do Chart.js e possíveis `null`). O `vite build` funciona, mas `npm run
  type-check` falha.
- `settings/account.vue` ainda não salva alterações (o `handleSave` só faz `console.log`).
