package com.bhuemer.play.api.libs.ws.akka

import akka.http.scaladsl.model.headers.{Cookie, `Content-Type`}
import akka.http.scaladsl.model.{HttpCharsets, HttpHeader, HttpResponse}
import akka.util.ByteString
import play.api.libs.json.{Json, JsValue}
import play.api.libs.ws.{WSCookie, WSResponse}

import scala.xml.Elem

class AkkaWSResponse(akkaResponse: HttpResponse, rawBody: ByteString) extends WSResponse {

  // -------------------------------------------- WSResponse methods

  /**
   * Return all header values grouped by name. 
   */
  override def allHeaders: Map[String, Seq[String]] = {
    val headersByName: Map[String, Seq[HttpHeader]] = akkaResponse.headers.groupBy(_.name())
    headersByName.mapValues({
      headers => headers.map(_.value())
    })
  }

  /** Returns the underlying Akka HTTP Response */
  override def underlying[T]: T = akkaResponse.asInstanceOf[T]

  /** The response status code as plain integer value. */
  override def status: Int = akkaResponse.status.intValue()

  /** Returns something like `OK`, `Bad Request` or `Internal Server Error` - nothing more explanatory. */
  override def statusText: String = akkaResponse.status.reason()

  /** Returns the value of the given response header, if it is present. */
  override def header(key: String): Option[String] = akkaResponse.getHeader(key).map(_.value())

  /** Gets all the cookies from the response. */
  override def cookies: Seq[WSCookie] =
    akkaResponse.header[Cookie] match {
      case Some(Cookie(cookies)) => cookies.map({ cookie => new AkkaWSCookie(cookie) })
      case _ => Seq.empty
    }

  /** Gets only one cookie, using the cookie name. */
  override def cookie(name: String): Option[WSCookie] = cookies.find(_.name.contains(name))

  lazy val body: String = {
    val charset = akkaResponse.header[`Content-Type`].map({ header =>
      header.contentType.charset()
    }).getOrElse(HttpCharsets.`UTF-8`)
    rawBody.decodeString(charset.value)
  }

  /** The response body as Xml. */
  lazy val xml: Elem = ???

  /** The response body as Json. */
  lazy val json: JsValue = Json.parse(body)

  // -------------------------------------------- Object methods

  override def toString = s"AkkaWSResponse(underlying: $akkaResponse)"

}