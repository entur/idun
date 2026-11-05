# Idun

Idun godkjenner trygge refaktoreringer. Den vurderer PR-er der tittelen har conventional commit-typen `refactor`, kontrollerer at PR-en bare er refaktorering, og godkjenner den når den er liten og trygg. Ellers sier den fra at PR-en trenger et menneske, og forklarer hvorfor.

Idun **merger ikke**: den godkjenner, og et menneske trykker merge. Den gir heller ikke code review. Navnet kommer fra gudinnen som passer på eplene som holder gudene uforandret.

Hvis Idun er i tvil, går PR-en til et menneske. Den skal heller sende for mange PR-er til et menneske enn for få, og den blokkerer aldri merge.

## Hvordan den bestemmer

1. **Tittelen** må ha typen `refactor`. PR-er av andre typer ignoreres helt: ingen check, ingen kommentar, ingen label.
2. **Filreglene** (vanlig kode, ingen AI) stopper PR-er som er markert som breaking (`refactor!:`), kommer fra en fork, rører API-spec, databaseskjema, konfigurasjon, workflows eller bygg-filer, legger til eller endrer tester (en refactor-PR som rører tester går til et menneske), endrer andre filer enn kildekode, endrer for mange filer, eller har over 50 endrede linjer kildekode.
3. **CI** på head-commiten må være grønn.
4. **Gemini** (Vertex AI) leser diffen mot en sjekkliste for endret oppførsel. PR-en går bare videre når modellen svarer at oppførselen er bevart, alle punkter er besvart med «nei», og hver påstand har en kilde. Timeout, for stor input, ugyldig svar eller et brukt daglig kostnadstak sender PR-en til et menneske.

## Moduser

Modusen settes per repo i Iduns egen konfigurasjon: `repo_modes` i `terraform/env/prd.tfvars` i `entur/idun-infra` (internt), for eksempel `"entur/idun-sandbox=approve,entur/products-api=advise"`. Den endres med en PR i `entur/idun-infra`. Den leses aldri fra filer i repoet som vurderes, fordi en PR kan endre filer. Et repo som ikke er listet, er `off`, og en misformet verdi, også en ukjent modus som `aprove`, gjør alle repoer `off`. Rulles Idun ut i hele orgen, kan en custom property på orgen erstatte listen.

| Modus | Oppførsel |
|---|---|
| `off` (standard) | Gjør ingenting |
| `advise` | Check, kommentar og label, men ingen godkjenning |
| `approve` | Som `advise`, og godkjenner PR-er som vurderes som trygge |

Settes et repo til `off`, blir en godkjenning Idun allerede har gitt på gjeldende commit stående. Fjern den for hånd, eller push en ny commit.

## Hva du ser på en PR

- **Check run `idun/refactor`:** `success` når PR-en er vurdert som trygg, `neutral` når den trenger et menneske, og `in_progress` mens Idun venter på CI eller vurderer. Den blir aldri `failure`, så den blokkerer aldri merge. Checken hører til commiten, ikke PR-en, så to PR-er med samme head-commit deler den.
- **Én kommentar**, som redigeres i stedet for at det skrives nye. Den har utfallet, hvilke regler som feilet eller modellens funn, og hva et menneske bør se på.
- **Label:** `idun:approved` eller `idun:needs-human`, aldri begge.
- **Godkjenning** (bare i `approve`): en review med `APPROVE` på akkurat den commiten som ble vurdert. En ny push trekker godkjenningen tilbake, og Idun vurderer den nye commiten fra bunnen. Endres tittelen bort fra `refactor`, trekkes godkjenningen tilbake og labelen fjernes.
  - **Tilbaketrukket er tilbaketrukket:** Er en review fra Idun på en head-commit først trukket tilbake, godkjenner Idun aldri den commiten igjen. Det gjelder enten et menneske trakk den tilbake, eller Idun gjorde det selv fordi tittelen eller basen ble endret på samme commit. Da trengs en ny push eller en godkjenning fra et menneske.
  - **Ny modell eller ny prompt:** Når Idun deployes med en ny `GEMINI_MODEL` eller en høyere `PROMPT_VERSION`, endres fingeravtrykket. Da vurderer Idun åpne PR-er på nytt ved neste hendelse, og godkjenningene de allerede har, trekkes tilbake og gis ikke igjen før en ny push.

## Feedback

Reager med 👍 eller 👎 på Iduns kommentar. Når PR-en lukkes, logger Idun utfallet og reaksjonene. Det er dette teamet bruker til å avgjøre når et repo kan gå fra `advise` til `approve`.

## Konfigurasjon per repo

