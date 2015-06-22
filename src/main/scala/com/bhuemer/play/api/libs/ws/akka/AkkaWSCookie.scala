package com.bhuemer.play.api.libs.ws.akka

import akka.http.scaladsl.model.headers.HttpCookie
import play.api.libs.ws.WSCookie

class AkkaWSCookie(akkaCookie: HttpCookie) extends WSCookie {

  // -------------------------------------------- WSCookie methods

  override def underlying[T]: T = akkaCookie.asInstanceOf[T]

  /** Maybe returns the expiry date in milliseconds since January 1, 1970, 00:00:00 GMT */
  override def expires: Option[Long] = akkaCookie.expires.map(_.clicks)

  /** The domain */
  override def domain: String = akkaCookie.domain.getOrElse("")

  /** The maximum age */
  override def maxAge: Option[Int] = akkaCookie.maxAge.map(_.toInt)

  /** If the cookie is secure */
  override def secure: Boolean = akkaCookie.secure

  /** The cookie value */
  override def value: Option[String] = Some(akkaCookie.content)

  /** The cookie name */
  override def name: Option[String] = Some(akkaCookie.name)

  /** The path */
  override def path: String = akkaCookie.path.getOrElse("/")

  // -------------------------------------------- Object methods

  override def toString = s"AkkaWSCookie(underlying: $akkaCookie)"

}