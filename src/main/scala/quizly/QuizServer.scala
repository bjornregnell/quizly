package quizly

import org.eclipse.jetty.http.{HttpHeader, HttpStatus}
import org.eclipse.jetty.io.Content
import org.eclipse.jetty.server.{Handler, Request, Response, Server, ServerConnector}
import org.eclipse.jetty.util.Callback

object QuizServer:
  val defaultPort = 8095

  def main(args: Array[String]): Unit =
    val port = sys.env.get("PORT").flatMap(_.toIntOption).getOrElse(defaultPort)
    val server = Server()
    val connector = ServerConnector(server)
    connector.setPort(port)

    server.addConnector(connector)
    server.setHandler(QuizHandler)
    server.start()

    println(s"Quiz server listening on http://localhost:$port/quiz")
    server.join()

object QuizHandler extends Handler.Abstract.NonBlocking:
  override def handle(request: Request, response: Response, callback: Callback): Boolean =
    Request.getPathInContext(request) match
      case "/quiz" =>
        val params = Request.extractQueryParameters(request)
        val nameOpt = Option(params.getValue("name"))
        val warEnd2026Opt =
          Option(params.get("warEnd2026")).map(_ => params.getValuesOrEmpty("warEnd2026").contains("true"))
        writeHtml(response, callback, html.quizPage(nameOpt, warEnd2026Opt))
        true

      case _ =>
        response.setStatus(HttpStatus.NOT_FOUND_404)
        Content.Sink.write(response, true, "not found", callback)
        true

  private def writeHtml(response: Response, callback: Callback, body: String): Unit =
    response.setStatus(HttpStatus.OK_200)
    response.getHeaders.put(HttpHeader.CONTENT_TYPE, "text/html; charset=utf-8")
    Content.Sink.write(response, true, body, callback)