Valgfritt, i `.github/idun.yaml`. Den leses fra **base-commiten** til PR-en, aldri fra head, og siden `.github/**` er beskyttet, går en PR som endrer filen alltid til et menneske. Uten fil gjelder standardverdiene.

Konfigurasjonen kan bare gjøre reglene strengere: linjegrensen kan senkes, men ikke økes over 50, og beskyttede stier og innholdsmønstre kan legges til, men ikke fjernes. Kildekode- og testmønstre kan bare snevres inn.

## Før du slår Idun på i et repo

- [ ] **Ruleset:** repoets rulesets krever én godkjenning, og ingen av dem krever code owner-review. Da skal godkjenningen fra GitHub-appen telle som den påkrevde reviewen.
- [ ] **Påkrevd: «Dismiss stale pull request approvals when new commits are pushed»** er slått på i rulesetet som krever godkjenning. Det er sikkerhetsnettet når Idun er av, i redusert modus eller feiler, og ikke rekker å trekke en godkjenning selv etter en ny push. Uten dette skal repoet ikke stå i `approve`.
- [ ] **Godkjenningen teller:** bekreft det i et sandbox-repo med de samme rulesetene før du setter `approve`. Bypass hjelper ikke, fordi bypass gjelder den som merger, og det er et menneske. Hvis godkjenningen ikke teller, ikke gi Idun mer makt: bestem på nytt hvordan rulesetene skal se ut.
- [ ] **Appen er installert** på repoet, og bare på repoer som skal vurderes (begrens installasjonen til valgte repoer).
- [ ] **CI** kjører på PR-er og rapporterer status på head-commiten, siden Idun venter på grønn CI.
- [ ] **Kildestier:** standardene er `**/src/main/**/*.kt` og `**/src/main/**/*.java` for kildekode og `**/src/test/**` m.fl. for tester. Avviker repoet, legg inn `.github/idun.yaml`.
- [ ] **PR-titler** følger conventional commits, så `refactor:` faktisk brukes for refaktoreringer.
- [ ] **Start i `advise`:** legg til `owner/repo=advise` i `repo_modes` i `terraform/env/prd.tfvars` i `entur/idun-infra` (en PR der), og følg vurderingene og tilbakemeldingene en stund.
- [ ] **Gå til `approve`** når teamet er enig i vurderingene, på samme måte (`owner/repo=approve`).

## Prøv lokalt (tørrkjøring)

Du kan la Idun vurdere en ekte PR fra laptopen, uten Cloud Run, uten GitHub-app og uten at noe skrives til GitHub. Check, kommentar, label og godkjenning skrives bare ut i terminalen, med `[tørrkjøring]` foran.

Forutsetninger:

- `gh auth login` (tokenet hentes med `gh auth token`, eller sett `GITHUB_TOKEN`).
- `gcloud auth application-default login`.
- Et GCP-prosjekt der du har `roles/aiplatform.user` og Vertex AI-API-et er slått på.

Kjør:

```sh
GCP_PROJECT=<prosjekt> ./gradlew assess --args="entur/idun-sandbox <pr>"
```

Modusen er `approve` som standard, så utskriften viser om Idun ville godkjent. Bruk `--mode advise` for å se advise-oppførselen. Valgfritt: `GEMINI_MODEL`, og `GEMINI_PRICE_IN_PER_MTOK`/`GEMINI_PRICE_OUT_PER_MTOK` for at kostnaden skal regnes ut (ellers er den ukjent). Kjøringen har et lokalt kostnadstak på 5 USD. Exit-kode er ulik 0 hvis vurderingen endte i Iduns feilsti.

Tørrkjøringen leser PR-en, filene, CI-status og Gemini-svaret på ekte, så den tester reglene, CI-vurderingen og modellen. Den tester **ikke**:

- om en godkjenning fra appen teller for branch protection (ingenting godkjennes på ekte),
- webhooks, signaturer eller flyten i sanntid (`check_suite`, `status`, låser og gjenbruk av tidligere vurderinger), siden hver kjøring er en ny vurdering uten tidligere kommentarer eller checks fra Idun.

## Kjør som GitHub Action (demo)

For demoen i `entur/idun-sandbox` kan Idun kjøre som en workflow i repoet i stedet for som GitHub-app på Cloud Run. Det er samme vurdering som appen gjør (regler, CI-status, Gemini), men den skrives til GitHub på ekte med `assess --write`: check `idun/refactor`, kommentar, label og, i `approve`, godkjenning. Uten `--write` er `assess` fortsatt bare en tørrkjøring.

