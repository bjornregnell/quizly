package quizly.common

import upickle.default.*

case class Quiz(name: String, question: String, answer: Option[Boolean])

case class QuizQuestionSummary(
  question: String,
  trueAnswers: Int,
  falseAnswers: Int,
  noAnswerYet: Int
)

case class QuizSummary(questions: Vector[QuizQuestionSummary])

object Quiz:
  val defaultQuestion = "The war will end in 2026"
  val governmentQuestion = "The current government will remain in power after the election"
  val questions = Vector(defaultQuestion, governmentQuestion)

  given ReadWriter[Quiz] = macroRW

object QuizQuestionSummary:
  def empty(question: String): QuizQuestionSummary =
    QuizQuestionSummary(question, trueAnswers = 0, falseAnswers = 0, noAnswerYet = 0)

  given ReadWriter[QuizQuestionSummary] = macroRW

object QuizSummary:
  val empty = QuizSummary(Quiz.questions.map(QuizQuestionSummary.empty))

  given ReadWriter[QuizSummary] = macroRW
