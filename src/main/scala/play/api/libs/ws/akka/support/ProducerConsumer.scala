package play.api.libs.ws.akka.support

import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.atomic.AtomicReference
import java.util.function.UnaryOperator
import scala.concurrent.{ExecutionContext, Promise, Future}

/**
 *
 */
class ProducerConsumer[A] {

  private val elements = new ConcurrentLinkedDeque[A]()

  /**
   * Consumers will listen to the future of this promise so that they get "woken up" whenever this
   * collection will be filled somehow. In doing so, no operation needs to block.
   */
  private val notEmpty: AtomicReference[Option[Promise[Unit]]] = new AtomicReference(None)

  // -------------------------------------------- Public methods

  /** Inserts the element at the back of this queue, like [[offerLast()]] */
  def offer(element: A): Unit = offerWith(_.offer(element))

  /**
   * Inserts the element at the front of this queue. The next time someone calls [[take()]] this element
   * will be returned then.
   */
  def offerFirst(element: A): Unit = offerWith(_.offerFirst(element))

  /** Inserts the element at the back of this queue, like [[offer()]] */
  def offerLast(element: A): Unit = offerWith(_.offerLast(element))

  @inline private def offerWith(body: ConcurrentLinkedDeque[A] => Boolean): Boolean = {
    val result = body(elements)
    // If a promise had already been created, succeed it, so that pending futures
    // returned from [[take()]] can try again to poll values from the dequeue. Unfortunately,
    // this will fire them all at once - the whole concept is like wait/notifyAll, in a way,
    // which is not ideal. However, at the moment, we only ever have 1 producer and 1 consumer,
    // who will only ever wait for one element at the same time at most .. so it's not as big
    // of a deal. I just wouldn't reuse this class in a more general way without improving it
    // beforehand.
    notEmpty.getAndSet(None).foreach(_.success(()))
    result
  }

  /**
   * Note that the way this is implemented, we don't offer any kind of order ..
   *
   * #1 offer
   * #1 take -> receives #1
   * #2 take
   * #3 take
   * #2 offer -> might cause #3 take to receive this
   * #4 take
   * #3 offer -> might cause #4 take to receive this
   * #4 offer -> might cause #2 take to receive this
   *
   * This only guarantees that each take will correspond to one and only one offer.
   */
  def take(implicit ec: ExecutionContext): Future[A] = {
    val result = elements.poll()
    if (result != null) {
      Future.successful(result)
    } else {
      // If someone already created a promise to listen on, use that one. We're
      // doing something like a wait/notifyAll here, so all consumers waiting for
      // more elements will be woken up at once.
      val promise = notEmpty.updateAndGet(unary({
        case Some(previous) => Some(previous)
        case None           => Some(Promise[Unit]())
      }))

      promise.get.future.flatMap({ _ =>
        // .. now try again, only one of the consumers will succeed though.
        take
      })
    }
  }

  private def unary[T](body: T => T): UnaryOperator[T] = new UnaryOperator[T] {
    override def apply(t: T): T = body(t)
  }

}
