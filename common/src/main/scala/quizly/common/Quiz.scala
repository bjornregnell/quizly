package quizly.common

import upickle.default.*

case class User(name: String, answers: Map[Quiz.Id, Option[Boolean]])

case class QuizQuestionSummary(
  id: Quiz.Id,
  question: Quiz.Question,
  trueAnswers: Int,
  falseAnswers: Int,
  noAnswerYet: Int
)

case class QuizSummary(questions: Vector[QuizQuestionSummary])

object Quiz:
  type Id = Int
  type Question = String

  val defaultQuestionId = 1
  val governmentQuestionId = 2

  val questions: Map[Id, Question] = Map(
    defaultQuestionId -> "The war will end in 2026",
    governmentQuestionId -> "The current government will remain in power after the election"
  )

  val questionIds: Vector[Id] =
    questions.keys.toVector.sorted

  val questionRows: Vector[(Id, Question)] =
    questionIds.map(id => id -> questions(id))

  def normalizeAnswers(answers: Map[Id, Option[Boolean]]): Map[Id, Option[Boolean]] =
    questionIds.map(id => id -> answers.getOrElse(id, None)).toMap

  val emptyAnswers: Map[Id, Option[Boolean]] =
    normalizeAnswers(Map.empty)

object User:
  given ReadWriter[User] = macroRW

object QuizQuestionSummary:
  def empty(id: Quiz.Id, question: Quiz.Question): QuizQuestionSummary =
    QuizQuestionSummary(id, question, trueAnswers = 0, falseAnswers = 0, noAnswerYet = 0)

  given ReadWriter[QuizQuestionSummary] = macroRW

object QuizSummary:
  val empty = QuizSummary:
    Quiz.questionRows.map: row =>
      QuizQuestionSummary.empty(row._1, row._2)

  given ReadWriter[QuizSummary] = macroRW
