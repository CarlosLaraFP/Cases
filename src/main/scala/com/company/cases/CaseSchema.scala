package com.company.cases

import Validation._
import caliban.schema.Annotations.GQLDescription
import zio.stream.ZStream

import java.util.UUID

/*
  We use the Caliban library to define the GraphQL schema, which has a built-in facility to generate the schema from our Scala data types.
  API input should be a date string format that GraphQL understands, such as the ISO 8601 standard date format yyyy-MM-dd.
 */
case class ListCases(status: CaseStatus, created: Option[String])
case class CreateCase(name: String, dateOfBirth: String, dateOfDeath: Option[String])
case class UpdateCase(id: UUID, status: CaseStatus)
case class DeleteCase(id: UUID)
case class ModifyTable(action: TableAction)
sealed trait TableAction
object TableAction {
  case object Create extends TableAction
  case object Delete extends TableAction
  case object Clear extends TableAction
}
case class Mutation(
  result: String,
  caseId: Option[String],
  caseStatus: Option[CaseStatus]
)
case class CaseStatusChanged(
  id: UUID,
  status: CaseStatus
)

final case class Queries(
  @GQLDescription("List all cases with a specific status and optional created date")
  listCases: ListCases => Result[List[Case]]
)

final case class Mutations(
  @GQLDescription("Create or delete cases table")
  modifyTable: ModifyTable => Result[Mutation],

  @GQLDescription("Create a new case")
  createCase: CreateCase => Result[Mutation],

  @GQLDescription("Update the status of a case")
  updateCase: UpdateCase => Result[Mutation],

  @GQLDescription("Delete a case based on UUID")
  deleteCase: DeleteCase => Result[Mutation]
)

final case class Subscriptions(
  @GQLDescription("Subscribe to changes in case status")
  caseStatusChanged: ZStream[Any, Throwable, CaseStatusChanged]
)
// Hub, ZStream, ZPipeline, ZSink
