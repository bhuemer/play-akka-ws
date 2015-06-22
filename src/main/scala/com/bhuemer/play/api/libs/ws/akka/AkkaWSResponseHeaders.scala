package com.bhuemer.play.api.libs.ws.akka

import akka.http.scaladsl.model.{HttpHeader, HttpResponse}
import play.api.libs.ws.WSResponseHeaders

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