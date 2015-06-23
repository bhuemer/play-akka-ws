package play.api.libs.ws.akka.support

import java.util.concurrent.atomic.AtomicBoolean

import akka.stream.FlowMaterializer
import akka.stream.scaladsl.{Sink, Source}
import org.reactivestreams.{Publisher, Subscriber, Subscription}
import play.api.libs.iteratee.{Done, Error, Enumerator, Input, Iteratee, Step}

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
      val nextIt = Promise[Iteratee[E, A]]()
      publisher.subscribe(new IterateeSubscriber(it, nextIt)(ec))
      nextIt.future
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

  private class IterateeSubscriber[E, A](it0: Iteratee[E, A], outcome: Promise[Iteratee[E, A]])(implicit ec: ExecutionContext) extends Subscriber[E] {

    @volatile private var currentState: Subscriber[E] = WaitingForSubscription

    // ------------------------------------------ Subscriber methods

    // They always simply delegate to the current state's implementations
    override def onSubscribe(s: Subscription): Unit = currentState.onSubscribe(s)
    override def onNext(e: E): Unit                 = currentState.onNext(e)
    override def onComplete(): Unit                 = currentState.onComplete()
    override def onError(t: Throwable): Unit        = currentState.onError(t)

    // ------------------------------------------ Subscription states

    object WaitingForSubscription extends Subscriber[E] {
      override def onSubscribe(subscription: Subscription): Unit = {
        // Now that we have our subscription, use that and the initial state of the iteratee
        // to determine the new current state of this subscriber. Either we'll continue receiving
        // elements (if it's a Cont iteratee) or we'll just finish the subscription right away.
        GainedSubscription(subscription).determineState(it0)
      }

      override def onError(error: Throwable): Unit = onNotYetSubscribed
      override def onNext(elem: E): Unit           = onNotYetSubscribed
      override def onComplete(): Unit              = onNotYetSubscribed

      def onNotYetSubscribed: Nothing = throw new IllegalStateException(
        "No subscription has been provided yet, so we shouldn't receive " +
          "any of these onError/onNext/onComplete callbacks.")
    }

    case class GainedSubscription(subscription: Subscription) {

      /**
       * Takes the given Iteratee and determines the current state for this overall subscriber.
       */
      def determineState(it: Iteratee[E, A], mostRecentInput: Input[E] = Input.Empty): Unit = {
        it.pureFold({
          case Step.Cont(k) =>
            currentState = Active(k, mostRecentInput)

            // We simply request elements one-by-one
            subscription.request(1)

          // The Iteratee already is either in the Done or Error state
          case otherwise =>
            completeWith(Success(otherwise.it))

        }).onFailure({
          case error => completeWith(Failure(new RuntimeException(
            s"After processing $mostRecentInput eventually we encountered the exception: ${error.getMessage}, \n$error", error)))
        })
      }

      def completeWith(result: Try[Iteratee[E, A]]): Unit = {
        subscription.cancel()
        currentState = Completed
        outcome.tryComplete(result)
      }

      /** Handles superfluous onSubscribe callbacks in states where we have a subscription already. */
      def onAlreadySubscribed: Nothing = throw new IllegalStateException(
        "Please don't use this subscriber for more than one subscription. You cannot subscribe to two publishers " +
          "at the same time with this anyway and if you want to subscribe to many publishers sequentially, " +
          "then please just use the Iteratee that came out of this one and create another subscriber wrapper " +
          "around it.")

      // Like a ContIteratee
      case class Active(k: Input[E] => Iteratee[E, A], mostRecentInput: Input[E]) extends Subscriber[E] {
        override def onSubscribe(s: Subscription): Unit = onAlreadySubscribed
        override def onNext(elem: E): Unit              = determineState(k(Input.El(elem)), Input.El(elem))
        override def onComplete(): Unit                 = determineState(k(Input.EOF), Input.EOF)
        override def onError(error: Throwable): Unit    = determineState(
          Error(s"After processing $mostRecentInput eventually " +
            s"we encountered the exception: ${error.getMessage}, \n$error", mostRecentInput), mostRecentInput)
      }

      // Like either a DoneIteratee or ErrorIteratee. In the world of reactive streams, there's no distinction.
      object Completed extends Subscriber[E] {
        override def onSubscribe(s: Subscription): Unit = onAlreadySubscribed
        override def onError(error: Throwable): Unit = ()
        override def onNext(elem: E): Unit = ()
        override def onComplete(): Unit = ()
      }

    }
  }

}