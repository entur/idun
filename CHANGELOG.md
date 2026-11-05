# Changelog

## [0.2.0](https://github.com/entur/idun/compare/v0.1.0...v0.2.0) (2026-11-05)


### Features

* add canary eval with offline rule test ([b52842e](https://github.com/entur/idun/commit/b52842e024f7b3f5e7b13859cd8475fe8beef5f0))
* add pure decision functions ([22b722f](https://github.com/entur/idun/commit/22b722fba98615f5b2dc16e84a7fb2abd3309bb9))
* AI review with Gemini that fails closed ([f1a143c](https://github.com/entur/idun/commit/f1a143c4e10ab58d646647112b327fc3c6025392))
* assess pull requests from webhook events ([370e8e5](https://github.com/entur/idun/commit/370e8e5f3f11020aaf05818fca67813be0936aba))
* evaluate CI status of head commit, fail closed ([a20562d](https://github.com/entur/idun/commit/a20562d5b52a1089f3d7dd16ca1cb15005d8dec8))
* file rules classify changed files and evaluate eligibility ([f194965](https://github.com/entur/idun/commit/f1949651bfdd942dbd206df07947af2c85f20990))
* Idun, en bot som godkjenner trygge refaktoreringer (PoC) ([283b671](https://github.com/entur/idun/commit/283b671f8bc15da622c0b2fbc398c17172a018ae))
* project skeleton with /healthz endpoint ([0c2d8a7](https://github.com/entur/idun/commit/0c2d8a73833cbbc2898e30ef46f0d9bbb35b682e))
* refactor PRs must not add test files ([3389c9e](https://github.com/entur/idun/commit/3389c9e5f88e76fc809ad5d3816c5583d714bf45))
* release, deploy and infrastructure as in freyja ([0e34ce2](https://github.com/entur/idun/commit/0e34ce242fd13c5ef47cd628093f3e8ae496c3af))
* repo modes from IDUN_REPO_MODES instead of custom property ([26f17cf](https://github.com/entur/idun/commit/26f17cf12872bb2b7f08cea30e4748250f136575))
* report check, comment, label and approval with defused text ([219862a](https://github.com/entur/idun/commit/219862a63b7647e4962f56d3916d710583be1c2d))
* title parsing, glob matching and config with narrowing semantics ([e3f568c](https://github.com/entur/idun/commit/e3f568c6ba3ba97cafd632f214a54baa988bb5f5))
* webhook signature, app authentication and GitHub client ([49f972a](https://github.com/entur/idun/commit/49f972ae800eb93fed31f5e71e0e98a8218e5e17))


### Bug Fixes

* add decideBeforeModel as the only go-ahead for the model call ([d5cce5f](https://github.com/entur/idun/commit/d5cce5f97ecb20534bd8a9bdccfeb4f19ce99988))
* app identity from GET /app at startup ([4ec8603](https://github.com/entur/idun/commit/4ec860328a4c2fde7b8f33c5648f1a6fdb2a2a8d))
* approval only stands for Safe in approve, reuse keyed on review fingerprint ([c8adbda](https://github.com/entur/idun/commit/c8adbdae0f9251bd211bdcd47a8c198b04fa6139))
* changed files from the pinned compare API ([13bb90a](https://github.com/entur/idun/commit/13bb90a2abcdc1687259c0e631513a75a4c3cb74))
* control characters in paths are OTHER and stripped from the title ([db19197](https://github.com/entur/idun/commit/db191976acaa9ae5fa3916944c234e77f7cff122))
* defuse cuts input before regex to avoid quadratic link matching ([bfe76cc](https://github.com/entur/idun/commit/bfe76ccd250caadb0e46d94dd32d53dc3537c33e))
* defuse link removal no longer depends on word boundary ([e31ddca](https://github.com/entur/idun/commit/e31ddca11ec27271a624dac4d3d77b3df44e0417))
* defuse removes any-scheme and www links case-insensitively ([54f89c6](https://github.com/entur/idun/commit/54f89c66b974edf7f2518fe997ec07fe9b5f752a))
* dismissApprovals attempts every approval before failing ([0900c05](https://github.com/entur/idun/commit/0900c0521a80d9efa93f7f23493f4b61c7d7106b))
* feedback only on close, leave only PRs Idun commented on ([5d098d6](https://github.com/entur/idun/commit/5d098d632eb40a31dc5b275997aa84b15e039cdc))
* fetch real-PR canaries with gh at eval time instead of copying them ([7407698](https://github.com/entur/idun/commit/74076983a5a23101d643b8253c26d6286206137f))
* glob matches line terminators, qualified Exposed tables, correct config file name ([78b41a0](https://github.com/entur/idun/commit/78b41a075b743c3d35ed1ee5d1c1273de39b7cf1))
* KtorGitHub requires appId and only updates its own check run ([3464da8](https://github.com/entur/idun/commit/3464da8c7ca99c1f620910c1a2f40327f50e4cc7))
* never log the model's text when its answer cannot be parsed ([7f5f2b2](https://github.com/entur/idun/commit/7f5f2b259ae8498f23185208d26fab2b653e693d))
* never re-approve a commit after its approval was dismissed ([71f0466](https://github.com/entur/idun/commit/71f0466871deda47f4ea9b16e0798770e7d706a6))
* no approval for a diff the model has not accepted ([582b886](https://github.com/entur/idun/commit/582b8867ab5aede8586308444b395136f6ae442a))
* no source files after the rules needs a human ([f3138c7](https://github.com/entur/idun/commit/f3138c7b31a451256412b1a80ed549f760f726d8))
* only Kotlin and Java files count as new tests ([19c83ad](https://github.com/entur/idun/commit/19c83adc99168d06deaa381f7e7e5fdb76916bea))
* path matching both a test and a source glob is OTHER ([31b9a75](https://github.com/entur/idun/commit/31b9a75cd3ba26c09329336357d496d65ef3f851))
* protect buildSrc and build-logic ([7cb03b9](https://github.com/entur/idun/commit/7cb03b9bb120d4a369855ccc4ab6e013cf03a8b9))
* review fingerprint includes model and prompt version ([5ad1053](https://github.com/entur/idun/commit/5ad1053f06bc4ba5ce09dc0ba5430fa7c39b1bee))
* Safe check summary starts with fixed text ([0e99813](https://github.com/entur/idun/commit/0e99813f3f0a67fc245f4dddc45ea78e1383f97a))
* validate X-GitHub-Delivery before logging, 1 MB webhook cap ([099bae0](https://github.com/entur/idun/commit/099bae09715cf736aad52f152132aa609ba8a70a))


### Documentation

* dismissed approvals are never renewed on the same commit; model or prompt change re-reviews ([a77e85d](https://github.com/entur/idun/commit/a77e85d628f2125a3ac29a220f879c9e2b1fe822))
* tfvars changes need the fix PR and the release PR; dispatch applies main HEAD ([0169b24](https://github.com/entur/idun/commit/0169b24f49733da2103b3b592e09e917c13b70a3))


### Code Refactoring

* move infrastructure and deploy to entur/idun-infra ([218fe90](https://github.com/entur/idun/commit/218fe9000fd1472c01c5cc42677f690e7b77a889))
* remove unused customProperty, labels and writeCheck ([1e0c2c9](https://github.com/entur/idun/commit/1e0c2c9c83f7f73e51f94f882aa532c89cc11ab7))


### Miscellaneous Chores

* initial commit ([73f5d01](https://github.com/entur/idun/commit/73f5d019a5df2cf38b9b0394fc30d85fe86ce36c))


### Tests

* no real Vertex client in DependenciesTest ([9e7f6e3](https://github.com/entur/idun/commit/9e7f6e3cc10af7badae2292c87272bb12d013837))
* stillReviewed computes the merge base from the new baseSha ([55628fd](https://github.com/entur/idun/commit/55628fd4a0ee6e6fa71334eaad6fb9359c67b87d))
