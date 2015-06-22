package play.api.libs.ws.akka.streams

import org.reactivestreams.Publisher
import play.api.libs.iteratee.{Step, Iteratee, Enumerator}

import scala.concurrent.{Promise, Future, ExecutionContext}

class PublisherEnumerator[E](publisher: Publisher[E])(implicit ec: ExecutionContext) extends Enumerator[E] {
  override def apply[A](it: Iteratee[E, A]): Future[Iteratee[E, A]] = {
    it.fold({
      case Step.Cont(k) =>
        val nextIt = Promise[Iteratee[E, A]]()
        publisher.subscribe(new IterateeSubscriber(k, nextIt)(ec))
        nextIt.future

      // If the iteratee is in a Done/Error state, we don't even need to create a subscription
      case otherwise => Future.successful(otherwise.it)
    })(ec)
  }
}