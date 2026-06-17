package quizly.server

import quizly.common.{Quiz, QuizQuestionSummary, QuizSummary}
import upickle.default.*

import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}
import java.util.concurrent.ConcurrentHashMap
import scala.jdk.CollectionConverters.*
import scala.util.{Failure, Success, Try}

import org.eclipse.jetty.http.{HttpHeader, HttpStatus}
import org.eclipse.jetty.io.Content
import org.eclipse.jetty.server.{Handler, Request, Response, Server, ServerConnector}
import org.eclipse.jetty.util.Callback

object QuizServer:
  val defaultPort = 8095
  val defaultSpaPort = 8096

  def main(args: Array[String]): Unit =
    val apiPort = sys.env.get("API_PORT").orElse(sys.env.get("PORT")).flatMap(_.toIntOption).getOrElse(defaultPort)
    val spaPort = sys.env.get("SPA_PORT").flatMap(_.toIntOption).getOrElse(defaultSpaPort)
    val staticDir = sys.env.get("STATIC_DIR").map(Paths.get(_)).getOrElse(appDir).toAbsolutePath.normalize()

    val server = Server()
    val apiConnector = ServerConnector(server)
    val spaConnector = ServerConnector(server)
    apiConnector.setPort(apiPort)
    spaConnector.setPort(spaPort)

    server.addConnector(apiConnector)
    server.addConnector(spaConnector)
    server.setHandler(QuizHandler(staticDir))
    server.start()

    println(s"Quiz API listening on http://localhost:$apiPort/api/quizzes")
    println(s"Quiz SPA listening on http://localhost:$spaPort/quizly")
    println(s"Quiz static files served from $staticDir")
    server.join()

  def appDir: Path =
    val codeSourcePath = Try(Paths.get(QuizServer.getClass.getProtectionDomain.getCodeSource.getLocation.toURI))
      .getOrElse(Paths.get("."))
    val dir = if Files.isRegularFile(codeSourcePath) then codeSourcePath.getParent else codeSourcePath
    Option(dir).getOrElse(Paths.get(".")).toAbsolutePath.normalize()

