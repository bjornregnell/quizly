package quizly.common

import upickle.default.*

case class User(id: User.Id, answers: Map[Quiz.Id, Option[Boolean]])
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

  private var id = 0
  def nextId(): Int = {id +=1; id}

  val questions: Map[Id, Question] = Map(
    nextId() -> "Kriget i Ukraina kommer att vara över inom ett år.",
    nextId() -> "Sveriges nuvarande statsministern kommer att behålla makten efter nästa val.",
    nextId() -> "Liberalerna kommer att lämna riksdagen i nästa val.",
    nextId() -> "Ett nybyggt kärnkraftverk kommer att vara i drift i Sverige inom de närmaste 10 åren.",
    nextId() -> "Sverige kommer att införa euron inom 20 år.",
    nextId() -> "Elpriset i Skåne kommer att vara lägre om 5 år än idag.",
    nextId() -> "Försäljningen av bensinbilar kommer att förbjudas i Sverige inom 10 år.",
    nextId() -> "En svensk region kommer att införa helt avgiftsfri kollektivtrafik permanent inom 10 år.",
    nextId() -> "Kontanter kommer att användas av mindre än 1 % av svenskarna i vardagen inom 10 år.",
    nextId() -> "Minst ett svenskt universitet kommer att använda AI som obligatorisk examinator inom 10 år.",
    nextId() -> "Sverige kommer att ha fler registrerade elbilar än bensinbilar inom 10 år.",
    nextId() -> "Sverige kommer att bygga fler fängelseplatser än studentbostäder under de kommande 10 åren.",
    nextId() -> "TikTok kommer att vara förbjudet i Sverige inom 10 år.",
    nextId() -> "Sverige kommer att ha en kvinnlig statsminister igen inom 10 år.",
    nextId() -> "Det kommer att finnas självkörande taxibilar i reguljär trafik i någon svensk stad inom 10 år.",
    nextId() -> "Sverige kommer att vinna Eurovision igen inom 10 år.",
    nextId() -> "Donald Trump kommer att avsluta sin nuvarande mandatperiod som USA:s president.",
    nextId() -> "Ryssland kommer att ha samma president om 5 år som idag.",
    nextId() -> "Taiwan kommer att vara självstyrande även om 15 år.",
    nextId() -> "Enligt V-Dem:s klassificering 'Regimes of the World' klassas USA som 'elektoral autokrati' inom 5 år.",
  )

  val questionIds: Vector[Id] =
    questions.keys.toVector.sorted

  val questionRows: Vector[(Id, Question)] =
    questionIds.map(id => id -> questions(id))

  def normalizeAnswers(answers: Map[Id, Option[Boolean]]): Map[Id, Option[Boolean]] =
    questionIds.map(id => id -> answers.getOrElse(id, None)).toMap

  val emptyAnswers: Map[Id, Option[Boolean]] =
    normalizeAnswers(Map.empty)

