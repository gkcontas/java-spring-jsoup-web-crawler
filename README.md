# Web scraping e crawler por endpoint

Endpoint que recebe uma URL e extrai dados da página, evoluindo para um **crawler** que segue links em profundidade.

Usar Jsoup é a parte fácil. O que este projeto trata é tudo que cerca um crawler que pode ir para produção: **SSRF**, respeito a `robots.txt`, rate limiting, normalização e deduplicação de URLs, timeouts e concorrência. Um scraper ingênuo exposto como endpoint HTTP é uma vulnerabilidade de SSRF clássica — e saber disso é o que separa o júnior do sênior neste tema.

## Status

✅ Implementado, testado e validado contra um site real local.

## Stack

- Java 21 + Spring Boot 3.5 — **virtual threads** no Tomcat e no crawl
- **Jsoup 1.21** para fetch e parsing
- Caffeine para cache de `robots.txt` por host
- Gradle (Kotlin DSL) + wrapper `gradlew` (toolchain Java 21 resolvida automaticamente)
- JUnit 5 + AssertJ + Awaitility + **WireMock** (site falso, offline e determinístico)

## SSRF — o ponto central

Um endpoint que busca uma URL escolhida por quem chama transforma o servidor num proxy para dentro da própria rede. Sem defesa, `POST /scrape` com `http://169.254.169.254/latest/meta-data/` entrega as credenciais da instância na nuvem, `http://localhost:8080/actuator/env` despeja a configuração, e `http://10.0.0.5/` alcança o que estiver na rede interna. Nada disso exige um bug em outro lugar — é a funcionalidade operando como escrita.

Com a configuração padrão, rodando de verdade:

```
http://127.0.0.1:8099/          → 400  "port 8099 is not allowed"
http://169.254.169.254/latest/  → 400  "resolves to a non-public address (169.254.169.254)"
file:///etc/passwd              → 400  "only http and https are allowed"
```

As camadas:

1. **Esquema** — só `http` e `https`. Um fetcher que aceita `file:` é um leitor de arquivos locais fantasiado de URL.
2. **Porta** — allowlist (padrão `80, 443`). Sem ela, uma URL vira port scanner: `:6379` fala com Redis, `:5432` com Postgres, `:9200` com Elasticsearch.
3. **Endereço** — resolve o DNS e recusa loopback, link-local, RFC 1918, CGNAT, multicast, `0.0.0.0`, `::1` e `fc00::/7`. **Todos** os endereços que o nome resolve precisam ser públicos: checar só o primeiro deixaria passar um nome que devolve um público e um privado.
4. **Revalidação a cada redirect** — detalhado abaixo.

### O detalhe que quase sempre passa batido

O ataque não usa a URL proibida diretamente. Usa um host público, que o atacante controla, e que responde `302 Location: http://169.254.169.254/`. Uma guarda que valida só a URL digitada entrega as credenciais.

Por isso os redirects são seguidos **à mão**, com `followRedirects(false)` no Jsoup, revalidando **cada salto**. Coberto por teste de integração (`shouldRefuseToFollowARedirectThatJumpsToAPrivateAddress`), que exercita exatamente esse caminho.

### Limitação conhecida, declarada

A validação resolve o nome e depois o cliente HTTP resolve de novo ao conectar. Um registro DNS que muda entre as duas consultas (**DNS rebinding**) escapa. Fechar essa brecha exige conectar ao endereço já validado e mandar o nome no header `Host`, o que precisa de um socket factory próprio.

Está escrito aqui, e não implícito, porque uma defesa cujos limites não são documentados acaba merecendo mais confiança do que deveria.

### Allowlist em vez de interruptor

Desligar a guarda inteira para rastrear um site interno é exagero. `allowed-private-hosts` isenta **hosts nomeados** — exatamente `wiki.internal`, nunca uma faixa inteira — mantendo o bloqueio para todo o resto. A própria suíte de testes usa isso: roda com a guarda **ligada**, isentando só o loopback do WireMock, e é justamente por isso que as asserções de SSRF têm valor.

## Rodando de verdade

Site local com `robots.txt` declarando `Disallow: /admin` e `Crawl-delay: 1`, página inicial linkando dois produtos, o mesmo produto de novo com `?utm_source=newsletter`, o painel admin e um link externo.

**Scrape de uma página:**

