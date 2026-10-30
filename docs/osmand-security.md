# Segurança do protocolo OsmAnd

Levantamento de 2026-10-30. Baseado no código desta fork (Traccar 6.16.0): `OsmAndProtocol`,
`OsmAndProtocolDecoder`, `TrackerServer`, `WebServer` e `Keys`.

## Resumo

O OsmAnd **não oferece autenticação nem criptografia**. O único dado de identidade é o `uniqueId` do
dispositivo, enviado em texto claro. Quem souber o ID de um ônibus consegue enviar posições falsas, e elas
aparecem no mapa público como verdadeiras.

## Como o protocolo funciona

- Servidor HTTP na porta `osmand.port` (padrão `5055`), sem TLS por padrão.
- O rastreador envia `GET` ou `POST` com parâmetros de formulário, ou um `POST` com JSON:

  ```
  GET /?id=<uniqueId>&lat=-1.47&lon=-48.44&timestamp=1767225600&speed=10
  POST / {"device_id": "<uniqueId>", "location": {...}}
  ```

- O decoder (`OsmAndProtocolDecoder`) procura o dispositivo pelo `id`/`deviceid` (ou `device_id` no JSON) e
  aceita a posição se o ID existir.

| Proteção | Disponível? | Detalhe |
|---|---|---|
| Autenticação (senha, token, assinatura) | Não | Nenhum campo é conferido além do ID |
| Criptografia em trânsito | Não, por padrão | HTTP puro, então o `uniqueId` pode ser lido na rede |
| Integridade / anti-replay | Não | Uma requisição capturada pode ser reenviada |
| Rejeição de ID desconhecido | Sim | Responde `400` (`404` no formato JSON) se o ID não existe, a menos que `database.registerUnknown=true` |
| Filtro de dados absurdos | Parcial | `osmand.minAccuracy` e os filtros gerais `filter.*` |

### Exposição também pela porta web

`WebServer.initClientProxy` repassa todo `POST /` da porta web (`web.port`, padrão `8082`) para a porta do
OsmAnd (`/client-proxy`). O protocolo fica alcançável **pela porta web também**, não só pela 5055. Por isso
bloquear a 5055 no firewall não basta.

## Riscos para este projeto

| Risco | Impacto |
|---|---|
| Posição falsa de um ônibus | O mapa público mostra o ônibus no lugar errado, e passageiros são enganados |
| Status online falso | Um ônibus parado aparece como em operação |
| Poluição dos relatórios | Quilometragem, consumo e emissões calculados com dados falsos |
| Descoberta do `uniqueId` | Vazamento por tráfego HTTP, por logs, por quem configurou o rastreador ou por IDs previsíveis (IMEI, `bus01`) |

O que **já reduz** o risco nesta fork:
- Os endpoints públicos (`/api/public/*`) não expõem `uniqueId` (ver [public-access.md](public-access.md)).
- `uniqueId` só aparece para usuários logados (`/api/devices`, telas de configuração).

## Mitigações disponíveis hoje (sem mudar código)

Em ordem de custo/benefício:

1. **`uniqueId` longo e aleatório.** Na prática ele é a senha do dispositivo. Evite IMEI, placa ou sequências
   (`bus01`). Exemplo: `openssl rand -hex 16`.
2. **Manter `database.registerUnknown=false`** (padrão), para que IDs inventados não virem dispositivos novos.
3. **TLS:**
   - **Recomendado:** HTTPS no proxy reverso (nginx) na porta web, com o rastreador enviando para
     `https://servidor/`. Funciona porque o `POST /` da porta web é repassado ao OsmAnd. Feche a 5055 para
     acesso externo.
   - **Alternativa:** `osmand.ssl=true`. O `TrackerServer` lê `<protocolo>.ssl` para qualquer protocolo, mas
     é preciso configurar um keystore Java (`javax.net.ssl.keyStore`). **Não testado.**
4. **Firewall na 5055** liberando só os IPs dos rastreadores. Só serve com IP fixo ou VPN, e chips de celular
   normalmente não têm. Lembre-se de que a porta web também aceita OsmAnd.
5. **Filtros** (`filter.enable`, `filter.invalid`, `filter.maxSpeed`, `filter.distance`,
   `osmand.minAccuracy`). Descartam saltos e posições inválidas, mas **não** impedem um falsificador cuidadoso.

## Proposta: token por dispositivo (não implementado)

Exigir um segredo além do ID, conferido no `OsmAndProtocolDecoder`:

- Atributo no dispositivo, ex.: `osmandToken = <aleatório>`.
- Parâmetro na requisição: `...&id=<uniqueId>&token=<osmandToken>` (ou campo no JSON).
- Sem token ou com token errado, a posição é recusada. A comparação deve ser em tempo constante
  (`MessageDigest.isEqual`).
- Chave de config, ex.: `osmand.requireToken=true`, para ligar aos poucos. Dispositivos sem o atributo podem
  continuar aceitos até a migração terminar.

| Prós | Contras |
|---|---|
| ID vazado deixa de bastar para falsificar | O rastreador precisa permitir configurar a URL ou um parâmetro extra |
| Token trocável sem mudar o ID (histórico preservado) | Sem TLS o token também trafega em claro, então deve ser combinado com HTTPS |
| Mudança pequena e isolada no decoder | Código próprio da fork, a manter nos merges com o upstream |

O app **Traccar Client** permite URL customizada com parâmetros. Rastreadores de hardware que falam OsmAnd
nem sempre permitem, então verifique o modelo usado antes de exigir o token.

## Checklist de produção

- [ ] `uniqueId` aleatório em todos os ônibus
- [ ] `database.registerUnknown=false`
- [ ] HTTPS no proxy reverso, com a 5055 fechada externamente
- [ ] `filter.enable=true` com limites de velocidade e distância adequados ao campus
- [ ] Decidir sobre o token por dispositivo (proposta acima)
