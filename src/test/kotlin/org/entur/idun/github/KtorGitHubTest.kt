package org.entur.idun.github

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.entur.idun.rules.ChangedFile
import org.entur.idun.rules.FileStatus
import org.junit.jupiter.api.Test
import java.util.Base64

class KtorGitHubTest {
    private val repo = RepoRef("entur", "products-api")

    private fun gh(handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData): KtorGitHub =
        KtorGitHub(githubHttpClient(MockEngine(handler)), "tok")

    private fun MockRequestHandleScope.json(
        body: String,
        status: HttpStatusCode = HttpStatusCode.OK,
        vararg extra: Pair<String, String>,
    ): HttpResponseData =
        respond(
            body,
            status,
            headersOf(HttpHeaders.ContentType, "application/json").let { base ->
                val all = base.entries().associate { it.key to it.value }.toMutableMap()
                extra.forEach { (k, v) -> all[k] = listOf(v) }
                io.ktor.http.Headers.build { all.forEach { (k, v) -> appendAll(k, v) } }
            },
        )

    private fun HttpRequestData.bodyJson(): JsonObject =
        Json.parseToJsonElement((body as TextContent).text) as JsonObject

    // --- paginering og fil-mapping ---

    @Test
    fun `pullRequestFiles foelger Link-header over to sider og mapper rename`() =
        runBlocking<Unit> {
            val requested = mutableListOf<String>()
            val github =
                gh { req ->
                    requested += req.url.toString()
                    when (req.url.parameters["page"]) {
                        null ->
                            json(
                                """[{"filename":"a/New.kt","previous_filename":"a/Old.kt","status":"renamed","additions":1,"deletions":2,"patch":"@@ x"}]""",
                                HttpStatusCode.OK,
                                "Link" to
                                    """<https://api.github.com/repos/entur/products-api/pulls/5/files?per_page=100&page=2>; rel="next", <https://api.github.com/repos/entur/products-api/pulls/5/files?per_page=100&page=2>; rel="last"""",
                            )
                        "2" -> json("""[{"filename":"b.kt","status":"added","additions":3,"deletions":0}]""")
                        else -> error("uventet side")
                    }
                }
            github.pullRequestFiles(repo, 5) shouldContainExactly
                listOf(
                    ChangedFile("a/New.kt", "a/Old.kt", FileStatus.RENAMED, 1, 2, "@@ x"),
                    ChangedFile("b.kt", null, FileStatus.ADDED, 3, 0, null),
                )
            requested.size shouldBe 2
        }

    @Test
    fun `ukjent filstatus kaster`() {
        val github = gh { json("""[{"filename":"a.kt","status":"teleported","additions":0,"deletions":0}]""") }
        shouldThrow<Exception> { runBlocking { github.pullRequestFiles(repo, 5) } }
    }

    @Test
    fun `alle kjente filstatuser mappes`() =
        runBlocking<Unit> {
            val statuses = listOf("added", "modified", "removed", "renamed", "copied", "changed", "unchanged")
            val body =
                statuses.joinToString(",", "[", "]") {
                    """{"filename":"$it.kt","status":"$it","additions":0,"deletions":0}"""
                }
            val github = gh { json(body) }
            github.pullRequestFiles(repo, 1).map { it.status } shouldContainExactly
                listOf(
                    FileStatus.ADDED,
                    FileStatus.MODIFIED,
                    FileStatus.REMOVED,
                    FileStatus.RENAMED,
                    FileStatus.COPIED,
                    FileStatus.CHANGED,
                    FileStatus.UNCHANGED,
                )
        }

