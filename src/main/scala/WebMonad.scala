package uk.gov.hmrc.uniform

import java.time.LocalDate

import cats.data.{EitherT, RWST}
import cats.implicits._
import cats.{Monoid, Order}
import play.api._
import play.api.data.Forms._
import play.api.data.{Form, Mapping}
import play.api.http.Writeable
import play.api.i18n.Messages
import play.api.libs.json._
import play.api.mvc._
import play.twirl.api.Html

import scala.concurrent.{ExecutionContext, Future}
import scala.language.implicitConversions

trait FormHtml[A] {
  def asHtmlForm(key: String, form: Form[A])(implicit messages: Messages): Html
}

package object webmonad {

  implicit val htmlSemigroup: Monoid[Html] = new Monoid[Html] {
    def combine(x: Html, y: Html): Html = Html(x.toString ++ y.toString)
    def empty: Html = Html("")
  }

  implicit val orderedLD: Order[LocalDate] = new Order[LocalDate] {
    def compare(a: LocalDate, b: LocalDate): Int = a compareTo b
  }

  // State is read once, threaded through the webmonads and the final state is
  // written back at the end of execution
  type DbState = Map[String, JsValue]

  // write out Pages (path state)
  type Path = List[String]

  type WebInner[A] = RWST[Future, (JourneyConfig, String, Request[AnyContent]), Path, (Path, DbState), A]
  type WebMonad[A] = EitherT[WebInner, Result, A]

  lazy val log = play.api.Logger("uniform2")

  def webMonad[A](
    f: (String, Request[AnyContent], Path, DbState) => Future[(Option[String], Path, DbState, Either[Result, A])]
  )(implicit ec: ExecutionContext): WebMonad[A] =
    EitherT[WebInner, Result, A] {
      RWST { case ((config, pathA, r), (pathB, st)) =>
        f(pathA, r, pathB, st).map { case (a, b, c, d) => (a.toList, (b, c), d) }
      }
    }

  def getRequest(implicit ec: ExecutionContext): WebMonad[Request[AnyContent]] =
    webMonad { (a, request, path, db) =>
      (none[String], path, db, request.asRight).pure[Future]
    }

  def getPath(implicit ec: ExecutionContext): WebMonad[List[String]] =
    webMonad{ (a, request, path, db) =>
      (none[String], path, db, path.asRight).pure[Future]
    }

  def read[A](key: String)(implicit reads: Reads[A], ec: ExecutionContext): WebMonad[Option[A]] =
    webMonad { (_, _, path, db) =>
      (none[String], path, db, db.get(key).map {
        _.as[A]
      }.asRight[Result]).pure[Future]
    }

  def write[A](key: String, value: A)(implicit writes: Writes[A], ec: ExecutionContext): WebMonad[Unit] =
    webMonad { (_, _, path, db) =>
      (none[String], path, db + (key -> Json.toJson(value)), ().asRight[Result]).pure[Future]
    }

  def update[A](key: String)(
    f: Option[A] => Option[A]
  )(implicit format: Format[A], ec: ExecutionContext): WebMonad[Unit] = webMonad { (_, _, path, db) =>
    f(db.get(key).map {
      _.as[A]
    }) match {
      case Some(x) =>
        (none[String], path, db + (key -> Json.toJson(x)), ().asRight[Result]).pure[Future]
      case None =>
        (none[String], path, db - key, ().asRight[Result]).pure[Future]
    }
  }

  def clear(key: String)(implicit ec: ExecutionContext): WebMonad[Unit] =
    webMonad { (_, _, path, db) =>
      (none[String], path, db - key, ().asRight[Result]).pure[Future]
    }

  def clear(implicit ec: ExecutionContext): WebMonad[Unit] =
    webMonad { (_, _, path, db) =>
      (none[String], path, Map.empty[String,JsValue], ().asRight[Result]).pure[Future]
    }


  def cachedFuture[A]
    (cacheId: String)
    (f: => Future[A])
    (implicit format: Format[A], ec: ExecutionContext): WebMonad[A] =
    webMonad { (id, request, path, db) =>
      val cached = for {
        record <- db.get(cacheId)
      } yield
          (none[String], path, db, record.as[A].asRight[Result])
            .pure[Future]

      cached.getOrElse {
        f.map { result =>
          val newDb = db + (cacheId -> Json.toJson(result))
          (none[String], path, newDb, result.asRight[Result])
        }
      }
    }  

  def cachedFutureOpt[A]
    (cacheId: String)
    (f: => Future[Option[A]])
    (implicit format: Format[A], ec: ExecutionContext): WebMonad[Option[A]] =
    webMonad { (id, request, path, db) =>
      val cached = for {
        record <- db.get(cacheId)
      } yield
          (none[String], path, db, record.as[A].some.asRight[Result])
            .pure[Future]

      cached.getOrElse {
        f.map { result =>
          val newDb = result.map { r => 
            db + (cacheId -> Json.toJson(r))
          } getOrElse db
          (none[String], path, newDb, result.asRight[Result])
        }
      }
    }  


}

