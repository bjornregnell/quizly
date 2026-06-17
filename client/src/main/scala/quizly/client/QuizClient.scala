package quizly.client

import quizly.common.{Quiz, QuizQuestionSummary, QuizSummary, User}
import upickle.default.*

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.scalajs.js
import scala.scalajs.js.Thenable.Implicits.*

import com.raquo.laminar.api.L.{*, given}
import org.scalajs.dom

object QuizClient:
  val apiBase =
    val location = dom.window.location
    val host =
      Option(location.hostname).filter(_.nonEmpty).getOrElse("localhost")
    val scheme =
      location.protocol match
        case "https:" => "https:"
        case _        => "http:"

    s"$scheme//$host:8095"

  private val nameVar = Var("")
  private val answersVar = Var(Quiz.emptyAnswers)
  private val usersVar = Var(Vector.empty[User])
  private val summaryVar = Var(QuizSummary.empty)
  private val messageVar = Var("Ready")

  def main(args: Array[String]): Unit =
    renderOnDomContentLoaded(
      dom.document.getElementById("app"),
      mainView
    )
    refreshAll()

  def mainView: HtmlElement =
    div(
      cls := "app",
      h1("Quizly"),
      form(
        cls := "editor",
        onSubmit.preventDefault.mapTo(()) --> (_ => saveAnswers()),
        label(
          "Name",
          input(
            typ := "text",
            placeholder := "you name",
            value <-- nameVar.signal,
            onInput.mapToValue --> nameVar
          )
        ),
        div(
          cls := "question-list",
          Quiz.questionRows.map: row =>
            questionRow(row._1, row._2)
        ),
        div(
          cls := "actions",
          button("Save answers", typ := "submit"),
          button(
            "Clear answers",
            typ := "button",
            onClick.mapTo(()) --> (_ => answersVar.set(Quiz.emptyAnswers))
          ),
          button(
            "Refresh",
            typ := "button",
            onClick.mapTo(()) --> (_ => refreshAll())
          )
        )
      ),
      p(cls := "message", child.text <-- messageVar.signal),
      div(
        cls := "summary",
        h2("Summary"),
        div(
          cls := "summary-list",
          children <-- summaryVar.signal.map(_.questions.map(summaryRow))
        )
      ),
      h2("Stored users"),
      ul(
        cls := "quiz-list",
        children <-- usersVar.signal.map(_.map(userRow))
      )
    )

  def userRow(user: User): HtmlElement =
    val answerTexts = Quiz.questionRows.map: row =>
      val answer = user.answers.getOrElse(row._1, None)
      s"${row._2}: ${answerLabel(answer)}"

    li(
      cls := "quiz-row",
      div(
        strong(user.name),
        span(
          s" - ${answerTexts.mkString(" - ")}"
        )
      ),
      div(
        cls := "row-actions",
        button(
          "Load",
          onClick.mapTo(user) --> loadUser
        ),
        button(
          "Delete",
          onClick.mapTo(user) --> deleteUser
        )
      )
    )

  def questionRow(id: Quiz.Id, question: Quiz.Question): HtmlElement =
    val answerSignal = answersVar.signal.map(_.getOrElse(id, None))
    val radioName = s"answer-$id"

    label(
      cls := "question-row",
      span(cls := "question-text", question),
      span(
        cls := "radio-group",
        answerRadio(radioName, id, Some(true), "true", answerSignal),
        answerRadio(radioName, id, Some(false), "false", answerSignal),
        answerRadio(radioName, id, None, "No answer yet", answerSignal)
      )
    )

  def answerRadio(
      radioName: String,
      id: Quiz.Id,
      value: Option[Boolean],
      labelText: String,
      answerSignal: Signal[Option[Boolean]]
  ): HtmlElement =
    label(
      cls := "radio-option",
      input(
        typ := "radio",
        nameAttr := radioName,
        checked <-- answerSignal.map(_ == value),
        onInput.mapTo(value) --> (answer => setAnswer(id, answer))
      ),
      span(labelText)
    )

  def summaryRow(summary: QuizQuestionSummary): HtmlElement =
    div(
      cls := "summary-row",
      div(strong(summary.question)),
      div(
        cls := "summary-counts",
        span(s"True: ${summary.trueAnswers}"),
        span(s"False: ${summary.falseAnswers}"),
        span(s"No answer yet: ${summary.noAnswerYet}")
      )
    )

  def answerLabel(answer: Option[Boolean]): String =
    answer.fold("not answered")(_.toString)

  private def loadUser(user: User): Unit =
    nameVar.set(user.name)
    answersVar.set(Quiz.normalizeAnswers(user.answers))
    messageVar.set(s"Loaded ${user.name}")

  private def setAnswer(id: Quiz.Id, answer: Option[Boolean]): Unit =
    answersVar.update(_ + (id -> answer))

  private def saveAnswers(): Unit =
    val name = nameVar.now().trim

    if name.isEmpty then messageVar.set("Name is required")
    else
      val user = User(name, Quiz.normalizeAnswers(answersVar.now()))

      postJson[User, User]("/api/quizzes", user).foreach: saved =>
        answersVar.set(Quiz.normalizeAnswers(saved.answers))
        refreshAll()
        messageVar.set(s"Saved answers for $name")

  private def deleteUser(user: User): Unit =
    val query =
      s"name=${js.URIUtils.encodeURIComponent(user.name)}"

    postEmpty(s"/api/quizzes/delete?$query").foreach: _ =>
      if nameVar.now() == user.name then
        nameVar.set("")
        answersVar.set(Quiz.emptyAnswers)
      refreshAll()
      messageVar.set(s"Deleted ${user.name}")

  private def refreshAll(): Unit =
    refreshQuizzes()
    refreshSummary()

  private def refreshQuizzes(): Unit =
    getJson[Vector[User]]("/api/quizzes").foreach: users =>
      usersVar.set(users)
      messageVar.set(s"Loaded ${users.size} user record(s)")

  private def refreshSummary(): Unit =
    getJson[QuizSummary]("/api/quizzes/summary").foreach(summaryVar.set)

  def getJson[A: Reader](path: String): Future[A] =
    dom.fetch(apiBase + path).toFuture.flatMap(readResponse[A])

  def postJson[A: Reader, B: Writer](path: String, value: B): Future[A] =
    val headers = dom.Headers()
    headers.set("Content-Type", "application/json")

    val init = new dom.RequestInit {}
    init.method = dom.HttpMethod.POST
    init.headers = headers
    init.body = write(value)

    dom.fetch(apiBase + path, init).toFuture.flatMap(readResponse[A])

  def postEmpty(path: String): Future[Unit] =
    val init = new dom.RequestInit {}
    init.method = dom.HttpMethod.POST

    dom
      .fetch(apiBase + path, init)
      .toFuture
      .flatMap: response =>
        if response.ok then Future.successful(())
        else
          response
            .text()
            .toFuture
            .flatMap(text => Future.failed(RuntimeException(text)))

  def readResponse[A: Reader](response: dom.Response): Future[A] =
    response
      .text()
      .toFuture
      .flatMap: text =>
        if response.ok then Future.successful(read[A](text))
        else Future.failed(RuntimeException(text))
