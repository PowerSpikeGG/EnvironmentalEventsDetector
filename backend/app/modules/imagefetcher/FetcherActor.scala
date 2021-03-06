package modules.imagefetcher

import java.io.{File, FileOutputStream}

import akka.actor.{Actor, ActorRef, Props}
import akka.pattern.ask
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.Sink
import akka.util.{ByteString, Timeout}
import models.Task
import modules.scheduler.MonitoringActor.{StartTask, UpdateTask}
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import play.api.libs.ws.WSClient

import scala.concurrent.Future
import scala.concurrent.duration._
import scala.util.Random

object FetcherActor {

  /**
    * @param ws         WebService to send http request to the flask server
    * @param serverUrl  Url of the flask server
    * @param monitoring A reference to the monitoring actor
    */
  def props(ws: WSClient, serverUrl: String, monitoring: ActorRef): Props = Props(new FetcherActor(ws, serverUrl, monitoring))

  // Messages definitions
  case class FetchRGB(date: String, place: String, scale: Option[Double], queryId: String)

  case class FetchForestDiff(start: String, stop: String, place: String, scale: Option[Double], queryId: String)

  case class FetchResponse(url: String)

  case class DownloadFile(url: String, queryId: String, outputFolder: String)

  case class Downloaded(file: File)

}

class FetcherActor(ws: WSClient, serverUrl: String, monitoring: ActorRef) extends Actor {

  // Import implicit definition
  import FetcherActor._

  implicit val timeout: Timeout = 5.seconds

  implicit val materializer = ActorMaterializer()

  /**
    * Message handling
    */
  override def receive: Receive = {
    case message: FetchRGB => fetchRGB(message)
    case message: FetchForestDiff => fetchForestDiff(message)
    case DownloadFile(url, queryId, outputFolder) => downloadFile(url, queryId, outputFolder)
    case _ => sender() ! "Image Fetcher not yet implemented"
  }

  /**
    * Ask the flask server to generate an url to download a RGB image
    * @param message Contains all the information about the request
    */
  def fetchRGB(message: FetchRGB): Unit = {
    val task = monitoring.ask(StartTask(message.queryId, "Fetching image"))(50.seconds).mapTo[Task]

    val initialParams = Seq(
      ("date", message.date),
      ("place", message.place))

    val params =
      if (message.scale.isDefined) initialParams :+ ("scale", message.scale.get.toString)
      else initialParams

    fetchImage("/rgb", params, task, message.queryId)
  }

  /**
    * Ask the flask server to generate an url to download a Forest Diff image
    * @param message Contains all the information about the request
    */
  def fetchForestDiff(message: FetchForestDiff): Unit = {
    val task = monitoring.ask(StartTask(message.queryId, "Fetching image"))(50.seconds).mapTo[Task]

    val initialParams = Seq(
      ("start", message.start),
      ("stop", message.stop),
      ("place", message.place))

    val params =
      if (message.scale.isDefined) initialParams :+ ("scale", message.scale.get.toString)
      else initialParams

    fetchImage("/forestDiff", params, task, message.queryId)
  }

  /**
    * Request the flask server to process image from Earth Engine and return the url to download the image
    *
    * @param url Which url to ask for on the flask server
    * @param params Parameters added to the Get request
    * @param task Reference to the task monitoring this computation
    * @param queryId Id of the query which triggered this request
    */
  def fetchImage(url: String, params: Seq[(String, String)], task: Future[Task], queryId: String): Unit = {
    val updatedTask = task.flatMap(task => monitoring
      .ask(UpdateTask(queryId, task.id, Some("Fetching"), Some(5), Some(params.toMap)))
      .mapTo[Task])

    val request = ws.url(serverUrl + url)
      .withQueryString(params: _*)
      .get()

    val currentSender = sender

    request.map(response =>
      if (response.status == 200) {
        val url = (response.json \ "href").as[String]
        updatedTask map (t => monitoring ? UpdateTask(queryId, t.id, Some("Image generated on Earth Engine"), Some(100),
                                               Some(t.metadata + ("url" -> url))))
        currentSender ! FetchResponse(url)
      } else {
        val error = (response.json \ "error").asOpt[String]
        updatedTask map (t => monitoring ? UpdateTask(queryId, t.id, Some("Link generation failed: " + error.getOrElse("no details")), Some(0)))
      }
    )

    request.onFailure {
      case _ => updatedTask map (t => monitoring ? UpdateTask(queryId, t.id, Some("Connection to server (flask) failed"), Some(0)))
    }
  }

  /**
    * Download a file from an url through an akka stream and place it in the downloaded folder
    * (Downloaded file is not labeled yet, we use a random id)
    *
    * @param url          Url from which to download the file
    * @param processId    Identifier of the process to which this download is related (for monitoring purpose)
    * @param outputFolder Folder in which the file will be downloaded
    */
  def downloadFile(url: String, processId: String, outputFolder: String): Unit = {
    val task = monitoring.ask(StartTask(processId, "Downloading image")).mapTo[Task]
    val currentSender = sender

    // Create the directory to store downloaded files
    new File(outputFolder).mkdir()
    val file = new File(outputFolder + "/" + Random.nextInt() + ".zip")
    val response = ws.url(url).withMethod("GET").stream()

    response.flatMap(res => {
      task.map(t => monitoring ? UpdateTask(processId, t.id, Some("Download started"), Some(20)))

      val outputStream = new FileOutputStream(file)
      val sink = Sink.foreach[ByteString](bytes => outputStream.write(bytes.toArray))

      res.body.runWith(sink).andThen {
        case _ =>
          outputStream.close()
          val metadata = Map(
            "url" -> url,
            "outputFolder" -> outputFolder,
            "file" -> file.getName
          )
          task.map(t => monitoring ? UpdateTask(processId, t.id, Some("Download finished"),
                                                Some(100), Some(metadata)))
          currentSender ! Downloaded(file)
      }
    })
  }
}
