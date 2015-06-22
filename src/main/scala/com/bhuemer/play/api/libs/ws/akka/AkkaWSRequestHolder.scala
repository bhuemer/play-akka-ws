package com.bhuemer.play.api.libs.ws.akka

import akka.http.scaladsl.model.StatusCodes.Redirection
import akka.http.scaladsl.model.headers.Location
import akka.http.scaladsl.model.{HttpResponse, HttpMethod, HttpMethods, HttpRequest}
import akka.stream.FlowMaterializer
import akka.stream.scaladsl.{Flow, Sink, Source}
import akka.util.ByteString
import com.bhuemer.play.api.libs.ws.akka.streams.Streams
import play.api.libs.iteratee.Enumerator
import play.api.libs.ws.{WSResponseHeaders, WSResponse}

import scala.concurrent.Future

/**
 *
 *
 */
case class AkkaWSRequestHolder(connection: Flow[HttpRequest, HttpResponse, _], request: HttpRequest)(implicit materializer: FlowMaterializer) {

  import materializer.executionContext

  private def using(f: HttpRequest => HttpRequest): AkkaWSRequestHolder = copy(request = f(request))

  def withUri(uri: akka.http.scaladsl.model.Uri): AkkaWSRequestHolder = using {
    _.withUri(uri)
  }

  def withMethod(method: String): AkkaWSRequestHolder = withMethod(
    HttpMethods.getForKey(method).getOrElse(HttpMethods.GET)
  )
  def withMethod(method: HttpMethod): AkkaWSRequestHolder = using {
    _.withMethod(method)
  }

  def get(): Future[WSResponse] = withMethod(HttpMethods.GET).execute()
  def getStream(): Future[(WSResponseHeaders, Enumerator[Array[Byte]])] = {
    withMethod(HttpMethods.GET).stream()
  }

  def execute(): Future[WSResponse] =
    executeRequest().flatMap({ response =>
      response.entity.dataBytes.runFold(ByteString())(_ ++ _).map({ body =>
        new AkkaWSResponse(response, body)
      })
    })

  def stream(): Future[(WSResponseHeaders, Enumerator[Array[Byte]])] = {
    executeRequest().map({ response =>
      val source = response.entity.dataBytes.map(_.toByteBuffer.array())
      (new AkkaWSResponseHeaders(response), Streams.sourceAsEnumerator(source))
    })
  }

  private def executeRequest(): Future[HttpResponse] = {
    Source.single(request)
      .via(connection)
      .runWith(Sink.head)
      .flatMap(followRedirects)
  }

  private def followRedirects(response: HttpResponse): Future[HttpResponse] = {
    val maybeRedirectLocation =
      if (response.status.isInstanceOf[Redirection]) {
        response.header[Location]
      } else {
        None
      }
    maybeRedirectLocation match {
      case Some(location) => withUri(location.uri).executeRequest()
      case _              => Future.successful(response)
    }
  }

}
