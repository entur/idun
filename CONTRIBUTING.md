# Contributing

## Kom i gang

1. **Installer** JDK 25 (Temurin), Docker og Google Cloud CLI (for eval).
2. **Logg inn:** `gh auth login` og `gcloud auth login`.
3. **Sjekk at alt er grønt:** `./gradlew check`.

## Utvikling

| Kommando | Hva den gjør |
|---|---|
| `./gradlew check` | enhetstester, inkludert regeltesten av de syntetiske kanarifuglene. Trenger ikke nettverk. Det samme som CI-jobben Test |
| `./gradlew eval` | kjører kanarifuglene mot den **ekte** Gemini-modellen (under) |
| `./gradlew installDist` | bygger distribusjonen |
| `docker build -t idun:local .` | bygger imaget slik PR-bygget gjør, uten å pushe |

Hvis Gradle klager på `DOCKER_PATH`, prefiks kommandoen med `DOCKER_PATH="$(command -v docker)"`.

## Lag en endring

1. Lag en gren fra `main`, gjør endringen og skriv en test for hver endring i oppførsel.
2. Kjør `./gradlew check`.
3. Åpne en PR med en conventional commit-tittel. CI kjører tester, Docker-lint og -build.
4. Å merge deployer ingenting. `feat:` og `fix:` får Release Please til å åpne eller oppdatere en release-PR, mens `docs:`, `chore:` og lignende ikke gjør det. Å merge release-PR-en lager en release, men deployer ikke (se «Release og deploy»).

## Kjør lokalt

Idun er en vanlig HTTP-server med `GET /healthz` og webhook-endepunktet. Den leser konfigurasjonen fra miljøvariabler:

| Variabel | Påkrevd | Hva |
|---|---|---|
| `GITHUB_APP_ID` | ja | ID-en til GitHub-appen. Må stemme med `GET /app`, ellers starter Idun i redusert modus |
| `GITHUB_APP_SLUG` | ja | slug-en til GitHub-appen. Må stemme med `GET /app`, ellers starter Idun i redusert modus |
| `GITHUB_PRIVATE_KEY` | ja | privat nøkkel til appen (PEM) |
| `GITHUB_WEBHOOK_SECRET` | ja | webhook-hemmeligheten |
| `GCP_PROJECT` | ja | GCP-prosjektet Vertex AI kalles i, `ent-idun-prd` |
| `GEMINI_MODEL` | nei | modellen, standard `gemini-3.1-pro-preview` |
| `GEMINI_DAILY_USD` | nei | daglig kostnadstak, standard 20 |
| `GEMINI_PRICE_IN_PER_MTOK` | ja | listepris per million tokens inn, USD |
| `GEMINI_PRICE_OUT_PER_MTOK` | ja | listepris per million tokens ut, USD |
| `PORT` | nei | standard 8080 |

Vertex-kallene bruker dine egne Google-legitimasjon lokalt: `gcloud auth application-default login`. For å ta imot webhooks lokalt, videresend dem med for eksempel `gh webhook forward` eller smee.io.

## Kjør eval

`./gradlew eval` sender kanarifuglene i `eval/canaries` gjennom filreglene og, hvis de slipper gjennom, gjennom den ekte modellen. Den feiler hvis en kanarifugl med forventet utfall får et annet utfall, hvis reglene ikke gir grunnkodene i `rules` i `meta.json`, eller hvis modellen ikke svarer. Den koster penger, så den er bevisst ikke koblet til `check`.

Det finnes to slags kanarifugler:

- **Syntetiske** (`before/` og `after/` ligger i repoet). Regeltesten i `./gradlew check` sjekker dem uten nettverk.
- **Ekte PR-er** (`pr-*`). Bare `meta.json` ligger i repoet, med `source` (repo og PR-nummer) og `rules`. Kilderepoet (`entur/products-api`) er internt mens `entur/idun` er offentlig, så koden legges **aldri** inn her. Evalen henter innholdet før og etter med GitHub CLI (`gh pr view` og `gh api`, bare lesing) til en midlertidig mappe som slettes etterpå, og sjekker også at linjetallene fra diffen stemmer med GitHubs. Uten `gh`, eller uten `gh auth login` med lesetilgang til kilderepoet, rapporteres de som `SKIPPED`, og evalen feiler (exit-kode 1). En hoppet over kanarifugl er aldri bestått.

Hentingen og regelsjekken for de ekte PR-ene kan kjøres alene, uten modellen og uten kostnad:

```sh
IDUN_CANARY_FETCH=1 ./gradlew test --tests 'org.entur.idun.eval.CanaryFetcherTest'
```

For å legge til en ekte PR: lag `eval/canaries/pr-<nummer>/meta.json` med `title`, `expected`, `source` og `rules`, uten kildekode.

```sh
GCP_PROJECT=ent-idun-prd \
GEMINI_PRICE_IN_PER_MTOK=<usd> GEMINI_PRICE_OUT_PER_MTOK=<usd> \
./gradlew eval
```

Kjør den før du endrer prompten, modellen eller reglene, og før et repo settes til `approve`.

## Release og deploy

Dette repoet er offentlig og har verken infrastruktur eller GCP-tilgang. Infrastrukturen (Terraform), hemmelighetsoppsettet og deployen ligger i det interne repoet [`entur/idun-infra`](https://github.com/entur/idun-infra), siden Entur ikke legger infrastrukturkonfigurasjon i offentlige repoer. Idun kjører på Cloud Run i `ent-idun-prd`, og det finnes bare prd.

En ny versjon kommer ut i prd i tre steg:

1. **Release her:** merge release-PR-en som Release Please har åpnet. `release.yml` lager releasen, bygger imaget og skanner det, og legger det som en artefakt på kjøringen (ingenting pushes). Jobbsammendraget til kjøringen skriver ut **versjonen** og **run-ID-en**.
2. **Publiser i `entur/idun-infra`:** kjør workflowen **Publish image** på `main` med de to verdiene. Den pusher `eu.gcr.io/entur-system-1287/idun:<versjon>`. Artefakten lever i 4 dager; er den borte, kjør Docker Build-jobben i release-kjøringen på nytt.
3. **Deploy i `entur/idun-infra`:** en PR som bumper `idun_version` i `terraform/env/prd.tfvars`. Planen på PR-en er forhåndsvisningen; når PR-en er reviewet og merget, kjøres `terraform apply`.

| Hendelse | Hvor | Hva skjer |
|---|---|---|
| Merge til `main` | `release.yml` | Release Please åpner eller oppdaterer release-PR-en. Ingenting bygges |
| Release-PR merget | `release.yml` | Bygger og skanner imaget som artefakt, skriver ut versjon og run-ID |
| Publish image kjørt | `entur/idun-infra` | Pusher imaget med versjonstaggen. Ingenting deployes |
| PR merget i `entur/idun-infra` | `entur/idun-infra` | `terraform apply` mot prd |

- **Konfigurasjon** som `repo_modes`, prisene og `github_app_id` endres med en PR i `entur/idun-infra`, ikke her, og trenger ingen release.
- **Rollback:** en PR i `entur/idun-infra` som setter `idun_version` tilbake til en tidligere versjon. Imaget finnes allerede i registryet.
- **Første deploy og hemmeligheter:** se README-en i `entur/idun-infra`.