```json
{
  "requestedUrl": "http://127.0.0.1:8099/produtos/cafeteira.html",
  "finalUrl": "http://127.0.0.1:8099/produtos/cafeteira.html",
  "statusCode": 200,
  "title": "Cafeteira",
  "data": {
    "titulo": "Cafeteira Italiana",
    "preco": "R$ 189,90",
    "imagem": "http://127.0.0.1:8099/img/cafeteira.png"
  },
  "linksFound": 1
}
```

Acentuação intacta (detecção de charset) e o `src` relativo devolvido **absoluto**, para o cliente não ter que juntar com a URL da página.

**Crawl completo:**

```
status         : COMPLETED
paginas obtidas: 3
paginas puladas: 1
duracao total  : 2180 ms

  depth=0  http://127.0.0.1:8099/                          titulo=Loja Demo
  depth=1  http://127.0.0.1:8099/produtos/cafeteira.html   titulo=Cafeteira Italiana  preco=R$ 189,90
  depth=1  http://127.0.0.1:8099/produtos/moedor.html      titulo=Moedor Manual       preco=R$ 249,00
```

Quatro comportamentos visíveis de uma vez:

- **`/admin/painel.html` pulado** — `Disallow` no robots.txt. A página não foi baixada e descartada: nunca foi pedida.
- **Cafeteira aparece uma vez** — os dois links (`/produtos/cafeteira.html` e o mesmo com `?utm_source=newsletter`) colapsam numa URL só.
- **Link externo não seguido** — o crawl fica no host da semente.
- **2180 ms para 3 páginas** — o `Crawl-delay: 1` do site foi respeitado. Sem ele, o crawl levaria uns 50 ms.

## Armadilhas cobertas de propósito

**1. `robots.txt` não é opcional.** Buscar, cachear e respeitar `Disallow` e `Crawl-delay`. O parser implementa o que importa na prática — prefixo, curinga `*`, âncora `$` e **regra mais longa vence** entre Allow e Disallow. Essa última é o que permite a um site escrever `Disallow: /admin` junto de `Allow: /admin/public`: ler de cima para baixo dá a resposta errada.

Detalhes que quebram parsers ingênuos e estão cobertos por teste: `Disallow:` vazio significa o **oposto** de `Disallow: /`; um grupo que nomeia o crawler substitui o grupo `*` em vez de somar a ele; arquivo ausente significa "sem restrições", não "proibido tudo".

**2. Cachear o robots.** Pedir `/robots.txt` antes de cada página dobraria a carga imposta ao site — o contrário do que respeitar o arquivo serve para fazer.

**3. Rate limiting por host.** Um crawler sem throttle é indistinguível de um ataque de negação de serviço; do outro lado do fio ninguém consegue diferenciar entusiasmo de ataque. O limitador reserva o slot de cada chamador numa **única operação atômica** (`ConcurrentHashMap.compute`) — ler o último instante e gravar depois deixaria dois threads reservarem o mesmo momento. Há teste com 4 chamadores concorrentes provando o espaçamento.

**4. Normalização de URL.** `/page`, `/page/`, `/page#secao` e `/page?utm_source=x` são quatro URLs e um documento. Sem normalizar, o crawler busca a mesma coisa várias vezes, gasta o orçamento de páginas à toa e, num site que gera links com tracking, **nunca termina**.

**5. Limites obrigatórios.** Calendários e arquivos paginados geram links infinitamente. Profundidade e teto de páginas não são configurações opcionais: sem eles o crawl não termina. A reserva do orçamento usa CAS — checar o contador e incrementar em passos separados deixa vários threads passarem juntos e estourarem o limite.

**6. BFS, não DFS.** O limite de profundidade é a trava de segurança. Em largura, o crawler já viu tudo a distância 1 antes de olhar a distância 2, então parar no limite entrega um retrato completo dos níveis rasos em vez de um fio fundo e estreito pelo site.

**7. Uma página ruim não derruba o crawl.** Todo site real tem link quebrado. Falha de uma página é contabilizada em `pagesSkipped` e o crawl segue.

**8. Timeouts e tamanho máximo de resposta.** Sem timeout, um thread fica preso num host morto para sempre; sem `maxBodySize`, uma resposta enorme vira bomba de memória.

**9. Retry só em falha de transporte.** Um HTTP 404 é uma resposta — repetir produz a mesma resposta mais carga no servidor. Backoff exponencial apenas em `IOException`.

