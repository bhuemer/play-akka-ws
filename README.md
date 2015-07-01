# Play Akka WS

An implementation of the Play framework's web service API based on Akka HTTP and Akka Streams. At the moment we don't
provide any additional features than what's offered in the standard API, but soon we'll add, for example, the ability
to stream request bodies, i.e. the converse of returning an ``Enumerator`` in a response.

# Usage

```scala
// It can be used exactly like you know it from the Play web service library already,
// but with the nice difference that you don't even have to have a running Play
// application instance.
def searchRepositories(query: String)(implicit system: ActorSystem): Future[JsValue] = {
  import system.dispatcher
  AkkaWSAPI()
    .url("https://api.github.com/search/repositories")
    .withQueryString(
      "q" -> query
    )
    .get()
    .map(_.json)
}

```