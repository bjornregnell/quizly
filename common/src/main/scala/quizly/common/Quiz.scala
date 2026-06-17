package quizly.common

import upickle.default.*

case class User(id: User.Id, name: String, answers: Map[Quiz.Id, Option[Boolean]])
object User:
  type Id = String
  val unsavedId: Id = ""

  given ReadWriter[User] = macroRW

case class QuizQuestionSummary(
  id: Quiz.Id,
  trueAnswers: Int,
  falseAnswers: Int,
  noAnswerYet: Int
)
object QuizQuestionSummary:
  def empty(id: Quiz.Id): QuizQuestionSummary =
    QuizQuestionSummary(id, trueAnswers = 0, falseAnswers = 0, noAnswerYet = 0)

  given ReadWriter[QuizQuestionSummary] = macroRW


case class QuizSummary(questions: Vector[QuizQuestionSummary])
object QuizSummary:
  val empty = QuizSummary:
    Quiz.questionIds.map(QuizQuestionSummary.empty)

  given ReadWriter[QuizSummary] = macroRW


case class ServerConfig(debug: Boolean)
object ServerConfig:
  given ReadWriter[ServerConfig] = macroRW

object Quiz:
  type Id = Int
  type Question = String

  val questions: Map[Id, Question] = Map(
    1 -> "Kriget i Ukraina kommer att vara över inom ett år",
    2 -> "Sveriges nuvarande statsministern kommer att behålla makten efter nästa val",
    3 -> "Liberalerna kommer att lämna riksdagen i nästa val",
    4 -> "Ett nybyggt kärnkraftverk kommer att vara i drift i Sverige inom de närmaste 10 åren",
    5 -> "Sverige kommer att införa euron inom 20 år",
    6 -> "Elpriset i Skåne kommer att vara lägre år 2030 än idag",
    7 -> "Försäljningen av bensinbilar kommer att förbjudas i Sverige före 2035",
    8 -> "En svensk region kommer att införa helt avgiftsfri kollektivtrafik permanent före 2035",
    9 -> "Kontanter kommer att användas av mindre än 1 % av svenskarna i vardagen år 2035",
    10 -> "Minst ett svenskt universitet kommer att använda AI som obligatorisk examinator inom 10 år",
    11 -> "Sverige kommer att ha fler registrerade elbilar än bensinbilar före 2035",
    12 -> "Sverige kommer att bygga fler fängelseplatser än studentbostäder under de kommande 10 åren",
    13 -> "TikTok kommer att vara förbjudet i Sverige inom 10 år",
    14 -> "Sverige kommer att ha en kvinnlig statsminister igen före 2035",
    15 -> "Det kommer att finnas självkörande taxibilar i reguljär trafik i någon svensk stad före 2035",
    16 -> "Sverige kommer att vinna Eurovision igen före 2035",
    17 -> "Donald Trump kommer att avsluta sin nuvarande mandatperiod som USA:s president",
    18 -> "Ryssland kommer att ha samma president år 2030 som idag",
    19 -> "Taiwan kommer att vara självstyrande även år 2040",
    20 -> "The Vallkärra Greens kommer sälja huset och flytta till sin stuga när de gått i pension",
  )

  val questionIds: Vector[Id] =
    questions.keys.toVector.sorted

  val questionRows: Vector[(Id, Question)] =
    questionIds.map(id => id -> questions(id))

  def normalizeAnswers(answers: Map[Id, Option[Boolean]]): Map[Id, Option[Boolean]] =
    questionIds.map(id => id -> answers.getOrElse(id, None)).toMap

  val emptyAnswers: Map[Id, Option[Boolean]] =
    normalizeAnswers(Map.empty)

