# Contributing

## Kom i gang

1. **Installer** JDK 25 (Temurin), Docker og, for infrastrukturen, Terraform og Google Cloud CLI.
2. **Logg inn:** `gh auth login` og `gcloud auth login`.
3. **Sjekk at alt er grønt:** `./gradlew check`.

## Utvikling

| Kommando | Hva den gjør |
|---|---|
| `./gradlew check` | enhetstester, inkludert regeltesten av de syntetiske kanarifuglene. Trenger ikke nettverk. Det samme som CI-jobben Test |
| `./gradlew eval` | kjører kanarifuglene mot den **ekte** Gemini-modellen (under) |
| `./gradlew installDist` | bygger distribusjonen |
| `docker build -t idun:local .` | bygger imaget slik PR-bygget gjør, uten å pushe |
| `terraform -chdir=terraform init -backend=false && terraform -chdir=terraform validate` | sjekker Terraform uten backend |

Hvis Gradle klager på `DOCKER_PATH`, prefiks kommandoen med `DOCKER_PATH="$(command -v docker)"`.

## Lag en endring

1. Lag en gren fra `main`, gjør endringen og skriv en test for hver endring i oppførsel.
2. Kjør `./gradlew check`.
3. Åpne en PR med en conventional commit-tittel. CI kjører tester, Docker-lint og -build, Terraform-lint og Terraform-plan.
4. Å merge deployer ingenting. `feat:` og `fix:` får Release Please til å åpne eller oppdatere en release-PR, mens `docs:`, `chore:` og lignende ikke gjør det. Å merge release-PR-en deployer (se «Deploy»).

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

## Deploy

Idun kjører på Cloud Run i `ent-idun-prd`. Det finnes bare prd: ingen dev-app og ikke noe dev-prosjekt. Terraform i `terraform/` eier alt: hemmeligheter, Cloud Run-tjenesten og den offentlige tilgangen. GCP-prosjektet og Terraform-backenden lages av Platform Orchestrator fra `.entur/idun.yaml` og `.entur/cicd.yaml`.

**En release er den eneste veien inn i prd.** Ingenting annet pusher et image eller kjører Terraform.

| Hendelse | Workflow | Hva skjer |
|---|---|---|
| Merge til `main` | `release.yml` | Release Please åpner eller oppdaterer release-PR-en. Ingenting deployes |
| Release-PR merget | `release.yml` → `deploy.yml` | Bygger, skanner og pusher `eu.gcr.io/entur-system-1287/idun:<versjon>`, kjører `terraform apply` |
| Manuell kjøring av Release på `main` | `release.yml` → `deploy.yml` | Kjører `terraform apply` med Terraform fra `main` HEAD og imaget til den gjeldende releasen, uten å bygge. Bruk den etter en mislykket apply. En kjøring fra en annen gren deployer ingenting |

- **Release-PR-ens plan er forhåndsvisningen.** Den bumper `version.txt`, så Terraform-planen viser den nye image-taggen og alle infrastrukturendringer siden forrige release.
- **Rollback:** tilbakestill `version.txt` og lag en ny release.
- **Infrastrukturendringer venter til neste release.** Bruk `fix:` for en Terraform-endring som skal ut alene, siden `chore:` ikke utløser en release.
- **Endre `terraform/env/prd.tfvars` (for eksempel `repo_modes`) krever to merger:** først PR-en med `fix:`-tittel, deretter release-PR-en som Release Please da åpner eller oppdaterer. Endringen er ikke i prd før release-PR-en er merget.
- **En manuell kjøring av Release bruker Terraform fra `main` HEAD**, ikke fra den forrige releasen. Alt som er merget til `main` siden forrige release, også tfvars og andre Terraform-endringer, blir applyet, mens imaget fortsatt er den gjeldende releasen.

### GitHub-miljø (engangsoppsett)

I repo-innstillingene, under Environments, lag miljøet `prd`. `gha-terraform` bruker det til å hente GCP-innloggingen. Som i freyja er det ingen ekstra godkjenningsregel utover det.

### Første deploy

Tjenesten deployes før noen hemmeligheter finnes. Terraform lager en plassholder-versjon (`unset`) for hver hemmelighet, og Idun starter i **redusert modus**: den svarer ikke på webhooks, i stedet for at deployen feiler.

1. **Deploy:** merge release-PR-en. Apply lager hemmelighetene, plassholderne og tjenesten.
2. **Opprett GitHub-appen** `entur-idun` med tillatelsene og hendelsene fra spec-en. Sett webhook-URL-en til Terraform-outputen `webhook_url`, som står i apply-loggen. Du kan også hente den slik:
   ```sh
   P=ent-idun-prd
   echo "$(gcloud run services describe idun --project $P --region europe-west1 --format 'value(status.url)')/webhook"
   ```
3. **Legg inn de ekte hemmelighetene** (verdiene havner aldri i Terraform eller git):
   ```sh
   P=ent-idun-prd
   gcloud secrets versions add idun-github-app-private-key --project $P --data-file=idun.private-key.pem
   openssl rand -hex 32 | tee /dev/tty | gcloud secrets versions add idun-github-webhook-secret --project $P --data-file=-
   ```
   Lim inn det utskrevne webhook-hemmeligheten i appen også.
4. **Sett `github_app_id` og `github_app_slug`** i `terraform/env/prd.tfvars`, merge PR-en med en `fix:`-tittel og merge deretter release-PR-en. Det lager en ny revisjon som leser de siste hemmelighetsversjonene. Instanser som starter senere gjør det samme, siden Cloud Run løser `latest` når en instans starter.
5. **Aktiver webhooken** på appen og installer den på `entur/idun-sandbox` og `entur/products-api`. Modusen per repo står i `repo_modes` i `terraform/env/prd.tfvars` (`entur/idun-sandbox=approve` for testing; `entur/products-api=advise` legges til senere) og endres med en `fix:`-PR og deretter release-PR-en. Et repo som ikke er listet, er `off`.
6. **Verifiser** at en `refactor:`-PR i sandbox-repoet får check, kommentar og label.

Før du bytter hemmelighetene: `GEMINI_PRICE_IN_PER_MTOK` og `GEMINI_PRICE_OUT_PER_MTOK` i `terraform/env/prd.tfvars` må bekreftes mot gjeldende Vertex-listepriser. Idun regner kostnaden mot det daglige taket ut fra dem.
