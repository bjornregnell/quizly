package quizly.client

import quizly.common.{Quiz, QuizQuestionSummary, QuizSummary}
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
  private val answersVar = Var(Map.empty[String, Option[Boolean]])
  private val quizzesVar = Var(Vector.empty[Quiz])
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
          Quiz.questions.map: question =>
            questionRow(question)
        ),
        div(
          cls := "actions",
          button("Save answers", typ := "submit"),
          button(
            "Clear answers",
            typ := "button",
            onClick.mapTo(()) --> (_ => answersVar.set(Map.empty))
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
      h2("Stored quizzes"),
      ul(
        cls := "quiz-list",
        children <-- quizzesVar.signal.map(_.map(quizRow))
      )
    )

  def quizRow(quiz: Quiz): HtmlElement =
    li(
      cls := "quiz-row",
      div(
        strong(quiz.name),
        span(
          s" - ${quiz.question} - ${quiz.answer.fold("not answered")(_.toString)}"
        )
      ),
      div(
        cls := "row-actions",
        button(
          "Load",
          onClick.mapTo(quiz) --> loadQuiz
        ),
        button(
          "Delete",
          onClick.mapTo(quiz) --> deleteQuiz
        )
      )
    )

  def questionRow(question: String): HtmlElement =
    val answerSignal = answersVar.signal.map(_.getOrElse(question, None))
    val radioName = s"answer-${Quiz.questions.indexOf(question)}"

    label(
      cls := "question-row",
      span(cls := "question-text", question),
      span(
        cls := "radio-group",
        answerRadio(radioName, question, Some(true), "true", answerSignal),
        answerRadio(radioName, question, Some(false), "false", answerSignal),
        answerRadio(radioName, question, None, "No answer yet", answerSignal)
      )
    )

  def answerRadio(
      radioName: String,
      question: String,
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
        onInput.mapTo(value) --> (answer => setAnswer(question, answer))
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

  private def loadQuiz(quiz: Quiz): Unit =
    val answersByQuestion = quizzesVar
      .now()
      .filter(_.name == quiz.name)
      .map(quiz => quiz.question -> quiz.answer)
      .toMap

    nameVar.set(quiz.name)
    answersVar.set(answersByQuestion)
    messageVar.set(s"Loaded ${quiz.name}")

  private def setAnswer(question: String, answer: Option[Boolean]): Unit =
    answersVar.update(_ + (question -> answer))

  private def saveAnswers(): Unit =
    val name = nameVar.now().trim

    if name.isEmpty then messageVar.set("Name is required")
    else
      val answers = answersVar.now()
      val quizzes = Quiz.questions.map: question =>
        Quiz(name, question, answers.getOrElse(question, None))

      Future
        .sequence(
          quizzes.map(quiz => postJson[Quiz, Quiz]("/api/quizzes", quiz))
        )
        .foreach: saved =>
          answersVar.set(saved.map(quiz => quiz.question -> quiz.answer).toMap)
          refreshAll()
          messageVar.set(s"Saved ${saved.size} answer(s) for $name")

  private def deleteQuiz(quiz: Quiz): Unit =
    val query =
      s"name=${js.URIUtils.encodeURIComponent(quiz.name)}&question=${js.URIUtils.encodeURIComponent(quiz.question)}"

    postEmpty(s"/api/quizzes/delete?$query").foreach: _ =>
      if nameVar.now() == quiz.name then
        answersVar.update(_ + (quiz.question -> None))
      if nameVar.now() == quiz.name && quizzesVar
          .now()
          .count(_.name == quiz.name) <= 1
      then nameVar.set("")
      refreshAll()
      messageVar.set(s"Deleted ${quiz.name} - ${quiz.question}")

  private def refreshAll(): Unit =
    refreshQuizzes()
    refreshSummary()

  private def refreshQuizzes(): Unit =
    getJson[Vector[Quiz]]("/api/quizzes").foreach: quizzes =>
      quizzesVar.set(quizzes)
      messageVar.set(s"Loaded ${quizzes.size} quiz record(s)")

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
