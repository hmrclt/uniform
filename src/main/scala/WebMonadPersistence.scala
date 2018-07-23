package uk.gov.hmrc.uniform

import cats.implicits._
import play.api.libs.json._

import scala.collection.mutable.{Map => MMap}
import scala.concurrent._

/** WebMonads read in all their data at the start of the interaction with the
  * user and write it all out again at the end.
  * A major limitation at the moment is that this has to be Json values. This
  * was a bad design decision as if I'd used plain serialisation or pickling
  * then the programmer building the journey wouldn't have needed to worry about
  * writing Format converters.
  * The store should be tolerant of failure - the structure of objects is likely
  * to change during development and it's usually fine to just ask the user again.
  */
trait Persistence {
  def dataGet(session: String): Future[Map[String, JsValue]]
  def dataPut(session: String, dataIn: Map[String, JsValue]): Unit
}

/** A non-presistent persistence engine - fast to develop in (store gets purged on
  * recompile), but guaranteed to bring shame and ruin should you actually dare
  * to use this in production. Basically a thin wrapper around a mutable map.
  */
class JunkPersistence(implicit val ec: ExecutionContext) extends Persistence {

  private val data = MMap.empty[String,Map[String,JsValue]]

  def dataGet(session: String): Future[Map[String, JsValue]] =
    data.getOrElse(session, Map.empty[String,JsValue]).pure[Future]

  def dataPut(session: String, dataIn: Map[String, JsValue]): Unit =
    data(session) = dataIn
}
