package play.api.libs.ws.akka

import java.util.concurrent.atomic.{AtomicBoolean, AtomicReference}

import akka.stream.FlowMaterializer
import akka.stream.scaladsl.{Sink, Source}
import org.reactivestreams.{Publisher, Subscriber, Subscription}
import play.api.libs.iteratee.{Iteratee, Enumerator, Done, Input, Step}
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
      val iteratee = new SubscriberIteratee[E](subscriber)
      subscriber.onSubscribe(new Subscription {
        private val initialised = new AtomicBoolean(false)

        /** Whenever the subscriber wants to cancel the subscription, make sure that the Iteratee transitions into `Done`. */
        override def cancel(): Unit = iteratee.cancel()

        /**  */
        override def request(n: Long): Unit = {
          iteratee.request(n)

          // Once the subscriber has requested at least one element, close the circuit by passing the Iteratee
          // that we have created to the enumerator that was given. This basically makes sure that we only bother
          // to evaluate the Enumerator in case we actually need to.
          if (initialised.compareAndSet(false, true)) {
            enumerator.run(iteratee)
              .onFailure({
                // TODO: Is it okay to deliver this onError callback even if the subscriber cancelled the subscription already?
                case error => subscriber.onError(error)
              })
          }
        }
      })
    }
  }

  def enumeratorAsSource[E](enumerator: Enumerator[E])(implicit ec: ExecutionContext): Source[E, Unit] = Source(enumeratorAsPublisher(enumerator))

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

  private class SubscriberIteratee[E](subscriber: Subscriber[_ >: E]) extends Iteratee[E, Unit] { self =>

    private val states = new ProducerConsumer[Iteratee[E, Unit]]()

    /**
     * Will always delegate to our dequeue of iteratee states.
     *
     * Depending on the number of elements that have been requested from the subscriber, this will either return
     * a pending future that may/may not consume elements eventually, or it's an immediate consumeElements() /
     * Done state.
     */
    override def fold[B](folder: (Step[E, Unit]) => Future[B])(implicit ec: ExecutionContext): Future[B] = {
      states.take.flatMap({ it =>
        // `it` represents the current state of this Iteratee, usually either what's returned by
        // `consumeElements()` or it's the Done state. Error states cannot really occur, because subscribers
        // cannot propagate those back to publishers.
        it.fold(folder)
      })
    }

    /**
     * Will be called whenever the subscriber requests more elements via its subscription.
     */
    def request(n: Long): Unit = {
      (0l until n) foreach { _ =>
        states.offer(consumeElements())
      }
    }

    def cancel(): Unit = {
      // Skip any other consuleElements() states that are enqueued already and go into the Done state as
      // quickly as possible. There might still be one #fold happening that leads to a call to
      // consumeElements() -> subscriber.onNext(), but that can only happen, if the subscriber requested
      // more than one element and some are still in-flight while he/she decided to cancel the subscription.
      states.offerFirst(Done((), Input.Empty))
    }

    /**
     * This is the state the iteratee is in when we know that we can expect more input.
     */
    private def consumeElements(): Iteratee[E, Unit] = new Iteratee[E, Unit] {
      override def fold[B](folder: Step[E, Unit] => Future[B])(implicit ec: ExecutionContext): Future[B] = {
        folder(Step.Cont({
          case in@Input.El(elem) =>
            subscriber.onNext(elem)
            self

          case in@Input.EOF =>
            subscriber.onComplete()
            Done((), in)

          // This one we'll just ignore and stay in the current state
          case Input.Empty => consumeElements()
        }))
      }
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
      k(Input.El(element)).pureFold({
        case Step.Cont(nextK) =>
          k = nextK
          requestNext()
        case otherwise => completeWith(Success(otherwise.it))
      })(ec)
    }

    /** If the upstream publisher received an error, we'll just pass it along. */
    override def onError(t: Throwable): Unit = completeWith(Failure(t))

    override def onComplete(): Unit = completeWith(Success(k(Input.EOF)))

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