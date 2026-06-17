package quizly.server

import quizly.common.{Quiz, QuizSummary}
import upickle.default.*

import java.net.{URI, URLEncoder}
import java.net.http.HttpRequest.BodyPublishers
import java.net.http.HttpResponse.BodyHandlers
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}

import org.eclipse.jetty.server.{Server, ServerConnector}

class QuizServerSuite extends munit.FunSuite:
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

  test("GET /api/quizzes returns stored quizzes"):
    withRunningServer: base =>
      val quiz = Quiz("Ada", Quiz.defaultQuestion, Some(true))
      postJson(base, "/api/quizzes", write(quiz))

      val response = get(base, "/api/quizzes")

      assertOkJson(response)
      assertEquals(read[Vector[Quiz]](response.body()), Vector(quiz))

  test("GET /api/quizzes/summary returns per-question counts"):
    withRunningServer: base =>
      postJson(base, "/api/quizzes", write(Quiz("Ada", Quiz.defaultQuestion, Some(true))))
      postJson(base, "/api/quizzes", write(Quiz("Grace", Quiz.governmentQuestion, Some(false))))
      postJson(base, "/api/quizzes", write(Quiz("Linus", Quiz.governmentQuestion, None)))

      val response = get(base, "/api/quizzes/summary")

      assertOkJson(response)
      val summary = read[QuizSummary](response.body())
      assertEquals(summary.questions.find(_.question == Quiz.defaultQuestion).map(_.trueAnswers), Some(1))
      assertEquals(summary.questions.find(_.question == Quiz.governmentQuestion).map(_.falseAnswers), Some(1))
      assertEquals(summary.questions.find(_.question == Quiz.governmentQuestion).map(_.noAnswerYet), Some(1))

  test("GET /api/quizzes/{name} returns the named user's quizzes"):
    withRunningServer: base =>
      val adaQuiz = Quiz("Ada Lovelace", Quiz.defaultQuestion, Some(true))
      postJson(base, "/api/quizzes", write(adaQuiz))
      postJson(base, "/api/quizzes", write(Quiz("Grace Hopper", Quiz.defaultQuestion, Some(false))))

      val response = get(base, s"/api/quizzes/${encode("Ada Lovelace")}")

      assertOkJson(response)
      assertEquals(read[Vector[Quiz]](response.body()), Vector(adaQuiz))

  test("POST /api/quizzes creates a quiz"):
    withRunningServer: base =>
      val quiz = Quiz("Ada", Quiz.defaultQuestion, Some(true))

      val response = postJson(base, "/api/quizzes", write(quiz))

      assertOkJson(response)
      assertEquals(read[Quiz](response.body()), quiz)

  test("POST /api/quizzes/delete deletes a quiz"):
    withRunningServer: base =>
      val quiz = Quiz("Ada", Quiz.defaultQuestion, Some(true))
      postJson(base, "/api/quizzes", write(quiz))

      val response = post(base, s"/api/quizzes/delete?name=${encode(quiz.name)}&question=${encode(quiz.question)}")

      assertOkJson(response)
      assertEquals(read[Quiz](response.body()), quiz)

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

  def withRunningServer(run: URI => Unit): Unit =
    val staticDir = Files.createTempDirectory("quizly-server-test")
    Files.writeString(staticDir.resolve("index.html"), indexHtml, StandardCharsets.UTF_8)
    Files.writeString(staticDir.resolve("main.js"), mainJs, StandardCharsets.UTF_8)

    val server = Server()
    val connector = ServerConnector(server)
    connector.setHost("127.0.0.1")
    connector.setPort(0)
    server.addConnector(connector)
    server.setHandler(QuizHandler(staticDir))

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
    Files.deleteIfExists(staticDir)

  val indexHtml = "<!doctype html><main id=\"app\"></main>"
  val mainJs = "console.log('quizly test');"