package webmonad {

  sealed trait Control
  case object Add extends Control
  case object Done extends Control
  case class Delete(i: Int) extends Control {
    override def toString = s"Delete.$i"
  }
  case class Edit(i: Int) extends Control {
    override def toString = s"Edit.$i"
  }


  sealed trait JourneyMode

  /** Only advance through the journey one page at a time. This is the normal
    * behaviour. 
    */
  case object SingleStep extends JourneyMode

  /** Advance through to the latest possible page using whatever defaults or
    * persisted data is available. If you have a check-your-answers page it is
    * possible to simulate a hub-and-spoke design using this option with the
    * added advantage of knowing that the state will always be consistent with
    * the logic. Care must be taken with listing pages however.
    */
  case object LeapAhead extends JourneyMode

  case class JourneyConfig(
    mode: JourneyMode = SingleStep,

    /** If enabled a JSON argument containing the state can be supplied. This
      * is intended for testing and may represent a security issue if used on a
      * live service. 
      */
    allowRestoreState: Boolean = false
  )

  trait WebMonadController extends Controller with i18n.I18nSupport {

    implicit def ec: ExecutionContext

    def execute[A](f: => Future[A]): WebMonad[A] =
      webMonad{ (id, request, path, db) =>
        f.map{ e =>
          (none[String], path, db, e.asRight[Result])
        }
      }

    implicit def resultToWebMonad[A](result: Result): WebMonad[A] =

      EitherT[WebInner, Result, A] {
        RWST { case ((_, _, r), (path, st)) =>
          (
            List.empty[String],
            (path, st),
            result.asLeft[A]
          ).pure[Future]
        }
      }

    def redirect(target: String): WebMonad[Unit] = webMonad {
      (id, request, path, db) =>
      (none[String], path, db, Redirect(s"./$target").asLeft[Unit]).pure[Future]
    }

    def many[A](
      id: String,
      min: Int = 0,
      max: Int = 1000,
      default: List[A] = List.empty[A],
      deleteConfirmation: A => WebMonad[Boolean] = {_: A => true.pure[WebMonad]},
      itemEdit: Option[A => WebMonad[A]] = None
    )(listingPage: (String, Int, Int, List[A]) => WebMonad[Control])
      (wm: String => WebMonad[A])
      (implicit format: Format[A]): WebMonad[List[A]] =
    {
      val addId = s"add-$id"
      val addPage: WebMonad[A] = wm(addId)

      val editId = s"edit-$id"
      val editPage: WebMonad[A] = wm(editId)

      val dataKey = s"${id}_data"
      val addProgram: WebMonad[List[A]] = for {
        addItem <- addPage
        _ <- update[List[A]](dataKey) { x => {
          x.getOrElse(default) :+ addItem
        }.some }

        _ <- clear(addId)
        _ <- clear(id)
        i <- many(id, min, max)(listingPage)(wm)
      } yield i

      def allItems: EitherT[WebInner, Result, List[A]] = read[List[A]](dataKey).map{_.getOrElse(default)}

      def replaceItem(index: Int, item: A) = addItem(index, List(item))
      def removeItem(index: Int) = addItem(index, Nil)
      def addItem(index: Int, item: List[A]): WebMonad[Unit] =
        update[List[A]](dataKey) { x =>
          {
            val all = x.getOrElse(default)
            all.take(index) ++ item ++ all.drop(index + 1)
          }.some
        }

      def deleteProgram(index: Int): WebMonad[List[A]] = for {
        allItems <- allItems
        _ <- removeItem(index) when deleteConfirmation(allItems(index))
        _ <- clear(id)
        _ <- redirect(id)
      } yield {
        throw new IllegalStateException("Redirect failing for deletion!")
      }

      def editProgram(index: Int): WebMonad[List[A]] = {
        itemEdit match {
          case Some(editor) =>
            log.debug(s"Editing $index")
            for {
              allItems <- allItems
              changedItem <- editor(allItems(index))
              _ <- replaceItem(index, changedItem)
              _ <- clear(editId)
              _ <- clear(id)
              i <- many(id, min, max)(listingPage)(wm)
            } yield i
          case _ =>
            log.debug(s"Not editing $index")
            clear(id) >> redirect(id) >> List.empty[A].pure[WebMonad]
        }
      }

      for {
        items <- allItems
        res <- if (items.size < min) addProgram
               else listingPage(id,min,max,items).flatMap {
                 case Add => addProgram
                 case Edit(index) => editProgram(index)
                 case Done => allItems
                 case Delete(index) => deleteProgram(index)
               }
      } yield res

    }

    def runInner(request: Request[AnyContent])(program: WebMonad[Result])(id: String)(
      load: String => Future[Map[String, JsValue]],
      save: (String, Map[String, JsValue]) => Future[Unit],
      config: JourneyConfig = JourneyConfig()
    ): Future[Result] = {
        request.session.get("uuid").fold {
          Redirect(id).withSession {
            request.session + ("uuid" -> java.util.UUID.randomUUID.toString)
          }.pure[Future]
        } { sessionUUID =>
          load(sessionUUID).flatMap { initialData =>

            val data = request.getQueryString("restoreState")
              .filter(_ => config.allowRestoreState)
              .fold(initialData){
                Json.parse(_) match {
                  case JsObject(v) => v.toList.toMap
                  case _ => throw new IllegalArgumentException
                }
              }

            program.value
              .run((config, id, request), (List.empty[String], data))
              .flatMap { case (_, (path, state), a) => {
                if (state != initialData) {
                  save(sessionUUID, state)
                }
                else
                  ().pure[Future]
              }.map { _ =>
                a.fold(identity, identity)
              }
              }
          }
        }
    }

    def run(program: WebMonad[Result])(id: String)(
      load: String => Future[Map[String, JsValue]],
      save: (String, Map[String, JsValue]) => Future[Unit]
    ) = Action.async {
      request => runInner(request)(program)(id)(load, save)
    }

    def formPage[A, B: Writeable](id: String)(
      mapping: Mapping[A],
      default: Option[A] = None,
      configOverride: JourneyConfig => JourneyConfig = identity
    )(
      render: (List[String], Form[A], Request[AnyContent]) => B
    )(implicit f: Format[A]): WebMonad[A] = {
      val form = Form(single(id -> mapping))
      val formWithDefault =
        default.map{form.fill}.getOrElse(form)

      EitherT[WebInner, Result, A] {
        RWST { case ((configIn, targetId, r), (path, st)) =>

          val config = configOverride(configIn)

          implicit val request: Request[AnyContent] = r

          val post = request.method.toLowerCase == "post"
          val method = request.method.toLowerCase
          val data = st.get(id)
          log.debug(s"$id :: method: $method, data: $data, targetId: $targetId, default: $default");
          {
            (method, data, targetId) match {
              case ("get", _, "") =>
                log.debug(s"$id :: empty URI, redirecting to ./$id")
                (
                  id.pure[List],
                  (path, st),
                  Redirect(s"./$id").asLeft[A]
                )
              case ("get", None, `id`) =>
                log.debug(s"$id :: nothing in database, step in URI, render empty form")
                (
                  id.pure[List],
                  (path, st),
                  Ok(render(path, formWithDefault, implicitly)).asLeft[A]
                )
              case ("get", Some(json), `id`) =>
                log.debug(s"$id :: something in database, step in URI, user revisting old page, render filled in form")
                (
                  id.pure[List],
                  (path, st),
                  Ok(render(path, form.fill(json.as[A]), implicitly)).asLeft[A]
                )
              case ("get", Some(json), _) =>
                log.debug(s"$id :: something in database, not step in URI, pass through")
                (
                  id.pure[List],
                  (id :: path, st),
                  json.as[A].asRight[Result]
                )
              case ("post", _, `id`) => form.bindFromRequest.fold(
                formWithErrors => {
                  log.debug(s"$id :: errors in form, badrequest")
                  (
                    id.pure[List],
                    (path, st),
                    BadRequest(render(path, formWithErrors, implicitly)).asLeft[A]
                  )
                },
                formData => {
                  log.debug(s"$id :: form data passed, all good, update DB and pass through")
                  (
                    id.pure[List],
                    (id :: path, st + (id -> Json.toJson(formData))),
                    formData.asRight[Result]
                  )
                }
              )
              case ("post", Some(json), _) if path.contains(targetId) && (config.mode == SingleStep || id.startsWith("edit-")) =>
                log.debug(s"$id :: something in database, previous page submitted, single step => redirect to ./$id")
                (
                  id.pure[List],
                  (id :: path, st),
                  Redirect(s"./$id").asLeft[A]
                )
              case ("post", Some(json), _) =>
                log.debug(s"$id :: something in database, posting, not step in URI nor previous page -> pass through")
                (
                  id.pure[List],
                  (id :: path, st),
                  json.as[A].asRight[Result]
                )
              case ("post" | "get", None, _) if config.mode == LeapAhead && default.isDefined && !id.startsWith("edit-") =>
                log.debug(s"$id :: nothing in db but leap ahead is set and default is defined -> pass through")
                (
                  id.pure[List],
                  (id :: path, st + (id -> Json.toJson(default.get))),
                  default.get.asRight[Result]
                )
              case ("post", _, _) | ("get", _, _) =>
                log.debug(s"$id :: some other scenario, redirecting to ./$id")
                (
                  id.pure[List],
                  (path, st),
                  Redirect(s"./$id").asLeft[A]
                )
            }
          }.pure[Future]
        }
      }
    }

    implicit class RichWebMonadBoolean(wmb: WebMonad[Boolean]) {
      def andIfTrue[A](next: WebMonad[A]): WebMonad[Option[A]] = for {
        opt <- wmb
        ret <- if (opt) next map {_.some} else none[A].pure[WebMonad]
      } yield ret
    }

    implicit class RichWebMonoid[A](wm: WebMonad[A])(implicit monoid: Monoid[A]) {
      def emptyUnless(b: => Boolean): WebMonad[A] =
        if(b) wm else monoid.empty.pure[WebMonad]

      def emptyUnless(wmb: WebMonad[Boolean]): WebMonad[A] = for {
        opt <- wmb
        ret <- if (opt) wm else monoid.empty.pure[WebMonad]
      } yield ret
    }

    implicit class RichWebMonad[A](wm: WebMonad[A]) {
      def when(b: => Boolean): WebMonad[Option[A]] =
        if(b) wm.map{_.some} else none[A].pure[WebMonad]

      def when(wmb: WebMonad[Boolean]): WebMonad[Option[A]] = for {
        opt <- wmb
        ret <- if (opt) wm map {_.some} else none[A].pure[WebMonad]
      } yield ret
    }

    def when[A](b: => Boolean)(wm: WebMonad[A]): WebMonad[Option[A]] =
      if(b) wm.map{_.some} else none[A].pure[WebMonad]

  }
}
