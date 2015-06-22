package com.bhuemer.play.api.libs.ws.akka

import akka.http.scaladsl.model.HttpHeader.ParsingResult
import akka.http.scaladsl.model.StatusCodes.Redirection
import akka.http.scaladsl.model.headers.Location
import akka.http.scaladsl.model._
import akka.stream.FlowMaterializer
import akka.stream.scaladsl.{Flow, Sink, Source}
import akka.util.ByteString
import com.bhuemer.play.api.libs.ws.akka.streams.Streams
import play.api.libs.iteratee.Enumerator
import play.api.libs.ws._

import scala.concurrent.{ExecutionContext, Future}

/**
 *
 */
case class AkkaWSRequestHolder(
    connection: Flow[HttpRequest, HttpResponse, _],
    uri: Uri,
    method: String,
    body: WSBody = EmptyBody,
    entity: RequestEntity = HttpEntity.Empty,
    headers: Map[String, Seq[String]] = Map(),
    calc: Option[WSSignatureCalculator] = None,
    auth: Option[(String, String, WSAuthScheme)] = None,
    followRedirects: Option[Boolean] = None,
    requestTimeout: Option[Int] = None,
    virtualHost: Option[String] = None,
    proxyServer: Option[WSProxyServer] = None)(implicit materializer: FlowMaterializer)
   extends WSRequestHolder {

  /** The URL for this request */
  override lazy val url: String = uri.toString()

  /** The query string for this request */
  override lazy val queryString: Map[String, Seq[String]] = uri.query.toMultiMap

  /** Sets the signature calculator for the request */
  // TODO: Not supported yet
  override def sign(calc: WSSignatureCalculator) = copy(calc = Some(calc))

  // TODO: Not supported yet
  override def withAuth(username: String, password: String, scheme: WSAuthScheme) =
    copy(auth = Some((username, password, scheme)))

  // TODO: Not supported yet
  override def withVirtualHost(virtualHost: String) = copy(virtualHost = Some(virtualHost))

  // TODO: Not supported yet
  override def withProxyServer(proxyServer: WSProxyServer) = copy(proxyServer = Some(proxyServer))

  /**
   * Adds any number of query string parameters to the request.
   */
  override def withQueryString(parameters: (String, String)*) = {
    // Unlike the built-in version this will also mean that [[url]] returns an actual,
    // up-to-date string that also includes these parameters! :)
    copy(uri = uri.withQuery(parameters.foldLeft(uri.query) {
      (query, parameter) => query.+:(parameter)
    }))
  }

  /**
   * Adds any number of HTTP headers
   */
  override def withHeaders(hdrs: (String, String)*) = {
    val headers = hdrs.foldLeft(this.headers)((m, hdr) =>
      if (m.contains(hdr._1)) m.updated(hdr._1, m(hdr._1) :+ hdr._2)
      else m + (hdr._1 -> Seq(hdr._2))
    )
    copy(headers = headers)
  }

  /** Sets the body for this request */
  override def withBody(body: WSBody) = {
    copy(body = body, entity = body match {
      case InMemoryBody(bytes) => HttpEntity(bytes)
      case _ => HttpEntity.Empty
    })
  }

  /** Sets whether redirects (301, 302, ..) should be followed automatically */
  override def withFollowRedirects(follow: Boolean) = copy(followRedirects = Some(follow))

  /** Sets the maximum time in milliseconds you expect the request to take. */
  // TODO: Cannot really implement this properly yet, because of https://github.com/akka/akka/issues/17346
  override def withRequestTimeout(timeout: Int) = copy(requestTimeout = Some(timeout))

  /** Sets the method for this request. */
  override def withMethod(method: String) = copy(method = method)

  /**
   * Executes this request
   */
  override def execute(): Future[WSResponse] = {
    import materializer.executionContext
    prepareAndExecute.flatMap({ response =>
      response.entity.dataBytes.runFold(ByteString())(_ ++ _).map({ body =>
        new AkkaWSResponse(response, body)
      })
    })
  }

  /**
   * Executes this request and streams the response body in an enumerator
   */
  override def stream(): Future[(WSResponseHeaders, Enumerator[Array[Byte]])] = {
    import materializer.executionContext
    prepareAndExecute.map({ response =>
      val source = response.entity.dataBytes.map(_.toByteBuffer.array())
      (new AkkaWSResponseHeaders(response), Streams.sourceAsEnumerator(source))
    })
  }

  // -------------------------------------------- Utility methods

  private def prepareAndExecute(implicit ec: ExecutionContext): Future[HttpResponse] = {
    val request = HttpRequest()
      .withMethod(
        HttpMethods.getForKey(method).getOrElse(
          HttpMethods.GET
        ))
      .withHeaders({
        for {
          (name, values)  <- headers.toList
          value           <- values
          header          <- HttpHeader.parse(name, value) match {
            case ParsingResult.Ok(header, _) => Some(header)
            case _ => None
          }
        } yield header
      })
      .withUri(uri)
      .withEntity(entity)
    Source.single(request)
      .via(connection)
      .runWith(Sink.head)
      .flatMap({ response => maybeFollowRedirects(response) })
  }

  /**
   * Follows redirects in the given response, if:
   *  1. the user wants us to do so (see [[withFollowRedirects()]]
   *  2. the response status is a redirection
   *  3. the response contains a `Location` header
   *
   * Otherwise we'll just return the given response again.
   */
  private def maybeFollowRedirects(response: HttpResponse)(implicit ec: ExecutionContext): Future[HttpResponse] = {
    val maybeRedirectLocation =
      if (followRedirects.contains(true) && response.status.isInstanceOf[Redirection]) {
        response.header[Location]
      } else {
        None
      }

    maybeRedirectLocation match {
      case Some(location) => copy(uri = location.uri).prepareAndExecute
      case _ => Future.successful(response)
    }
  }

}
