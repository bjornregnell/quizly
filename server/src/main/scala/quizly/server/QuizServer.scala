package quizly.server

import quizly.common.{Quiz, QuizQuestionSummary, QuizSummary, ServerConfig, User}
import upickle.default.*

import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import scala.jdk.CollectionConverters.*
import scala.util.{Failure, Success, Try}

import org.eclipse.jetty.http.{HttpHeader, HttpStatus}
import org.eclipse.jetty.io.Content
import org.eclipse.jetty.server.{
  Handler,
  Request,
  Response,
  Server,
  ServerConnector
}
import org.eclipse.jetty.util.Callback

object QuizServer:
  val defaultPort = 8095
  val defaultSpaPort = 8096

  def main(args: Array[String]): Unit =
    val apiPort = argValue(args, "--api-port")
      .orElse(sys.env.get("API_PORT"))
      .orElse(sys.env.get("PORT"))
      .flatMap(_.toIntOption)
      .getOrElse(defaultPort)
    val spaPort = argValue(args, "--spa-port")
      .orElse(sys.env.get("SPA_PORT"))
      .flatMap(_.toIntOption)
      .getOrElse(defaultSpaPort)
    val staticDir = argValue(args, "--static-dir")
      .orElse(sys.env.get("STATIC_DIR"))
      .map(Paths.get(_))
      .getOrElse(appDir)
      .toAbsolutePath
      .normalize()
    val debug = hasArg(args, "--debug")

    start(apiPort, spaPort, staticDir, debug)

  def start(
      apiPort: Int,
      spaPort: Int,
      staticDir: Path,
      debug: Boolean = false
  ): Unit =
    val server = Server()
    val apiConnector = ServerConnector(server)
    val spaConnector = ServerConnector(server)
    apiConnector.setPort(apiPort)
    spaConnector.setPort(spaPort)

    server.addConnector(apiConnector)
    server.addConnector(spaConnector)
    server.setHandler(QuizHandler(staticDir, debug))
    server.start()

    println(s"Quiz API listening on http://localhost:$apiPort/api/quizzes")
    println(s"Quiz SPA listening on http://localhost:$spaPort/quizly")
    println(s"Quiz static files served from $staticDir")
    println(s"Quiz debug mode: $debug")
    server.join()

  def argValue(args: Array[String], name: String): Option[String] =
    args.sliding(2).collectFirst:
      case Array(`name`, value) => value
    .orElse:
      args.collectFirst:
        case arg if arg.startsWith(s"$name=") =>
          arg.drop(name.length + 1)

  def hasArg(args: Array[String], name: String): Boolean =
    args.contains(name)

  def appDir: Path =
    val codeSourcePath = Try(
      Paths.get(
        QuizServer.getClass.getProtectionDomain.getCodeSource.getLocation.toURI
      )
    )
      .getOrElse(Paths.get("."))
    val dir =
      if Files.isRegularFile(codeSourcePath) then codeSourcePath.getParent
      else codeSourcePath
    Option(dir).getOrElse(Paths.get(".")).toAbsolutePath.normalize()

