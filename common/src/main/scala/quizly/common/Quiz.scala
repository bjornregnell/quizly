package quizly.common

import upickle.default.*

case class User(id: User.Id, name: String, answers: Map[Quiz.Id, Option[Boolean]])

case class QuizQuestionSummary(
  id: Quiz.Id,
  trueAnswers: Int,
  falseAnswers: Int,
  noAnswerYet: Int
)

case class QuizSummary(questions: Vector[QuizQuestionSummary])

case class ServerConfig(debug: Boolean)

object Quiz:
  type Id = Int
  type Question = String

  val questions: Map[Id, Question] = Map(
    1 -> "The war in Ukraine will end within the next year",
    2 -> "The current prime minister will remain in power after the next election",
    3 -> "A new nuclear power plant will be operational within the next 10 years",
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
  type Id = String
  val unsavedId: Id = ""

  given ReadWriter[User] = macroRW

object QuizQuestionSummary:
  def empty(id: Quiz.Id): QuizQuestionSummary =
    QuizQuestionSummary(id, trueAnswers = 0, falseAnswers = 0, noAnswerYet = 0)

  given ReadWriter[QuizQuestionSummary] = macroRW

object QuizSummary:
  val empty = QuizSummary:
    Quiz.questionIds.map(QuizQuestionSummary.empty)

  given ReadWriter[QuizSummary] = macroRW

object ServerConfig:
  given ReadWriter[ServerConfig] = macroRW
