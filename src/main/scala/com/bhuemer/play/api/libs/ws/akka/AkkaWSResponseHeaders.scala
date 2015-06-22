package com.bhuemer.play.api.libs.ws.akka

import akka.http.scaladsl.model.{HttpHeader, HttpResponse}
import play.api.libs.ws.WSResponseHeaders

class AkkaWSResponseHeaders(akkaResponse: HttpResponse) extends WSResponseHeaders {

  // -------------------------------------------- WSResponseHeaders methods

  /** The response status code as plain integer value. */
  override def status: Int = akkaResponse.status.intValue()

  /**
   * Return all header values grouped by name.
   */
  override def headers: Map[String, Seq[String]] = {
    val headersByName: Map[String, Seq[HttpHeader]] = akkaResponse.headers.groupBy(_.name())
    headersByName.mapValues({
      headers => headers.map(_.value())
    })
  }

  // -------------------------------------------- Object methods

  override def toString = s"AkkaWSResponseHeaders(underlying: $akkaResponse)"

}