final class QuizHandler(staticDir: Path) extends Handler.Abstract:
  private val quizzes = ConcurrentHashMap[String, Quiz]()
  val quizPath = "/api/quizzes"
  val quizByNamePrefix = s"$quizPath/"

  override def handle(request: Request, response: Response, callback: Callback): Boolean =
    addCorsHeaders(response)

    (request.getMethod, Request.getPathInContext(request)) match
      case ("OPTIONS", _) =>
        response.setStatus(HttpStatus.NO_CONTENT_204)
        Content.Sink.write(response, true, "", callback)
        true

      case ("GET", `quizPath`) =>
        val values = quizzes.values().asScala.toVector.sortBy(quiz => (quiz.name.toLowerCase, quiz.question))
        writeJson(response, callback, values)
        true

      case ("GET", "/api/quizzes/summary") =>
        writeJson(response, callback, summarizeQuizzes())
        true

      case ("GET", path) if path.startsWith(quizByNamePrefix) =>
        val name = decodePathSegment(path.stripPrefix(quizByNamePrefix))
        val values = quizzes.values().asScala.toVector
          .filter(_.name == name)
          .sortBy(_.question)

        if values.nonEmpty then writeJson(response, callback, values)
        else writeError(response, callback, HttpStatus.NOT_FOUND_404, s"No quizzes for '$name'")
        true

      case ("POST", `quizPath`) =>
        readJsonBody[Quiz](request) match
          case Success(quiz) =>
            normalize(quiz) match
              case Some(normalized) =>
                quizzes.put(quizKey(normalized.name, normalized.question), normalized)
                writeJson(response, callback, normalized)
              case None =>
                writeError(response, callback, HttpStatus.BAD_REQUEST_400, "Quiz name is required")
          case Failure(error) =>
            writeError(response, callback, HttpStatus.BAD_REQUEST_400, error.getMessage)
        true

      case ("POST", "/api/quizzes/delete") =>
        val params = Request.extractQueryParameters(request)
        val nameOpt = Option(params.getValue("name")).map(_.trim).filter(_.nonEmpty)
        val questionOpt = Option(params.getValue("question")).map(_.trim).filter(_.nonEmpty)

        (nameOpt, questionOpt) match
          case (Some(name), Some(question)) =>
            Option(quizzes.remove(quizKey(name, question))) match
              case Some(quiz) => writeJson(response, callback, quiz)
              case None       => writeError(response, callback, HttpStatus.NOT_FOUND_404, s"No quiz for '$name' and '$question'")
          case _ =>
            writeError(response, callback, HttpStatus.BAD_REQUEST_400, "Quiz name and question are required")
        true

      case ("GET", path) if path == "/quizly" =>
        response.setStatus(HttpStatus.MOVED_PERMANENTLY_301)
        response.getHeaders.put(HttpHeader.LOCATION, "/quizly/")
        Content.Sink.write(response, true, "", callback)
        true

      case ("GET", path) if path == "/quizly/" || path == "/quizly/index.html" =>
        writeStaticFile(response, callback, "index.html")
        true

      case ("GET", path) if path.startsWith("/assets/") =>
        writeStaticFile(response, callback, path.stripPrefix("/assets/"))
        true

      case _ =>
        writeError(response, callback, HttpStatus.NOT_FOUND_404, "not found")
        true

  def normalize(quiz: Quiz): Option[Quiz] =
    val name = quiz.name.trim
    val question = quiz.question.trim match
      case ""    => Quiz.defaultQuestion
      case value => value

    Option.when(name.nonEmpty)(quiz.copy(name = name, question = question))

  def quizKey(name: String, question: String): String =
    s"$name\u0000$question"

  def summarizeQuizzes(): QuizSummary =
    val questions = (Quiz.questions ++ quizzes.values().asScala.map(_.question)).distinct
    val rows = quizzes.values().asScala.foldLeft(questions.map(QuizQuestionSummary.empty)):
      case (rows, quiz) =>
        rows.map: row =>
          if row.question == quiz.question then
            quiz.answer match
              case Some(true)  => row.copy(trueAnswers = row.trueAnswers + 1)
              case Some(false) => row.copy(falseAnswers = row.falseAnswers + 1)
              case None        => row.copy(noAnswerYet = row.noAnswerYet + 1)
          else row

    QuizSummary(rows)

  def readJsonBody[A: Reader](request: Request): Try[A] =
    Try:
      val body = String(Request.asInputStream(request).readAllBytes(), StandardCharsets.UTF_8)
      read[A](body)

  def writeJson[A: Writer](response: Response, callback: Callback, value: A): Unit =
    response.setStatus(HttpStatus.OK_200)
    response.getHeaders.put(HttpHeader.CONTENT_TYPE, "application/json; charset=utf-8")
    Content.Sink.write(response, true, write(value), callback)

  def writeError(response: Response, callback: Callback, status: Int, message: String): Unit =
    response.setStatus(status)
    response.getHeaders.put(HttpHeader.CONTENT_TYPE, "application/json; charset=utf-8")
    Content.Sink.write(response, true, write(Map("error" -> message)), callback)

  def writeStaticFile(response: Response, callback: Callback, fileName: String): Unit =
    if fileName.contains("/") || fileName.contains("\\") || fileName.contains("..") then
      response.setStatus(HttpStatus.FORBIDDEN_403)
      Content.Sink.write(response, true, "forbidden", callback)
    else
      val file = staticDir.resolve(fileName).normalize()
      if !file.startsWith(staticDir) || !Files.isRegularFile(file) then
        response.setStatus(HttpStatus.NOT_FOUND_404)
        Content.Sink.write(response, true, "not found", callback)
      else
        val body = Files.readString(file, StandardCharsets.UTF_8)
        response.setStatus(HttpStatus.OK_200)
        response.getHeaders.put(HttpHeader.CONTENT_TYPE, contentType(fileName))
        Content.Sink.write(response, true, body, callback)

  def contentType(path: String): String =
    path match
      case name if name.endsWith(".html") => "text/html; charset=utf-8"
      case name if name.endsWith(".js")   => "text/javascript; charset=utf-8"
      case name if name.endsWith(".map")  => "application/json; charset=utf-8"
      case name if name.endsWith(".css")  => "text/css; charset=utf-8"
      case _                              => "text/plain; charset=utf-8"

  def addCorsHeaders(response: Response): Unit =
    response.getHeaders.put(HttpHeader.ACCESS_CONTROL_ALLOW_ORIGIN, "*")
    response.getHeaders.put(HttpHeader.ACCESS_CONTROL_ALLOW_METHODS, "GET, POST, OPTIONS")
    response.getHeaders.put(HttpHeader.ACCESS_CONTROL_ALLOW_HEADERS, "Content-Type")

  def decodePathSegment(value: String): String =
    URLDecoder.decode(value, StandardCharsets.UTF_8)
