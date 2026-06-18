package quizly.client

import quizly.common.{Quiz, QuizQuestionSummary, QuizSummary, ServerConfig, User}
import upickle.default.*

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.scalajs.js
import scala.scalajs.js.Thenable.Implicits.*
import scala.util.{Failure, Success}

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

  val pollIntervalMs = 3000
  val autoSaveDebounceMs = 400

  private val userIdVar = Var(User.unsavedId)
  private val answersVar = Var(Quiz.emptyAnswers)
  private val debugVar = Var(false)
  private val usersVar = Var(Vector.empty[User])
  private val summaryVar = Var(QuizSummary.empty)
  private val messageVar = Var("Ready")
  private val saveRequests = new EventBus[Unit]

  def main(args: Array[String]): Unit =
    renderOnDomContentLoaded(
      dom.document.getElementById("app"),
      mainView
    )
    refreshConfigAndData()

  def mainView: HtmlElement =
    div(
      cls := "app",
      EventStream.periodic(pollIntervalMs) --> Observer(_ => refreshAll()),
      saveRequests.events.debounce(autoSaveDebounceMs) --> Observer(_ =>
        saveAnswers()
      ),
      h1(
        child.text <-- debugVar.signal.map:
          case true  => "Quizly Debugger"
          case false => "Quizly"
      ),
      form(
        cls := "editor",
        onSubmit.preventDefault.mapTo(()) --> (_ => ()),
        div(
          cls := "question-list",
          Quiz.questionRows.map: row =>
            questionRow(row._1, row._2)
        ),
        div(
          cls := "actions",
          button(
            "Danger: Clear all my answers",
            typ := "button",
            cls := "danger",
            disabled <-- answersVar.signal.map(_.values.forall(_.isEmpty)),
            onClick.mapTo(()) --> (_ => clearAnswers())
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
      child <-- debugVar.signal.map:
        case true  => storedUsersView
        case false => emptyNode
    )

  def storedUsersView: HtmlElement =
    div(
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
        strong(user.id),
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

  def majorityClass(summary: QuizQuestionSummary): String =
    if summary.trueAnswers > summary.falseAnswers then "majority-true"
    else if summary.falseAnswers > summary.trueAnswers then "majority-false"
    else if summary.trueAnswers > 0 then "majority-tie"
    else ""

  def summaryRow(summary: QuizQuestionSummary): HtmlElement =
    val question = Quiz.questions.getOrElse(summary.id, s"Question ${summary.id}")

    div(
      cls := "summary-row",
      div(
        cls := List("summary-question", majorityClass(summary))
          .filter(_.nonEmpty)
          .mkString(" "),
        strong(question)
      ),
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
    userIdVar.set(user.id)
    answersVar.set(Quiz.normalizeAnswers(user.answers))
    messageVar.set(s"Loaded ${user.id}")

  private def setAnswer(id: Quiz.Id, answer: Option[Boolean]): Unit =
    answersVar.update(_ + (id -> answer))
    saveRequests.emit(())

  private def clearAnswers(): Unit =
    answersVar.set(Quiz.emptyAnswers)
    saveRequests.emit(())

  private def saveAnswers(): Unit =
    val user =
      User(userIdVar.now(), Quiz.normalizeAnswers(answersVar.now()))

    handle("Saving answers")(postJson[User, User]("/api/quizzes", user)): saved =>
      userIdVar.set(saved.id)
      messageVar.set("Answers saved")

  private def deleteUser(user: User): Unit =
    val query =
      s"id=${js.URIUtils.encodeURIComponent(user.id)}"

    handle(s"Deleting ${user.id}")(postEmpty(s"/api/quizzes/delete?$query")): _ =>
      if userIdVar.now() == user.id then
        userIdVar.set(User.unsavedId)
        answersVar.set(Quiz.emptyAnswers)
      refreshAll()
      messageVar.set(s"Deleted ${user.id}")

  private def refreshAll(): Unit =
    if debugVar.now() then refreshQuizzes()
    refreshSummary()

  private def refreshConfigAndData(): Unit =
    handle("Loading server config")(getJson[ServerConfig]("/api/config")): config =>
      debugVar.set(config.debug)
      refreshAll()

  private def refreshQuizzes(): Unit =
    handle("Loading user records")(getJson[Vector[User]]("/api/quizzes"))(usersVar.set)

  private def refreshSummary(): Unit =
    handle("Loading summary")(getJson[QuizSummary]("/api/quizzes/summary"))(summaryVar.set)

  def handle[A](action: String)(future: Future[A])(onSuccess: A => Unit): Unit =
    future.onComplete:
      case Success(value) => onSuccess(value)
      case Failure(error) =>
        messageVar.set(s"$action failed: ${errorMessage(error)}")

  def errorMessage(error: Throwable): String =
    Option(error.getMessage).filter(_.nonEmpty).getOrElse(error.toString)

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
