package play.api.libs.ws.akka

import java.util.concurrent.atomic.{AtomicBoolean, AtomicReference, AtomicLong}

import akka.stream.FlowMaterializer
import akka.stream.scaladsl.{Sink, Source}
import org.reactivestreams.{Publisher, Subscriber, Subscription}
import play.api.libs.iteratee.Input.{EOF, El}
import play.api.libs.iteratee._
import play.api.libs.ws.akka.support.ProducerConsumer

import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.util.{Failure, Success, Try}

/**
 * Contains utility methods related to converting between reactive publishers/subscribers and enumerators/iteratees.
 *
 * @author Bernhard Huemer
 */
object Streams {

  // -------------------------------------------- Public methods

  def enumeratorAsPublisher[E](enumerator: Enumerator[E])(implicit ec: ExecutionContext): Publisher[E] = new Publisher[E] {
    override def subscribe(subscriber: Subscriber[_ >: E]): Unit = {
      subscriber.onSubscribe(new Subscription {
        private val initialised = new AtomicBoolean(false)
        private val iteratee = new SubscriberIteratee[E](subscriber)

        override def cancel(): Unit = {
          iteratee.cancel()
        }

        override def request(n: Long): Unit = {
          if (initialised.compareAndSet(false, true)) {
            enumerator.run(iteratee)
          }

          iteratee.request(n)
        }
      })
    }
  }

  def sourceAsEnumerator[E](source: Source[E, _])(implicit materializer: FlowMaterializer, ec: ExecutionContext): Enumerator[E] =
    publisherAsEnumerator(source.runWith(Sink.publisher))(ec)

  def publisherAsEnumerator[E](publisher: Publisher[E])(implicit ec: ExecutionContext): Enumerator[E] = new Enumerator[E] {
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

  // -------------------------------------------- Utility classes

  private class SubscriberIt[E](subscriber: Subscriber[_ >: E]) {

    private val states = new ProducerConsumer[Iteratee[E, Unit]]()

    /**
     * Depending on the number of elements that have been requested from the subscriber, this will
     * return a different state for an iteratee. Either we'll wait for these requests to happen, via
     * the subscription, or we'll keep on consuming elements from the enumerator.
     */
    def nextState(implicit ec: ExecutionContext): Iteratee[E, Unit] = Iteratee.flatten(states.take)

    /**
     * This is the state the iteratee is in when we know that we can expect more input.
     */
    def consumeElements(): Iteratee[E, Unit] = new Iteratee[E, Unit] {
      override def fold[B](folder: Step[E, Unit] => Future[B])(implicit ec: ExecutionContext): Future[B] = {
        folder(Step.Cont({
          case in @ Input.El(elem) =>
            subscriber.onNext(elem)
            nextState

          case in @ Input.EOF =>
            subscriber.onComplete()
            Done((), in)

          // This one we'll just ignore and stay in the current state
          case Input.Empty => consumeElements()
        }))
      }

      def request(n: Long): Unit = {
        (0l until n) foreach { _ =>
          states.offer(consumeElements())
        }
      }

      def cancel(): Unit = {
        states.offerFirst(Done((), Input.Empty))
      }
    }

  }

  private class SubscriberIteratee[E](subscriber: Subscriber[_ >: E]) extends Iteratee[E, Any] with Subscription {

    private val numberOfRequested = new AtomicLong()
    private val iterateeRef = new AtomicReference(Promise[Iteratee[E, Any]]())

    // ------------------------------------------ Iteratee methods

    override def fold[B](folder: Step[E, Any] => Future[B])(implicit ec: ExecutionContext): Future[B] = {
      val next = Iteratee.flatten(iterateeRef.get.future)
      folder(Step.Cont({
        case Input.El(elem) =>
          subscriber.onNext(elem)
          next

        case Input.EOF      =>
          subscriber.onComplete()
          next

        case Input.Empty    =>
          next
      }))
    }

    // ------------------------------------------ Subscription methods

    override def request(n: Long): Unit = {
      if (numberOfRequested.addAndGet(n) > 0) {

      }
    }

    override def cancel(): Unit = {

    }

  }

  /**
   * @param k the initial continuation for the iteratee, if we're not in the Cont state, we can't create such a subscriber
   * @param outcome the promise that we'll fulfill eventually with the final state of the iteratee
   */
  private class IterateeSubscriber[E, A](
      @volatile private var k: Input[E] => Iteratee[E, A],
      outcome: Promise[Iteratee[E, A]])(implicit ec: ExecutionContext) extends Subscriber[E] {

    private val subscriptionRef: AtomicReference[Subscription] = new AtomicReference(null)

    // -------------------------------------------- Subscriber methods

    override def onSubscribe(sub: Subscription): Unit = {
      if (subscriptionRef.compareAndSet(null, sub)) {
        requestNext()
      } else {
        // This shouldn't happen ..
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

}
