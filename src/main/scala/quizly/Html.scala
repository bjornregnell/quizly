package quizly

object html:
  def quizPage(
    name: Option[String] = None, 
    warEnd2026: Option[Boolean] = None
  ): String =
    val checked = if warEnd2026.contains(true) then " checked" else ""
    val value = name.map(escapeHtml).getOrElse("")
    val answer = warEnd2026.map(_.toString).getOrElse("")

    s"""|<!doctype html>
        |<html lang="en">
        |  <head>
        |    <meta charset="utf-8">
        |    <title>Quizly</title>
        |  </head>
        |  <body>
        |    <main>
        |      <form action="/quiz" method="get">
        |        <label for="name">you name</label>
        |        <input id="name" name="name" type="text" value="$value">
        |
        |        <div>
        |          <input name="warEnd2026" type="hidden" value="false">
        |          <input id="warEnd2026" name="warEnd2026" type="checkbox" value="true"$checked>
        |          <label for="warEnd2026">will the war end in 2026: $answer</label>
        |        </div>
        |
        |        <button type="submit">update</button>
        |      </form>
        |    </main>
        |  </body>
        |</html>
        |""".stripMargin

  private def escapeHtml(value: String): String =
    value
      .replace("&", "&amp;")
      .replace("\"", "&quot;")
      .replace("<", "&lt;")
      .replace(">", "&gt;")
