package play.api.libs.ws.akka

import akka.actor.ActorSystem
import akka.stream.{ActorFlowMaterializer, FlowMaterializer}
import play.api.libs.concurrent.Akka
import play.api.Application
import play.api.libs.ws._

/**
 *
 */
class AkkaWSClient(actorSystem: ActorSystem, materializer: FlowMaterializer) extends WSClient {
  override def underlying[T]: T = this.asInstanceOf[T]

  /**
   * Generates a request holder which can be used to build requests.
   * @param url The base URL to make HTTP requests to.
   */
  override def url(url: String): WSRequestHolder = new AkkaWSRequestHolder(url)(actorSystem, materializer)
}

class AkkaWSAPI(actorSystem: ActorSystem, materializer: FlowMaterializer) extends WSAPI {
  override def client: WSClient = new AkkaWSClient(actorSystem, materializer)
  override def url(url: String): WSRequestHolder = client.url(url)
}

object AkkaWSAPI {
  def apply()(implicit actorSystem: ActorSystem) = new AkkaWSAPI(actorSystem, ActorFlowMaterializer())
}

/**
 * WSPlugin implementation hook.
 */
class AkkaWSPlugin(application: Application) extends WSPlugin {

  override private[ws] def loaded: Boolean = true

  override val enabled = true

  override def onStart() { }

  override def onStop() { }

  def api = new AkkaWSAPI(Akka.system(application), ActorFlowMaterializer()(Akka.system(application)))

}