    @Test
    fun `Link til annen vert avvises saa tokenet ikke lekker`() {
        val github =
            gh {
                json("[]", HttpStatusCode.OK, "Link" to """<https://evil.example/x?page=2>; rel="next"""")
            }
        shouldThrow<Exception> { runBlocking { github.pullRequestFiles(repo, 5) } }
    }

    @Test
    fun `ikke-2xx paa liste kaster i stedet for tom liste`() {
        val github = gh { json("""{"message":"boom"}""", HttpStatusCode.InternalServerError) }
        val e = shouldThrow<GitHubApiException> { runBlocking { github.pullRequestFiles(repo, 5) } }
        e.status shouldBe 500
        shouldThrow<GitHubApiException> { runBlocking { gh { json("{}", HttpStatusCode.NotFound) }.labels(repo, 1) } }
        shouldThrow<GitHubApiException> { runBlocking { gh { json("{}", HttpStatusCode.Forbidden) }.comments(repo, 1) } }
    }

    @Test
    fun `sender riktige headere`() =
        runBlocking<Unit> {
            var seen: HttpRequestData? = null
            val github =
                gh { req ->
                    seen = req
                    json("[]")
                }
            github.labels(repo, 1)
            seen!!.headers[HttpHeaders.Authorization] shouldBe "Bearer tok"
            seen.headers[HttpHeaders.Accept] shouldBe "application/vnd.github+json"
            seen.headers["X-GitHub-Api-Version"] shouldBe "2022-11-28"
        }

    // --- pull request ---

    @Test
    fun `pullRequest mapper felter`() =
        runBlocking<Unit> {
            val github =
                gh { req ->
                    req.url.encodedPath shouldBe "/repos/entur/products-api/pulls/9"
                    json(
                        """{"number":9,"title":"refactor: x","draft":false,"state":"open","merged":false,
                           "head":{"sha":"h1","repo":{"id":11}},"base":{"sha":"b1","repo":{"id":22}},"changed_files":4,"extra":1}""",
                    )
                }
            github.pullRequest(repo, 9) shouldBe
                PullRequest(9, "refactor: x", false, "open", false, "h1", "b1", 11L, 22L, 4)
        }

    @Test
    fun `pullRequest med slettet head-repo gir null headRepoId`() =
        runBlocking<Unit> {
            val github =
                gh {
                    json(
                        """{"number":9,"title":"t","draft":true,"state":"open","merged":false,
                           "head":{"sha":"h1","repo":null},"base":{"sha":"b1","repo":{"id":22}},"changed_files":0}""",
                    )
                }
            github.pullRequest(repo, 9).headRepoId shouldBe null
        }

    @Test
    fun `pullRequestsForCommit gir nummer og mergeBase gir sha`() =
        runBlocking<Unit> {
            val github =
                gh { req ->
                    when {
                        req.url.encodedPath.endsWith("/commits/abc/pulls") -> json("""[{"number":3},{"number":4}]""")
                        req.url.encodedPath.endsWith("/compare/main...feat") ->
                            json("""{"merge_base_commit":{"sha":"mb"}}""")
                        else -> error(req.url.toString())
                    }
                }
            github.pullRequestsForCommit(repo, "abc") shouldContainExactly listOf(3, 4)
            github.mergeBase(repo, "main", "feat") shouldBe "mb"
        }

    // --- fileContent ---

    @Test
    fun `fileContent base64-dekoder innhold med linjeskift og sender ref`() =
        runBlocking<Unit> {
            val text = "package a\n\nclass A { val æøå = 1 }\n"
            val b64 = Base64.getMimeEncoder(10, "\n".toByteArray()).encodeToString(text.toByteArray())
            var seen: HttpRequestData? = null
            val github =
                gh { req ->
                    seen = req
                    json(buildJsonObject {
                        put("type", "file")
                        put("encoding", "base64")
                        put("content", b64)
                    }.toString())
                }
            github.fileContent(repo, "src/main/A.kt", "deadbeef") shouldBe text
            seen!!.url.encodedPath shouldBe "/repos/entur/products-api/contents/src/main/A.kt"
            seen.url.parameters["ref"] shouldBe "deadbeef"
        }

    @Test
    fun `fileContent gir null ved 404`() =
        runBlocking<Unit> {
            val github = gh { json("""{"message":"Not Found"}""", HttpStatusCode.NotFound) }
            github.fileContent(repo, "x.kt", "sha") shouldBe null
        }

    @Test
    fun `fileContent kaster ved alle andre feil enn 404`() {
        for (status in listOf(HttpStatusCode.Forbidden, HttpStatusCode.InternalServerError, HttpStatusCode.BadGateway, HttpStatusCode.Unauthorized, HttpStatusCode.UnprocessableEntity)) {
            val github = gh { json("""{"message":"nei"}""", status) }
            shouldThrow<GitHubApiException> { runBlocking { github.fileContent(repo, "x.kt", "sha") } }
        }
    }

    @Test
    fun `fileContent kaster for katalog, manglende content, ukjent encoding og ikke-fil`() {
        val bodies =
            listOf(
                """[{"type":"file","name":"a"}]""",
                """{"type":"file","encoding":"base64"}""",
                """{"type":"file","encoding":"none","content":""}""",
                """{"type":"file","encoding":"base64","content":"!!!ikke base64!!!"}""",
                """{"type":"symlink","encoding":"base64","content":"YQ=="}""",
                """{"type":"submodule"}""",
            )
        for (body in bodies) {
            val github = gh { json(body) }
            shouldThrow<Exception> { runBlocking { github.fileContent(repo, "x.kt", "sha") } }
        }
    }

    @Test
    fun `fileContent kaster for innhold som ikke er gyldig UTF-8`() {
        val b64 = Base64.getEncoder().encodeToString(byteArrayOf(0xC3.toByte(), 0x28))
        val github = gh { json("""{"type":"file","encoding":"base64","content":"$b64"}""") }
        shouldThrow<Exception> { runBlocking { github.fileContent(repo, "x.bin", "sha") } }
    }

    // --- custom properties ---

    @Test
    fun `customProperty plukker idun-mode`() =
        runBlocking<Unit> {
            val github =
                gh { req ->
                    req.url.encodedPath shouldBe "/repos/entur/products-api/properties/values"
                    json("""[{"property_name":"team","value":"x"},{"property_name":"idun-mode","value":"auto"}]""")
                }
            github.customProperty(repo, "idun-mode") shouldBe "auto"
        }

    @Test
    fun `customProperty gir null for manglende eller null-verdi`() =
        runBlocking<Unit> {
            gh { json("""[{"property_name":"team","value":"x"}]""") }.customProperty(repo, "idun-mode") shouldBe null
            gh { json("""[{"property_name":"idun-mode","value":null}]""") }.customProperty(repo, "idun-mode") shouldBe null
        }

    @Test
    fun `customProperty kaster paa feil og uventet verdiform`() {
        shouldThrow<GitHubApiException> {
            runBlocking { gh { json("{}", HttpStatusCode.NotFound) }.customProperty(repo, "idun-mode") }
        }
        shouldThrow<Exception> {
            runBlocking<Unit> {
                gh { json("""[{"property_name":"idun-mode","value":["a","b"]}]""") }.customProperty(repo, "idun-mode")
            }
        }
    }

    // --- checks og statuser ---

    @Test
    fun `checkRuns, checkSuites, statuses og workflowRuns mappes`() =
        runBlocking<Unit> {
            val github =
                gh { req ->
                    val p = req.url.encodedPath
                    when {
                        p.endsWith("/commits/s1/check-runs") ->
                            json(
                                """{"total_count":1,"check_runs":[{"id":1,"name":"idun","status":"completed","conclusion":"success",
                                   "check_suite":{"id":10},"app":{"id":99},"external_id":"x"}]}""",
                            )
                        p.endsWith("/commits/s1/check-suites") ->
                            json("""{"total_count":1,"check_suites":[{"id":10,"status":"completed","app":{"id":99}}]}""")
                        p.endsWith("/commits/s1/status") ->
                            json("""{"state":"success","statuses":[{"context":"ci","state":"success"}]}""")
                        p.endsWith("/actions/runs") -> {
                            req.url.parameters["head_sha"] shouldBe "s1"
                            json("""{"total_count":1,"workflow_runs":[{"id":5,"workflow_id":6,"check_suite_id":10}]}""")
                        }
                        else -> error(req.url.toString())
                    }
                }
            github.checkRuns(repo, "s1") shouldContainExactly
                listOf(CheckRunInfo(1, "idun", "completed", "success", 10, 99, "x"))
            github.checkSuites(repo, "s1") shouldContainExactly listOf(CheckSuiteInfo(10, "completed", 99))
            github.statuses(repo, "s1") shouldContainExactly listOf(StatusInfo("ci", "success"))
            github.workflowRuns(repo, "s1") shouldContainExactly listOf(WorkflowRunInfo(5, 6, 10))
        }

    @Test
    fun `upsertCheckRun oppretter naar ingen egen run finnes`() =
        runBlocking<Unit> {
            val calls = mutableListOf<Pair<HttpMethod, String>>()
            var created: JsonObject? = null
            val github =
                gh { req ->
                    calls += req.method to req.url.encodedPath
                    if (req.method == HttpMethod.Get) {
                        json("""{"total_count":0,"check_runs":[]}""")
                    } else {
                        created = req.bodyJson()
                        json("""{"id":1}""", HttpStatusCode.Created)
                    }
                }
            github.upsertCheckRun(repo, "s1", "idun", CheckRunWrite("completed", "success", "Tittel", "Sammendrag", "ext"))
            calls.last() shouldBe (HttpMethod.Post to "/repos/entur/products-api/check-runs")
            created!!["name"] shouldBe JsonPrimitive("idun")
            created["head_sha"] shouldBe JsonPrimitive("s1")
            created["status"] shouldBe JsonPrimitive("completed")
            created["conclusion"] shouldBe JsonPrimitive("success")
            created["external_id"] shouldBe JsonPrimitive("ext")
            (created["output"] as JsonObject)["title"] shouldBe JsonPrimitive("Tittel")
            (created["output"] as JsonObject)["summary"] shouldBe JsonPrimitive("Sammendrag")
        }

    @Test
    fun `upsertCheckRun oppdaterer eksisterende run med samme navn`() =
        runBlocking<Unit> {
            val calls = mutableListOf<Pair<HttpMethod, String>>()
            var patched: JsonObject? = null
            val github =
                gh { req ->
                    calls += req.method to req.url.encodedPath
                    if (req.method == HttpMethod.Get) {
                        json(
                            """{"total_count":2,"check_runs":[
                               {"id":7,"name":"annen","status":"completed","conclusion":"success","check_suite":{"id":1},"app":{"id":99}},
                               {"id":8,"name":"idun","status":"in_progress","check_suite":{"id":1},"app":{"id":99}}]}""",
                        )
                    } else {
                        patched = req.bodyJson()
                        json("""{"id":8}""")
                    }
                }
            github.upsertCheckRun(repo, "s1", "idun", CheckRunWrite("in_progress", null, "T", "S", null))
            calls.last() shouldBe (HttpMethod.Patch to "/repos/entur/products-api/check-runs/8")
            patched!!.containsKey("conclusion") shouldBe false
            patched.containsKey("head_sha") shouldBe false
        }

    @Test
    fun `upsertCheckRun med appId ignorerer andre appers run med samme navn`() =
        runBlocking<Unit> {
            val calls = mutableListOf<Pair<HttpMethod, String>>()
            val github =
                KtorGitHub(
                    githubHttpClient(
                        MockEngine { req ->
                            calls += req.method to req.url.encodedPath
                            if (req.method == HttpMethod.Get) {
                                json(
                                    """{"total_count":2,"check_runs":[
                                       {"id":7,"name":"idun","status":"completed","conclusion":"success","check_suite":{"id":1},"app":{"id":1}},
                                       {"id":8,"name":"idun","status":"in_progress","check_suite":{"id":1},"app":{"id":99}}]}""",
                                )
                            } else {
                                json("""{"id":8}""")
                            }
                        },
                    ),
                    "tok",
                    appId = 99,
                )
            github.upsertCheckRun(repo, "s1", "idun", CheckRunWrite("completed", "failure", "T", "S", null))
            calls.last() shouldBe (HttpMethod.Patch to "/repos/entur/products-api/check-runs/8")
        }

    @Test
    fun `upsertCheckRun kaster naar skriving feiler`() {
        val github =
            gh { req ->
                if (req.method == HttpMethod.Get) json("""{"total_count":0,"check_runs":[]}""") else json("{}", HttpStatusCode.Forbidden)
            }
        shouldThrow<GitHubApiException> {
            runBlocking { github.upsertCheckRun(repo, "s1", "idun", CheckRunWrite("completed", "success", "T", "S", null)) }
        }
    }

    // --- kommentarer ---

    @Test
    fun `kommentarer opprettes, oppdateres, listes og reaksjoner telles`() =
        runBlocking<Unit> {
            var created: JsonObject? = null
            var updated: Pair<HttpMethod, String>? = null
            val github =
                gh { req ->
                    val p = req.url.encodedPath
                    when {
                        req.method == HttpMethod.Post && p.endsWith("/issues/5/comments") -> {
                            created = req.bodyJson()
                            json("""{"id":123}""", HttpStatusCode.Created)
                        }
                        req.method == HttpMethod.Patch -> {
                            updated = req.method to p
                            json("""{"id":123}""")
                        }
                        p.endsWith("/issues/5/comments") ->
                            json("""[{"id":123,"user":{"login":"idun[bot]"},"body":"hei"}]""")
                        p.endsWith("/issues/comments/123") ->
                            json("""{"id":123,"reactions":{"total_count":4,"+1":3,"-1":1,"heart":0}}""")
                        else -> error(p)
                    }
                }
            github.createComment(repo, 5, "hei") shouldBe 123L
            created!!["body"] shouldBe JsonPrimitive("hei")
            github.updateComment(repo, 123, "nytt")
            updated shouldBe (HttpMethod.Patch to "/repos/entur/products-api/issues/comments/123")
            github.comments(repo, 5) shouldContainExactly listOf(IssueComment(123, "idun[bot]", "hei"))
            github.reactions(repo, 123) shouldBe Reactions(3, 1)
        }

    // --- labels ---

    @Test
    fun `ensureLabel ignorerer already_exists men kaster paa annen 422`() =
        runBlocking<Unit> {
            var posted: JsonObject? = null
            gh { req ->
                posted = req.bodyJson()
                json(
                    """{"message":"Validation Failed","errors":[{"resource":"Label","code":"already_exists","field":"name"}]}""",
                    HttpStatusCode.UnprocessableEntity,
                )
            }.ensureLabel(repo, "idun-reviewed", "0e8a16", "beskrivelse")
            posted!!["name"] shouldBe JsonPrimitive("idun-reviewed")
            posted["color"] shouldBe JsonPrimitive("0e8a16")
            posted["description"] shouldBe JsonPrimitive("beskrivelse")

            shouldThrow<GitHubApiException> {
                gh { json("""{"message":"Validation Failed","errors":[{"code":"invalid","field":"color"}]}""", HttpStatusCode.UnprocessableEntity) }
                    .ensureLabel(repo, "x", "zzz", "d")
            }
            shouldThrow<GitHubApiException> {
                gh { json("{}", HttpStatusCode.Forbidden) }.ensureLabel(repo, "x", "000000", "d")
            }
        }

    @Test
    fun `addLabel, labels og removeLabel`() =
        runBlocking<Unit> {
            var added: JsonObject? = null
            var deleted: String? = null
            val github =
                gh { req ->
                    when (req.method) {
                        HttpMethod.Post -> {
                            added = req.bodyJson()
                            json("""[{"name":"a"}]""")
                        }
                        HttpMethod.Delete -> {
                            deleted = req.url.encodedPath
                            json("""[]""")
                        }
                        else -> json("""[{"name":"a"},{"name":"b c"}]""")
                    }
                }
            github.addLabel(repo, 5, "a")
            added!!["labels"].toString() shouldBe """["a"]"""
            github.labels(repo, 5) shouldContainExactly listOf("a", "b c")
            github.removeLabel(repo, 5, "b c")
            deleted shouldBe "/repos/entur/products-api/issues/5/labels/b%20c"
        }

    @Test
    fun `removeLabel ignorerer 404 men kaster paa andre feil`() {
        runBlocking { gh { json("{}", HttpStatusCode.NotFound) }.removeLabel(repo, 5, "a") }
        shouldThrow<GitHubApiException> {
            runBlocking { gh { json("{}", HttpStatusCode.InternalServerError) }.removeLabel(repo, 5, "a") }
        }
        shouldThrow<GitHubApiException> {
            runBlocking { gh { json("{}", HttpStatusCode.Forbidden) }.removeLabel(repo, 5, "a") }
        }
    }

    // --- reviews ---

    @Test
    fun `approve sender commit_id, event og body`() =
        runBlocking<Unit> {
            var req0: HttpRequestData? = null
            val github =
                gh { req ->
                    req0 = req
                    json("""{"id":1}""")
                }
            github.approve(repo, 5, "abc123", "ser bra ut")
            req0!!.method shouldBe HttpMethod.Post
            req0.url.encodedPath shouldBe "/repos/entur/products-api/pulls/5/reviews"
            req0.bodyJson() shouldBe
                buildJsonObject {
                    put("commit_id", "abc123")
                    put("event", "APPROVE")
                    put("body", "ser bra ut")
                }
        }

    @Test
    fun `reviews listes og dismissReview sender message`() =
        runBlocking<Unit> {
            var dismissed: Triple<HttpMethod, String, JsonObject>? = null
            val github =
                gh { req ->
                    if (req.method == HttpMethod.Put) {
                        dismissed = Triple(req.method, req.url.encodedPath, req.bodyJson())
                        json("""{"id":77}""")
                    } else {
                        json("""[{"id":77,"user":{"login":"idun[bot]"},"state":"APPROVED","body":null}]""")
                    }
                }
            github.reviews(repo, 5) shouldContainExactly listOf(Review(77, "idun[bot]", "APPROVED", ""))
            github.dismissReview(repo, 5, 77, "ikke lenger gyldig")
            dismissed!!.second shouldBe "/repos/entur/products-api/pulls/5/reviews/77/dismissals"
            dismissed.third shouldBe buildJsonObject { put("message", "ikke lenger gyldig") }
        }

    @Test
    fun `approve og dismiss kaster paa feil`() {
        shouldThrow<GitHubApiException> {
            runBlocking { gh { json("{}", HttpStatusCode.UnprocessableEntity) }.approve(repo, 5, "s", "b") }
        }
        shouldThrow<GitHubApiException> {
            runBlocking { gh { json("{}", HttpStatusCode.NotFound) }.dismissReview(repo, 5, 1, "m") }
        }
    }
}
