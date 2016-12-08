package models

import play.api.libs.json._


/**
  * A tasks is a process realised by an actor toward the creation of a response to a query
  */
case class Task(id: String, name: String, status: String, progress: Int)

/**
  * Represent a query send by the user
  * @param name Content of the query (request)
  * @param tasks List all tasks associated with this query
  */
case class Query(id: String, name: String, status: String, tasks: List[Task])

/**
  * Companion object for Query class, contains implicit converters for tasks and query
  */
object Query {
  implicit val taskWriters: Writes[Task] = Json.writes[Task]
  implicit val processWriters: Writes[Query] = Json.writes[Query]
}