final class QuizHandler(staticDir: Path, debug: Boolean = false)
    extends Handler.Abstract:
  private val users = ConcurrentHashMap[User.Id, User]()
  val quizPath = "/api/quizzes"
  val configPath = "/api/config"
  val quizByIdPrefix = s"$quizPath/"

  override def handle(
      request: Request,
      response: Response,
      callback: Callback
  ): Boolean =
    addCorsHeaders(response)

    (request.getMethod, Request.getPathInContext(request)) match
      case ("OPTIONS", _) =>
        response.setStatus(HttpStatus.NO_CONTENT_204)
        Content.Sink.write(response, true, "", callback)
        true

      case ("GET", `quizPath`) =>
        val values = users
          .values()
          .asScala
          .toVector
          .sortBy(_.id)
        writeJson(response, callback, values)
        true

      case ("GET", `configPath`) =>
        writeJson(response, callback, ServerConfig(debug))
        true

      case ("GET", "/api/quizzes/summary") =>
        writeJson(response, callback, summarizeQuizzes())
        true

      case ("GET", path) if path.startsWith(quizByIdPrefix) =>
        val id = decodePathSegment(path.stripPrefix(quizByIdPrefix))

        Option(users.get(id)) match
          case Some(user) =>
            writeJson(response, callback, user)
          case None =>
            writeError(
              response,
              callback,
              HttpStatus.NOT_FOUND_404,
              s"No user for id '$id'"
            )
        true

      case ("POST", `quizPath`) =>
        readJsonBody[User](request) match
          case Success(user) =>
            val normalized = normalize(user)
            users.put(normalized.id, normalized)
            writeJson(response, callback, normalized)
          case Failure(_) =>
            writeError(
              response,
              callback,
              HttpStatus.BAD_REQUEST_400,
              "invalid request body"
            )
        true

      case ("POST", "/api/quizzes/delete") =>
        val params = Request.extractQueryParameters(request)
        val idOpt =
          Option(params.getValue("id")).map(_.trim).filter(_.nonEmpty)

        idOpt match
          case Some(id) =>
            Option(users.remove(id)) match
              case Some(user) => writeJson(response, callback, user)
              case None       =>
                writeError(
                  response,
                  callback,
                  HttpStatus.NOT_FOUND_404,
                  s"No user for id '$id'"
                )
          case None =>
            writeError(
              response,
              callback,
              HttpStatus.BAD_REQUEST_400,
              "User id is required"
            )
        true

      case ("GET", path) if path == "/quizly" =>
        response.setStatus(HttpStatus.MOVED_PERMANENTLY_301)
        response.getHeaders.put(HttpHeader.LOCATION, "/quizly/")
        Content.Sink.write(response, true, "", callback)
        true

      case ("GET", path)
          if path == "/quizly/" || path == "/quizly/index.html" =>
        writeStaticFile(response, callback, "index.html")
        true

      case ("GET", path)
          if path == "/quizly/assessment" || path == "/quizly/assessment.html" =>
        writeStaticFile(response, callback, "assessment.html")
        true

      case ("GET", path) if path.startsWith("/assets/") =>
        writeStaticFile(response, callback, path.stripPrefix("/assets/"))
        true

      case _ =>
        writeError(response, callback, HttpStatus.NOT_FOUND_404, "not found")
        true

  def normalize(user: User): User =
    val id =
      Option(user.id).map(_.trim).filter(_.nonEmpty).getOrElse(newUserId())
    val answers = Quiz.normalizeAnswers(user.answers)

    user.copy(id = id, answers = answers)

  def newUserId(): User.Id =
    UUID.randomUUID().toString

  def summarizeQuizzes(): QuizSummary =
    val emptyRows = Quiz.questionIds.map(QuizQuestionSummary.empty)
    val allUsers = users.values().asScala.toVector

    val rows = allUsers
      .foldLeft(emptyRows):
        case (rows, user) =>
          rows.map: row =>
            user.answers.getOrElse(row.id, None) match
              case Some(true)  => row.copy(trueAnswers = row.trueAnswers + 1)
              case Some(false) =>
                row.copy(falseAnswers = row.falseAnswers + 1)
              case None => row.copy(noAnswerYet = row.noAnswerYet + 1)

    val respondents = allUsers.count(_.answers.values.exists(_.isDefined))

    QuizSummary(rows, respondents)

  def readJsonBody[A: Reader](request: Request): Try[A] =
    Try:
      val body = String(
        Request.asInputStream(request).readAllBytes(),
        StandardCharsets.UTF_8
      )
      read[A](body)

  def writeJson[A: Writer](
      response: Response,
      callback: Callback,
      value: A
  ): Unit =
    response.setStatus(HttpStatus.OK_200)
    response.getHeaders.put(
      HttpHeader.CONTENT_TYPE,
      "application/json; charset=utf-8"
    )
    Content.Sink.write(response, true, write(value), callback)

  def writeError(
      response: Response,
      callback: Callback,
      status: Int,
      message: String
  ): Unit =
    response.setStatus(status)
    response.getHeaders.put(
      HttpHeader.CONTENT_TYPE,
      "application/json; charset=utf-8"
    )
    Content.Sink.write(response, true, write(Map("error" -> message)), callback)

  def writeStaticFile(
      response: Response,
      callback: Callback,
      fileName: String
  ): Unit =
    if fileName.contains("/") || fileName.contains("\\") || fileName.contains(
        ".."
      )
    then
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
    response.getHeaders.put(
      HttpHeader.ACCESS_CONTROL_ALLOW_METHODS,
      "GET, POST, OPTIONS"
    )
    response.getHeaders.put(
      HttpHeader.ACCESS_CONTROL_ALLOW_HEADERS,
      "Content-Type"
    )

  def decodePathSegment(value: String): String =
    URLDecoder.decode(value, StandardCharsets.UTF_8)
