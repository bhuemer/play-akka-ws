package play.api.libs.ws.akka.streams

import java.util.concurrent.atomic.AtomicReference

import org.reactivestreams.{Subscription, Subscriber}
import play.api.libs.iteratee.Input.{EOF, El}
import play.api.libs.iteratee._

import scala.concurrent.{ExecutionContext, Promise}
import scala.util.{Failure, Success, Try}

/**
 *
 * @param k the initial continuation for the iteratee, if we're not in the Cont state, we can't create such a subscriber
 * @param outcome the promise that we'll fulfill eventually with the final state of the iteratee
 * @tparam E
 * @tparam A
 */
class IterateeSubscriber[E, A](@volatile private var k: Input[E] => Iteratee[E, A], outcome: Promise[Iteratee[E, A]])(implicit ec: ExecutionContext)
    extends Subscriber[E] {

  private val subscriptionRef: AtomicReference[Subscription] = new AtomicReference(null)

  // -------------------------------------------- Subscriber methods

  override def onSubscribe(sub: Subscription): Unit = {
    if (subscriptionRef.compareAndSet(null, sub)) {
      requestNext()
    } else {
      sub.cancel()
    }
  }
  
  override def onNext(element: E): Unit = {
    // Note that this method assumes that it won't be called if we haven't requested anything yet / it won't be
    // called more often than we requested yet. Of course, that's exactly what the reactive subscriber trait
    // is supposed to guarantee anyway. We're just not doing any safety checks in addition.
    k(El(element)).pureFold({
      case Step.Cont(nextK) => 
        k = nextK
        requestNext()
      case otherwise => completeWith(Success(otherwise.it))
    })(ec)
  }

  /** If the upstream publisher received an error, we'll just pass it along. */
  override def onError(t: Throwable): Unit = completeWith(Failure(t))

  override def onComplete(): Unit = completeWith(Success(k(EOF)))

  // -------------------------------------------- Utility methods

  protected def requestNext(): Unit = {
    val subscription = Option(subscriptionRef.get()).getOrElse(
      throw new IllegalStateException(
        "Tried to access the current subscription without it being initialized yet.")
    )

    subscription.request(1)
  }
  
  private def completeWith(result: Try[Iteratee[E, A]]): Unit = {
    // No matter whether we've encountered a Done or Error state for the
    // next iteratee, we need to cancel the subscription in any case.
    Option(subscriptionRef.get()).foreach(_.cancel())
    outcome.complete(result)
  }

}