**10. Virtual threads onde fazem diferença.** Cada página é I/O-bound, então o pool de virtual threads permite alta concorrência sem um pool de plataforma gigante — é o caso de uso canônico do recurso. Isso também é o que torna barato **bloquear** no rate limiter: um thread parado esperando sua vez não custa praticamente nada. Em threads de plataforma, esse mesmo desenho queimaria um thread do pool por host sendo tratado com educação. Onde virtual threads **não** ajudam: trabalho CPU-bound, como o parsing em si.

**11. Cancelamento cooperativo.** `DELETE /crawl/{id}` marca a flag e responde na hora — logo depois o status ainda é `RUNNING`, e só depois vira `CANCELLED`. Interromper no meio de uma requisição deixaria conexão pela metade e não ensinaria nada de bom ao host do outro lado.

**12. Códigos HTTP com significado.** URL recusada → **400** (pedido malformado, não permissão que o chamador poderia ter). CSS inválido → **400**. Site de destino fora do ar ou servindo algo que não é HTML → **502**, não 500: o pedido estava correto, o problema é a montante, e chamar isso de 500 manda quem está depurando para os logs errados.

## Endpoints

| Método | Rota | Descrição |
|--------|------|-----------|
| POST | `/scrape` | Extrai dados de **uma** página, síncrono |
| POST | `/crawl` | Inicia crawl assíncrono; responde **202** com `jobId` |
| GET | `/crawl/{jobId}` | Status, progresso e páginas extraídas |
| DELETE | `/crawl/{jobId}` | Cancela um crawl em andamento |

## Como rodar

```bash
./gradlew bootRun
```

A API sobe em `http://localhost:8080`. Com a configuração padrão ela só alcança endereços públicos nas portas 80 e 443.

Para apontar a um site local durante o desenvolvimento, isente o host explicitamente:

```bash
SPRING_APPLICATION_JSON='{"app":{"crawler":{"security":{"allowed-private-hosts":["127.0.0.1"],"allowed-ports":[]}}}}' ./gradlew bootRun
```

## Como rodar os testes

```bash
./gradlew test
```

68 testes: 43 unitários (tabela de SSRF, normalização de URL, parser de robots.txt, rate limiter) e 25 de integração contra um site falso servido por WireMock. Não precisam de Docker nem de internet.

Usar WireMock em vez de sites reais mantém a suíte determinística e offline — e é também a postura correta eticamente: uma suíte de testes não tem o que fazer martelando o servidor de outra pessoa a cada execução, que é exatamente o comportamento que este projeto gasta tanto esforço prevenindo.

## Exemplo de uso

```bash
# Extrair dados de uma página
curl -s -X POST localhost:8080/scrape -H "Content-Type: application/json" -d '{
  "url": "https://example.com/produto",
  "selectors": {
    "titulo": { "css": "h1.product-title" },
    "preco":  { "css": "span.price" },
    "imagem": { "css": "img.main", "attribute": "src" },
    "tags":   { "css": "li.tag", "multiple": true }
  }
}'

# Iniciar um crawl
curl -s -X POST localhost:8080/crawl -H "Content-Type: application/json" -d '{
  "seedUrl": "https://example.com/",
  "maxDepth": 2,
  "maxPages": 20,
  "selectors": { "titulo": { "css": "h1" } }
}'

# Acompanhar
curl -s localhost:8080/crawl/<jobId>

# Cancelar
curl -s -X DELETE localhost:8080/crawl/<jobId>

# Recusados pela guarda de SSRF
curl -s -X POST localhost:8080/scrape -H "Content-Type: application/json" \
  -d '{"url":"http://169.254.169.254/latest/meta-data/","selectors":{"x":{"css":"h1"}}}'
curl -s -X POST localhost:8080/scrape -H "Content-Type: application/json" \
  -d '{"url":"file:///etc/passwd","selectors":{"x":{"css":"h1"}}}'
```

## Uso responsável

Scraping tem limites legais e éticos, e eles não são detalhe de implementação:

- **Respeite `robots.txt`** e os termos de uso do site. Este projeto obedece por padrão; `respect-robots-txt: false` existe para casos em que se rastreia o próprio site, não para contornar o de terceiros.
- **Identifique-se.** O User-Agent padrão traz nome e URL de contato, para que quem administra o site saiba quem é e como pedir para parar.
- **Limite a taxa.** O padrão é uma requisição por segundo por host, e o `Crawl-delay` do site prevalece quando é maior.
- **Colete só o necessário** e cuidado com dados pessoais — LGPD e GDPR se aplicam a dados raspados exatamente como a qualquer outro.
- Conteúdo de terceiros em geral é **protegido por direito autoral**; conseguir baixar não é o mesmo que poder republicar.
