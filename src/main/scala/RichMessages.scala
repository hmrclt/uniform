package uk.gov.hmrc.uniform

import play.api.i18n._
import play.api.data.Forms._
import play.api.data._
import play.api.data.format.Formats._
import cats.implicits._
import java.time.LocalDate
import java.time.format.DateTimeFormatter.ofPattern

package object playutil {

  implicit class RichLocalDate(ld: LocalDate) {

    def suffix: String = {ld.getDayOfMonth % 10} match {
      case 1 => "st"
      case 2 => "nd"
      case 3 => "rd"
      case _ => "th"
    }

    def format(fmt: String): String = {
      val fmt2 = fmt.replace("!", "'" ++ suffix ++ "'")
      ld.format(ofPattern(fmt2))
    }
  }

  implicit class RichMessages(mo: Messages.type) {

    def get(key: String, args: Any*)(implicit provider: Messages): Option[String] = {
      if (provider.isDefinedAt(key))
        provider.messages(key, args:_*).some
      else
        none[String]
    }

    def many(
      key: String,
      args: Any*)(implicit provider: Messages): List[String] =
    {

      @annotation.tailrec
      def inner(cnt: Int = 2, list: List[String] = Nil): List[String] =
        get(s"$key.$cnt", args:_*) match {
          case Some(_) => inner(cnt+1, provider.messages(s"$key.$cnt", args:_*) :: list)
          case None       => list
        }

      List(key, s"$key.1").map(get(_, args)).flatten ++ inner().reverse
    }

  }

}
