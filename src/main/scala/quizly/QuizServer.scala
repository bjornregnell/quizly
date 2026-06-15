package quizly

import org.eclipse.jetty.http.{HttpHeader, HttpStatus}
import org.eclipse.jetty.io.Content
import org.eclipse.jetty.server.{Handler, Request, Response, Server, ServerConnector}
import org.eclipse.jetty.util.Callback

object QuizServer:
  private val defaultPort = 8080

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

private object QuizHandler extends Handler.Abstract.NonBlocking:
  private val quizHtml =
    """<!doctype html>
      |<html lang="en">
      |  <head>
      |    <meta charset="utf-8">
      |    <title>Quizly</title>
      |  </head>
      |  <body>
      |    <main>hello quiz</main>
      |  </body>
      |</html>
      |""".stripMargin

  override def handle(request: Request, response: Response, callback: Callback): Boolean =
    if Request.getPathInContext(request) == "/quiz" then
      response.setStatus(HttpStatus.OK_200)
      response.getHeaders.put(HttpHeader.CONTENT_TYPE, "text/html; charset=utf-8")
      Content.Sink.write(response, true, quizHtml, callback)
      true
    else
      response.setStatus(HttpStatus.NOT_FOUND_404)
      Content.Sink.write(response, true, "not found", callback)
      true
