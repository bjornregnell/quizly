package quizly.server

import quizly.common.{Quiz, QuizQuestionSummary, QuizSummary, ServerConfig, User}
import upickle.default.*

import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}
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
  private val users = ConcurrentHashMap[String, User]()
  val quizPath = "/api/quizzes"
  val configPath = "/api/config"
  val quizByNamePrefix = s"$quizPath/"

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
          .sortBy(user => user.name.toLowerCase)
        writeJson(response, callback, values)
        true

      case ("GET", `configPath`) =>
        writeJson(response, callback, ServerConfig(debug))
        true

      case ("GET", "/api/quizzes/summary") =>
        writeJson(response, callback, summarizeQuizzes())
        true

      case ("GET", path) if path.startsWith(quizByNamePrefix) =>
        val name = decodePathSegment(path.stripPrefix(quizByNamePrefix))

        Option(users.get(name)) match
          case Some(user) =>
            writeJson(response, callback, user)
          case None =>
            writeError(
              response,
              callback,
              HttpStatus.NOT_FOUND_404,
              s"No user for '$name'"
            )
        true

      case ("POST", `quizPath`) =>
        readJsonBody[User](request) match
          case Success(user) =>
            normalize(user) match
              case Some(normalized) =>
                users.put(normalized.name, normalized)
                writeJson(response, callback, normalized)
              case None =>
                writeError(
                  response,
                  callback,
                  HttpStatus.BAD_REQUEST_400,
                  "User name is required"
                )
          case Failure(error) =>
            writeError(
              response,
              callback,
              HttpStatus.BAD_REQUEST_400,
              error.getMessage
            )
        true

      case ("POST", "/api/quizzes/delete") =>
        val params = Request.extractQueryParameters(request)
        val nameOpt =
          Option(params.getValue("name")).map(_.trim).filter(_.nonEmpty)

        nameOpt match
          case Some(name) =>
            Option(users.remove(name)) match
              case Some(user) => writeJson(response, callback, user)
              case None       =>
                writeError(
                  response,
                  callback,
                  HttpStatus.NOT_FOUND_404,
                  s"No user for '$name'"
                )
          case None =>
            writeError(
              response,
              callback,
              HttpStatus.BAD_REQUEST_400,
              "User name is required"
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

      case ("GET", path) if path.startsWith("/assets/") =>
        writeStaticFile(response, callback, path.stripPrefix("/assets/"))
        true

      case _ =>
        writeError(response, callback, HttpStatus.NOT_FOUND_404, "not found")
        true

  def normalize(user: User): Option[User] =
    val name = user.name.trim
    val answers = Quiz.normalizeAnswers(user.answers)

    Option.when(name.nonEmpty)(user.copy(name = name, answers = answers))

  def summarizeQuizzes(): QuizSummary =
    val emptyRows = Quiz.questionRows.map: row =>
      QuizQuestionSummary.empty(row._1, row._2)

    val rows = users
      .values()
      .asScala
      .foldLeft(emptyRows):
        case (rows, user) =>
          rows.map: row =>
            user.answers.getOrElse(row.id, None) match
              case Some(true)  => row.copy(trueAnswers = row.trueAnswers + 1)
              case Some(false) =>
                row.copy(falseAnswers = row.falseAnswers + 1)
              case None => row.copy(noAnswerYet = row.noAnswerYet + 1)

    QuizSummary(rows)

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
