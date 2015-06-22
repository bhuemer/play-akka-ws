package play.api.libs.ws.akka

import akka.http.scaladsl.model.headers.{HttpCookie, Cookie, `Content-Type`}
import akka.http.scaladsl.model.{HttpCharsets, HttpHeader, HttpResponse}
import akka.util.ByteString
import play.api.libs.json.{Json, JsValue}
import play.api.libs.ws.{WSResponseHeaders, WSCookie, WSResponse}

import scala.xml.Elem

/**
 *
 */
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
  override def cookies: Seq[WSCookie] = {
    akkaResponse.header[Cookie] match {
      case Some(Cookie(cookies)) => cookies.map({ cookie => new AkkaWSCookie(cookie) })
      case _ => Seq.empty
    }
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
  lazy val xml: Elem = {
    // TODO: Possibly use reflection to access Play's SAX parser configuration
    scala.xml.XML.loadString(body)
  }

  /** The response body as Json. */
  lazy val json: JsValue = Json.parse(body)

  // -------------------------------------------- Object methods

  override def toString = s"AkkaWSResponse(underlying: $akkaResponse)"

}

/**
 * Wrapper around an Akka HTTP response that only exposes response headers and information related to them.
 *
 * @author Bernhard Huemer
 */
class AkkaWSResponseHeaders(akkaResponse: HttpResponse) extends WSResponseHeaders {

  // -------------------------------------------- WSResponseHeaders methods

  /** The response status code as plain integer value. */
  override def status: Int = akkaResponse.status.intValue()

  /**
   * Return all header values grouped by name.
   */
  override def headers: Map[String, Seq[String]] = {
    // In the Play framework each HTTP header can contain many values, with Akka, however,
    // each header only contains one value .. so we kind of assume that it contains many
    // headers with the same name, if there's multiple values for one header.
    val headersByName: Map[String, Seq[HttpHeader]] = akkaResponse.headers.groupBy(_.name())
    headersByName.mapValues({
      headers => headers.map(_.value())
    })
  }

  // -------------------------------------------- Object methods

  override def toString = s"AkkaWSResponseHeaders(underlying: $akkaResponse)"

}

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