# Idun

Idun godkjenner trygge refaktoreringer. Den vurderer PR-er der tittelen har conventional commit-typen `refactor`, kontrollerer at PR-en bare er refaktorering, og godkjenner den når den er liten og trygg. Ellers sier den fra at PR-en trenger et menneske, og forklarer hvorfor.

Idun **merger ikke**: den godkjenner, og et menneske trykker merge. Den gir heller ikke code review. Navnet kommer fra gudinnen som passer på eplene som holder gudene uforandret.

Hvis Idun er i tvil, går PR-en til et menneske. Den skal heller sende for mange PR-er til et menneske enn for få, og den blokkerer aldri merge.

## Hvordan den bestemmer

1. **Tittelen** må ha typen `refactor`. PR-er av andre typer ignoreres helt: ingen check, ingen kommentar, ingen label.
2. **Filreglene** (vanlig kode, ingen AI) stopper PR-er som er markert som breaking (`refactor!:`), kommer fra en fork, rører API-spec, databaseskjema, konfigurasjon, workflows eller bygg-filer, endrer tester eller andre filer enn kildekode, endrer for mange filer, eller har over 50 endrede linjer kildekode.
3. **CI** på head-commiten må være grønn.
4. **Gemini** (Vertex AI) leser diffen mot en sjekkliste for endret oppførsel. PR-en går bare videre når modellen svarer at oppførselen er bevart, alle punkter er besvart med «nei», og hver påstand har en kilde. Timeout, for stor input, ugyldig svar eller et brukt daglig kostnadstak sender PR-en til et menneske.

## Moduser

Modusen settes per repo med custom property-en `idun-mode` på `entur`-orgen. Den leses bare derfra, aldri fra filer i repoet, fordi en PR kan endre filer.

| `idun-mode` | Oppførsel |
|---|---|
| `off` (standard) | Gjør ingenting |
| `advise` | Check, kommentar og label, men ingen godkjenning |
| `approve` | Som `advise`, og godkjenner PR-er som vurderes som trygge |

## Hva du ser på en PR

- **Check run `idun/refactor`:** `success` når PR-en er vurdert som trygg, `neutral` når den trenger et menneske, og `in_progress` mens Idun venter på CI eller vurderer. Den blir aldri `failure`, så den blokkerer aldri merge.
- **Én kommentar**, som redigeres i stedet for at det skrives nye. Den har utfallet, hvilke regler som feilet eller modellens funn, og hva et menneske bør se på.
- **Label:** `idun:approved` eller `idun:needs-human`, aldri begge.
- **Godkjenning** (bare i `approve`): en review med `APPROVE` på akkurat den commiten som ble vurdert. En ny push trekker godkjenningen tilbake, og Idun vurderer den nye commiten fra bunnen. Endres tittelen bort fra `refactor`, trekkes godkjenningen tilbake og labelen fjernes.

## Feedback

Reager med 👍 eller 👎 på Iduns kommentar. Når PR-en lukkes, logger Idun utfallet og reaksjonene. Det er dette teamet bruker til å avgjøre når et repo kan gå fra `advise` til `approve`.

## Konfigurasjon per repo

Valgfritt, i `.github/idun.yaml`. Den leses fra **base-commiten** til PR-en, aldri fra head, og siden `.github/**` er beskyttet, går en PR som endrer filen alltid til et menneske. Uten fil gjelder standardverdiene.

Konfigurasjonen kan bare gjøre reglene strengere: linjegrensen kan senkes, men ikke økes over 50, og beskyttede stier og innholdsmønstre kan legges til, men ikke fjernes. Kildekode- og testmønstre kan bare snevres inn.

## Før du slår Idun på i et repo

- [ ] **Ruleset:** repoets rulesets krever én godkjenning, og ingen av dem krever code owner-review. Da skal godkjenningen fra GitHub-appen telle som den påkrevde reviewen.
- [ ] **Godkjenningen teller:** bekreft det i et sandbox-repo med de samme rulesetene før du setter `approve`. Bypass hjelper ikke, fordi bypass gjelder den som merger, og det er et menneske. Hvis godkjenningen ikke teller, ikke gi Idun mer makt: bestem på nytt hvordan rulesetene skal se ut.
- [ ] **Appen er installert** på repoet, og bare på repoer som skal vurderes (begrens installasjonen til valgte repoer).
- [ ] **CI** kjører på PR-er og rapporterer status på head-commiten, siden Idun venter på grønn CI.
- [ ] **Kildestier:** standardene er `**/src/main/**/*.kt` og `**/src/main/**/*.java` for kildekode og `**/src/test/**` m.fl. for tester. Avviker repoet, legg inn `.github/idun.yaml`.
- [ ] **PR-titler** følger conventional commits, så `refactor:` faktisk brukes for refaktoreringer.
- [ ] **Start i `advise`:** sett custom property-en `idun-mode=advise`, og følg vurderingene og tilbakemeldingene en stund.
- [ ] **Gå til `approve`** når teamet er enig i vurderingene.

## Drift og utvikling

Se [CONTRIBUTING.md](CONTRIBUTING.md) for å kjøre lokalt, legge inn hemmeligheter, deploy og eval. Idun er eid av `team-produkt` og kjører på Cloud Run i `ent-idun-prd`. Det er én GitHub-app, `entur-idun`, installert på `entur/idun-sandbox` (modus `approve`, for testing) og på `entur/products-api` (modus `advise`).
