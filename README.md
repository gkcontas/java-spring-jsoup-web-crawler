# Web Crawler e Scraper

API que recebe uma URL e extrai dados dela. Dois modos: raspar uma página só, com seletores CSS informados na requisição, ou percorrer o site inteiro a partir de uma URL semente, respeitando limites de profundidade e de quantidade de páginas.

O crawl roda como job assíncrono — a requisição devolve um identificador na hora e o progresso é consultado depois.

## O que o crawler faz

- Extrai campos por seletor CSS, podendo ler atributos (`href`, `src`) e listas de valores.
- Percorre links em largura, restrito ao domínio da semente, sem visitar a mesma página duas vezes.
- Normaliza URLs antes de comparar, para que variações da mesma página não virem páginas diferentes.
- Obedece ao `robots.txt` do site, incluindo o `Crawl-delay`.
- Limita a taxa por host e aplica um intervalo de cortesia entre requisições.
- Valida o destino antes de cada requisição e também a cada redirecionamento, bloqueando endereços privados e esquemas que não sejam HTTP.
- Executa as buscas em paralelo com virtual threads.

## Tecnologias e bibliotecas

| | |
|---|---|
| Linguagem | Java 21 (virtual threads) |
| Framework | Spring Boot 3.5 |
| HTML | Jsoup 1.21 |
| Cache | Caffeine (robots.txt por host) |
| Validação | Bean Validation |
| Build | Gradle Kotlin DSL (wrapper `gradlew`) |
| Testes | JUnit 5, WireMock, Awaitility |

## Pré-requisitos

- JDK 21 ou superior

Não precisa de Docker nem de banco de dados.

## Como rodar

```bash
./gradlew bootRun
```

A API fica em `http://localhost:8080`. Na configuração padrão só alcança endereços públicos, nas portas 80 e 443.

Para apontar a um site local durante o desenvolvimento, libere o host explicitamente:

```bash
SPRING_APPLICATION_JSON='{"app":{"crawler":{"security":{"allowed-private-hosts":["127.0.0.1"],"allowed-ports":[]}}}}' ./gradlew bootRun
```

## Endpoints

| Método | Rota | Descrição |
|---|---|---|
| `POST` | `/scrape` | Extrai dados de uma única página, de forma síncrona |
| `POST` | `/crawl` | Inicia um crawl e devolve o `jobId` |
| `GET` | `/crawl/{jobId}` | Situação do job e páginas já coletadas |
| `DELETE` | `/crawl/{jobId}` | Cancela um crawl em andamento |

## Exemplos de uso

Raspar uma página:

```bash
curl -s -X POST localhost:8080/scrape -H "Content-Type: application/json" -d '{
  "url": "https://example.com/produto",
  "selectors": {
    "titulo": { "css": "h1.product-title" },
    "preco":  { "css": "span.price" },
    "imagem": { "css": "img.main", "attribute": "src" },
    "tags":   { "css": "li.tag", "multiple": true }
  }
}'
```

Iniciar um crawl:

```bash
curl -s -X POST localhost:8080/crawl -H "Content-Type: application/json" -d '{
  "seedUrl": "https://example.com/",
  "maxDepth": 2,
  "maxPages": 20,
  "selectors": { "titulo": { "css": "h1" } }
}'
```

Acompanhar e cancelar:

```bash
curl -s localhost:8080/crawl/<jobId>
```

```bash
curl -s -X DELETE localhost:8080/crawl/<jobId>
```

## Uso responsável

Scraping tem limites legais e éticos, e eles não são detalhe de implementação:

- **Respeite o `robots.txt`** e os termos de uso do site. A aplicação obedece por padrão; `respect-robots-txt: false` existe para rastrear o próprio site, não o de terceiros.
- **Identifique-se.** O User-Agent padrão traz nome e URL de contato, para quem administra o site saber quem é e como pedir para parar.
- **Limite a taxa.** O padrão é uma requisição por segundo por host, e o `Crawl-delay` do site prevalece quando é maior.
- **Colete só o necessário.** LGPD e GDPR valem para dados raspados como para quaisquer outros.
- Conteúdo de terceiros costuma ser protegido por direito autoral: conseguir baixar não é o mesmo que poder republicar.

## Testes

```bash
./gradlew test
```

68 testes: 43 unitários — validação de endereços, normalização de URL, parser de `robots.txt` e rate limiter — e 25 de integração contra um site falso servido pelo WireMock. A suíte roda offline e não depende de Docker.
