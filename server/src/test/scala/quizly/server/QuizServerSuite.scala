package quizly.server

import quizly.common.{Quiz, QuizSummary, ServerConfig, User}
import upickle.default.*

import java.net.{URI, URLEncoder}
import java.net.http.HttpRequest.BodyPublishers
import java.net.http.HttpResponse.BodyHandlers
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}

import org.eclipse.jetty.server.{Server, ServerConnector}

class QuizServerSuite extends munit.FunSuite:
  val warQuestionId = 1
  val governmentQuestionId = 2
  val adaId = "ada-id"
  val graceId = "grace-id"

  val httpClient = HttpClient
    .newBuilder()
    .followRedirects(HttpClient.Redirect.NEVER)
    .build()

  test("OPTIONS /api/quizzes responds to a CORS preflight"):
    withRunningServer: base =>
      val response = send:
        HttpRequest
          .newBuilder(base.resolve("/api/quizzes"))
          .method("OPTIONS", BodyPublishers.noBody())
          .build()

      assertEquals(response.statusCode(), 204)
      assertEquals(response.headers().firstValue("access-control-allow-origin").orElse(""), "*")

  test("GET /api/config returns non-debug mode by default"):
    withRunningServer: base =>
      val response = get(base, "/api/config")

      assertOkJson(response)
      assertEquals(read[ServerConfig](response.body()), ServerConfig(debug = false))

  test("GET /api/config returns debug mode when enabled"):
    withDebugServer: base =>
      val response = get(base, "/api/config")

      assertOkJson(response)
      assertEquals(read[ServerConfig](response.body()), ServerConfig(debug = true))

  test("GET /api/quizzes returns stored users"):
    withRunningServer: base =>
      val user =
        User(adaId, Quiz.emptyAnswers + (warQuestionId -> Some(true)))
      postJson(base, "/api/quizzes", write(user))

      val response = get(base, "/api/quizzes")

      assertOkJson(response)
      assertEquals(read[Vector[User]](response.body()), Vector(user))

  test("GET /api/quizzes/summary returns per-question counts"):
    withRunningServer: base =>
      postJson(
        base,
        "/api/quizzes",
        write(User(adaId, Quiz.emptyAnswers + (warQuestionId -> Some(true))))
      )
      postJson(
        base,
        "/api/quizzes",
        write(User(graceId, Quiz.emptyAnswers + (governmentQuestionId -> Some(false))))
      )

      val response = get(base, "/api/quizzes/summary")

      assertOkJson(response)
      assert(!response.body().contains("\"question\":"))
      val summary = read[QuizSummary](response.body())
      assertEquals(summary.respondents, 2)
      assertEquals(summary.questions.find(_.id == warQuestionId).map(_.trueAnswers), Some(1))
      assertEquals(summary.questions.find(_.id == warQuestionId).map(_.noAnswerYet), Some(1))
      assertEquals(summary.questions.find(_.id == governmentQuestionId).map(_.falseAnswers), Some(1))
      assertEquals(summary.questions.find(_.id == governmentQuestionId).map(_.noAnswerYet), Some(1))

  test("GET /api/quizzes/{id} returns the identified user"):
    withRunningServer: base =>
      val ada =
        User(adaId, Quiz.emptyAnswers + (warQuestionId -> Some(true)))
      postJson(base, "/api/quizzes", write(ada))
      postJson(base, "/api/quizzes", write(User(graceId, Quiz.emptyAnswers)))

      val response = get(base, s"/api/quizzes/${encode(ada.id)}")

      assertOkJson(response)
      assertEquals(read[User](response.body()), ada)

  test("POST /api/quizzes creates a user id when needed"):
    withRunningServer: base =>
      val user =
        User(User.unsavedId, Quiz.emptyAnswers + (warQuestionId -> Some(true)))

      val response = postJson(base, "/api/quizzes", write(user))

      assertOkJson(response)
      val saved = read[User](response.body())
      assert(saved.id.nonEmpty)
      assertEquals(saved.answers, user.answers)

  test("POST /api/quizzes assigns distinct ids to separate unsaved posts"):
    withRunningServer: base =>
      val first = read[User]:
        postJson(
          base,
          "/api/quizzes",
          write(User(User.unsavedId, Quiz.emptyAnswers))
        ).body()
      val second = read[User]:
        postJson(
          base,
          "/api/quizzes",
          write(User(User.unsavedId, Quiz.emptyAnswers))
        ).body()

      val response = get(base, "/api/quizzes")

      assertOkJson(response)
      assertEquals(read[Vector[User]](response.body()).map(_.id).toSet, Set(first.id, second.id))
      assertNotEquals(first.id, second.id)

  test("POST /api/quizzes/delete deletes a user"):
    withRunningServer: base =>
      val user =
        User(adaId, Quiz.emptyAnswers + (warQuestionId -> Some(true)))
      postJson(base, "/api/quizzes", write(user))

      val response = post(base, s"/api/quizzes/delete?id=${encode(user.id)}")

      assertOkJson(response)
      assertEquals(read[User](response.body()), user)

  test("GET /quizly redirects to /quizly/"):
    withRunningServer: base =>
      val response = get(base, "/quizly")

      assertEquals(response.statusCode(), 301)
      assertEquals(response.headers().firstValue("location").orElse(""), "/quizly/")

  test("GET /quizly/ serves the SPA shell"):
    withRunningServer: base =>
      val response = get(base, "/quizly/")

      assertEquals(response.statusCode(), 200)
      assertEquals(response.headers().firstValue("content-type").orElse(""), "text/html; charset=utf-8")
      assertEquals(response.body(), indexHtml)

  test("GET /quizly/index.html serves the SPA shell"):
    withRunningServer: base =>
      val response = get(base, "/quizly/index.html")

      assertEquals(response.statusCode(), 200)
      assertEquals(response.headers().firstValue("content-type").orElse(""), "text/html; charset=utf-8")
      assertEquals(response.body(), indexHtml)

  test("GET /assets/main.js serves the Scala.js bundle"):
    withRunningServer: base =>
      val response = get(base, "/assets/main.js")

      assertEquals(response.statusCode(), 200)
      assertEquals(response.headers().firstValue("content-type").orElse(""), "text/javascript; charset=utf-8")
      assertEquals(response.body(), mainJs)

  test("GET /quizly/assessment serves the assessment page"):
    withRunningServer: base =>
      val response = get(base, "/quizly/assessment")

      assertEquals(response.statusCode(), 200)
      assertEquals(response.headers().firstValue("content-type").orElse(""), "text/html; charset=utf-8")
      assertEquals(response.body(), assessmentHtml)

  def withRunningServer(run: URI => Unit): Unit =
    withRunningServer(debug = false, run)

  def withDebugServer(run: URI => Unit): Unit =
    withRunningServer(debug = true, run)

  def withRunningServer(debug: Boolean, run: URI => Unit): Unit =
    val staticDir = Files.createTempDirectory("quizly-server-test")
    Files.writeString(staticDir.resolve("index.html"), indexHtml, StandardCharsets.UTF_8)
    Files.writeString(staticDir.resolve("main.js"), mainJs, StandardCharsets.UTF_8)
    Files.writeString(staticDir.resolve("assessment.html"), assessmentHtml, StandardCharsets.UTF_8)

    val server = Server()
    val connector = ServerConnector(server)
    connector.setHost("127.0.0.1")
    connector.setPort(0)
    server.addConnector(connector)
    server.setHandler(QuizHandler(staticDir, debug))

    server.start()
    try run(URI(s"http://127.0.0.1:${connector.getLocalPort}"))
    finally
      server.stop()
      server.destroy()
      deleteStaticDir(staticDir)

  def get(base: URI, path: String): HttpResponse[String] =
    send(HttpRequest.newBuilder(base.resolve(path)).GET().build())

  def post(base: URI, path: String): HttpResponse[String] =
    send(HttpRequest.newBuilder(base.resolve(path)).POST(BodyPublishers.noBody()).build())

  def postJson(base: URI, path: String, body: String): HttpResponse[String] =
    send:
      HttpRequest
        .newBuilder(base.resolve(path))
        .header("Content-Type", "application/json")
        .POST(BodyPublishers.ofString(body, StandardCharsets.UTF_8))
        .build()

  def send(request: HttpRequest): HttpResponse[String] =
    httpClient.send(request, BodyHandlers.ofString(StandardCharsets.UTF_8))

  def assertOkJson(response: HttpResponse[String]): Unit =
    assertEquals(response.statusCode(), 200)
    assertEquals(response.headers().firstValue("content-type").orElse(""), "application/json; charset=utf-8")

  def encode(value: String): String =
    URLEncoder.encode(value, StandardCharsets.UTF_8)

  def deleteStaticDir(staticDir: Path): Unit =
    Files.deleteIfExists(staticDir.resolve("index.html"))
    Files.deleteIfExists(staticDir.resolve("main.js"))
    Files.deleteIfExists(staticDir.resolve("assessment.html"))
    Files.deleteIfExists(staticDir)

  val indexHtml = "<!doctype html><main id=\"app\"></main>"
  val mainJs = "console.log('quizly test');"
  val assessmentHtml = "<!doctype html><h1>assessment</h1>"
