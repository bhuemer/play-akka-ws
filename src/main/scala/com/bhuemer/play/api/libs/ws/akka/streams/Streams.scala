package com.bhuemer.play.api.libs.ws.akka.streams

import akka.stream.FlowMaterializer
import akka.stream.scaladsl.{Source, Sink}
import org.reactivestreams.Publisher
import play.api.libs.iteratee.Enumerator

import scala.concurrent.ExecutionContext

/**
 *
 */
object Streams {

  def sourceAsEnumerator[E](source: Source[E, _])(implicit materializer: FlowMaterializer, ec: ExecutionContext): Enumerator[E] =
    publisherAsEnumerator(source.runWith(Sink.publisher))(ec)

  def publisherAsEnumerator[E](publisher: Publisher[E])(implicit ec: ExecutionContext): Enumerator[E] =
    new PublisherEnumerator(publisher)(ec)

}
