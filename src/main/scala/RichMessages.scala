package uk.gov.hmrc.uniform

import java.time.LocalDate
import java.time.format.DateTimeFormatter.ofPattern

import cats.implicits._
import play.api.i18n._

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
        provider.messages(key, args: _*).some
      else
        none[String]
    }

    def many(key: String, args: Any*)(implicit provider: Messages): List[String] = {

      @annotation.tailrec
      def inner(cnt: Int = 2, list: List[String] = Nil): List[String] =
        get(s"$key.$cnt", args: _*) match {
          case Some(_) => inner(cnt + 1, provider.messages(s"$key.$cnt", args: _*) :: list)
          case None => list
        }

      List(key, s"$key.1").flatMap(get(_, args)) ++ inner().reverse
    }

    def extraMessage(key: String, args: Any*)
      (implicit lang: Lang, messages: Messages, extraMessages: ExtraMessages): String = {
      if (extraMessages.messages.get(key).nonEmpty) {
        extraMessages.messages(key)
      } else mo.apply(key, args)
    }

    def extraMessages(keys: Seq[String], args: Any*)
      (implicit lang: Lang, messages: Messages, extraMessages: ExtraMessages): String = {
      if (extraMessages.messages.get(keys(0)).nonEmpty) {
        extraMessages.messages(keys(0))
      } else mo.apply(keys, args)
    }
  }

  case class ExtraMessages(messages: Map[String, String] = Map.empty)

}