Den skriver med workflowens `GITHUB_TOKEN`, altså som `github-actions[bot]` (app-id 15368). `--write` krever `GITHUB_TOKEN` og henter aldri token fra `gh auth token`, så ingen skriver som seg selv ved et uhell. Med `--write` er modusen `advise` som standard; godkjenning må bes om med `--mode approve`. Exit-kodene er de samme som i tørrkjøringen, og kostnadstaket er 5 USD per kjøring.

Siden repoets egen CI også kjører som GitHub Actions (samme app-id), kjenner Idun igjen sin egen check på app-id **og** navnet `idun/refactor`, ikke på app-id alene. Resten av CI-en fra Actions teller som vanlig.

Oppsett i repoet:

- **Godkjenning:** Settings → Actions → General → «Allow GitHub Actions to create and approve pull requests» må være på, ellers avviser GitHub godkjenningen (`--mode approve`).
- **GCP:** workflowen logger inn i GCP med GitHub Factory / Workload Identity Federation (`entur/gha-meta/.github/actions/cloud-auth`). Repoet trenger variablene `WORKLOAD_IDENTITY_PROVIDER` og `SERVICE_ACCOUNT` (og `GCP_PROJECT`), og service-kontoen trenger `roles/aiplatform.user` i prosjektet.
- **Hvilken PR:** workflowen startes av `workflow_run` når repoets CI-workflow er ferdig, og PR-nummeret hentes fra `github.event.workflow_run.pull_requests[0].number`. Listen er tom for PR-er fra forks, og da hopper workflowen over. `workflow_run` virker bare når workflow-filen ligger på default-branchen.

Begrensninger:

- Det er ikke en GitHub-app. Det er ingen webhooks: Idun vurderer bare når CI-workflowen er ferdig, ikke når tittelen endres eller en status kommer. Har repoet flere CI-workflows, gir de som ikke er ferdige `Waiting`, og neste ferdige workflow starter en ny vurdering.
- Kommentarer og reviews kjennes igjen på `github-actions[bot]`. Andre workflows i repoet som kommenterer eller godkjenner som `github-actions[bot]`, kan forveksles med Iduns (for eksempel kan Idun trekke tilbake en slik godkjenning). Greit for sandkassen, ikke for andre repoer.
- Den tester ikke om en godkjenning fra appen `entur-idun` teller for branch protection; en godkjenning fra `github-actions[bot]` er ikke det samme.
- En Actions-suite på PR-ens head som ikke er ferdig gir alltid `Waiting`, også om den bare venter på Iduns egen check (feiler lukket). Idun-workflowens egen jobb kjører på default-branchens sha og påvirker ikke CI-vurderingen av PR-en.

Eksempel, `.github/workflows/idun.yml` (bytt `CI` med navnet på repoets CI-workflow og `<ref>` med en tag eller commit i `entur/idun`):

```yaml
name: Idun

on:
  workflow_run:
    workflows: [CI]
    types: [completed]

permissions:
  checks: write
  pull-requests: write
  issues: write
  contents: read
  actions: read
  statuses: read
  id-token: write

jobs:
  assess:
    # Bare PR-er i repoet selv: for fork-PR-er er pull_requests tom.
    if: github.event.workflow_run.event == 'pull_request' && toJSON(github.event.workflow_run.pull_requests) != '[]'
    runs-on: ubuntu-24.04
    steps:
      - uses: actions/checkout@v4
        with:
          repository: entur/idun
          ref: <ref>
          persist-credentials: false
      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: "25"
      - uses: gradle/actions/setup-gradle@v4
      - uses: entur/gha-meta/.github/actions/cloud-auth@v1
        with:
          environment: prd
          cloud_provider: gcp
          gcp_workload_identity_provider: ${{ vars.WORKLOAD_IDENTITY_PROVIDER }}
          gcp_service_account: ${{ vars.SERVICE_ACCOUNT }}
      - name: Vurder PR-en
        env:
          GITHUB_TOKEN: ${{ secrets.GITHUB_TOKEN }}
          GCP_PROJECT: ${{ vars.GCP_PROJECT }}
          PR_NUMBER: ${{ github.event.workflow_run.pull_requests[0].number }}
        run: ./gradlew assess --args="${{ github.repository }} $PR_NUMBER --write --mode approve"
```

## Drift og utvikling

Se [CONTRIBUTING.md](CONTRIBUTING.md) for å kjøre lokalt, release og eval. Infrastrukturen, hemmelighetene og deployen ligger i det interne repoet `entur/idun-infra`, siden Entur ikke legger infrastrukturkonfigurasjon i offentlige repoer. Idun er eid av `team-produkt` og kjører på Cloud Run i `ent-idun-prd`. Det er én GitHub-app, `entur-idun`, installert på `entur/idun-sandbox` (modus `approve`, for testing) og på `entur/products-api` (modus `advise` når den legges til i `repo_modes`).
